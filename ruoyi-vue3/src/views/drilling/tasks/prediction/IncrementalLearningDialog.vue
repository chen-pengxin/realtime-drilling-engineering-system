<template>
  <ResizableDialog v-model="visible" title="自动增量更新设置" width="720px" class="pose-dialog" append-to-body destroy-on-close>
    <el-alert v-if="error" :title="error" type="error" :closable="false" class="pose-error"/>

    <el-form label-position="top" :model="form">
      <div class="pose-field-grid">
        <el-form-item label="启用自动增量更新"><el-switch v-model="form.enabled"/></el-form-item>
        <el-form-item label="更新完成后启用新版本"><el-switch v-model="form.autoActivate"/></el-form-item>
        <el-form-item label="实测数据来源井眼"><WellboreSelect v-model="form.wellboreId" :rows="bores" @change="form.wellId=bores.find(b=>Number(b.wellbore_id)===Number($event))?.well_id" /></el-form-item>
        <el-form-item label="增量更新适配器"><el-select v-model="form.templateId" placeholder="支持继续训练的模型脚本"><el-option v-for="item in templates" :key="item.template_id" :value="item.template_id" :label="item.template_name"/></el-select></el-form-item>
        <el-form-item label="触发阈值：新增实测点数"><el-input-number v-model="form.minimumNewSamples" :min="1" :max="10000" :precision="0"/></el-form-item>
        <el-form-item label="更新间隔（秒）"><el-input-number v-model="form.intervalSeconds" :min="10" :max="86400" :precision="0"/></el-form-item>
        <el-form-item label="实测窗口长度（点）"><el-input-number v-model="form.windowSize" :min="2" :max="10000" :precision="0"/></el-form-item>
        <el-form-item label="每次更新轮数（Epoch）"><el-input-number v-model="form.epochs" :min="1" :max="1000" :precision="0"/></el-form-item>
        <el-form-item label="学习率"><el-input-number v-model="form.learningRate" :min="0.00000001" :max="1" :step="0.00001" :precision="8"/></el-form-item>
        <el-form-item label="批大小（Batch size）"><el-input-number v-model="form.batchSize" :min="1" :max="4096" :precision="0"/></el-form-item>
        <el-form-item label="验证集比例"><el-input-number v-model="form.validationRatio" :min="0" :max="0.5" :step="0.05" :precision="2"/></el-form-item>
        <el-form-item label="历史样本回放比例"><el-input-number v-model="form.replayRatio" :min="0" :max="0.9" :step="0.05" :precision="2"/></el-form-item>
        <el-form-item label="更新设备"><el-select v-model="form.device"><el-option label="自动选择" value="auto"/><el-option label="CPU" value="cpu"/><el-option label="GPU 0" value="cuda:0"/></el-select></el-form-item>
      </div>
    </el-form>
    <template #footer><el-button @click="visible=false">取消</el-button><el-button type="primary" :loading="saving" @click="save">确 定</el-button></template>
  </ResizableDialog>
</template>
<script setup>
import ResizableDialog from '@/components/ResizableDialog.vue'
import WellboreSelect from '@/views/drilling/layout/WellboreSelect.vue'
import { reactive, ref, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { saveIncrementalLearning } from './api'
import { listWellbores } from '@/api/drillingRegistry'
const props=defineProps({modelValue:Boolean,fieldId:[Number,String],policy:Object,wells:Array,templates:Array})
const emit=defineEmits(['update:modelValue','saved'])
const visible=ref(false),saving=ref(false),error=ref(''),bores=ref([])
const defaults=()=>({enabled:false,autoActivate:true,wellId:null,wellboreId:null,templateId:null,minimumNewSamples:10,intervalSeconds:300,windowSize:256,epochs:3,learningRate:0.0001,batchSize:16,validationRatio:0.2,replayRatio:0.25,device:'auto'})
const form=reactive(defaults())
watch(()=>props.modelValue,async value=>{visible.value=value;if(value){error.value='';Object.assign(form,defaults(),props.policy||{});try{bores.value=await listWellbores(props.fieldId);if(!form.wellboreId){const matches=bores.value.filter(b=>b.well_id===form.wellId);if(matches.length===1)form.wellboreId=matches[0].wellbore_id}}catch(e){error.value=e.message||'井眼列表读取失败'}}})
watch(visible,value=>emit('update:modelValue',value))
async function save(){if(form.enabled&&(!form.wellboreId||!form.templateId)){error.value='请选择实测数据来源井眼与增量更新适配器';return}if(form.minimumNewSamples>form.windowSize){error.value='新增点数阈值不能大于窗口长度';return}saving.value=true;error.value='';try{const result=await saveIncrementalLearning(props.fieldId,{...form});emit('saved',result);visible.value=false;ElMessage.success('增量更新策略已保存')}catch(e){error.value=e.message||'策略保存失败'}finally{saving.value=false}}
</script>
