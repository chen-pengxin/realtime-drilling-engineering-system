"""Subprocess contract for model-specific inference adapters."""
from __future__ import annotations

import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
from typing import Any

from .training import normalize_device
from app.tasks.prediction.pose_contract import normalize_pose_result


def run_inference(model_path: str, script: str, payload: dict[str, Any], device: str, timeout_seconds: int) -> dict[str, Any]:
    template_root = Path(os.getenv("TRAIN_TEMPLATE_ROOT", Path(__file__).resolve().parents[2] / "train_templates")).resolve()
    model_root = Path(os.getenv("MODEL_ROOT", Path(__file__).resolve().parents[2] / "models")).resolve()
    selected_script = (template_root / script).resolve()
    selected_model = (model_root / model_path).resolve()
    if not selected_script.is_relative_to(template_root) or not selected_script.is_file() or selected_script.suffix != ".py":
        raise ValueError("inference script must be an existing .py file under TRAIN_TEMPLATE_ROOT")
    if not selected_model.is_relative_to(model_root) or not selected_model.exists():
        raise ValueError("model_path must exist under MODEL_ROOT")
    selected_device = normalize_device(device)
    with tempfile.NamedTemporaryFile("w", encoding="utf-8", suffix=".json", dir=model_root, delete=False) as handle:
        json.dump(payload, handle, ensure_ascii=False)
        input_path = Path(handle.name)
    try:
        env = os.environ.copy()
        if selected_device.startswith("cuda:"):
            env["CUDA_VISIBLE_DEVICES"] = selected_device.split(":", 1)[1]
        else:
            env["CUDA_VISIBLE_DEVICES"] = ""
            env["DRILLING_DEVICE"] = "cpu"
        result = subprocess.run([sys.executable, "-u", str(selected_script), "--model-path", str(selected_model),
                                 "--input-json", str(input_path), "--device", selected_device],
                                capture_output=True, text=True, encoding="utf-8", errors="replace",
                                timeout=timeout_seconds, check=False, env=env)
        if result.returncode:
            raise RuntimeError(f"inference process exited with code {result.returncode}: {result.stderr[-4000:]}")
        lines = [line for line in result.stdout.splitlines() if line.strip()]
        if not lines:
            raise RuntimeError("inference script returned no JSON result")
        try:
            value = json.loads(lines[-1])
        except json.JSONDecodeError as exc:
            raise RuntimeError("inference script must print its result as JSON on the final stdout line") from exc
        if not isinstance(value, dict):
            raise RuntimeError("inference result must be a JSON object")
        if payload.get("contract") == "drilling.pose-extrapolation.v1":
            value = normalize_pose_result(value, payload)
        return {"status": "COMPLETED", "device": selected_device, "result": value,
                "logs": lines[:-1]}
    finally:
        input_path.unlink(missing_ok=True)
