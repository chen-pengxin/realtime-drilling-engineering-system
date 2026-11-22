import useSettingsStore from '@/store/modules/settings'

// MySQL DATETIME fields returned without an offset are Beijing civil time.
export function formatPlatformTime(row,column,value) {
  if(value==null || value==='')return '—'
  let stamp=value
  if(typeof value==='string' && /^\d{4}-\d{2}-\d{2}[ T]\d{2}:\d{2}:\d{2}(?:\.\d+)?$/.test(value))stamp=value.replace(' ','T')+'+08:00'
  const date=new Date(stamp)
  if(!Number.isFinite(date.getTime()))return String(value)
  const options=useSettingsStore().platform
  return date.toLocaleString(options.dateStyle || 'zh-CN',{timeZone:options.timezone || 'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hourCycle:'h23'})
}
