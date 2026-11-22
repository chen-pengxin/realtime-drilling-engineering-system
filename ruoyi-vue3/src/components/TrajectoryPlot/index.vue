<template><div class="trajectory-plot"><div ref="el" class="plot-canvas"/><el-empty v-if="!visibleTrajectories.length" description="暂无所选版本的轨迹数据" :image-size="50"/><div v-if="error" class="plot-error">{{error}}</div></div></template>
<script setup>
import {ref,computed,watch,onMounted,onBeforeUnmount} from 'vue'
import {loadPlotly,chartPalette,plotConfig} from '@/utils/plotlyRuntime'
import {riskColors,riskLabels} from '@/utils/trajectoryColors'
import {trajectoryCategory} from '@/utils/trajectoryStyles'
import useTrajectoryAppearance from '@/store/modules/trajectoryAppearance'
const props=defineProps({trajectories:{type:Array,default:()=>[]},view:{type:String,default:'3d'},azimuth:{type:Number,default:0},markers:{type:Boolean,default:true},grid:{type:Boolean,default:true},legend:{type:Boolean,default:true},hidden:{type:Array,default:()=>[]},nearestPair:{type:Object,default:null},riskSegments:{type:Array,default:()=>[]}})
const el=ref(),error=ref('');let P,observer,theme,serial=0,disposed=false
const appearance=useTrajectoryAppearance()
const visibleTrajectories=computed(()=>props.trajectories.filter((t,i)=>!props.hidden.includes(String(t.id??i))&&(t.points||[]).length))
const point=p=>Array.isArray(p)?{north:p[0],east:p[1],tvd:p[2]}:p
const risks=riskColors
async function draw(){const n=++serial;try{P=await loadPlotly()}catch(e){if(!disposed)error.value='图形组件加载失败，请刷新重试：'+e.message;return;}if(disposed||n!==serial||!el.value?.isConnected)return;const palette=chartPalette();const a=props.azimuth*Math.PI/180;const origin=point(props.trajectories[0]?.points?.[0]||{});let traces=[]
 visibleTrajectories.value.forEach((t,i)=>{const points=(t.points||[]).map(point);const style=appearance.styleFor(t),c=style.color;const coords=points.map(p=>props.view==='horizontal'?[p.east,p.north]:props.view==='vertical'?[(p.north-(origin.north||0))*Math.cos(a)+(p.east-(origin.east||0))*Math.sin(a),p.tvd]:[p.north,p.east,p.tvd]);
 const {role,type}=trajectoryCategory(t);const design=type==='DESIGN'||type==='PLANNED';
 // The source trajectory may contain dense stations for interpolation.  A
 // marker is meaningful only at a design node/segment endpoint; measured and
 // neighbouring actual surveys must remain line-only.  Prefer explicit MD
 // lists, then per-point metadata, then segment transitions.  When an older
 // design source has no node metadata at all, retain only its two endpoints
 // instead of turning every survey station into a circle.
 const explicitKeys=points.some(p=>p.is_key_node!=null||p.key_node!=null||p.segment_endpoint!=null||p.is_segment_endpoint!=null||p.node_type!=null||p.segment!=null||p.segment_id!=null);
 const keyMds=new Set([...(t.key_mds||[]),...(t.segment_endpoints||[]),...(t.segmentEndpointMds||[])].map(Number));
 const metadataKeys=new Set();
 points.forEach((p,index)=>{
   const isNode=p.is_key_node||p.key_node||p.segment_endpoint||p.is_segment_endpoint||String(p.node_type||'').toUpperCase().includes('NODE');
   if(isNode)metadataKeys.add(Number(p.md));
   const segment=p.segment_id??p.segment;
   const previous=index>0?(points[index-1].segment_id??points[index-1].segment):undefined;
   if(segment!=null&&(index===0||segment!==previous))metadataKeys.add(Number(p.md));
   if(segment!=null&&index===points.length-1)metadataKeys.add(Number(p.md));
 });
 const fallbackKeys=new Set(points.length?[Number(points[0].md),Number(points[points.length-1].md)]:[]);
 const markerKeys=keyMds.size?keyMds:(metadataKeys.size?metadataKeys:fallbackKeys);
 const markerSizes=points.map(p=>{if(!props.markers||!design)return 0;return markerKeys.has(Number(p.md))?style.nodeSize:0});const hasMarkers=markerSizes.some(v=>v>0);
 const fullName=t.label||`轨迹 ${i+1}`,name=fullName.length>24?`${fullName.slice(0,12)}…${fullName.slice(-9)}`:fullName
 const base={type:props.view==='3d'?'scatter3d':'scatter',name,legendgroup:String(t.id??i),mode:hasMarkers?'lines+markers':'lines',opacity:style.opacity,line:{color:c,width:style.width,dash:style.dash},marker:{size:markerSizes,color:c,symbol:'circle'},x:coords.map(p=>p[0]),y:coords.map(p=>p[1]),customdata:points.map(p=>[p.md??null,p.inclination??null,p.azimuth??null,p.tvd,fullName]),hovertemplate:'%{customdata[4]}<br>MD %{customdata[0]:.2f} m<br>INC %{customdata[1]:.2f}° AZI %{customdata[2]:.2f}°<br>TVD %{customdata[3]:.2f} m<extra></extra>'};if(props.view==='3d')base.z=coords.map(p=>p[2]);traces.push(base)
 if(role==='REFERENCE'&&type==='ACTUAL')for(const seg of props.riskSegments){const pts=points.filter(p=>p.md>=seg.start_md&&p.md<=seg.end_md);if(pts.length<2)continue;const idx=points.indexOf(pts[0]);const sub=coords.slice(idx,idx+pts.length);traces.push({...base,opacity:1,x:sub.map(p=>p[0]),y:sub.map(p=>p[1]),...(props.view==='3d'?{z:sub.map(p=>p[2])}:{}),customdata:base.customdata.slice(idx,idx+pts.length),marker:{...base.marker,size:base.marker.size.slice(idx,idx+pts.length)},name:`${riskLabels[seg.risk_level]||seg.risk_level} MD ${seg.start_md}–${seg.end_md}`,line:{color:risks[seg.risk_level]||c,width:Math.max(style.width+.25,props.view==='3d'?1.65:1.35),dash:'solid'},showlegend:false})}
 })
 const pair=props.nearestPair;if(pair?.reference&&pair?.neighbor&&props.view==='3d'){const ps=[point(pair.reference),point(pair.neighbor)];traces.push({type:'scatter3d',mode:'lines+markers',x:ps.map(p=>p.north),y:ps.map(p=>p.east),z:ps.map(p=>p.tvd),name:'最近中心距',line:{color:'#b91c1c',width:2.5,dash:'dash'},marker:{size:4,color:'#b91c1c'}})}
 const axis=title=>({title:{text:title},gridcolor:appearance.gridStyle.color,gridwidth:appearance.gridStyle.width,zerolinecolor:palette.border,showgrid:props.grid});const compact=el.value.clientWidth<420;const sceneAxis=title=>({...axis(title),showbackground:props.grid,backgroundcolor:props.grid?palette.background:'rgba(0,0,0,0)'});const layout={autosize:true,margin:{l:props.view==='3d'?0:60,r:15,t:props.legend?65:12,b:props.view==='3d'?0:65},paper_bgcolor:palette.background,plot_bgcolor:palette.background,font:{family:'Arial, Microsoft YaHei, sans-serif',size:11,color:palette.text},showlegend:props.legend,legend:{orientation:'h',y:1.02,yanchor:'bottom',maxheight:65,entrywidthmode:'fraction',entrywidth:compact?1:.5,font:{size:10}},uirevision:`${props.view}-${props.azimuth}-${props.grid}`,scene:{xaxis:sceneAxis('N (m)'),yaxis:sceneAxis('E (m)'),zaxis:{...sceneAxis('TVD (m)'),autorange:'reversed'},aspectmode:'data',camera:{eye:{x:1.5,y:1.5,z:.9}}},xaxis:axis(props.view==='horizontal'?'E (m)':`垂直剖面位移 (m)<br>方位 ${props.azimuth.toFixed(2)}°`),yaxis:{...axis(props.view==='horizontal'?'N (m)':'TVD (m)'),autorange:props.view==='horizontal'?true:'reversed',scaleanchor:'x',scaleratio:1}}
 try{await P.react(el.value,traces,layout,{...plotConfig,toImageButtonOptions:{...plotConfig.toImageButtonOptions,format:props.view==='3d'?'png':'svg'}});error.value=''}catch(e){error.value='图形加载失败：'+e.message}
}
async function exportImage(format='png'){if(P&&el.value)await P.downloadImage(el.value,{format,filename:`trajectory-${props.view}`,width:1400,height:1000})}
defineExpose({exportImage,resize:()=>P?.Plots.resize(el.value)})
watch(()=>[props.trajectories,props.view,props.azimuth,props.markers,props.grid,props.legend,props.hidden,props.nearestPair,props.riskSegments,appearance.preferences,appearance.engineeringStyles],draw,{deep:true})
onMounted(()=>{draw();observer=new ResizeObserver(()=>{if(P&&el.value?.clientWidth)P.Plots.resize(el.value)});observer.observe(el.value);theme=new MutationObserver(draw);theme.observe(document.documentElement,{attributes:true,attributeFilter:['class','style']})})
onBeforeUnmount(()=>{disposed=true;serial++;observer?.disconnect();theme?.disconnect();if(P&&el.value)P.purge(el.value)})
</script>
<style scoped>.trajectory-plot{position:relative;width:100%;height:100%;min-width:0;min-height:220px;overflow:hidden}.plot-canvas{width:100%;height:100%;min-width:0}.el-empty{position:absolute;inset:0;pointer-events:none}.plot-error{position:absolute;bottom:10px;color:var(--el-color-danger);padding:12px}</style>
