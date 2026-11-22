<template><OperationsFrame title="数据权限"><el-tabs v-model="tab"><el-tab-pane label="角色工程授权" name="roles"><el-form inline v-show="showSearch"><el-form-item label="角色名称"><el-input v-model="keyword" placeholder="请输入角色名称或权限标识" clearable/></el-form-item><el-form-item><el-button type="primary" icon="Search" @click="load">搜索</el-button><el-button icon="Refresh" @click="keyword='';load()">重置</el-button></el-form-item></el-form><el-row class="mb8"><right-toolbar v-model:showSearch="showSearch" @queryTable="load"/></el-row><el-table :data="filteredRoles" v-loading="loading"><el-table-column prop="roleName" label="角色名称" min-width="150" show-overflow-tooltip/><el-table-column prop="roleKey" label="权限标识" min-width="150"/><el-table-column label="数据范围" min-width="160"><template #default="{row}">{{row.accountTier==='ADMIN'?'全部数据':row.accountTier==='GUEST'?'仅演示数据':(scopeNames.find(x=>x.dictValue===row.dataScope)?.dictLabel || scopeLabels[row.dataScope])}}</template></el-table-column><el-table-column label="授权范围" min-width="210"><template #default="{row}">
  <details v-if="row.accountTier==='ENGINEER' && row.dataScope==='6' && row.nodeKeys?.length">
    <summary>查看 {{row.nodeKeys.length}} 个授权节点</summary>
    <ul><li v-for="key in row.nodeKeys" :key="key">{{nodePaths.get(key) || '已失效的工程节点'}}</li></ul>
  </details>
  <span v-else>{{scopeDescription(row)}}</span>
</template></el-table-column><el-table-column label="成员" min-width="140"><template #default="{row}">{{row.members?.join("、") || "—"}}</template></el-table-column><el-table-column label="操作" width="140"><template #default="{row}"><el-button link type="primary" icon="Edit" :disabled="row.accountTier!=='ENGINEER'" @click="openRole(row)">设置工程范围</el-button></template></el-table-column></el-table></el-tab-pane><el-tab-pane label="用户实际范围" name="users" lazy><UserScopePage/></el-tab-pane><el-tab-pane label="井场部门与负责人" name="fields"><el-form inline v-show="showSearch"><el-form-item label="井场名称"><el-input v-model="keyword" placeholder="请输入井场名称" clearable/></el-form-item><el-form-item><el-button type="primary" icon="Search" @click="load">搜索</el-button><el-button icon="Refresh" @click="keyword='';load()">重置</el-button></el-form-item></el-form><el-row class="mb8"><right-toolbar v-model:showSearch="showSearch" @queryTable="load"/></el-row><el-table :data="filteredFields" v-loading="loading"><el-table-column prop="fieldName" label="井场名称" min-width="180" show-overflow-tooltip/><el-table-column label="所属部门" min-width="140"><template #default="{row}">{{row.deptName||'未分配'}}</template></el-table-column><el-table-column label="负责人" min-width="140"><template #default="{row}">{{row.ownerName||'未分配'}}</template></el-table-column><el-table-column label="操作" width="120"><template #default="{row}"><el-button link type="primary" icon="Edit" @click="openField(row)">修改归属</el-button></template></el-table-column></el-table></el-tab-pane></el-tabs>
 <el-dialog v-model="visible" :title="mode==='role'?'角色工程范围':'井场归属'" width="600px" append-to-body class="access-dialog" :close-on-click-modal="false"><el-form :model="form" label-width="120px"><template v-if="mode==='role'"><el-form-item label="角色名称">{{form.roleName}}</el-form-item><el-form-item label="数据范围" prop="dataScope"><el-select v-model="form.dataScope"><el-option v-for="(label,key) in scopeLabels" :key="key" :label="label" :value="key"/></el-select></el-form-item><el-form-item v-if="form.dataScope==='6'" label="授权工程范围"><ScopePicker v-model="form.nodeKeys"/></el-form-item><el-form-item v-if="form.dataScope==='2'" label="自定义部门"><el-tree ref="deptRef" :data="deptTree" show-checkbox check-strictly :default-checked-keys="form.deptIds" node-key="deptId" default-expand-all :props="{label:'deptName',children:'children'}"/></el-form-item></template><template v-else><el-form-item label="井场名称">{{form.fieldName}}</el-form-item><el-form-item label="所属部门" prop="deptId"><el-tree-select v-model="form.deptId" :data="deptTree" node-key="deptId" :props="{label:'deptName',children:'children'}" check-strictly clearable :render-after-expand="false"/></el-form-item><el-form-item label="负责人" prop="ownerUserId"><el-select v-model="form.ownerUserId" clearable filterable><el-option v-for="u in owners" :key="u.userId" :value="u.userId" :label="`${u.userName}（${u.loginName}）`"/></el-select></el-form-item></template></el-form><template #footer><el-button type="primary" :loading="saving" @click="save">确 定</el-button><el-button @click="visible=false">取 消</el-button></template></el-dialog>
</OperationsFrame></template>
<script setup>
import {useDisplayDictionary} from '@/views/operations/useDisplayDictionary'
const scopeNames=useDisplayDictionary('scope')
import {ref,reactive,computed,nextTick,onMounted} from 'vue'
import {ElMessage} from 'element-plus'
import ScopePicker from './ScopePicker.vue'
import OperationsFrame from '@/views/operations/OperationsFrame.vue'
import request from '@/utils/request'
import UserScopePage from './UserScopePage.vue'
import {listRoles,saveRoleScope} from '@/api/access'
import {departments,fieldOrganizations,fieldOwners,saveFieldOrganization,treeRows,scopeLabels} from '@/api/organization'
const keyword=ref(''),showSearch=ref(true),tab=ref('roles'),roles=ref([]),fields=ref([]),depts=ref([]),owners=ref([]),loading=ref(false),saving=ref(false),visible=ref(false),mode=ref('role'),form=reactive({}),deptRef=ref()
const hierarchy=ref([])
const nodePaths=computed(()=>{
  const paths=new Map()
  for(const row of hierarchy.value){
    const names=[]
    for(const type of ['block','field','well','bore']){
      if(row[type+'Id']==null)break
      names.push(row[type+'Name'])
      paths.set(type+':'+row[type+'Id'],names.join(' / '))
    }
  }
  return paths
})
function scopeDescription(row){
  if(row.accountTier==='ADMIN')return '所有启用井场'
  if(row.accountTier==='GUEST')return '演示数据'
  if(row.dataScope==='2')return depts.value.filter(d=>row.deptIds?.includes(d.deptId)).map(d=>d.deptName).join('、')
  return {'1':'所有启用井场','3':'用户所在部门的井场','4':'用户所在部门及下级部门的井场','5':'用户负责或创建的井场','6':'未指定角色节点'}[row.dataScope]
}
const filteredRoles=computed(()=>roles.value.filter(r=>!keyword.value||`${r.roleName} ${r.roleKey}`.includes(keyword.value)))
const filteredFields=computed(()=>fields.value.filter(p=>!keyword.value||p.fieldName.includes(keyword.value)))
const deptTree=computed(()=>treeRows(depts.value).map(disable))
function disable(n,blocked=false){const disabled=blocked||n.status!=='0';return {...n,disabled,children:n.children.map(x=>disable(x,disabled))}}
async function load(){loading.value=true;try{const r=await Promise.all([listRoles(),departments(),fieldOrganizations(),fieldOwners(),request({url:'/api/access/hierarchy'})]);hierarchy.value=r[4].data;roles.value=r[0].data;depts.value=r[1].data;fields.value=r[2].data;owners.value=r[3].data}finally{loading.value=false}}
async function openRole(row){mode.value='role';Object.keys(form).forEach(k=>delete form[k]);Object.assign(form,JSON.parse(JSON.stringify(row)));visible.value=true;await nextTick();deptRef.value?.setCheckedKeys(form.deptIds||[])}
function openField(row){mode.value='field';Object.keys(form).forEach(k=>delete form[k]);Object.assign(form,{...row});visible.value=true}
async function save(){saving.value=true;try{if(mode.value==='role'){if(form.dataScope==='2')form.deptIds=deptRef.value?.getCheckedKeys()||[];await saveRoleScope(form.roleId,{dataScope:form.dataScope,nodeKeys:form.nodeKeys||[],deptIds:form.deptIds||[]})}else await saveFieldOrganization(form);visible.value=false;ElMessage.success('数据权限配置已保存');await load()}catch(e){console.error(e)}finally{saving.value=false}}
onMounted(load)
</script>
