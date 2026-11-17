"""Small, dependency-light report exporters for completed evaluation/planning tasks."""
from __future__ import annotations

from pathlib import Path
import json
import tempfile
import zipfile
import math
import os
import platform
from html import escape
from typing import Any


SECTION_LABELS = {'summary':'评价摘要','configuration_and_audit':'参数与约束审计','source_versions':'数据来源与版本','collision_profile':'防碰扫描明细','risk_segments':'风险井段','network_results':'井间关系与防碰结果','planning_segments':'规划分段参数','trajectory':'轨道数据','limitations':'使用说明'}
FIELD_LABELS = {'key':'字段','parameter':'参数','value':'数值','md':'MD (m)','neighbor_md':'邻井 MD (m)','center_distance':'中心距 (m)','horizontal_distance':'水平距 (m)','normal_distance':'法面距 (m)','sf':'分离系数 SF','risk_level':'风险等级','error_envelope_radius':'误差包络 (m)','net_distance':'净距 (m)','start_md':'起点 MD (m)','end_md':'终点 MD (m)','min_center_distance':'最小中心距 (m)','min_sf':'最小 SF','point_count':'测点数','neighbor_well_id':'邻井 ID','neighbor_survey_version_id':'邻井版本 ID','min_distance':'最小中心距 (m)','operating_sf':'最小 SF','operating_risk_level':'综合风险','reference_md':'正钻井 MD (m)','segment_no':'段号','segment_length':'段长 (m)','dogleg_deg_per_30m':'狗腿度 (°/30m)','toolface_deg':'工具面 (°)','toolface':'工具面 (°)','sliding_ratio':'滑动比例','composite_ratio':'复合比例','constraint_status':'约束状态','toolface_change_deg':'工具面变化 (°)','north':'N (m)','east':'E (m)','tvd':'TVD (m)','inclination':'井斜 (°)','azimuth':'方位 (°)','limitation':'说明'}
FIELD_LABELS.update({'normal_distance':'法面投影距 (m)','status':'计算状态','solver':'求解器','total_length':'轨迹总长度 (m)','terminal_error':'终端位置误差 (m)','target_error':'中靶误差 (m)','elapsed_seconds':'计算耗时 (s)','iterations':'迭代次数','constraint_violations':'未满足的约束','best_effort':'近似不可行方案','failure_reason':'失败原因','report_scope':'报告类型','solver_metadata':'求解信息','actual_solver':'实际求解器','engine':'数值引擎','objective':'优化目标','constraint_audit':'约束复核','feasible':'方案可行','position_error_m':'位置误差 (m)','inclination_error_deg':'井斜误差 (°)','azimuth_error_deg':'方位误差 (°)','tangent_direction_error_deg':'切向误差 (°)','minimum_scaled_constraint':'最小归一化约束裕量','minimum_sf':'最小分离系数 SF','sf_threshold':'分离系数阈值','sf_margin':'分离系数裕量','minimum_centerline_distance_m':'最小中心距 (m)','centerline_minimum_m':'中心距下限 (m)','centerline_margin_m':'中心距裕量 (m)','verification':'复核方法','input_versions':'输入测斜版本','reference_version':'正钻井版本','wellbore':'井眼','neighbors':'邻井版本','error_model':'误差模型','normal_distance_definition':'法面投影距定义','mode':'评价方法','interpolation_method':'轨迹插值方法','geometry_tolerance_m':'圆弧离散容差 (m)'})

def _parameter_label(path):
    import re
    return ' / '.join(FIELD_LABELS.get(re.sub(r'\[\d+\]','',key),re.sub(r'\[\d+\]','',key))+''.join(re.findall(r'\[\d+\]',key)) for key in path.split('.'))


def _safe_value(value: Any) -> str:
    return '' if value is None else format(value,'.8g') if isinstance(value,float) else value if isinstance(value, str) else json.dumps(value, ensure_ascii=False) if isinstance(value, (list, dict, tuple)) else str(value)


def _office_title(title: str) -> str:
    """Return a readable office title when the headless renderer lacks CJK glyphs."""
    return title


def _structured_sections(payload: dict[str, Any]) -> list[tuple[str, list[str], list[list[Any]]]]:
    """Return stable tabular sections for office reports.

    The scalar summary remains useful for integrations, while these sections
    make the exported artefacts auditable by an engineer without opening the
    raw JSON response.
    """
    # Keep the first page useful as a summary.  Large repeated arrays belong in
    # their own tables; serialising them into one ``value`` cell makes Word wrap
    # JSON at every character and produces an unreadable report.
    repeated_keys = {
        "profile", "risk_segments", "segments", "trajectory", "observations",
        "results", "pairs", "metric_summaries", "per_well_summaries", "limitations", "collision_evaluation", "solver_metadata", "error_model", "input_versions", "reference_version", "wellbore", "neighbors", "report_sections",
    }
    sections: list[tuple[str, list[str], list[list[Any]]]] = [
        (
            "summary",
            ["key", "value"],
            [[FIELD_LABELS.get(key,key), _safe_value(value)] for key, value in payload.items() if key not in repeated_keys],
        )
    ]
    metadata=[]
    sources=[]
    def flatten(value, prefix='', destination=None):
        destination = metadata if destination is None else destination
        if isinstance(value,dict):
            for key,item in value.items():
                if key not in {'runs','configuration','report','initialization','profile','trajectory','stations'}:flatten(item,f'{prefix}.{key}' if prefix else key,destination)
        elif isinstance(value,(str,int,float,bool)) or value is None:
            destination.append([_parameter_label(prefix),value])
    for key in ('solver_metadata','error_model','wellbore','input_versions','reference_version','neighbors'):
        destination=sources if key in ('wellbore','input_versions','reference_version','neighbors') else metadata
        if isinstance(payload.get(key),dict):flatten(payload[key],key,destination)
        elif isinstance(payload.get(key),list):
            for i,item in enumerate(payload[key]):flatten(item,f'{key}[{i}]',destination)
        elif payload.get(key) is not None:flatten(payload[key],key,destination)
    if metadata:sections.append(('configuration_and_audit',['parameter','value'],metadata))
    if sources:sections.append(('source_versions',['parameter','value'],sources))
    collision=payload.get('collision_evaluation')
    if isinstance(collision,dict):
        sections.extend((name,headers,rows) for name,headers,rows in _structured_sections(collision) if name in ('network_results','risk_segments','collision_profile'))
    profile = payload.get("profile")
    if isinstance(profile, (list, tuple)) and profile:
        columns = ["md", "neighbor_md", "center_distance", "horizontal_distance", "normal_distance", "sf", "risk_level", "error_envelope_radius", "net_distance"]
        sections.append(("collision_profile", columns, [[row.get(column) for column in columns] for row in profile if isinstance(row, dict)]))
    risk_segments = payload.get("risk_segments")
    if isinstance(risk_segments, (list, tuple)) and risk_segments:
        columns = ["start_md", "end_md", "risk_level", "min_center_distance", "min_sf", "point_count"]
        sections.append(("risk_segments", columns, [[row.get(column) for column in columns] for row in risk_segments if isinstance(row, dict)]))
    results = payload.get("results")
    if isinstance(results, (list, tuple)) and results:
        columns = ["neighbor_well_id", "neighbor_survey_version_id", "min_distance", "horizontal_distance", "normal_distance", "operating_sf", "operating_risk_level", "reference_md", "neighbor_md"]
        sections.append(("network_results", columns, [[row.get(column) for column in columns] for row in results if isinstance(row, dict)]))
    segments = payload.get("segments")
    if isinstance(segments, (list, tuple)) and segments:
        columns = ["segment_no", "start_md", "end_md", "segment_length", "dogleg_deg_per_30m", "toolface_deg", "sliding_ratio", "composite_ratio", "constraint_status", "toolface_change_deg"]
        sections.append(("planning_segments", columns, [[row.get(column) for column in columns] for row in segments if isinstance(row, dict)]))
    trajectory = payload.get("trajectory")
    if isinstance(trajectory, (list, tuple)) and trajectory:
        columns = ["md", "north", "east", "tvd", "inclination", "azimuth", "dogleg_deg_per_30m", "toolface"]
        sections.append(("trajectory", columns, [[row.get(column) for column in columns] for row in trajectory if isinstance(row, dict)]))
    limitations = payload.get("limitations")
    if isinstance(limitations, (list, tuple)) and limitations:
        sections.append(("limitations", ["limitation"], [[item] for item in limitations]))
    selected=payload.get('report_sections')
    if isinstance(selected,list):
        groups={'trajectory':{'trajectory','planning_segments'},'constraints':{'configuration_and_audit','limitations'},'collision':{'collision_profile','risk_segments','network_results'},'sources':{'source_versions'}}
        allowed={'summary'}|set().union(*(groups.get(name,set()) for name in selected))
        sections=[section for section in sections if section[0] in allowed]
    return sections


def _write_svg_curve(path: Path, title: str, rows: list[dict[str, Any]], x_key: str, y_keys: list[str]) -> None:
    width, panel, left, right = 960, 220, 88, 30
    height = 50 + panel * len(y_keys)
    elements = [f'<rect width="100%" height="100%" fill="white"/><text x="{left}" y="28" font-size="18">{escape(title)}</text>']
    palette = ["#1976b9", "#da751b", "#3c9060", "#8b60a6"]
    def number(value):
        try:
            result = float(value)
            return result if math.isfinite(result) else None
        except (ValueError, TypeError): return None
    for index, key in enumerate(y_keys):
        pairs = [(number(row.get(x_key)), number(row.get(key))) for row in rows if isinstance(row, dict)]
        valid = [(x,y) for x,y in pairs if x is not None and y is not None]
        top = 52 + panel * index
        bottom = top + 150
        label = FIELD_LABELS.get(key,key)
        elements.append(f'<text x="{left}" y="{top-8}" font-size="13">{escape(label)}</text>')
        if not valid:
            elements.append(f'<text x="{left}" y="{top+40}" font-size="12">未生成该字段结果</text>')
            continue
        xs,ys=zip(*valid);xmin,xmax=min(xs),max(xs);ymin,ymax=min(0,min(ys)),max(ys)
        if xmax<=xmin:xmax=xmin+1
        if ymax<=ymin:ymax=ymin+1
        def xy(x,y):return left+(x-xmin)/(xmax-xmin)*(width-left-right), bottom-(y-ymin)/(ymax-ymin)*150
        for tick in range(5):
            y=ymin+(ymax-ymin)*tick/4;py=xy(xmin,y)[1]
            x=xmin+(xmax-xmin)*tick/4;px=xy(x,ymin)[0]
            elements.append(f'<line x1="{left}" y1="{py}" x2="{width-right}" y2="{py}" stroke="#e1e5e9"/><text x="{left-8}" y="{py+4}" text-anchor="end" font-size="11">{y:.5g}</text><text x="{px}" y="{bottom+18}" text-anchor="middle" font-size="11">{x:.5g}</text>')
        commands=[];connected=False
        for x,y in pairs:
            if x is None or y is None:connected=False;continue
            px,py=xy(x,y);commands.append(f'{"L" if connected else "M"} {px:.2f},{py:.2f}');connected=True
            elements.append(f'<circle cx="{px}" cy="{py}" r="2" fill="{palette[index%4]}"/>')
        elements.append(f'<path d="{" ".join(commands)}" fill="none" stroke="{palette[index%4]}" stroke-width="1.7"/><text x="{width-right}" y="{bottom+38}" text-anchor="end" font-size="12">{escape(FIELD_LABELS.get(x_key,x_key))}</text>')
    path.write_text(f'<svg xmlns="http://www.w3.org/2000/svg" width="{width}" height="{height}" font-family="sans-serif">'+"".join(elements)+"</svg>",encoding="utf-8")


def _export_single(title: str, payload: dict[str, Any], file_format: str) -> Path:
    fmt = file_format.lower()
    suffix = {"xlsx": ".xlsx", "excel": ".xlsx", "docx": ".docx", "pdf": ".pdf", "json": ".json"}.get(fmt)
    if not suffix:
        raise ValueError("format must be xlsx, docx, pdf or json")
    handle = tempfile.NamedTemporaryFile(suffix=suffix, delete=False)
    path = Path(handle.name)
    handle.close()
    if fmt == "json":
        path.write_text(json.dumps({"title": title, "payload": payload}, ensure_ascii=False, indent=2), encoding="utf-8")
    elif fmt in {"xlsx", "excel"}:
        try:
            from openpyxl import Workbook
        except ImportError:
            raise RuntimeError("缺少 openpyxl，无法生成完整 Excel 报告；请安装项目依赖")
        else:
            workbook = Workbook()
            first = True
            for name, headers, rows in _structured_sections(payload):
                sheet = workbook.active if first else workbook.create_sheet()
                first = False
                sheet.title = name[:31]
                sheet.append([title if name == "summary" else SECTION_LABELS.get(name,name)])
                sheet.append([FIELD_LABELS.get(h,h) for h in headers])
                for row in rows:
                    sheet.append([value if isinstance(value,(int,float)) else _safe_value(value) for value in row])
                sheet.freeze_panes = "A3"
                sheet.auto_filter.ref = sheet.dimensions
            workbook.save(path)
    elif fmt == "docx":
        try:
            from docx import Document
            from docx.enum.section import WD_ORIENT
            from docx.enum.table import WD_CELL_VERTICAL_ALIGNMENT
            from docx.enum.text import WD_ALIGN_PARAGRAPH
            from docx.oxml import OxmlElement
            from docx.oxml.ns import qn
            from docx.shared import Inches, Pt, RGBColor
        except ImportError:
            raise RuntimeError("缺少 python-docx，无法生成完整 Word 报告；请安装项目依赖")
        else:
            document = Document()
            # Engineering tables are wider than portrait A4.  A single
            # landscape layout keeps field names and values on the same line,
            # while the small table font remains readable when printed.
            for section in document.sections:
                section.orientation = WD_ORIENT.LANDSCAPE
                section.page_width, section.page_height = section.page_height, section.page_width
                section.top_margin = Inches(0.45)
                section.bottom_margin = Inches(0.45)
                section.left_margin = Inches(0.45)
                section.right_margin = Inches(0.45)
            normal = document.styles["Normal"]
            # STSong is a PDF CID font name, not an installed Office font.
            # Select a real CJK family on the export host so headless rendering
            # preserves Chinese text as well as Word on the operator's machine.
            report_font = os.getenv('DRILLING_REPORT_FONT') or ('Arial Unicode MS' if platform.system()=='Darwin' else 'Noto Sans CJK SC')
            normal.font.name = report_font
            normal._element.rPr.rFonts.set(qn("w:eastAsia"), report_font)
            normal.font.size = Pt(8)
            for style_name in ('Title', 'Heading 1', 'Heading 2', 'Heading 3'):
                style = document.styles[style_name]
                style.font.name = report_font
                style._element.get_or_add_rPr().get_or_add_rFonts().set(qn('w:eastAsia'), report_font)
                style.font.color.rgb = RGBColor(0, 0, 0)
                # Some office templates put a decorative rule on headings.
                ppr = style._element.find(qn('w:pPr'))
                if ppr is not None:
                    for border in list(ppr.findall(qn('w:pBdr'))):ppr.remove(border)
            title_paragraph = document.add_paragraph(_office_title(title), style="Title")
            document.add_paragraph('本报告记录所选版本的计算结果、参数和约束状态。实际施工前请核对工程基准与误差参数。')
            for run in title_paragraph.runs:
                run.font.name = report_font
                run._element.rPr.rFonts.set(qn("w:eastAsia"), report_font)
                run.font.color.rgb = RGBColor(0, 0, 0)

            def _set_repeat_header(row) -> None:
                tr_pr = row._tr.get_or_add_trPr()
                tbl_header = OxmlElement("w:tblHeader")
                tbl_header.set(qn("w:val"), "true")
                tr_pr.append(tbl_header)

            def _shade(cell, fill: str) -> None:
                tc_pr = cell._tc.get_or_add_tcPr()
                shading = OxmlElement("w:shd")
                shading.set(qn("w:fill"), fill)
                tc_pr.append(shading)

            for name, headers, rows in _structured_sections(payload):
                heading = document.add_heading(SECTION_LABELS.get(name,name), level=2)
                for run in heading.runs:
                    run.font.name = report_font
                    run._element.rPr.rFonts.set(qn("w:eastAsia"), report_font)
                    run.font.color.rgb = RGBColor(0, 0, 0)
                if not rows:
                    continue
                table = document.add_table(rows=1, cols=len(headers))
                table.style = "Table Grid"
                table.autofit = False
                # Allocate more space to identifiers and narrative fields while
                # keeping short numeric/unit columns compact.
                available_width = 10.0
                weights = [max(0.65, min(1.5, len(str(header)) / 9.0)) for header in headers]
                scale = available_width / sum(weights)
                widths = [Inches(weight * scale) for weight in weights]
                for index, (cell, header) in enumerate(zip(table.rows[0].cells, headers)):
                    cell.text = FIELD_LABELS.get(header,str(header))
                    cell.width = widths[index]
                    cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
                    _shade(cell, "1F4E78")
                    for paragraph in cell.paragraphs:
                        paragraph.alignment = WD_ALIGN_PARAGRAPH.CENTER
                        for run in paragraph.runs:
                            run.font.name = report_font
                            run._element.rPr.rFonts.set(qn("w:eastAsia"), report_font)
                            run.font.bold = True
                            run.font.color.rgb = RGBColor(255, 255, 255)
                            run.font.size = Pt(7)
                _set_repeat_header(table.rows[0])
                for row in rows:
                    cells = table.add_row().cells
                    for index, (cell, value) in enumerate(zip(cells, row)):
                        cell.text = _safe_value(value)
                        cell.width = widths[index]
                        cell.vertical_alignment = WD_CELL_VERTICAL_ALIGNMENT.CENTER
                        for paragraph in cell.paragraphs:
                            paragraph.paragraph_format.space_after = Pt(0)
                            for run in paragraph.runs:
                                run.font.name = report_font
                                run._element.rPr.rFonts.set(qn("w:eastAsia"), report_font)
                                run.font.size = Pt(7)
            document.save(path)
    else:
        try:
            from reportlab.lib.pagesizes import A4, landscape
            from reportlab.pdfgen import canvas
        except ImportError:
            raise RuntimeError("缺少 ReportLab，无法生成完整中文 PDF 报告；请安装项目依赖")
        else:
            from reportlab.platypus import SimpleDocTemplate,Paragraph,Spacer,LongTable,TableStyle
            from reportlab.lib.styles import getSampleStyleSheet,ParagraphStyle
            from reportlab.lib import colors
            from reportlab.pdfbase import pdfmetrics
            from reportlab.pdfbase.cidfonts import UnicodeCIDFont
            pdfmetrics.registerFont(UnicodeCIDFont('STSong-Light'))
            styles=getSampleStyleSheet()
            for style in ('Normal','Title','Heading2'):styles[style].fontName='STSong-Light'
            styles['Normal'].fontSize=9;styles['Normal'].leading=12
            styles['Title'].textColor=colors.black;styles['Heading2'].textColor=colors.black
            labels={'summary':'评价摘要','configuration_and_audit':'参数与约束审计','collision_profile':'防碰扫描明细','risk_segments':'风险井段','network_results':'井间关系与防碰结果','planning_segments':'规划分段参数','trajectory':'轨道数据','limitations':'使用说明'}
            document=SimpleDocTemplate(str(path),pagesize=landscape(A4),leftMargin=28,rightMargin=28,topMargin=30,bottomMargin=30,title=title)
            story=[Paragraph(escape(title),styles['Title']),Paragraph('本报告记录所选版本的计算结果、参数和约束状态。实际施工前请核对工程基准与误差参数。',styles['Normal']),Spacer(1,10)]
            for name,headers,rows in _structured_sections(payload):
                story.append(Paragraph(SECTION_LABELS.get(name,name),styles['Heading2']))
                if not rows:continue
                def cell(value):
                    if value is None:return ''
                    if isinstance(value,float):value=format(value,'.6g')
                    return Paragraph(escape(str(value)),styles['Normal'])
                table=LongTable([[cell(FIELD_LABELS.get(h,h)) for h in headers]]+[[cell(v) for v in row] for row in rows],colWidths=[document.width/len(headers)]*len(headers),repeatRows=1,hAlign='LEFT')
                table.setStyle(TableStyle([('BACKGROUND',(0,0),(-1,0),colors.HexColor('#EAF0F6')),('GRID',(0,0),(-1,-1),.4,colors.HexColor('#D9D9D9')),('VALIGN',(0,0),(-1,-1),'MIDDLE'),('LEFTPADDING',(0,0),(-1,-1),5),('RIGHTPADDING',(0,0),(-1,-1),5),('TOPPADDING',(0,0),(-1,-1),5),('BOTTOMPADDING',(0,0),(-1,-1),5)]))
                story.extend([table,Spacer(1,10)])
            def footer(canvas,doc):
                canvas.setFont('STSong-Light',8);canvas.drawRightString(doc.pagesize[0]-28,15,str(doc.page))
            document.build(story,onFirstPage=footer,onLaterPages=footer)

    return path


def export_report(title: str, payload: dict[str, Any], file_format: str) -> Path:
    """Export one report or a default Word/PDF/Excel delivery bundle.

    ``bundle`` is deliberately a ZIP so one authenticated download contains
    the three requested office formats.  Single-format exports remain
    available for integrations that need a particular media type.
    """
    fmt = file_format.lower()
    payload = dict(payload)
    if "profile" in payload:
        payload.setdefault("report_scope", "ANTI_COLLISION_DAILY_REPORT")
    elif "results" in payload:
        payload.setdefault("report_scope", "ANTI_COLLISION_NETWORK_REPORT")
    elif "segments" in payload:
        payload.setdefault("report_scope", "TRAJECTORY_PLANNING_REPORT")
    if fmt not in {"bundle", "all"}:
        return _export_single(title, payload, fmt)
    output = Path(tempfile.NamedTemporaryFile(suffix=".zip", delete=False).name)
    members = []
    try:
        with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_DEFLATED) as archive:
            for child_fmt, name in (("docx", "drilling-report.docx"), ("pdf", "drilling-report.pdf"), ("xlsx", "drilling-report.xlsx")):
                child = _export_single(title, payload, child_fmt)
                members.append(child)
                archive.write(child, arcname=name)
            profile = payload.get("profile")
            if isinstance(profile, (list, tuple)) and profile and ('report_sections' not in payload or 'collision' in payload['report_sections']):
                curve = Path(tempfile.NamedTemporaryFile(suffix=".svg", delete=False).name)
                members.append(curve)
                _write_svg_curve(curve, "防碰扫描曲线", profile, "md", ["center_distance", "horizontal_distance", "normal_distance", "sf"])
                archive.write(curve, arcname="collision-profile.svg")
            segments = payload.get("segments")
            if isinstance(segments, (list, tuple)) and segments and ('report_sections' not in payload or 'trajectory' in payload['report_sections']):
                curve = Path(tempfile.NamedTemporaryFile(suffix=".svg", delete=False).name)
                members.append(curve)
                _write_svg_curve(curve, "规划段长 / 狗腿曲线", list(segments), "segment_no", ["segment_length", "dogleg_deg_per_30m"])
                archive.write(curve, arcname="planning-segments.svg")
            results = payload.get("results")
            if isinstance(results, (list, tuple)) and results and ('report_sections' not in payload or 'collision' in payload['report_sections']):
                curve = Path(tempfile.NamedTemporaryFile(suffix=".svg", delete=False).name)
                members.append(curve)
                _write_svg_curve(curve, "全邻井最小中心距 / 分离系数", list(results), "neighbor_well_id", ["min_distance", "operating_sf"])
                archive.write(curve, arcname="network-results.svg")
            # Preserve the original three-file bundle for scalar-only API
            # callers.  Structured engineering reports receive a manifest so
            # their extra tables/curves are self-describing.
            sections = _structured_sections(payload)
            if len(sections) > 1:
                manifest = Path(tempfile.NamedTemporaryFile(suffix=".json", delete=False).name)
                members.append(manifest)
                manifest.write_text(json.dumps({
                    "report_scope": payload.get("report_scope", "DRILLING_PROTOTYPE"),
                    "structured_sections": [name for name, _, _ in sections],
                    "graphics": [name for name in ("collision-profile.svg", "planning-segments.svg", "network-results.svg") if name in archive.namelist()],
                }, ensure_ascii=False, indent=2), encoding="utf-8")
                archive.write(manifest, arcname="manifest.json")
    finally:
        for child in members:
            child.unlink(missing_ok=True)
    return output
