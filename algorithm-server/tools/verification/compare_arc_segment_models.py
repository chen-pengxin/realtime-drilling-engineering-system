"""Compare the two supplied nominal arc-segment models outside the product.

This is a verification artifact.  It runs the well_control_iscwsa scenario
workflow with the same A/B/C input, solver, constraints and Rev.0 budget, and
changes only ``arc_segment_model``.  The generated files are documentation
evidence; they are deliberately not written to the application database and
are not exposed by a product route.
"""

from __future__ import annotations

import argparse
import csv
import json
import os
import copy
import hashlib
import platform
import statistics
import sys
from datetime import datetime, timezone
import tempfile
from pathlib import Path


MODELS = ("minimum_curvature", "constant_toolface")
MODEL_LABELS = {
    "minimum_curvature": "最小曲率平面圆弧",
    "constant_toolface": "恒工具面恒曲率空间曲线",
}


def _plain(value):
    if hasattr(value, "item"):
        return value.item()
    return value


def run(args: argparse.Namespace) -> dict:
    # The supplied wheel resolves its scenario assets through this variable.
    # Set it before importing wellpath.data_io.scenarios.
    os.environ["WELLPATH_PROJECT_ROOT"] = str(args.engine_project.resolve())
    import wellpath.workflows.scenario_replanning as workflow
    # This verification retains numerical reports only. Optional static
    # figures require matplotlib and are deliberately skipped.
    workflow.create_scene_html = lambda *args, **kwargs: None
    workflow._run_continuous_scene.__globals__['create_scene_html'] = workflow.create_scene_html
    import wellpath.reports.scenario_replanning as scenario_report
    scenario_report.create_scene_html = workflow.create_scene_html
    run_scene = workflow.run_scene

    base = json.loads(args.config.read_text(encoding="utf-8"))
    scenarios = [s.upper() for s in args.scenarios]
    rows: list[dict] = []
    with tempfile.TemporaryDirectory(prefix="arc-model-compare-") as temporary:
        work = Path(temporary)
        for repetition in range(1, args.repeats + 1):
            # Alternate model order to reduce systematic warm-up/order bias.
            for model in (MODELS if repetition % 2 else tuple(reversed(MODELS))):
                settings = copy.deepcopy(base)
                settings["scenarios"] = scenarios
                # Keep the experiment controlled: only the nominal geometry model
                # changes.  A/B/C all use the same SLSQP solver and Rev.0 inputs.
                settings["algorithms"] = [args.solver]
                settings["arc_segment_model"] = model
                model_root = work / f"run-{repetition}-{model}"
                model_root.mkdir()
                for scene in scenarios:
                    reports = run_scene(scene, settings, model_root)
                    if len(reports) != 1:
                        raise RuntimeError(f"expected one {args.solver} report for {model}/{scene}")
                    report = reports[0]
                    check = report.get("final_check") or {}
                    rows.append({
                        "repetition": repetition,
                        "scene": scene,
                        "arc_segment_model": model,
                        "model_label": MODEL_LABELS[model],
                        "solver": args.solver,
                        "feasible": bool(check.get("feasible")),
                        "preparation_seconds": float(report.get("shared_preprocessing_seconds", 0.0)),
                        "optimization_wall_seconds": float(report.get("algorithm_wall_seconds", 0.0)),
                        "solver_success": bool(report.get("selected_candidate_solver_success")),
                        "solver_messages": [r.get("message") for r in report.get("runs", [])],
                        "initial_feasibility_seconds": float(report.get("feasibility_construction_seconds", 0.0)),
                        "optimization_seconds": float(report.get("optimization_seconds", 0.0)),
                        "final_verification_seconds": float(report.get("dense_final_verification_seconds", 0.0)),
                        "result_output_seconds": float(report.get("export_seconds", 0.0)),
                        "total_seconds": float(report.get("complete_flow_seconds", 0.0)),
                        "total_length_m": float(report.get("final_total_length_m")),
                        "position_error_m": float(check.get("position_error_m")),
                        "minimum_sf": float(check.get("minimum_sf")),
                        "iterations": sum(int(run.get("iterations", 0)) for run in report.get("runs", [])),
                    })
    args.output.mkdir(parents=True, exist_ok=True)
    import numpy as np
    import scipy
    sources = ["src/wellpath/geometry/segments.py", "src/wellpath/geometry/arc.py",
               "src/wellpath/planning/replanning_problem.py", "src/wellpath/workflows/scenario_replanning.py"]
    metadata = {
        "scope": "verification_only", "created_utc": datetime.now(timezone.utc).isoformat(),
        "python": sys.version, "numpy": np.__version__, "scipy": scipy.__version__,
        "platform": platform.platform(), "machine": platform.machine(), "processor": platform.processor(),
        "repetitions": args.repeats, "config_sha256": hashlib.sha256(args.config.read_bytes()).hexdigest(),
        "source_sha256": {name: hashlib.sha256((args.engine_project / name).read_bytes()).hexdigest() for name in sources},
        "configuration": base,
    }
    metrics = ["preparation_seconds", "initial_feasibility_seconds", "optimization_seconds", "optimization_wall_seconds",
               "final_verification_seconds", "result_output_seconds", "total_seconds", "total_length_m", "position_error_m", "minimum_sf"]
    summaries = []
    for scene in scenarios:
        for model in MODELS:
            selected = [r for r in rows if r["scene"] == scene and r["arc_segment_model"] == model]
            summaries.append({"scene": scene, "arc_segment_model": model, "model_label": MODEL_LABELS[model],
                              "feasible_runs": sum(r["feasible"] for r in selected),
                              "converged_runs": sum(r["solver_success"] for r in selected),
                              **{key: statistics.median(r[key] for r in selected) for key in metrics},
                              "total_seconds_range": [min(r["total_seconds"] for r in selected), max(r["total_seconds"] for r in selected)]})
    (args.output / "comparison.json").write_text(
        json.dumps({"metadata": metadata, "rows": rows, "summary_medians": summaries}, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8",
    )
    fields = [
        "repetition", "scene", "arc_segment_model", "model_label", "solver", "feasible",
        "preparation_seconds", "optimization_wall_seconds", "solver_success", "solver_messages",
        "initial_feasibility_seconds", "optimization_seconds",
        "final_verification_seconds", "result_output_seconds", "total_seconds",
        "total_length_m", "position_error_m", "minimum_sf", "iterations",
    ]
    with (args.output / "comparison.csv").open("w", encoding="utf-8-sig", newline="") as stream:
        writer = csv.DictWriter(stream, fieldnames=fields)
        writer.writeheader()
        writer.writerows(rows)

    markdown = [
        "# 初始轨道模型对比验证",
        "",
        "本结果由 `well_control_iscwsa` 独立验证脚本生成，仅用于方法和模型核验，不写入系统数据库，也不进入前端业务页面。两种模型使用相同的 A/B/C 测试场景、Rev.0 误差预算、分离系数与中心距联合约束、三段圆弧—直线组合和 SLSQP 求解器；唯一变量是圆弧段模型。",
        "",
        "| 场景 | 初始轨道模型 | 可行初始解(s) | 优化(s) | 最终核算(s) | 结果输出(s) | 总耗时(s) | 轨迹总长(m) | 最小 SF |",
        "|---|---|---:|---:|---:|---:|---:|---:|---:|",
    ]
    for row in summaries:
        markdown.append(
            f"| {row['scene']} | {row['model_label']} | {row['initial_feasibility_seconds']:.3f} | "
            f"{row['optimization_seconds']:.3f} | {row['final_verification_seconds']:.3f} | "
            f"{row['result_output_seconds']:.3f} | {row['total_seconds']:.3f} | "
            f"{row['total_length_m']:.3f} | {row['minimum_sf']:.3f} |"
        )
    markdown.extend([
        "",
        "## 模型含义",
        "",
        "- 最小曲率平面圆弧：每个弯曲段由起点姿态、终点姿态和曲率确定，采用最小曲率圆弧内核；曲率为零时退化为直线。",
        "- 恒工具面恒曲率空间曲线：每个弯曲段保持曲率和当地工具面恒定，姿态按恒工具面状态方程积分，位置用 DOP853 求积；一般不是平面圆弧。",
        "",
        f"每个组合重复 {args.repeats} 次，表中为中位数。总耗时还包含准备与求解器内部校验；各子项不能脱离计时边界直接求和。浏览器耗时见独立报告，本表仅为原算法工作流计时。",
    ])
    (args.output / "comparison.md").write_text("\n".join(markdown) + "\n", encoding="utf-8")
    return {"summary_medians": summaries, "output": str(args.output)}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--engine-project", type=Path, required=True)
    parser.add_argument("--config", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--scenarios", nargs="+", default=["A", "B", "C"])
    parser.add_argument("--repeats", type=int, default=3)
    parser.add_argument("--solver", default="SLSQP", choices=["SLSQP", "trust-constr", "COBYLA", "COBYQA"])
    args = parser.parse_args()
    if args.repeats < 1:
        parser.error("--repeats must be positive")
    print(json.dumps(run(args), ensure_ascii=False, indent=2, default=_plain))


if __name__ == "__main__":
    main()
