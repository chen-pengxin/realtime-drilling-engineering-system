// Compatibility for existing metric panels. All drilling rendering uses Plotly;
// the numerical-engine report convention is shared by the trajectory components.
import {loadPlotly,chartPalette,plotConfig} from './plotlyRuntime'
export {chartPalette}
export function init(element){let option,disposed=false,revision=0,plot
 const axis=v=>Array.isArray(v)?v[0]||{}:v||{}
 async function render(){const n=++revision;let P;try{P=await loadPlotly()}catch(e){if(!disposed)element.textContent="图表组件加载失败，请刷新重试";return;}if(disposed||n!==revision||!option||!element?.isConnected)return;plot=P;const p=chartPalette();const x=axis(option.xAxis),y=axis(option.yAxis);const traces=(option.series||[]).map(s=>{const data=s.data||[];return {name:s.name,type:s.type==='bar'?'bar':'scatter',mode:s.showSymbol===false?'lines':'lines+markers',x:data.map((v,i)=>Array.isArray(v)?v[0]:(x.data?.[i]??i)),y:data.map(v=>Array.isArray(v)?v[1]:typeof v==='object'?v.value:v),line:{color:s.lineStyle?.color||s.itemStyle?.color,width:s.lineStyle?.width||2,dash:s.lineStyle?.type==='dashed'?'dash':'solid'},marker:{size:4},connectgaps:false}});await P.react(element,traces,{autosize:true,margin:{t:30,b:55,l:65,r:20},paper_bgcolor:p.background,plot_bgcolor:p.background,font:{color:p.text,size:11},legend:{orientation:'h',y:1.12},xaxis:{title:{text:x.name||''},gridcolor:p.border,autorange:x.inverse?'reversed':true,type:x.type==='category'?'category':undefined},yaxis:{title:{text:y.name||''},gridcolor:p.border,autorange:y.inverse?'reversed':true},uirevision:'metrics'},plotConfig)}
 const observer=new ResizeObserver(()=>{if(plot&&!disposed&&element?.clientWidth)plot.Plots.resize(element)});observer.observe(element)
 const theme=new MutationObserver(render);theme.observe(document.documentElement,{attributes:true,attributeFilter:['class','style']})
 return {setOption(o){option=o;render()},resize(){if(plot&&!disposed)plot.Plots.resize(element)},dispose(){disposed=true;revision++;observer.disconnect();theme.disconnect();plot?.purge(element)},isDisposed(){return disposed},getDataURL(){return ''}}
}
