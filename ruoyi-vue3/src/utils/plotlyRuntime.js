let pending
export const loadPlotly = () => pending ||= import('plotly.js-dist-min').then(m=>m.default || m).catch(e=>{pending=undefined;throw e})
export function chartPalette(){const s=getComputedStyle(document.documentElement);const v=(key,f)=>s.getPropertyValue(key).trim()||f;return {text:v('--el-text-color-regular','#606266'),border:v('--el-border-color-light','#e4e7ed'),background:v('--el-bg-color','#fff')}}
const zhCN={moduleType:'locale',name:'zh-CN',dictionary:{
  'Download plot as a PNG':'导出 PNG 图片','Download plot':'导出图形','Zoom':'缩放','Pan':'平移',
  'Zoom in':'放大','Zoom out':'缩小','Autoscale':'自动缩放','Reset axes':'重置坐标轴',
  'Orbital rotation':'自由旋转','Turntable rotation':'水平旋转','Reset camera to default':'重置视角',
  'Reset camera to last save':'恢复上次视角','Box Select':'框选','Lasso Select':'套索选择',
  'Click to enter Colorscale title':'点击编辑色标标题','Toggle Spike Lines':'切换参考线'
},format:{}}
export const plotConfig={responsive:true,displaylogo:false,scrollZoom:true,locale:'zh-CN',locales:{'zh-CN':zhCN},toImageButtonOptions:{format:'svg',filename:'trajectory',scale:2}}
