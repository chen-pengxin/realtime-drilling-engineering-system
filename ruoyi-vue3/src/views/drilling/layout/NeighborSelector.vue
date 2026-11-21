<template>
  <div class="neighbor-picker">
    <div class="neighbor-summary"><span>已选 {{selectedRows.length}} / {{selectableRows.length}}</span><span>搜索范围 {{modelValue[0]?.manual_search_radius_m??3000}} m</span></div>
    <div class="drilling-toolbar"><el-button link type="primary" :disabled="!selectableRows.length" @click="all(true)">全选</el-button><el-button link :disabled="!selectedRows.length" @click="all(false)">清空</el-button></div>
    <el-tree v-if="treeData.length" ref="treeRef" :data="treeData" node-key="id" show-checkbox :default-checked-keys="selectedKeys" :props="treeProps" empty-text="当前范围没有可参与评价的邻井" @check="checkChange">
      <template #default="{data}"><span class="tree-label">{{data.label}}</span><span v-if="data.row" class="tree-meta"><span v-if="data.row.horizontal_box_distance_m!=null" class="neighbor-distance">{{Math.round(data.row.horizontal_box_distance_m)}} m</span><el-tag size="small" type="info">{{data.row.trajectory_kind==='DESIGN'?'设计':'实钻'}} v{{data.row.version_no||'—'}}</el-tag><el-tag v-if="!data.row.usable" size="small" type="info">{{data.row.coordinate_compatible===false?'坐标基准不一致':data.row.within_search_range===false?'超出范围':'版本不可用'}}</el-tag><el-tag v-else-if="data.row.selection_dirty" size="small" type="warning">未保存</el-tag><el-tag v-else-if="data.row.relationship_source==='AUTO'" size="small" type="success">自动</el-tag><el-button v-if="!data.row.usable&&data.row.requested_included" link type="danger" @click.stop="excludeUnavailable(data.row)">取消选择</el-button></span></template>
    </el-tree>
    <el-empty v-else :image-size="48" description="范围内无邻井" />
    <div v-if="selectedRows.length" class="selected-neighbors">
      <div class="selected-title">已选邻井</div>
      <div v-for="row in selectedRows" :key="row.wellbore_id" class="neighbor-row">
        <strong>{{pathLabel(row)}}</strong>
        <el-select v-model="row.version_policy" size="small" @change="policy(row)"><el-option v-if="Number(row.has_actual)" label="当前实钻版本" value="CURRENT_ACTUAL"/><el-option v-else label="当前设计版本（无实钻数据）" value="CURRENT_DESIGN"/><el-option label="固定历史版本" value="PINNED"/></el-select>
        <el-select v-if="row.version_policy==='PINNED'" v-model="row.survey_version_id" size="small" placeholder="选择固定版本" @change="pinned(row)"><el-option v-for="v in (choices[row.wellbore_id]||[]).filter(v=>!Number(row.has_actual)||v.trajectory_kind==='ACTUAL')" :key="v.survey_version_id" :value="v.survey_version_id" :label="`${v.trajectory_kind==='DESIGN'?'设计':'实钻'} v${v.version_no}`"/></el-select>
      </div>
    </div>
    <el-button icon="DocumentChecked" :disabled="!hasChanges" @click="$emit('save')">保存邻井关系</el-button>
  </div>
</template>
<script setup>
import {computed,nextTick,reactive,ref,watch} from 'vue'
import {boreVersions} from '@/api/drillingRegistry'
import {setNeighborIncluded} from '@/utils/neighborSelection'
import {engineeringName as cleanName} from '@/utils/wellboreHierarchy'
const props=defineProps({fieldId:Number,modelValue:{type:Array,default:()=>[]}}),emit=defineEmits(['change','save']),choices=reactive({}),treeRef=ref()
const treeProps={label:'label',children:'children',disabled:'disabled'}
const candidateRows=computed(()=>props.modelValue.filter(r=>r.survey_version_id))
const selectableRows=computed(()=>candidateRows.value.filter(r=>r.usable))
const selectedRows=computed(()=>selectableRows.value.filter(r=>r.included))
const selectedKeys=computed(()=>selectedRows.value.map(r=>String(r.wellbore_id)))
const hasChanges=computed(()=>props.modelValue.some(r=>r.selection_dirty))
const treeData=computed(()=>{
  const blocks=new Map()
  for(const row of candidateRows.value){
    const blockKey=String(row.block_id||row.block_name||'block'),fieldKey=String(row.field_id||row.field_name||'field'),wellKey=String(row.well_id)
    if(!blocks.has(blockKey))blocks.set(blockKey,{id:`block:${blockKey}`,label:cleanName(row.block_name)||'未注明区块',children:new Map()})
    const block=blocks.get(blockKey)
    if(!block.children.has(fieldKey))block.children.set(fieldKey,{id:`field:${fieldKey}`,label:cleanName(row.field_name)||'未注明井场',children:new Map()})
    const field=block.children.get(fieldKey)
    if(!field.children.has(wellKey))field.children.set(wellKey,{id:`well:${wellKey}`,label:cleanName(row.well_name)||`井 ${cleanName(row.well_code)||'未命名'}`,children:[]})
    field.children.get(wellKey).children.push({id:String(row.wellbore_id),label:wellboreName(row),disabled:!row.usable,row})
  }
  return [...blocks.values()].map(block=>({...block,children:[...block.children.values()].map(field=>({...field,children:[...field.children.values()]}))}))
})
function syncTree(){nextTick(()=>treeRef.value?.setCheckedKeys(selectedKeys.value))}
watch(selectedKeys,syncTree,{immediate:true})
function wellboreName(row){const bore=cleanName(row.wellbore_name||row.wellbore_code),well=cleanName(row.well_name||row.well_code);return !bore||bore===well?'主井眼':bore}
function pathLabel(row){return [cleanName(row.block_name)||'未注明区块',cleanName(row.field_name)||'未注明井场',cleanName(row.well_name)||`井 ${cleanName(row.well_code)||'未命名'}`,wellboreName(row)].filter((part,index,all)=>part&&part!==all[index-1]).join(' / ')}
function checkChange(data,state){const selected=new Set(state.checkedKeys.map(String));selectableRows.value.forEach(row=>setNeighborIncluded(row,selected.has(String(row.wellbore_id))));emit('change')}
function excludeUnavailable(row){setNeighborIncluded(row,false);syncTree();emit('change')}
function all(value){selectableRows.value.forEach(row=>setNeighborIncluded(row,value));syncTree();emit('change')}
async function policy(row){row.selection_dirty=true;const versions=await boreVersions(props.fieldId,row.wellbore_id,'');choices[row.wellbore_id]=versions;const kind=row.version_policy==='CURRENT_DESIGN'?'DESIGN':'ACTUAL',version=versions.find(v=>v.trajectory_kind===kind&&Number(v.is_active));if(row.version_policy!=='PINNED'){row.survey_version_id=version?.survey_version_id;row.quality_status=version?.quality_status;row.source_review_status=version?.source_review_status}row.usable=!!row.survey_version_id&&row.quality_status==='READY'&&row.source_review_status==='CONFIRMED';row.included=row.usable;syncTree();emit('change')}
function pinned(row){row.selection_dirty=true;const version=choices[row.wellbore_id]?.find(v=>v.survey_version_id===row.survey_version_id);row.quality_status=version?.quality_status;row.source_review_status=version?.source_review_status;row.usable=row.quality_status==='READY'&&row.source_review_status==='CONFIRMED';row.included=row.usable;syncTree();emit('change')}
</script>
<style scoped>
.neighbor-picker{display:grid;gap:8px}.neighbor-summary{display:flex;flex-wrap:wrap;justify-content:space-between;gap:6px;font-size:12px;color:var(--el-text-color-regular)}.neighbor-picker :deep(.el-tree){max-height:260px;overflow:auto;border:1px solid var(--el-border-color-lighter);padding:6px}.neighbor-picker :deep(.el-tree-node__content){height:auto;min-height:30px;flex-wrap:wrap;padding-block:3px}.tree-label{min-width:0;overflow-wrap:anywhere;font-size:13px}.tree-meta{display:flex;align-items:center;flex-wrap:wrap;gap:4px;margin-left:6px}.neighbor-distance{font-size:12px;color:var(--el-text-color-regular)}.selected-neighbors{display:grid;gap:8px}.selected-title{font-size:12px;font-weight:600;color:var(--el-text-color-primary)}.neighbor-row{display:grid;gap:6px;padding:8px 0;border-bottom:1px solid var(--el-border-color-lighter)}.neighbor-row strong{font-size:12px;font-weight:500;line-height:1.5;overflow-wrap:anywhere}
</style>
