import router from './router'
import { ElMessage } from 'element-plus'
import NProgress from 'nprogress'
import 'nprogress/nprogress.css'
import { getToken } from '@/utils/auth'
import { isHttp, isPathMatch } from '@/utils/validate'
import { isRelogin } from '@/utils/request'
import useUserStore from '@/store/modules/user'
import useLockStore from '@/store/modules/lock'
import useSettingsStore from '@/store/modules/settings'
import usePermissionStore from '@/store/modules/permission'
import { moduleForPath, firstModulePath } from '@/security/access'

NProgress.configure({ showSpinner: false })

const whiteList = ['/login']

const isWhiteList = (path) => {
  return whiteList.some(pattern => isPathMatch(pattern, path))
}

router.beforeEach(async (to, from) => {
  NProgress.start()
  if (getToken()) {
    to.meta.title && useSettingsStore().setTitle(to.meta.title)
    const isLock = useLockStore().isLock
    if (to.path === '/login') {
      NProgress.done()
      return { path: '/' }
    }
    if (isWhiteList(to.path)) {
      return true
    }
    if (!useUserStore().mustChangePassword && isLock && to.path !== '/lock') {
      NProgress.done()
      return { path: '/lock' }
    }
    if ((!isLock || useUserStore().mustChangePassword) && to.path === '/lock') {
      NProgress.done()
      return { path: '/' }
    }
    if (useUserStore().roles.length === 0) {
      isRelogin.show = true
      try {
        // 拉取user_info信息
        await useUserStore().getInfo()
        isRelogin.show = false
        // 根据roles权限生成可访问的路由
        const accessRoutes = await usePermissionStore().generateRoutes()
        accessRoutes.forEach(route => {
          if (!isHttp(route.path)) {
            router.addRoute(route)
          }
        })
        // 重新导航到目标路由，确保动态路由已注册
        return { ...to, replace: true }
      } catch (err) {
        try {
          await useUserStore().logOut()
        } catch (logoutError) {
          // A stale server session must not prevent returning to the login page.
        }
        isRelogin.show = false
        ElMessage.error(err?.message || err || '登录状态已失效，请重新登录')
        return { path: '/login', query: { redirect: to.fullPath } }
      }
    }
    const user = useUserStore()
    if (user.mustChangePassword && !['/user/password', '/user/password-required'].includes(to.path)) {
      return { path: '/user/password-required', replace: true }
    }
    if ((to.path.startsWith('/access') || to.path.startsWith('/operations')) && user.accountTier !== 'ADMIN') return '/401'
    if (to.path.startsWith('/messages') && user.demoOnly) return '/401'
    if (usePermissionStore().disabledPaths.some(p=>to.path===p||to.path.startsWith(`${p}/`))) return '/401'
    const module = moduleForPath(to.path,to.query)
    if(to.meta.modules && !to.query.tab && !user.allowedModules.includes(module)){const first=to.meta.modules.find(key=>user.allowedModules.includes(key));if(first)return {path:to.path,query:{...to.query,tab:first},replace:true}}
    if (module) {
      if (!user.allowedModules.includes(module)) return to.path === '/drilling/realtime' ? firstModulePath(user) : '/401'
      if (user.demoOnly && !to.path.startsWith('/demo/')) return `/demo/${module}`
    }
    if (to.path.startsWith('/demo/') && !user.allowedModules.includes(to.params.module)) return '/401'
    return true
  } else {
    // 没有token
    if (isWhiteList(to.path)) {
      // 在免登录白名单，直接进入
      return true
    }
    NProgress.done()
    return `/login?redirect=${to.fullPath}` // 否则全部重定向到登录页
  }
})

router.afterEach(() => {
  NProgress.done()
  // index.html contains a full-screen bootstrap loader. It must be removed
  // after the first successful navigation; otherwise an initialization error
  // or a route without dynamic permissions leaves the app looking as if it is
  // still loading even after Vue has mounted.
  document.getElementById('loader-wrapper')?.classList.add('loaded')
})
