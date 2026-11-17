"""GPU monitoring must distinguish real telemetry, unavailable metrics and probe failure."""
import json
import subprocess
import sys
from pathlib import Path
from types import SimpleNamespace

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.shared import hardware


@pytest.fixture(autouse=True)
def isolated_cache(monkeypatch):
    monkeypatch.setattr(hardware, "_cache", None)


def command_result(monkeypatch, stdout):
    monkeypatch.setattr(hardware.subprocess, "run", lambda *a, **k: SimpleNamespace(stdout=stdout))


def test_nvidia_telemetry_and_missing_sensor_values(monkeypatch):
    monkeypatch.setattr(hardware.shutil, "which", lambda name: "/usr/bin/nvidia-smi")
    command_result(monkeypatch,
                   '0, GPU-a, NVIDIA RTX A6000, 550.54, 49140, 2048, 73, 61, 198.5\n'
                   '1, GPU-b, NVIDIA T4, 550.54, 15360, 0, 0, N/A, [Not Supported]\n')
    result = hardware.probe_gpu()
    assert result["status"] == "UP" and len(result["devices"]) == 2
    busy, idle = result["devices"]
    assert (busy["utilization_percent"], busy["memory_used_mb"], busy["temperature_c"], busy["power_w"]) == (73, 2048, 61, 198.5)
    assert idle["utilization_percent"] == 0 and idle["memory_used_mb"] == 0
    assert idle["temperature_c"] is None and idle["power_w"] is None
    assert all(device["available"] and device["backend"] == "CUDA" for device in result["devices"])


def test_apple_unified_memory_does_not_claim_cuda_or_fake_metrics(monkeypatch):
    monkeypatch.setattr(hardware.shutil, "which", lambda name: None)
    monkeypatch.setattr(hardware.platform, "system", lambda: "Darwin")
    command_result(monkeypatch, json.dumps({"SPDisplaysDataType": [{"sppci_model": "Apple M3", "sppci_cores": "10"}]}))
    gpu = hardware.gpu_snapshot()["devices"][0]
    assert gpu["name"] == "Apple M3" and gpu["shared_memory"]
    assert not gpu["available"] and gpu["backend"] == "Metal"
    assert gpu["memory_total_mb"] is None and gpu["utilization_percent"] is None
    from app.domain.training import list_gpus, normalize_device, resource_status
    assert list_gpus() == [] and normalize_device("auto") == "cpu"
    with pytest.raises(ValueError, match="unavailable"):
        normalize_device("cuda:0")
    resources = resource_status()
    assert resources["gpu_monitor"]["devices"][0]["name"] == "Apple M3"
    assert not resources["gpu_runtime_available"]


@pytest.mark.parametrize("error,state", [
    (subprocess.TimeoutExpired("nvidia-smi", 4), "TIMEOUT"),
    (subprocess.CalledProcessError(1, "nvidia-smi"), "ERROR"),
    (OSError("driver unavailable"), "ERROR")])
def test_driver_failure_is_reported_without_failing_monitoring(monkeypatch, error, state):
    monkeypatch.setattr(hardware.shutil, "which", lambda name: "nvidia-smi")
    def fail(*args, **kwargs):
        raise error
    monkeypatch.setattr(hardware.subprocess, "run", fail)
    snapshot = hardware.gpu_snapshot()
    assert snapshot["status"] == state and snapshot["devices"] == []
    assert snapshot["message"]


def test_invalid_driver_output_is_not_treated_as_healthy(monkeypatch):
    monkeypatch.setattr(hardware.shutil, "which", lambda name: "nvidia-smi")
    command_result(monkeypatch, "unparseable driver output")
    assert hardware.probe_gpu()["status"] == "ERROR"


def test_linux_inventory_reads_real_units_and_ignores_connector_entries(tmp_path):
    device = tmp_path / "card0" / "device"
    (device / "hwmon/hwmon0").mkdir(parents=True)
    for name, value in {"vendor": "0x1002", "device": "0x73bf", "mem_info_vram_total": str(8 * 1048576),
                        "mem_info_vram_used": str(2 * 1048576), "gpu_busy_percent": "42", "hwmon/hwmon0/temp1_input": "56000"}.items():
        (device / name).write_text(value)
    (tmp_path / "card0-HDMI-A-1").mkdir()
    rows = hardware._linux(tmp_path)
    assert len(rows) == 1
    gpu = rows[0]
    assert (gpu["vendor"], gpu["memory_total_mb"], gpu["memory_used_mb"], gpu["temperature_c"]) == ("AMD", 8, 2, 56)
    assert gpu["utilization_percent"] == 42 and not gpu["available"]


def test_no_device_and_unsupported_platform_have_explicit_states(monkeypatch):
    monkeypatch.setattr(hardware.shutil, "which", lambda name: None)
    monkeypatch.setattr(hardware.platform, "system", lambda: "Linux")
    monkeypatch.setattr(hardware, "_linux", lambda: [])
    assert hardware.probe_gpu()["status"] == "NO_DEVICE"
    monkeypatch.setattr(hardware.platform, "system", lambda: "Windows")
    assert hardware.probe_gpu()["status"] == "UNSUPPORTED"


def test_cached_data_isolated_from_consumers_and_refreshed(monkeypatch):
    calls = []
    clock = [100.0]
    monkeypatch.setattr(hardware.time, "monotonic", lambda: clock[0])
    def probe():
        calls.append(True)
        return {"status": "UP", "devices": [{"name": "GPU"}]}
    monkeypatch.setattr(hardware, "probe_gpu", probe)
    first = hardware.gpu_snapshot()
    first["devices"].clear()
    assert len(hardware.gpu_snapshot()["devices"]) == 1 and len(calls) == 1
    clock[0] = 111.0
    hardware.gpu_snapshot()
    assert len(calls) == 2
