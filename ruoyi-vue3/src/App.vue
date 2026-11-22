<template>
  <el-config-provider :size="settings.preferences.density || settings.platform.density || undefined"><router-view /></el-config-provider>
  <el-dialog :model-value="user.mustChangePassword && route.path !== '/user/password' && route.path !== '/login'" title="必须修改初始密码" width="min(460px, 92vw)" class="password-gate" :show-close="false" :close-on-click-modal="false" :close-on-press-escape="false" :before-close="() => {}" :lock-scroll="true" append-to-body>
    <p>当前账号使用初始密码。请先修改密码，修改成功后才能使用系统。</p>
    <template #footer><el-button type="primary" @click="router.push('/user/password')">修改密码</el-button></template>
  </el-dialog>
</template>

<script setup>
import { getMe, reportActivity } from '@/api/access'
import { getRuntimeSettings } from '@/api/operations'
import useSettingsStore from '@/store/modules/settings'
import useUserStore from '@/store/modules/user'
import usePermissionStore from '@/store/modules/permission'
import { moduleForPath } from '@/security/access'
import { getToken } from '@/utils/auth'
import { useRoute, useRouter } from 'vue-router'
import './views/access/access.css'
const settings = useSettingsStore()
const user = useUserStore(), route = useRoute(), router = useRouter()
watch(() => [user.roles.length, user.mustChangePassword, user.accountTier,user.id], async () => {
  if (!user.roles.length || user.mustChangePassword) return
  try { const r = user.demoOnly ? {data:settings.platform} : await getRuntimeSettings(); const me=(await getMe()).data; settings.applyPlatform(r.data,me.preferences || {}); settings.setTitle(route.meta.title || settings.title) } catch {}
}, { immediate: true })
const inputEvents = ['pointerdown', 'pointermove', 'keydown', 'wheel', 'touchstart', 'touchmove', 'input']
const reportInterval = 10000
let lastSent = 0, lastInput = 0, pendingInput
function clearPendingInput() {
  window.clearTimeout(pendingInput)
  pendingInput = undefined
}
function sendInput() {
  clearPendingInput()
  const now = Date.now()
  const age = Math.max(0, now - lastInput)
  if (age > reportInterval || !getToken() || !user.roles.length || route.path === '/login') return
  lastSent = now
  reportActivity(age).catch(() => {})
}
function onUserInput(event) {
  if (!event.isTrusted || document.hidden || !user.roles.length || !getToken() || route.path === '/login') return
  lastInput = Date.now()
  const remaining = lastSent + reportInterval - lastInput
  if (remaining <= 0) sendInput()
  else if (pendingInput === undefined) pendingInput = window.setTimeout(sendInput, remaining)
}
watch(() => user.roles.length > 0, signedIn => {
  if (!signedIn) { clearPendingInput(); lastSent = 0 }
})
onMounted(() => inputEvents.forEach(type => window.addEventListener(type, onUserInput, { capture: true, passive: true })))
onBeforeUnmount(() => {
  clearPendingInput()
  inputEvents.forEach(type => window.removeEventListener(type, onUserInput, true))
})
let accountTimer
onMounted(() => { accountTimer = window.setInterval(async () => {
  if (!getToken() || !user.roles.length || route.path === '/login') return
  try { await user.getInfo(); await usePermissionStore().generateRoutes();
    if (user.mustChangePassword && !route.path.startsWith('/user/password')) router.replace('/user/password-required')
    const module = moduleForPath(route.path, route.query)
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
