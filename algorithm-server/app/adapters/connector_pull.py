"""Bounded connector reads for the Java project-authorized connector worker.

Transport reads return raw records; only the durable Java inbox commits surveys.
TCP/serial accept the existing gateway's JSON-lines or CSV field contract.
"""
from __future__ import annotations

import base64
import csv
import io
import json
import math
import re
import socket
from urllib.parse import urlparse
from urllib.request import Request, urlopen
import xml.etree.ElementTree as ET

MAX_BYTES = 8 * 1024 * 1024


def _records(content: bytes, suffix: str) -> list[dict]:
    if len(content) > MAX_BYTES:
        raise ValueError("source exceeds 8 MB per poll")
    if suffix.lower() in {"xlsx", "xls"}:
        import pandas as pd
        frame = pd.read_excel(io.BytesIO(content)).where(lambda x: x.notna(), None)
        return json.loads(frame.to_json(orient="records"))
    text = content.decode("utf-8-sig")
    if text.lstrip().startswith("["):
        rows = json.loads(text)
        if not isinstance(rows, list) or any(not isinstance(x, dict) for x in rows):
            raise ValueError("expected a record array")
        return rows
    lines = [line for line in text.splitlines() if line.strip()]
    if lines and lines[0].lstrip().startswith("{"):
        return [json.loads(line) for line in lines]
    if not lines:
        return []
    try:
        dialect = csv.Sniffer().sniff("\n".join(lines[:5]), delimiters=",\t;")
    except csv.Error:
        dialect = csv.excel
    parsed = list(csv.reader(io.StringIO("\n".join(lines)), dialect=dialect))
    # Serial gateways commonly send a single headerless MD/INC/AZI row.
    try:
        headerless = len(parsed[0]) == 3 and all(math.isfinite(float(x)) for x in parsed[0])
    except (TypeError, ValueError):
        headerless = False
    if headerless:
        if any(len(row) != 3 for row in parsed):
            raise ValueError("headerless CSV requires MD,INC,AZI on every row")
        return [dict(zip(("md", "inc", "azi"), row)) for row in parsed]
    return list(csv.DictReader(io.StringIO("\n".join(lines)), dialect=dialect))


def _xml_measure(node: ET.Element | None, angle: bool = False):
    if node is None:
        return None
    unit = str(node.get("uom") or ("dega" if angle else "m")).lower()
    factors = {"dega": 1, "deg": 1, "rad": 180 / math.pi} if angle else {"m": 1, "ft": 0.3048}
    if unit not in factors:
        raise ValueError("unsupported trajectory unit: " + unit)
    return node.text if factors[unit] == 1 else float(node.text) * factors[unit]


def read_connector(payload: dict) -> dict:
    kind = payload.get("type")
    config = payload.get("config") or {}
    limit = int(config.get("batchSize", 500))
    if not 1 <= limit <= 2000:
        raise ValueError("batchSize must be 1–2000")
    timeout = min(10, max(1, float(config.get("timeoutSeconds", 5))))
    endpoint = str(payload.get("endpoint") or "")
    if kind in {"CSV", "EXCEL"}:
        raw = base64.b64decode(payload.get("contentBase64", ""), validate=True)
        rows = _records(raw, str(payload.get("suffix", "csv")))
        # File reads are repeatable. The durable event key suppresses already committed rows.
        cursor = int((payload.get("cursor") or {}).get("offset", 0))
        offset = max(0, min(cursor, len(rows)))
        return {"records": rows[offset:offset + limit], "cursor": {"offset": min(len(rows), offset + limit)}}
    if kind in {"WITSML_1_4_1", "WITSML_2_0"}:
        parsed = urlparse(endpoint)
        if parsed.scheme not in {"http", "https"} or parsed.username or parsed.password:
            raise ValueError("WITSML trajectory XML endpoint must be HTTP(S) without embedded credentials")
        headers = {"Accept": "application/xml"}
        if payload.get("credential"):
            headers["Authorization"] = str(payload["credential"])
        with urlopen(Request(endpoint, headers=headers), timeout=timeout) as response:
            content = response.read(MAX_BYTES + 1)
        if len(content) > MAX_BYTES or b"<!DOCTYPE" in content.upper() or b"<!ENTITY" in content.upper():
            raise ValueError("unsafe or oversized XML")
        from .witsml_pds import stations_from_witsml, inspect_witsml
        document = inspect_witsml(content)
        if kind.endswith("2_0") and document["version"] not in {"2.0", "UNKNOWN"}:
            raise ValueError("连接器声明为 WITSML 2.0，但原文不是 2.0 文档")
        rows = stations_from_witsml(content)
        converted_units = bool(re.search(br"uom\s*=\s*['\"](?:ft|ftus|feet|rad|radian|radians)['\"]", content, re.I))
        if document["version"] == "UNKNOWN" and not converted_units:
            # Preserve the legacy connector contract for non-standard wrappers:
            # untyped XML values remain strings and only canonical fields are
            # exposed.  Standard WITSML documents use normalized numeric values.
            rows = [{key: format(row[key], 'g') for key in ("md", "inclination", "azimuth")} for row in rows]
        else:
            rows = [{key: row[key] for key in ("md", "inclination", "azimuth")} for row in rows]
        if not rows:
            raise ValueError("XML contains no trajectory stations")
        offset = int((payload.get("cursor") or {}).get("offset", 0))
        if offset > len(rows):
            offset = 0
        return {"records": rows[offset:offset + limit], "cursor": {"offset": min(len(rows), offset + limit)}}
    if kind == "SQL_SERVER":
        import pyodbc
        credential = str(payload.get("credential") or "")
        if not credential:
            raise ValueError("SQL Server requires a server environment credential reference")
        table = str(config.get("table", ""))
        columns = [str(config.get(key, default)) for key, default in [("mdColumn", "md"), ("incColumn", "inclination"), ("aziColumn", "azimuth")]]
        if any(not re.fullmatch(r"[A-Za-z_][A-Za-z0-9_]*", x) for x in [table, *columns]):
            raise ValueError("table and columns must be simple SQL identifiers")
        with pyodbc.connect(credential, timeout=int(timeout), autocommit=True) as connection:
            cur = connection.cursor()
            cur.timeout = int(timeout)
            # No arbitrary SQL or mutation endpoint is exposed.
            sql = f"SELECT TOP {limit} [{columns[0]}],[{columns[1]}],[{columns[2]}] FROM [{table}] WHERE [{columns[0]}]>? ORDER BY [{columns[0]}]"
            rows = [{"md": row[0], "inclination": row[1], "azimuth": row[2]} for row in cur.execute(sql, float((payload.get("cursor") or {}).get("md", -1)))]
        return {"records": rows, "cursor": {"md": max((float(r["md"]) for r in rows), default=float((payload.get("cursor") or {}).get("md", -1)))}}
    if kind == "WITS_TCP":
        parsed = urlparse(endpoint)
        if parsed.scheme != "tcp" or not parsed.hostname or not parsed.port:
            raise ValueError("WITS endpoint must use tcp://host:port")
        chunks = bytearray()
        with socket.create_connection((parsed.hostname, parsed.port), timeout=timeout) as connection:
            connection.settimeout(timeout)
            while len(chunks) < MAX_BYTES and chunks.count(b"\n") <= limit:
                try:
                    block = connection.recv(min(65536, MAX_BYTES - len(chunks)))
                except socket.timeout:
                    break
                if not block:
                    break
                chunks.extend(block)
        if not chunks:
            return {"records": [], "cursor": payload.get("cursor") or {}}
        rows = _records(bytes(chunks), "csv")
        if len(rows) > limit:
            raise ValueError("TCP batch exceeds batchSize; increase batchSize or reduce upstream batch")
        return {"records": rows, "cursor": payload.get("cursor") or {}}
    if kind == "SERIAL":
        import serial
        device = endpoint
        if not (device.startswith("/dev/") or device.upper().startswith("COM")):
            raise ValueError("serial device must be /dev/... or COM...")
        with serial.Serial(device, int(config.get("baudrate", 9600)), timeout=timeout) as port:
            content = port.read_until(b"\n", MAX_BYTES)
        return {"records": _records(content, "csv") if content else [], "cursor": payload.get("cursor") or {}}
    raise ValueError("unsupported connector type")
