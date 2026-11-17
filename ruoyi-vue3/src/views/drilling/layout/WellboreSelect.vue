<template>
  <div class="wellbore-select">
    <div v-if="multiple" class="bulk-actions"><el-button link type="primary" @click="setAll(true)">全选</el-button><el-button link @click="setAll(false)">清空</el-button></div>
    <el-tree-select :model-value="modelValue" :data="options" :multiple="multiple" :show-checkbox="multiple" :check-strictly="false" :check-on-click-node="multiple" node-key="value" filterable clearable :render-after-expand="false" :collapse-tags="multiple" :collapse-tags-tooltip="multiple" :placeholder="placeholder" style="width:100%" @update:model-value="update" />
    <small v-if="!multiple && selectedPath" class="selected-path">{{selectedPath}}</small>
  </div>
</template>
<script setup>
import { computed } from 'vue'
import { wellboreTree, wellboreName } from '@/utils/wellboreHierarchy'
const props = defineProps({ modelValue: [Number, String, Array], rows: { type: Array, default: () => [] }, multiple: Boolean, placeholder: { type: String, default: '区块 / 井场 / 井 / 井眼' } })
const emit = defineEmits(['update:modelValue', 'change'])
const selectedPath = computed(() => { const row=props.rows.find(row=>Number(row.wellbore_id)===Number(props.modelValue));return row ? [row.block_name,row.field_name,row.well_name||row.well_code,wellboreName(row)].filter(Boolean).join(' / ') : '' })
const options = computed(() => wellboreTree(props.rows))
function update(value) {
  // Parent keys are navigation/selection controls, never calculation inputs.
  const ids = new Set(props.rows.map(row => Number(row.wellbore_id)))
  const result = props.multiple ? (value || []).filter(id => ids.has(Number(id))).map(Number) : value === undefined || value === null || value === '' ? null : Number(value)
  emit('update:modelValue', result)
  emit('change', result)
}
function setAll(yes) { update(yes ? [...new Set(props.rows.map(row => Number(row.wellbore_id)))] : []) }
</script>
<style scoped>.wellbore-select{width:100%;min-width:0}.selected-path{display:block;line-height:1.6;margin-top:5px;color:var(--el-text-color-regular);overflow-wrap:anywhere}.bulk-actions{display:flex;gap:12px;margin-bottom:6px}</style>
