<template>
  <router-view />
  <el-dialog :model-value="user.mustChangePassword && route.path !== '/user/password' && route.path !== '/login'" title="必须修改初始密码" width="min(460px, 92vw)" class="password-gate" :show-close="false" :close-on-click-modal="false" :close-on-press-escape="false" :before-close="() => {}" :lock-scroll="true" append-to-body>
    <p>当前账号使用初始密码。请先修改密码，修改成功后才能使用系统。</p>
    <template #footer><el-button type="primary" @click="router.push('/user/password')">修改密码</el-button></template>
  </el-dialog>
</template>

<script setup>
import { getRuntimeSettings } from '@/api/operations'
import useSettingsStore from '@/store/modules/settings'
import useUserStore from '@/store/modules/user'
import usePermissionStore from '@/store/modules/permission'
import { moduleForPath } from '@/security/access'
import { getToken } from '@/utils/auth'
import { useRoute, useRouter } from 'vue-router'
import './views/access/access.css'
const user = useUserStore(), route = useRoute(), router = useRouter()
watch(() => [user.roles.length, user.mustChangePassword, user.accountTier], async () => {
  if (!user.roles.length || user.mustChangePassword || user.demoOnly) return
  try { const r = await getRuntimeSettings(); const settings = useSettingsStore(); settings.systemName = r.data.systemName; settings.setTitle(route.meta.title || settings.title) } catch {}
}, { immediate: true })
let accountTimer
onMounted(() => { accountTimer = window.setInterval(async () => {
  if (!getToken() || !user.roles.length || route.path === '/login') return
  try { await user.getInfo(); await usePermissionStore().generateRoutes();
    if (user.mustChangePassword && !route.path.startsWith('/user/password')) router.replace('/user/password-required')
    const module = moduleForPath(route.path)
    if (module && !user.allowedModules.includes(module) && !user.mustChangePassword) router.replace('/401')
  } catch { /* Request interceptor handles disabled or expired sessions. */ }
}, 30000) })
onBeforeUnmount(() => window.clearInterval(accountTimer))
function passwordRequired() { user.mustChangePassword = true; if (route.path !== '/user/password') router.replace('/user/password-required') }
onMounted(() => window.addEventListener('drilling-password-required', passwordRequired))
onBeforeUnmount(() => window.removeEventListener('drilling-password-required', passwordRequired))
import { handleThemeStyle } from '@/utils/theme'

onMounted(() => {
  nextTick(() => {
    // 初始化主题样式
    handleThemeStyle(useSettingsStore().theme)
    // Hide the static bootstrap screen even when the current route renders
    // without triggering a router transition (for example after a hard
    // refresh). The optional chaining keeps this safe in tests/SSR.
    document.getElementById('loader-wrapper')?.classList.add('loaded')
  })
})
</script>
