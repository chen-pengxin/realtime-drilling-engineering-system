"""Bounded, read-only GPU inventory. Missing telemetry stays null, never zero."""
from __future__ import annotations

import csv
import io
import json
import plistlib
import platform
from pathlib import Path
import re
import shutil
import socket
import subprocess
import threading
import time
from copy import deepcopy
from typing import Any

_cache: tuple[float, dict[str, Any]] | None = None
_lock = threading.Lock()


def _safe_run(command: list[str], **kwargs: Any) -> subprocess.CompletedProcess:
    """Run a read-only hardware probe without forking the threaded API process.

    On macOS, the algorithm service has already loaded pyproj/PROJ.  Python's
    default ``subprocess`` settings can then use ``fork`` for a probe such as
    ``system_profiler``; PROJ's SQLite at-fork cleanup may crash the child
    before exec.  Keeping file descriptors open lets Python use macOS
    ``posix_spawn``.  The probes do not receive request data or write files.
    """
    if platform.system() == "Darwin":
        kwargs.setdefault("close_fds", False)
    return subprocess.run(command, **kwargs)


def _metric(value: Any) -> float | None:
    try:
        n = float(value)
        return n if n >= 0 and n < float("inf") else None
    except (TypeError, ValueError):
        return None


def _device(**values: Any) -> dict[str, Any]:
    return {"index": 0, "name": "", "vendor": "", "driver_version": None,
            "memory_total_mb": None, "memory_used_mb": None, "utilization_percent": None,
            "temperature_c": None, "power_w": None, "shared_memory": False,
            "available": False, "status": "DETECTED", **values}


def _nvidia(executable: str) -> list[dict[str, Any]]:
    result = _safe_run(
        [executable, "--query-gpu=index,uuid,name,driver_version,memory.total,memory.used,utilization.gpu,temperature.gpu,power.draw",
         "--format=csv,noheader,nounits"], capture_output=True, text=True, timeout=4, check=True)
    devices = []
    for row in csv.reader(io.StringIO(result.stdout)):
        row = [v.strip() for v in row]
        if not row:
            continue
        if len(row) != 9 or not row[0].isdigit() or not row[2]:
            raise ValueError("invalid GPU metrics")
        devices.append(_device(index=int(row[0]), uuid=row[1], name=row[2], vendor="NVIDIA",
                               driver_version=row[3], memory_total_mb=_metric(row[4]),
                               memory_used_mb=_metric(row[5]), utilization_percent=_metric(row[6]),
                               temperature_c=_metric(row[7]), power_w=_metric(row[8]),
                               available=True, status="UP", backend="CUDA"))
    return devices


def _apple() -> list[dict[str, Any]]:
    result = _safe_run(["/usr/sbin/system_profiler", "SPDisplaysDataType", "-json"],
                            capture_output=True, text=True, timeout=4, check=True)
    devices = []
    for index, row in enumerate(json.loads(result.stdout).get("SPDisplaysDataType", [])):
        name = row.get("sppci_model") or row.get("_name")
        if not name:
            continue
        vram = str(row.get("spdisplays_vram", ""))
        match = re.search(r"([0-9.]+)\s*(GB|MB)", vram, re.I)
        total = float(match[1]) * (1024 if match[2].upper() == "GB" else 1) if match else None
        vendor = str(row.get("spdisplays_vendor", "Apple")).replace("sppci_vendor_", "")
        devices.append(_device(index=index, name=name, vendor=vendor,
                               memory_total_mb=total, shared_memory=total is None,
                               cores=row.get("sppci_cores"), backend="Metal", status="DETECTED"))
    # Do not attribute one accelerator's counters to another card on a multi-GPU Mac.
    if len(devices) == 1 and devices[0]["name"].startswith("Apple"):
        try:
            report = _safe_run(
                ["/usr/sbin/ioreg", "-a", "-r", "-d", "1", "-c", "AGXAccelerator", "-k", "PerformanceStatistics"],
                capture_output=True, timeout=1, check=True)
            controllers = plistlib.loads(report.stdout)
            if len(controllers) == 1:
                stats = controllers[0].get("PerformanceStatistics", {})
                used = _metric(stats.get("In use system memory"))
                utilization = _metric(stats.get("Device Utilization %"))
                if utilization is not None and utilization <= 100:
                    devices[0]["utilization_percent"] = utilization
                if used is not None:
                    devices[0]["memory_used_mb"] = used / 1048576
        except (OSError, subprocess.SubprocessError, plistlib.InvalidFileException, TypeError, ValueError):
            pass  # Hardware inventory is still valid when performance counters are unavailable.
    return devices


def _linux(root: Path = Path("/sys/class/drm")) -> list[dict[str, Any]]:
    def read(path: Path) -> str:
        try:
            return path.read_text().strip()
        except OSError:
            return ""
    devices, seen = [], set()
    for card in sorted(root.glob("card*")):
        if not re.fullmatch(r"card\d+", card.name):
            continue
        path = (card / "device").resolve()
        if not path.exists() or path in seen:
            continue
        seen.add(path)
        vendor = {"0x1002": "AMD", "0x8086": "Intel", "0x10de": "NVIDIA"}.get(read(path / "vendor"), read(path / "vendor") or "未知")
        total, used = _metric(read(path / "mem_info_vram_total")), _metric(read(path / "mem_info_vram_used"))
        temp = next(iter(sorted(path.glob("hwmon/hwmon*/temp1_input"))), None)
        temperature = _metric(read(temp)) if temp else None
        devices.append(_device(index=len(devices), name=f"{vendor} {read(path / 'device')}".strip(),
                               vendor=vendor, backend="DRM", memory_total_mb=total / 1048576 if total is not None else None,
                               memory_used_mb=used / 1048576 if used is not None else None,
                               utilization_percent=_metric(read(path / "gpu_busy_percent")),
                               temperature_c=temperature / 1000 if temperature is not None else None))
    return devices


def probe_gpu() -> dict[str, Any]:
    base = {"host": socket.gethostname(), "platform": platform.system(), "devices": [],
            "checked_at": time.strftime("%Y-%m-%dT%H:%M:%SZ", time.gmtime())}
    executable = shutil.which("nvidia-smi")
    try:
        if executable:
            devices, backend = _nvidia(executable), "NVIDIA_SMI"
        elif base["platform"] == "Darwin":
            devices, backend = _apple(), "SYSTEM_PROFILER"
        elif base["platform"] == "Linux":
            devices, backend = _linux(), "DRM"
        else:
            return {**base, "backend": "NONE", "status": "UNSUPPORTED", "message": "当前平台未提供 GPU 采集接口"}
        return {**base, "devices": devices, "backend": backend,
                "status": "UP" if devices else "NO_DEVICE",
                "message": "" if devices else "未检测到 GPU 设备"}
    except subprocess.TimeoutExpired:
        return {**base, "backend": "NVIDIA_SMI" if executable else "SYSTEM", "status": "TIMEOUT", "message": "GPU 信息采集超时"}
    except (OSError, subprocess.SubprocessError, ValueError, TypeError):
        return {**base, "backend": "NVIDIA_SMI" if executable else "SYSTEM", "status": "ERROR", "message": "GPU 驱动或信息采集接口不可用"}


def gpu_snapshot() -> dict[str, Any]:
    global _cache
    with _lock:
        now = time.monotonic()
        if _cache is None or now - _cache[0] >= 10:
            _cache = (now, probe_gpu())
        return deepcopy(_cache[1])
