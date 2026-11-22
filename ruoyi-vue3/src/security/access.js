export const moduleLabels = { realtime: '实时大屏', anticollision: '防碰评价', prediction: '预测展示', models: '模型仓库', replanning: '轨迹再规划', catalog: '工程资料目录', surveys: '井与测斜数据', 'realtime-data': '实时数据接入', config: '系统配置', files: '文件与审计', alerts: '告警中心', history: '任务历史', reports: '报告中心', connectors: '连接器管理', versions: '版本与变更记录' }
export const tierLabels = { ADMIN: '系统管理员', ENGINEER: '工程师', GUEST: '游客' }
export const modulePaths = { realtime: '/drilling/realtime', anticollision: '/drilling/tasks/anticollision', prediction: '/drilling/tasks/prediction/overview', models: '/drilling/tasks/prediction/models', replanning: '/drilling/tasks/replanning', catalog: '/drilling/basic/catalog', surveys: '/drilling/basic/surveys', 'realtime-data': '/drilling/basic/realtime-data', config: '/drilling/basic/config', files: '/drilling/basic/files', ...Object.fromEntries(['alerts','history','reports','connectors','versions'].map(key=>[key,`/drilling/centers/${key}`])) }
export function moduleForPath(path, query = {}) {
  const groups={'/drilling/basic/catalog':['catalog','surveys','versions'],'/drilling/basic/surveys':['surveys','versions'],'/drilling/basic/files':['files','history','reports'],'/drilling/basic/realtime-data':['realtime-data','connectors'],'/drilling/realtime':['realtime','alerts']}
  if(path==='/drilling/basic/catalog' && query.tab==='trajectory')return 'surveys'
  if(groups[path]?.includes(query.tab))return query.tab
  if (path.startsWith('/demo/')) return path.split('/')[2]
  return Object.keys(modulePaths).find(key => path === modulePaths[key] || path.startsWith(`${modulePaths[key]}/`))
}
export function firstModulePath(user) { const key = user.allowedModules[0]; return key ? (user.demoOnly ? `/demo/${key}` : modulePaths[key]) : '/401' }

export const accountRules = {
  accountId: /^[1-9]\d{9}$/,
  userName: /^[\p{Script=Han}A-Za-z0-9_·-]{2,20}$/u,
  password: /^(?=.*[A-Za-z])(?=.*\d)[A-Za-z0-9!@#$%^&*()_+=.?,~\-]{8,20}$/
}
export const accountHints = {
  accountId: '系统随机生成10位数字，永久不可修改',
  userName: '2～20个汉字、字母、数字、下划线、短横线或中点',
  password: '8～20位，必须同时包含字母和数字，可含 !@#$%^&*()_+=.?,~-，不允许空白或汉字'
}
