<template><AccessFrame title="修改密码"><el-alert v-if="user.mustChangePassword" title="请先修改初始密码，修改成功后才能使用系统功能。" type="warning" :closable="false" show-icon style="margin-bottom:20px" /><el-card><PasswordForm /></el-card></AccessFrame></template>
<script setup>
import AccessFrame from './AccessFrame.vue'
import PasswordForm from './PasswordForm.vue'
import useUserStore from '@/store/modules/user'
const user=useUserStore()
</script>
