"""Source-family exports retain unrelated content, locator, units and formatting."""
import io
import xml.etree.ElementTree as ET
import pytest
from app.adapters.source_documents import inspect_document, export_source_document
from app.adapters.survey_io import parse_witsml

ROWS=[{'md':0,'inclination':1,'azimuth':2},{'md':120,'inclination':12,'azimuth':22},{'md':180,'inclination':18,'azimuth':28}]

def test_noncontiguous_csv_rows_extend_before_footer(tmp_path):
    p=tmp_path/'survey.csv'
    p.write_bytes(b'MD,INC,AZI,Remark\r\n0,0,0,A\r\n"note,keep"\r\n100,10,20,B\r\nfooter\r\n')
    extraction=inspect_document(p)['trajectories'][0]
    out,_,_=export_source_document(p.read_bytes(),p.name,ROWS,extraction)
    assert out==b'MD,INC,AZI,Remark\r\n0,1,2,A\r\n"note,keep"\r\n120,12,22,B\r\n180,18,28\r\nfooter\r\n'

def test_source_xlsx_keeps_styles_notes_sheets_and_added_row(tmp_path):
    from openpyxl import Workbook,load_workbook
    from openpyxl.styles import Font
    p=tmp_path/'survey.xlsx';w=Workbook();s=w.active;s.title='Survey'
    for row in [['MD','INC','AZI'],[0,0,0],['interleaved note'],[100,10,20],['footer']]:s.append(row)
    s['B4'].font=Font(bold=True,color='FF0000');w.create_sheet('Notes')['A1']='unchanged';w.save(p)
    out,_,_=export_source_document(p.read_bytes(),p.name,ROWS,inspect_document(p)['trajectories'][0])
    result=load_workbook(io.BytesIO(out));s=result['Survey']
    assert s['A3'].value=='interleaved note' and s['A6'].value=='footer'
    assert s['A5'].value==180 and s['A5'].data_type=='n' and s['B5'].font.bold and s['B4'].font.bold
    assert result['Notes']['A1'].value=='unchanged'

def test_source_docx_keeps_unrelated_runs_and_cell_style(tmp_path):
    from docx import Document
    p=tmp_path/'survey.docx';d=Document();d.add_paragraph().add_run('preface').bold=True
    t=d.add_table(rows=0,cols=3)
    for row in [['MD','INC','AZI'],[0,0,0],['interleaved note','',''],[100,10,20],['footer','','']]:
        for c,v in zip(t.add_row().cells,row):c.text=str(v)
    t.rows[3].cells[1].paragraphs[0].runs[0].italic=True;d.save(p)
    out,_,_=export_source_document(p.read_bytes(),p.name,ROWS,inspect_document(p)['trajectories'][0])
    result=Document(io.BytesIO(out));t=result.tables[0]
    assert result.paragraphs[0].runs[0].bold
    assert t.rows[2].cells[0].text=='interleaved note' and t.rows[5].cells[0].text=='footer'
    assert t.rows[4].cells[1].text=='18' and t.rows[4].cells[1].paragraphs[0].runs[0].italic

@pytest.mark.parametrize('suffix',['xml','witsml'])
def test_witsml_namespace_incl_added_and_removed_stations(tmp_path,suffix):
    p=tmp_path/f'survey.{suffix}'
    p.write_text('<trajectories xmlns="urn:test"><trajectory uid="one"><name>preserved</name><trajectoryStation uid="a"><md uom="m">0</md><incl uom="dega">0</incl><azi uom="dega">0</azi></trajectoryStation><trajectoryStation uid="b"><md uom="m">100</md><incl uom="dega">10</incl><azi uom="dega">20</azi></trajectoryStation></trajectory></trajectories>')
    assert len(parse_witsml(p.read_text()))==2
    extraction=inspect_document(p)['trajectories'][0]
    out,_,_=export_source_document(p.read_bytes(),p.name,ROWS,extraction)
    assert [s.md for s in parse_witsml(out.decode())]==[0,120,180]
    assert b'preserved' in out
    stations = [e for e in ET.fromstring(out).iter() if e.tag.endswith('trajectoryStation')]
    assert len({e.get('uid') for e in stations}) == len(stations)
    out,_,_=export_source_document(p.read_bytes(),p.name,ROWS[:1],extraction)
    assert len(parse_witsml(out.decode()))==1

def test_inverse_source_units_and_magnetic_azimuth():
    source=b'MD,INC,AZI\n0,0,0\n100,0,0\n'
    extraction={'locator':'text:header:1','mapping':{'md':0,'inclination':1,'azimuth':2},'rows':[{'source_row':2},{'source_row':3}]}
    rows=[{'md':0,'inclination':0,'azimuth':3},{'md':30.48,'inclination':10,'azimuth':23}]
    out,_,_=export_source_document(source,'s.csv',rows,extraction,source_reference={'length_unit':'ft','angle_unit':'deg','north_reference':'MAGNETIC_NORTH','magnetic_declination':5,'meridian_convergence':2})
    assert b'100,10,20' in out

def test_original_mode_is_byte_identical_even_for_legacy_documents():
    for name in ['old.xls','old.doc','scan.pdf']:
        original=b'immutable original bytes'
        assert export_source_document(original,name,ROWS,mode='ORIGINAL')[0]==original

@pytest.mark.parametrize('name',['survey.csv','survey.txt'])
def test_semicolon_source_preserves_delimiter_and_footer_on_append(tmp_path,name):
    p=tmp_path/name
    p.write_bytes(b'MD;INC;AZI;Note\r\n0;0;0;A\r\n100;10;20;B\r\nfooter;unchanged\r\n')
    extraction=inspect_document(p)['trajectories'][0]
    out,_,_=export_source_document(p.read_bytes(),name,ROWS,extraction)
    assert out==b'MD;INC;AZI;Note\r\n0;1;2;A\r\n120;12;22;B\r\n180;18;28\r\nfooter;unchanged\r\n'

def test_whitespace_source_preserves_existing_field_spacing(tmp_path):
    p=tmp_path/'survey.txt';p.write_bytes(b'MD INC AZI\n  0   0\t  0 \n  100   10\t  20 \nfooter\n')
    # This source has whitespace fields rather than a consistent TSV dialect.
    extraction={'locator':'text:header:1','mapping':{'md':0,'inclination':1,'azimuth':2},'rows':[{'source_row':2},{'source_row':3}]}
    out,_,_=export_source_document(p.read_bytes(),p.name,ROWS[:2],extraction)
    assert b'  0   1\t  2 ' in out and b'  120   12\t  22 ' in out

def test_single_space_text_header_and_unrelated_notes_roundtrip(tmp_path):
    p=tmp_path/'survey.txt'
    p.write_text('MD INC AZI\n0 0 0\n100 10 20\nfooter text remains\n')
    extraction=inspect_document(p)['trajectories'][0]
    out,_,_=export_source_document(p.read_bytes(),p.name,ROWS,extraction)
    assert out.decode().startswith('MD INC AZI\n')
    assert out.decode().endswith('footer text remains\n')
    q=tmp_path/'export.txt';q.write_bytes(out)
    assert [r['md'] for r in inspect_document(q)['trajectories'][0]['rows']]==[0,120,180]
@pytest.mark.parametrize('policy',[
    {'duplicate_tolerance':0},{'duplicate_tolerance':float('nan')},
    {'max_dls_deg_per_30m':-1},{'max_dls_deg_per_30m':float('inf')},
    {'remove_anomalies':'false'}])
def test_invalid_cleaning_configuration_is_rejected(policy):
    from app.adapters.source_documents import clean_rows
    with pytest.raises(ValueError):clean_rows([],policy)


def test_duplicate_tolerance_works_across_rounding_bucket_boundary():
    from app.adapters.source_documents import clean_rows
    rows=[{'md':1.0049,'inclination':1,'azimuth':0},{'md':1.0051,'inclination':1,'azimuth':360}]
    accepted,issues=clean_rows(rows,{'duplicate_tolerance':.01})
    assert len(accepted)==1 and issues[0]['type']=='DUPLICATE_POINT'

def test_manual_mapping_retains_real_source_rows_and_can_roundtrip():
    from app.adapters.source_documents import map_source_table
    original=b'Unrecognized survey\nDepthX,AngleA,AngleB,Note\n0,1,2,keep-A\n30,3,4,keep-B\nfooter\n'
    catalog=map_source_table(original,'s.csv')
    assert catalog['tables'][0]['row_count']==5
    selection={'locator':'text','header_row':2,'start_row':3,'end_row':4,'mapping':{'md':0,'inclination':1,'azimuth':2}}
    result=map_source_table(original,'s.csv',selection)
    assert [r['source_row'] for r in result['extraction']['rows']]==[3,4]
    out,_,_=export_source_document(original,'s.csv',ROWS[:2],result['extraction'])
    assert b'0,1,2,keep-A' in out and b'120,12,22,keep-B' in out and out.endswith(b'footer\n')
    changed=map_source_table(original,'s.csv',{**selection,'end_row':3})
    assert result['extraction']['locator']!=changed['extraction']['locator']

@pytest.mark.parametrize('change',[
    {'mapping':{'md':0,'inclination':0,'azimuth':2}},
    {'mapping':{'md':0,'inclination':1,'azimuth':99}},
    {'end_row':99},{'start_row':1},{'locator':'absent'}])
def test_manual_mapping_rejects_invalid_source_coordinates(change):
    from app.adapters.source_documents import map_source_table
    selection={'locator':'text','header_row':1,'start_row':2,'end_row':3,'mapping':{'md':0,'inclination':1,'azimuth':2}}
    with pytest.raises(ValueError):map_source_table(b'A,B,C\n0,0,0\n30,1,2','s.csv',{**selection,**change})


def test_source_coordinate_offsets_are_not_exported_as_global_coordinates():
    source=b'MD,INC,AZI,N,E\n0,0,0,0,0\n100,1,90,0,1\n'
    extraction={'locator':'text:header:1','mapping':{'md':0,'inclination':1,'azimuth':2,'north':3,'east':4},'rows':[{'source_row':2},{'source_row':3}]}
    values=[{'md':0,'inclination':0,'azimuth':0,'north':4200000,'east':19400000},{'md':100,'inclination':1,'azimuth':90,'north':4200001,'east':19400002}]
    out,_,_=export_source_document(source,'survey.csv',values,extraction,source_reference={'coordinates_are_offsets':True,'origin_north':4200000,'origin_east':19400000})
    assert b'100,1,90,1,2' in out and b'4200000' not in out

def test_updated_appendix_metrics_and_remarks_are_exported_with_selected_rows():
    source=b'MD,INC,AZI,DLS,Remark\n0,0,0,0,old\n100,1,90,.3,old\n'
    extraction={'locator':'text:header:1','mapping':{'md':0,'inclination':1,'azimuth':2,'dogleg':3,'remark':4},'rows':[{'source_row':2},{'source_row':3}]}
    out,_,_=export_source_document(source,'survey.csv',[{'md':0,'inclination':0,'azimuth':0,'dogleg':0,'remark':'start'},{'md':100,'inclination':2,'azimuth':90,'dogleg':.6,'remark':'changed'}],extraction)
    assert b'100,2,90,0.6,changed' in out

def test_pdf_table_export_replaces_searchable_values_and_retains_other_report_content(tmp_path):
    from reportlab.pdfgen import canvas
    import pymupdf
    stream=io.BytesIO();c=canvas.Canvas(stream,pagesize=(400,300));c.drawString(30,250,'Original report heading')
    for x in [30,130,230,330]:c.line(x,100,x,190)
    for y in [100,130,160,190]:c.line(30,y,330,y)
    for y,row in [(170,['MD','INC','AZI']),(140,['0.00','0.00','0.00']),(110,['100.00','1.00','90.00'])]:
        for x,value in zip([45,145,245],row):c.drawString(x,y,value)
    c.drawString(30,50,'Unrelated source note');c.save();original=stream.getvalue()
    path=tmp_path/'report.pdf';path.write_bytes(original);extraction=inspect_document(path)['trajectories'][0]
    output,_,mime=export_source_document(original,path.name,[{'md':0,'inclination':0,'azimuth':0},{'md':110,'inclination':2,'azimuth':90},{'md':200,'inclination':3,'azimuth':90}],extraction)
    with pymupdf.open(stream=output,filetype='pdf') as doc:
        assert len(doc)==2
        text='\n'.join(p.get_text() for p in doc)
        assert '110.00' in text and '200.00' in text and '100.00' not in text
        assert 'Original report heading' in text and 'Unrelated source note' in text
    assert mime=='application/pdf'

def test_legacy_doc_native_writer_preserves_table_on_add_edit_delete(tmp_path):
    import shutil
    from pathlib import Path
    from docx import Document
    from app.adapters.office_conversion import convert_office
    helper=Path(__file__).resolve().parents[1]/'tools/legacy-office-reader/target/legacy-office-reader.jar'
    if not helper.is_file() or not (shutil.which('soffice') or shutil.which('libreoffice') or Path('/Applications/LibreOffice.app').exists()):pytest.skip('Office/Word component is not installed')
    document=Document();document.add_paragraph('Unrelated original text');table=document.add_table(rows=0,cols=3)
    for row in [['MD','INC','AZI'],[0,0,0],[100,1,90]]:
        for c,v in zip(table.add_row().cells,row):c.text=str(v)
    stream=io.BytesIO();document.save(stream);original=convert_office(stream.getvalue(),'.docx','doc')
    path=tmp_path/'source.doc';path.write_bytes(original);extraction=inspect_document(path)['trajectories'][0]
    for count in (1,2,3):
        output,_,_=export_source_document(original,path.name,ROWS[:count],extraction)
        assert output.startswith(bytes.fromhex('D0CF11E0'))
        target=tmp_path/f'updated-{count}.doc';target.write_bytes(output);parsed=inspect_document(target)
        assert [r['md'] for r in parsed['trajectories'][0]['rows']]==[r['md'] for r in ROWS[:count]]
        assert 'Unrelated original text' in parsed['text_excerpt']
