<template>
  <el-dialog v-model="open" title="修订测量参数" width="min(980px, calc(100vw - 24px))" append-to-body :close-on-click-modal="false" :before-close="close">
    <el-form label-width="140px" :disabled="saving">
      <el-row :gutter="20"><el-col :span="12"><el-form-item label="测斜工具" required><el-input v-model="tool.name" maxlength="120" /></el-form-item></el-col><el-col :span="12"><el-form-item label="工具型号 / 代码"><el-input v-model="tool.code" maxlength="120" /></el-form-item></el-col><el-col :span="12"><el-form-item label="仪器编号"><el-input v-model="tool.serial_number" maxlength="120" /></el-form-item></el-col><el-col :span="12"><el-form-item label="标定日期"><el-input v-model="tool.calibration_date" maxlength="120" /></el-form-item></el-col></el-row>
      <el-form-item label="轨道不确定性模型" required><el-select :model-value="profile.model_version" @change="selectModel"><el-option v-for="model in catalog.models" :key="model.name" :value="model.name" :label="errorModelName(model.settings.uncertainty_model?.type||model.name)" /></el-select></el-form-item>
      <ParameterFields v-model="profile.settings" />
      <el-form-item label="修订原因" required><el-input v-model="note" type="textarea" maxlength="500" show-word-limit /></el-form-item>
      <el-form-item label="启用新版本"><el-switch v-model="activate" /></el-form-item>
      <el-checkbox v-model="checked">已核对本版本全井段的测斜工具及误差参数</el-checkbox>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" style="margin-top:12px" />
    <template #footer><el-button :disabled="saving" @click="open=false">取消</el-button><el-button type="primary" :disabled="!checked" :loading="saving" @click="save">保存为新版本</el-button></template>
  </el-dialog>
</template>
<script setup>
import {ref,watch} from 'vue'
import {ElMessage} from 'element-plus'
import {registry,engineCatalog} from '@/api/drillingRegistry'
import {errorModelName} from '@/utils/engineeringErrorModels'
import ParameterFields from '../../layout/ParameterFields.vue'
const props=defineProps({fieldId:Number,currentVersionId:Number}),emit=defineEmits(['saved'])
const open=ref(false),saving=ref(false),checked=ref(false),note=ref(''),error=ref(''),activate=ref(true),tool=ref({}),profile=ref({settings:{}}),catalog=ref({models:[]})
let version
const copy=value=>JSON.parse(JSON.stringify(value))
async function show(detail){version=detail.survey_version_id;tool.value=copy(detail.reference?.survey_tool||{});const effective=detail.effective_uncertainty;profile.value={model_version:effective.model_version,settings:copy(effective.settings),parameter_source:'MANUAL'};note.value='';error.value='';activate.value=true;checked.value=false;open.value=true;try{catalog.value=await engineCatalog()}catch(e){error.value=e.message}}
function selectModel(name){const model=catalog.value.models.find(m=>m.name===name);if(model)profile.value={model_version:name,settings:copy(model.settings),parameter_source:'MANUAL'}}
watch([tool,profile],()=>checked.value=false,{deep:true})
function close(done){if(!saving.value)done()}
async function save(){if(!checked.value||!tool.value.name?.trim()||!note.value.trim()){error.value='请填写工具名称、修订原因并确认参数';return}const field=props.fieldId,original=version;saving.value=true;error.value='';try{const result=await registry(field,`/versions/${original}/measurement-parameters`,'post',{surveyTool:tool.value,uncertainty:{...profile.value,confirmed:true},note:note.value,activate:activate.value,expectedCurrentVersionId:props.currentVersionId});open.value=false;ElMessage.success(result.duplicate?'参数未变化':'测量参数已保存为新版本');window.dispatchEvent(new CustomEvent('drilling-survey-version-changed',{detail:{fieldId:field}}));emit('saved',result)}catch(e){error.value=e.message}finally{saving.value=false}}
defineExpose({show})
</script>
