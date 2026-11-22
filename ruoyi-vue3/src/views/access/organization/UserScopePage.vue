<template>
  <el-form inline @submit.prevent="search">
    <el-form-item label="用户"><el-input v-model="query.keyword" placeholder="账号 ID 或名称" clearable @clear="search"/></el-form-item>
    <el-button type="primary" native-type="submit">搜索</el-button>
  </el-form>
  <el-table :data="rows" v-loading="loading">
    <el-table-column prop="loginName" label="账号 ID"/><el-table-column prop="userName" label="账号名称"/>
    <el-table-column prop="deptName" label="部门"/>
    <el-table-column label="工程数据"><template #default="{row}">
      <el-button link type="primary" @click="inspect(row)">查看实际范围</el-button>
    </template></el-table-column>
  </el-table>
  <pagination v-show="total>0" :total="total" v-model:page="query.pageNum" v-model:limit="query.pageSize" @pagination="load"/>
  <el-dialog v-model="visible" :title="accountName+'的实际工程范围'" width="min(760px, 95vw)" append-to-body>
    <div v-loading="inspecting">
      <el-alert v-if="reason" :title="reason" :closable="false" type="info"/>
      <template v-else-if="!inspecting">
        <p>已合并该用户所有启用角色及个人井场授权。</p>
        <el-input v-model="filter" placeholder="搜索工程路径" clearable/>
        <el-table :data="filtered" max-height="390" empty-text="没有匹配的授权路径">
          <el-table-column v-for="(label,key) in levels" :key="key" :prop="key" :label="label"/>
        </el-table>
      </template>
    </div>
  </el-dialog>
</template>
<script setup>
import {ref,reactive,computed,onMounted} from 'vue'
import {listAccounts,getAccount} from '@/api/access'
import useSettingsStore from '@/store/modules/settings'
const query=reactive({keyword:'',pageNum:1,pageSize:Number(useSettingsStore().platform.pageSize)||20})
const rows=ref([]),total=ref(0),loading=ref(false),visible=ref(false),inspecting=ref(false)
const accountName=ref(''),scope=ref([]),filter=ref(''),reason=ref('')
const levels={blockName:'区块',fieldName:'井场',wellName:'井',boreName:'井眼'}
const filtered=computed(()=>scope.value.filter(row=>Object.keys(levels).map(key=>row[key]).join(' / ').toLowerCase().includes(filter.value.trim().toLowerCase())))
let listRequest=0,detailRequest=0
async function load(){
  const seq=++listRequest
  loading.value=true
  try{const data=await listAccounts({...query});if(seq===listRequest){rows.value=data.rows;total.value=data.total}}
  finally{if(seq===listRequest)loading.value=false}
}
function search(){query.pageNum=1;load()}
async function inspect(row){
  const seq=++detailRequest
  accountName.value=row.userName;scope.value=[];filter.value='';reason.value=''
  visible.value=true;inspecting.value=true
  try{
    const {data}=await getAccount(row.userId)
    if(seq!==detailRequest)return
    scope.value=data.actualScope
    reason.value=data.scopeUnavailable || (data.accountTier==='GUEST'?'游客仅可访问已授权的演示数据':'')
  }catch{if(seq===detailRequest)reason.value='读取失败，请关闭后重试'}
  finally{if(seq===detailRequest)inspecting.value=false}
}
onMounted(load)
</script>
