let pending
export const loadPlotly = () => pending ||= import('plotly.js-dist-min').then(m=>m.default || m).catch(e=>{pending=undefined;throw e})
export function chartPalette(){const s=getComputedStyle(document.documentElement);const v=(key,f)=>s.getPropertyValue(key).trim()||f;return {text:v('--el-text-color-regular','#606266'),border:v('--el-border-color-light','#e4e7ed'),background:v('--el-bg-color','#fff')}}
export const plotConfig={responsive:true,displaylogo:false,scrollZoom:true,toImageButtonOptions:{format:'svg',filename:'trajectory',scale:2}}
