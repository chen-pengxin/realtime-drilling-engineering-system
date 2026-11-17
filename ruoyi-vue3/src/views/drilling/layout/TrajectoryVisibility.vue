<template>
  <div class="trajectory-visibility">
    <div><el-button link type="primary" @click="all(true)">全部显示</el-button><el-button link @click="all(false)">全部隐藏</el-button></div>
    <el-tree ref="tree" :data="nodes" node-key="value" :props="{label:'label',children:'children'}" show-checkbox default-expand-all @check="change" />
  </div>
</template>
<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { wellboreTree } from '@/utils/wellboreHierarchy'
const props=defineProps({trajectories:{type:Array,default:()=>[]},modelValue:{type:Array,default:()=>[]}})
const emit=defineEmits(['update:modelValue']),tree=ref()
const nodes=computed(()=>wellboreTree(props.trajectories,{trajectories:true}))
const ids=computed(()=>props.trajectories.map(t=>String(t.id)))
watch(()=>[nodes.value,props.modelValue],()=>nextTick(()=>tree.value?.setCheckedKeys(ids.value.filter(id=>!props.modelValue.includes(id)))),{immediate:true,deep:true})
function change(_,state){const selected=new Set(state.checkedKeys);emit('update:modelValue',ids.value.filter(id=>!selected.has(id)))}
function all(show){emit('update:modelValue',show?[]:[...ids.value])}
</script>
<style scoped>.trajectory-visibility :deep(.el-tree-node__label){white-space:normal;line-height:1.5}.trajectory-visibility :deep(.el-tree-node__content){height:auto;min-height:28px;align-items:flex-start}.trajectory-visibility{overflow:auto;max-height:440px}</style>
