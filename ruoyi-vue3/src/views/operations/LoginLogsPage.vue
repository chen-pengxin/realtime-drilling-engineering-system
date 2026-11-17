<template><OperationsFrame title="登录日志">
 <el-form :inline="true" v-show="showSearch" :model="query" @submit.prevent="search">
  <el-form-item label="账号"><el-input v-model="query.account" placeholder="账号ID / 账号名称" clearable @keyup.enter="search" /></el-form-item>
  <el-form-item label="登录地址"><el-input v-model="query.ipaddr" placeholder="IP地址" clearable @keyup.enter="search" /></el-form-item>
  <el-form-item label="状态"><el-select v-model="query.status" clearable placeholder="全部"><el-option label="成功" value="0" /><el-option label="失败" value="1" /></el-select></el-form-item>
  <el-form-item label="登录时间"><el-date-picker v-model="dateRange" type="daterange" value-format="YYYY-MM-DD" start-placeholder="开始日期" end-placeholder="结束日期" /></el-form-item>
  <el-form-item><el-button type="primary" icon="Search" @click="search">搜索</el-button><el-button icon="Refresh" @click="reset">重置</el-button></el-form-item>
 </el-form>
 <div class="operations-toolbar"><el-button type="warning" plain icon="Download" :loading="exporting" @click="download">导出</el-button><right-toolbar v-model:showSearch="showSearch" @queryTable="load" /></div>
 <el-table :data="rows" v-loading="loading"><el-table-column prop="infoId" label="访问编号" width="100" /><el-table-column prop="loginName" label="账号ID" min-width="140" /><el-table-column prop="userName" label="当前账号名称" min-width="140" show-overflow-tooltip /><el-table-column prop="ipaddr" label="IP地址" min-width="130" /><el-table-column prop="browser" label="浏览器" width="110" /><el-table-column prop="os" label="操作系统" width="120" /><el-table-column label="状态" width="85"><template #default="{row}"><el-tag :type="row.status==='0'?'success':'danger'">{{row.status==='0'?'成功':'失败'}}</el-tag></template></el-table-column><el-table-column prop="msg" label="说明" min-width="220" show-overflow-tooltip /><el-table-column prop="loginTime" label="时间" width="180" /><el-table-column label="操作" width="100" fixed="right"><template #default="{row}"><el-button link type="primary" @click="unlock(row)">解锁账号</el-button></template></el-table-column></el-table>
 <pagination v-show="total>0" :total="total" v-model:page="query.pageNum" v-model:limit="query.pageSize" @pagination="load" />
</OperationsFrame></template>
<script setup>
import { ref } from 'vue';import { ElMessage,ElMessageBox } from 'element-plus';import OperationsFrame from './OperationsFrame.vue';import {useOperations} from './useOperations';import {executeOperation,exportOperation} from '@/api/operations';
const {rows,total,loading,query,showSearch,dateRange,params,load,search,reset}=useOperations('login-logs',{account:'',ipaddr:'',status:''});const exporting=ref(false)
async function unlock(row){try{await ElMessageBox.confirm(`清除账号 ${row.loginName} 的登录失败锁定？`,'解锁账号',{type:'warning'});await executeOperation('login-logs/unlock',{account:row.loginName});ElMessage.success('账号已解锁')}catch{}}
async function download(){exporting.value=true;try{await exportOperation('login-logs',params())}catch{}finally{exporting.value=false}}
</script>
