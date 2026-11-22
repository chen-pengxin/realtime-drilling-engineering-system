<template><OperationsFrame title="全局参数配置">
 <el-form :inline="true" v-show="showSearch"><el-form-item label="参数"><el-input v-model="query.keyword" clearable placeholder="参数名称 / 键名" @keyup.enter="search" /></el-form-item><el-form-item><el-button type="primary" icon="Search" @click="search">搜索</el-button><el-button icon="Refresh" @click="reset">重置</el-button></el-form-item></el-form>
 <div class="operations-toolbar"><el-button type="primary" plain icon="Plus" @click="edit()">新增</el-button><el-button plain icon="Refresh" @click="refresh">刷新缓存</el-button><right-toolbar v-model:showSearch="showSearch" @queryTable="load" /></div>
 <el-table :data="rows" v-loading="loading"><el-table-column prop="configName" label="参数名称" min-width="220" /><el-table-column prop="configKey" label="参数键名" min-width="280" /><el-table-column prop="configValue" label="参数值" min-width="170" show-overflow-tooltip /><el-table-column label="类型" width="100"><template #default="{row}"><el-tag :type="row.configType==='Y'?'info':'success'">{{row.configType==='Y'?'内置':'自定义'}}</el-tag></template></el-table-column><el-table-column prop="remark" label="说明" min-width="250" show-overflow-tooltip /><el-table-column label="操作" width="140" fixed="right"><template #default="{row}"><el-button link type="primary" @click="edit(row)">修改</el-button><el-button v-if="row.configType!=='Y'" link type="danger" @click="remove(row)">删除</el-button></template></el-table-column></el-table>
 <pagination v-show="total>0" :total="total" v-model:page="query.pageNum" v-model:limit="query.pageSize" @pagination="load" />
 <el-dialog v-model="open" :title="form.configId?'修改参数':'新增参数'" width="590px" class="operations-dialog"><el-form ref="formRef" :model="form" :rules="rules" label-width="100px"><el-form-item label="参数名称" prop="configName"><el-input v-model="form.configName" maxlength="100" /></el-form-item><el-form-item label="参数键名" prop="configKey"><el-input v-model="form.configKey" :disabled="!!form.configId" placeholder="drilling.custom." maxlength="100" /></el-form-item><el-form-item label="参数值" prop="configValue"><el-select v-if="choices[form.configKey]" v-model="form.configValue"><el-option v-for="(label,value) in choices[form.configKey]" :key="value" :value="value" :label="label"/></el-select><template v-else-if="imageKeys.includes(form.configKey)"><input type="file" accept="image/png,image/jpeg" @change="uploadImage"/><span>{{form.configValue}}</span><el-button link @click="form.configValue='内置'">使用内置图片</el-button></template><el-input v-else v-model="form.configValue" maxlength="500" /><p class="operations-caption">{{hint}}</p></el-form-item><el-form-item label="备注"><el-input v-model="form.remark" type="textarea" maxlength="500" /></el-form-item></el-form><template #footer><el-button @click="open=false">取 消</el-button><el-button type="primary" :loading="saving" @click="save">确 定</el-button></template></el-dialog>
</OperationsFrame></template>
<script setup>
import {ref,reactive,computed} from 'vue';import {ElMessage,ElMessageBox} from 'element-plus';import OperationsFrame from './OperationsFrame.vue';import {useOperations} from './useOperations';import {createOperation,updateOperation,removeOperation,executeOperation,getRuntimeSettings} from '@/api/operations';import useSettingsStore from '@/store/modules/settings';
const {rows,total,loading,query,showSearch,load,search,reset}=useOperations('settings',{keyword:''});const open=ref(false),saving=ref(false),formRef=ref(),form=reactive({});
const rules={configName:[{required:true,message:'请输入参数名称',trigger:'blur'}],configKey:[{required:true,message:'请输入参数键名',trigger:'blur'}],configValue:[{required:true,message:'请输入参数值',trigger:'blur'}]}
import request from '@/utils/request'
const imageKeys=['logo','favicon','loginImage'].map(key=>'drilling.ui.'+key)
const choices=Object.fromEntries(Object.entries({
  preset:{light:'标准浅色',ocean:'深海青色'},density:{small:'紧凑',default:'标准',large:'宽松'},
  pageSize:{10:'10',20:'20',50:'50',100:'100'},timezone:{'Asia/Shanghai':'北京时间',UTC:'UTC'},
  dateStyle:{'zh-CN':'年/月/日','en-GB':'日/月/年'},home:{'/drilling/realtime':'实时大屏','/user/profile':'个人中心'},
  tags:{true:'显示',false:'隐藏'},breadcrumb:{true:'显示',false:'隐藏'},cache:{true:'启用',false:'关闭'}
}).map(([key,value])=>['drilling.ui.'+key,value]))
async function uploadImage(event){
  const file=event.target.files[0];if(!file)return
  const body=new FormData();body.append('file',file)
  saving.value=true
  try {form.configValue=(await request({url:'/api/operations/branding',method:'post',headers:{'Content-Type':'multipart/form-data'},data:body})).data.path}
  finally {saving.value=false}
}
const hints={'drilling.system.name':'系统名称为2～30个字符，保存后更新导航与浏览器标题。','drilling.session.timeoutMinutes':'5～240分钟，对用户后续请求生效。','drilling.monitor.refreshSeconds':'10～300秒，运行监控页按此间隔刷新。','drilling.maintenance.retentionDays':'1～365天，仅用于清理临时导出文件。'};const hint=computed(()=>hints[form.configKey]||'自定义参数供业务扩展读取。')
function edit(row){Object.keys(form).forEach(k=>delete form[k]);Object.assign(form,row?{...row}:{configName:'',configKey:'drilling.custom.',configValue:'',remark:''});open.value=true;formRef.value?.clearValidate()}
async function updateTitle(){const r=await getRuntimeSettings();useSettingsStore().applyPlatform(r.data);useSettingsStore().setTitle(useSettingsStore().title)}
async function save(){if(!await formRef.value.validate().catch(()=>false))return;saving.value=true;try{if(form.configId)await updateOperation('settings',form.configId,form);else await createOperation('settings',form);ElMessage.success('参数已保存');open.value=false;await updateTitle();await load()}catch{}finally{saving.value=false}}
async function refresh(){try{await executeOperation('settings/refresh');await updateTitle();await load();ElMessage.success('缓存已刷新')}catch{}}
async function remove(row){try{await ElMessageBox.confirm(`删除参数 ${row.configName}？`,'删除参数',{type:'warning'});await removeOperation('settings',row.configId);await load();ElMessage.success('参数已删除')}catch{}}
</script>
