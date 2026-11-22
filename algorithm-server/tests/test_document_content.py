"""Document import retains engineering tables which are not survey stations."""
import hashlib
import io
import os
from pathlib import Path

import pytest

from app.adapters.source_documents import inspect_document
from app.adapters.document_structure import inspect_structure, read_asset
from app.adapters.standard_appendix import appendix_templates


def test_workbook_all_sheets_merged_headers_formulas_and_image(tmp_path):
    from openpyxl import Workbook
    from openpyxl.drawing.image import Image as ExcelImage
    from PIL import Image
    path=tmp_path/'实际测斜.xlsx'
    workbook=Workbook();sheet=workbook.active;sheet.title='单点数据'
    for row in [['测斜数据','',''],['MD','Inc','Azi'],[0,0,0],[30,2,20]]:sheet.append(row)
    sheet.merge_cells('A1:C1');sheet['D5']='=A4+10'
    cover=workbook.create_sheet('封皮');cover['A1']='原始井号';cover['B1']='井1'
    image=io.BytesIO();Image.new('RGB',(16,16),'red').save(image,'PNG');image.seek(0)
    workbook.create_sheet('水平投影图').add_image(ExcelImage(image),'A1');workbook.save(path)
    before=path.read_bytes();result=inspect_document(path);document=result['document']
    sheets=[b for b in document['blocks'] if b['type']=='TABLE']
    assert [b['sheet'] for b in sheets]==['单点数据','封皮','水平投影图']
    assert sheets[0]['merges'][0]==dict(row=0,column=0,rowspan=1,colspan=3)
    assert sheets[0]['formulas']['4:3']=='=A4+10'
    assert sheets[1]['rows'][0]==['原始井号','井1']
    figure=next(b for b in document['blocks'] if b['type']=='FIGURE')
    assert figure['sheet']=='水平投影图'
    data,mime=read_asset(before,path.name,figure['asset'])
    assert mime=='image/png' and Image.open(io.BytesIO(data)).size==(16,16)
    assert path.read_bytes()==before
    assert len(result['trajectories'])==1


def test_word_keeps_non_survey_tables_text_merges_and_figures(tmp_path):
    from docx import Document
    from PIL import Image
    path=tmp_path/'井钻井工程设计.docx';word=Document()
    word.add_heading('1.2 基本数据',level=1);word.add_paragraph('来源正文应完整保存。')
    table=word.add_table(rows=2,cols=3);table.cell(0,0).text='构造位置';table.cell(0,1).merge(table.cell(0,2)).text='鄂尔多斯盆地伊陕斜坡'
    word.add_paragraph('表3-4-3 靶点数据表')
    targets=word.add_table(rows=2,cols=3)
    for cell,value in zip(targets.rows[0].cells,['靶名','纵坐标X（m）','横坐标Y（m）']):cell.text=value
    for cell,value in zip(targets.rows[1].cells,['A','4182007.357','19444210.285']):cell.text=value
    image=io.BytesIO();Image.new('RGB',(20,12),'blue').save(image,'PNG');image.seek(0);word.add_picture(image);word.save(path)
    result=inspect_document(path);blocks=result['document']['blocks'];tables=[b for b in blocks if b['type']=='TABLE']
    assert len(tables)==2 and not result['trajectories']
    assert tables[0]['rows'][0]==['构造位置','鄂尔多斯盆地伊陕斜坡','']
    assert tables[0]['merges'][0]['colspan']==2
    assert tables[1]['semantic_type']=='TARGETS' and tables[1]['rows'][1][0]=='A'
    assert any('来源正文应完整保存' in b.get('text','') for b in blocks)
    figure=next(b for b in blocks if b['type']=='FIGURE')
    assert read_asset(path.read_bytes(),path.name,figure['asset'])[1]=='image/png'


@pytest.mark.parametrize('suffix,encoding',[('.txt','gb18030'),('','gb18030'),('.txt','utf-16'),('','utf-8-sig'),('.csv','utf-8')])
def test_text_and_extensionless_encodings_full_content_and_stations(tmp_path,suffix,encoding):
    path=tmp_path/('测斜记录'+suffix);text='井号：测试井\nMD,INC,AZI\n0,0,0\n30,1,2\n末尾原文必须保留\n'
    path.write_bytes(text.encode(encoding));result=inspect_document(path)
    assert result['status']=='PARSED' and len(result['trajectories'][0]['rows'])==2
    assert next(b for b in result['document']['blocks'] if b['type']=='TEXT')['text']==text
    assert '测试井' in result['text_excerpt']


def test_statoil_tool_program_and_absolute_and_relative_coordinates_are_distinct(tmp_path):
    path=tmp_path/'F1'
    path.write_text('HEADER INFORMATION\nWell Name: F-1\nSurvey Program:\nH  0 - 100 MWD : Magnetic\n----------\nSURVEY LIST\nMD Inc Azim TVD Eoffset Noffset East North DLS\n0 0 0 0 2 3 435002 6478003 0\n100 10 20 99 4 5 435004 6478005 3\n')
    result=inspect_document(path);blocks=result['document']['blocks']
    tool=next(b for b in blocks if b['semantic_type']=='TOOL_PROGRAM')
    assert tool['records'][0]['from_md']==0 and tool['records'][0]['to_md']==100
    survey=next(b for b in blocks if b['locator']=='text:survey')
    assert survey['records'][0]['east']==435002 and survey['records'][0]['offset_e']==2
    assert result['sha256']==hashlib.sha256(path.read_bytes()).hexdigest()


def test_standard_six_tables_are_blank_templates_with_merged_headers():
    tables=appendix_templates()
    assert len(tables)==6 and all(t['template'] and not t['records'] for t in tables)
    assert tables[0]['merges'][0]['colspan']==3
    assert tables[3]['rows'][0][4]=='北坐标（m）'
    assert tables[3]['rows'][0][8]=='水平长度（m）'
    assert tables[4]['rows'][0][8]=='视平长（m）'
    assert tables[5]['rows'][0][7:10]==['靶心距（m）','纵偏（m）','横偏（m）']
    assert all(not any(t['rows'][-1]) for t in tables)


def test_zip_container_recursively_parses_supported_members_and_preserves_locator(tmp_path):
    from openpyxl import Workbook
    import zipfile
    workbook = Workbook(); workbook.active['A1'] = '压缩包内原始表格'; workbook.save(tmp_path/'inside.xlsx')
    (tmp_path/'notes').write_text('无后缀文本\n第二行', encoding='utf-8')
    archive = tmp_path/'资料.zip'
    with zipfile.ZipFile(archive, 'w') as z:
        z.write(tmp_path/'inside.xlsx', '报告/inside.xlsx')
        z.write(tmp_path/'notes', '报告/notes')
    result = inspect_document(archive)
    assert result['status'] == 'PARSED' and result['format'] == 'zip'
    assert any(b['locator'].startswith('archive:报告/inside.xlsx:sheet:') for b in result['document']['blocks'])
    assert any('无后缀文本' in b.get('text', '') for b in result['document']['blocks'])


def test_extensionless_binary_attachment_is_not_decoded_as_text(tmp_path):
    path = tmp_path/'附件'; path.write_bytes(b'\x00\x01\x02\x03binary')
    result = inspect_document(path)
    assert result['status'] == 'ATTACHMENT' and result['document']['status'] == 'UNSUPPORTED'


@pytest.mark.parametrize('asset',[{'entry':'../../etc/passwd'},{'entry':'word/document.xml'},{'entry':'xl/media/a.png','conversion':'exe'}])
def test_asset_locator_does_not_allow_arbitrary_files(asset):
    with pytest.raises(ValueError):read_asset(b'not a zip','source.xlsx',asset)


DATASET=Path(os.environ.get('DRILLING_SOURCE_DATASET','/Users/ermoon/Desktop/系统启动/系统实现/数据集'))


@pytest.mark.skipif(not DATASET.exists(),reason='Original engineering dataset is not installed')
def test_real_compass_design_all_tables_and_cross_page_survey():
    path=next(DATASET.rglob('*F-1 A rev0_PLAN.pdf'));result=inspect_document(path)
    trajectory=result['trajectories'][0];blocks=result['document']['blocks']
    assert trajectory['kind']=='DESIGN' and len(trajectory['rows'])==8
    assert trajectory['rows'][0]['md']==2587 and trajectory['rows'][-1]['md']==3829.03
    assert {r['source_page'] for r in trajectory['rows']}=={2,3}
    assert trajectory['reference']['origin_md']==2587
    assert trajectory['reference']['crs']=='EPSG:23031'
    assert trajectory['reference']['vertical_section_azimuth']==333.09
    assert len(next(b for b in blocks if b['semantic_type']=='TOOL_PROGRAM')['records'])==6
    assert len(next(b for b in blocks if b['semantic_type']=='TARGETS')['records'])==2
    assert len(next(b for b in blocks if b['semantic_type']=='CASING')['records'])==3
    assert next(b for b in blocks if b['semantic_type']=='MAGNETICS')['records'][0]['declination']==-1.14


@pytest.mark.skipif(not DATASET.exists(),reason='Original engineering dataset is not installed')
def test_real_compass_actual_polygon_vertices_and_tools():
    result=inspect_document(next(DATASET.rglob('*F-14_ACTUAL.pdf')))
    assert len(result['trajectories'][0]['rows'])==93
    blocks=result['document']['blocks'];targets=next(b for b in blocks if b['semantic_type']=='TARGETS')
    polygon=next(r for r in targets['records'] if r['name']=='F-14 rev6_target1_Top Hugin')
    assert polygon['shape']=='Polygon' and len(polygon['vertices'])==4
    assert polygon['vertices'][0]['northing']==6478786.31
    tools=next(b for b in blocks if b['semantic_type']=='TOOL_PROGRAM')
    assert len(tools['records'])==3 and tools['records'][0]['to_md']==2655.9


@pytest.mark.skipif(not DATASET.exists(),reason='Original engineering dataset is not installed')
def test_real_well_summary_without_surveys_still_has_five_plans():
    result=inspect_document(next(DATASET.rglob('Well Summary F-3.pdf')))
    assert result['status']=='PARSED' and result['trajectories']==[]
    blocks=result['document']['blocks']
    assert len(next(b for b in blocks if b['semantic_type']=='PLANS')['records'])==5
    assert 'has no survey' in next(b for b in blocks if b['semantic_type']=='SURVEYS')['notes'][0]


@pytest.mark.skipif(not DATASET.exists(),reason='Original engineering dataset is not installed')
def test_real_old_word_has_full_basic_data_table():
    results=[inspect_document(path) for path in DATASET.rglob('*井基本数据表.doc')]
    assert results and all(result['status']=='PARSED' for result in results)
    assert any('陕西省榆林市米脂县杨家沟镇侯家沟村' in str(block.get('rows',[]))
               for result in results for block in result['document']['blocks'])


@pytest.mark.skipif(not DATASET.exists(),reason='Original engineering dataset is not installed')
def test_real_legacy_excel_keeps_cover_measurements_and_projection_images():
    result=inspect_document(next(DATASET.rglob('米41-37YH5井斜数据表.xls')))
    blocks=result['document']['blocks']
    assert result['status']=='PARSED'
    assert {b['sheet'].strip() for b in blocks if b['type']=='TABLE'}>={'封皮','单点数据','水平投影图','垂直投影图','立体图'}
    assert any(b['type'] in ('FIGURE','CHART') for b in blocks)

