from __future__ import annotations

import asyncio
import base64
import binascii
import csv
import hashlib
import math
import json
from pathlib import Path

from fastapi import FastAPI, HTTPException, UploadFile, File, WebSocket, WebSocketDisconnect, Form, Query, Request
from fastapi.responses import StreamingResponse, FileResponse, Response

from .adapters.realtime import RealtimeIngestService
from .adapters.survey_io import (inspect_survey_rows, merge_incremental_survey, parse_file, parse_witsml,
                                  export_field_mapping, stations_to_csv, stations_to_json, stations_to_witsml)
from .tasks.anticollision.evaluation import CollisionThresholds, ErrorModelConfig, scan_pair, scan_network
from .shared.trajectory.min_curvature import compute_minimum_curvature
from .tasks.replanning.trajectory_planning import PlanningConstraints, SteeringState, Target, plan_trajectory
from .domain.inference import run_inference
from .tasks.prediction.prediction import manager as prediction_manager
from .domain.reports import export_report
from .domain.training import manager as training_manager, resource_status
from .schemas import (CollisionInput, CollisionNetworkInput, IncrementalSurveyInput, InferenceInput, PlanningInput,
                      PredictionStartInput, ReportInput, SurveyExportInput, SurveyInput,
                      SurveyQualityInput, TrainingStartInput, RealtimeIngestInput)

app = FastAPI(title="Drilling Trajectory Algorithm Service", version="0.1.0")
realtime_ingest = RealtimeIngestService()


@app.on_event("startup")
def warm_replanning_workers():
    # Process imports and native numerical modules are paid during service
    # startup, so the 30-second online wait measures the numerical task.
    from .tasks.replanning.execution import warm_planning_workers
    warm_planning_workers()


@app.on_event("shutdown")
def close_replanning_workers():
    from .tasks.replanning.execution import close_planning_workers
    close_planning_workers()
def _stations(value: SurveyInput):
    rows=compute_minimum_curvature([s.model_dump() for s in value.stations], (value.origin_north, value.origin_east, value.origin_tvd))
    if value.canonical_coordinates:
        from dataclasses import replace
        if any(s.north is None or s.east is None or s.tvd is None for s in value.stations):raise ValueError('归算坐标不完整')
        rows=[replace(r,north=s.north,east=s.east,tvd=s.tvd) for r,s in zip(rows,value.stations)]
    return rows


@app.get("/health")
def health():
    return {"status": "UP", "service": "algorithm-server"}


@app.post("/internal/connectors/read")
def connector_read(payload: dict, request: Request):
    import os
    import secrets
    from .adapters.connector_pull import read_connector
    token = os.getenv("DRILLING_CONNECTOR_TOKEN", "")
    if token:
        if not secrets.compare_digest(request.headers.get("X-Connector-Token", ""), token):
            raise HTTPException(403, "internal worker authentication required")
    elif request.client is None or request.client.host not in {"127.0.0.1", "::1", "testclient"}:
        raise HTTPException(403, "configure DRILLING_CONNECTOR_TOKEN for remote workers")
    try:
        return read_connector(payload)
    except Exception as exc:
        # Driver/network exceptions can contain credentials; never send their text to clients.
        raise HTTPException(422, f"connector read failed ({type(exc).__name__})") from exc


@app.post("/api/algorithm/survey/calculate")
def calculate_survey(payload: SurveyInput):
    return {"stations": [s.to_dict() for s in _stations(payload)]}


@app.post('/api/algorithm/survey/sample')
def sample_survey(payload: dict):
    """Native MC view geometry; measured rows remain separate immutable data."""
    import numpy as np
    from wellpath.geometry.minimum_curvature import interpolate_minimum_curvature
    try:
        step=float(payload.pop('step',10))
        survey=SurveyInput.model_validate(payload)
        rows=_stations(survey)
        if not math.isfinite(step) or step<1 or step>100:raise ValueError('绘图采样间距必须为1～100 m')
        if rows and (rows[-1].md-rows[0].md)/step>100000:raise ValueError('绘图采样点过多，请增加采样间距')
        measured={round(s.md,6) for s in rows}
        if len(rows)<2:
            sampled=[s.to_dict() for s in rows]
        else:
            # View sampling is independent of the immutable measurement table.
            # Dense 1 m input must not defeat a requested 10 m plot interval.
            md=np.unique(np.r_[rows[0].md,np.arange(rows[0].md,rows[-1].md,step),rows[-1].md])
            p,inc,azi=interpolate_minimum_curvature([s.md for s in rows],[s.inclination for s in rows],
                [s.azimuth for s in rows],[[s.north,s.east,s.tvd] for s in rows],md)
            sampled=[{'md':float(m),'inclination':float(i),'azimuth':float(a),'north':float(v[0]),'east':float(v[1]),'tvd':float(v[2])} for m,i,a,v in zip(md,inc,azi,p)]
        return {'stations':[{**s,'measured':round(s['md'],6) in measured} for s in sampled],
                'measured_count':len(rows),'geometry_method':'minimum-curvature'}
    except (ValueError,TypeError) as exc:raise HTTPException(422,str(exc)) from exc


@app.post("/api/algorithm/survey/quality-check")
def survey_quality_check(payload: SurveyQualityInput):
    issues = inspect_survey_rows([station.model_dump() for station in payload.stations],
                                 payload.frozen_tolerance, payload.jump_threshold_deg)
    return {"issues": issues, "has_error": any(issue["severity"] == "ERROR" for issue in issues),
            "can_calculate": not any(issue["severity"] == "ERROR" for issue in issues)}


@app.post("/api/algorithm/survey/import/incremental")
def import_incremental_survey(payload: IncrementalSurveyInput):
    existing = _stations(payload.existing)
    merged, issues = merge_incremental_survey(
        existing, [station.model_dump() for station in payload.incoming], payload.md_tolerance
    )
    has_error = any(issue["severity"] == "ERROR" for issue in issues)
    return {
        "stations": [station.to_dict() for station in merged],
        "issues": issues,
        "has_error": has_error,
        "import_mode": "INCREMENTAL",
        "can_commit_new_version": not has_error,
    }


@app.post("/api/algorithm/realtime/ingest")
def ingest_realtime(payload: RealtimeIngestInput):
    """标准化并整理一批实时站点。

    返回的 ``accepted``/``flushed`` 事件应由 Java 业务层在井场事务中写入
    ``drilling_survey_version``；算法服务本身不绕过井场数据权限直接写业务库。
    """
    try:
        result = realtime_ingest.ingest(
            payload.source,
            payload.events,
            default_well_id=str(payload.well_id),
            reorder_window=payload.reorder_window,
            flush=payload.flush,
            replay=payload.replay,
        )
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    response = result.to_dict()
    response["source"] = payload.source
    response["field_id"] = payload.field_id
    response["commit_required"] = bool(response["accepted"] or response["flushed"])
    return response


@app.post("/api/algorithm/survey/export")
def export_survey(payload: SurveyExportInput):
    stations = _stations(payload)
    if payload.format == "csv":
        return Response(stations_to_csv(stations, payload.export_schema, payload.field_mapping), media_type="text/csv", headers={"Content-Disposition": "attachment; filename=trajectory.csv"})
    if payload.format == "json":
        return Response(stations_to_json(stations, payload.export_schema, payload.field_mapping), media_type="application/json", headers={"Content-Disposition": "attachment; filename=trajectory.json"})
    # WITSML has a fixed trajectoryStation vocabulary.  Still validate the
    # selected preset/overrides so a typo cannot silently pass through an
    # export request whose labels are not used by this XML dialect.
    export_field_mapping(payload.export_schema, payload.field_mapping)
    return Response(stations_to_witsml(stations), media_type="application/xml", headers={"Content-Disposition": "attachment; filename=trajectory.witsml"})


@app.post("/api/algorithm/survey/import")
async def import_survey(file: UploadFile = File(...), coordinate_system: str = Form(...),
                        length_unit: str = Form(...), angle_unit: str = Form(...),
                        north_reference: str = Form(...), tvd_positive: str = Form(...),
                        magnetic_declination: float = Form(...), meridian_convergence: float = Form(...),
                        origin_north: float = Form(0), origin_east: float = Form(0), origin_tvd: float = Form(0)):
    import tempfile
    suffix = "." + file.filename.rsplit(".", 1)[-1] if file.filename and "." in file.filename else ".csv"
    try:
        with tempfile.NamedTemporaryFile(suffix=suffix, delete=True) as handle:
            handle.write(await file.read())
            handle.flush()
            if not coordinate_system.strip():
                raise ValueError("coordinate_system is required on first import")
            stations = parse_file(
                handle.name,
                (origin_north, origin_east, origin_tvd),
                length_unit=length_unit,
                angle_unit=angle_unit,
                north_reference=north_reference,
                tvd_positive=tvd_positive,
                magnetic_declination=magnetic_declination,
                meridian_convergence=meridian_convergence,
            )
    except (ValueError, RuntimeError) as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    return {"stations": [s.to_dict() for s in stations], "count": len(stations),
            "reference": {"coordinate_system": coordinate_system, "internal_coordinate_system": "LOCAL_ENU",
                          "length_unit": length_unit, "angle_unit": angle_unit,
                          "north_reference": north_reference, "tvd_positive": tvd_positive,
                          "magnetic_declination": magnetic_declination,
                          "meridian_convergence": meridian_convergence}}


@app.post("/api/algorithm/anti-collision/scan")
def anti_collision(payload: CollisionInput):
    result = scan_pair(
        _stations(payload.reference), _stations(payload.neighbor), payload.step,
        ErrorModelConfig(**payload.error_model.model_dump()),
        CollisionThresholds(**payload.thresholds.model_dump()), payload.mode,
        reference_uncertainty=payload.reference.uncertainty.model_dump() if payload.reference.uncertainty else None,
        neighbor_uncertainty=payload.neighbor.uncertainty.model_dump() if payload.neighbor.uncertainty else None,
    )
    return result.to_dict()


@app.post("/api/algorithm/anti-collision/network-scan")
def anti_collision_network(payload: CollisionNetworkInput):
    result = scan_network(
        _stations(payload.reference),
        [(item.well_id, item.survey_version_id, _stations(item)) for item in payload.neighbors],
        payload.step,
        ErrorModelConfig(**payload.error_model.model_dump()),
        CollisionThresholds(**payload.thresholds.model_dump()),
        payload.incremental_from_md,
        payload.incremental_to_md,
        reference_uncertainty=payload.reference.uncertainty.model_dump() if payload.reference.uncertainty else None,
        neighbor_uncertainties=[item.uncertainty.model_dump() if item.uncertainty else None for item in payload.neighbors],
    )
    return result


@app.post("/api/algorithm/trajectory-plan/solve")
def trajectory_plan(payload: PlanningInput):
    from app.tasks.replanning.execution import execute_planning, PlanningBusy, PlanningDeadline, PlanningWorkerFailed
    try:
        return execute_planning(payload)
    except PlanningDeadline as exc:
        raise HTTPException(status_code=504, detail=str(exc)) from exc
    except PlanningBusy as exc:
        raise HTTPException(status_code=429, detail=str(exc)) from exc
    except PlanningWorkerFailed as exc:
        raise HTTPException(status_code=500, detail=str(exc)) from exc
    except (ValueError, RuntimeError) as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc


@app.get("/api/algorithm/resources")
def algorithm_resources():
    return resource_status()


@app.get("/api/algorithm/files")
def files():
    return {"files": training_manager.list_files()}


@app.post("/api/algorithm/files", status_code=201)
async def upload_file(file: UploadFile = File(...), category: str = Form("DATASET")):
    try:
        metadata = training_manager.store_file(file.filename or "upload.bin", await file.read(), category)
        return metadata
    except ValueError as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc


@app.get("/api/algorithm/files/{file_path:path}")
def download_file(file_path: str):
    try:
        path = training_manager.resolve_file(file_path)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="file not found") from exc
    return FileResponse(path, filename=path.name)


@app.get("/api/algorithm/train/tasks")
def training_tasks():
    return {"tasks": training_manager.list()}


@app.post("/api/algorithm/train/start", status_code=202)
def start_training(payload: TrainingStartInput):
    try:
        task = training_manager.start(payload.template, payload.dataset, payload.device, payload.hyperparameters,
                                      payload.field_id, payload.base_model_path)
        return task.public()
    except (ValueError, RuntimeError) as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc


@app.get("/api/algorithm/train/status/{task_id}")
def training_status(task_id: str):
    try:
        return training_manager.get(task_id).public()
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="training task not found") from exc


@app.post("/api/algorithm/train/stop/{task_id}")
def stop_training(task_id: str):
    try:
        return training_manager.stop(task_id).public()
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="training task not found") from exc


@app.get("/api/algorithm/train/logs/{task_id}")
async def training_logs(task_id: str, offset: int = 0):
    try:
        training_manager.get(task_id)
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="training task not found") from exc

    async def events():
        cursor = max(0, offset)
        while True:
            try:
                lines, next_cursor, status = training_manager.logs_after(task_id, cursor)
            except KeyError:
                break
            for line in lines:
                yield f"data: {json.dumps({'message': line}, ensure_ascii=False)}\n\n"
            cursor = next_cursor
            if status in {"SUCCEEDED", "FAILED", "STOPPED"} and not lines:
                yield f"event: complete\ndata: {json.dumps({'status': status})}\n\n"
                break
            await asyncio.sleep(0.5)

    return StreamingResponse(events(), media_type="text/event-stream", headers={"Cache-Control": "no-cache"})


@app.websocket("/api/algorithm/train/ws/{task_id}")
async def training_websocket(websocket: WebSocket, task_id: str):
    await websocket.accept()
    cursor = 0
    try:
        training_manager.get(task_id)
        while True:
            lines, cursor, status = training_manager.logs_after(task_id, cursor)
            task = training_manager.get(task_id)
            for line in lines:
                await websocket.send_json({"type": "log", "message": line})
            await websocket.send_json({"type": "status", "status": status, "metrics": task.public()["metrics"]})
            if status in {"SUCCEEDED", "FAILED", "STOPPED"}:
                break
            await asyncio.sleep(1)
    except (KeyError, WebSocketDisconnect):
        pass
    finally:
        try:
            await websocket.close()
        except RuntimeError:
            pass


@app.get("/api/algorithm/models")
def models(field_id: int = Query(..., ge=1)):
    root = Path(training_manager.model_root)
    items = [{"name": item.name, "path": item.name, "size_bytes": item.stat().st_size,
              "sha256": hashlib.sha256(item.read_bytes()).hexdigest(),
              "updated_at": item.stat().st_mtime}
             for item in sorted(root.glob("*.zip"), key=lambda path: path.stat().st_mtime, reverse=True)]
    return {"models": [item for item in items if training_manager.model_visible_to_field(item["path"], field_id)]}


@app.post("/api/algorithm/models/import")
def import_model(payload: dict):
    import shutil
    from uuid import uuid4
    field_id = int(payload.get("field_id", 0))
    source = str(payload.get("source_path", ""))
    if field_id <= 0 or not source.startswith(f"drilling-files/{field_id}/"):
        raise HTTPException(status_code=422, detail="model source must belong to this field")
    try:
        path = training_manager.resolve_file(source)
    except KeyError as exc:
        raise HTTPException(status_code=422, detail="model source file is unavailable") from exc
    if path.suffix.lower() not in {".zip", ".pt", ".pth", ".onnx", ".json"}:
        raise HTTPException(status_code=422, detail="unsupported model package")
    name = f"import-{uuid4().hex}{path.suffix.lower()}"
    destination = training_manager.model_root / name
    shutil.copyfile(path, destination)
    (training_manager.model_root / f"{name}.owner.json").write_text(json.dumps({"field_id": field_id}), encoding="utf-8")
    return {"uri": name, "sha256": hashlib.sha256(destination.read_bytes()).hexdigest(), "size_bytes": destination.stat().st_size}


@app.get("/api/algorithm/models/{model_path:path}")
def download_model(model_path: str, field_id: int = Query(..., ge=1)):
    try:
        path = training_manager.resolve_model(model_path)
    except KeyError:
        path = None
    if path is not None:
        if not training_manager.model_visible_to_field(path.name, field_id):
            raise HTTPException(status_code=404, detail="model not found")
        return FileResponse(path, filename=path.name, media_type="application/zip")

    remote = training_manager.remote_model_for_field(model_path, field_id)
    if remote is None:
        raise HTTPException(status_code=404, detail="model not found")
    client, bucket, object_name = remote
    try:
        response = client.get_object(bucket, object_name)
    except Exception as exc:
        raise HTTPException(status_code=404, detail="model not found") from exc

    def stream_remote():
        try:
            while True:
                chunk = response.read(1024 * 1024)
                if not chunk:
                    break
                yield chunk
        finally:
            response.close()
            response.release_conn()

    return StreamingResponse(stream_remote(), media_type="application/zip",
                             headers={"Content-Disposition": f'attachment; filename="{Path(model_path).name}"'})


@app.post("/api/algorithm/infer")
def infer(payload: InferenceInput):
    try:
        return run_inference(payload.model_path, payload.script, payload.input, payload.device, payload.timeout_seconds)
    except (ValueError, RuntimeError) as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc


@app.post("/api/algorithm/prediction/start", status_code=202)
def start_prediction(payload: PredictionStartInput):
    return prediction_manager.start(payload.prediction_step, payload.device,
                                    payload.model_version, payload.input_summary,
                                    payload.field_id, payload.well_id,
                                    payload.survey_version_id, payload.input_file_id).public()


@app.get("/api/algorithm/prediction/tasks")
def prediction_tasks():
    return {"tasks": prediction_manager.list()}


@app.get("/api/algorithm/prediction/status/{task_id}")
def prediction_status(task_id: str):
    try:
        return prediction_manager.get(task_id).public()
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="prediction task not found") from exc


@app.post("/api/algorithm/prediction/cancel/{task_id}")
def cancel_prediction(task_id: str):
    try:
        return prediction_manager.cancel(task_id).public()
    except KeyError as exc:
        raise HTTPException(status_code=404, detail="prediction task not found") from exc


@app.post("/api/algorithm/report/export")
def report_export(payload: ReportInput):
    try:
        report_payload = dict(payload.payload)
        if payload.field_id is not None:
            report_payload.setdefault("field_id", payload.field_id)
        path = export_report(payload.title, report_payload, payload.format)
    except (ImportError, ValueError, RuntimeError) as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    media_types = {"bundle": "application/zip", "all": "application/zip",
                   "xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                   "excel": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
                   "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
                   "pdf": "application/pdf", "json": "application/json"}
    filename = "drilling-report.zip" if payload.format in {"bundle", "all"} else f"drilling-report.{path.suffix[1:]}"
    return FileResponse(path, media_type=media_types[payload.format], filename=filename)

@app.exception_handler(ValueError)
async def input_value_error(request: Request, exc: ValueError):
    from fastapi.responses import JSONResponse
    return JSONResponse(status_code=422,content={'detail':str(exc)})

@app.get('/api/algorithm/engine/catalog')
def engine_catalog():
    from wellpath.anticollision.error_models.registry import MODEL_NAMES,load_model_settings
    from wellpath.optimization.runner import ALGORITHMS
    from .tasks.replanning.native_profiles import ensure_native_profiles
    return {'engine':'wellpath-engine','version':'1.0.0','default_model':'iscwsa_rev0','default_solver':'SLSQP','models':[{'name':n,'settings':load_model_settings(n)} for n in MODEL_NAMES],'algorithms':list(ALGORITHMS),'trajectory_modes':list(ensure_native_profiles()),'segment_counts':[1,2,3,4,5],
      'arc_segment_models':['minimum_curvature','constant_toolface'],'default_arc_segment_model':'minimum_curvature','sf_methods':[
      # ``name`` is an engine contract used by the request payload.  The
      # catalog deliberately exposes only the engineering formula and symbol
      # definitions; internal branch names are not user-facing labels.
      {'name':'major_axis_sum','formula':'SF = D / [K × (r₁ + r₂)]','definition':'K 为不确定性放大系数；D 为两井中心距；r₁、r₂ 为当前位置不确定半径。'},
      {'name':'directional_sum','formula':'SF = D / [K × (√(uᵀC₁u) + √(uᵀC₂u))]','definition':'K 为不确定性放大系数；u 为两井中心连线单位向量；C₁、C₂ 为两井位置协方差矩阵。'},
      {'name':'project_rss','formula':'SF = max(D − b, 0) / [K × √(uᵀ(C₁+C₂)u + σpa²)]','definition':'K 为不确定性放大系数；D 为两井中心距；b 为井径与附加裕量；σpa 为附加位置标准差；u 为中心连线单位向量。'}],
      'visualization':{'renderer':'plotly','axes':['N (m)','E (m)','TVD (m)'],'tvd_reversed':True,'aspectmode':'data','marker':'circle','source':'wellpath.reports.planning.plot_run / ellipsoid'}}

@app.get('/api/algorithm/engine/tool-parameters')
def engine_tool_parameters(name: str = 'ISCWSA MWD Rev5.11'):
    from .tasks.anticollision.tool_parameters import tool_terms
    import welleng as we
    return {'models': we.error.get_error_models(), 'name': name, 'terms': tool_terms(name)}

@app.post('/api/algorithm/engine/validate')
def validate_engine(payload: dict):
    from .tasks.anticollision.evaluation import ErrorModelConfig
    from wellpath.api import calculate_uncertainty
    from wellpath.optimization.runner import ALGORITHMS
    model=ErrorModelConfig(**payload.get('error_model',{}));settings=model.resolved()
    if payload.get('solver','SLSQP') not in ALGORITHMS:raise ValueError('不支持的求解器')
    if 'native_options' in payload:
      options=payload['native_options']
      from .tasks.replanning.native_profiles import ensure_native_profiles
      if options.get('trajectory_mode') not in ensure_native_profiles():raise ValueError('不支持的原生轨迹模型')
      if options.get('arc_segment_model','minimum_curvature') not in ('minimum_curvature','constant_toolface'):raise ValueError('不支持的圆弧段模型')
      if not isinstance(options.get('n_segments'),int) or isinstance(options.get('n_segments'),bool) or options['n_segments'] not in range(1,6):raise ValueError('原生轨迹段数必须为1至5段')
    from .tasks.anticollision.parameter_validation import validate_settings
    kind=validate_settings(settings)
    return {'valid':True,'model':kind,'engine':'wellpath-engine-1.0.0'}

@app.post('/internal/sources/inspect')
async def inspect_source(file: UploadFile = File(...)):
    import tempfile
    from .adapters.source_documents import inspect_document
    # Extension is metadata only; the adapter additionally detects the magic.
    suffix=Path(file.filename or '').suffix
    with tempfile.NamedTemporaryFile(suffix=suffix) as f:
        content=await file.read(256*1024*1024+1)
        if len(content)>256*1024*1024:raise ValueError('单文件上限256MiB')
        f.write(content);f.flush();return inspect_document(f.name,original_name=file.filename)

@app.post('/api/algorithm/source/preview')
def source_preview(payload: dict):
    from .shared.trajectory.revisions import preview_revision
    return preview_revision(payload)

@app.post('/api/algorithm/source/asset')
def source_asset(payload: dict):
    from .adapters.document_structure import read_asset
    try:
        original=base64.b64decode(payload.get('original_base64',''),validate=True)
        if not original or len(original)>256*1024*1024:raise ValueError('来源文件为空或超过256MiB')
        content,mime=read_asset(original,str(payload.get('original_name','')),payload.get('asset') or {})
        return Response(content=content,media_type=mime)
    except (ValueError,TypeError,KeyError,binascii.Error) as exc:
        raise HTTPException(status_code=422,detail=str(exc)) from exc

@app.post('/api/algorithm/source/map')
def source_map(payload: dict):
    from .adapters.source_documents import map_source_table
    try:
        original=base64.b64decode(payload.get('original_base64',''),validate=True)
        if not original or len(original)>256*1024*1024:raise ValueError('来源文件为空或超过256MiB')
        return map_source_table(original,str(payload.get('original_name','')),payload.get('selection'))
    except (ValueError,TypeError,binascii.Error) as exc:
        raise HTTPException(status_code=422,detail=str(exc)) from exc

@app.post('/api/algorithm/source/export')
def source_export(payload: dict):
    """Return an original or source-family-preserving trajectory export.

    The Java registry supplies the immutable source bytes and the explicitly
    selected extraction table.  The adapter never guesses a table on export;
    this prevents a multi-table Excel/Word document from being silently
    rewritten in the wrong place.
    """
    from .adapters.source_documents import export_source_document
    try:
        original = base64.b64decode(str(payload.get('original_base64', '')), validate=True)
        if not original:
            raise ValueError('original source bytes are required')
        rows = payload.get('stations') or []
        output, name, content_type = export_source_document(
            original, str(payload.get('original_name') or 'trajectory.dat'), rows,
            payload.get('extraction'), mode=str(payload.get('mode') or 'PATCHED'),
            source_reference=payload.get('source_reference'))
    except (ValueError, TypeError, binascii.Error) as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
    return Response(output, media_type=content_type,
                    headers={'Content-Disposition': "attachment; filename=trajectory-export; filename*=UTF-8''" + __import__('urllib.parse', fromlist=['quote']).quote(Path(name).name)})

@app.post('/api/algorithm/survey/reduce')
def reduce_survey(payload: dict):
    from .shared.trajectory.reduction import reduce_trajectory,standard_table,export_standard_xlsx
    stations=reduce_trajectory(payload['rows'],payload['reference'],target_reference=payload.get('target_reference'),preserve_stored=payload.get('preserve_stored',False))
    design=reduce_trajectory(payload['design']['rows'],payload['design']['reference']) if payload.get('design') else None
    if payload.get('format')=='xlsx':return Response(export_standard_xlsx(stations,payload.get('name','井眼'),payload.get('kind','ACTUAL'),design),media_type='application/vnd.openxmlformats-officedocument.spreadsheetml.sheet',headers={'Content-Disposition':'attachment; filename=trajectory-reduction.xlsx'})
    return {'stations':[s.to_dict() for s in stations],'table':standard_table(stations,kind=payload.get('kind','ACTUAL'),design=design) if payload.get('include_table',True) else [],'reference':payload.get('target_reference') or payload['reference'],'method':'minimum_curvature','standard':'SY/T 5435-2012 §4, A.4/A.5'}

@app.post('/api/algorithm/survey/horizontal-bounds')
def survey_horizontal_bounds(payload: dict):
    from .shared.trajectory.reduction import reduce_trajectory
    from .shared.trajectory.horizontal_bounds import horizontal_bounds
    try:
        stations = reduce_trajectory(payload['rows'], payload['reference'],
            target_reference=payload.get('target_reference'), preserve_stored=True)
        return {'bounds': horizontal_bounds(stations), 'method': 'WHOLE_TRAJECTORY_MC_HORIZONTAL_AABB'}
    except (KeyError, ValueError, TypeError) as exc:
        raise HTTPException(status_code=422, detail=str(exc)) from exc
