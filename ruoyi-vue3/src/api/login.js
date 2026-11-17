import request from '@/utils/request'

// 登录方法
export function login(username, password, code, rememberMe = false) {
  // The Java/Shiro login endpoint reads request parameters (and the captcha
  // filter expects `validateCode`), so submit a form instead of JSON.
  const data = new URLSearchParams()
  data.set('username', username || '')
  data.set('password', password || '')
  data.set('validateCode', code || '')
  data.set('rememberMe', String(Boolean(rememberMe)))
  return request({
    url: '/login',
    headers: {
      isToken: false,
      repeatSubmit: false,
      'Content-Type': 'application/x-www-form-urlencoded;charset=UTF-8'
    },
    method: 'post',
    data: data
  })
}

// 注册方法
export function register(data) {
  return request({
    url: '/register',
    headers: {
      isToken: false
    },
    method: 'post',
    data: data
  })
}

// 获取用户详细信息
export function getInfo() {
  return request({
    url: '/getInfo',
    method: 'get'
  })
}

// 解锁屏幕
export function unlockScreen(password) {
  return request({
    url: '/unlockscreen',
    method: 'post',
    data: { password }
  })
}

// 退出方法
export function logout() {
  return request({
    url: '/logout',
    method: 'post'
  })
}

// 获取验证码
export function getCodeImg() {
  return request({
    // The Java backend writes the captcha as an image/jpeg response. Keep the
    // response binary so the view can display it as an object URL.
    url: '/captcha/captchaImage?type=math',
    headers: {
      isToken: false
    },
    method: 'get',
    responseType: 'blob',
    timeout: 20000
  })
}
