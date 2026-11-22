<template><AccessFrame title="账号操作审计"><template #actions><el-button @click="load">刷新</el-button></template><el-table :data="rows" v-loading="loading"><el-table-column prop="audit_id" label="记录编号" width="100" /><el-table-column prop="actor_name" label="操作账号" min-width="130" /><el-table-column label="操作" min-width="150"><template #default="{row}">{{actions[row.action]||row.action}}</template></el-table-column><el-table-column prop="target_id" label="目标用户 / 角色ID" min-width="160" /><el-table-column label="结果" width="95"><template #default="{row}">{{row.outcome==='SUCCESS'?'成功':row.outcome==='FAILURE'?'失败':'未记录'}}</template></el-table-column><el-table-column prop="clientIp" label="来源 IP" min-width="120"/><el-table-column prop="detail_json" label="变更内容" min-width="240" /><el-table-column prop="create_time" label="时间" min-width="180" /></el-table><p class="access-note">保留账号创建、编辑、删除、密码重置与修改、角色授权变更记录。密码内容不会写入审计。</p></AccessFrame></template>
<script setup>
import {ref,onMounted} from 'vue'
import AccessFrame from './AccessFrame.vue'
import {accountAudit} from '@/api/access'
const rows=ref([]),loading=ref(false),actions={RENAME_ACCOUNT:'修改账号名称',CREATE_USER:'创建账号',UPDATE_USER:'修改账号与权限',DELETE_USER:'删除账号',RESET_PASSWORD:'重置密码',CHANGE_PASSWORD:'修改密码',UPDATE_PROFILE:'修改个人资料',CREATE_ROLE:'新增角色',UPDATE_ROLE:'修改角色权限',DELETE_ROLE:'删除角色'}
async function load(){loading.value=true;try{rows.value=(await accountAudit()).data}finally{loading.value=false}}onMounted(load)
</script>
