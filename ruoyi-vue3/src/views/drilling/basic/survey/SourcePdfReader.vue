<template>
  <section class="source-pdf-reader" v-loading="loading" aria-label="原文档阅读器">
    <div v-if="pages" class="reader-toolbar">
      <el-button size="small" :disabled="page<=1" @click="page--">上一页</el-button>
      <el-input-number v-model="page" :min="1" :max="pages" :controls="false" aria-label="文档页码" size="small" />
      <span>/ {{ pages }}</span>
      <el-button size="small" :disabled="page>=pages" @click="page++">下一页</el-button>
      <el-button size="small" :disabled="zoom<=.5" @click="zoom=Math.max(.5,zoom-.25)">缩小</el-button>
      <el-button size="small" :disabled="zoom>=3" @click="zoom=Math.min(3,zoom+.25)">放大</el-button>
    </div>
    <div ref="surface" class="reader-surface"><canvas ref="canvas" aria-label="原文档页面" /></div>
    <el-alert v-if="error" :title="error" type="error" :closable="false" />
  </section>
</template>
<script setup>
import {nextTick,onBeforeUnmount,onMounted,ref,watch} from 'vue'
import * as pdfjs from 'pdfjs-dist'
import pdfWorker from 'pdfjs-dist/build/pdf.worker.min.mjs?url'
pdfjs.GlobalWorkerOptions.workerSrc=pdfWorker
const props=defineProps({url:String})
const pages=ref(0),page=ref(1),zoom=ref(1),loading=ref(false),error=ref(''),canvas=ref(),surface=ref()
let document,loadTask,renderTask,observer,revision=0,disposed=false
async function render(){
 if(!document||!canvas.value||!surface.value)return
 const token=++revision;loading.value=true
 if(renderTask){renderTask.cancel();try{await renderTask.promise}catch{}renderTask=null}
 try{
  const pdfPage=await document.getPage(page.value);if(disposed||token!==revision)return
  const base=pdfPage.getViewport({scale:1}),width=Math.max(200,surface.value.clientWidth-24),scale=width/base.width*zoom.value,viewport=pdfPage.getViewport({scale}),ratio=Math.min(window.devicePixelRatio||1,2)
  const node=canvas.value;node.width=Math.ceil(viewport.width*ratio);node.height=Math.ceil(viewport.height*ratio);node.style.width=`${viewport.width}px`;node.style.height=`${viewport.height}px`
  renderTask=pdfPage.render({canvasContext:node.getContext('2d'),viewport,transform:ratio===1?null:[ratio,0,0,ratio,0,0]})
  await renderTask.promise;if(token===revision)error.value=''
 }catch(e){if(e.name!=='RenderingCancelledException'&&!disposed)error.value='文档页面读取失败，请下载原件查看'}
 finally{if(token===revision)loading.value=false}
}
async function load(){
 revision++;loadTask?.destroy();document?.destroy();document=null;pages.value=0;page.value=1;error.value=''
 if(!props.url)return
 loading.value=true
 try{
  loadTask=pdfjs.getDocument({url:props.url,useSystemFonts:true})
  loadTask.onPassword=()=>{error.value='文档已加密，请下载原件查看';loadTask.destroy()}
  const result=await loadTask.promise;if(disposed){result.destroy();return}document=result;pages.value=result.numPages;await nextTick();await render()
 }catch(e){if(!disposed){error.value=error.value||'原文档读取失败，请下载原件查看';loading.value=false}}
}
watch(()=>props.url,load)
watch([page,zoom],render)
onMounted(()=>{load();observer=new ResizeObserver(render);observer.observe(surface.value)})
onBeforeUnmount(()=>{disposed=true;revision++;observer?.disconnect();renderTask?.cancel();loadTask?.destroy();document?.destroy()})
</script>
<style scoped>
.source-pdf-reader{width:100%;min-width:0;background:#e5e7eb}.reader-toolbar{display:flex;align-items:center;justify-content:center;gap:8px;flex-wrap:wrap;padding:8px;background:var(--el-bg-color);border-bottom:1px solid var(--el-border-color-light);position:sticky;top:0;z-index:1}.reader-toolbar .el-button{margin:0}.reader-toolbar .el-input-number{width:64px}.reader-surface{height:min(66vh,700px);min-height:350px;overflow:auto;padding:12px;box-sizing:border-box;text-align:center}.reader-surface canvas{display:block;margin:auto;background:white;box-shadow:0 1px 6px #0003}
</style>
