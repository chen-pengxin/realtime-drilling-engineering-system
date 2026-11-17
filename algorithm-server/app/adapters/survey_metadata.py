"""Read explicit survey-program evidence; never infer a tool's accuracy budget.

The tool name alone is insufficient to choose an ISCWSA error-source profile.
Extracted values are candidates for the import form, not confirmed parameters.
"""
import re


def survey_metadata(text):
    result, evidence, tool = {}, [], {}
    # Datum + projection zone must both be explicit; a basin/field name or a
    # coordinate magnitude never determines a CRS.
    epsg_matches=list(re.finditer(r'\bEPSG\s*[:：]?\s*(\d{4,6})\b',text,re.I))
    epsg_codes=list(dict.fromkeys('EPSG:'+m.group(1) for m in epsg_matches))
    epsg=epsg_matches[0] if len(epsg_codes)==1 else None
    if len(epsg_codes)>1:
        result.setdefault('metadata_conflicts',{})['crs']=epsg_codes
        evidence.extend({'field':'crs','text':m.group(0),'line':text.count('\n',0,m.start())+1} for m in epsg_matches)
    if epsg:
        from pyproj import CRS
        code='EPSG:'+epsg.group(1)
        CRS.from_user_input(code)
        result['crs']=code
        evidence.append({'field':'crs','text':epsg.group(0),'line':text.count('\n',0,epsg.start())+1})
    datum=re.search(r'(?:Geo\s+Datum|Geodetic\s+Datum|大地基准|坐标基准)\s*[:：|]\s*([^\n|]{1,120})',text,re.I)
    zone=re.search(r'(?:Map[- ]Zone|Projection|投影分带)\s*[:：|]\s*([^\n|]{1,120})',text,re.I)
    if datum:result['coordinate_datum_name']=datum.group(1).strip()
    if zone:result['projection_zone']=zone.group(1).strip()
    if datum and zone and not epsg_codes:
        utm=re.search(r'UTM(?:\s+Zone)?\s*(\d{1,2})\s*([NS])\b',zone.group(1),re.I)
        if utm and 1<=int(utm.group(1))<=60:
            number=int(utm.group(1));hemisphere=utm.group(2).upper();name=datum.group(1)
            if re.search(r'European\s*1950|\bED\s*50\b',name,re.I) and hemisphere=='N' and 28<=number<=38:
                result['crs']=f'EPSG:{23000+number}'
            elif re.search(r'WGS\s*[- ]?\s*84',name,re.I):
                result['crs']=f'EPSG:{(32600 if hemisphere=="N" else 32700)+number}'
            if result.get('crs'):evidence.append({'field':'crs','text':datum.group(0)+'; '+zone.group(0),'line':text.count('\n',0,datum.start())+1})
    for label,key in ((r'North\s+Reference|指北方向|方位基准','north_reference'),(r'Datum\s+Name|Depth\s+Datum|深度基准','depth_datum')):
        found=re.search(r'(?:'+label+r')\s*[:：|]\s*([^\n|]{1,120})',text,re.I)
        if found:
            value=found.group(1).strip()
            if key=='north_reference':
                value=next((code for pattern,code in ((r'grid|网格','GRID_NORTH'),(r'true|真北','TRUE_NORTH'),(r'magnetic|磁北','MAGNETIC_NORTH')) if re.search(pattern,value,re.I)),None)
            if value:result[key]=value;evidence.append({'field':key,'text':found.group(0),'line':text.count('\n',0,found.start())+1})
    numeric={
        'depth_datum_elevation_m':r'(?:KB[- ]MSL|基准高程\s*[（(]m[）)])',
        'magnetic_declination':r'(?:Magnetic\s+Declination|磁偏角)(?:\s*[（(](?:deg|°)[）)])?',
        'meridian_convergence':r'(?:Grid\s+Convergence|Meridian\s+Convergence|子午线收敛角)(?:\s*[（(](?:deg|°)[）)])?',
    }
    for key,label in numeric.items():
        found=re.search(label+r'\s*[:：|]\s*([+-]?\d+(?:\.\d+)?)\s*(m|ft|deg|°)?',text,re.I)
        if found:
            value=float(found.group(1))
            if key=='depth_datum_elevation_m' and (found.group(2) or '').lower()=='ft':value*=.3048
            result[key]=value;evidence.append({'field':key,'text':found.group(0),'line':text.count('\n',0,found.start())+1})
    labels = {
        'name': r'Survey\s+Tool(?:\s+Name)?|Survey\s+Instrument|测斜(?:仪器|工具)(?:名称|型号)?|测量仪器',
        'code': r'Tool\s+(?:Code|Model)|Instrument\s+Model|仪器型号|工具代码',
        'serial_number': r'Tool\s+Serial(?:\s+Number)?|Instrument\s+Serial|仪器编号|仪器序列号',
        'calibration_date': r'Calibration\s+Date|校准日期|标定日期',
    }
    for key, label in labels.items():
        matches = list(re.finditer(r'(?:^|\n|\|)\s*(?:'+label+r')\s*(?::|：|\|)\s*([^\n|]{1,120})', text, re.I))
        values = list(dict.fromkeys(m.group(1).strip() for m in matches))
        if len(values) == 1:
            tool[key] = values[0]
        if matches:
            evidence.extend({'field': 'survey_tool.'+key, 'text': m.group(0).strip(),
                             'line': text.count('\n', 0, m.start())+1} for m in matches)
        if len(values) > 1:
            result.setdefault('metadata_conflicts', {})['survey_tool.'+key] = values
    if tool:
        result['survey_tool'] = tool
    models = list(re.finditer(r'(?:Error\s+Model|Uncertainty\s+Model|误差模型|不确定性模型)\s*[:：|]\s*([^\n|]{1,120})', text, re.I))
    model_values=list(dict.fromkeys(m.group(1).strip() for m in models))
    model=models[0] if len(model_values)==1 else None
    if len(model_values)>1:
        result.setdefault('metadata_conflicts',{})['uncertainty_model_text']=model_values
        evidence.extend({'field':'uncertainty_model_text','text':m.group(0),'line':text.count('\n',0,m.start())+1} for m in models)
    if model:
        value = model.group(1).strip()
        result['uncertainty_model_text'] = value
        if re.search(r'ISCWSA[\s_-]*(?:Rev\.?\s*)?0\b', value, re.I):
            result['uncertainty_model_hint'] = 'iscwsa_rev0'
        evidence.append({'field': 'uncertainty_model_text', 'text': model.group(0),
                         'line': text.count('\n', 0, model.start())+1})
    # Only labelled engineering quantities with stated units (or the exact
    # native key whose unit is fixed by its schema) can populate numeric inputs.
    values = {}
    patterns = {
        'sigma_inc_deg': r'(?:井斜测量标准差|Inclination\s+(?:Sigma|Std))\s*[（(](?:°|deg)[）)]',
        'sigma_azi_deg': r'(?:方位测量标准差|Azimuth\s+(?:Sigma|Std))\s*[（(](?:°|deg)[）)]',
        'sigma_md_m': r'(?:测深标准差|Depth\s+(?:Sigma|Std))\s*[（(]m[）)]',
        'accel_bias_mps2': r'加速度计偏置\s*[（(]m/s[²2][）)]',
        'mag_bias_nt': r'磁传感器偏置\s*[（(]nT[）)]',
    }
    from wellpath.anticollision.error_models.registry import load_model_settings
    native = load_model_settings('iscwsa_rev0').get('uncertainty_model', {}).get('parameters', {})
    for key in set(patterns) | set(native):
        label = r'(?:'+re.escape(key)+(r'|'+patterns[key] if key in patterns else '')+r')'
        found = list(re.finditer(label+r'\s*[:：=|]\s*([+-]?(?:\d+(?:\.\d*)?|\.\d+)(?:[eE][+-]?\d+)?)', text, re.I))
        unique = list(dict.fromkeys(float(m.group(1)) for m in found))
        if len(unique) == 1:
            values[key] = unique[0]
            evidence.append({'field': 'uncertainty_parameters.'+key, 'text': found[0].group(0),
                             'line': text.count('\n', 0, found[0].start())+1})
        elif len(unique) > 1:
            result.setdefault('metadata_conflicts', {})['uncertainty_parameters.'+key] = unique
    if values:
        result['uncertainty_parameters'] = values
    if evidence:
        result['survey_metadata_evidence'] = evidence
    return result
