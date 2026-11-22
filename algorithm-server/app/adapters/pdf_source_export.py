"""Replace a selected PDF survey table while retaining the report's pages.

Numeric source-cell positions are verified against the archived extraction.
Redactions remove superseded text, including the searchable text layer; all
other report content and the original archived PDF remain untouched.
"""
from collections import defaultdict
import io
import math
import re


def _number(text):
    try:return float(str(text).replace(',','').replace('−','-'))
    except (ValueError,TypeError):return None


def _lines(page):
    lines=[]
    for word in sorted(page.extract_words(x_tolerance=2,y_tolerance=2),key=lambda w:(w['top'],w['x0'])):
        if not lines or abs(lines[-1][0]['top']-word['top'])>2.5:lines.append([])
        lines[-1].append(word)
    return [sorted(line,key=lambda w:w['x0']) for line in lines]


def _compass_slots(pdf,extraction):
    slots=[];page_lines={}
    for source in extraction['rows']:
        page_id=int(source['source_page'])-1
        lines=page_lines.setdefault(page_id,_lines(pdf.pages[page_id]))
        matches=[]
        for line in lines:
            numbers=[w for w in line if _number(w['text']) is not None]
            if len(numbers)<4:continue
            if all(abs(_number(w['text'])-float(source[key]))<.011 for w,key in zip(numbers,('md','inclination','azimuth','tvd'))):matches.append(numbers)
        if len(matches)!=1:raise ValueError('PDF来源行无法唯一定位，请重新解析原件后导出')
        words=matches[0]
        fields=['md','inclination','azimuth','tvd','offset_north','offset_east','apparent_horizontal_length','dogleg','build_rate','turn_rate']
        if len(words)!=len(fields):raise ValueError('PDF测斜表列数与来源不一致，已停止导出')
        slots.append((page_id,[(k,(w['x0']-1,w['top']-1,w['x1']+1,w['bottom']+1),w['text']) for k,w in zip(fields,words)]))
    return slots


def _table_slots(pdf,extraction):
    match=re.fullmatch(r'page:(\d+):table:(\d+):header:\d+',extraction['locator'])
    if not match:raise ValueError('该PDF来源表缺少可核验的表格位置，已停止导出')
    page_id,table_id=(int(v)-1 for v in match.groups());tables=pdf.pages[page_id].find_tables()
    if table_id>=len(tables):raise ValueError('PDF来源表位置已变化')
    table=tables[table_id];slots=[]
    mapping=extraction['mapping']
    for source in extraction['rows']:
        index=int(source['source_row'])-1
        if index>=len(table.rows):raise ValueError('PDF来源行位置已变化')
        cells=table.rows[index].cells;values=extraction.get('rows',[])[len(slots)].get('raw_cells',[])
        entries=[]
        for key,column in mapping.items():
            if column>=len(cells) or cells[column] is None:raise ValueError('PDF来源单元格无法定位')
            box=cells[column];interior=(box[0]+.6,box[1]+.6,box[2]-.6,box[3]-.6)
            entries.append((key,interior,str(values[column]) if column<len(values) else '0.00'))
        slots.append((page_id,entries))
    return slots


def patch_pdf(data,stations,extraction,reference):
    import pdfplumber
    import pymupdf
    compass=extraction['locator'].startswith('compass:')
    with pdfplumber.open(io.BytesIO(data)) as pdf:
        slots=_compass_slots(pdf,extraction) if compass else _table_slots(pdf,extraction)
    if not slots:raise ValueError('PDF来源表没有数据行位置')
    # Incoming rows already use the original source units and heading datum.
    source_reference={**extraction.get('reference',{}),**reference}
    prepared=[];last=None
    n0=float(source_reference.get('site_north') or source_reference.get('wellhead_north') or source_reference.get('origin_north') or 0)
    e0=float(source_reference.get('site_east') or source_reference.get('wellhead_east') or source_reference.get('origin_east') or 0)
    angle=source_reference.get('vertical_section_azimuth')
    original_by_md={round(float(r['md']),6):r for r in extraction['rows']}
    for row in stations:
        record=dict(row)
        for key,origin in (('north',n0),('east',e0)):
            if record.get(key) is not None:record['offset_'+key]=float(record[key])-(0 if source_reference.get('coordinates_are_offsets') else origin)
        if angle is not None and all(record.get('offset_'+key) is not None for key in ('north','east')):
            record.setdefault('apparent_horizontal_length',record['offset_north']*math.cos(math.radians(float(angle)))+record['offset_east']*math.sin(math.radians(float(angle))))
        if last is not None:
            length=float(row['md'])-float(last['md'])
            if length<=0:raise ValueError('导出测点井深必须递增')
            record['build_rate']=(float(row['inclination'])-float(last['inclination']))/length*30
            record['turn_rate']=((float(row['azimuth'])-float(last['azimuth'])+180)%360-180)/length*30
        else:
            old=original_by_md.get(round(float(row['md']),6),{})
            for key in ('build_rate','turn_rate'):
                if old.get(key) is not None:record.setdefault(key,old[key])
        prepared.append(record);last=row
    doc=pymupdf.open(stream=data,filetype='pdf')
    by_page=defaultdict(list)
    for slot in slots:by_page[slot[0]].append(slot)
    # Clone the source's own last table page for additional data, preserving
    # its existing headers, column spacing and unrelated content.
    donor_id=max(by_page,key=lambda p:len(by_page[p]));donor=by_page[donor_id]
    extra=max(0,len(prepared)-len(slots))
    footers={}
    for _ in range(math.ceil(extra/len(donor))):
        doc.fullcopy_page(donor_id)
        new_id=len(doc)-1
        slots.extend((new_id,entries) for _,entries in donor)
        by_page[new_id]=[(new_id,entries) for _,entries in donor]
        page=doc[new_id];footers[new_id]=[]
        for label in re.findall(r'\bPage\s+\d+\b',page.get_text()):
            for rect in page.search_for(label):
                if rect.y0>page.rect.height*.8:footers[new_id].append((rect,f'Page {new_id+1}'))
    # Redact every original slot once, even when fewer rows are exported.
    for page_id,page_slots in by_page.items():
        page=doc[page_id]
        for _,entries in page_slots:
            for _,box,_ in entries:page.add_redact_annot(pymupdf.Rect(box),fill=(1,1,1),cross_out=False)
        for rect,label in footers.get(page_id,[]):page.add_redact_annot(rect,fill=(1,1,1),cross_out=False)
        page.apply_redactions(images=0,graphics=0)
        for rect,label in footers.get(page_id,[]):page.insert_text((rect.x0,rect.y1-2),label,fontsize=8,fontname='helv',color=(0,0,.65))
    for index,(page_id,entries) in enumerate(slots):
        if index>=len(prepared):continue
        page=doc[page_id];record=prepared[index]
        for key,box,original in entries:
            value=record.get(key)
            if value is None:continue
            text=str(value) if key=='remark' else (f'{float(value):,.{len(original.rsplit(".",1)[1]) if "." in original else 2}f}' if ',' in original else f'{float(value):.{len(original.rsplit(".",1)[1]) if "." in original else 2}f}')
            rect=pymupdf.Rect(box);font_size=min(12,max(5,rect.height-2 if compass else (rect.height-3)*.85))
            while font_size>4 and pymupdf.get_text_length(text,fontsize=font_size)>rect.width-1:font_size-=.25
            if key=='remark' and any(ord(c)>127 for c in text):font='china-s'
            else:font='helv'
            page.insert_text((rect.x1-pymupdf.get_text_length(text,fontname=font,fontsize=font_size),rect.y1-2),text,fontname=font,fontsize=font_size,color=(0,0,0))
    output=doc.tobytes(garbage=4,deflate=True);doc.close();return output
