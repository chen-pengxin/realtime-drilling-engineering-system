import axios from 'axios'
import { ElNotification , ElMessageBox, ElMessage, ElLoading } from 'element-plus'
import { getToken, removeToken } from '@/utils/auth'
import errorCode from '@/utils/errorCode'
import { tansParams, blobValidate } from '@/utils/ruoyi'
import cache from '@/plugins/cache'
import { saveAs } from 'file-saver'
import useUserStore from '@/store/modules/user'

let downloadLoadingInstance
// 是否显示重新登录
export let isRelogin = { show: false }

axios.defaults.headers['Content-Type'] = 'application/json;charset=utf-8'
// 创建axios实例
const service = axios.create({
  // axios中请求配置有baseURL选项，表示请求URL公共部分
  baseURL: import.meta.env.VITE_APP_BASE_API,
  headers: { 'X-Requested-With': 'XMLHttpRequest' },
  // 超时
  timeout: 10000
})

// request拦截器
service.interceptors.request.use(config => {
  // 是否需要设置 token
  const isToken = (config.headers || {}).isToken === false
  // 是否需要防止数据重复提交
  const isRepeatSubmit = (config.headers || {}).repeatSubmit === false
  // 间隔时间(ms)，小于此时间视为重复提交
  const interval = (config.headers || {}).interval || 1000
  const token = getToken()
  if (token && token !== 'shiro-session' && !isToken) {
    config.headers['Authorization'] = 'Bearer ' + token // Bearer tokens are used only when the backend issues one.
  }
  // get请求映射params参数
  if (config.method === 'get' && config.params) {
    let url = config.url + '?' + tansParams(config.params)
    url = url.slice(0, -1)
    config.params = {}
    config.url = url
  }
  if (!isRepeatSubmit && (config.method === 'post' || config.method === 'put')) {
    const requestObj = {
      url: config.url,
      data: typeof config.data === 'object' ? JSON.stringify(config.data) : config.data,
      time: new Date().getTime()
    }
    const requestSize = Object.keys(JSON.stringify(requestObj)).length // 请求数据大小
    const limitSize = 5 * 1024 * 1024 // 限制存放数据5M
    if (requestSize >= limitSize) {
      console.warn(`[${config.url}]: ` + '请求数据大小超出允许的5M限制，无法进行防重复提交验证。')
      return config
    }
    const sessionObj = cache.session.getJSON('sessionObj')
    if (sessionObj === undefined || sessionObj === null || sessionObj === '') {
      cache.session.setJSON('sessionObj', requestObj)
    } else {
      const s_url = sessionObj.url                // 请求地址
      const s_data = sessionObj.data              // 请求数据
      const s_time = sessionObj.time              // 请求时间
      if (s_data === requestObj.data && requestObj.time - s_time < interval && s_url === requestObj.url) {
        const message = '数据正在处理，请勿重复提交'
        console.warn(`[${s_url}]: ` + message)
        return Promise.reject(new Error(message))
      } else {
        cache.session.setJSON('sessionObj', requestObj)
      }
    }
  }
  return config
}, error => {
    console.log(error)
    Promise.reject(error)
})

// 响应拦截器
service.interceptors.response.use(async res => {
    // 未设置状态码则默认成功状态
    const code = res.data.code || 200
    // 获取错误信息
    // Business errors from login and engineering APIs use RuoYi's 500 code,
    // but their response message explains the actual cause (for example,
    // “验证码错误”). Preserve that message instead of replacing it with the
    // generic “系统接口500异常”.
    const msg = res.data.msg || errorCode[code] || errorCode['default']
    // 二进制数据则直接返回
    if (res.request.responseType ===  'blob' || res.request.responseType ===  'arraybuffer') {
      if(String(res.headers?.['content-type']||'').includes('application/json')){
        try{
          const body=JSON.parse(res.data instanceof Blob?await res.data.text():new TextDecoder().decode(res.data))
          if(Number(body.code)>=400&&body.msg){if(!res.config.silentError)ElMessage.error(body.msg);throw new Error(body.msg)}
        }catch(error){if(!(error instanceof SyntaxError))throw error}
      }
      return res.data
    }
    if (code === 401) {
      if (!isRelogin.show) {
        isRelogin.show = true
        ElMessageBox.confirm('登录状态已过期，您可以继续留在该页面，或者重新登录', '系统提示', { confirmButtonText: '重新登录', cancelButtonText: '取消', type: 'warning' }).then(() => {
          isRelogin.show = false
          useUserStore().logOut().then(() => {
            location.href = '/index'
          })
      }).catch(() => {
        isRelogin.show = false
      })
    }
      return Promise.reject('无效的会话，或者会话已过期，请重新登录。')
    } else if (code === 500) {
      if (!res.config.silentError) ElMessage({ message: msg, type: 'error' })
      return Promise.reject(new Error(msg))
    } else if (code === 601) {
      if (!res.config.silentError) ElMessage({ message: msg, type: 'warning' })
      return Promise.reject(new Error(msg))
    } else if (code !== 200) {
      if (!res.config.silentError) ElNotification.error({ title: msg })
      return Promise.reject('error')
    } else {
      return  Promise.resolve(res.data)
    }
  },
  error => {
    if (axios.isCancel(error)) return Promise.reject(error)
    const payload = error.response?.data
    if (error.response?.status === 428 || payload?.code === 428) {
      let data = error.config?.data
      try { if (typeof data === 'string') data = JSON.parse(data) } catch (_) { data = {} }
      const fieldId = data?.field_id || error.config?.url?.match(/\/fields\/(\d+)/)?.[1]
      window.dispatchEvent(new CustomEvent('drilling-engine-confirmation-required', { detail: { fieldId } }))
      return Promise.reject(new Error(payload?.msg || '请先确认工程评价参数'))
    }
    if (payload?.code === 460) {
      useUserStore().mustChangePassword = true
      window.dispatchEvent(new CustomEvent('drilling-password-required'))
      return Promise.reject(new Error(payload.msg))
    }
    if (error.response?.status === 401) {
      removeToken()
      useUserStore().roles = []
      if (!isRelogin.show && location.pathname !== '/login') {
        isRelogin.show = true
        ElMessage.error(payload?.msg || '登录已失效，请重新登录')
        location.assign('/login')
      }
      return Promise.reject(new Error(payload?.msg || '登录状态已失效'))
    }
    let { message } = error
    if (payload?.msg || payload?.message || typeof payload?.detail === 'string') message = payload.msg || payload.message || payload.detail
    if (message == "Network Error") {
      message = "后端接口连接异常"
    } else if (message.includes("timeout")) {
      message = "系统接口请求超时"
    } else if (message.includes("Request failed with status code")) {
      message = "系统接口" + message.slice(-3) + "异常"
    }
    if (!error.config?.silentError) ElMessage({ message: message, type: 'error', duration: 5 * 1000 })
    error.message = message
    return Promise.reject(error)
  }
)

// 通用下载方法
export function download(url, params, filename, config) {
  downloadLoadingInstance = ElLoading.service({ text: "正在下载数据，请稍候", background: "rgba(0, 0, 0, 0.7)", })
  return service.post(url, params, {
    transformRequest: [(params) => { return tansParams(params) }],
    headers: { 'Content-Type': 'application/x-www-form-urlencoded' },
    responseType: 'blob',
    ...config
  }).then(async (data) => {
    const isBlob = blobValidate(data)
    if (isBlob) {
      const blob = new Blob([data])
      saveAs(blob, filename)
    } else {
      const resText = await data.text()
      const rspObj = JSON.parse(resText)
      const errMsg = errorCode[rspObj.code] || rspObj.msg || errorCode['default']
      ElMessage.error(errMsg)
    }
    downloadLoadingInstance.close()
  }).catch((r) => {
    console.error(r)
    ElMessage.error('下载文件出现错误，请联系管理员！')
    downloadLoadingInstance.close()
  })
}

export default service
