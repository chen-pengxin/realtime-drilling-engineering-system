<template>
  <div class="error-settings">
    <template v-if="kind==='iscwsa'">
      <el-form-item label="测斜工具误差代码">
        <el-select :model-value="modelValue.error_model" filterable :loading="loading" @change="changeTool"><el-option v-for="name in tools" :key="name" :label="name" :value="name" /></el-select>
      </el-form-item>
      <el-table v-loading="loading" :data="terms" border max-height="430" size="small" row-key="code" empty-text="暂无工具误差项">
        <el-table-column prop="code" label="误差项" min-width="140" />
        <el-table-column label="误差幅值" min-width="150"><template #default="{row}"><el-input-number :model-value="modelValue.tool_error_parameters?.[row.code]??row.value" :min="0" :step="step(row.value)" controls-position="right" @update:model-value="setTerm(row.code,$event)" /></template></el-table-column>
        <el-table-column prop="unit" label="单位" width="90" />
        <el-table-column label="传播类型" width="100"><template #default="{row}">{{propagation[row.propagation]||row.propagation}}</template></el-table-column>
      </el-table>
      <el-alert v-if="error" :title="error" type="error" :closable="false" />
      <ParameterFields :model-value="modelValue" :exclude="['uncertainty_model','error_model','tool_error_parameters']" @update:model-value="$emit('update:modelValue',$event)" />
    </template>
    <ParameterFields v-else :model-value="modelValue" :exclude="['type']" @update:model-value="$emit('update:modelValue',$event)" />
  </div>
</template>
<script setup>
import {computed,ref,watch} from 'vue'
import {engineToolParameters} from '@/api/drillingRegistry'
import ParameterFields from './ParameterFields.vue'
const props=defineProps({modelValue:{type:Object,required:true}}),emit=defineEmits(['update:modelValue'])
const kind=computed(()=>props.modelValue.uncertainty_model?.type),tools=ref([]),terms=ref([]),loading=ref(false),error=ref('')
const propagation={random:'随机',systematic:'系统',global:'全局',well:'井级',within_pad:'井场级'}
const step=v=>v===0?0.001:10**Math.floor(Math.log10(Math.abs(v)))
let serial=0
watch(()=>[kind.value,props.modelValue.error_model],async([type,name])=>{const request=++serial;terms.value=[];if(type!=='iscwsa')return;loading.value=true;error.value='';try{const data=await engineToolParameters(name||'ISCWSA MWD Rev5.11');if(request!==serial)return;tools.value=data.models;terms.value=data.terms}catch(e){if(request===serial)error.value=e.message||'工具误差参数加载失败'}finally{if(request===serial)loading.value=false}},{immediate:true})
function changeTool(name){emit('update:modelValue',{...props.modelValue,error_model:name,tool_error_parameters:{}})}
function setTerm(code,value){emit('update:modelValue',{...props.modelValue,tool_error_parameters:{...props.modelValue.tool_error_parameters,[code]:value}})}
</script>
<style scoped>.error-settings{min-width:0}.error-settings :deep(.el-select),.error-settings :deep(.el-input-number){width:100%}.error-settings :deep(.el-form-item__label){height:auto;line-height:1.5;white-space:normal}.error-settings :deep(.el-table){margin-bottom:16px}</style>
