// An evaluation request finishing after navigation must not alert on the directory.
const assert=require('node:assert/strict')
const fs=require('node:fs')
const path=require('node:path')
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright')
const base=process.env.DRILLING_FRONTEND_URL||'http://127.0.0.1:5173'
const hash=JSON.parse(fs.readFileSync(path.join(__dirname,'../node_modules/.vite/deps/_metadata.json'),'utf8')).browserHash
const template=`<!doctype html><html><meta charset="utf-8"><div id="app"></div><script type="module">
import {createApp,h} from '/node_modules/.vite/deps/vue.js?v=${hash}';
import {createPinia} from '/node_modules/.vite/deps/pinia.js?v=${hash}';
import {createRouter,createMemoryHistory} from '/node_modules/.vite/deps/vue-router.js?v=${hash}';
let Monitor;const app=createApp({render:()=>Monitor?h(Monitor):null});app.use(createPinia());
const {default:useUserStore}=await import('/src/store/modules/user.js');const user=useUserStore();user.permissions=['*:*:*'];user.roles=['admin'];
const {useDrillingContext}=await import('/src/views/drilling/composables/useDrillingContext.js');useDrillingContext().fieldId.value=1;
const {useLiveDrilling}=await import('__LIVE_MODULE_URL__');window.live=useLiveDrilling();
Monitor=(await import('/src/views/drilling/layout/RealtimeMonitor.vue')).default;
window.router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{render:()=>null}}]});app.use(window.router);
await window.router.push('/drilling/realtime');await window.router.isReady();app.mount('#app');window.previewReady=true;
</script></html>`
;(async()=>{
 const browser=await chromium.launch({headless:true,executablePath:process.env.DRILLING_BROWSER_EXECUTABLE})
 let release
 try{
  const transformed=await(await fetch(base+'/src/views/drilling/layout/RealtimeMonitor.vue')).text()
  const liveModule=transformed.match(/from\s*['"]([^'"]*useLiveDrilling\.js[^'"]*)['"]/)[1]
  const html=template.replace('__LIVE_MODULE_URL__',liveModule)
  const page=await browser.newPage();const errors=[],unexpected=[]
  page.on('pageerror',error=>errors.push(String(error)))
  let started;const neighborStarted=new Promise(resolve=>started=resolve),held=new Promise(resolve=>release=resolve)
  await page.route('**/__monitor_directory__',route=>route.fulfill({contentType:'text/html',body:html}))
  await page.route('**/dev-api/**',async route=>{
   const endpoint=new URL(route.request().url()).pathname.replace('/dev-api','')
   if(endpoint.endsWith('/registry/wellbores'))return route.fulfill({json:[{wellbore_id:31,well_id:21,field_id:1,actual_version_id:101,engineering_status:'DRILLING'}]})
   if(endpoint.endsWith('/registry/versions/101'))return route.fulfill({json:{survey_version_id:101,well_id:21,quality_status:'READY',stations:[{md:0},{md:10}]}})
   if(endpoint.endsWith('/registry/engine-confirmation'))return route.fulfill({json:{confirmed:true,configuration:{prediction:{}}}})
   if(endpoint.endsWith('/registry/wellbores/31/neighbors')){
    started();await held
    return route.fulfill({status:400,json:{code:400,msg:'跨井归算缺少明确源/目标CRS'}})
   }
   unexpected.push(endpoint);return route.fulfill({status:500,json:{code:500,msg:'unexpected request'}})
  })
  await page.goto(base+'/__monitor_directory__');await page.waitForFunction(()=>window.previewReady,null,{timeout:20000});await neighborStarted
  assert.equal(await page.evaluate(()=>window.live.loading.value),true)
  await page.evaluate(()=>window.router.push('/drilling/basic/catalog'))
  await page.waitForFunction(()=>!window.live.loading.value)
  release();await page.waitForTimeout(400)
  assert.equal(await page.locator('.el-message--error').count(),0)
  assert.equal(await page.evaluate(()=>window.live.error.value),'')
  assert.equal(await page.evaluate(()=>window.live.detail.value),null)
  assert.deepEqual(errors,[]);assert.deepEqual(unexpected,[])
  console.log('PASS: leaving engineering evaluation invalidates the live request; its late coordinate error produces no alert or stale snapshot on the directory.')
 }finally{if(release)release();await browser.close()}
})().catch(error=>{console.error(error);process.exitCode=1})
