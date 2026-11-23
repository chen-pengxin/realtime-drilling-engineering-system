"""COMPASS reports: recognize actual source sections, not filename guesses.

    Continuous survey tables are joined across pages. Other report tables stay
    separate and retain their source page/line and unmodified text.
"""
from __future__ import annotations

import re

NUMBER = r'[+−-]?\d[\d,]*\.\d+'
DEPTH = r'[+−-]?\d[\d,]*\.\d{2}'

SURVEY_COLUMNS = [
    ('md', '测深 / Measured Depth', 'm'), ('inclination', '井斜 / Inclination', '°'),
    ('azimuth', '方位 / Azimuth', '°'), ('tvd', '垂深 / Vertical Depth', 'm'),
    ('offset_north', '南北位移 / +N/-S', 'm'), ('offset_east', '东西位移 / +E/-W', 'm'),
    ('vertical_section', '垂直剖面位移 / Vertical Section', 'm'),
    ('dogleg', '狗腿度 / Dogleg Rate', '°/30m'),
    ('build_rate', '增斜率 / Build Rate', '°/30m'), ('turn_rate', '转向率 / Turn Rate', '°/30m')]

# COMPASS "Survey Report - Geographic" rows carry eight numeric columns
# followed by latitude/longitude text. Keep this schema separate from the
# compact numeric survey schema used by other reports so the original table
# shape is preserved during document review and export.
GEOGRAPHIC_SURVEY_COLUMNS = [
    ('md', '测深 / Measured Depth', 'm'), ('inclination', '井斜 / Inclination', '°'),
    ('azimuth', '方位 / Azimuth', '°'), ('tvd', '垂深 / Vertical Depth', 'm'),
    ('offset_north', '南北位移 / +N/-S', 'm'), ('offset_east', '东西位移 / +E/-W', 'm'),
    ('northing', '绝对北坐标 / Northing', 'm'), ('easting', '绝对东坐标 / Easting', 'm'),
    ('latitude', '纬度 / Latitude', ''), ('longitude', '经度 / Longitude', '')]

DEFINITIONS = {
    'survey': ('实钻测斜数据 / Survey', 'SURVEY_STATIONS', SURVEY_COLUMNS),
    'planned_survey': ('设计轨道数据 / Planned Survey', 'DESIGN_STATIONS', SURVEY_COLUMNS),
    'magnetics': ('磁场参数 / Magnetics', 'MAGNETICS', [
        ('model', '磁场模型 / Model Name', ''), ('sample_date', '日期 / Sample Date', ''),
        ('declination', '磁偏角 / Declination', '°'), ('dip_angle', '磁倾角 / Dip Angle', '°'),
        ('field_strength', '磁场强度 / Field Strength', 'nT')]),
    'vertical_section': ('垂直剖面基准 / Vertical Section', 'VERTICAL_SECTION', [
        ('tvd', '起点垂深 / Depth From (TVD)', 'm'), ('offset_north', '南北位移 / +N/-S', 'm'),
        ('offset_east', '东西位移 / +E/-W', 'm'), ('direction', '剖面方位 / Direction', '°')]),
    'tools': ('测斜工具分段 / Survey Program', 'TOOL_PROGRAM', [
        ('from_md', '起始井深 / From', 'm'), ('to_md', '终止井深 / To', 'm'),
        ('survey', '测斜记录（井眼） / Survey (Wellbore)', ''),
        ('tool_name', '工具名称 / Tool Name', ''), ('description', '说明 / Description', '')]),
    'targets': ('设计靶点 / Design Targets', 'TARGETS', [
        ('name', '靶点名称 / Target Name', ''), ('dip_angle', '靶面倾角 / Dip Angle', '°'),
        ('dip_direction', '靶面方位 / Dip Dir.', '°'), ('tvd', '垂深 / TVD', 'm'),
        ('offset_north', '南北位移 / +N/-S', 'm'), ('offset_east', '东西位移 / +E/-W', 'm'),
        ('northing', '绝对北坐标 / Northing', 'm'), ('easting', '绝对东坐标 / Easting', 'm'),
        ('geographic_coordinates', '经纬度 / Latitude Longitude', ''),
        ('shape', '靶区形状 / Shape', ''), ('notes', '中靶说明与靶区顶点', '')]),
    'casing': ('套管点 / Casing Points', 'CASING', [
        ('md', '测深 / Measured Depth', 'm'), ('tvd', '垂深 / Vertical Depth', 'm'),
        ('name', '名称 / Name', ''), ('casing_diameter', '套管直径 / Casing Diameter', 'in'),
        ('hole_diameter', '井眼直径 / Hole Diameter', 'in')]),
    'plans': ('设计方案清单 / Plans', 'PLANS', [
        ('wellbore', '井眼 / Wellpath', ''), ('name', '方案 / Plan', ''),
        ('principal', '主方案 / Principal Plan', ''), ('from_md', '起始井深 / From', 'm'),
        ('to_md', '终止井深 / To', 'm'), ('effective_date', '生效日期 / Effective Date', ''),
        ('version', '版本 / Version', '')]),
    'surveys': ('测斜记录清单 / Surveys', 'SURVEYS', [
        ('wellbore', '井眼 / Wellpath', ''), ('name', '测斜记录 / Survey', ''),
        ('from_md', '起始井深 / From', 'm'), ('to_md', '终止井深 / To', 'm'),
        ('date', '日期 / Date', ''), ('version', '版本 / Version', '')]),
}


def number(value):
    return float(str(value).replace(',', '').replace('−', '-'))


def _columns(definition):
    return [dict(key=k, label=l, unit=u) for k, l, u in definition]


def _section(text):
    patterns = [('tools', r'^Survey (?:Tool )?Program\b'), ('planned_survey', r'^Planned Survey$'),
                ('survey', r'^Survey$'), ('targets', r'^Design Targets$'),
                ('casing', r'^Casing Points$'), ('magnetics', r'^Magnetics\b'),
                ('vertical_section', r'^Vertical Section:'), ('plans', r'^Plans(?:\s|$)'),
                ('surveys', r'^Surveys(?:\s|$)')]
    return next((key for key, pattern in patterns if re.search(pattern, text)), None)


def parse_compass(pages):
    """pages contain text plus positioned words, derived from the real PDF."""
    full_text = '\n'.join(page['text'] for page in pages)
    if not re.search(r'(?:COMPASS|Survey Calculation Method:)', full_text) or not re.search(
            r'(?:Well Level Summary Report|Survey Report)', full_text):
        return None
    blocks, by_key = _base_tables(pages), {}
    active = None
    tool_boundaries = [153., 307., 401.]
    current_bore = ''

    def block(key, page):
        if key not in by_key:
            title, semantic, cols = DEFINITIONS[key]
            item = dict(locator='compass:' + key, type='TABLE', title=title, semantic_type=semantic,
                        columns=_columns(cols), records=[], source_rows=[], raw_lines=[], pages=[], notes=[])
            by_key[key] = item
            blocks.append(item)
        item = by_key[key]
        if page not in item['pages']:
            item['pages'].append(page)
        return item

    for page in pages:
        for line_no, line in enumerate(page['lines'], 1):
            text = line['text'].strip()
            if not text or re.search(r'\bPage\s+\d+.*COMPASS', text):
                continue
            key = _section(text)
            if key:
                active = block(key, page['page'])
                active['raw_lines'].append(text)
                if 'has no survey' in text.lower():
                    active['notes'].append(text)
                continue
            if re.match(r'^(?:Project|Site|Well|Wellbore|Design)\s+[^:]', text):
                active = None
                continue
            if not active or re.match(r'^(?:Statoil|Survey Report|Well Level|Company:|Project:|Site:|Well:|Wellbore:|Design:|Database:)', text):
                continue
            key = active['locator'].split(':')[1]
            active['raw_lines'].append(text)
            record = None
            raw_values = []
            if key in ('survey', 'planned_survey', 'vertical_section'):
                tokens = text.split()
                if key in ('survey', 'planned_survey'):
                    # Geographic reports have eight numeric values followed by
                    # DMS latitude/longitude strings. Their exact text is kept
                    # as-is; numeric trajectory calculations use the first eight
                    # values and the absolute Northing/Easting columns.
                    geographic = len(tokens) >= 8 and all(re.fullmatch(NUMBER, token) for token in tokens[:8]) and any('°' in token for token in tokens[8:])
                    if geographic:
                        active['columns'] = _columns(GEOGRAPHIC_SURVEY_COLUMNS)
                        latitude = longitude = ''
                        match = re.search(r'(?P<lat>\d+°\s+\d+\'\s+[\d.]+\s+[NS])\s+(?P<lon>\d+°\s+\d+\'\s+[\d.]+\s+[EW])$', text)
                        if match:
                            latitude, longitude = match.group('lat'), match.group('lon')
                        values = [number(token) for token in tokens[:8]] + [latitude, longitude]
                        record = dict(zip((c['key'] for c in active['columns']), values))
                        raw_values = tokens
                    else:
                        count = 10
                        if len(tokens) == count and all(re.fullmatch(NUMBER, token) for token in tokens):
                            record = dict(zip((c['key'] for c in active['columns']), map(number, tokens)))
                            raw_values = tokens
                else:
                    count = 4
                    if len(tokens) == count and all(re.fullmatch(NUMBER, token) for token in tokens):
                        record = dict(zip((c['key'] for c in active['columns']), map(number, tokens)))
                        raw_values = tokens
            elif key == 'magnetics':
                match = re.match(r'(.+?)\s+(\d{2}\.\d{2}\.\d{4})\s+(' + NUMBER + r')\s+(' + NUMBER + r')\s+([\d,]+(?:\.\d+)?)$', text)
                if match:
                    record = dict(model=match[1], sample_date=match[2], declination=number(match[3]),
                                  dip_angle=number(match[4]), field_strength=number(match[5]))
            elif key == 'tools':
                words = line.get('words', [])
                if any(w['text'] == 'Description' for w in words):
                    for i, term in enumerate(('Survey', 'Tool', 'Description')):
                        candidates = [w['x0'] for w in words if w['text'] == term]
                        if candidates:
                            tool_boundaries[i] = candidates[0]
                match = re.match(r'^(' + DEPTH + r')\s+(' + DEPTH + r')(.*)', text)
                if match:
                    def zone(left, right):
                        return ' '.join(w['text'].strip() for w in line.get('runs',words) if left - 1 <= w['x0'] < right - 1)
                    a, b, c = tool_boundaries
                    record = dict(from_md=number(match[1]), to_md=number(match[2]),
                                  survey=zone(a, b), tool_name=zone(b, c), description=zone(c, 10000))
                    if not record['survey']:
                        record['survey'] = match[3].strip()
            elif key == 'targets':
                match = re.match(r'^(.+?)\s+' + r'\s+'.join('(' + NUMBER + ')' for _ in range(7)) + r'(?:\s+(.*))?$', text)
                if match:
                    fields = ['dip_angle', 'dip_direction', 'tvd', 'offset_north', 'offset_east', 'northing', 'easting']
                    record = dict(name=match[1], **dict(zip(fields, (number(match[i]) for i in range(2, 9)))),
                                  geographic_coordinates=match[9] or '', shape='', notes=[])
                elif active['records'] and (text.startswith('-') or re.match(r'^(?:Point|Circle|Rectangle|Polygon|Ellipse)\b', text, re.I)):
                    previous = active['records'][-1]
                    previous['notes'].append(text)
                    vertex = re.match(r'^Point\s+(\d+)\s+' + r'\s+'.join('(' + NUMBER + ')' for _ in range(5)) + '$', text)
                    if vertex:
                        previous.setdefault('vertices', []).append(dict(index=int(vertex[1]), **dict(zip(
                            ('tvd', 'offset_north', 'offset_east', 'northing', 'easting'), (number(vertex[i]) for i in range(2, 7))))))
                    elif re.search(r'\b(?:Point|Circle|Rectangle|Polygon|Ellipse)\b', text, re.I):
                        previous['shape'] = text.lstrip('- ').strip()
                        dimensions = re.findall(r'\b([WHDR])([\d.]+)', text)
                        if dimensions:
                            previous['geometry'] = {k: float(v) for k, v in dimensions}
            elif key == 'casing':
                match = re.match(r'^(' + NUMBER + r')\s+(' + NUMBER + r')\s+(.+?)\s+(' + NUMBER + r')\s+(' + NUMBER + r')$', text)
                if match:
                    record = dict(md=number(match[1]), tvd=number(match[2]), name=match[3],
                                  casing_diameter=number(match[4]), hole_diameter=number(match[5]))
            elif key in ('plans', 'surveys'):
                if 'has no survey' in text.lower():
                    active['notes'].append(text)
                else:
                    match = re.match(r'^(.*?)\s+(Yes|No)\s+(' + DEPTH + r')\s+(' + DEPTH + r')(?:\s+(.*))?$', text)
                    if key == 'plans' and match:
                        tail = (match[5] or '').split()
                        record = dict(wellbore=current_bore, name=match[1], principal=match[2],
                                      from_md=number(match[3]), to_md=number(match[4]),
                                      effective_date=tail[0] if tail else '', version=' '.join(tail[1:]))
                    elif key == 'plans':
                        # Some source names visually overlap the Yes/No column.
                        match = re.match(r'^(.*?)\s+(' + DEPTH + r')\s+(' + DEPTH + r')(?:\s+(.*))?$', text)
                        if match:
                            record = dict(wellbore=current_bore, name=match[1], principal='',
                                          from_md=number(match[2]), to_md=number(match[3]),
                                          effective_date=match[4] or '', version='')
                    else:
                        match = re.match(r'^(.+?)\s+(' + DEPTH + r')\s+(' + DEPTH + r')(?:\s+(.*))?$', text)
                        if match:
                            record = dict(wellbore=current_bore, name=match[1], from_md=number(match[2]),
                                          to_md=number(match[3]), date=match[4] or '', version='')
                    if not record and re.fullmatch(r'(?:15/9[- ]?)?(?:F[- ]?)?\d+[ A-Z0-9-]*|Relief well location \d+', text):
                        current_bore = text
            if record is not None:
                record['source_page'] = page['page']
                record['source_line'] = line_no
                record['raw_text'] = text
                record['raw_cells'] = raw_values
                active['records'].append(record)
                active['source_rows'].append(dict(page=page['page'], line=line_no))

    metadata = _metadata(full_text)
    trajectories = []
    for item in blocks:
        if item['locator'].startswith('compass:base:'):
            continue
        cols = item['columns']
        item['header_rows'] = 2
        item['rows'] = [[c['label'] for c in cols], [c['unit'] for c in cols]] + [
            ['\n'.join(r.get(c['key'], [])) if isinstance(r.get(c['key']), list) else r.get(c['key'], '') for c in cols]
            for r in item['records']]
        if item['locator'] not in ('compass:survey', 'compass:planned_survey') or not item['records']:
            continue
        rows = []
        for source in item['records']:
            row = dict(source, source_row=source['source_line'])
            if source.get('northing') is not None and source.get('easting') is not None:
                row.update(north=source['northing'], east=source['easting'])
            elif metadata.get('site_north') is not None and metadata.get('site_east') is not None:
                row.update(north=metadata['site_north'] + source['offset_north'],
                           east=metadata['site_east'] + source['offset_east'])
            rows.append(row)
        reference = dict(metadata, length_unit='m', angle_unit='deg',
                         origin_md=rows[0]['md'], origin_tvd=rows[0]['tvd'],
                         origin_north=rows[0].get('north'), origin_east=rows[0].get('east'),
                         reference_status='READY' if rows[0]['md'] == 0 else 'NEEDS_MD0_HISTORY')
        if by_key.get('tools'):
            reference['survey_tool_program'] = by_key['tools']['records']
        if by_key.get('magnetics') and by_key['magnetics']['records']:
            mag = by_key['magnetics']['records'][0]
            reference.update(magnetic_declination=mag['declination'], magnetic_dip=mag['dip_angle'],
                             magnetic_field_strength=mag['field_strength'], magnetic_model=mag['model'],
                             magnetic_sample_date=mag['sample_date'])
        if by_key.get('vertical_section') and by_key['vertical_section']['records']:
            reference['vertical_section_azimuth'] = by_key['vertical_section']['records'][0]['direction']
        trajectories.append(dict(locator=item['locator'], headers=[c['label'] for c in cols], rows=rows,
                                 mapping={'md': 0, 'inclination': 1, 'azimuth': 2, 'tvd': 3},
                                 reference=reference,
                                 kind='DESIGN' if item['locator'].endswith('planned_survey') else 'ACTUAL',
                                 label=metadata.get('design_name', item['title'])))
    return dict(blocks=blocks, metadata=metadata, trajectories=trajectories)


def _base_tables(pages):
    """The header, coordinate, site, well and design forms are tables too."""
    result, active = [], None
    started = False
    fields = ['Local Co-ordinate Reference','Survey Calculation Method','Position Uncertainty',
              'Grid Convergence','Wellhead Depth','Water Depth','Tie On Depth','System Datum',
              'TVD Reference','MD Reference','North Reference','Map System','Geo Datum','Map Zone',
              'Site Position','Slot Radius','Audit Notes','Northing','Easting','Latitude','Longitude',
              'Company','Project','Site','Wellbore','Well','Design','Database','Version','Phase','From']
    pattern = re.compile(r'(?<!\w)(' + '|'.join(re.escape(f) for f in fields) + r')\s*:\s*')
    for page in pages:
        if 'Company:' not in page['text'] or not re.search(r'^Project\s+[^:]',page['text'],re.M):
            continue
        for line_no,line in enumerate(page['lines'],1):
            text=line['text']
            if text.startswith('Company:'):
                started = True
            if not started:
                continue
            if text.startswith('Magnetics') or text.startswith('Vertical Section:'):
                active=None
                continue
            if text.startswith('Company:'):
                key,title='header','报告信息'
            else:
                match=re.match(r'^(Project|Site|Well|Wellbore|Design)\s+([^:]+)$',text)
                if match:
                    key=match[1].lower();title={'Project':'坐标体系','Site':'井场信息','Well':'井信息','Wellbore':'井眼信息','Design':'方案信息'}[match[1]]
                else:
                    key=None
            if key:
                active=dict(locator='compass:base:'+key,type='TABLE',semantic_type='BASE_DATA',title=title,
                            header_rows=1,rows=[['原文参数','原文值']],records=[],pages=[page['page']],raw_lines=[])
                if key!='header':
                    active['rows'].append(['名称',match[2]])
                result.append(active)
            if active is None:
                continue
            active['raw_lines'].append(text)
            matches=list(pattern.finditer(text))
            for i,found in enumerate(matches):
                value=text[found.end():matches[i+1].start() if i+1<len(matches) else len(text)].strip()
                active['rows'].append([found[1],value])
                active['records'].append(dict(name=found[1],value=value,source_page=page['page'],source_line=line_no))
            relative=re.match(r'^(Well Position\s+\+N/-S|\+E/-W|Position Uncertainty)\s+([+−-]?[\d,.]+\s*m)',text)
            if relative:
                active['rows'].append([relative[1],relative[2]])
        break
    return result


def _metadata(text):
    out = {}
    patterns = {
        'company': r'^Company:\s*(.*?)\s+Local Co-ordinate Reference:',
        'project': r'^Project\s*:\s*(.*?)\s+TVD Reference:',
        'well_name': r'^Well:\s*(.*?)\s+(?:North Reference:|Survey Calculation Method:)',
        'wellbore_name': r'^Wellbore:\s*(.*?)\s+Survey Calculation Method:',
        'design_name': r'^Design:\s*(.*?)\s+Database:',
        'site_name': r'^Site:\s*(.*?)\s+MD Reference:',
        'north_reference_text': r'North Reference:\s*(\w+)',
        'coordinate_datum_name': r'Geo Datum:\s*(.*?)\s+Using',
        'projection_zone': r'Map Zone:\s*(Zone \d+[NS])',
        'depth_datum': r'TVD Reference:\s*(.*)',
        'report_version': r'Version:\s*(.*?)\s+Phase:',
        'phase': r'Phase:\s*(\w+)',
        'survey_program_date': r'Survey Program\s+Date\s+(\d{2}\.\d{2}\.\d{4})',
    }
    for key, pattern in patterns.items():
        match = re.search(pattern, text, re.M)
        if match:
            out[key] = match[1].strip()
    wrapped = re.search(r'TVD Reference:\s*([^\n]+)\n(Table\))', text)
    if wrapped:
        out['depth_datum'] = wrapped[1] + ' ' + wrapped[2]
    for key, pattern in {
        'site_north': r'Site Position:\s*Northing:\s*(' + NUMBER + ')',
        'site_east': r'From:\s*Map\s+Easting:\s*(' + NUMBER + ')',
        'meridian_convergence': r'Grid Convergence:\s*(' + NUMBER + ')',
        'tie_on_md': r'Tie On Depth:\s*(' + NUMBER + ')',
        'depth_datum_elevation_m': r'TVD Reference:.*?@\s*(' + NUMBER + ')m',
    }.items():
        match = re.search(pattern, text)
        if match:
            out[key] = number(match[1])
    out['north_reference'] = {'grid': 'GRID_NORTH', 'true': 'TRUE_NORTH', 'magnetic': 'MAGNETIC_NORTH'}.get(
        out.get('north_reference_text', '').lower(), out.get('north_reference_text', ''))
    if 'European 1950' in out.get('coordinate_datum_name', ''):
        zone = re.fullmatch(r'Zone (\d+)N', out.get('projection_zone', ''))
        if zone:
            out['crs'] = 'EPSG:' + str(23000 + int(zone[1]))
    return out
