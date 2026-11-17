<template>
  <div class="covariance-table">
    <el-table :data="rows" border size="small" max-height="360">
      <el-table-column label="井深（m）" min-width="130"><template #default="{row}"><el-input-number :model-value="row.md" :min="0" :controls="false" @update:model-value="setDepth(row.index,$event)" /></template></el-table-column>
      <el-table-column v-for="entry in components" :key="entry.label" :label="entry.label+'（m²）'" min-width="135"><template #default="{row}"><el-input-number :model-value="row.matrix?.[entry.r]?.[entry.c]" :controls="false" @update:model-value="setComponent(row.index,entry.r,entry.c,$event)" /></template></el-table-column>
      <el-table-column label="操作" width="75" fixed="right"><template #default="{row}"><el-button link type="danger" :disabled="rows.length<=1" @click="remove(row.index)">删除</el-button></template></el-table-column>
    </el-table>
    <el-button plain icon="Plus" @click="add">添加协方差控制点</el-button>
    <p>协方差方向为北、东、垂向。井深须递增，矩阵须对称半正定；计算井段必须位于控制点井深范围内。</p>
  </div>
</template>
<script setup>
import {computed} from 'vue'
const props=defineProps({modelValue:{type:Object,required:true}}),emit=defineEmits(['update:modelValue'])
const components=[{label:'北—北',r:0,c:0},{label:'东—东',r:1,c:1},{label:'垂—垂',r:2,c:2},{label:'北—东',r:0,c:1},{label:'北—垂',r:0,c:2},{label:'东—垂',r:1,c:2}]
const rows=computed(()=>(props.modelValue.md_m||[]).map((md,index)=>({md,index,matrix:props.modelValue.cov_nev_m2?.[index]})))
const copy=()=>JSON.parse(JSON.stringify(props.modelValue))
function setDepth(index,value){const next=copy();next.md_m[index]=value;emit('update:modelValue',next)}
function setComponent(index,row,column,value){const next=copy();next.cov_nev_m2[index][row][column]=value;next.cov_nev_m2[index][column][row]=value;emit('update:modelValue',next)}
function add(){const next=copy();next.md_m=[...(next.md_m||[]),(next.md_m?.at(-1)||0)+100];next.cov_nev_m2=[...(next.cov_nev_m2||[]),JSON.parse(JSON.stringify(next.cov_nev_m2?.at(-1)||[[0,0,0],[0,0,0],[0,0,0]]))];emit('update:modelValue',next)}
function remove(index){const next=copy();next.md_m.splice(index,1);next.cov_nev_m2.splice(index,1);emit('update:modelValue',next)}
</script>
<style scoped>.covariance-table{width:100%;min-width:0}.el-input-number{width:100%}.el-button{margin-top:10px}p{font-size:12px;line-height:1.6;color:var(--el-text-color-secondary)}</style>
