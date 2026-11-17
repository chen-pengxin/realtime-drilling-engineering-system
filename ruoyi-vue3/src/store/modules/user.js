import cache from '@/plugins/cache'
import { login, logout, getInfo } from '@/api/login'
import { getToken, setToken, removeToken } from '@/utils/auth'
import { isHttp, isEmpty } from "@/utils/validate"
import useLockStore from '@/store/modules/lock'
import defAva from '@/assets/images/profile.jpg'

const useUserStore = defineStore(
  'user',
  {
    state: () => ({
      token: getToken(),
      id: '',
      name: '',
      nickName: '',
      avatar: '',
      roles: [],
      permissions: [],
      accountTier: '', mustChangePassword: false, allowedModules: [], demoOnly: false, validFrom: null, validUntil: null
    }),
    actions: {
      // 登录
      login(userInfo) {
        const username = userInfo.username.trim()
        const password = userInfo.password
        const code = userInfo.code
        const rememberMe = userInfo.rememberMe
        return new Promise((resolve, reject) => {
          login(username, password, code, rememberMe).then(() => {
            // Java authentication is session-based (JSESSIONID), so the
            // frontend stores only a local signed-in marker, never a fake JWT.
            const sessionMarker = 'shiro-session'
            setToken(sessionMarker)
            this.token = sessionMarker
            useLockStore().unlockScreen()
            resolve()
          }).catch(error => {
            reject(error)
          })
        })
      },
      // 获取用户信息
      getInfo() {
        return new Promise((resolve, reject) => {
          getInfo().then(res => {
            const user = res.user
            let avatar = user.avatar || ""
            if (!isHttp(avatar)) {
              avatar = (isEmpty(avatar)) ? defAva : import.meta.env.VITE_APP_BASE_API + avatar
            }
            if (res.roles && res.roles.length > 0) { // 验证返回的roles是否是一个非空数组
              this.roles = res.roles
              this.permissions = res.permissions
            } else {
              this.roles = ['ROLE_DEFAULT']
            }
            this.id = user.userId
            this.name = user.userName
            this.nickName = user.nickName
            this.avatar = avatar
            cache.session.set('pwrChrtype', res.pwdChrtype)
            this.accountTier = res.accountTier
            this.mustChangePassword = Boolean(res.mustChangePassword)
            this.allowedModules = res.allowedModules || []
            this.demoOnly = Boolean(res.demoOnly)
            this.validFrom = res.validFrom
            this.validUntil = res.validUntil
            resolve(res)
          }).catch(error => {
            reject(error)
          })
        })
      },
      // 退出系统
      logOut() {
        return new Promise((resolve, reject) => {
          logout().then(() => {
            this.token = ''
            this.roles = []
            this.permissions = []
            this.accountTier = ''
            this.mustChangePassword = false
            this.allowedModules = []
            this.demoOnly = false
            removeToken()
            resolve()
          }).catch(error => {
            this.token = ''
            this.roles = []
            this.permissions = []
            this.accountTier = ''
            this.mustChangePassword = false
            this.allowedModules = []
            this.demoOnly = false
            removeToken()
            reject(error)
          })
        })
      }
    }
  })

export default useUserStore
