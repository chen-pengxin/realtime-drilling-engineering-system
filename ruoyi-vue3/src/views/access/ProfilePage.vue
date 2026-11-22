<template>
  <div class="app-container">
    <h1 class="sr-only">个人中心</h1>
    <el-row :gutter="20">
      <el-col :xs="24" :sm="6">
        <el-card class="box-card">
          <template #header>个人信息</template>
          <div class="text-center">
            <img :src="user.avatar" class="profile-avatar" alt="用户头像" />
            <label class="link-type">更换头像<input type="file" hidden accept="image/png,image/jpeg" @change="avatar" /></label>
          </div>
          <ul class="list-group list-group-striped">
            <li class="list-group-item"><svg-icon icon-class="user" /> 账号ID <div class="pull-right">{{ saved?.loginName || '—' }}</div></li>
            <li class="list-group-item">账号名称 <div class="pull-right">{{ saved?.userName || '—' }}</div></li>
            <li class="list-group-item"><svg-icon icon-class="peoples" /> 用户等级 <div class="pull-right">{{ tierLabels[user.accountTier] }}</div></li>
            <li class="list-group-item"><svg-icon icon-class="phone" /> 手机号码 <div class="pull-right">{{ saved?.phonenumber || '—' }}</div></li>
            <li class="list-group-item"><svg-icon icon-class="email" /> 邮箱 <div class="pull-right">{{ saved?.email || '—' }}</div></li>
            <li v-if="user.demoOnly" class="list-group-item">访问时段（北京时间）<p>{{ user.validFrom }}<br />至 {{ user.validUntil }}</p><el-tag type="warning">游客演示模式</el-tag></li>
          </ul>
        </el-card>
      </el-col>
      <el-col :xs="24" :sm="18">
        <el-card>
          <template #header>基本资料</template>
          <el-tabs v-model="activeTab">
            <el-tab-pane label="基本资料" name="userinfo">
              <el-alert v-if="loadError" type="error" :closable="false" title="个人资料加载失败">
                <el-button link type="primary" @click="loadProfile">重新加载资料</el-button>
              </el-alert>
              <el-form ref="profileForm" v-loading="loading" :disabled="!saved || saving" :model="form"
                       :rules="rules" label-width="80px" @submit.prevent="save">
                <el-form-item label="账号名称" prop="userName">
                  <el-input v-model.trim="form.userName" maxlength="20" :placeholder="accountHints.userName" />
                </el-form-item>
                <el-form-item label="手机号码" prop="phonenumber">
                  <el-input v-model.trim="form.phonenumber" maxlength="11" />
                </el-form-item>
                <el-form-item label="邮箱" prop="email">
                  <el-input v-model.trim="form.email" maxlength="50" />
                </el-form-item>
                <el-form-item><el-button type="primary" native-type="submit" :loading="saving">保存</el-button></el-form-item>
              </el-form>
            </el-tab-pane>
            <el-tab-pane label="修改密码" name="resetPwd"><PasswordForm /></el-tab-pane>
            <el-tab-pane label="权限、设备与界面" name="security" lazy><PersonalSettings :guest="user.demoOnly" /></el-tab-pane>
          </el-tabs>
        </el-card>
      </el-col>
    </el-row>
  </div>
</template>
<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import PersonalSettings from './PersonalSettings.vue'
import PasswordForm from './PasswordForm.vue'
import './access.css'
import { getMe, updateProfile } from '@/api/access'
import { tierLabels, accountHints, accountRules } from '@/security/access'
import useUserStore from '@/store/modules/user'
import request from '@/utils/request'

const user = useUserStore()
const activeTab = ref('userinfo')
const saved = ref(null)
const form = reactive({ userName: '', phonenumber: '', email: '' })
const profileForm = ref(null)
const loading = ref(true)
const loadError = ref(false)
const saving = ref(false)
const rules = {
  userName: [{ required: true, pattern: accountRules.userName, message: accountHints.userName, trigger: 'blur' }],
  phonenumber: [{ pattern: /^1[3-9][0-9]{9}$/, message: '请输入正确的11位手机号', trigger: 'blur' }],
  email: [{ pattern: /^[a-z0-9!#$%&'*+\/=?^_`{|}~-]+(?:\.[a-z0-9!#$%&'*+\/=?^_`{|}~-]+)*@[a-z0-9](?:[a-z0-9-]*[a-z0-9])?(?:\.[a-z0-9](?:[a-z0-9-]*[a-z0-9])?)+$/i, message: '请输入正确的邮箱地址', trigger: 'blur' }]
}

async function loadProfile() {
  loading.value = true
  loadError.value = false
  try {
    const { data } = await getMe()
    Object.assign(form, { userName: data.userName, phonenumber: data.phonenumber || '', email: data.email || '' })
    saved.value = { loginName: data.loginName, ...form }
  } catch {
    loadError.value = true
  } finally {
    loading.value = false
  }
}
onMounted(loadProfile)

async function save() {
  if (!saved.value || saving.value) return
  saving.value = true
  try {
    if (!await profileForm.value.validate().catch(() => false)) return
    const data = { ...form }
    await updateProfile(data)
    saved.value = { ...saved.value, ...data }
    ElMessage.success('资料已更新')
    await user.getInfo()
  } catch {
    // The request interceptor displays the server error.
  } finally {
    saving.value = false
  }
}

async function avatar(event) {
  const file = event.target.files[0]
  if (!file) return
  const data = new FormData()
  data.append('file', file)
  await request({ url: '/api/access/me/avatar', method: 'post', headers: { 'Content-Type': 'multipart/form-data' }, data })
  await user.getInfo()
  ElMessage.success('头像已更新')
}
</script>
<style scoped>.profile-avatar{width:120px;height:120px;border-radius:50%;}@media(max-width:767px){.box-card{margin-bottom:20px;}}</style>
