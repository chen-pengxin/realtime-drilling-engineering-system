<template><el-card v-if="bores.length"><template #header>按井或井眼授权的轨迹资料</template>
  <el-select v-model="bore" filterable placeholder="选择授权井眼" style="width:min(100%,450px)" @change="loadVersions">
    <el-option v-for="b in bores" :key="b.boreId" :value="b.boreId" :label="[b.blockName,b.fieldName,b.wellName,b.boreName].join(' / ')"/>
  </el-select>
  <el-select v-if="bore" v-model="version" placeholder="选择轨迹版本" @change="show(1)">
    <el-option v-for="v in versions" :key="v.id" :value="v.id" :label="(v.kind==='ACTUAL'?'实钻':'设计')+' v'+v.version"/>
  </el-select>
  <template v-if="version"><el-table :data="rows"><el-table-column v-for="(label,key) in columns" :key="key" :label="label.replace('(m)','('+labelUnit('m')+')').replace('(°)','('+labelUnit('deg')+')')" :prop="key"/></el-table>
  <el-button :disabled="page===1" @click="show(page-1)">上一页</el-button><el-button :disabled="!more" @click="show(page+1)">下一页</el-button></template>
</el-card></template>
<script setup>
import {useDisplayDictionary} from '@/views/operations/useDisplayDictionary'
const units=useDisplayDictionary('unit')
const labelUnit=code=>units.value.find(x=>x.dictValue===code)?.dictLabel || code
import {ref,onMounted} from 'vue'
import request from '@/utils/request'
import useUserStore from '@/store/modules/user'
const base='/api/drilling/scoped-surveys', bores=ref([]),bore=ref(),versions=ref([]),version=ref(),rows=ref([]),more=ref(false),page=ref(1)
const columns=computed(()=>({md:'测深 (m)',inclination:'井斜 (°)',azimuth:'方位 (°)',north:'北坐标 (m)',east:'东坐标 (m)',tvd:'垂深 (m)'}))
onMounted(async()=>{if(useUserStore().permissions.includes('drilling:well:list')) bores.value=(await request({url:base})).data})
async function loadVersions(){const id=bore.value;version.value=null;versions.value=[];rows.value=[];const r=await request({url:base+'/'+id+'/versions'});if(id===bore.value)versions.value=r.data}
async function show(n){const id=version.value;const r=await request({url:base+'/'+bore.value+'/versions/'+id,params:{page:n}});if(id===version.value){rows.value=r.data.rows;more.value=r.data.more;page.value=n}}
</script>
