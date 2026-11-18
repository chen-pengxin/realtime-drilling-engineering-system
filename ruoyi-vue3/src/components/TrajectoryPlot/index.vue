<template><div class="trajectory-plot"><div ref="el" class="plot-canvas"/><el-empty v-if="!visibleTrajectories.length" description="暂无所选版本的轨迹数据" :image-size="50"/><div v-if="error" class="plot-error">{{error}}</div></div></template>
<script setup>
import {ref,computed,watch,onMounted,onBeforeUnmount} from 'vue'
import {loadPlotly,chartPalette,plotConfig} from '@/utils/plotlyRuntime'
import {trajectoryColors,riskColors} from '@/utils/trajectoryColors'
const props=defineProps({trajectories:{type:Array,default:()=>[]},view:{type:String,default:'3d'},azimuth:{type:Number,default:0},markers:{type:Boolean,default:true},grid:{type:Boolean,default:true},legend:{type:Boolean,default:true},hidden:{type:Array,default:()=>[]},nearestPair:{type:Object,default:null},riskSegments:{type:Array,default:()=>[]}})
const el=ref(),error=ref('');let P,observer,theme,serial=0,disposed=false
const visibleTrajectories=computed(()=>props.trajectories.filter((t,i)=>!props.hidden.includes(String(t.id??i))&&(t.points||[]).length))
const point=p=>Array.isArray(p)?{north:p[0],east:p[1],tvd:p[2]}:p
const color=c=>typeof c==='number'?'#'+c.toString(16).padStart(6,'0'):c
const risks=riskColors
 const trajectoryColor=(trajectory,dark=false)=>{
 const kind=String(trajectory.kind||'').toUpperCase()
 const id=String(trajectory.id||'')
 if(kind==='NEIGHBOR'||id.startsWith('neighbor-')||id.startsWith('bore-'))return dark?'#d9cbed':'#b9a8d0'
 if(trajectoryColors[kind])return trajectoryColors[kind]
 return color(trajectory.color)||'#64748b'
}
async function draw(){const n=++serial;try{P=await loadPlotly()}catch(e){if(!disposed)error.value='图形组件加载失败，请刷新重试：'+e.message;return;}if(disposed||n!==serial||!el.value?.isConnected)return;const palette=chartPalette();const a=props.azimuth*Math.PI/180;const origin=point(props.trajectories[0]?.points?.[0]||{});let traces=[]
 const background=String(palette.background||'').trim().toLowerCase();const rgb=background.match(/rgba?\(\s*(\d+)\s*,\s*(\d+)\s*,\s*(\d+)/);const hex=background.match(/^#([0-9a-f]{6})$/);const channels=rgb?[Number(rgb[1]),Number(rgb[2]),Number(rgb[3])]:hex?[parseInt(hex[1].slice(0,2),16),parseInt(hex[1].slice(2,4),16),parseInt(hex[1].slice(4,6),16)]:[255,255,255];const dark=(channels[0]*299+channels[1]*587+channels[2]*114)/1000<150
 visibleTrajectories.value.forEach((t,i)=>{const points=(t.points||[]).map(point);const c=trajectoryColor(t,dark);const coords=points.map(p=>props.view==='horizontal'?[p.east,p.north]:props.view==='vertical'?[(p.north-(origin.north||0))*Math.cos(a)+(p.east-(origin.east||0))*Math.sin(a),p.tvd]:[p.north,p.east,p.tvd]);
 const kind=String(t.kind||'').toUpperCase();const design=kind==='DESIGN'||kind==='PLANNED';
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
 const markerSizes=points.map(p=>{if(!props.markers||!design)return 0;return markerKeys.has(Number(p.md))?5:0});const hasMarkers=markerSizes.some(v=>v>0);
 const fullName=t.label||`轨迹 ${i+1}`,name=fullName.length>24?`${fullName.slice(0,12)}…${fullName.slice(-9)}`:fullName
 const baseWidth=kind==='NEIGHBOR'?1.05:props.view==='3d'?1.25:1.1
 const base={type:props.view==='3d'?'scatter3d':'scatter',name,legendgroup:String(t.id??i),mode:hasMarkers?'lines+markers':'lines',line:{color:c,width:baseWidth,dash:t.dash?'dash':'solid'},marker:{size:markerSizes,color:c,symbol:'circle'},x:coords.map(p=>p[0]),y:coords.map(p=>p[1]),customdata:points.map(p=>[p.md??null,p.inclination??null,p.azimuth??null,p.tvd,fullName]),hovertemplate:'%{customdata[4]}<br>MD %{customdata[0]:.2f} m<br>INC %{customdata[1]:.2f}° AZI %{customdata[2]:.2f}°<br>TVD %{customdata[3]:.2f} m<extra></extra>'};if(props.view==='3d')base.z=coords.map(p=>p[2]);traces.push(base)
 if(t.kind==='ACTUAL')for(const seg of props.riskSegments){const pts=points.filter(p=>p.md>=seg.start_md&&p.md<=seg.end_md);if(pts.length<2)continue;const idx=points.indexOf(pts[0]);const sub=coords.slice(idx,idx+pts.length);traces.push({...base,x:sub.map(p=>p[0]),y:sub.map(p=>p[1]),...(props.view==='3d'?{z:sub.map(p=>p[2])}:{}),customdata:base.customdata.slice(idx,idx+pts.length),marker:{...base.marker,size:base.marker.size.slice(idx,idx+pts.length)},name:`${seg.risk_level} MD ${seg.start_md}–${seg.end_md}`,line:{color:risks[seg.risk_level]||c,width:props.view==='3d'?1.65:1.35},showlegend:false})}
 })
 const pair=props.nearestPair;if(pair?.reference&&pair?.neighbor&&props.view==='3d'){const ps=[point(pair.reference),point(pair.neighbor)];traces.push({type:'scatter3d',mode:'lines+markers',x:ps.map(p=>p.north),y:ps.map(p=>p.east),z:ps.map(p=>p.tvd),name:'最近中心距',line:{color:'#b91c1c',width:2.5,dash:'dash'},marker:{size:4,color:'#b91c1c'}})}
 const axis=title=>({title:{text:title},gridcolor:palette.border,zerolinecolor:palette.border,showgrid:props.grid});const compact=el.value.clientWidth<420;const sceneAxis=title=>({...axis(title),showbackground:props.grid,backgroundcolor:props.grid?palette.background:'rgba(0,0,0,0)'});const layout={autosize:true,margin:{l:props.view==='3d'?0:60,r:15,t:props.legend?65:12,b:props.view==='3d'?0:65},paper_bgcolor:palette.background,plot_bgcolor:palette.background,font:{family:'Arial, Microsoft YaHei, sans-serif',size:11,color:palette.text},showlegend:props.legend,legend:{orientation:'h',y:1.02,yanchor:'bottom',maxheight:65,entrywidthmode:'fraction',entrywidth:compact?1:.5,font:{size:10}},uirevision:`${props.view}-${props.azimuth}-${props.grid}`,scene:{xaxis:sceneAxis('N (m)'),yaxis:sceneAxis('E (m)'),zaxis:{...sceneAxis('TVD (m)'),autorange:'reversed'},aspectmode:'data',camera:{eye:{x:1.5,y:1.5,z:.9}}},xaxis:axis(props.view==='horizontal'?'E (m)':`垂直剖面位移 (m)<br>方位 ${props.azimuth.toFixed(2)}°`),yaxis:{...axis(props.view==='horizontal'?'N (m)':'TVD (m)'),autorange:props.view==='horizontal'?true:'reversed',scaleanchor:'x',scaleratio:1}}
 try{await P.react(el.value,traces,layout,{...plotConfig,toImageButtonOptions:{...plotConfig.toImageButtonOptions,format:props.view==='3d'?'png':'svg'}});error.value=''}catch(e){error.value='图形加载失败：'+e.message}
}
async function exportImage(format='png'){if(P&&el.value)await P.downloadImage(el.value,{format,filename:`trajectory-${props.view}`,width:1400,height:1000})}
defineExpose({exportImage,resize:()=>P?.Plots.resize(el.value)})
watch(()=>[props.trajectories,props.view,props.azimuth,props.markers,props.grid,props.legend,props.hidden,props.nearestPair,props.riskSegments],draw,{deep:true})
onMounted(()=>{draw();observer=new ResizeObserver(()=>{if(P&&el.value?.clientWidth)P.Plots.resize(el.value)});observer.observe(el.value);theme=new MutationObserver(draw);theme.observe(document.documentElement,{attributes:true,attributeFilter:['class','style']})})
onBeforeUnmount(()=>{disposed=true;serial++;observer?.disconnect();theme?.disconnect();if(P&&el.value)P.purge(el.value)})
</script>
<style scoped>.trajectory-plot{position:relative;width:100%;height:100%;min-height:220px}.plot-canvas{width:100%;height:100%}.el-empty{position:absolute;inset:0;pointer-events:none}.plot-error{position:absolute;bottom:10px;color:var(--el-color-danger);padding:12px}</style>
