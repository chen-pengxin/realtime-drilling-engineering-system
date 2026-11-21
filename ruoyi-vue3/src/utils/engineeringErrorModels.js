export const errorModelNames = Object.freeze({
  iscwsa_rev0: 'ISCWSA Rev.0 协方差模型',
  iscwsa: 'ISCWSA 工具误差模型',
  constant_covariance: '恒定协方差模型',
  constant_isotropic: '恒定各向同性误差模型',
  linear_std: '线性标准差模型',
  random_walk: '随机游走协方差模型',
  tabulated_covariance: '分井深协方差模型',
  md_scalar_radius: '随井深变化的标量半径模型',
  simplified_mwd: '简化随钻测量误差模型（MWD）',
  wdw: 'WdW 协方差传播模型'
})

export const errorModelName = name => errorModelNames[name] || name || '未配置'

// Names and units describe the supplied engine's inputs; numerical values stay
// in their native units and are never rounded or rescaled for confirmation.
export const errorParameterMetadata = Object.freeze({
  uncertainty_model: ['轨道不确定性模型', ''], parameters: ['测量误差参数', ''],
  header: ['测量环境参数', ''], type: ['轨道不确定性模型', ''],
  propagation_basis: ['不确定性传播方法', ''],
  accel_bias_mps2: ['加速度计偏置', 'm/s²'], accel_scale: ['加速度计比例误差', '无量纲'],
  mag_bias_nt: ['磁传感器偏置', 'nT'], mag_scale: ['磁传感器比例误差', '无量纲'],
  sag_deg: ['钻具下垂误差', '°'], misalignment_x_deg: ['X 向安装误差', '°'],
  misalignment_y_deg: ['Y 向安装误差', '°'], amid_constant_deg: ['轴向磁干扰常数误差', '°'],
  amid_direction_deg: ['轴向磁干扰方向误差', '°'], declination_sigma_deg: ['磁偏角标准差', '°'],
  dbh_deg_nt: ['地磁方位误差系数', '°·nT'], dip_sigma_deg: ['磁倾角标准差', '°'],
  total_field_sigma_nt: ['总磁场标准差', 'nT'], depth_random_m: ['随机深度误差', 'm'],
  depth_system_m: ['系统深度误差', 'm'], depth_scale: ['深度比例误差', '无量纲'],
  depth_stretch_per_m: ['深度拉伸系数', '1/m'], b_total_nt: ['总磁场强度', 'nT'],
  dip_deg: ['磁倾角', '°'], declination_deg: ['磁偏角', '°'], gravity: ['重力加速度', 'm/s²'],
  toolface_deg: ['工具面角', '°'], asz_divide_by_gravity: ['轴向加速度计比例误差重力归一化', ''],
  compass_sigma_deg: ['磁罗盘方位标准差', '°'], magnetization_sigma_deg: ['磁化误差标准差', '°'],
  gyro_sigma_deg: ['陀螺方位标准差', '°'], inclination_sigma_deg: ['井斜标准差', '°'],
  depth_scale_sigma: ['深度比例误差标准差', '无量纲'],
  misalignment_sigma_deg: ['安装误差标准差', '°'], magnetization_mean_deg: ['磁化误差均值', '°'],
  quadrature_order: ['数值积分阶数', ''], sigma_m: ['各向同性位置标准差', 'm'],
  cov_nev_m2: ['位置协方差矩阵', 'm²'], start_cov_nev_m2: ['起始位置协方差矩阵', 'm²'],
  correlation: ['位置分量相关系数矩阵', '无量纲'], sigma0_nev_m: ['起始位置标准差', 'm'],
  slope_nev_per_m: ['位置标准差增长率', 'm/m'], q_nev_m2_per_m: ['单位井深方差增量', 'm²/m'],
  depth_origin_m: ['误差增长起算井深', 'm'], md_m: ['协方差控制点井深', 'm'],
  csv_file: ['协方差数据文件', ''], sigma_md_m: ['测深标准差', 'm'],
  sigma_inc_deg: ['井斜测量标准差', '°'], sigma_azi_deg: ['方位测量标准差', '°'],
  md_break_1_m: ['第一分界井深', 'm'], md_break_2_m: ['第二分界井深', 'm'],
  radius_low_m: ['浅部井段不确定半径', 'm'], radius_mid_m: ['基准不确定半径', 'm'],
  slope_after_m2_m: ['半径随井深增长率', 'm/m'],
  tool_error_parameters: ['工具误差项幅值', ''],error_model: ['测量工具误差配置', ''], radius_m: ['井眼物理半径', 'm'],
  latitude: ['纬度', '°'], longitude: ['经度', '°'], altitude: ['海拔高程', 'm'],
  b_total: ['总磁场强度', 'nT'], dip: ['磁倾角', '°'], declination: ['磁偏角', '°'],
  convergence: ['子午线收敛角', '°'], azi_reference: ['方位基准', ''], dp_basis: ['深度误差传播方法', ''],
  G: ['重力加速度', 'm/s²'], earth_rate: ['地球自转角速度', 'rad/h'],
  vertical_inc_limit: ['近垂直井斜界限', 'rad'], grid_scale_factor: ['网格比例因子', '无量纲'],
  vertical_section_azimuth: ['垂直剖面方位角', '°'], deg: ['使用角度制输入', ''],
  depth_unit: ['深度单位', ''], surface_unit: ['地面坐标单位', ''],
  xcl_representation: ['协方差表示方式', ''], tie_on: ['采用接点误差', ''],
  first_station_allowance: ['首测点误差处理', ''],
  k: ['不确定性放大系数 K', '无量纲'], radiiMarginM: ['两井物理半径与附加裕量之和', 'm'],
  sigmaPaM: ['附加位置标准差', 'm'], curveToleranceM: ['轨迹离散几何容差', 'm']
})

export const parameterMeta = key => errorParameterMetadata[key] || [key, '']
export function errorParameterLabel(key) {
  const [name, unit] = parameterMeta(key)
  return unit ? `${name}（${unit}）` : name
}

export const parameterChoices = Object.freeze({
  propagation_basis: { minimum_curvature: '最小曲率法', balanced_tangent: '平衡切线法', midpoint_legacy: '中点法（历史配置）' },
  dp_basis: { min_curve: '最小曲率法', balanced_tangent: '平衡切线法' },
  azi_reference: { true: '真北', grid: '网格北', magnetic: '磁北' },
  depth_unit: { meters: '米' }, surface_unit: { meters: '米' },
  xcl_representation: { nev_direct: '北—东—垂向', dia: '测深—井斜—方位' }
})

export function formatErrorParameter(value, key) {
  if (typeof value === 'boolean') return value ? '是' : '否'
  if (key === 'type') return errorModelName(value)
  return parameterChoices[key]?.[value] ?? (value == null ? '未设置' : String(value))
}

// Only confirm parameters used by the selected native model and SF formula.
// Geometry tolerances, plotting options and inactive model profiles are not
// measurement-error parameters, even when present in an older saved profile.
const modelParameterKeys = Object.freeze({
  iscwsa: [],
  constant_covariance: ['cov_nev_m2'],
  constant_isotropic: ['sigma_m', 'correlation'],
  linear_std: ['sigma0_nev_m', 'slope_nev_per_m', 'depth_origin_m', 'correlation'],
  random_walk: ['sigma0_nev_m', 'q_nev_m2_per_m', 'depth_origin_m', 'correlation'],
  tabulated_covariance: ['csv_file', 'md_m', 'cov_nev_m2'],
  md_scalar_radius: ['md_break_1_m', 'md_break_2_m', 'radius_low_m', 'radius_mid_m', 'slope_after_m2_m'],
  simplified_mwd: ['sigma_md_m', 'sigma_inc_deg', 'sigma_azi_deg', 'depth_origin_m'],
  iscwsa_rev0: ['parameters', 'propagation_basis'],
  wdw: ['parameters', 'propagation_basis', 'quadrature_order']
})

export function separationFactorParameterKeys(configuration = {}) {
  const scalar = (configuration.settings?.uncertainty_model?.type || configuration.modelVersion) === 'md_scalar_radius'
  if (scalar && configuration.sfMethod === 'project_rss') return []
  return configuration.sfMethod === 'project_rss' ? ['k', 'radiiMarginM', 'sigmaPaM'] : ['k']
}

export function errorParameterRows(configuration = {}) {
  const rows = []
  const axes = ['北向 N', '东向 E', '垂向 TVD']
  function append(key, value, section) {
    const [label, unit] = parameterMeta(key)
    const add = (suffix, v) => rows.push({ key, label: label + suffix, value: formatErrorParameter(v, key), unit, section })
    if (Array.isArray(value)) {
      if (Array.isArray(value[0]?.[0])) {
        value.forEach((matrix, i) => matrix.forEach((row, r) => row.forEach((v, c) => add(` · 控制点 ${i + 1} · ${axes[r]} / ${axes[c]}`, v))))
      } else if (Array.isArray(value[0])) {
        value.forEach((row, r) => row.forEach((v, c) => add(` · ${axes[r] || r + 1} / ${axes[c] || c + 1}`, v)))
      } else value.forEach((v, i) => add(` · ${key === 'md_m' ? `控制点 ${i + 1}` : axes[i] || i + 1}`, v))
    } else if (value && typeof value === 'object') {
      Object.entries(value).forEach(([nestedKey, v]) => append(nestedKey, v, label))
    } else add('', value)
  }
  const settings = configuration.settings || {}
  const spec = settings.uncertainty_model || {}
  const kind = spec.type || configuration.modelVersion
  const evaluationKeys = separationFactorParameterKeys(configuration)
  if (evaluationKeys.includes('k') && configuration.k !== undefined) append('k', configuration.k, '评价参数')
  for (const key of modelParameterKeys[kind] || []) {
    if (kind === 'tabulated_covariance' && spec.csv_file && key !== 'csv_file') continue
    if (kind === 'wdw' && key === 'quadrature_order' && spec.propagation_basis === 'midpoint_legacy') continue
    if (spec[key] !== undefined) append(key, spec[key], '误差模型参数')
  }
  if (kind === 'iscwsa') {
    if (settings.error_model !== undefined) append('error_model', settings.error_model, '测量工具误差配置')
    if(settings.tool_error_parameters)append('tool_error_parameters',settings.tool_error_parameters,'工具误差项幅值')
    for (const [key, value] of Object.entries(settings.header || {})) {
      if (!['vertical_section_azimuth', 'xcl_representation'].includes(key)) append(key, value, '测量环境参数')
    }
  }
  if (['iscwsa', 'iscwsa_rev0', 'wdw'].includes(kind) && settings.start_cov_nev_m2 !== undefined) {
    append('start_cov_nev_m2', settings.start_cov_nev_m2, '初始条件')
  }
  for (const key of evaluationKeys.filter(key => key !== 'k')) {
    if (configuration[key] !== undefined) append(key, configuration[key], '分离系数公式参数')
  }
  return rows
}

export function separationFactorDetails(model = {}) {
  const scalar = (model.settings?.uncertainty_model?.type || model.modelVersion) === 'md_scalar_radius'
  const common = 'K 为不确定性放大系数（无量纲）；D 为两井中心距（m）；r₁、r₂ 为两井不确定半径（m）。'
  if (scalar) return {
    formula: model.sfMethod === 'project_rss' ? 'SF = D / (r₁ + r₂)' : 'SF = D / [K × (r₁ + r₂)]',
    definition: model.sfMethod === 'project_rss' ? 'D 为两井中心距（m）；r₁、r₂ 为两井不确定半径（m），两井不确定半径直接相加。' : common + '两井不确定半径相加后乘以 K，不区分投影方向。'
  }
  return {
    major_axis_sum: { formula: 'SF = D / [K × (r₁ + r₂)]', definition: common + 'rᵢ = √λmax(Cᵢ)，即位置协方差矩阵 Cᵢ（m²）最大特征值的平方根，对应误差椭球的最大半轴。' },
    directional_sum: { formula: 'SF = D / [K × (√(uᵀC₁u) + √(uᵀC₂u))]', definition: 'K 为不确定性放大系数（无量纲）；D 为两井中心距（m）；u 为中心连线单位向量；C₁、C₂ 为位置协方差矩阵（m²），分别沿 u 方向投影后开平方求和。' },
    project_rss: { formula: 'SF = max(D − b, 0) / [K × √(uᵀ(C₁ + C₂)u + σpa²)]', definition: 'K 为不确定性放大系数（无量纲）；D 为两井中心距（m）；b 为两井物理半径与附加裕量之和（m）；σpa 为附加位置标准差（m）；u 为中心连线单位向量；C₁、C₂ 为位置协方差矩阵（m²），按两井误差相互独立计算。' }
  }[model.sfMethod] || { formula: '—', definition: '当前配置未提供分离系数公式。' }
}
