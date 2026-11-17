<template>
  <el-dialog v-model="open" title="来源资料确认" width="860px" append-to-body :close-on-click-modal="false">
    <el-alert type="info" :closable="false" class="mb16">新导入资料需要核对工程归属、坐标基准和邻井关系；确认后才能生成轨迹版本或用于防碰评价。</el-alert>
    <el-form v-loading="loading" label-width="120px" @submit.prevent>
      <el-row :gutter="16">
        <el-col :sm="12" :xs="24"><el-form-item label="原始文件名">{{ source?.original_name || review?.source_path }}</el-form-item></el-col>
        <el-col :sm="12" :xs="24"><el-form-item label="确认状态"><el-tag :type="review?.status==='CONFIRMED'?'success':'warning'">{{ review?.status==='CONFIRMED'?'已确认':'需确认' }}</el-tag></el-form-item></el-col>
        <el-col v-if="review?.associations?.length" :span="24"><el-form-item label="已关联层级"><div><div v-for="association in review.associations" :key="`${association.node_id}-${association.document_role}-${association.trajectory_kind}`">{{ association.engineering_path }}</div></div></el-form-item></el-col>
        <el-col :sm="8" :xs="24"><el-form-item label="资料范围"><el-select v-model="form.document_scope" class="full"><el-option value="WELL" label="井/井眼资料"/><el-option value="FIELD" label="井场级资料"/></el-select></el-form-item></el-col>
        <el-col :sm="8" :xs="24"><el-form-item label="归属井" :required="form.document_scope!=='FIELD'"><el-input v-model="form.well" maxlength="128" :disabled="form.document_scope==='FIELD'" /></el-form-item></el-col>
        <el-col :sm="8" :xs="24"><el-form-item label="井眼"><el-input v-model="form.wellbore" maxlength="128" /></el-form-item></el-col>
        <el-col :sm="8" :xs="24"><el-form-item label="井场 / 井组"><el-input v-model="form.site" maxlength="128" /></el-form-item></el-col>
        <el-col :sm="12" :xs="24"><el-form-item label="构造位置"><el-input v-model="form.structural_position" /></el-form-item></el-col>
        <el-col :sm="12" :xs="24"><el-form-item label="气田 / 开发区"><el-input v-model="form.oilfield" /></el-form-item></el-col>
        <el-col :span="24"><el-form-item label="行政位置"><el-input v-model="form.location" /></el-form-item></el-col>
        <el-col :sm="8" :xs="24"><el-form-item label="坐标参考系 CRS"><el-input v-model="form.crs" placeholder="未在原件明确时留空" /></el-form-item></el-col>
        <el-col :sm="8" :xs="24"><el-form-item label="坐标基准名称"><el-input v-model="form.coordinate_datum_name" placeholder="例如 北京54" /></el-form-item></el-col>
        <el-col :sm="8" :xs="24"><el-form-item label="深度基准"><el-input v-model="form.depth_datum" placeholder="例如 KB / MSL" /></el-form-item></el-col>
        <el-col :sm="12" :xs="24"><el-form-item label="首点 N / X"><el-input-number v-model="form.origin_north" :controls="false" :precision="6" class="full" /></el-form-item></el-col>
        <el-col :sm="12" :xs="24"><el-form-item label="首点 E / Y"><el-input-number v-model="form.origin_east" :controls="false" :precision="6" class="full" /></el-form-item></el-col>
        <el-col :span="24">
          <el-collapse class="evidence-collapse">
            <el-collapse-item title="查看自动解析证据（只读）" name="evidence">
              <el-descriptions :column="2" border size="small">
                <el-descriptions-item label="资料用途">{{review?.metadata?.engineering?.document_purpose||review?.metadata?.engineering?.document_role||'未识别'}}</el-descriptions-item>
                <el-descriptions-item label="来源阶段">{{review?.metadata?.engineering?.phase||'未识别'}}</el-descriptions-item>
                <el-descriptions-item label="盆地">{{review?.metadata?.engineering?.basin||'—'}}</el-descriptions-item>
                <el-descriptions-item label="构造位置">{{review?.metadata?.engineering?.structural_position||'—'}}</el-descriptions-item>
                <el-descriptions-item label="开发区块">{{review?.metadata?.engineering?.development_block||'—'}}</el-descriptions-item>
                <el-descriptions-item label="目标点">{{review?.metadata?.engineering?.target_metadata||'—'}}</el-descriptions-item>
                <el-descriptions-item label="解析诊断" :span="2">{{source?.diagnostics_json||'无'}}</el-descriptions-item>
              </el-descriptions>
            </el-collapse-item>
          </el-collapse>
        </el-col>
        <el-col :span="24">
          <el-form-item label="邻井候选关系">
            <div class="neighbor-box">
              <WellboreSelect v-model="neighborIds" :rows="review?.neighbor_options||[]" multiple />
              <el-input v-model="form.neighborText" type="textarea" :rows="2" placeholder="也可手工编辑井号；只能填写当前工程目录中的井或井眼" />
              <el-alert v-if="review?.unresolved_neighbor_candidates?.length" type="warning" :closable="false" class="neighbor-warning">
                未映射候选：{{review.unresolved_neighbor_candidates.join('、')}}。请删除这些名称，或先在工程目录中关联对应井眼后再确认。
              </el-alert>
            </div>
          </el-form-item>
        </el-col>
        <el-col :span="24" v-if="review?.extractions?.length">
          <el-form-item label="解析到的测斜表">
            <el-table :data="review.extractions" border size="small" max-height="180">
              <el-table-column prop="locator" label="来源定位" min-width="200" show-overflow-tooltip />
              <el-table-column prop="kind" label="类型" width="90" />
              <el-table-column prop="row_count" label="测点数" width="80" />
              <el-table-column prop="first_md" label="起始 MD" width="100" />
              <el-table-column prop="last_md" label="末点 MD" width="100" />
              <el-table-column prop="import_status" label="使用状态" width="100" />
            </el-table>
            <div class="mapping-tip">测点表的表头、数据范围和列映射可在“字段映射”中修改；来源原件和已生成版本不会被覆盖。</div>
          </el-form-item>
        </el-col>
        <el-col :span="24"><el-form-item label="人工确认说明"><el-input v-model="note" type="textarea" :rows="2" maxlength="500" show-word-limit placeholder="填写依据文件、核对人和需要保留的冲突说明" /></el-form-item></el-col>
      </el-row>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template #footer><el-button @click="open=false">取 消</el-button><el-button type="primary" :loading="saving" :disabled="loading" @click="confirm">确认解析结果并允许使用</el-button></template>
  </el-dialog>
</template>
<script setup>
import WellboreSelect from '@/views/drilling/layout/WellboreSelect.vue'
import {computed,reactive,ref} from 'vue'
import {sourceReview,confirmSourceReview} from '@/api/drillingRegistry'
const props=defineProps({fieldId:Number}),emit=defineEmits(['confirmed'])
const sourceFieldId=computed(()=>Number(source.value?.source_field_id || source.value?.field_id) || props.fieldId)
const open=ref(false),loading=ref(false),saving=ref(false),error=ref(''),source=ref(),review=ref(),note=ref('')
const form=reactive({document_scope:'WELL',well:'',wellbore:'',site:'',structural_position:'',oilfield:'',location:'',crs:'',coordinate_datum_name:'',depth_datum:'',origin_north:null,origin_east:null,neighborText:'',neighborSelections:[]})
function value(...keys){for(const k of keys){const v=review.value?.proposed?.[k];if(v!=null&&v!=='')return v}return ''}
function neighborCode(row){return row.wellbore_code||row.well_code}
function reset(){const p=review.value?.status==='CONFIRMED'?review.value.confirmed:review.value?.proposed||{};const e=p.engineering||p;const r=p.reference||{};const linked=review.value?.associations?.length===1?review.value.associations[0]:{};const candidates=p.neighbor_candidates||e.neighbor_candidates||value('neighbor_candidates')||[];const codes=new Set((Array.isArray(candidates)?candidates:[]).map(String));const suggested=(review.value?.neighbor_options||[]).filter(x=>x.suggested||codes.has(neighborCode(x))).map(neighborCode);Object.assign(form,{document_scope:p.document_scope||'WELL',well:p.well||e.well||value('well')||linked.well_code||linked.well_name||'',wellbore:p.wellbore||e.wellbore||value('wellbore')||linked.wellbore_code||linked.wellbore_name||'',site:p.site||e.site||value('site'),structural_position:e.structural_position||value('structural_position','geological_structure'),oilfield:e.oilfield||value('oilfield'),location:e.location||e.administrative_location||value('location'),crs:r.crs||p.crs||'',coordinate_datum_name:r.coordinate_datum_name||e.coordinate_datum_name||value('coordinate_datum_name'),depth_datum:r.depth_datum||p.depth_datum||'',origin_north:r.origin_north??e.wellhead_north??e.origin_north??null,origin_east:r.origin_east??e.wellhead_east??e.origin_east??null,neighborSelections:[...new Set(suggested)],neighborText:[...new Set(suggested)].join(',')})}
const neighborIds=computed({get:()=>(review.value?.neighbor_options||[]).filter(r=>form.neighborSelections.includes(neighborCode(r))).map(r=>Number(r.wellbore_id)),set:ids=>{form.neighborSelections=(review.value?.neighbor_options||[]).filter(r=>ids.includes(Number(r.wellbore_id))).map(neighborCode);syncNeighborText()}})
function syncNeighborText(){form.neighborText=form.neighborSelections.join(',')}
async function show(row){source.value=row;open.value=true;loading.value=true;error.value='';try{review.value=await sourceReview(sourceFieldId.value,row.source_id);reset()}catch(e){error.value=e.response?.data?.msg||e.message}finally{loading.value=false}}
async function confirm(){if(form.document_scope!=='FIELD'&&!form.well.trim()){error.value='请确认归属井；井场级资料请选择“井场级资料”并填写确认依据';return}saving.value=true;error.value='';try{const neighbors=form.neighborText.split(/[,，\s]+/).map(x=>x.trim()).filter(Boolean);const confirmed={document_scope:form.document_scope,well:form.well.trim(),wellbore:form.wellbore.trim(),site:form.site.trim(),engineering:{well:form.well.trim(),wellbore:form.wellbore.trim(),site:form.site.trim(),structural_position:form.structural_position,oilfield:form.oilfield,location:form.location,administrative_location:form.location,coordinate_datum_name:form.coordinate_datum_name,neighbor_candidates:neighbors},reference:{crs:form.crs||undefined,coordinate_datum_name:form.coordinate_datum_name||undefined,depth_datum:form.depth_datum||undefined,origin_north:form.origin_north,origin_east:form.origin_east},neighbor_candidates:neighbors};review.value=await confirmSourceReview(sourceFieldId.value,source.value.source_id,{status:'CONFIRMED',confirmed,note:note.value});emit('confirmed',review.value);open.value=false}catch(e){error.value=e.response?.data?.msg||e.message}finally{saving.value=false}}
defineExpose({show})
</script>
<style scoped>.full{width:100%}.mb16{margin-bottom:16px}.evidence-collapse{width:100%;margin:2px 0 12px}.neighbor-box{width:100%;display:grid;gap:8px}.neighbor-box :deep(.el-checkbox-group){display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:4px 12px;max-height:130px;overflow:auto}.neighbor-box :deep(.el-checkbox){margin-right:0}.suggested{font-size:12px;color:var(--el-color-primary)}.neighbor-warning{margin-top:4px}.mapping-tip{font-size:12px;color:var(--el-text-color-secondary);line-height:1.5;margin-top:6px}</style>
