<template>
  <ResizableDialog v-model="open" title="测斜版本对比" width="1060px" append-to-body class="drilling-dialog">
    <el-form inline label-width="70px" @submit.prevent="compare">
      <el-form-item label="原版本"><el-select v-model="from" style="width:240px"><el-option v-for="v in versions" :key="v.survey_version_id" :value="v.survey_version_id" :label="versionLabel(v)" /></el-select></el-form-item>
      <el-form-item label="新版本"><el-select v-model="to" style="width:240px"><el-option v-for="v in versions" :key="v.survey_version_id" :value="v.survey_version_id" :label="versionLabel(v)" /></el-select></el-form-item>
      <el-form-item><el-button type="primary" icon="Search" :loading="busy" :disabled="!from || !to || from===to" @click="compare">对比</el-button></el-form-item>
    </el-form>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
    <template v-if="result">
      <el-descriptions :column="4" border class="mb16">
        <el-descriptions-item label="新增测点">{{ result.added }}</el-descriptions-item>
        <el-descriptions-item label="移除测点">{{ result.removed }}</el-descriptions-item>
        <el-descriptions-item label="复测变更">{{ result.resurvey }}</el-descriptions-item>
        <el-descriptions-item label="重算范围">{{ result.affected_from_md == null ? '无变化' : `${result.affected_from_md}～${result.affected_to_md} m` }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="pageRows" border max-height="420" empty-text="两版本测点井深、井斜和方位一致">
        <el-table-column prop="md" label="MD（m）" width="110" />
        <el-table-column label="变化" width="90"><template #default="s">{{ changeLabels[s.row.change] }}</template></el-table-column>
        <el-table-column label="原井斜（°）"><template #default="s">{{ fmt(s.row.before?.inclination) }}</template></el-table-column>
        <el-table-column label="新井斜（°）"><template #default="s">{{ fmt(s.row.after?.inclination) }}</template></el-table-column>
        <el-table-column label="原方位（°）"><template #default="s">{{ fmt(s.row.before?.azimuth) }}</template></el-table-column>
        <el-table-column label="新方位（°）"><template #default="s">{{ fmt(s.row.after?.azimuth) }}</template></el-table-column>
        <el-table-column label="新坐标 N / E / TVD（m）" min-width="230"><template #default="s">{{ s.row.after ? [s.row.after.north,s.row.after.east,s.row.after.tvd].map(fmt).join(' / ') : '—' }}</template></el-table-column>
      </el-table>
      <pagination v-show="result.changes.length>0" v-model:page="page" v-model:limit="limit" :total="result.changes.length" />
    </template>
    <template #footer><el-button @click="open=false">关 闭</el-button></template>
  </ResizableDialog>
</template>
<script setup>
import ResizableDialog from '@/components/ResizableDialog.vue'
import {
  computed,
  ref,
  watch
} from 'vue'
import {
  compareTrajectoryVersions
} from '@/api/drillingRegistry'
const props = defineProps({
  fieldId: Number,
  versions: {
    type: Array,
    default: () => []
  }
})
const open = ref(false),
  from = ref(),
  to = ref(),
  result = ref(),
  busy = ref(false),
  error = ref(''),
  page = ref(1),
  limit = ref(20)
const changeLabels = {
  ADDED: '新增',
  REMOVED: '移除',
  RESURVEY: '复测修订'
}
const versionLabel = v => `v${v.version_no} · ${v.label || ''}`
const fmt = value => value == null ? '—' : Number(value).toFixed(3)
const pageRows = computed(() => result.value?.changes.slice((page.value - 1) * limit.value, page.value * limit.value) || [])
watch([from, to], () => {
  result.value = null;
  error.value = '';
  page.value = 1
})
async function compare() {
  busy.value = true;
  error.value = '';
  const a = from.value,
    b = to.value;
  try {
    const data = await compareTrajectoryVersions(props.fieldId, a, b);
    if (a === from.value && b === to.value) result.value = data
  } catch (e) {
    error.value = e.response?.data?.msg || e.message || '对比失败'
  } finally {
    busy.value = false
  }
}

function show(version) {
  to.value = version.survey_version_id;
  from.value = version.parent_version_id || props.versions.find(v => v.survey_version_id !== to.value)?.survey_version_id;
  result.value = null;
  open.value = true;
  if (from.value) compare()
}
defineExpose({
  show
})
</script>
