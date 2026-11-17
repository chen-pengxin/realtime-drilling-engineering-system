<template>
  <section class="drilling-page-frame" :class="embedded?'drilling-embedded':'app-container'">
    <h1 class="sr-only">{{ title }}</h1>
    <el-form v-if="showField&&!embedded&&!basicOnly" inline class="drilling-field-search">
      <el-form-item label="工程井场"><el-cascader v-model="fieldPath" :options="fieldOptions" filterable :props="{emitPath:true}" placeholder="区块 / 井场" style="width:320px" /></el-form-item>
    </el-form>
    <!-- Engineering pages expose the block/field/well/wellbore hierarchy. -->
    <main class="drilling-content"><slot /></main>
  </section>
</template>
<script setup>
import { computed, inject, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import useUserStore from '@/store/modules/user'
import { moduleForPath } from '@/security/access'
import { useDrillingContext } from '../composables/useDrillingContext'
import './drilling.css'
const props=defineProps({title:{type:String,required:true},showField:{type:Boolean,default:true},basicContext:{type:Boolean,default:false}})
const embedded=inject('drillingEmbedded',false)
const route=useRoute(),user=useUserStore()
const basicOnly=computed(()=>props.basicContext||['alerts','versions','history','reports','connectors'].includes(moduleForPath(route.path,route.query)))
const canConfirmEngine=computed(()=>user.permissions.some(p=>['*:*:*','drilling:collision:run','drilling:plan:run','drilling:prediction:run'].includes(p)))
const {fields,fieldId,currentConfig,loading,loadFields,loadFieldContext,selectField}=useDrillingContext()
const fieldOptions=computed(()=>{
  const blocks=new Map()
  for(const f of fields.value){
    const id=String(f.block_id)
    if(!blocks.has(id))blocks.set(id,{value:id,label:f.block_name||'未注明区块',children:[]})
    blocks.get(id).children.push({value:Number(f.field_id),label:f.field_name})
  }
  return [...blocks.values()]
})
const fieldPath=computed({get(){const f=fields.value.find(f=>Number(f.field_id)===Number(fieldId.value));return f?[String(f.block_id),Number(f.field_id)]:[]},set(value){if(value?.length===2)selectField(Number(value[1])).catch(()=>{})}})
onMounted(async()=>{
  if(!props.showField||embedded)return
  if(basicOnly.value||!fields.value.length)await loadFields({basicOnly:basicOnly.value})
  else if(fieldId.value&&Number(currentConfig.value?.field_id)!==Number(fieldId.value))await loadFieldContext()
})
</script>
