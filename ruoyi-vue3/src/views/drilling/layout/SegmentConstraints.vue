<template>
  <section class="constraint-editor">
    <el-radio-group :model-value="mode" size="small" class="constraint-switch" @update:model-value="$emit('update:mode',$event)"><el-radio-button value="GLOBAL">全局约束</el-radio-button><el-radio-button value="PER_SEGMENT">分段约束</el-radio-button></el-radio-group>
    <div v-if="mode==='GLOBAL'" class="bound-card" data-constraint-mode="GLOBAL">
      <div class="bound-title">段长（m）</div><div class="bound-pair"><el-form-item label="下限"><el-input-number :model-value="minLength" :min="0.1" controls-position="right" @update:model-value="$emit('update:minLength',$event)" /></el-form-item><el-form-item label="上限"><el-input-number :model-value="maxLength" :min="0.1" controls-position="right" @update:model-value="$emit('update:maxLength',$event)" /></el-form-item></div>
      <template v-if="types.includes('arc')"><div class="bound-title">圆弧段狗腿度（°/30 m）</div><div class="bound-pair"><el-form-item label="下限"><el-input-number :model-value="minDls" :min="0" :step="0.1" controls-position="right" @update:model-value="$emit('update:minDls',$event)" /></el-form-item><el-form-item label="上限"><el-input-number :model-value="maxDls" :min="0" :step="0.1" controls-position="right" @update:model-value="$emit('update:maxDls',$event)" /></el-form-item></div></template>
      <div v-if="types.includes('straight')" class="straight-dls">直线段狗腿度：0 °/30 m</div>
    </div>
    <template v-else><div v-for="(row,index) in rows" :key="index" class="bound-card" data-constraint-mode="PER_SEGMENT">
      <header>第 {{index+1}} 段 · {{row.type==='straight'?'直线':'圆弧'}}</header>
      <div class="bound-title">段长（m）</div><div class="bound-pair"><el-form-item label="下限"><el-input-number :model-value="row.minLength" :min="0.1" controls-position="right" @update:model-value="edit(index,'minLength',$event)" /></el-form-item><el-form-item label="上限"><el-input-number :model-value="row.maxLength" :min="0.1" controls-position="right" @update:model-value="edit(index,'maxLength',$event)" /></el-form-item></div>
      <div class="bound-title">狗腿度（°/30 m）</div><div class="bound-pair"><el-form-item label="下限"><el-input-number :model-value="row.minDls" :disabled="row.type==='straight'" :min="0" :step="0.1" controls-position="right" @update:model-value="edit(index,'minDls',$event)" /></el-form-item><el-form-item label="上限"><el-input-number :model-value="row.maxDls" :disabled="row.type==='straight'" :min="0" :step="0.1" controls-position="right" @update:model-value="edit(index,'maxDls',$event)" /></el-form-item></div>
    </div></template>
  </section>
</template>
<script setup>
import {computed,watch} from 'vue'
import {segmentRows,segmentTypes} from '@/utils/segmentConstraints'
const props=defineProps({mode:{type:String,default:'GLOBAL'},trajectoryMode:{type:String,default:'arc-straight'},count:{type:Number,default:3},modelValue:{type:Array,default:()=>[]},minLength:{type:Number,default:3},maxLength:{type:Number,default:30},minDls:{type:Number,default:0},maxDls:{type:Number,default:6}})
const emit=defineEmits(['update:mode','update:modelValue','update:minLength','update:maxLength','update:minDls','update:maxDls'])
const types=computed(()=>segmentTypes(props.trajectoryMode,props.count))
const rows=computed(()=>segmentRows(props.trajectoryMode,props.count,props,props.modelValue))
watch(()=>[props.mode,props.trajectoryMode,props.count],()=>{if(props.mode==='PER_SEGMENT')emit('update:modelValue',rows.value)},{immediate:true})
function edit(index,key,value){emit('update:modelValue',rows.value.map((row,i)=>i===index?{...row,[key]:value}:row))}
</script>
<style scoped>
.constraint-editor{display:grid;gap:12px;min-width:0;container-type:inline-size}.constraint-switch{justify-self:start;max-width:100%;flex-wrap:nowrap}.bound-card{min-width:0;padding:10px;border:1px solid var(--el-border-color-light);background:var(--el-bg-color)}.bound-card header{font-size:13px;font-weight:600;margin:0 0 12px}.bound-title{font-size:12px;font-weight:500;line-height:1.6;margin-bottom:5px;color:var(--el-text-color-regular)}.bound-pair{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.bound-pair :deep(.el-form-item){min-width:0;margin:0 0 10px}.bound-pair :deep(.el-form-item__label){font-size:12px;height:auto;line-height:1.5;margin-bottom:5px}.bound-pair :deep(.el-input-number){width:100%;min-width:0}.bound-pair :deep(.el-input__wrapper){padding-left:5px;padding-right:31px}.bound-pair :deep(.el-input__inner){text-align:left}.straight-dls{font-size:12px;line-height:1.6;color:var(--el-text-color-regular)}@container(max-width:220px){.bound-pair{grid-template-columns:1fr}}
</style>
