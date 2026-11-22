<template>
  <section class="appendix-table">
    <h3>{{ title }}</h3><p v-if="calculation" class="calculation">计算方法：最小曲率法</p>
    <el-table :data="pageRows" border :max-height="height" :empty-text="'暂无数据'">
      <el-table-column v-if="showIndex" type="index" label="序号" width="64" :index="i=>(page-1)*pageSize+i+1" />
      <template v-for="column in columns" :key="column.key||column.label">
        <el-table-column v-if="column.children" :label="column.label" align="center">
          <el-table-column v-for="child in column.children" :key="child.key" :label="child.label" :width="child.width" align="center"><template #default="s">{{ format(s.row[child.key]) }}</template></el-table-column>
        </el-table-column>
        <el-table-column v-else :label="column.label" :width="column.width" align="center"><template #default="s">{{ format(s.row[column.key]) }}</template></el-table-column>
      </template>
      <el-table-column v-if="actions" label="操作" width="120" align="center"><template #default="s"><el-button link type="primary" :disabled="!editable(s.row)" @click="$emit('edit',s.row,(page-1)*pageSize+s.$index)">修改</el-button><el-button link type="danger" :disabled="!editable(s.row)" @click="$emit('delete',s.row,(page-1)*pageSize+s.$index)">删除</el-button></template></el-table-column>
    </el-table>
    <div class="appendix-notes"><p v-for="note in notes" :key="note">{{ note }}</p></div>
    <pagination v-if="rows.length>20" v-model:page="page" v-model:limit="pageSize" :total="rows.length" :page-sizes="[20,50,100]" />
  </section>
</template>
<script setup>
import {computed,ref,watch} from 'vue'
const props=defineProps({rows:{type:Array,default:()=>[]},columns:Array,title:String,notes:{type:Array,default:()=>[]},calculation:Boolean,showIndex:{type:Boolean,default:true},actions:Boolean,editable:{type:Function,default:()=>true},height:{type:Number,default:490}})
defineEmits(['edit','delete']);const page=ref(1),pageSize=ref(20)
const pageRows=computed(()=>props.rows.slice((page.value-1)*pageSize.value,page.value*pageSize.value))
watch(()=>props.rows,()=>{page.value=1})
function format(v){if(v==null||v==='')return '—';if(typeof v==='number')return Number.isFinite(v)?v.toFixed(3):'—';return ({GRID_NORTH:'网格北',TRUE_NORTH:'真北',MAGNETIC_NORTH:'磁北'}[v]||v)}
</script>
<style scoped>
.appendix-table{min-width:0}.appendix-table h3{text-align:center;font-size:17px;font-weight:600;margin:12px 0 16px;color:var(--el-text-color-primary)}.calculation{text-align:right;font-size:13px;margin:0 12px 8px}.appendix-table :deep(.el-table th .cell){white-space:pre-line;line-height:1.6;padding:6px 5px}.appendix-table :deep(.el-table td .cell){font-variant-numeric:tabular-nums;white-space:pre-wrap;line-height:1.6}.appendix-table :deep(.el-table th){background:var(--el-fill-color-light);font-weight:600;color:var(--el-text-color-primary)}.appendix-notes{border:1px solid var(--el-border-color-lighter);border-top:0;padding:0 12px;text-align:left;font-size:12px;line-height:1.8}.appendix-notes p{margin:6px 0}
</style>
