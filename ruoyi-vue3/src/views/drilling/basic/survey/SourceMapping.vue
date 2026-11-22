<template>
  <ResizableDialog v-model="visible" :title="readOnly ? '来源文件内容' : '来源表字段映射'" width="1100px" append-to-body class="drilling-dialog" :close-on-click-modal="false">
    <el-alert :title="readOnly ? '按工作表或源表查看解析到的原始单元格。原件内容未被改写。' : '请按原件选择测斜表、表头与数据范围。映射保存为新记录，已有轨迹版本及原件保持不变。'" type="info" :closable="false" class="mb16" />
    <el-form v-if="!readOnly" v-loading="loading" label-width="110px" @submit.prevent>
      <el-form-item label="原始文件名">{{source?.original_name}}</el-form-item>
      <el-form-item label="工作表 / 源表"><el-select v-model="form.locator" @change="resetTable"><el-option v-for="t in tables" :key="t.locator" :value="t.locator" :label="`${t.locator} · ${t.row_count} 行 / ${t.column_count} 列`" /></el-select></el-form-item>
      <el-row :gutter="16"><el-col v-for="[key,label] in rowFields" :key="key" :sm="8" :xs="24"><el-form-item :label="label"><el-input-number v-model="form[key]" :min="1" :max="table?.row_count||1" :precision="0" /></el-form-item></el-col></el-row>
      <el-row :gutter="16"><el-col v-for="[key,label] in fields" :key="key" :sm="8" :xs="24"><el-form-item :label="label" :required="required.includes(key)"><el-select v-model="form.mapping[key]" clearable placeholder="请选择原件列"><el-option v-for="(_,i) in Array(table?.column_count||0)" :key="i" :value="i" :label="`第 ${i+1} 列 · ${header[i]||'无标题'}`" /></el-select></el-form-item></el-col></el-row>
    </el-form>
    <el-form v-else v-loading="loading" label-width="110px"><el-form-item label="工作表 / 源表"><el-select v-model="form.locator" @change="resetTable"><el-option v-for="t in tables" :key="t.locator" :value="t.locator" :label="`${t.locator} · ${t.row_count} 行 / ${t.column_count} 列`" /></el-select></el-form-item></el-form>
    <el-table :data="table?.preview||[]" border max-height="360"><el-table-column prop="source_row" label="原始行号" width="95" fixed /><el-table-column v-for="(_,i) in Array(table?.column_count||0)" :key="i" :label="`第 ${i+1} 列`" min-width="125" show-overflow-tooltip><template #default="s">{{s.row.cells[i]}}</template></el-table-column></el-table>
    <div v-if="!readOnly" class="mapping-note">预览显示原件前 100 行；保存时按所选的完整数据范围读取。来源单位、方位与坐标基准在导入版本时继续核对。</div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template #footer><el-button :disabled="saving" @click="visible=false">{{ readOnly ? '关 闭' : '取 消' }}</el-button><el-button v-if="!readOnly" type="primary" :loading="saving" :disabled="loading||!table" @click="save">保存映射并选择</el-button></template>
  </ResizableDialog>
</template>
<script setup>
import ResizableDialog from '@/components/ResizableDialog.vue'
import {computed,reactive,ref} from 'vue'
import {registry} from '@/api/drillingRegistry'
const props=defineProps({fieldId:Number,readOnly:Boolean}),emit=defineEmits(['mapped'])
const sourceFieldId=computed(()=>Number(source.value?.source_field_id || source.value?.field_id) || props.fieldId)
const visible=ref(false),source=ref(),tables=ref([]),loading=ref(false),saving=ref(false),error=ref('')
const required=['md','inclination','azimuth'],fields=[['md','MD / 测深'],['inclination','井斜角'],['azimuth','方位角'],['north','N / 北坐标'],['east','E / 东坐标'],['tvd','TVD / 垂深']],rowFields=[['header_row','表头行号'],['start_row','数据起始行'],['end_row','数据结束行']]
const form=reactive({locator:'',header_row:1,start_row:2,end_row:2,mapping:{}})
const table=computed(()=>tables.value.find(t=>t.locator===form.locator)),header=computed(()=>table.value?.preview.find(r=>r.source_row===form.header_row)?.cells||[])
function resetTable(){Object.assign(form,{header_row:1,start_row:2,end_row:table.value?.row_count||2,mapping:{}})}
async function show(row){source.value=row;visible.value=true;loading.value=true;tables.value=[];error.value='';try{const result=await registry(sourceFieldId.value,`/sources/${row.source_id}/mapping`,'post',{});tables.value=result.tables||[];form.locator=tables.value[0]?.locator||'';resetTable();if(!tables.value.length)error.value='原件中没有可读表格。请下载原件复核；扫描图片或损坏文档需要先恢复为可读表格。'}catch(e){error.value=e.message}finally{loading.value=false}}
async function save(){error.value='';const mapping=Object.fromEntries(Object.entries(form.mapping).filter(([,v])=>Number.isInteger(v)));if(required.some(k=>!Number.isInteger(mapping[k]))){error.value='请选择 MD、井斜和方位列';return}if(new Set(Object.values(mapping)).size!==Object.values(mapping).length){error.value='不同字段不能使用同一列';return}saving.value=true;try{const r=await registry(sourceFieldId.value,`/sources/${source.value.source_id}/mapping`,'post',{...form,mapping});visible.value=false;emit('mapped',{...source.value,extractionId:r.extraction_id})}catch(e){error.value=e.message}finally{saving.value=false}}
defineExpose({show})
</script>
<style scoped>.mapping-note{font-size:12px;color:var(--el-text-color-regular);margin:12px 0}</style>
