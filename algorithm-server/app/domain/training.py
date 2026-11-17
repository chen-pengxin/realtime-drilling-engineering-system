"""Subprocess based training task runner.

Training scripts are deliberately kept out of the API process. A script must live
under TRAIN_TEMPLATE_ROOT and datasets under TRAIN_DATA_ROOT. Task state is kept
in memory here; the configured callback is the durable integration point for the
Java service, which owns the database and user authorization.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
import json
import hashlib
import os
from pathlib import Path
import shutil
import signal
import subprocess
import sys
import tempfile
import threading
import time
from concurrent.futures import ThreadPoolExecutor
from typing import Any
from uuid import uuid4


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


def _inside(path: Path, root: Path) -> bool:
    try:
        path.relative_to(root)
        return True
    except ValueError:
        return False


@dataclass
class TrainingTask:
    task_id: str
    status: str
    device: str
    template: str
    dataset: str
    output_dir: str
    field_id: int | None = None
    created_at: str = field(default_factory=_now)
    started_at: str | None = None
    finished_at: str | None = None
    exit_code: int | None = None
    error: str | None = None
    model_artifact_uri: str | None = None
    model_artifact_sha256: str | None = None
    model_artifact_size_bytes: int | None = None
    queue_attempts: int = 0
    logs: list[str] = field(default_factory=list)
    metrics: list[dict[str, Any]] = field(default_factory=list)
    stop_requested: bool = False
    _process: subprocess.Popen | None = field(default=None, repr=False)
    _lock: threading.RLock = field(default_factory=threading.RLock, repr=False)

    def public(self) -> dict[str, Any]:
        with self._lock:
            return {
                "task_id": self.task_id, "status": self.status, "device": self.device,
                "template": self.template, "dataset": self.dataset, "output_dir": self.output_dir,
                "field_id": self.field_id,
                "created_at": self.created_at, "started_at": self.started_at, "finished_at": self.finished_at,
                "exit_code": self.exit_code, "error": self.error,
                "model_artifact_uri": self.model_artifact_uri,
                "model_artifact_sha256": self.model_artifact_sha256,
                "model_artifact_size_bytes": self.model_artifact_size_bytes,
                "queue_attempts": self.queue_attempts,
                "logs": list(self.logs), "metrics": list(self.metrics),
            }


class TrainingManager:
    def __init__(self) -> None:
        base = Path(__file__).resolve().parents[2]
        self.template_root = Path(os.getenv("TRAIN_TEMPLATE_ROOT", str(base / "train_templates"))).resolve()
        self.data_root = Path(os.getenv("TRAIN_DATA_ROOT", str(base / "data"))).resolve()
        self.model_root = Path(os.getenv("MODEL_ROOT", str(base / "models"))).resolve()
        self.template_root.mkdir(parents=True, exist_ok=True)
        self.data_root.mkdir(parents=True, exist_ok=True)
        self.model_root.mkdir(parents=True, exist_ok=True)
        state_file = os.getenv("TRAINING_STATE_FILE")
        self.state_file = Path(state_file).expanduser() if state_file else None
        self.redis_url = os.getenv("REDIS_URL")
        self.queue_stream = os.getenv("TRAINING_QUEUE_STREAM", "drilling:training:queue")
        self.queue_group = os.getenv("TRAINING_QUEUE_GROUP", "training-workers")
        self.queue_consumer = os.getenv("TRAINING_QUEUE_CONSUMER", f"worker-{uuid4().hex[:12]}")
        try:
            self.queue_max_retries = max(0, int(os.getenv("TRAINING_QUEUE_MAX_RETRIES", "2")))
        except ValueError:
            self.queue_max_retries = 2
        self._queue_client = None
        self._queue_thread: threading.Thread | None = None
        self._queue_stop = threading.Event()
        self._queue_connect_lock = threading.RLock()
        self._queue_enabled = False
        self.callback_url = os.getenv("TASK_CALLBACK_URL")
        self.tasks: dict[str, TrainingTask] = {}
        self._tasks_lock = threading.RLock()
        self._local_gpu_locks: dict[str, threading.Lock] = {}
        # Callback delivery is serialized to preserve event ordering, while
        # keeping log capture independent of Java network latency.
        self._callback_executor = ThreadPoolExecutor(max_workers=1, thread_name_prefix="training-callback")
        self._load_state()
        self._configure_queue()

    def _load_state(self) -> None:
        """恢复可查询的任务摘要；运行中的子进程无法跨进程恢复，标为 LOST。"""
        if self.state_file is None or not self.state_file.is_file():
            return
        try:
            rows = json.loads(self.state_file.read_text(encoding="utf-8"))
            for row in rows if isinstance(rows, list) else []:
                status = row.get("status", "FAILED")
                if status in {"QUEUED", "RUNNING"}:
                    status = "LOST"
                task = TrainingTask(
                    task_id=str(row["task_id"]), status=status, device=str(row.get("device", "cpu")),
                    template=str(row.get("template", "")), dataset=str(row.get("dataset", "")),
                    output_dir=str(row.get("output_dir", "")), field_id=row.get("field_id"),
                    created_at=str(row.get("created_at", _now())),
                    started_at=row.get("started_at"), finished_at=row.get("finished_at"),
                    exit_code=row.get("exit_code"), error=row.get("error"),
                    model_artifact_uri=row.get("model_artifact_uri"),
                    model_artifact_sha256=row.get("model_artifact_sha256"),
                    model_artifact_size_bytes=row.get("model_artifact_size_bytes"),
                    queue_attempts=int(row.get("queue_attempts", 0) or 0),
                    logs=list(row.get("logs") or []), metrics=list(row.get("metrics") or []),
                )
                self.tasks[task.task_id] = task
        except (OSError, ValueError, TypeError, KeyError):
            self.tasks.clear()

    def _configure_queue(self) -> None:
        """Start a Redis Stream consumer when Redis is available.

        The stream carries a complete task envelope, so another API process
        can claim a queued message even when its in-memory task dictionary is
        empty. Redis remains optional for local CPU-only development.
        """
        if not self.redis_url or os.getenv("TRAINING_QUEUE_ENABLED", "true").lower() in {"0", "false", "no"}:
            return
        # Start the monitor even when Redis is down during API startup.  It
        # will keep the local fallback usable and automatically switch back
        # to the durable Stream after Redis becomes healthy.
        self._connect_queue()
        self._queue_thread = threading.Thread(target=self._queue_worker, daemon=True,
                                              name=f"{self.queue_group}-{self.queue_consumer}")
        self._queue_thread.start()

    def _connect_queue(self) -> bool:
        """Connect (or reconnect) the Redis Stream without creating workers.

        Queue availability is deliberately independent from the in-process
        fallback.  A Redis outage should make new tasks fall back locally for
        the moment, but the existing consumer must keep probing so a later
        recovery re-enables durable delivery and claims messages left pending
        by a crashed worker.
        """
        if not self.redis_url or os.getenv("TRAINING_QUEUE_ENABLED", "true").lower() in {"0", "false", "no"}:
            return False
        with self._queue_connect_lock:
            if self._queue_enabled and self._queue_client is not None:
                return True
            try:
                import redis
                client = redis.Redis.from_url(self.redis_url, decode_responses=True,
                                              socket_connect_timeout=1, socket_timeout=2)
                client.ping()
                try:
                    client.xgroup_create(self.queue_stream, self.queue_group, id="0", mkstream=True)
                except redis.ResponseError as exc:
                    if "BUSYGROUP" not in str(exc).upper():
                        raise
                self._queue_client = client
                self._queue_enabled = True
                return True
            except Exception:
                self._queue_client = None
                self._queue_enabled = False
                return False

    def _queue_worker(self) -> None:
        while not self._queue_stop.is_set():
            client = self._queue_client
            if not self._queue_enabled or client is None:
                # Keep the original worker alive across a Redis restart.  The
                # next successful connection reuses the same consumer name,
                # allowing XAUTOCLAIM to recover pending envelopes.
                self._connect_queue()
                if self._queue_client is None:
                    self._queue_stop.wait(1.0)
                    continue
                client = self._queue_client
            try:
                # Recover messages left pending by a crashed API/worker
                # process.  Redis keeps the envelope durable in the stream;
                # XAUTOCLAIM hands it to this consumer after 30 seconds idle.
                try:
                    _next_id, pending, _deleted = client.xautoclaim(
                        self.queue_stream, self.queue_group, self.queue_consumer,
                        min_idle_time=30000, start_id="0-0", count=10)
                    for message_id, fields in pending:
                        self._handle_queue_message(client, message_id, fields)
                except Exception:
                    pass
                batches = client.xreadgroup(self.queue_group, self.queue_consumer,
                                            {self.queue_stream: ">"}, count=1, block=1000)
                for _stream, messages in batches:
                    for message_id, fields in messages:
                        self._handle_queue_message(client, message_id, fields)
            except Exception:
                # A transient Redis outage should not kill the API process or
                # permanently disable durable training.  Clear the client so
                # the next loop performs a bounded reconnect attempt.
                with self._queue_connect_lock:
                    self._queue_client = None
                    self._queue_enabled = False
                self._queue_stop.wait(1.0)

    def _handle_queue_message(self, client: Any, message_id: str, fields: dict[str, Any]) -> None:
        try:
            self._consume_message(fields)
            client.xack(self.queue_stream, self.queue_group, message_id)
        except Exception as exc:
            if not self._retry_queue_message(fields, str(exc)):
                self._record_queue_failure(fields, str(exc))
            client.xack(self.queue_stream, self.queue_group, message_id)

    def _enqueue(self, task: TrainingTask, hyperparameters: dict[str, Any]) -> bool:
        if not self._queue_enabled or self._queue_client is None:
            return False
        envelope = {
            "task_id": task.task_id,
            "field_id": task.field_id,
            "template": task.template,
            "dataset": task.dataset,
            "output_dir": task.output_dir,
            "device": task.device,
            "hyperparameters": hyperparameters,
            "queue_attempts": task.queue_attempts,
        }
        try:
            self._queue_client.xadd(self.queue_stream, {"payload": json.dumps(envelope, ensure_ascii=False),
                                                        "enqueued_at": _now()})
            self._publish(task, {"type": "status", "status": "QUEUED", "queue": "redis-stream"})
            return True
        except Exception:
            self._queue_enabled = False
            return False

    def _consume_message(self, fields: dict[str, Any]) -> None:
        raw = fields.get("payload")
        if raw is None:
            raise ValueError("training queue message is missing payload")
        envelope = json.loads(raw)
        task_id = str(envelope["task_id"])
        with self._tasks_lock:
            task = self.tasks.get(task_id)
            if task is None:
                task = TrainingTask(task_id, "QUEUED", normalize_device(str(envelope.get("device", "cpu"))),
                                    str(envelope["template"]), str(envelope["dataset"]),
                                    str(envelope["output_dir"]), field_id=envelope.get("field_id"))
                self.tasks[task_id] = task
                self._persist_state()
            # A cancellation or terminal result may have been persisted while
            # its Stream envelope was still pending.  A LOST task is the one
            # terminal-looking state that is intentionally retryable after a
            # worker crash.
            if task.status in {"STOPPED", "SUCCEEDED", "FAILED"}:
                return
        script = self._resolve_template(task.template)
        dataset = self._resolve_dataset(task.dataset)
        self._run(task, script, dataset, {str(k): v for k, v in (envelope.get("hyperparameters") or {}).items()})

    def _retry_queue_message(self, fields: dict[str, Any], error: str) -> bool:
        """Retry envelope validation/path failures with bounded attempts.

        Training script failures are terminal task failures and are handled by
        ``_run`` itself; this retry policy is for transient worker setup
        failures such as a shared upload volume becoming briefly unavailable.
        """
        if self._queue_client is None or self.queue_max_retries <= 0:
            return False
        try:
            envelope = json.loads(fields.get("payload", ""))
            attempts = int(envelope.get("queue_attempts", 0) or 0)
            if attempts >= self.queue_max_retries:
                return False
            envelope["queue_attempts"] = attempts + 1
            envelope["last_queue_error"] = error[:500]
            self._queue_client.xadd(self.queue_stream, {"payload": json.dumps(envelope, ensure_ascii=False),
                                                         "enqueued_at": _now()})
            return True
        except (TypeError, ValueError, KeyError, json.JSONDecodeError):
            return False

    def _record_queue_failure(self, fields: dict[str, Any], error: str) -> None:
        raw = fields.get("payload")
        if not raw:
            return
        try:
            envelope = json.loads(raw)
            task_id = str(envelope.get("task_id", ""))
            with self._tasks_lock:
                task = self.tasks.get(task_id)
                if task is None:
                    task = TrainingTask(task_id, "FAILED", str(envelope.get("device", "cpu")),
                                        str(envelope.get("template", "")), str(envelope.get("dataset", "")),
                                        str(envelope.get("output_dir", "")), field_id=envelope.get("field_id"))
                    self.tasks[task_id] = task
                with task._lock:
                    task.status = "FAILED"
                    task.error = error
                    task.queue_attempts = int(envelope.get("queue_attempts", 0) or 0)
                    task.finished_at = _now()
                self._persist_state()
                self._publish(task, {"type": "status", "status": "FAILED", "error": error})
        except (TypeError, ValueError, KeyError):
            pass

    def _persist_state(self) -> None:
        if self.state_file is None:
            return
        try:
            self.state_file.parent.mkdir(parents=True, exist_ok=True)
            with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=self.state_file.parent,
                                             prefix=self.state_file.name + ".", delete=False) as handle:
                json.dump([task.public() for task in self.tasks.values()], handle, ensure_ascii=False)
                temporary = Path(handle.name)
            temporary.replace(self.state_file)
        except OSError:
            pass

    def _resolve_template(self, template: str) -> Path:
        candidate = (self.template_root / template).resolve()
        if not _inside(candidate, self.template_root) or not candidate.is_file() or candidate.suffix != ".py":
            raise ValueError("template must be an existing .py file under TRAIN_TEMPLATE_ROOT")
        return candidate

    def _resolve_dataset(self, dataset: str) -> Path:
        candidate = (self.data_root / dataset).resolve()
        if not _inside(candidate, self.data_root) or not candidate.exists():
            raise ValueError("dataset must exist under TRAIN_DATA_ROOT")
        return candidate

    def start(self, template: str, dataset: str, device: str, hyperparameters: dict[str, Any],
              field_id: int | None = None, base_model_path: str | None = None) -> TrainingTask:
        script = self._resolve_template(template)
        data = self._resolve_dataset(dataset)
        if base_model_path:
            candidate = (self.model_root / base_model_path).resolve()
            if not _inside(candidate, self.model_root) or not candidate.is_file():
                raise ValueError("incremental base model must exist under MODEL_ROOT")
        selected = normalize_device(device)
        task_id = str(uuid4())
        output_dir = self.model_root / task_id
        output_dir.mkdir(parents=True, exist_ok=False)
        task = TrainingTask(task_id, "QUEUED", selected, str(script.relative_to(self.template_root)),
                            str(data.relative_to(self.data_root)), str(output_dir), field_id=field_id)
        with self._tasks_lock:
            self.tasks[task_id] = task
            self._persist_state()
        args = {str(k): v for k, v in hyperparameters.items() if k != "_base_model_path"}
        if base_model_path:
            args["_base_model_path"] = str(candidate)
        if not self._enqueue(task, args):
            threading.Thread(target=self._run, args=(task, script, data, args), daemon=True,
                             name=f"training-{task_id}").start()
        return task

    def _acquire_device(self, task: TrainingTask) -> tuple[threading.Lock | None, Any | None]:
        if not task.device.startswith("cuda:"):
            return None, None
        gpu_id = task.device.split(":", 1)[1]
        with self._tasks_lock:
            lock = self._local_gpu_locks.setdefault(gpu_id, threading.Lock())
        while not lock.acquire(timeout=1):
            if task.stop_requested:
                raise InterruptedError("task stopped while waiting for GPU lock")
        redis_client = None
        if self.redis_url:
            try:
                import redis
                redis_client = redis.Redis.from_url(self.redis_url, socket_connect_timeout=2)
                redis_client.ping()
                key = f"drilling:gpu:{gpu_id}:lock"
                deadline = time.monotonic() + float(os.getenv("GPU_LOCK_WAIT_SECONDS", "0"))
                while not redis_client.set(key, task.task_id, nx=True, ex=24 * 60 * 60):
                    if task.stop_requested:
                        raise InterruptedError("task stopped while waiting for GPU lock")
                    if time.monotonic() >= deadline:
                        raise RuntimeError(f"GPU {gpu_id} is already allocated")
                    time.sleep(1)
                return lock, (redis_client, key, task.task_id)
            except ImportError:
                pass
            except Exception:
                lock.release()
                raise
        return lock, None

    @staticmethod
    def _release_device(lock: threading.Lock | None, redis_lock: Any | None) -> None:
        if redis_lock:
            client, key, task_id = redis_lock
            try:
                client.eval("if redis.call('get', KEYS[1]) == ARGV[1] then return redis.call('del', KEYS[1]) else return 0 end", 1, key, task_id)
            except Exception:
                pass
        if lock and lock.locked():
            lock.release()

    def _publish(self, task: TrainingTask, event: dict[str, Any]) -> None:
        # Every callback carries an idempotency key.  Java stores it in
        # drilling_task_event, so retries and duplicate deliveries are safe.
        event = dict(event)
        event.setdefault("event_id", str(uuid4()))
        event.setdefault("idempotency_key", f"training:{task.task_id}:{event['event_id']}")
        event.setdefault("field_id", task.field_id)
        if self.redis_url:
            try:
                import redis
                redis.Redis.from_url(self.redis_url, socket_connect_timeout=1).publish(
                    f"drilling:task:{task.task_id}", json.dumps(event, ensure_ascii=False))
            except Exception:
                pass
        if self.callback_url:
            self._callback_executor.submit(self._deliver_callback, task.task_id, event)

    def _deliver_callback(self, task_id: str, event: dict[str, Any]) -> None:
        """Deliver an event at least once with bounded exponential retries.

        The Java submit transaction stores ``external_task_id`` only after the
        FastAPI start request returns.  A worker can therefore publish its
        first RUNNING event while that transaction is still in flight.  Keep
        retrying 404 responses (as well as transient network errors) long
        enough to cover that race, while keeping the policy configurable for
        deployments with a slower database or a remote Java service.
        """
        from urllib.error import HTTPError
        from urllib.request import Request, urlopen
        body = json.dumps({"task_id": task_id, **event}, ensure_ascii=False).encode()
        headers = {"Content-Type": "application/json", "Accept": "application/json"}
        token = os.getenv("TASK_CALLBACK_TOKEN")
        if token:
            headers["X-Drilling-Callback-Token"] = token
        try:
            retries = max(1, int(os.getenv("TASK_CALLBACK_RETRIES", "8")))
        except ValueError:
            retries = 8
        try:
            initial_delay = max(0.0, float(os.getenv("TASK_CALLBACK_RETRY_SECONDS", "0.5")))
        except ValueError:
            initial_delay = 0.5
        try:
            max_delay = max(initial_delay, float(os.getenv("TASK_CALLBACK_MAX_RETRY_SECONDS", "8")))
        except ValueError:
            max_delay = max(initial_delay, 8.0)
        for attempt in range(retries):
            try:
                request = Request(self.callback_url, body, headers, method="POST")
                with urlopen(request, timeout=3) as response:
                    if 200 <= response.status < 300:
                        return
                    # A 404 is expected until Java commits the task row.  A
                    # 4xx other than 404 is a contract/authentication error;
                    # retrying it only adds load and delays diagnostics.
                    if response.status != 404:
                        return
            except HTTPError as exc:
                # A 404 means Java has not committed the task row yet and is
                # safe to retry. Authentication/contract errors are terminal
                # for this event and should be visible in the worker log.
                if exc.code != 404:
                    return
            except Exception:
                # DNS, connection resets and timeouts are transient.
                pass
            if attempt + 1 < retries:
                time.sleep(min(max_delay, initial_delay * (2 ** attempt)))

    def _append_log(self, task: TrainingTask, line: str) -> None:
        line = line.rstrip("\r\n")
        event: dict[str, Any] = {"type": "log", "at": _now(), "message": line}
        try:
            candidate = json.loads(line)
            if isinstance(candidate, dict) and "epoch" in candidate and ("loss" in candidate or "metric" in candidate):
                event = {"type": "metric", "at": _now(), **candidate}
                with task._lock:
                    task.metrics.append(candidate)
        except (json.JSONDecodeError, TypeError):
            pass
        with task._lock:
            task.logs.append(line)
            if len(task.logs) > 5000:
                del task.logs[:1000]
        self._publish(task, event)

    def _run(self, task: TrainingTask, script: Path, dataset: Path, hyperparameters: dict[str, Any]) -> None:
        device_lock = None
        redis_lock = None
        try:
            device_lock, redis_lock = self._acquire_device(task)
            with task._lock:
                if task.stop_requested:
                    task.status = "STOPPED"
                    task.finished_at = _now()
                    return
                task.status = "RUNNING"
                task.started_at = _now()
            self._persist_state()
            self._publish(task, {"type": "status", "status": "RUNNING"})
            command = [sys.executable, "-u", str(script), "--dataset", str(dataset), "--output-dir", task.output_dir,
                       "--device", task.device, "--hyperparameters-json", json.dumps(hyperparameters)]
            base_model_path = hyperparameters.get("_base_model_path")
            if base_model_path:
                base = Path(base_model_path).resolve()
                if not _inside(base, self.model_root) or not base.is_file():
                    raise ValueError("incremental base model is unavailable")
                command.extend(["--base-model-path", str(base)])
            env = os.environ.copy()
            if task.device.startswith("cuda:"):
                env["CUDA_VISIBLE_DEVICES"] = task.device.split(":", 1)[1]
            else:
                env["CUDA_VISIBLE_DEVICES"] = ""
                env["DRILLING_DEVICE"] = "cpu"
            creationflags = subprocess.CREATE_NEW_PROCESS_GROUP if os.name == "nt" else 0
            process = subprocess.Popen(command, stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                                       text=True, encoding="utf-8", errors="replace", bufsize=1,
                                       env=env, start_new_session=(os.name != "nt"), creationflags=creationflags)
            with task._lock:
                task._process = process
            assert process.stdout is not None
            for line in process.stdout:
                self._append_log(task, line)
                if task.stop_requested and process.poll() is None:
                    self._terminate(process)
            exit_code = process.wait()
            with task._lock:
                task.exit_code = exit_code
                task.status = "STOPPED" if task.stop_requested else "SUCCEEDED" if exit_code == 0 else "FAILED"
                task.finished_at = _now()
                task._process = None
                if exit_code != 0 and not task.stop_requested:
                    task.error = f"training process exited with code {exit_code}"
            self._persist_state()
            self._publish(task, {"type": "status", "status": task.status, "exit_code": exit_code,
                                 "output_dir": task.output_dir})
            if task.status == "SUCCEEDED":
                self._archive_model(task)
        except InterruptedError:
            with task._lock:
                task.status, task.finished_at = "STOPPED", _now()
            self._persist_state()
        except Exception as exc:
            with task._lock:
                task.status, task.error, task.finished_at = "FAILED", str(exc), _now()
            self._persist_state()
            self._publish(task, {"type": "status", "status": "FAILED", "error": str(exc)})
        finally:
            self._release_device(device_lock, redis_lock)

    @staticmethod
    def _terminate(process: subprocess.Popen) -> None:
        try:
            if os.name == "nt":
                process.terminate()
            else:
                os.killpg(process.pid, signal.SIGTERM)
            process.wait(timeout=10)
        except subprocess.TimeoutExpired:
            if os.name == "nt":
                process.kill()
            else:
                os.killpg(process.pid, signal.SIGKILL)
        except ProcessLookupError:
            pass

    def stop(self, task_id: str) -> TrainingTask:
        task = self.get(task_id)
        with task._lock:
            if task.status in {"SUCCEEDED", "FAILED", "STOPPED"}:
                return task
            task.stop_requested = True
            process = task._process
        if process and process.poll() is None:
            self._terminate(process)
        elif task.status == "QUEUED":
            with task._lock:
                task.status, task.finished_at = "STOPPED", _now()
            self._persist_state()
        return task

    def get(self, task_id: str) -> TrainingTask:
        with self._tasks_lock:
            task = self.tasks.get(task_id)
        if not task:
            raise KeyError(task_id)
        return task

    def list(self) -> list[dict[str, Any]]:
        with self._tasks_lock:
            tasks = list(self.tasks.values())
        return [task.public() for task in reversed(tasks)]

    def store_file(self, name: str, content: bytes, category: str = "DATASET") -> dict[str, Any]:
        safe_name = Path(name).name
        if not safe_name or safe_name in {".", ".."}:
            raise ValueError("file name is required")
        category_dir = (self.data_root / category.lower()).resolve()
        if not _inside(category_dir, self.data_root):
            raise ValueError("invalid file category")
        category_dir.mkdir(parents=True, exist_ok=True)
        digest = hashlib.sha256(content).hexdigest()
        destination = category_dir / f"{digest[:16]}-{safe_name}"
        destination.write_bytes(content)
        return {"name": safe_name, "category": category.upper(), "path": str(destination.relative_to(self.data_root)),
                "size_bytes": len(content), "sha256": digest}

    def list_files(self) -> list[dict[str, Any]]:
        rows = []
        for path in self.data_root.rglob("*"):
            if path.is_file() and path.name != ".gitkeep":
                rows.append({"name": path.name, "path": str(path.relative_to(self.data_root)),
                             "size_bytes": path.stat().st_size, "updated_at": path.stat().st_mtime})
        return sorted(rows, key=lambda row: row["updated_at"], reverse=True)

    def resolve_file(self, relative_path: str) -> Path:
        path = (self.data_root / relative_path).resolve()
        if not _inside(path, self.data_root) or not path.is_file():
            raise KeyError(relative_path)
        return path

    def resolve_model(self, relative_path: str) -> Path:
        path = (self.model_root / relative_path).resolve()
        if not _inside(path, self.model_root) or not path.is_file() or path.suffix.lower() not in {".zip", ".pt", ".pth", ".onnx", ".json"}:
            raise KeyError(relative_path)
        return path

    def model_visible_to_field(self, relative_path: str, field_id: int) -> bool:
        """Return whether an archived model belongs to the requested field.

        Model archives are named after the training task UUID.  The task
        metadata is the authoritative field association; matching the
        artifact URI as well keeps this working for locally retained archives
        and for artifacts whose callback URI is an ``s3://`` object key.
        """
        name = Path(relative_path).name
        owner_file = self.model_root / f"{name}.owner.json"
        if name.startswith("import-") and owner_file.is_file():
            try:
                return json.loads(owner_file.read_text(encoding="utf-8")).get("field_id") == field_id
            except (OSError, ValueError):
                return False
        with self._tasks_lock:
            tasks = list(self.tasks.values())
        for task in tasks:
            if task.field_id != field_id:
                continue
            if name == f"{task.task_id}.zip":
                return True
            if task.model_artifact_uri and Path(str(task.model_artifact_uri)).name == name:
                return True
        return False

    def remote_model_for_field(self, relative_path: str, field_id: int):
        """Resolve a MinIO-backed model after the local archive is unavailable.

        Java stores the callback URI as the field-scoped artifact metadata
        and requests the basename from this service.  The persisted training
        state links that basename back to its ``s3://bucket/object`` URI, so a
        restart or a separate API process can still stream the object without
        accepting an arbitrary bucket/key from the client.
        """
        name = Path(relative_path).name
        with self._tasks_lock:
            tasks = list(self.tasks.values())
        remote_uri = None
        for task in tasks:
            if task.field_id != field_id or not task.model_artifact_uri:
                continue
            uri = str(task.model_artifact_uri)
            if Path(uri).name == name and uri.startswith("s3://"):
                remote_uri = uri
                break
        if not remote_uri:
            return None
        from urllib.parse import urlparse
        parsed = urlparse(remote_uri)
        bucket = parsed.netloc
        object_name = parsed.path.lstrip("/")
        endpoint = os.getenv("MINIO_ENDPOINT")
        access_key = os.getenv("MINIO_ACCESS_KEY")
        secret_key = os.getenv("MINIO_SECRET_KEY")
        if not bucket or not object_name or not endpoint or not access_key or not secret_key:
            return None
        try:
            from minio import Minio
            endpoint_url = urlparse(endpoint)
            client = Minio(endpoint_url.netloc or endpoint_url.path, access_key=access_key,
                            secret_key=secret_key, secure=endpoint_url.scheme == "https")
            return client, bucket, object_name
        except Exception:
            return None

    def logs_after(self, task_id: str, offset: int) -> tuple[list[str], int, str]:
        task = self.get(task_id)
        with task._lock:
            return task.logs[offset:], len(task.logs), task.status

    def _archive_model(self, task: TrainingTask) -> None:
        output = Path(task.output_dir)
        files = [p for p in output.rglob("*") if p.is_file()]
        if not files:
            self._append_log(task, "training succeeded without model artifacts; no model archive created")
            return
        archive = self.model_root / f"{task.task_id}.zip"
        shutil.make_archive(str(archive.with_suffix("")), "zip", output)
        digest = hashlib.sha256(archive.read_bytes()).hexdigest()
        size_bytes = archive.stat().st_size
        object_name = archive.name
        endpoint = os.getenv("MINIO_ENDPOINT")
        bucket = os.getenv("MINIO_BUCKET", "drilling-models")
        if endpoint and os.getenv("MINIO_ACCESS_KEY") and os.getenv("MINIO_SECRET_KEY"):
            try:
                from minio import Minio
                from urllib.parse import urlparse
                parsed = urlparse(endpoint)
                client = Minio(parsed.netloc, access_key=os.environ["MINIO_ACCESS_KEY"],
                               secret_key=os.environ["MINIO_SECRET_KEY"], secure=parsed.scheme == "https")
                if not client.bucket_exists(bucket):
                    client.make_bucket(bucket)
                client.fput_object(bucket, object_name, str(archive), content_type="application/zip")
                object_name = f"s3://{bucket}/{object_name}"
            except Exception as exc:
                self._append_log(task, f"model archive kept locally; MinIO upload failed: {exc}")
        with task._lock:
            task.model_artifact_uri = object_name
            task.model_artifact_sha256 = digest
            task.model_artifact_size_bytes = size_bytes
        self._persist_state()
        self._publish(task, {"type": "model", "status": "AVAILABLE", "model": {
            "uri": object_name, "name": archive.name, "sha256": digest, "size_bytes": size_bytes
        }})


def normalize_device(device: str) -> str:
    device = device.strip().lower()
    if device == "cpu":
        return "cpu"
    if device in {"auto", "cuda"}:
        available = list_gpus()
        if available:
            return f"cuda:{available[0]['index']}"
        if device == "auto":
            return "cpu"
        raise ValueError("CUDA was requested but no NVIDIA GPU is available")
    if device.startswith("cuda:") and device[5:].isdigit():
        requested = int(device[5:])
        if requested not in {gpu["index"] for gpu in list_gpus()}:
            raise ValueError(f"requested {device} is unavailable; choose CPU or an available GPU")
        return device
    raise ValueError("device must be cpu, auto, cuda or cuda:<index>")


def list_gpus() -> list[dict[str, Any]]:
    from app.shared.hardware import gpu_snapshot
    return [device for device in gpu_snapshot()["devices"]
            if device.get("backend") == "CUDA" and device.get("available")]


def resource_status() -> dict[str, Any]:
    try:
        import psutil
        memory = psutil.virtual_memory()
        cpu = {"percent": psutil.cpu_percent(interval=0.1), "memory_total_bytes": memory.total,
               "memory_used_bytes": memory.used, "memory_percent": memory.percent}
    except ImportError:
        cpu = {"available": False, "message": "Install psutil for CPU and memory metrics"}
    queue = {
        "enabled": bool(manager._queue_enabled),
        "stream": manager.queue_stream,
        "group": manager.queue_group,
        "consumer": manager.queue_consumer,
        "max_retries": manager.queue_max_retries,
        "durability": "redis-stream" if manager._queue_enabled else "in-process-fallback",
    }
    from app.shared.hardware import gpu_snapshot
    gpu = gpu_snapshot()
    gpus = [device for device in gpu["devices"] if device.get("backend") == "CUDA" and device.get("available")]
    return {"cpu": cpu, "gpus": gpus, "gpu_runtime_available": bool(gpus), "gpu_monitor": gpu,
            "training_queue": queue}


manager = TrainingManager()
