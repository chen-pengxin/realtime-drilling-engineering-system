<template><div class="app-container tree-sidebar-manage-wrap account-manage">
  <h1 class="sr-only">用户管理</h1>
  <TreePanel title="部门列表" :tree-data="departmentTree" search-placeholder="请输入部门名称" storage-key="account-department-sidebar" :default-expand-all="true" @node-click="selectDepartment" @refresh="load" />
  <div class="tree-sidebar-content"><div class="content-inner">
    <el-form :model="query" :inline="true" v-show="showSearch" label-width="68px">
      <el-form-item label="账号名称"><el-input v-model="query.keyword" placeholder="请输入账号ID或账号名称" clearable style="width:240px" @keyup.enter="search" /></el-form-item>
      <el-form-item label="用户等级"><el-select v-model="query.tier" placeholder="全部等级" clearable style="width:200px" @change="search"><el-option v-for="(name,key) in tierLabels" :key="key" :value="key" :label="name" /></el-select></el-form-item>
      <el-form-item label="状态"><el-select v-model="query.status" placeholder="用户状态" clearable style="width:160px"><el-option label="正常" value="0" /><el-option label="停用" value="1" /></el-select></el-form-item>
      <el-form-item><el-button type="primary" icon="Search" @click="search">搜索</el-button><el-button icon="Refresh" @click="resetQuery">重置</el-button></el-form-item>
    </el-form>
    <el-row :gutter="10" class="mb8">
      <el-col :span="1.5"><el-button type="primary" plain icon="Plus" @click="open()">新增</el-button></el-col>
      <el-col :span="1.5"><el-button type="success" plain icon="Edit" :disabled="selected.length!==1" @click="open(selected[0].userId)">修改</el-button></el-col>
      <el-col :span="1.5"><el-button type="danger" plain icon="Delete" :disabled="!selected.length || selected.some(x=>x.userId===1 || x.userId===user.id)" @click="removeSelected">删除</el-button></el-col>
      <right-toolbar v-model:showSearch="showSearch" @queryTable="load" />
    </el-row>
    <el-table :data="rows" v-loading="loading" row-key="userId" @selection-change="selected=$event">
      <el-table-column type="selection" width="50" align="center" />
      <el-table-column label="账号ID" min-width="130" align="center"><template #default="{row}"><a class="link-type" @click="open(row.userId)">{{row.loginName}}</a></template></el-table-column>
      <el-table-column prop="userName" label="账号名称" min-width="120" align="center" show-overflow-tooltip />
      <el-table-column label="用户等级" min-width="110" align="center"><template #default="{row}">{{tierLabels[row.accountTier]}}</template></el-table-column>
      <el-table-column prop="deptName" label="所属部门" min-width="140" align="center" show-overflow-tooltip/><el-table-column label="岗位" min-width="140" align="center"><template #default="{row}">{{row.postNames?.join("、")||"—"}}</template></el-table-column>
      <el-table-column prop="phonenumber" label="手机号码" min-width="120" align="center" />
      <el-table-column label="状态" width="90" align="center"><template #default="{row}"><el-switch :model-value="row.status" active-value="0" inactive-value="1" :disabled="row.userId===1 || row.userId===user.id" @change="value=>changeStatus(row,value)" /></template></el-table-column>
      <el-table-column label="密码状态" min-width="130" align="center"><template #default="{row}"><el-tag :type="row.mustChangePassword?'warning':'success'">{{row.mustChangePassword?'须修改初始密码':'已设置密码'}}</el-tag></template></el-table-column>
      <el-table-column label="游客有效期" min-width="190" align="center"><template #default="{row}"><template v-if="row.accountTier==='GUEST'">{{row.validFrom}}<br />至 {{row.validUntil}}</template><span v-else>—</span></template></el-table-column>
      <el-table-column prop="createTime" label="创建时间" min-width="170" align="center" />
      <el-table-column label="操作" width="160" fixed="right" align="center" class-name="small-padding fixed-width"><template #default="{row}">
        <el-tooltip content="修改" placement="top"><el-button link type="primary" icon="Edit" aria-label="修改" @click="open(row.userId)" /></el-tooltip>
        <el-tooltip content="删除" placement="top"><el-button link type="primary" icon="Delete" aria-label="删除" :disabled="row.userId===1 || row.userId===user.id" @click="remove(row)" /></el-tooltip>
        <el-tooltip content="重置密码" placement="top"><el-button link type="primary" icon="Key" aria-label="重置密码" :disabled="row.userId===user.id" @click="reset(row)" /></el-tooltip>
        <el-tooltip content="分配角色" placement="top"><el-button link type="primary" icon="CircleCheck" aria-label="分配角色" @click="open(row.userId)" /></el-tooltip>
      </template></el-table-column>
    </el-table>
    <pagination v-show="total>0" :total="total" v-model:page="query.pageNum" v-model:limit="query.pageSize" @pagination="load" />
  </div></div>
  <el-dialog v-model="visible" :title="form.userId?'编辑账号与权限':'新增账号'" width="800px" class="access-dialog" append-to-body :close-on-click-modal="false" destroy-on-close>
    <el-form ref="formRef" :model="form" :rules="rules" label-width="110px" class="account-edit-form">
      <el-form-item label="账号ID"><el-input :model-value="form.loginName || '保存后系统自动生成'" disabled /></el-form-item>
      <el-form-item v-if="form.userId" label="账号名称" prop="userName"><el-input v-model="form.userName" maxlength="20" :placeholder="accountHints.userName" /></el-form-item>
      <el-form-item label="用户等级"><el-select v-model="form.accountTier" :disabled="form.userId===1 || form.userId===user.id" @change="tierChanged"><el-option v-for="(name,key) in tierLabels" :key="key" :value="key" :label="name" /></el-select></el-form-item>
      <el-form-item v-if="form.accountTier!=='ADMIN'" label="功能角色" prop="roleIds"><el-select v-model="form.roleIds" multiple placeholder="选择一个或多个角色"><el-option v-for="r in selectableRoles" :key="r.roleId" :label="r.roleName" :value="r.roleId" /></el-select></el-form-item>
      <el-form-item label="所属部门" prop="deptId"><el-tree-select v-model="form.deptId" :data="assignmentDepartments" node-key="deptId" :props="{label:'deptName',children:'children'}" check-strictly clearable :render-after-expand="false"/></el-form-item>
      <el-form-item label="岗位" prop="postIds"><el-select v-model="form.postIds" multiple clearable><el-option v-for="p in options.posts" :key="p.postId" :value="p.postId" :label="p.postName" :disabled="p.status!=='0'"/></el-select></el-form-item>
      <el-form-item label="账号状态"><el-radio-group v-model="form.status" :disabled="form.userId===1 || form.userId===user.id"><el-radio value="0">正常</el-radio><el-radio value="1">停用</el-radio></el-radio-group></el-form-item>
      <template v-if="form.accountTier==='GUEST'"><el-divider>游客访问限制</el-divider><el-form-item label="开始时间" prop="validFrom"><el-date-picker v-model="form.validFrom" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="北京时间" /></el-form-item><el-form-item label="结束时间" prop="validUntil"><el-date-picker v-model="form.validUntil" type="datetime" value-format="YYYY-MM-DDTHH:mm:ss" placeholder="北京时间" /></el-form-item><el-form-item label="可演示功能" prop="allowedModules"><el-checkbox-group v-model="form.allowedModules"><el-checkbox v-for="(name,key) in moduleLabels" :key="key" :value="key">{{name}}</el-checkbox></el-checkbox-group></el-form-item><p class="access-note">实际可访问功能取所选角色授权与此处勾选范围的交集。游客只能查看演示数据，不能操作真实井场。</p></template>
      <el-form-item v-if="form.accountTier==='ENGINEER'" label="授权井场"><el-select v-model="form.fieldIds" multiple placeholder="用于角色的“指定井场”数据范围"><el-option v-for="f in options.fields" :key="f.fieldId" :value="f.fieldId" :label="f.fieldName" /></el-select></el-form-item>
      <el-form-item label="邮箱"><el-input v-model="form.email" maxlength="50" /></el-form-item><el-form-item label="手机号"><el-input v-model="form.phonenumber" maxlength="11" /></el-form-item><el-form-item label="备注"><el-input v-model="form.remark" type="textarea" maxlength="500" /></el-form-item>
      <el-alert v-if="!form.userId" title="账号ID由系统随机生成，初始账号名称与ID一致；初始密码为123456，登录后必须修改。" type="info" :closable="false" />
    </el-form><template #footer><el-button type="primary" :loading="saving" @click="save">确 定</el-button><el-button @click="visible=false">取 消</el-button></template>
  </el-dialog>
</div></template>
<script setup>
import { reactive,ref,computed,onMounted } from 'vue'
import { ElMessage,ElMessageBox } from 'element-plus'
import TreePanel from '@/components/TreePanel/index.vue'
import './access.css'
import { accessOptions,listAccounts,getAccount,saveAccount,deleteAccount,resetAccountPassword } from '@/api/access'
import { treeRows } from '@/api/organization'
import useUserStore from '@/store/modules/user'
import { tierLabels,moduleLabels,accountRules,accountHints } from '@/security/access'
const showSearch=ref(true),selected=ref([]),tierTree=[{id:'',label:'全部用户',children:Object.entries(tierLabels).map(([id,label])=>({id,label}))}]
const user=useUserStore(),query=reactive({keyword:'',tier:'',status:'',deptId:undefined,pageNum:1,pageSize:20}),rows=ref([]),total=ref(0),loading=ref(false),saving=ref(false),visible=ref(false),formRef=ref(),form=reactive({}),options=reactive({roles:[],fields:[],departments:[],posts:[]})
const rules={userName:[{pattern:accountRules.userName,required:true,message:accountHints.userName,trigger:'blur'}],roleIds:[{type:'array',required:true,min:1,message:'请分配功能角色',trigger:'change'}],validFrom:[{required:true,message:'请选择开始时间',trigger:'change'}],validUntil:[{validator:(_,v,cb)=>cb(!v || new Date(v)<=new Date(form.validFrom)?new Error('结束时间须晚于开始时间'):undefined),trigger:'change'}],allowedModules:[{type:'array',required:true,min:1,message:'请至少选择一个演示功能',trigger:'change'}]}
const selectableRoles=computed(()=>options.roles.filter(r=>r.accountTier===form.accountTier && r.status==='0'))
async function load(){loading.value=true;try{const r=await listAccounts(query);rows.value=r.rows;total.value=r.total}finally{loading.value=false}}
const departmentTree=computed(()=>[{id:undefined,label:'全部部门',children:treeRows(options.departments)}])
const assignmentDepartments=computed(()=>treeRows(options.departments).map(disableDept))
function disableDept(n,blocked=false){const disabled=blocked||n.status!=='0';return {...n,disabled,children:n.children.map(c=>disableDept(c,disabled))}}
function selectDepartment(node){query.deptId=node.deptId;search()}
function resetQuery(){query.keyword='';query.tier='';query.status='';query.deptId=undefined;search()}
async function changeStatus(row,status){try{await ElMessageBox.confirm(`确认${status==='0'?'启用':'停用'}账号 ${row.loginName}？`,'提示',{type:'warning'});const data=(await getAccount(row.userId)).data;await saveAccount({...data,status});await load()}catch(e){if(e!=='cancel'&&e!=='close')console.error(e)}}
async function removeSelected(){try{await ElMessageBox.confirm(`确认删除选中的${selected.value.length}个账号？`,'提示',{type:'warning'});for(const row of selected.value)await deleteAccount(row.userId);await load()}catch(e){if(e!=='cancel'&&e!=='close')console.error(e)}}
function search(){query.pageNum=1;load()}
function tierChanged(){if(form.accountTier==='GUEST'){const beijing=d=>new Intl.DateTimeFormat('sv-SE',{timeZone:'Asia/Shanghai',year:'numeric',month:'2-digit',day:'2-digit',hour:'2-digit',minute:'2-digit',second:'2-digit',hour12:false}).format(d).replace(' ','T');form.validFrom=beijing(new Date());form.validUntil=beijing(new Date(Date.now()+7*24*3600000))}form.roleIds=selectableRoles.value.filter(r=>r.builtin).map(r=>r.roleId);form.allowedModules=['realtime'];form.fieldIds=[]}
async function open(id){Object.assign(options,(await accessOptions()).data);Object.keys(form).forEach(k=>delete form[k]);Object.assign(form,{loginName:'',userName:'',accountTier:'ENGINEER',roleIds:[],deptId:null,postIds:[],status:'0',validFrom:'',validUntil:'',allowedModules:['realtime'],fieldIds:[],email:'',phonenumber:'',remark:''});if(id)Object.assign(form,(await getAccount(id)).data);else tierChanged();visible.value=true}
async function save(){if(!await formRef.value.validate().catch(()=>false))return;saving.value=true;try{const result=await saveAccount(form);visible.value=false;await load();if(result.data?.accountId)await ElMessageBox.alert(`账号ID：${result.data.accountId}；初始账号名称与ID一致。初始密码：123456。用户首次登录后必须修改密码。`,'账号创建成功',{confirmButtonText:'确定'});else ElMessage.success('账号及权限已保存')}catch{ /* API interceptor displays the validation error; keep the form open. */ }finally{saving.value=false}}
async function reset(row){try{await ElMessageBox.confirm(`将账号 ${row.loginName} 的密码重置为123456？现有登录会话将失效，重新登录后必须修改密码。`,'重置密码',{type:'warning'});await resetAccountPassword(row.userId);ElMessage.success('密码已重置为123456');await load()}catch(e){if(e!=='cancel'&&e!=='close')console.error(e)}}
async function remove(row){try{await ElMessageBox.confirm(`删除账号 ${row.loginName}？`,'删除账号',{type:'warning'});await deleteAccount(row.userId);ElMessage.success('账号已删除');await load()}catch(e){if(e!=='cancel'&&e!=='close')console.error(e)}}
onMounted(async()=>{Object.assign(options,(await accessOptions()).data);await load()})
</script>
