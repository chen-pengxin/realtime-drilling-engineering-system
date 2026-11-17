import { ref, reactive, onMounted, onBeforeUnmount, onActivated, onDeactivated } from 'vue'
import { listOperation } from '@/api/operations'
export function useOperations(resource, defaults = {}) {
 const rows = ref([]), total = ref(0), loading = ref(false), showSearch = ref(true), dateRange = ref([])
 const query = reactive({ pageNum: 1, pageSize: 20, ...defaults })
 const params = () => ({ ...query, beginTime: dateRange.value?.[0], endTime: dateRange.value?.[1] })
 async function load() { loading.value = true; try { const r = await listOperation(resource, params()); rows.value = r.rows || r.data || []; total.value = r.total || rows.value.length } catch { /* The common request handler displays errors. */ } finally { loading.value = false } }
 function search() { query.pageNum = 1; return load() }
 function reset() { Object.assign(query, { pageNum: 1, pageSize: 20 }); Object.keys(defaults).forEach(k => query[k] = defaults[k]); dateRange.value = []; return load() }
 onMounted(load)
 return { rows, total, loading, query, showSearch, dateRange, params, load, search, reset }
}
export function usePagePolling(callback, milliseconds = 30000) {
 let timer; function start() { stop(); timer = window.setInterval(() => { if (!document.hidden) callback() }, milliseconds) } function stop() { window.clearInterval(timer) }
 onMounted(start); onActivated(start); onDeactivated(stop); onBeforeUnmount(stop)
 return { start, stop }
}
export function prettyJson(value) { try { return JSON.stringify(typeof value === 'string' ? JSON.parse(value) : value, null, 2) } catch { return value || '—' } }
export const sourceLabels = { ACCOUNT: '账号与权限', BUSINESS: '业务审计', REQUEST: '接口操作', LEGACY: '原系统操作' }
