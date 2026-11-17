<template>
  <el-table v-if="matrix" :data="matrixRows" border size="small" class="array-table">
    <el-table-column prop="axis" label="方向" width="95" />
    <el-table-column v-for="(axis,c) in axes" :key="axis" :label="axis" min-width="135">
      <template #default="{row}"><el-input-number :model-value="row.values[c]" :controls="false" @update:model-value="setMatrix(row.index,c,$event)" /></template>
    </el-table-column>
  </el-table>
  <div v-else class="vector-fields">
    <label v-for="(value,i) in modelValue" :key="i"><span>{{axes[i] || `第 ${i+1} 项`}}</span><el-input-number :model-value="value" :controls="false" @update:model-value="setVector(i,$event)" /></label>
  </div>
</template>
<script setup>
import {computed} from 'vue'
const props=defineProps({modelValue:{type:Array,required:true},symmetric:Boolean,axisLabels:{type:Array,default:()=>['北向 N','东向 E','垂向 TVD']}})
const emit=defineEmits(['update:modelValue'])
const axes=computed(()=>props.axisLabels)
const matrix=computed(()=>Array.isArray(props.modelValue[0]))
const matrixRows=computed(()=>props.modelValue.map((values,index)=>({values,index,axis:axes.value[index]||String(index+1)})))
function setVector(index,value){const next=[...props.modelValue];next[index]=value;emit('update:modelValue',next)}
function setMatrix(row,column,value){const next=props.modelValue.map(r=>[...r]);next[row][column]=value;if(props.symmetric&&next[column])next[column][row]=value;emit('update:modelValue',next)}
</script>
<style scoped>.array-table{width:100%}.el-input-number{width:100%}.vector-fields{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px;width:100%}.vector-fields label{display:grid;gap:6px}.vector-fields span{font-size:12px;color:var(--el-text-color-regular)}@media(max-width:700px){.vector-fields{grid-template-columns:1fr}}</style>
