from app.domain.chinese_engineering import build_manifest
from app.domain.engineering_documents import describe_document


def record(folder, filename, text, trajectories=None):
    return {"relative_path": f"采气二厂煤岩气完井资料+设计（11口）/{folder}井完井资料/{filename}",
            "text_excerpt": text, "sha256": filename, "trajectories": trajectories or []}


def test_body_title_corrects_reversed_filename_without_changing_the_original_path():
    r = record("双48-45YH4", "双45-48YH4钻井液完井总结.doc",
               "长庆油田分公司采气二厂气田产能建设项目组\n双48-45YH4井\n（双48-45井组，共3口）\n钻井液完井总结")
    d = describe_document("双45-48YH4钻井液完井总结.doc", r["text_excerpt"], r["relative_path"])
    assert d["well"] == "双48-45YH4"
    assert d["site"] == "双48-45"
    assert d["conflicts"][0]["value"] == "双45-48YH4"
    assert d["source_project"] == d["block_code"] == ""


def test_foreign_design_in_a_folder_uses_its_title_and_keeps_the_conflict():
    r = record("米41-37YH3", "米45-30YH5井钻井工程设计(7.docx", "米45-30YH5井钻井工程设计")
    d = describe_document("米45-30YH5井钻井工程设计(7.docx", r["text_excerpt"], r["relative_path"])
    assert d["well"] == "米45-30YH5"
    assert d["directory_well"] == "米41-37YH3"
    assert d["conflicts"][0]["field"] == "directory"


def test_truncated_and_misspelled_names_do_not_become_physical_wells():
    a = record("米45-30YH3", "米45-30指令统计.docx", "")
    b = record("米45-30YH3", "米45-30TH3井固井卡片.doc", "米45-30YH3井\n（米45-30井组，共7口）\n固井卡片")
    m = build_manifest([a, b])
    assert m["primary_wells"] == ["米45-30YH3"]
    assert {t["well"] for f in m["files"] for t in f["targets"]} == {"米45-30YH3"}


def test_shared_pad_is_a_parent_and_not_a_merged_well():
    records = [record(n, n+"井基本数据表.doc", n+"井基本数据表\n（米41-37井组，共4口）")
               for n in ("米41-37YH3", "米41-37YH5")]
    m = build_manifest(records)
    assert len(m["primary_wells"]) == 2
    assert {t["site"] for f in m["files"] for t in f["targets"]} == {"米41-37"}


def test_same_numeric_prefix_alone_does_not_infer_shared_pad():
    records = [record(n, n+"井工程设计.docx", n+"井工程设计") for n in ("米41-37YH3", "米41-37YH5")]
    m = build_manifest(records)
    assert len({t["site"] for f in m["files"] for t in f["targets"]}) == 2
    assert all(t["site_basis"].endswith("待确认）") for f in m["files"] for t in f["targets"])


def test_catalog_manifest_exposes_only_the_four_confirmed_engineering_levels():
    manifest = build_manifest([record("米125H", "米125H井工程设计.pdf", "米125H井工程设计")])
    assert manifest["scope_order"] == ["BLOCK", "FIELD", "WELL", "WELLBORE", "PLAN/SURVEY"]
    assert "project_status" not in manifest


def test_multiwell_report_is_one_original_with_two_bindings():
    a = record("米41-37YH3", "申请报告.doc", "煤岩气（米41-37YH3、米45-30YH3）井技术套管下深优化申请报告")
    b = record("米45-30YH3", "米45-30YH3井基本数据表.doc", "米45-30YH3井基本数据表")
    m = build_manifest([a, b])
    report = next(f for f in m["files"] if "申请" in f["path"])
    assert len(report["targets"]) == 2
    assert report["descriptor"]["document_role"] == "ENGINEERING_REPORT"
    assert len(m["files"]) == 2


def test_pilot_is_a_separate_bore_only_when_explicitly_supported():
    relation = "说明：麒35井变更为麒35H井的导眼井，麒35H井为麒35井回填侧钻实施的水平井。原井眼水泥填至侧钻点（2250m）以上50m。"
    records = [record("麒35H", "麒35H井钻井工程设计.pdf", "麒35H井钻井工程设计\n"+relation),
               record("麒35H", "麒35井工程设计.pdf", "麒35井钻井工程设计"),
               record("麒35H", "麒35H井井斜数据表.xlsx", "麒35井单点数据表\n麒35H井基本数据表",
                      [{"locator": "sheet:先导眼单点:header:2", "kind": "ACTUAL"},
                       {"locator": "sheet:水平井单点:header:2", "kind": "ACTUAL"}])]
    m = build_manifest(records)
    assert m["primary_wells"] == ["麒35H"]
    assert m["bore_relationships"][0]["kickoff_md"] == 2250
    pilot = next(f for f in m["files"] if f["path"].endswith("麒35井工程设计.pdf"))
    assert pilot["targets"][0]["well"] == "麒35H"
    assert pilot["targets"][0]["wellbore"] == "麒35"
    workbook = next(f for f in m["files"] if f["path"].endswith(".xlsx"))
    assert [e["wellbore"] for e in workbook["extractions"]] == ["麒35", "麒35H"]


def test_neighbouring_wells_and_a_geological_structure_are_not_catalog_entities():
    d = describe_document("米125H井工程设计.pdf", "米125H井工程设计\n构造：鄂尔多斯盆地伊陕斜坡\n邻井米126井", "")
    assert d["source_wells"] == ["米125H"]
    assert d["geological_structure"] == "鄂尔多斯盆地伊陕斜坡"
    assert d["block_code"] == ""


def test_survey_files_are_actual_and_do_not_fabricate_business_revision_numbers():
    a = describe_document("双48-45YH4井井斜数据表.xls")
    b = describe_document("双48-45YH4井井斜数据表（4235米）.xls")
    assert a["phase"] == b["phase"] == "ACTUAL"
    assert a["business_revision"] == b["business_revision"] == ""
    assert a["series_name"] == b["series_name"] == "实钻测斜"
