<template>
  <el-alert v-if="detailsState === 'error'" title="角色、工程数据范围和最近登录加载失败" type="error" :closable="false">
    <el-button link type="primary" @click="loadDetails">重新加载权限与记录</el-button>
  </el-alert>
  <el-descriptions v-else v-loading="detailsState === 'loading'" :column="1" border>
    <el-descriptions-item label="角色">{{ details.roles?.join('、') }}</el-descriptions-item>
    <el-descriptions-item label="工程数据范围">
      <span v-if="guest">仅已授权的演示案例</span>
      <el-select v-else filterable placeholder="搜索已授权的完整路径" style="width:100%" v-model="choice">
        <el-option v-for="path in paths" :key="path" :label="path" :value="path"/>
      </el-select>
    </el-descriptions-item>
  </el-descriptions>
  <h3>界面偏好</h3>
  <el-alert v-if="prefsState === 'error'" title="界面偏好加载失败" type="error" :closable="false">
    <el-button link type="primary" @click="loadPrefs">重新加载偏好</el-button>
  </el-alert>
  <el-form v-loading="prefsState === 'loading'" :disabled="prefsState !== 'ready' || savingPrefs" label-width="100px">
    <el-form-item v-for="(choices,key) in options" :key="key" :label="names[key]">
      <el-select v-model="prefs[key]" placeholder="跟随平台" clearable style="width:210px">
        <el-option v-for="(name,value) in choices" :key="value" :label="name" :value="value"/>
      </el-select>
    </el-form-item>
    <el-form-item><el-button type="primary" :loading="savingPrefs" @click="save">保存偏好</el-button></el-form-item>
  </el-form>
  <h3 v-if="detailsState === 'ready'">最近登录</h3>
  <el-table v-if="detailsState === 'ready'" :data="details.logins"><el-table-column :formatter="formatPlatformTime" prop="time" label="时间"/><el-table-column prop="ip" label="来源 IP"/><el-table-column prop="browser" label="浏览器"/><el-table-column label="结果"><template #default="{row}">{{row.status==='0'?'登录成功':row.status==='2'?'退出登录':'登录失败'}}</template></el-table-column></el-table>
  <h3>设备管理</h3>
  <p class="device-hint">每条记录代表一次浏览器登录。同一台设备使用不同浏览器登录时，会分别列出。</p>
  <div class="device-actions">
    <el-button type="danger" plain :disabled="!selected.length || loadingDevices" :loading="revoking" @click="revokeSelected">
      退出所选设备{{ selected.length ? '（' + selected.length + '）' : '' }}
    </el-button>
    <el-button :loading="loadingDevices" :disabled="revoking" @click="loadDevices">刷新</el-button>
  </div>
  <el-table :data="devices" row-key="sessionKey" v-loading="loadingDevices || revoking"
            @selection-change="selected=$event" :empty-text="deviceError ? '加载失败，请刷新重试' : '暂无在线设备'">
    <el-table-column type="selection" width="48"/>
    <el-table-column label="登录设备" min-width="190">
      <template #default="{row}">{{ row.os || '未知系统' }} · {{ row.browser || '未知浏览器' }}
        <el-tag v-if="row.current" type="success" size="small">当前登录</el-tag>
      </template>
    </el-table-column>
    <el-table-column prop="ipaddr" label="登录 IP" min-width="130"/>
    <el-table-column :formatter="formatPlatformTime" prop="startTime" label="登录时间" min-width="165"/>
    <el-table-column :formatter="formatPlatformTime" prop="lastAccessTime" label="最近活动" min-width="165"/>
  </el-table>
</template>
<script setup>
import {formatPlatformTime} from '@/utils/platformTime'

import {ref,computed,onMounted} from 'vue'
import request from '@/utils/request'
import useSettingsStore from '@/store/modules/settings'
import useUserStore from '@/store/modules/user'
import {ElMessage,ElMessageBox} from 'element-plus'
defineProps({guest:Boolean})
const layout=useSettingsStore(),details=ref({}),choice=ref(''),prefs=ref({})
const detailsState = ref('loading')
const prefsState = ref('loading')
const savingPrefs = ref(false)
const user=useUserStore()
const devices=ref([]), selected=ref([]), loadingDevices=ref(false), revoking=ref(false), deviceError=ref(false)
const deviceUrl='/api/operations/sessions/self'
const paths=computed(()=>[...new Set((details.value.scope || []).map(x=>[x.blockName,x.fieldName,x.wellName,x.boreName].filter(Boolean).join(' / ')))])
const names={preset:'主题方案',density:'表格密度',tags:'标签页',breadcrumb:'页面位置导航'}
const options={preset:{light:'标准浅色',ocean:'深海青色'},density:{small:'紧凑',default:'标准',large:'宽松'},tags:{true:'显示',false:'隐藏'},breadcrumb:{true:'显示',false:'隐藏'}}
async function loadDetails() {
  detailsState.value = 'loading'
  try {
    const { data } = await request({ url: '/api/access/me/details' })
    details.value = data
    detailsState.value = 'ready'
  } catch {
    detailsState.value = 'error'
  }
}

async function loadPrefs() {
  prefsState.value = 'loading'
  try {
    const { data } = await request({ url: '/api/access/me' })
    const stored = data.preferences
    if (!stored || typeof stored !== 'object' || Array.isArray(stored)) {
      throw new Error('界面偏好数据无效')
    }
    prefs.value = { ...stored }
    prefsState.value = 'ready'
  } catch {
    prefsState.value = 'error'
  }
}

onMounted(loadDetails)
onMounted(loadPrefs)
onMounted(loadDevices)

async function save() {
  if (prefsState.value !== 'ready' || savingPrefs.value) return
  savingPrefs.value = true
  try {
    const data = Object.fromEntries(Object.entries(prefs.value).filter(([, value]) => value != null && value !== ''))
    await request({ url: '/api/access/me/preferences', method: 'put', data, headers: { repeatSubmit: false } })
    layout.applyPlatform(layout.platform, data)
    ElMessage.success('偏好已保存')
  } catch {
    // Keep the user's selection available for retry.
  } finally {
    savingPrefs.value = false
  }
}
async function loadDevices(){
  loadingDevices.value=true
  try {
    const {data}=await request({url:deviceUrl})
    devices.value=data
    deviceError.value=false
  } catch {
    devices.value=[]
    deviceError.value=true
  } finally {
    selected.value=[]
    loadingDevices.value=false
  }
}
async function revokeSelected(){
  const rows=[...selected.value]
  if(!rows.length || revoking.value)return
  const leaveHere=rows.some(row=>row.current)
  const otherKeys=rows.filter(row=>!row.current).map(row=>row.sessionKey)
  const message=leaveHere
    ? '已选中当前登录。确认退出所选的 '+rows.length+' 个登录并返回登录页？'
    : '退出选中的 '+rows.length+' 个登录？这些设备下次访问时需要重新登录。'
  const confirmed=await ElMessageBox.confirm(
    message,
    '退出所选设备',{type:'warning',confirmButtonText:'确认退出',cancelButtonText:'取消'}
  ).then(()=>true).catch(()=>false)
  if(!confirmed)return
  revoking.value=true
  let signedOut=false
  try {
    if(otherKeys.length){
      const {data}=await request({url:deviceUrl+'/revoke-selected',method:'post',data:otherKeys})
      if(!leaveHere){
        const offline=otherKeys.length-data.count
        ElMessage.success('已退出 '+data.count+' 个登录'+(offline?'，另有 '+offline+' 个已下线':''))
      }
    }
    if(leaveHere){
      await user.logOut()
      signedOut=true
      window.location.replace('/login')
    }
  } finally {
    if(!signedOut)await loadDevices()
    revoking.value=false
  }
}
</script>
<style scoped>
.device-hint{color:var(--el-text-color-secondary);font-size:13px}
.device-actions{display:flex;gap:8px;margin-bottom:12px}
</style>
