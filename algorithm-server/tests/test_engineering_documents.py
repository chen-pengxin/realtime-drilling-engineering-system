from app.domain.engineering_documents import describe_document


def native(well="F-1", bore="F-1 A", name="15/9-F-1 A rev0"):
    return f"FIELD: SLEIPNER\nWELL NAME: {well}\nWELLBORE NAME: {bore}\nSurvey Name: {name}\nSurvey Date: 22.08.2013\nExtraction Date: 03.04.2018\nDrilled From: F-1\nSURVEY LIST\n"


def test_pdf_source_project_site_are_not_inverted():
    text = "Project: SLEIPNER TVD Reference: Rotary Table @ 54.90m\nSite: Volve F MD Reference: Rotary Table\nWell: F-1 North Reference: Grid\nWellbore: F-1 A Survey Calculation Method: Minimum Curvature\nDesign: 15/9-F-1 A rev0 Database: Production\nVersion: Phase: PLAN Tie On Depth: 2587\nSite Volve F, 15/9\n"
    d = describe_document("Standard Survey Report_rev0_PLAN.pdf", text)
    assert (d["source_project"], d["site"], d["well"], d["wellbore"]) == ("SLEIPNER", "Volve F", "F-1", "F-1 A")
    assert d["block_code"] == "15/9"
    assert d["document_role"] == "TRAJECTORY_REPORT"


def test_revisions_and_options_have_independent_identity():
    a = describe_document("Volve_F-10 A_rev#1_PROTOTYPE", native("F-10", "F-10 A", "F-10 A rev#1 STGAL 230915"))
    b = describe_document("Volve_F-10 A_rev#7_PLAN", native("F-10", "F-10 A", "F-10 A rev#7 LHULT 171115"))
    c = describe_document("Combined target EW_PROTOTYPE", native("F-10", "F-10 A", "Combined target EW"))
    assert a["series_name"] == b["series_name"] == "F-10 A"
    assert a["business_revision"] == "rev#1" and b["business_revision"] == "rev#7"
    assert c["series_name"] != a["series_name"]
    assert a["usage_state"] == "DRAFT" and b["usage_state"] == "HISTORICAL"


def test_actual_and_plan_are_not_the_same_series_kind():
    a = describe_document("F-1 A_ACTUAL", native())
    b = describe_document("F-1 A_PLAN", native())
    assert a["trajectory_kind"] == "ACTUAL" and b["trajectory_kind"] == "DESIGN"
    assert a["survey_date"] == "2013-08-22" and a["extraction_date"] == "2018-04-03"
    assert a["parent_wellbore"] == "F-1"


def test_unlabelled_native_survey_list_is_an_actual_survey():
    d = describe_document("15_9-19 SR_Standard_Survey_NPD.txt", native("15/9-19", "15/9-19 SR"))
    assert d["phase"] == "ACTUAL"
    assert d["trajectory_kind"] == "ACTUAL"


def test_conflicting_folder_never_overwrites_heading():
    d = describe_document("relief_PROTOTYPE", native("F-11", "Relief well 508m West"), "WellWellbore/15_9-F-11/Relief well 1049m West/relief_PROTOTYPE")
    assert d["wellbore"] == "Relief well 508m West"
    assert d["conflicts"][0]["field"] == "wellbore"
    assert d["entity_kind"] == "PLANNED_RELIEF"


def test_summary_and_forbidden_and_archive_have_distinct_roles():
    summary = describe_document("WellSummary F-1.pdf", "Well Level Summary Report\nWell: F-1 North Reference: Grid")
    assert summary["document_role"] == "WELL_SUMMARY" and not summary["wellbore"]
    assert describe_document("WCD-DO NOT USE - TO BE ERASED_PROTOTYPE", native())["usage_state"] == "FORBIDDEN"
    assert describe_document("Production_data.zip")["document_role"] == "ARCHIVE"
    assert describe_document("Volve production data.xlsx")["document_role"] == "PRODUCTION_DATA"


def test_unknown_document_does_not_invent_actual_or_project():
    d = describe_document("notes.txt", "memo")
    assert not d["source_project"] and d["trajectory_kind"] is None
