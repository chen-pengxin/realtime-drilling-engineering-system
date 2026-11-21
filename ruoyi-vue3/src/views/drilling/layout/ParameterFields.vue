<template>
  <div class="parameter-fields">
    <template v-for="(value,key) in modelValue" :key="key">
      <template v-if="!exclude.includes(key)">
      <template v-if="modelValue.type==='tabulated_covariance'&&['md_m','cov_nev_m2'].includes(key)">
        <CovarianceTable v-if="key==='md_m'" class="wide-field" :model-value="modelValue" @update:model-value="$emit('update:modelValue',$event)" />
      </template>
      <el-collapse v-else-if="value && typeof value==='object' && !Array.isArray(value)" v-model="expanded[key]"><el-collapse-item :title="label(key)" :name="key"><ParameterFields :model-value="value" :exclude="exclude" @update:model-value="set(key,$event)" /></el-collapse-item></el-collapse>
      <el-form-item v-else :label="label(key)" :data-parameter-key="key" :class="{'wide-field':Array.isArray(value)}">
        <el-tag v-if="key==='type'" type="info">{{errorModelName(value)}}</el-tag>
        <el-select v-else-if="parameterChoices[key]" :model-value="value" @update:model-value="set(key,$event)"><el-option v-for="(text,code) in parameterChoices[key]" :key="code" :label="text" :value="code" /></el-select>
        <el-switch v-else-if="typeof value==='boolean'" :model-value="value" @update:model-value="set(key,$event)" />
        <el-input-number v-else-if="typeof value==='number'" :model-value="value" controls-position="right" :step="step(value)" @update:model-value="set(key,$event)" />
        <ParameterArray v-else-if="Array.isArray(value)&&!Array.isArray(value[0]?.[0])" :model-value="value" :symmetric="['cov_nev_m2','start_cov_nev_m2','correlation'].includes(key)" :axis-labels="key==='inclination_bounds_deg'?['下限','上限']:undefined" @update:model-value="set(key,$event)" />
        <el-input v-else-if="Array.isArray(value)" :model-value="JSON.stringify(value)" type="textarea" :autosize="{minRows:2,maxRows:10}" @change="array(key,$event)" />
        <el-input v-else :model-value="value" @update:model-value="set(key,$event)" />
      </el-form-item>
      </template>
    </template>
    <div v-if="optionalParameters.length" class="wide-field optional-parameters"><span>可选计算参数</span><el-button v-for="key in optionalParameters" :key="key" link type="primary" icon="Plus" @click="addOptional(key)">{{label(key)}}</el-button></div>
  </div>
</template>
<script setup>
import {computed,reactive,watch} from 'vue'
import {ElMessage} from 'element-plus'
import {errorModelName,errorParameterLabel,errorParameterMetadata,parameterChoices} from '@/utils/engineeringErrorModels'
import ParameterArray from './ParameterArray.vue'
import CovarianceTable from './CovarianceTable.vue'
defineOptions({name:'ParameterFields'})
const props=defineProps({modelValue:{type:Object,required:true},exclude:{type:Array,default:()=>[]}}),emit=defineEmits(['update:modelValue'])
// The online numerical wait is a project policy, not an editable
// solver parameter. Keep the legacy JSON key for compatibility but never
// expose it as a second time limit in the configuration form.
const exclude=computed(()=>[...(props.exclude||[]),'max_seconds_per_run'])
// The stored configuration keeps the native engine's English keys.  Those
// keys are an API contract and must remain unchanged; this table is the sole
// presentation mapping so the operator never sees implementation names.
const labels={
  trajectory_mode:'轨迹段组合（圆弧 / 圆弧—直线 / 直线—圆弧）',
  solver:'求解器参数',
  algorithm_options:'求解器专用参数',
  'trust-constr':'trust-constr',
  COBYLA:'COBYLA',
  COBYQA:'COBYQA',
  SLSQP:'SLSQP',
  maxiter:'最大迭代次数',
  gtol:'梯度容差',
  tol:'收敛容差',
  rhobeg:'初始搜索半径',
  max_seconds_per_run:'单次算法最长等待时间（s）',
  random_seed:'随机数种子',
  initial_search_attempts:'可行初值搜索次数',
  optimization_starts:'优化起点数量',
  feasibility_max_nfev:'可行性搜索函数计算上限',
  max_function_evaluations:'函数计算次数上限',
  retain_feasible_iterates:'保留可行迭代结果',
  inclination_bounds_deg:'井斜范围（°）',
  max_segment_bend_deg:'单段最大弯曲角（°）',
  centerline_min_distance_m:'最小井间中心距（m）',
  optimization_cells_per_segment:'每段优化采样单元数',
  optimization_offset_scan_step_m:'优化防碰扫描步长（m）',
  planned_survey_interval_m:'规划测斜间隔（m）',
  planned_survey_max_step_m:'规划轨迹最大采样步长（m）',
  optimization_target_radius_m:'优化目标半径（m）',
  optimization_sf_buffer:'分离系数安全裕量',
  centerline_buffer_m:'中心距安全裕量（m）',
  scan_stations_per_arc:'每个圆弧扫描点数',
  n_segments:'轨迹段数',
  arc_segment_model:'圆弧段计算模型',
  segment_bounds:'分段约束',
  default_segment_bounds:'默认分段约束',
  dls_deg_per_30m:'狗腿度约束（°/30m）',
  start_toolface_deg:'起始工具面范围（°）',
  length_m:'段长范围（m）',
  position_mode:'目标位置约束方式',
  position_tolerance_m:'目标位置容差（m）',
  inclination_tolerance_deg:'目标井斜容差（°）',
  azimuth_tolerance_deg:'目标方位容差（°）',
  target:'目标点参数',
  start:'起始井底状态',
  iscwsa:'ISCWSA 误差参数',
  planned:'规划轨迹误差参数',
  neighbors:'邻井参数',
  history:'历史记录',
  parameters:'测量误差参数',
  propagation_basis:'误差传播方法',
  sf_method:'分离系数计算方法',
  k_sigma:'不确定性放大系数 K',
  sf_threshold:'分离系数阈值',
  centerline_optimization_buffer_m:'中心距优化裕量（m）'
}
const label=k=>errorParameterMetadata[k]?errorParameterLabel(k):labels[k]||k
const step=v=>v===0?0.01:Math.min(1,10**Math.floor(Math.log10(Math.abs(v))))
const expanded=reactive({})
watch(()=>Object.keys(props.modelValue),keys=>{for(const key of keys)if(!expanded[key])expanded[key]=[key]},{immediate:true})
const optionalFields={constant_isotropic:['correlation'],linear_std:['depth_origin_m','correlation'],random_walk:['depth_origin_m','correlation'],simplified_mwd:['depth_origin_m']}
const optionalParameters=computed(()=>(optionalFields[props.modelValue.type]||[]).filter(key=>props.modelValue[key]===undefined))
function addOptional(key){set(key,key==='correlation'?[[1,0,0],[0,1,0],[0,0,1]]:0)}
function set(k,v){emit('update:modelValue',{...props.modelValue,[k]:v})}
function array(k,v){try{const a=JSON.parse(v);if(!Array.isArray(a))throw Error();set(k,a)}catch{ElMessage.error('请输入有效的 JSON 数组')}}
</script>
<style scoped>.parameter-fields{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:0 16px}.el-collapse,.wide-field{grid-column:1/-1;min-width:0}.el-form-item{min-width:0}.el-input-number,.el-select{width:100%}.optional-parameters{display:flex;gap:8px;flex-wrap:wrap;align-items:center;margin:10px 0;font-size:12px}.optional-parameters span{color:var(--el-text-color-secondary)}small{font-size:11px;color:var(--el-text-color-secondary)}@media(max-width:700px){.parameter-fields{grid-template-columns:1fr}}</style>
