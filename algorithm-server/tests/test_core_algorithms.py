"""Retained IO, reports, realtime and public-contract regressions after engine replacement."""
import math
import sys
import zipfile
import csv
import json
import io
from pathlib import Path
import numpy as np
import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from app.domain.evaluation import (
    CollisionThresholds,
    ErrorModelConfig,
    scan_pair,
)
from app.domain.min_curvature import compute_minimum_curvature, interpolate_segment
from app.tasks.replanning import trajectory_planning as planning_task
from app.adapters.survey_io import parse_file
from app.adapters.survey_io import stations_to_csv, stations_to_json
from app.main import export_survey
from app.schemas import CollisionInput, PlanningInput, SurveyExportInput
from app.adapters.realtime import RealtimeIngestService, ReorderDeduplicator, normalize_event
from app.domain.reports import export_report
from app.main import report_export
from app.schemas import ReportInput
from app.tasks.prediction.prediction import PredictionManager


def test_minimum_curvature_vertical_and_horizontal():
    vertical = compute_minimum_curvature([(0, 0, 0), (100, 0, 0)])
    assert math.isclose(vertical[-1].tvd, 100.0, abs_tol=1e-10)
    assert math.isclose(vertical[-1].north, 0.0, abs_tol=1e-10)
    horizontal = compute_minimum_curvature([(0, 90, 0), (100, 90, 0)])
    assert math.isclose(horizontal[-1].north, 100.0, abs_tol=1e-10)
    assert math.isclose(horizontal[-1].tvd, 0.0, abs_tol=1e-10)


def test_product_collision_contract_requires_both_methods_and_one_meter_default():
    survey = {"stations": [{"md": 0, "inclination": 0, "azimuth": 0}]}
    payload = CollisionInput(reference=survey, neighbor=survey)
    assert payload.mode == "BOTH"
    assert payload.step == 1.0
    with pytest.raises(ValueError):
        CollisionInput(reference=survey, neighbor=survey, mode="SPACING")


def test_prediction_not_configured_result_keeps_empty_future_fields():
    task = PredictionManager().start(9.0, "cpu", None, {}, 1, 2, 3, 4)
    assert task.status == "MODEL_NOT_CONFIGURED"
    assert task.result["sample"] is False
    assert task.result["forward_9m"] is None
    assert task.result["forward_28m"] is None
    assert task.result["toolface"] is None
    assert task.result["sliding_ratio"] is None
    assert task.result["model_evaluation"] is None


def test_planning_contract_requires_combined_collision_constraint():
    target = {"north": 0, "east": 0, "tvd": 100}
    payload = PlanningInput(
        start_md=0, start_north=0, start_east=0, start_tvd=0,
        start_inclination=0, start_azimuth=0, target=target, minimum_sf=1.8,
    )
    assert payload.collision_constraint == "SEPARATION_FACTOR_AND_CENTER_DISTANCE"
    assert payload.centerline_min_distance_m == 5.0
    assert payload.scan_step == 1.0
    assert payload.minimum_sf == 1.8
    with pytest.raises(ValueError):
        PlanningInput(
            start_md=0, start_north=0, start_east=0, start_tvd=0,
            start_inclination=0, start_azimuth=0, target=target,
            collision_constraint="SEPARATION_FACTOR",
        )


def test_report_bundle_contains_word_pdf_and_excel():
    path = export_report("test", {"answer": 42}, "bundle")
    try:
        with zipfile.ZipFile(path) as archive:
            assert set(archive.namelist()) == {
                "drilling-report.docx",
                "drilling-report.pdf",
                "drilling-report.xlsx",
            }
            docx = zipfile.ZipFile(io.BytesIO(archive.read("drilling-report.docx")))
            xlsx = zipfile.ZipFile(io.BytesIO(archive.read("drilling-report.xlsx")))
            assert "word/document.xml" in docx.namelist()
            assert "xl/workbook.xml" in xlsx.namelist()
            assert archive.read("drilling-report.pdf").startswith(b"%PDF-")
    finally:
        path.unlink(missing_ok=True)


def test_network_report_bundle_contains_per_well_table_and_svg_curve():
    path = export_report("network", {
        "neighbor_count": 1,
        "candidate_count": 1,
        "results": [{"neighbor_well_id": 8, "neighbor_survey_version_id": 80,
                     "min_distance": 4.0, "operating_sf": 1.0,
                     "operating_risk_level": "RED", "reference_md": 20.0,
                     "neighbor_md": 21.0}],
    }, "bundle")
    try:
        with zipfile.ZipFile(path) as archive:
            assert "network-results.svg" in archive.namelist()
            assert "manifest.json" in archive.namelist()
            manifest = json.loads(archive.read("manifest.json"))
            assert "network_results" in manifest["structured_sections"]
            assert "network-results.svg" in manifest["graphics"]
    finally:
        path.unlink(missing_ok=True)


@pytest.mark.parametrize("file_format,signature", [
    ("docx", b"PK"), ("xlsx", b"PK"), ("pdf", b"%PDF-"), ("json", b"{"),
])
def test_report_single_format_outputs_expected_container(file_format, signature):
    path = export_report("test", {"answer": 42}, file_format)
    try:
        content = path.read_bytes()
        assert content.startswith(signature)
        if file_format in {"docx", "xlsx"}:
            with zipfile.ZipFile(path) as archive:
                assert archive.testzip() is None
    finally:
        path.unlink(missing_ok=True)


@pytest.mark.parametrize(
    ("file_format", "media_type", "filename"),
    [
        ("bundle", "application/zip", "drilling-report.zip"),
        ("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", "drilling-report.docx"),
        ("pdf", "application/pdf", "drilling-report.pdf"),
        ("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", "drilling-report.xlsx"),
        ("json", "application/json", "drilling-report.json"),
    ],
)
def test_report_export_route_sets_download_headers_and_returns_valid_content(file_format, media_type, filename):
    response = report_export(ReportInput(title="API report", format=file_format, payload={"answer": 42}))
    path = Path(response.path)
    try:
        content = path.read_bytes()
        assert response.media_type == media_type
        assert filename in response.headers["content-disposition"]
        if file_format == "bundle":
            with zipfile.ZipFile(io.BytesIO(content)) as archive:
                assert set(archive.namelist()) == {
                    "drilling-report.docx",
                    "drilling-report.pdf",
                    "drilling-report.xlsx",
                }
        elif file_format in {"docx", "xlsx"}:
            with zipfile.ZipFile(io.BytesIO(content)) as archive:
                assert archive.testzip() is None
        elif file_format == "pdf":
            assert content.startswith(b"%PDF-")
        else:
            assert json.loads(content)["payload"]["answer"] == 42
    finally:
        path.unlink(missing_ok=True)


def test_export_presets_map_compass_and_wellplan_labels():
    stations = compute_minimum_curvature([(0, 0, 0), (30, 3, 10)])
    compass = list(csv.reader(io.StringIO(stations_to_csv(stations, "COMPASS"))))
    assert compass[0] == ["MD", "INC", "AZI", "NORTH", "EAST", "TVD", "DLS", "CURVATURE"]
    wellplan = json.loads(stations_to_json(stations, "WELLPLAN"))
    assert set(wellplan[0]) == {"MeasuredDepth", "Inclination", "Azimuth", "Northing", "Easting", "TVD", "DoglegSeverity", "Curvature"}


def test_export_custom_mapping_overrides_preset_labels():
    stations = compute_minimum_curvature([(0, 0, 0), (30, 3, 10)])
    rows = list(csv.reader(io.StringIO(stations_to_csv(stations, "COMPASS", {"md": "Measured Depth"}))))
    assert rows[0][0] == "Measured Depth"


def test_export_schema_accepts_legacy_and_new_field_names_without_shadow_warning():
    common = {"stations": [{"md": 0, "inclination": 0, "azimuth": 0}]}
    legacy = SurveyExportInput(**common, schema="COMPASS")
    current = SurveyExportInput(**common, export_schema="WELLPLAN")
    assert legacy.export_schema == "COMPASS"
    assert current.export_schema == "WELLPLAN"
    assert legacy.model_dump(by_alias=True)["schema"] == "COMPASS"


def test_survey_export_route_accepts_legacy_schema_alias():
    response = export_survey(SurveyExportInput(
        stations=[{"md": 0, "inclination": 0, "azimuth": 0}],
        schema="COMPASS",
    ))
    assert response.body.decode("utf-8").splitlines()[0] == "MD,INC,AZI,NORTH,EAST,TVD,DLS,CURVATURE"


def test_import_converts_units_north_reference_and_tvd_sign(tmp_path):
    survey = tmp_path / "survey.csv"
    survey.write_text(
        "md,inclination,azimuth\n0,1.5707963267948966,0\n100,1.5707963267948966,0\n",
        encoding="utf-8",
    )
    stations = parse_file(
        survey,
        origin=(10, 20, 100),
        length_unit="ft",
        angle_unit="rad",
        north_reference="MAGNETIC_NORTH",
        tvd_positive="U",
        # Declination and convergence use the same angle unit as the survey
        # file. Grid convergence is ignored when the source bearing is
        # magnetic.
        magnetic_declination=math.pi / 2,
        meridian_convergence=math.pi,
    )
    assert math.isclose(stations[-1].md, 30.48, abs_tol=1e-9)
    assert math.isclose(stations[0].north, 3.048, abs_tol=1e-9)
    assert math.isclose(stations[0].east, 6.096, abs_tol=1e-9)
    assert math.isclose(stations[0].tvd, -30.48, abs_tol=1e-9)
    assert math.isclose(stations[-1].north, stations[0].north, abs_tol=1e-8)
    assert math.isclose(stations[-1].east - stations[0].east, 30.48, abs_tol=1e-8)
    assert math.isclose(stations[-1].tvd, stations[0].tvd, abs_tol=1e-8)


def test_import_tvd_positive_up_reflects_vertical_increments(tmp_path):
    survey = tmp_path / "vertical-up.csv"
    survey.write_text(
        "md,inclination,azimuth\n0,0,0\n100,0,0\n",
        encoding="utf-8",
    )
    stations = parse_file(
        survey,
        origin=(0, 0, 100),
        length_unit="m",
        angle_unit="deg",
        tvd_positive="U",
    )
    assert stations[0].tvd == pytest.approx(-100.0)
    assert stations[-1].tvd == pytest.approx(-200.0)


def test_realtime_adapter_normalizes_and_reorders_idempotently():
    stream = ReorderDeduplicator(reorder_window=2.0)
    first = normalize_event({"well_id": "A", "md": 100, "inc": 1, "azi": 2}, "test")
    late = normalize_event({"well_id": "A", "md": 101, "inc": 1, "azi": 2}, "test")
    assert stream.ingest(first).accepted == ()
    assert [event.md for event in stream.ingest(late).accepted] == [100.0]
    assert stream.ingest(first).duplicate is True
    assert [event.md for event in stream.flush()] == [101.0]


def test_realtime_ingest_service_isolates_invalid_rows_and_flushes():
    service = RealtimeIngestService(default_reorder_window=2.0)
    result = service.ingest("wits", [
        {"well_id": "A", "md": 100, "inc": 1, "azi": 2},
        {"well_id": "A", "md": 101, "inc": 1, "azi": 2},
        {"well_id": "A", "md": "bad", "inc": 1, "azi": 2},
    ])
    assert [event.md for event in result.accepted] == [100.0]
    assert len(result.invalid) == 1
    flushed = service.ingest("wits", [], flush=True)
    assert [event.md for event in flushed.flushed] == [101.0]
    duplicate = service.ingest("wits", [{"well_id": "A", "md": 100, "inc": 1, "azi": 2}])
    assert duplicate.duplicates == 1


def test_realtime_adapter_isolates_station_arriving_before_emitted_watermark():
    stream = ReorderDeduplicator(reorder_window=2.0)
    assert stream.ingest(normalize_event({"well_id": "A", "md": 100, "inc": 1, "azi": 2}, "test")).accepted == ()
    assert [event.md for event in stream.ingest(
        normalize_event({"well_id": "A", "md": 102, "inc": 1, "azi": 2}, "test")
    ).accepted] == [100.0]

    late = stream.ingest(normalize_event({"well_id": "A", "md": 99, "inc": 1, "azi": 2}, "test"))
    assert late.accepted == ()
    assert late.out_of_order is True
    assert late.reason == "before emitted watermark"
    assert stream.ingest(normalize_event({"well_id": "A", "md": 99, "inc": 1, "azi": 2}, "test")).duplicate is True
    assert [event.md for event in stream.flush()] == [102.0]


def test_realtime_replay_is_stateless_after_partial_batch():
    service = RealtimeIngestService(default_reorder_window=3.0)
    first = service.ingest("gateway", [
        {"well_id": "A", "md": 100, "inc": 1, "azi": 2},
        {"well_id": "A", "md": 103, "inc": 1, "azi": 2},
    ])
    assert [event.md for event in first.accepted] == [100.0]
    # The second station was buffered in the in-process stream.  A durable
    # gateway retry must be able to replay both pending rows as one batch.
    retry = service.ingest("gateway", [
        {"well_id": "A", "md": 103, "inc": 1, "azi": 2},
    ], flush=True, replay=True)
    assert [event.md for event in retry.accepted + retry.flushed] == [103.0]


def test_realtime_http_contract_accepts_numeric_well_id_from_java_gateway():
    from app.schemas import RealtimeIngestInput

    payload = RealtimeIngestInput(
        source="WITS_TCP:rig-01:5000",
        well_id=42,
        events=[{"md": 1000, "inclination": 12.4, "azimuth": 86.1}],
    )
    assert payload.well_id == "42"
