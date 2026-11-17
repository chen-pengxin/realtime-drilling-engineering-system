"""A resurvey changes measurements by MD, never the immutable parent snapshot."""
from copy import deepcopy
import math
import sys
from pathlib import Path

import pytest

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from app.shared.trajectory.min_curvature import compute_minimum_curvature
from app.shared.trajectory.revisions import preview_revision


@pytest.fixture
def baseline():
    return [s.to_dict() for s in compute_minimum_curvature(
        [(0, 1, 0), (30, 5, 10), (60, 10, 20), (90, 12, 30), (120, 15, 40)], (100, 200, 0))]


def payload(baseline, **overrides):
    return {"revision_type": "RESURVEY", "kind": "ACTUAL", "existing": baseline,
            "parent_reference": {"crs": "EPSG:23031", "depth_datum_elevation_m": 25},
            "reference": {"north_reference": "GRID_NORTH", "length_unit": "m"},
            "rows": [{"md": 60, "inclination": 11, "azimuth": 22}],
            "replace_from_md": 60, "replace_to_md": 60, **overrides}


def test_resurvey_replaces_same_md_preserves_parent_and_reduces_downstream(baseline):
    original = deepcopy(baseline)
    result = preview_revision(payload(baseline))
    assert baseline == original
    assert not result["has_error"]
    assert result["count"] == len(baseline)
    assert result["stations"][:2] == original[:2]
    assert result["stations"][2]["inclination"] == 11
    assert result["stations"][3]["inclination"] == original[3]["inclination"]
    assert result["stations"][-1]["north"] != original[-1]["north"]
    assert result["change_summary"]["resurvey"] == 1
    assert result["incremental_from_md"] == 30  # the segment before the revised station
    assert result["downstream_recalculation"]["to_md"] == 120


def test_resurvey_interval_removes_superseded_points_and_adds_new_ones(baseline):
    result = preview_revision(payload(baseline, replace_from_md=30, replace_to_md=90,
        rows=[{"md": 35, "inclination": 6, "azimuth": 12}, {"md": 85, "inclination": 13, "azimuth": 28}]))
    assert [s["md"] for s in result["stations"]] == [0, 35, 85, 120]
    assert result["change_summary"]["removed"] == 3
    assert result["change_summary"]["added"] == 2
    assert result["incremental_from_md"] == 0


def test_resurvey_converts_only_incoming_magnetic_angles_once(baseline):
    result = preview_revision(payload(baseline,
        reference={"north_reference": "MAGNETIC_NORTH", "magnetic_declination": 5, "meridian_convergence": 2},
        rows=[{"md": 60, "inclination": 11, "azimuth": 19}]))
    assert result["stations"][1]["azimuth"] == 10
    assert result["stations"][2]["azimuth"] == 22
    assert result["normalized_reference"]["north_reference"] == "GRID_NORTH"


def test_resurvey_handles_incoming_feet_radians_without_rescaling_parent(baseline):
    result = preview_revision(payload(baseline,
        reference={"length_unit": "ft", "angle_unit": "rad"},
        rows=[{"md": 60 / 0.3048, "inclination": math.radians(11), "azimuth": math.radians(22)}]))
    assert result["stations"][1] == baseline[1]
    assert result["stations"][2]["inclination"] == pytest.approx(11)
    assert result["stations"][2]["md"] == pytest.approx(60)


def test_unchanged_resurvey_has_no_recalculation_range(baseline):
    result = preview_revision(payload(baseline, rows=[baseline[2]]))
    assert result["change_summary"]["changed_points"] == 0
    assert result["incremental_from_md"] is None


@pytest.mark.parametrize("revision", ["RESURVEY", "INCREMENTAL"])
def test_incoming_cleaning_cannot_remove_parent_measurements(baseline, revision):
    source = deepcopy(baseline)
    values = {"rows": [{"md": 150, "inclination": 15, "azimuth": 40}]} if revision == "INCREMENTAL" else {}
    result = preview_revision(payload(baseline, revision_type=revision,
        policy={"max_dls_deg_per_30m": .01, "remove_anomalies": True}, **values))
    assert baseline == source
    assert [s["md"] for s in result["stations"]] == ([0, 30, 60, 90, 120, 150] if revision == "INCREMENTAL" else [0, 30, 60, 90, 120])
    for old in source:
        if revision == "RESURVEY" and old["md"] == 60:
            continue
        current = next(r for r in result["stations"] if r["md"] == old["md"])
        assert current["inclination"] == old["inclination"]
        assert current["azimuth"] == old["azimuth"]


def test_resurvey_keeps_the_stored_prefix_coordinate_anchor(baseline):
    # Existing snapshots have already been rounded by DECIMAL database columns.
    rounded = [{k: round(float(v), 6) for k, v in row.items()} for row in baseline]
    result = preview_revision(payload(rounded))
    assert result["stations"][:2] == rounded[:2]
    expected = compute_minimum_curvature(
        [(30, 5, 10), (60, 11, 22), (90, 12, 30), (120, 15, 40)],
        tuple(rounded[1][k] for k in ("north", "east", "tvd")))
    assert result["stations"][-1] == expected[-1].to_dict()


def test_first_measurement_can_be_resurveyed_without_moving_its_coordinate(baseline):
    result = preview_revision(payload(baseline, replace_from_md=0, replace_to_md=0,
        rows=[{"md": 0, "inclination": 2, "azimuth": 5}]))
    assert result["stations"][0]["inclination"] == 2
    assert result["stations"][0]["north"] == baseline[0]["north"]
    assert result["stations"][1]["north"] != baseline[1]["north"]


def test_equal_numeric_depth_datum_accepts_integer_or_decimal_encoding(baseline):
    result = preview_revision(payload(baseline, reference={"depth_datum_elevation_m": 25.0}))
    assert not result["has_error"]


@pytest.mark.parametrize("overrides, message", [
    ({"existing": []}, "父版本"),
    ({"kind": "DESIGN"}, "只适用于实钻"),
    ({"replace_from_md": 70, "replace_to_md": 60}, "范围无效"),
    ({"replace_from_md": 60, "replace_to_md": 150}, "父版本井深"),
    ({"replace_from_md": 70, "replace_to_md": 90}, "声明的替换范围"),
    ({"replace_from_md": 0, "replace_to_md": 60}, "首测点和末测点"),
    ({"reference": {"crs": "EPSG:32631"}}, "坐标和深度基准"),
    ({"reference": {"depth_datum_elevation_m": 26}}, "坐标和深度基准"),
    ({"revision_type": "UNKNOWN"}, "导入方式"),
])
def test_reject_invalid_resurvey(baseline, overrides, message):
    with pytest.raises(ValueError, match=message):
        preview_revision(payload(baseline, **overrides))


def test_append_conflict_is_blocked_instead_of_overwriting_measurement(baseline):
    result = preview_revision(payload(baseline, revision_type="INCREMENTAL"))
    assert result["has_error"]
    assert result["stations"][2]["inclination"] == baseline[2]["inclination"]


def test_append_extends_from_last_existing_segment(baseline):
    result = preview_revision(payload(baseline, revision_type="INCREMENTAL",
        rows=[{"md": 150, "inclination": 17, "azimuth": 45}]))
    assert not result["has_error"]
    assert result["stations"][:5] == baseline
    assert result["incremental_from_md"] == 120


def test_full_import_normalizes_units_and_grid_once():
    result = preview_revision({"rows": [{"md": 0, "inclination": 0, "azimuth": 20},
        {"md": 100, "inclination": 10, "azimuth": 20}],
        "reference": {"north_reference": "MAGNETIC_NORTH", "magnetic_declination": 5,
            "meridian_convergence": 2, "length_unit": "ft", "origin_north": 1000}})
    assert result["stations"][1]["md"] == 30.48
    assert result["stations"][1]["azimuth"] == 23
    assert result["stations"][0]["north"] == 304.8


def test_invalid_points_remain_blocking(baseline):
    result = preview_revision(payload(baseline, rows=[
        {"md": 60, "inclination": 11, "azimuth": 22},
        {"md": 60, "inclination": -1, "azimuth": 22}]))
    assert result["has_error"]


def test_http_preview_keeps_the_same_version_contract(baseline):
    import asyncio
    import httpx
    from app.main import app
    async def call():
        async with httpx.AsyncClient(transport=httpx.ASGITransport(app=app), base_url="http://test") as client:
            return await client.post("/api/algorithm/source/preview", json=payload(baseline))
    response = asyncio.run(call())
    assert response.status_code == 200
    assert response.json()["change_summary"]["resurvey"] == 1


def test_single_point_resurvey_file_can_be_extracted(tmp_path):
    from app.adapters.source_documents import inspect_document
    source = tmp_path / "resurvey.csv"
    source.write_text("MD,INC,AZI\n60,11,22\n")
    parsed = inspect_document(source)
    assert len(parsed["trajectories"]) == 1
    assert len(parsed["trajectories"][0]["rows"]) == 1


def test_patched_source_export_preserves_unrelated_text_and_table_shape(tmp_path):
    from app.adapters.source_documents import export_source_document, inspect_document

    source = tmp_path / "survey.csv"
    source.write_text(
        "资料说明\nMD,INC,AZI,备注\n0,0,0,井口\n100,10,20,原始\n页尾说明\n",
        encoding="utf-8",
    )
    parsed = inspect_document(source)
    stations = [{"md": 0, "inclination": 1, "azimuth": 2},
                {"md": 120, "inclination": 12, "azimuth": 22}]
    exported, name, content_type = export_source_document(
        source.read_bytes(), source.name, stations, parsed["trajectories"][0]
    )
    text = exported.decode("utf-8-sig")
    assert name == source.name
    assert content_type.startswith("text/plain")
    assert "资料说明" in text and "页尾说明" in text
    assert "0,1,2,井口" in text and "120,12,22,原始" in text
    roundtrip = tmp_path / "roundtrip.csv"
    roundtrip.write_bytes(exported)
    reparsed = inspect_document(roundtrip)
    assert [row["md"] for row in reparsed["trajectories"][0]["rows"]] == [0.0, 120.0]


def test_resurvey_reuses_only_unaffected_collision_cache_blocks(tmp_path, monkeypatch):
    from app.tasks.anticollision.evaluation import scan_pair
    monkeypatch.setenv("DRILLING_SCAN_CACHE", str(tmp_path / "scan.sqlite3"))
    parent = compute_minimum_curvature([(md, 1 + md / 25, md / 10) for md in range(0, 401, 50)])
    neighbor = compute_minimum_curvature([(md, 1 + md / 25, md / 10) for md in range(0, 401, 50)], (0, 100, 0))
    original = scan_pair(parent, neighbor, step=20).to_dict()
    assert original["incremental"]["recomputed_blocks"] == 4
    preview = preview_revision(payload([s.to_dict() for s in parent],
        rows=[{"md": 250, "inclination": 12, "azimuth": 28}],
        replace_from_md=250, replace_to_md=250))
    revised = compute_minimum_curvature(preview["stations"])
    result = scan_pair(revised, neighbor, step=20).to_dict()
    assert result["incremental"]["reused_ranges"] == [[0, 100], [100, 200]]
    assert result["incremental"]["recomputed_ranges"] == [[200, 300], [300, 400]]
