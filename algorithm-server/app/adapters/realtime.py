"""可插拔实时测斜适配器。

适配器只负责传输和事件语义，轨迹计算仍由 ``survey_io`` 完成。每个事件
先经过幂等/乱序整理器，再交给井场级增量导入事务；因此 WITS TCP、串口和
SQL Server 可以替换而不改变上层 API。
"""
from __future__ import annotations

from dataclasses import dataclass, asdict
from datetime import datetime, timezone
import csv
import io
import json
import socket
import threading
import time
from collections import deque
from typing import Any, Iterable, Iterator, Mapping, Protocol


@dataclass(frozen=True)
class SurveyEvent:
    source: str
    well_id: str
    md: float
    inclination: float
    azimuth: float
    timestamp: str | None = None
    sequence: str | None = None
    raw: dict[str, Any] | None = None

    @property
    def idempotency_key(self) -> tuple[str, str, str, float]:
        # Sequence is preferred when a source provides it; MD remains in the
        # key so two stations at different depths never collapse accidentally.
        return (self.source, self.well_id, self.sequence or "", round(self.md, 6))

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class IngestDecision:
    accepted: tuple[SurveyEvent, ...]
    duplicate: bool = False
    out_of_order: bool = False
    reason: str | None = None


class EventSink(Protocol):
    def ingest(self, event: SurveyEvent) -> IngestDecision: ...


class ReorderDeduplicator:
    """Per-source MD ordering and duplicate guard.

    A small watermark is used rather than dropping every late packet: packets
    within ``reorder_window`` metres are emitted in MD order, while packets
    older than the watermark are isolated as late data for audit/replay.
    """

    def __init__(self, reorder_window: float = 3.0, max_seen: int = 100_000):
        if reorder_window < 0:
            raise ValueError("reorder_window must be non-negative")
        if max_seen < 1:
            raise ValueError("max_seen must be positive")
        self.reorder_window = float(reorder_window)
        self.max_seen = int(max_seen)
        self._seen: set[tuple[str, str, str, float]] = set()
        self._seen_order: deque[tuple[str, str, str, float]] = deque()
        self._buffers: dict[tuple[str, str], list[SurveyEvent]] = {}
        self._watermark: dict[tuple[str, str], float] = {}

    def ingest(self, event: SurveyEvent) -> IngestDecision:
        if event.md < 0 or not 0 <= event.inclination <= 180:
            return IngestDecision((), reason="invalid station")
        key = event.idempotency_key
        if key in self._seen:
            return IngestDecision((), duplicate=True, reason="duplicate event")
        source_key = (event.source, event.well_id)
        watermark = self._watermark.get(source_key, float("-inf"))
        if event.md < watermark - self.reorder_window:
            self._remember(key)
            return IngestDecision((), out_of_order=True, reason="outside reorder window")
        # Once a station has been emitted, accepting a lower MD would expose
        # it after a newer station and break the monotonic stream consumed by
        # incremental survey versioning.  Keep it as an auditable late packet;
        # callers may replay it with a new stream after correcting the source.
        if event.md < watermark:
            self._remember(key)
            return IngestDecision((), out_of_order=True, reason="before emitted watermark")
        self._remember(key)
        buffer = self._buffers.setdefault(source_key, [])
        buffer.append(event)
        buffer.sort(key=lambda value: (value.md, value.timestamp or "", value.sequence or ""))
        emit_before = max(watermark, event.md - self.reorder_window)
        emitted = [value for value in buffer if value.md <= emit_before]
        # A stream must make progress even when stations arrive more closely
        # spaced than the depth window.  Once a second packet is buffered, the
        # earliest station is stable relative to every packet currently seen;
        # emit it while retaining the newest packet for possible reordering.
        if not emitted and len(buffer) > 1:
            emitted = [buffer[0]]
        self._buffers[source_key] = [value for value in buffer if value.md > emit_before]
        if emitted:
            emitted_keys = {value.idempotency_key for value in emitted}
            self._buffers[source_key] = [
                value for value in self._buffers[source_key]
                if value.idempotency_key not in emitted_keys
            ]
        if emitted:
            self._watermark[source_key] = max(watermark, emitted[-1].md)
        return IngestDecision(tuple(emitted))

    def _remember(self, key: tuple[str, str, str, float]) -> None:
        self._seen.add(key)
        self._seen_order.append(key)
        while len(self._seen_order) > self.max_seen:
            self._seen.discard(self._seen_order.popleft())

    def flush(self, source: str | None = None, well_id: str | None = None) -> tuple[SurveyEvent, ...]:
        emitted: list[SurveyEvent] = []
        for key, values in list(self._buffers.items()):
            if source is not None and key[0] != source:
                continue
            if well_id is not None and key[1] != well_id:
                continue
            emitted.extend(sorted(values, key=lambda value: value.md))
            if values:
                self._watermark[key] = max(self._watermark.get(key, float("-inf")), max(v.md for v in values))
            self._buffers[key] = []
        return tuple(emitted)


@dataclass(frozen=True)
class RealtimeBatchResult:
    """Result of normalizing one source batch.

    Invalid records are isolated per record so one malformed packet does not
    discard a valid batch.  Persistence of accepted stations and their quality
    issues remains the responsibility of the Java project transaction.
    """

    accepted: tuple[SurveyEvent, ...]
    duplicates: int = 0
    out_of_order: int = 0
    invalid: tuple[dict[str, Any], ...] = ()
    flushed: tuple[SurveyEvent, ...] = ()
    late_events: tuple[SurveyEvent, ...] = ()

    def to_dict(self) -> dict[str, Any]:
        return {
            "accepted": [event.to_dict() for event in self.accepted],
            "duplicates": self.duplicates,
            "out_of_order": self.out_of_order,
            "out_of_order_events": [event.to_dict() for event in self.late_events],
            "invalid": list(self.invalid),
            "flushed": [event.to_dict() for event in self.flushed],
        }


class RealtimeIngestService:
    """Thread-safe in-process event coordinator for the FastAPI boundary.

    Connector instances can feed this service directly, while HTTP clients
    use the same semantics through ``/api/algorithm/realtime/ingest``.  The
    service deliberately does not write project tables: callers receive the
    accepted events and commit them with their project-level transaction.
    """

    def __init__(self, default_reorder_window: float = 3.0, max_seen: int = 100_000):
        if default_reorder_window < 0:
            raise ValueError("default_reorder_window must be non-negative")
        self.default_reorder_window = float(default_reorder_window)
        self.max_seen = int(max_seen)
        self._streams: dict[str, ReorderDeduplicator] = {}
        self._lock = threading.RLock()

    def _stream(self, source: str, reorder_window: float | None) -> ReorderDeduplicator:
        window = self.default_reorder_window if reorder_window is None else float(reorder_window)
        if window < 0:
            raise ValueError("reorder_window must be non-negative")
        stream = self._streams.get(source)
        if stream is None:
            stream = ReorderDeduplicator(window, self.max_seen)
            self._streams[source] = stream
        elif reorder_window is not None and stream.reorder_window != window:
            raise ValueError("reorder_window cannot change for an active source")
        return stream

    def ingest(
        self,
        source: str,
        records: Iterable[Mapping[str, Any]],
        *,
        default_well_id: str = "",
        reorder_window: float | None = None,
        flush: bool = False,
        replay: bool = False,
    ) -> RealtimeBatchResult:
        source = str(source).strip()
        if not source:
            raise ValueError("source is required")
        accepted: list[SurveyEvent] = []
        invalid: list[dict[str, Any]] = []
        duplicate_count = 0
        out_of_order_count = 0
        late_events: list[SurveyEvent] = []
        with self._lock:
            if replay:
                # A durable caller (the Java gateway) may be replaying rows
                # that a previous request already put into this process's
                # buffer.  Use a fresh stream for that batch; otherwise a
                # retry could be reported as a duplicate forever after a
                # partial database commit.
                window = self.default_reorder_window if reorder_window is None else float(reorder_window)
                if window < 0:
                    raise ValueError("reorder_window must be non-negative")
                stream = ReorderDeduplicator(window, self.max_seen)
            else:
                stream = self._stream(source, reorder_window)
            for index, record in enumerate(records):
                try:
                    event = normalize_event(record, source, default_well_id)
                    decision = stream.ingest(event)
                except (TypeError, ValueError) as exc:
                    invalid.append({"index": index, "reason": str(exc)})
                    continue
                accepted.extend(decision.accepted)
                duplicate_count += int(decision.duplicate)
                out_of_order_count += int(decision.out_of_order)
                if decision.out_of_order:
                    late_events.append(event)
            flushed = stream.flush(source=source) if flush else ()
        return RealtimeBatchResult(tuple(accepted), duplicate_count,
                                   out_of_order_count, tuple(invalid), tuple(flushed), tuple(late_events))


def _number(value: Any, name: str) -> float:
    try:
        result = float(value)
    except (TypeError, ValueError) as exc:
        raise ValueError(f"invalid {name}") from exc
    if result != result or result in {float("inf"), float("-inf")}:
        raise ValueError(f"invalid {name}")
    return result


def normalize_event(record: Mapping[str, Any], source: str, default_well_id: str = "") -> SurveyEvent:
    def first(*names: str, default: Any = None):
        for name in names:
            if name in record and record[name] not in (None, ""):
                return record[name]
        return default
    well = str(first("well_id", "well", "wellName", default=default_well_id) or "")
    if not well:
        raise ValueError("well_id is required for realtime data")
    timestamp = first("timestamp", "time", "datetime")
    if isinstance(timestamp, datetime):
        timestamp = timestamp.astimezone(timezone.utc).isoformat()
    return SurveyEvent(source=source, well_id=well,
                       md=_number(first("md", "measured_depth", "measuredDepth"), "md"),
                       inclination=_number(first("inclination", "inc"), "inclination"),
                       azimuth=_number(first("azimuth", "azi"), "azimuth"),
                       timestamp=str(timestamp) if timestamp is not None else None,
                       sequence=str(first("sequence", "seq", default="")) or None,
                       raw=dict(record))


class ReconnectingAdapter:
    def __init__(self, source: str, reconnect_initial: float = 1.0, reconnect_max: float = 30.0):
        if reconnect_initial <= 0 or reconnect_max < reconnect_initial:
            raise ValueError("invalid reconnect backoff")
        self.source = source
        self.reconnect_initial = reconnect_initial
        self.reconnect_max = reconnect_max
        self._closed = False

    def close(self) -> None:
        self._closed = True

    def _retry_delay(self, attempt: int) -> float:
        return min(self.reconnect_max, self.reconnect_initial * (2 ** min(attempt, 8)))


class WitsTcpAdapter(ReconnectingAdapter):
    """Read newline-delimited JSON or CSV records from a WITS TCP gateway."""

    def __init__(self, host: str, port: int, well_id: str = "", **kwargs: Any):
        super().__init__(f"WITS_TCP:{host}:{port}", **kwargs)
        self.host, self.port, self.well_id = host, int(port), well_id

    @staticmethod
    def _decode(line: bytes) -> Mapping[str, Any]:
        text = line.decode("utf-8", errors="replace").strip()
        if not text:
            raise ValueError("empty WITS record")
        if text.startswith("{"):
            value = json.loads(text)
            if not isinstance(value, dict):
                raise ValueError("WITS JSON record must be an object")
            return value
        row = next(csv.DictReader(io.StringIO(text)))
        return row

    def events(self, timeout: float = 10.0) -> Iterator[SurveyEvent]:
        attempt = 0
        while not self._closed:
            try:
                with socket.create_connection((self.host, self.port), timeout=timeout) as connection:
                    connection.settimeout(timeout)
                    attempt = 0
                    stream = connection.makefile("rb")
                    for line in stream:
                        if self._closed:
                            return
                        yield normalize_event(self._decode(line), self.source, self.well_id)
            except (OSError, ValueError, json.JSONDecodeError):
                if self._closed:
                    return
                time.sleep(self._retry_delay(attempt))
                attempt += 1


class SerialWitsAdapter(ReconnectingAdapter):
    """Read WITS records from a serial port; pyserial is loaded on demand."""

    def __init__(self, device: str, baudrate: int = 9600, well_id: str = "", **kwargs: Any):
        super().__init__(f"SERIAL:{device}", **kwargs)
        self.device, self.baudrate, self.well_id = device, int(baudrate), well_id

    def events(self) -> Iterator[SurveyEvent]:
        try:
            import serial
        except ImportError as exc:  # pragma: no cover
            raise RuntimeError("serial adapter requires pyserial") from exc
        attempt = 0
        while not self._closed:
            try:
                with serial.Serial(self.device, self.baudrate, timeout=1) as port:
                    attempt = 0
                    while not self._closed:
                        line = port.readline()
                        if line:
                            yield normalize_event(WitsTcpAdapter._decode(line), self.source, self.well_id)
            except (OSError, ValueError, serial.SerialException):
                if self._closed:
                    return
                time.sleep(self._retry_delay(attempt))
                attempt += 1


class SqlServerAdapter(ReconnectingAdapter):
    """Polling adapter for an SQL Server survey table using pyodbc."""

    def __init__(self, connection_string: str, query: str, source: str = "SQL_SERVER", **kwargs: Any):
        super().__init__(source, **kwargs)
        if ";" not in query.lower() and "select" not in query.lower():
            raise ValueError("query must be a SELECT statement")
        if not query.lstrip().lower().startswith("select"):
            raise ValueError("SQL Server adapter accepts SELECT only")
        self.connection_string, self.query = connection_string, query

    def events(self, poll_seconds: float = 5.0) -> Iterator[SurveyEvent]:
        try:
            import pyodbc
        except ImportError as exc:  # pragma: no cover
            raise RuntimeError("SQL Server adapter requires pyodbc") from exc
        seen: set[tuple[Any, ...]] = set()
        attempt = 0
        while not self._closed:
            try:
                with pyodbc.connect(self.connection_string, timeout=10) as connection:
                    attempt = 0
                    cursor = connection.cursor()
                    cursor.execute(self.query)
                    columns = [str(column[0]) for column in cursor.description]
                    for row in cursor.fetchall():
                        record = dict(zip(columns, row))
                        marker = tuple(record.get(k) for k in ("well_id", "md", "timestamp", "sequence"))
                        if marker in seen:
                            continue
                        seen.add(marker)
                        yield normalize_event(record, self.source)
                time.sleep(max(0.1, poll_seconds))
            except (OSError, ValueError, pyodbc.Error):
                if self._closed:
                    return
                time.sleep(self._retry_delay(attempt))
                attempt += 1
