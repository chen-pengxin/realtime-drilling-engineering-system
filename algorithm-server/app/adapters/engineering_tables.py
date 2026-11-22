"""Structured appendix data from source tables. Missing values remain unknown."""
import math
import re


def engineering_tables(document, text, metadata):
    from .source_documents import columns, number, extract_tables
    result = {'basic': {}, 'targets': [], 'design_nodes': [], 'design_details': [], 'survey': [], 'target_points': [], 'locators': {}}
    basic = result['basic']
    for key, candidates in {
        'coordinate_system': ('crs', 'coordinate_system', 'coordinate_system_text'),
        'north_reference': ('north_reference',), 'depth_datum': ('depth_datum',),
        'elevation': ('ground_elevation_m',), 'north': ('wellhead_north',), 'east': ('wellhead_east',),
        'meridian_convergence': ('meridian_convergence',), 'magnetic_declination': ('magnetic_declination',),
    }.items():
        for candidate in candidates:
            if metadata.get(candidate) is not None:
                basic[key] = metadata[candidate]
                break
    patterns = {
        'elevation': r'地面(?:海拔|高程|高度)\s*[：:|]?\s*([+-]?\d+(?:\.\d+)?)',
        'kelly_height': r'(?:补心距|补心高度)\s*[：:|]?\s*([+-]?\d+(?:\.\d+)?)',
        'pocket_length': r'口袋长度\s*[：:|]?\s*([+-]?\d+(?:\.\d+)?)',
    }
    for key, pattern in patterns.items():
        found = re.search(pattern, text)
        if found:
            basic[key] = float(found.group(1))
    # Merged office cells may leave several empty cells between a label and
    # its value. Read those original cells rather than dropping the value.
    for block in document.get('blocks',[]):
        for row in block.get('rows',[]):
            for i,cell in enumerate(row):
                label=re.sub(r'\s+','',str(cell))
                key=next((k for pattern,k in ((r'地面(?:海拔|高程|高度)(?:[（(]m[）)])?','elevation'),(r'补心(?:距|高度)(?:[（(]m[）)])?','kelly_height'),(r'口袋长度(?:[（(]m[）)])?','pocket_length')) if re.fullmatch(pattern,label,re.I)),None)
                if key:
                    value=next((str(v).strip() for v in row[i+1:] if str(v).strip()),'')
                    match=re.fullmatch(r'([+-]?\d+(?:\.\d+)?)\s*(?:m|米)?',value,re.I)
                    if match:basic[key]=float(match.group(1))
                if label=='磁偏角':
                    value=next((str(v).strip() for v in row[i+1:] if str(v).strip()),'')
                    angle=re.fullmatch(r'([（(]?[东西][偏]?[）)]?)?\s*([+-]?\d+(?:\.\d+)?)°(?:\s*(\d+(?:\.\d+)?)?[′\']?)?',value)
                    if angle:basic['magnetic_declination']=(-1 if '西' in (angle.group(1) or '') else 1)*(float(angle.group(2))+float(angle.group(3) or 0)/60)

    def _first_number(value):
        """Return the first numeric value in a cell, preserving unknown text."""
        if value in (None, ''):
            return None
        match = re.search(r'[-+−]?\d+(?:\.\d+)?', str(value).replace(',', ''))
        if not match:
            return None
        try:
            return float(match.group(0).replace('−', '-'))
        except (TypeError, ValueError):
            return None

    def _actual_coordinates(value):
        """Read the E/N pair used by the completion-card hit table."""
        if value in (None, ''):
            return None, None
        raw = str(value).replace('，', ',').replace('：', ':')
        east = re.search(r'(?:E|东)\s*[:：]?\s*([-+]?\d+(?:\.\d+)?)', raw, re.I)
        north = re.search(r'(?:N|北)\s*[:：]?\s*([-+]?\d+(?:\.\d+)?)', raw, re.I)
        return (_first_number(east.group(1)) if east else None,
                _first_number(north.group(1)) if north else None)

    def _completion_card_targets(rows):
        """Parse the merged A.6-style completion-card table.

        The card uses one target block per group (靶点 A～F), with the third
        and fourth columns holding design and actual values.  It has no normal
        header row, so the generic table extractor cannot recover A.6.  Only
        the actual column is materialized here; the design values remain in
        the source table and are never mistaken for measured hit data.
        """
        if not rows or not any(re.search(r'靶点\s*[A-Za-z0-9一二三四五六七八九十]+', str(r[0] if r else ''), re.I) for r in rows):
            return []
        result_rows = []
        current = None
        for row in rows:
            cells = list(row) + [''] * max(0, 4 - len(row))
            marker = re.search(r'靶点\s*([A-Za-z0-9一二三四五六七八九十]+)', str(cells[0]), re.I)
            if marker:
                current = {'target_no': f'靶点{marker.group(1)}'}
                result_rows.append(current)
            if current is None:
                continue
            label = re.sub(r'\s+', '', str(cells[1] or '')).replace('（', '(').replace('）', ')')
            actual = cells[3] if len(cells) > 3 else ''
            # A few cards place the actual value in the last non-empty cell.
            if actual in (None, ''):
                non_empty = [v for v in cells[2:] if v not in (None, '')]
                actual = non_empty[-1] if non_empty else ''
            if '井深' in label or '测深' in label:
                value = _first_number(actual)
                if value is not None: current['md'] = value
            elif '垂深' in label:
                value = _first_number(actual)
                if value is not None: current['tvd'] = value
            elif '坐标' in label:
                east, north = _actual_coordinates(actual)
                if east is not None: current['east'] = east
                if north is not None: current['north'] = north
            elif '左右偏差' in label or '横偏' in label:
                value = _first_number(actual)
                if value is not None: current['lateral_offset'] = value
            elif '前后偏差' in label or '纵偏' in label:
                value = _first_number(actual)
                if value is not None: current['longitudinal_offset'] = value
            elif '上下偏差' in label:
                value = _first_number(actual)
                if value is not None: current['vertical_offset'] = value
            elif '闭合方位' in label:
                value = _first_number(actual)
                if value is not None: current['closure_azimuth'] = value
            elif '靶方位' in label:
                value = _first_number(actual)
                if value is not None: current['target_azimuth'] = value
            elif '水平位移' in label:
                value = _first_number(actual)
                if value is not None: current['closure_distance'] = value
        # A target is an A.6 record only when an actual MD is present.  This
        # avoids converting design-only target lists into measured hit data.
        return [r for r in result_rows if r.get('md') is not None]

    # Completion-card A.6 is a target block embedded in a table that is also
    # visually labelled as single-point survey data.  Parse it independently
    # of the generic semantic type, keeping the source table intact.
    for block in document.get('blocks', []):
        if block.get('type') == 'TABLE':
            result['target_points'].extend(_completion_card_targets(block.get('rows', [])))

    for block in document.get('blocks', []):
        if block.get('type') != 'TABLE' or block.get('template'):
            continue
        rows, title = block.get('rows', []), block.get('title', '')
        semantic = block.get('semantic_type')
        records=block.get('records') or []
        if records and semantic=='TARGETS':
            for record in records:
                target={key:record[source] for key,source in {'name':'name','north':'northing','east':'easting','tvd':'tvd','shape':'shape','remark':'notes'}.items() if record.get(source) is not None}
                if target.get('name') and target.get('north') is not None and target.get('east') is not None:
                    target['coordinate_type']='ABSOLUTE';result['targets'].append(target)
            continue
        if records and semantic in ('DESIGN_STATIONS','SURVEY_STATIONS'):
            values=[{**r,**({'north':r['offset_north']} if 'offset_north' in r else {}),**({'east':r['offset_east']} if 'offset_east' in r else {}),**({'apparent_horizontal_length':r['vertical_section']} if 'vertical_section' in r else {})} for r in records if all(r.get(k) is not None for k in ('md','inclination','azimuth'))]
            key='design_details' if semantic=='DESIGN_STATIONS' else 'survey'
            result[key]=sorted(values,key=lambda r:r['md']);continue
        if not rows:
            continue
        # Coordinates of the wellhead in a target-coordinate table are
        # explicit absolute coordinates; trajectory N/E columns are offsets.
        if semantic == 'TARGETS':
            header = next((r for r in rows[:5] if any(re.search('控制点|靶点名称|靶点名|Target Name', str(c), re.I) for c in r)), None)
            if header:
                def index(pattern):
                    return next((i for i,c in enumerate(header) if re.search(pattern, re.sub(r'\s+', '', str(c)), re.I)), None)
                mapping = {'name': index('控制点|靶点名称|靶点名|TargetName'), 'north': index('纵坐标|北坐标|North'),
                           'east': index('横坐标|东坐标|East'), 'elevation': index('海拔|Elevation'),
                           'tvd': index('垂深|TVD'), 'radius': index('半径|Radius'), 'height': index('高度|Height'),
                           'width': index('宽度|Width'), 'shape': index('形状|Shape'), 'remark': index('备注|Comment')}
                for row in rows[rows.index(header)+1:]:
                    ni=mapping['name']
                    if ni is None or ni>=len(row) or not str(row[ni]).strip(): continue
                    record={}
                    for key,i in mapping.items():
                        if i is None or i>=len(row) or row[i] in ('',None):continue
                        try:record[key]=str(row[i]).strip() if key in ('name','shape','remark') else number(row[i])
                        except (ValueError,TypeError):pass
                    if '井口' in record.get('name',''):
                        for key in ('north','east'): 
                            if key in record:basic[key]=record[key]
                        if record.get('elevation') is not None:basic['depth_datum_elevation_m']=record['elevation']
                        continue
                    if record.get('north') is not None and record.get('east') is not None:
                        record['coordinate_type']='ABSOLUTE'
                        result['targets'].append(record)
        if semantic=='TARGET_POINTS':
            header=next((row for row in rows[:6] if any('靶点名称' in str(v) for v in row)),None)
            if header:
                keys={'target_no':'靶点名称','md':'井深|测深|MD','inclination':'井斜','azimuth':'方位角','tvd':'垂深','closure_distance':'水平位移|闭合距','closure_azimuth':'平移方位|闭合方位','offset':'靶心距','longitudinal_offset':'纵偏|北偏','lateral_offset':'横偏|东偏','remark':'备注'}
                mapping={key:next((i for i,v in enumerate(header) if re.search(pattern,str(v))),None) for key,pattern in keys.items()}
                for row in rows[rows.index(header)+1:]:
                    record={}
                    for key,i in mapping.items():
                        if i is None or i>=len(row) or row[i] in ('',None):continue
                        try:record[key]=str(row[i]).strip() if key in ('target_no','remark') else number(row[i])
                        except (ValueError,TypeError):pass
                    if record.get('target_no') and record.get('md') is not None:result['target_points'].append(record)
            continue
        if semantic in ('DESIGN_NODES','DESIGN_STATIONS','SURVEY_STATIONS','TARGET_POINTS'):
            kind = 'DESIGN' if semantic.startswith('DESIGN') else 'ACTUAL'
            for extraction in extract_tables(rows, block['locator'], {}, kind):
                values=[]
                for source in extraction.rows:
                    row={k:v for k,v in source.items() if k not in ('raw_cells',)}
                    remark_index=next((i for i,c in enumerate(extraction.headers) if re.search('备注|Comment',str(c),re.I)),None)
                    if remark_index is not None and remark_index<len(source['raw_cells']):row['remark']=source['raw_cells'][remark_index]
                    if semantic=='DESIGN_NODES':row['is_key_node']=True
                    values.append(row)
                key={'DESIGN_NODES':'design_nodes','DESIGN_STATIONS':'design_details','SURVEY_STATIONS':'survey','TARGET_POINTS':'target_points'}[semantic]
                # Keep the most complete actual survey; 25 m output is a
                # derived representation, not an independent measurement.
                if key!='survey' or len(values)>len(result[key]):
                    result[key]=sorted(values,key=lambda r:r['md']);result['locators'][key]=extraction.locator
    if basic.get('kelly_height') is not None and basic.get('elevation') is not None:
        basic.setdefault('depth_datum_elevation_m',basic['elevation']+basic['kelly_height'])
    for source,target in (('survey','survey'),('design_details','design_details')):
        direction=metadata.get('vertical_section_azimuth')
        if direction is not None:
            angle=math.radians(float(direction))
            for row in result[target]:
                if row.get('north') is not None and row.get('east') is not None:
                    row.setdefault('apparent_horizontal_length',row['north']*math.cos(angle)+row['east']*math.sin(angle))
    for target in result['targets']:
        if target.get('tvd') is None and basic.get('depth_datum_elevation_m') is not None and target.get('elevation') is not None:
            target['tvd']=basic['depth_datum_elevation_m']-target['elevation']
        if all(k in target and k in basic for k in ('north','east')):
            n,e=target['north']-basic['north'],target['east']-basic['east']
            target['closure_distance']=math.hypot(n,e)
            target['closure_azimuth']=math.degrees(math.atan2(e,n))%360
    # Preserve the first occurrence for each target/MD pair if a source table
    # also exposes a conventional A.6 header.
    unique = {}
    for row in result['target_points']:
        key = (str(row.get('target_no', '')).strip(), row.get('md'))
        unique.setdefault(key, row)
    result['target_points'] = sorted(unique.values(), key=lambda r: (float(r.get('md', 0)), str(r.get('target_no', ''))))
    return result
