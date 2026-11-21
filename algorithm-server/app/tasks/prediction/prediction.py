"""兼容旧任务接口的姿态预测登记器。

生产预测由 Java 的井场模型配置和 ``/infer`` 适配器执行；本模块只保留
旧版异步任务登记接口。当该接口没有绑定模型时，它必须明确返回
``MODEL_NOT_CONFIGURED``，不能伪造姿态或调用几何外推。
"""
from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime, timezone
from threading import RLock
from typing import Any
from uuid import uuid4
import json
import os
from pathlib import Path
import tempfile


def _now() -> str:
    return datetime.now(timezone.utc).isoformat()


@dataclass
class PredictionTask:
    task_id: str
    status: str
    prediction_step: float
    device: str
    model_version: str | None
    field_id: int | None
    well_id: int | None
    survey_version_id: int | None
    input_file_id: int | None
    input_summary: dict[str, Any]
    created_at: str = field(default_factory=_now)
    result: dict[str, Any] | None = None

    def public(self) -> dict[str, Any]:
        return {
            "task_id": self.task_id,
            "status": self.status,
            "prediction_step": self.prediction_step,
            "device": self.device,
            "model_version": self.model_version,
            "field_id": self.field_id,
            "well_id": self.well_id,
            "survey_version_id": self.survey_version_id,
            "input_file_id": self.input_file_id,
            "input_summary": self.input_summary,
            "created_at": self.created_at,
            "result": self.result,
        }


def _empty_result_contract() -> dict[str, Any]:
    """Stable result shape until a field model is configured.

    Clients can render the future prediction columns without inferring whether
    a missing key means "not calculated" or an older API version.
    """
    return {
        "code": "MODEL_NOT_CONFIGURED",
        "message": "姿态预测模型尚未配置；当前只创建业务任务，不产生真实预测结果。",
        "sample": False,
        "zero_length_extrapolation": None,
        "forward_9m": None,
        "forward_28m": None,
        "toolface": None,
        "sliding_ratio": None,
        "composite_ratio": None,
        "model_evaluation": None,
    }


class PredictionManager:
    def __init__(self, state_file: str | None = None) -> None:
        self._tasks: dict[str, PredictionTask] = {}
        self._lock = RLock()
        configured = state_file or os.getenv("PREDICTION_STATE_FILE")
        self._state_file = Path(configured).expanduser() if configured else None
        self._load()

    def _load(self) -> None:
        if self._state_file is None or not self._state_file.is_file():
            return
        try:
            rows = json.loads(self._state_file.read_text(encoding="utf-8"))
            for row in rows if isinstance(rows, list) else []:
                self._tasks[row["task_id"]] = PredictionTask(
                    task_id=row["task_id"], status=row["status"], prediction_step=float(row["prediction_step"]),
                    device=row["device"], model_version=row.get("model_version"),
                    field_id=row.get("field_id"), well_id=row.get("well_id"),
                    survey_version_id=row.get("survey_version_id"), input_file_id=row.get("input_file_id"),
                    input_summary=dict(row.get("input_summary") or {}), created_at=row.get("created_at", _now()),
                    result=row.get("result"),
                )
        except (OSError, ValueError, TypeError, KeyError):
            # 损坏的快照不能阻止算法服务启动；Java/MySQL仍是生产权威状态源。
            self._tasks.clear()

    def _persist(self) -> None:
        if self._state_file is None:
            return
        try:
            self._state_file.parent.mkdir(parents=True, exist_ok=True)
            content = json.dumps([task.public() for task in self._tasks.values()], ensure_ascii=False, indent=2)
            with tempfile.NamedTemporaryFile("w", encoding="utf-8", dir=self._state_file.parent,
                                             prefix=self._state_file.name + ".", delete=False) as handle:
                handle.write(content)
                temporary = Path(handle.name)
            temporary.replace(self._state_file)
        except OSError:
            # 快照是重启恢复的增强能力，不能让一次任务登记因磁盘问题失败。
            pass

    def start(self, prediction_step: float, device: str, model_version: str | None,
              input_summary: dict[str, Any], field_id: int | None = None,
              well_id: int | None = None, survey_version_id: int | None = None,
              input_file_id: int | None = None) -> PredictionTask:
        task = PredictionTask(
            task_id=str(uuid4()),
            status="MODEL_NOT_CONFIGURED",
            prediction_step=prediction_step,
            device=device,
            model_version=model_version,
            field_id=field_id,
            well_id=well_id,
            survey_version_id=survey_version_id,
            input_file_id=input_file_id,
            input_summary=input_summary,
            result=_empty_result_contract(),
        )
        with self._lock:
            self._tasks[task.task_id] = task
            self._persist()
        return task

    def cancel(self, task_id: str) -> PredictionTask:
        with self._lock:
            task = self._tasks.get(task_id)
            if task is None:
                raise KeyError(task_id)
            if task.status not in {"SUCCEEDED", "FAILED", "CANCELLED"}:
                task.status = "CANCELLED"
                task.result = {"code": "CANCELLED", "message": "预测业务任务已取消。", "sample": False}
                self._persist()
            return task

    def get(self, task_id: str) -> PredictionTask:
        with self._lock:
            task = self._tasks.get(task_id)
        if task is None:
            raise KeyError(task_id)
        return task

    def list(self) -> list[dict[str, Any]]:
        with self._lock:
            return [task.public() for task in reversed(list(self._tasks.values()))]


manager = PredictionManager()
