<template>
<el-form ref="formRef" :model="form" :rules="rules" label-position="top" class="password-form" @submit.prevent="submit">
      <el-form-item label="当前密码" prop="oldPassword"><el-input v-model="form.oldPassword" type="password" show-password autocomplete="current-password" /></el-form-item>
      <el-form-item label="新密码" prop="newPassword"><el-input v-model="form.newPassword" type="password" show-password autocomplete="new-password" maxlength="20" /></el-form-item>
      <el-form-item label="确认新密码" prop="confirmPassword"><el-input v-model="form.confirmPassword" type="password" show-password autocomplete="new-password" maxlength="20" @keyup.enter="submit" /></el-form-item>
      <p class="access-note">{{ accountHints.password }}。不能使用初始密码 123456。</p>
      <el-button type="primary" :loading="saving" @click="submit">保存新密码</el-button>
    </el-form>
</template>
<script setup>
import { reactive,ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { changePassword } from '@/api/access'
import useUserStore from '@/store/modules/user'
import { firstModulePath, accountRules, accountHints } from '@/security/access'
const user=useUserStore(),router=useRouter(),formRef=ref(),saving=ref(false)
const form=reactive({oldPassword:'',newPassword:'',confirmPassword:''})
const rules={oldPassword:[{required:true,message:'请输入当前密码',trigger:'blur'}],newPassword:[{validator:(_,v,cb)=>cb(!accountRules.password.test(v) || v==='123456'?new Error(accountHints.password):undefined),trigger:'blur'}],confirmPassword:[{validator:(_,v,cb)=>cb(!v || v!==form.newPassword?new Error('两次输入的密码不一致'):undefined),trigger:'blur'}]}
async function submit(){if(!await formRef.value.validate().catch(()=>false))return;saving.value=true;try{await changePassword(form);await user.getInfo();ElMessage.success('密码修改成功');await router.replace(firstModulePath(user))}catch{ /* API interceptor displays the validation error; keep the form open. */ }finally{saving.value=false}}
</script>
