"""Immutable, paginatable document content in addition to calculation inputs.

All readable tables and paragraphs are retained, including tables unrelated to
MD/INC/AZI. Source cells, merged headings, units and figure locations are not
replaced by a generic survey table. No imported value is silently corrected.
"""
from __future__ import annotations

import csv
from datetime import date, datetime, time
import hashlib
import io
import json
import mimetypes
from pathlib import Path
import re
import zipfile

from .office_conversion import convert_office

PARSER_VERSION = 'source-content-2026.11.17.1'
TEXT_SUFFIXES = ('', '.txt', '.csv', '.tsv', '.asc', '.dat', '.mwd', '.survey', '.md')


def cell_value(value):
    if value is None:
        return ''
    if isinstance(value, (date, datetime, time)):
        return value.isoformat()
    if isinstance(value, float) and (value != value or abs(value) == float('inf')):
        return str(value)
    return value if isinstance(value, (int, float, bool, str)) else str(value)


def decode_text(data):
    if data.startswith((b'\xff\xfe', b'\xfe\xff')):
        return data.decode('utf-16'), 'utf-16'
    for encoding in ('utf-8-sig', 'gb18030', 'cp1252', 'latin1'):
        try:
            return data.decode(encoding), encoding
        except UnicodeDecodeError:
            continue
    raise ValueError('无法识别文本编码')


def semantic_type(title, rows=(), kind='ACTUAL'):
    text = str(title) + ' ' + ' '.join(str(c) for row in list(rows)[:3] for c in row)
    rules = [('TOOL_PROGRAM', r'Survey (?:Tool )?Program|测斜工具|仪器分段'),
             ('MAGNETICS', r'Magnetics|磁场参数'), ('TARGETS', r'Design Targets|靶点数据|靶区数据'),
             ('CASING', r'Casing Points|套管'), ('BASE_DATA', r'基本数据|基本信息|封[皮面]|扉页'),
             ('DESIGN_NODES', r'节点数据|设计节点'), ('DESIGN_STATIONS', r'详细点|分点数据|Planned Survey'),
             ('SURVEY_STATIONS', r'单点|连斜|测斜数据|井斜数据|25\s*m|25\s*米'),
             ('PLANS', r'^Plans$|设计方案清单'), ('SURVEYS', r'^Surveys$|测斜记录清单')]
    for key, pattern in rules:
        if re.search(pattern, text, re.I):
            return key
    from .source_documents import columns
    if any({'md', 'inclination', 'azimuth'} <= columns(row).keys() for row in list(rows)[:10]):
        return 'DESIGN_STATIONS' if kind == 'DESIGN' else 'SURVEY_STATIONS'
    return 'TABLE'


def table_block(locator, title, rows, *, merges=None, kind='ACTUAL', **extra):
    rows = [[cell_value(v) for v in row] for row in rows]
    return dict(locator=locator, type='TABLE', title=title, rows=rows, merges=merges or [],
                header_rows=_header_count(rows), semantic_type=semantic_type(title, rows, kind), **extra)


def _header_count(rows):
    # Caption, column labels and units precede the first numeric measurement.
    for index, row in enumerate(rows[:12]):
        numeric = sum(isinstance(v, (int, float)) or bool(re.fullmatch(r'[+−-]?\d[\d,]*(?:\.\d+)?', str(v).strip())) for v in row if v != '')
        if numeric >= 3:
            return min(index, 5)
    return 0  # Key/value forms and covers have no invented column headings.


def _safe_zip(data):
    archive = zipfile.ZipFile(io.BytesIO(data))
    if len(archive.infolist()) > 20000 or sum(i.file_size for i in archive.infolist()) > 1024**3:
        archive.close()
        raise ValueError('Office压缩内容超过解析限制')
    return archive


def _xlsx(data, kind, *, conversion=None):
    from openpyxl import load_workbook
    with _safe_zip(data) as archive:
        media = [n for n in archive.namelist() if n.startswith('xl/media/') and not n.endswith('/')]
    book = load_workbook(io.BytesIO(data), data_only=True)
    formulas = load_workbook(io.BytesIO(data), data_only=False)
    blocks = []
    try:
        for sheet in book:
            nonempty = [(r, c) for (r, c), cell in formulas[sheet.title]._cells.items() if cell.value is not None]
            max_row = max((r for r, c in nonempty), default=0)
            max_col = max((c for r, c in nonempty), default=0)
            if max_row * max_col > 2000000:
                raise ValueError('单个工作表超过200万个单元格，请拆分文件后导入')
            rows = [[sheet.cell(r, c).value for c in range(1, max_col + 1)] for r in range(1, max_row + 1)]
            merges = [dict(row=m.min_row - 1, column=m.min_col - 1, rowspan=m.max_row - m.min_row + 1,
                           colspan=m.max_col - m.min_col + 1) for m in sheet.merged_cells.ranges
                      if m.min_row <= max_row and m.min_col <= max_col]
            formula_cells = {f'{r-1}:{c-1}': cell.value for (r, c), cell in formulas[sheet.title]._cells.items() if cell.data_type == 'f'}
            formats = {f'{r-1}:{c-1}': cell.number_format for (r, c), cell in sheet._cells.items()
                       if cell.value is not None and cell.number_format != 'General'}
            blocks.append(table_block('sheet:' + sheet.title, sheet.title, rows, merges=merges, kind=kind,
                                      sheet=sheet.title, formulas=formula_cells, number_formats=formats,
                                      hidden=sheet.sheet_state != 'visible'))
            # Charts are real source objects; store their series and chart XML,
            # not a made-up image or a reconstruction of unknown curves.
            for i, chart in enumerate(sheet._charts, 1):
                from openpyxl.xml.functions import tostring
                blocks.append(dict(locator=f'sheet:{sheet.title}:chart:{i}', type='CHART', semantic_type='FIGURE',
                                   title=f'{sheet.title} · 图表{i}', sheet=sheet.title,
                                   chart_xml=tostring(chart.to_tree()).decode(),
                                   asset=dict(conversion=conversion, office_preview=True, sheet=sheet.title)))
        # Drawing relationships provide a stable original ZIP member per figure.
        import xml.etree.ElementTree as ET
        with _safe_zip(data) as archive:
            names = set(archive.namelist())
            sheet_xmls = [n for n in names if re.fullmatch(r'xl/worksheets/sheet\d+\.xml', n)]
            for path in sorted(sheet_xmls, key=lambda p: int(re.search(r'(\d+)\.xml', p)[1])):
                index = int(re.search(r'(\d+)\.xml', path)[1]) - 1
                sheet_name = book.worksheets[index].title if index < len(book.worksheets) else path
                rel_path = path.replace('worksheets/', 'worksheets/_rels/') + '.rels'
                if rel_path not in names:
                    continue
                import posixpath
                relationships = {r.attrib['Id']: posixpath.normpath(posixpath.join('xl/worksheets', r.attrib['Target'])).lstrip('/')
                                 if not r.attrib['Target'].startswith('/') else r.attrib['Target'].lstrip('/')
                                 for r in ET.fromstring(archive.read(rel_path)) if r.attrib.get('TargetMode') != 'External'}
                for drawing in relationships.values():
                    if drawing not in names or '/drawings/' not in drawing:
                        continue
                    drawing_rels = posixpath.dirname(drawing) + '/_rels/' + posixpath.basename(drawing) + '.rels'
                    if drawing_rels not in names:
                        continue
                    for relation in ET.fromstring(archive.read(drawing_rels)):
                        if relation.attrib.get('TargetMode') == 'External':
                            continue
                        target = relation.attrib['Target']
                        entry = target.lstrip('/') if target.startswith('/') else posixpath.normpath(posixpath.join(posixpath.dirname(drawing), target))
                        if entry in media:
                            blocks.append(dict(locator='figure:' + sheet_name + ':' + entry, type='FIGURE', semantic_type='FIGURE',
                                               title=sheet_name + ' · 原图', sheet=sheet_name,
                                               asset=dict(entry=entry, conversion=conversion)))
    finally:
        book.close()
        formulas.close()
    return blocks


def _xls(data, kind, warnings):
    import xlrd
    book = xlrd.open_workbook(file_contents=data, formatting_info=True, on_demand=True)
    blocks = []
    try:
        for sheet in book.sheets():
            rows = [sheet.row_values(i) for i in range(sheet.nrows)]
            # Remove formatted-only tails, not blank rows inside a source form.
            while rows and not any(v != '' for v in rows[-1]):
                rows.pop()
            merges = [dict(row=r0, column=c0, rowspan=r1-r0, colspan=c1-c0)
                      for r0, r1, c0, c1 in sheet.merged_cells if r0 < len(rows)]
            blocks.append(table_block('sheet:' + sheet.name, sheet.name, rows, merges=merges, kind=kind, sheet=sheet.name))
        try:
            converted = convert_office(data, '.xls', 'xlsx')
            blocks.extend(b for b in _xlsx(converted, kind, conversion='xlsx') if b['type'] in ('FIGURE', 'CHART'))
        except (ValueError, OSError, TimeoutError) as error:
            warnings.append(dict(code='LEGACY_DRAWINGS', message=str(error)))
    finally:
        book.release_resources()
    return blocks


def _docx(data, kind, *, conversion=None):
    from docx import Document
    from docx.table import Table
    from docx.text.paragraph import Paragraph
    with _safe_zip(data):
        document = Document(io.BytesIO(data))
    blocks, pending = [], []
    heading, caption, table_index, text_index = '正文', '', 0, 0

    def flush():
        nonlocal text_index, pending
        if pending:
            text_index += 1
            blocks.append(dict(locator=f'paragraphs:{text_index}', title=heading, type='TEXT', semantic_type='TEXT',
                               paragraphs=pending, text='\n'.join(pending)))
            pending = []

    def add_table(table, locator, title):
        rows, positions = [], {}
        for ri, row in enumerate(table.rows):
            cells = []
            for ci, cell in enumerate(row.cells):
                key = cell._tc
                positions.setdefault(key, []).append((ri, ci))
                cells.append(cell.text if positions[key][0] == (ri, ci) else '')
            rows.append(cells)
        merges = []
        for locations in positions.values():
            if len(locations) > 1:
                r0, c0 = locations[0]
                merges.append(dict(row=r0, column=c0, rowspan=max(r for r, c in locations)-r0+1,
                                   colspan=max(c for r, c in locations)-c0+1))
        blocks.append(table_block(locator, title, rows, merges=merges, kind=kind))
        for ri, row in enumerate(table.rows):
            for ci, cell in enumerate(row.cells):
                if positions[cell._tc][0] != (ri, ci):
                    continue
                for i, nested in enumerate(cell.tables, 1):
                    add_table(nested, f'{locator}:cell:{ri}:{ci}:table:{i}', title + ' · 内嵌表')

    for element in document.element.body:
        if element.tag.endswith('}p'):
            paragraph = Paragraph(element, document)
            value = paragraph.text
            if value.strip():
                style = paragraph.style.name if paragraph.style else ''
                if style.lower().startswith(('heading', '标题')) or re.match(r'^\s*\d+(?:\.\d+)+\s*\S', value) and len(value) < 90:
                    flush()
                    heading = value.strip()
                pending.append(value)
                if re.match(r'^\s*[表图]\s*[\d一二三四五六七八九十]', value):
                    caption = value.strip()
            images = element.xpath('.//*[local-name()="blip" or local-name()="imagedata"]')
            for image in images:
                rid = image.get('{http://schemas.openxmlformats.org/officeDocument/2006/relationships}embed') or image.get('{http://schemas.openxmlformats.org/officeDocument/2006/relationships}id')
                part = document.part.related_parts.get(rid)
                if part is not None:
                    entry = str(part.partname).lstrip('/')
                    if not any(b['locator'] == 'figure:' + entry for b in blocks):
                        flush()
                        blocks.append(dict(locator='figure:' + entry, type='FIGURE', semantic_type='FIGURE',
                                           title=caption if caption.startswith('图') else heading + ' · 原图',
                                           asset=dict(entry=entry, conversion=conversion)))
        elif element.tag.endswith('}tbl'):
            flush()
            table_index += 1
            add_table(Table(element, document), f'table:{table_index}', caption if caption.startswith('表') else heading + f' · 表{table_index}')
            caption = ''
    flush()
    # Media inside table cells, floating objects and headers must not disappear.
    with _safe_zip(data) as archive:
        existing = {b['asset'].get('entry') for b in blocks if 'asset' in b}
        for entry in archive.namelist():
            if entry.startswith('word/media/') and not entry.endswith('/') and entry not in existing:
                blocks.append(dict(locator='figure:' + entry, title='文档插图', type='FIGURE', semantic_type='FIGURE',
                                   asset=dict(entry=entry, conversion=conversion)))
    if not blocks:
        raise ValueError('Word文档未读取出正文、表格或插图')
    return blocks


def _text(data, name, kind):
    text, encoding = decode_text(data)
    if '\x00' in text:
        raise ValueError('文件为二进制内容，不能作为无后缀文本解析')
    blocks = [dict(locator='text:original', title='原文', type='TEXT', semantic_type='TEXT', text=text, encoding=encoding)]
    lines = text.splitlines()
    if 'SURVEY LIST' in text:
        from .source_documents import parse_statoil
        parsed = parse_statoil(text)
        if parsed:
            columns = [('md', '测深', 'm RKB'), ('inclination', '井斜', 'deg'), ('azimuth', '方位', 'deg'),
                       ('tvd', '垂深', 'm RKB'), ('offset_e', '东西位移', 'm'), ('offset_n', '南北位移', 'm'),
                       ('east', '绝对东坐标', 'm'), ('north', '绝对北坐标', 'm'), ('dls', '狗腿度', 'deg/30m')]
            records = parsed[0].rows
            block = table_block('text:survey', '测斜数据 / SURVEY LIST',
                                [[v[1] for v in columns], [v[2] for v in columns]] + [r['raw_cells'] for r in records], kind=kind,
                                records=records, columns=[dict(key=k, label=l, unit=u) for k, l, u in columns])
            block['header_rows'] = 2
            blocks.append(block)
        program = re.search(r'Survey Program:\s*\n(.*?)(?:\n-{5,}|\nSURVEY LIST)', text, re.S | re.I)
        if program:
            records = []
            for i, line in enumerate(lines, 1):
                match = re.match(r'\s*([A-Z])\s+(\d+(?:\.\d+)?)\s*-\s*(\d+(?:\.\d+)?)\s+(.*)', line)
                if match and line in program[1]:
                    records.append(dict(source_row=i, type=match[1], from_md=float(match[2]), to_md=float(match[3]), description=match[4]))
            blocks.append(table_block('text:tools', '测斜工具分段 / Survey Program',
                                      [['类型', '起始井深（m）', '终止井深（m）', '测斜工具与说明']] + [[r['type'], r['from_md'], r['to_md'], r['description']] for r in records],
                                      records=records, kind=kind, semantic_source='Survey Program'))
            blocks[-1]['semantic_type'] = 'TOOL_PROGRAM'
        metadata = []
        for i, line in enumerate(lines, 1):
            if line.strip() == 'SURVEY LIST':
                break
            if ':' in line and not line.lstrip().startswith('H '):
                key, value = line.split(':', 1)
                if value.strip():
                    metadata.append([key.strip(), value.strip()])
        blocks.insert(0, table_block('text:metadata', '文件参数', metadata, kind=kind))
    else:
        suffix = Path(name).suffix.lower()
        if suffix in ('.csv', '.tsv') or any(delimiter in text for delimiter in ('\t', ',', ';')):
            try:
                dialect = csv.Sniffer().sniff(text[:65536], delimiters=',;\t')
                rows = list(csv.reader(io.StringIO(text), dialect))
            except csv.Error:
                rows = [re.split(r'\t|,|;', line) for line in lines]
        else:
            rows = [re.split(r'\s{2,}|\t', line.strip()) if re.search(r'\s{2,}', line.strip()) else line.split() for line in lines]
        blocks.insert(0, table_block('text', '数据表', rows, kind=kind))
    return blocks, dict(encoding=encoding)


def _pdf(data, name, kind, warnings):
    import pdfplumber
    from .compass_documents import parse_compass
    blocks, pages = [], []
    is_standard = bool(re.search(r'(?:SY[_ /-]*T\s*5435|5435[-—_]2012)', name, re.I))
    with pdfplumber.open(io.BytesIO(data)) as document:
        for i, page in enumerate(document.pages, 1):
            words = page.extract_words(x_tolerance=1, y_tolerance=2)
            grouped = []
            for word in sorted(words, key=lambda w: (round(w['top']/2)*2, w['x0'])):
                match = next((line for line in reversed(grouped[-3:]) if abs(line['top']-word['top']) < 2.5), None)
                if match is None:
                    match = dict(top=word['top'], words=[])
                    grouped.append(match)
                match['words'].append(word)
            for line in grouped:
                line['words'].sort(key=lambda w: w['x0'])
                # Preserve PDF text-object runs before sorting columns. Sorting
                # individual letters corrupts long tool/plan names when the
                # original report prints them over an adjacent column.
                runs=[]
                for char in page.chars:
                    if abs(char['top']-line['top'])>=2.5:
                        continue
                    if runs and abs(runs[-1]['x1']-char['x0'])<1.2:
                        runs[-1]['text']+=char['text'];runs[-1]['x1']=char['x1']
                    else:
                        runs.append(dict(text=char['text'],x0=char['x0'],x1=char['x1']))
                line['runs']=sorted(runs,key=lambda r:r['x0'])
                line['text']=' '.join(r['text'].strip() for r in line['runs'] if r['text'].strip()) or ' '.join(w['text'] for w in line['words'])
            text = '\n'.join(line['text'] for line in grouped)
            if len(text.strip()) < 20 and not is_standard:
                from .document_ocr import recognize_page
                try:
                    grouped = recognize_page(page)
                    text = '\n'.join(line['text'] for line in grouped)
                except (ValueError, OSError, RuntimeError) as error:
                    warnings.append(dict(code='OCR_FAILED', page=i, message=str(error)))
            pages.append(dict(page=i, text=text, lines=grouped))
            blocks.append(dict(locator=f'pdf:page:{i}:text', title=f'第{i}页原文', type='TEXT', semantic_type='TEXT', page=i, text=text,
                               asset=dict(pdf_page=i)))
        compass = parse_compass(pages)
        if compass:
            return compass['blocks'] + blocks, compass['metadata'], compass['trajectories']
        # A scanned blank standard appendix is a template, never fake well data.
        if is_standard:
            from .standard_appendix import appendix_templates
            return appendix_templates() + blocks, dict(page_count=len(pages), document_type='STANDARD'), []
        for i, page in enumerate(document.pages, 1):
            found = page.find_tables()
            for j, table in enumerate(found, 1):
                rows = table.extract()
                if not rows:
                    continue
                blocks.append(table_block(f'page:{i}:table:{j}', f'第{i}页 · 表{j}', rows, kind=kind, page=i))
            if not found and any(line.get('ocr') for line in pages[i-1]['lines']):
                from .document_ocr import scanned_tables
                for j, rows in enumerate(scanned_tables(page, pages[i-1]['lines']), 1):
                    blocks.append(table_block(f'page:{i}:ocr-table:{j}', f'第{i}页 · 表{j}', rows, kind=kind, page=i, ocr=True))
    return blocks, dict(page_count=len(pages)), []


def _image(data, name, kind, warnings):
    from PIL import Image
    from .document_ocr import recognize_pillow_image
    try:
        image = Image.open(io.BytesIO(data))
        image.load()
        lines = recognize_pillow_image(image)
        text = '\n'.join(line['text'] for line in lines)
        blocks = [dict(locator='image:page:1:text', title='图片原文', type='TEXT', semantic_type='TEXT',
                       text=text, page=1, ocr=True, asset=dict(image_page=1))]
        return blocks, dict(page_count=1, image_size=[image.width,image.height], ocr=True), []
    except Exception as error:
        warnings.append(dict(code='OCR_FAILED', message=str(error)))
        return [dict(locator='image:page:1', title='图片原件', type='FIGURE', semantic_type='FIGURE',
                     asset=dict(image_page=1))], dict(page_count=1), []


def _archive(data, name, kind, warnings):
    """Parse supported members of a supplied ZIP without changing the archive."""
    blocks, metadata, trajectories = [], {'container': 'ZIP', 'members': []}, []
    with _safe_zip(data) as archive:
        members = [entry for entry in archive.infolist()
                   if not entry.is_dir() and not entry.filename.startswith('__MACOSX/')
                   and not Path(entry.filename).name.startswith('.')]
        if len(members) > 5000:
            raise ValueError('ZIP文档成员超过5000个，无法一次安全解析')
        for entry in members:
            member_name = entry.filename
            payload = archive.read(entry)
            try:
                child, child_trajectories = inspect_structure(payload, member_name, kind)
            except (ValueError, OSError, RuntimeError) as error:
                warnings.append(dict(code='ARCHIVE_MEMBER_FAILED', member=member_name, message=str(error)[:300]))
                continue
            metadata['members'].append(dict(name=member_name, format=child.get('format'), status=child.get('status'), sections=len(child.get('blocks', []))))
            for block in child.get('blocks', []):
                block = dict(block)
                block['locator'] = f'archive:{member_name}:{block["locator"]}'
                block['title'] = f'{member_name} · {block.get("title", "")}'
                if isinstance(block.get('asset'), dict):
                    block['asset'] = dict(block['asset'], archive_entry=member_name, archive_name=member_name)
                blocks.append(block)
            for trajectory in child_trajectories:
                trajectory = dict(trajectory)
                trajectory['locator'] = f'archive:{member_name}:{trajectory["locator"]}'
                for row in trajectory.get('rows', []):
                    row['archive_member'] = member_name
                trajectories.append(trajectory)
    return blocks, metadata, trajectories


def inspect_structure(data, name, kind='ACTUAL'):
    suffix = Path(name).suffix.lower()
    warnings, metadata, trajectories = [], {}, []
    if data.startswith(b'%PDF'):
        blocks, metadata, trajectories = _pdf(data, name, kind, warnings)
        source_format = 'pdf'
    elif data.startswith(b'PK'):
        with _safe_zip(data) as archive:
            names = archive.namelist()
        if 'word/document.xml' in names:
            blocks, source_format = _docx(data, kind), 'docx'
        elif 'xl/workbook.xml' in names:
            blocks, source_format = _xlsx(data, kind), 'xlsx'
        else:
            blocks, metadata, trajectories = _archive(data, name, kind, warnings)
            source_format = 'zip'
    elif data.startswith(bytes.fromhex('D0CF11E0')):
        # Detect actual Office type; several supplied .docx files are Word 97.
        import olefile
        try:
            with olefile.OleFileIO(io.BytesIO(data)) as archive:
                word = archive.exists('WordDocument')
                excel = archive.exists('Workbook') or archive.exists('Book')
        except (OSError, ValueError) as error:
            raise ValueError('原文件的OLE复合文档结构不完整，请重新提供可正常打开的原件') from error
        if word:
            from .legacy_word import word_blocks
            blocks, metadata = word_blocks(data, kind)
            source_format = 'doc'
        elif excel:
            blocks, source_format = _xls(data, kind, warnings), 'xls'
        else:
            raise ValueError('复合文档不包含可读取的Word或Excel数据')
        if suffix.lstrip('.') != source_format:
            metadata['declared_extension'] = suffix
    elif suffix in ('.doc', '.wps', '.rtf'):
        blocks = _docx(convert_office(data, suffix, 'docx'), kind, conversion='docx')
        source_format = suffix.lstrip('.')
    elif suffix in TEXT_SUFFIXES:
        if suffix == '' and b'\x00' in data[:4096]:
            return dict(parser_version=PARSER_VERSION, format='binary', status='UNSUPPORTED', blocks=[], warnings=[dict(code='BINARY_ATTACHMENT', message='无后缀文件为二进制附件，已归档但未按文本解析')]), []
        blocks, metadata = _text(data, name, kind)
        source_format = suffix.lstrip('.') or 'text'
    elif suffix in ('.xml', '.witsml'):
        text, encoding = decode_text(data)
        blocks = [dict(locator='xml:original', title='XML原文', type='TEXT', semantic_type='TEXT', text=text)]
        metadata, source_format = dict(encoding=encoding), suffix.lstrip('.')
    elif suffix in ('.png','.jpg','.jpeg','.tif','.tiff','.bmp','.webp'):
        blocks, metadata, trajectories = _image(data,name,kind,warnings)
        source_format = suffix.lstrip('.')
    else:
        return dict(parser_version=PARSER_VERSION, format=suffix.lstrip('.'), status='UNSUPPORTED', blocks=[], warnings=[]), []
    # Stable unique locators are essential for saved links and immutable parses.
    seen = set()
    for block in blocks:
        if block['locator'] in seen:
            raise ValueError('来源内容定位重复：' + block['locator'])
        seen.add(block['locator'])
        if block['type'] == 'TABLE':
            block['row_count'] = len(block.get('rows', []))
            block['column_count'] = max((len(r) for r in block.get('rows', [])), default=0)
    document = dict(parser_version=PARSER_VERSION, format=source_format, title=Path(name).stem,
                    source_sha256=hashlib.sha256(data).hexdigest(), metadata=metadata, blocks=blocks,
                    warnings=warnings, status='PARTIAL' if warnings else 'PARSED')
    document['content_sha256'] = hashlib.sha256(json.dumps(document,ensure_ascii=False,sort_keys=True,separators=(',',':')).encode()).hexdigest()
    return document, trajectories


def read_asset(data, original_name, asset):
    """Read only a validated member/page of the archived original."""
    if asset.get('archive_entry'):
        member = str(asset['archive_entry'])
        if not re.fullmatch(r"[^/\\]+(?:/[^/\\]+)*", member) or member.startswith('.'):
            raise ValueError('压缩文件成员定位无效')
        with _safe_zip(data) as archive:
            if member not in archive.namelist():
                raise ValueError('压缩文件成员不存在')
            child = archive.read(member)
        nested = dict(asset)
        nested.pop('archive_entry', None); nested.pop('archive_name', None)
        return read_asset(child, member, nested)
    if 'legacy_picture' in asset:
        from .legacy_word import read_word
        content = read_word(data, int(asset['legacy_picture']))
        from PIL import Image
        image = Image.open(io.BytesIO(content))
        out = io.BytesIO();image.save(out, 'PNG')
        return out.getvalue(), 'image/png'
    if asset.get('image_page'):
        mime = mimetypes.guess_type(original_name)[0] or 'image/png'
        if mime not in ('image/png','image/jpeg','image/gif','image/tiff','image/bmp','image/webp'):
            raise ValueError('原件图片格式无效')
        return data, mime
    if asset.get('pdf_page'):
        import pdfplumber
        with pdfplumber.open(io.BytesIO(data)) as document:
            page = int(asset['pdf_page'])
            if page < 1 or page > len(document.pages):
                raise ValueError('原件页码不存在')
            image = document.pages[page-1].to_image(resolution=130).original
            out = io.BytesIO()
            image.save(out, 'PNG')
            return out.getvalue(), 'image/png'
    if asset.get('office_preview'):
        return convert_office(data, Path(original_name).suffix.lower(), 'pdf'), 'application/pdf'
    conversion = asset.get('conversion')
    if conversion:
        if conversion not in ('docx', 'xlsx'):
            raise ValueError('无效的文档转换格式')
        data = convert_office(data, Path(original_name).suffix.lower(), conversion)
    entry = str(asset.get('entry', ''))
    if not re.fullmatch(r'(?:word|xl)/media/[^/]+', entry):
        raise ValueError('插图来源定位无效')
    with _safe_zip(data) as archive:
        if entry not in archive.namelist():
            raise ValueError('原件插图不存在')
        content = archive.read(entry)
    mime = mimetypes.guess_type(entry)[0] or 'application/octet-stream'
    if mime not in ('image/png', 'image/jpeg', 'image/gif', 'image/webp'):
        # Convert Office vector graphics rather than executing arbitrary SVG.
        try:
            from PIL import Image
            image = Image.open(io.BytesIO(content))
            out = io.BytesIO()
            image.save(out, 'PNG')
            return out.getvalue(), 'image/png'
        except Exception:
            converted = convert_office(content, Path(entry).suffix.lower(), 'png')
            return converted, 'image/png'
    return content, mime
