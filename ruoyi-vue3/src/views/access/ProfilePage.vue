<template><div class="app-container"><h1 class="sr-only">个人中心</h1><el-row :gutter="20"><el-col :xs="24" :sm="6"><el-card class="box-card"><template #header><div class="clearfix"><span>个人信息</span></div></template><div class="text-center"><img :src="user.avatar" class="profile-avatar" alt="用户头像" /></div><ul class="list-group list-group-striped"><li class="list-group-item"><svg-icon icon-class="user" /> 账号ID <div class="pull-right">{{form.loginName}}</div></li><li class="list-group-item">账号名称 <div class="pull-right">{{form.userName}}</div></li><li class="list-group-item"><svg-icon icon-class="peoples" /> 用户等级 <div class="pull-right">{{tierLabels[user.accountTier]}}</div></li><li class="list-group-item"><svg-icon icon-class="phone" /> 手机号码 <div class="pull-right">{{form.phonenumber||'—'}}</div></li><li class="list-group-item"><svg-icon icon-class="email" /> 邮箱 <div class="pull-right">{{form.email||'—'}}</div></li><li v-if="user.demoOnly" class="list-group-item">访问时段（北京时间）<p>{{user.validFrom}}<br />至 {{user.validUntil}}</p><el-tag type="warning">游客演示模式</el-tag></li></ul></el-card></el-col><el-col :xs="24" :sm="18"><el-card><template #header><span>基本资料</span></template><el-tabs v-model="activeTab"><el-tab-pane label="基本资料" name="userinfo"><el-form v-loading="loading" :model="form" label-width="80px" @submit.prevent="save"><el-form-item label="账号名称"><el-input v-model="form.userName" maxlength="20" :placeholder="accountHints.userName" /></el-form-item><el-form-item label="手机号码"><el-input v-model="form.phonenumber" maxlength="11" :disabled="user.demoOnly" /></el-form-item><el-form-item label="邮箱"><el-input v-model="form.email" maxlength="50" :disabled="user.demoOnly" /></el-form-item><el-form-item><el-button type="primary" :loading="saving" @click="save">保存</el-button></el-form-item></el-form></el-tab-pane><el-tab-pane label="修改密码" name="resetPwd"><PasswordForm /></el-tab-pane></el-tabs></el-card></el-col></el-row></div></template>
<script setup>
import { reactive,ref,onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import PasswordForm from './PasswordForm.vue'
import './access.css'
import { getMe,updateProfile,renameAccount } from '@/api/access'
import useUserStore from '@/store/modules/user'
import { tierLabels,accountHints,accountRules } from '@/security/access'
const activeTab=ref('userinfo'),loading=ref(true)
const user=useUserStore(),form=reactive({}),saving=ref(false)
onMounted(async()=>{try{Object.assign(form,(await getMe()).data)}catch{ /* Session errors are handled globally. */ }finally{loading.value=false}})
async function save(){if(!accountRules.userName.test(form.userName)){ElMessage.error(accountHints.userName);return}saving.value=true;try{if(user.demoOnly)await renameAccount(form.userName);else await updateProfile(form);await user.getInfo();ElMessage.success('资料已更新')}catch{ /* API interceptor displays the validation error; keep the form open. */ }finally{saving.value=false}}
</script>

<style scoped>.profile-avatar{width:120px;height:120px;border-radius:50%;}@media(max-width:767px){.box-card{margin-bottom:20px;}}</style>
