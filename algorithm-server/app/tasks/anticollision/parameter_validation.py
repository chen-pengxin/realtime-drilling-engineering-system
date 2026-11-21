"""Validate engineering parameters without solving or evaluating a trajectory."""
import math
import numpy as np
from wellpath.anticollision.error_models.registry import load_model_settings
from wellpath.anticollision.error_models.schema import _model_spec
from wellpath.anticollision.error_models.common import parameters, validate_covariance, _validate_correlation


def validate_settings(settings):
    spec = _model_spec(settings)
    kind = spec['type']

    def nonnegative(key, value, positive=False):
        if isinstance(value, bool) or not math.isfinite(float(value)) or (float(value) <= 0 if positive else float(value) < 0):
            raise ValueError(f'{key} 必须为有限的'+('正数' if positive else '非负数'))

    if kind in ('iscwsa_rev0', 'wdw'):
        base = load_model_settings(kind)['uncertainty_model']
        p = parameters(base['parameters'], spec.get('parameters', {}))
        allowed = ('minimum_curvature', 'balanced_tangent') if kind == 'iscwsa_rev0' else ('minimum_curvature', 'midpoint_legacy')
        if spec.get('propagation_basis', 'minimum_curvature') not in allowed:
            raise ValueError('不支持的误差传播方法')
        if kind == 'iscwsa_rev0' and (p['gravity'] <= 0 or p['b_total_nt'] <= 0 or abs(p['dip_deg']) >= 89.9):
            raise ValueError('重力加速度及总磁场强度必须大于0，磁倾角绝对值须小于89.9°')
        if kind == 'wdw':
            order = spec.get('quadrature_order', 16)
            if isinstance(order, bool) or int(order) != order or not 2 <= order <= 64:
                raise ValueError('积分阶数必须为2～64的整数')
    elif kind == 'iscwsa':
        import welleng as we
        from wellpath.anticollision.error_models.iscwsa.model import header
        header(settings)
        from .tool_parameters import validate_overrides
        validate_overrides(settings)
        if settings.get('error_model', 'ISCWSA MWD Rev5.11') not in we.error.get_error_models():
            raise ValueError('未知的ISCWSA测斜工具误差代码')
    elif kind == 'constant_covariance':
        validate_covariance(spec['cov_nev_m2'])
    elif kind in ('linear_std', 'random_walk'):
        for key in ('sigma0_nev_m', 'slope_nev_per_m' if kind == 'linear_std' else 'q_nev_m2_per_m'):
            vector = np.asarray(spec[key], dtype=float)
            if vector.shape != (3,) or not np.isfinite(vector).all() or (vector < 0).any():
                raise ValueError(f'{key} 必须包含北、东、垂向三个非负有限数')
        nonnegative('误差起算井深', spec.get('depth_origin_m', 0))
    elif kind == 'constant_isotropic':
        nonnegative('位置标准差', spec['sigma_m'])
    elif kind == 'tabulated_covariance':
        if 'csv_file' in spec:
            raise ValueError('请将协方差文件导入为井深与矩阵表，不能使用服务器文件路径')
        md = np.asarray(spec['md_m'], dtype=float)
        cov = np.asarray(spec['cov_nev_m2'], dtype=float)
        if md.ndim != 1 or not len(md) or not np.isfinite(md).all() or (md < 0).any() or (np.diff(md) <= 0).any() or cov.shape != (len(md), 3, 3):
            raise ValueError('协方差表井深须非负且严格递增，且每个井深对应一个3×3矩阵')
        for matrix in cov:
            validate_covariance(matrix)
    elif kind == 'md_scalar_radius':
        base = load_model_settings(kind)['uncertainty_model']
        values = {**base, **spec}
        for key in ('md_break_1_m', 'md_break_2_m', 'radius_low_m', 'radius_mid_m', 'slope_after_m2_m'):
            nonnegative(key, values[key], key.startswith('radius_'))
        if values['md_break_1_m'] > values['md_break_2_m']:
            raise ValueError('第一分界井深不能大于第二分界井深')
    elif kind == 'simplified_mwd':
        for key, value in spec.items():
            if key != 'type':
                nonnegative(key, value)
    if 'correlation' in spec:
        _validate_correlation(spec['correlation'])
    if 'start_cov_nev_m2' in settings:
        validate_covariance(settings['start_cov_nev_m2'])
    if 'radius_m' in settings:
        nonnegative('井眼半径', settings['radius_m'])
    return kind
