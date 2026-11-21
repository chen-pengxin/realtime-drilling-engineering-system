"""Per-request tool magnitudes using the supplied welleng weight functions.

Packaged model files and their cached objects are never edited. Context-local
overrides are applied to a private model copy while native propagation runs.
"""
from contextvars import ContextVar
from copy import deepcopy
from functools import lru_cache
import math
from pathlib import Path

_budget = ContextVar('iscwsa_tool_budget', default=None)


@lru_cache(maxsize=128)
def tool_definition(name):
    import welleng as we
    from welleng.error import TOOL_INDEX
    from welleng.errors import tool_errors as te
    if name not in we.error.get_error_models():
        raise ValueError('未知的 ISCWSA 工具型号')
    key = next((k for k, v in TOOL_INDEX.items() if v['Short Name'] == name), name)
    yaml_path = Path(te.PATH) / 'tool_codes' / f'{key}.yaml'
    if yaml_path.is_file():
        model = te._drilling_original_yaml(str(yaml_path))
        return {'path': str(yaml_path), 'format': 'yaml', 'codes': model['codes']}
    path = te._resolve_json_model(key) or te._resolve_json_model(name)
    if not path:
        raise ValueError('工具误差项配置不存在')
    model = te._drilling_original_json(path)
    return {'path': str(path), 'format': 'json', 'codes': te._json_to_em_adapter(model)['codes']}


def tool_terms(name):
    definition = tool_definition(name)
    return [{'code': key, 'value': float(term['magnitude']), 'unit': term['unit'],
             'propagation': term['propagation'], 'function': term['function']}
            for key, term in definition['codes'].items()]


def validate_overrides(settings):
    values = settings.get('tool_error_parameters', {})
    if not isinstance(values, dict):
        raise ValueError('工具误差参数必须为对象')
    terms = {row['code']: row for row in tool_terms(settings.get('error_model', 'ISCWSA MWD Rev5.11'))}
    for code, value in values.items():
        if code not in terms:
            raise ValueError(f'当前工具不包含误差项 {code}')
        if isinstance(value, bool) or not isinstance(value, (int, float)) or not math.isfinite(value) or value < 0:
            raise ValueError(f'误差项 {code} 必须为非负有限数')
    return values


def install_tool_parameter_adapter():
    from welleng.errors import tool_errors as te
    import wellpath.api as api
    if hasattr(te, '_drilling_original_yaml'):
        return
    te._drilling_original_yaml = te._load_yaml_model
    te._drilling_original_json = te._load_json_model

    def yaml_model(path):
        source = te._drilling_original_yaml(path)
        budget = _budget.get()
        if not budget or str(path) != budget['path']:
            return source
        model = deepcopy(source)
        for code, value in budget['values'].items():
            model['codes'][code]['magnitude'] = value
        return model

    def json_model(path):
        source = te._drilling_original_json(path)
        budget = _budget.get()
        if not budget or str(path) != budget['path']:
            return source
        model = deepcopy(source)
        for term in model['terms']:
            if term['name'] in budget['values']:
                term['value'] = budget['values'][term['name']]
        return model

    original = api.make_survey

    def make_survey(md, inc, azi, positions, settings):
        values = validate_overrides(settings)
        if not values:
            return original(md, inc, azi, positions, settings)
        definition = tool_definition(settings.get('error_model', 'ISCWSA MWD Rev5.11'))
        token = _budget.set({'path': definition['path'], 'values': values})
        try:
            return original(md, inc, azi, positions, settings)
        finally:
            _budget.reset(token)

    te._load_yaml_model = yaml_model
    te._load_json_model = json_model
    api.make_survey = make_survey
