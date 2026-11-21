// Run production task loading against isolated HTTP fixtures; no user session or database writes.
const assert=require('node:assert/strict')
const fs=require('node:fs')
const path=require('node:path')
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright')
const base=process.env.DRILLING_FRONTEND_URL||'http://127.0.0.1:5173'
const hash=JSON.parse(fs.readFileSync(path.join(__dirname,'../node_modules/.vite/deps/_metadata.json'),'utf8')).browserHash
const station=(md,north=100)=>({md,inclination:1,azimuth:30,north,east:200,tvd:md})
const version=(id,bore=31)=>({survey_version_id:id,well_id:bore-10,wellbore_id:bore,quality_status:'READY',stations:[station(109),station(id===111?149:139,bore===32?999:101)]})
const neighbor=id=>({wellbore_id:id===102?33:34,well_id:id===102?23:24,survey_version_id:id,usable:true,included:true,relationship_confirmed:true,coordinate_compatible:true,quality_status:'READY',source_review_status:'CONFIRMED',version_policy:'CURRENT_ACTUAL'})
const links=[neighbor(102),neighbor(104)]
const bores=[{wellbore_id:59,well_id:20,field_id:3,owner_field_id:3,actual_version_id:143},{wellbore_id:31,well_id:21,field_id:1,owner_field_id:1,actual_version_id:101,engineering_status:'DRILLING'},{wellbore_id:32,well_id:22,field_id:1,owner_field_id:1,actual_version_id:201}]

;(async()=>{
  const source=await(await fetch(base+'/src/views/drilling/tasks/anticollision/index.vue')).text()
  const moduleUrl=source.match(/from\s*['"]([^'"]*useTrajectoryTask\.js[^'"]*)['"]/)[1]
  const taskSource=await(await fetch(new URL(moduleUrl,base))).text()
  const contextUrl=taskSource.match(/from\s*['"]([^'"]*useDrillingContext\.js[^'"]*)['"]/)[1]
  const browser=await chromium.launch({headless:true,executablePath:process.env.DRILLING_BROWSER_EXECUTABLE})
  try{
    async function scenario(taskName,{failLinks=false,failNeighbor=false,failDesign=false,holdLinks=false,holdDesign=false,switchBore=false,switchVersion=false,retry=false}={}){
      const html=`<!doctype html><html><meta charset="utf-8"><div id="app"></div><script type="module">
        import {createApp,h} from '/node_modules/.vite/deps/vue.js?v=${hash}';
        import {createPinia} from '/node_modules/.vite/deps/pinia.js?v=${hash}';
        import {createRouter,createMemoryHistory} from '/node_modules/.vite/deps/vue-router.js?v=${hash}';
        const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{render:()=>null}}]});
        const pinia=createPinia();
        const {useDrillingContext}=await import('${contextUrl}');useDrillingContext().fieldId.value=1;
        const {useTrajectoryTask}=await import('${moduleUrl}');
        const app=createApp({setup(){const task=useTrajectoryTask('${taskName}');window.task=task;return()=>h('output',JSON.stringify({md:task.detail.value?.stations.at(-1)?.md,tracks:task.baseTrajectories.value.length,error:task.dataError.value,issue:task.inputIssue()}))}});
        app.use(pinia).use(router);
        const {default:useUserStore}=await import('/src/store/modules/user.js');const user=useUserStore();user.permissions=['*:*:*'];user.roles=['admin'];
        await router.push('/drilling/${taskName}?wellboreId=59');await router.isReady();app.mount('#app');window.testApp=app;window.previewReady=true;
      </script></html>`
      const page=await browser.newPage(),errors=[],unexpected=[]
      let release,releaseDesign,linkCalls=0
      const held=holdLinks?new Promise(resolve=>{release=resolve}):Promise.resolve()
      const heldDesign=holdDesign?new Promise(resolve=>{releaseDesign=resolve}):Promise.resolve()
      page.on('pageerror',error=>errors.push(String(error)))
      await page.route('**/__task_loading__',route=>route.fulfill({contentType:'text/html',body:html}))
      await page.route('**/dev-api/**',async route=>{
        const url=new URL(route.request().url()),endpoint=url.pathname.replace('/dev-api','')
        if(endpoint.endsWith('/registry/wellbores'))return route.fulfill({json:bores})
        if(endpoint.endsWith('/workspace/'+taskName))return route.fulfill({json:{payload:{wellboreId:59,surveyVersionId:143}}})
        const list=endpoint.match(/\/wellbores\/(31|32)\/versions$/)
        if(list){
          const bore=Number(list[1]),actual=bore===31?101:201
          return route.fulfill({json:url.searchParams.get('kind')==='DESIGN'?[{survey_version_id:actual+2,is_active:1,quality_status:'READY'}]:[{survey_version_id:actual,is_active:1,quality_status:'READY'},{survey_version_id:actual+10,quality_status:'READY'}]})
        }
        const candidates=endpoint.match(/\/wellbores\/(31|32)\/neighbors$/)
        if(candidates){
          const first=++linkCalls===1
          if(first)await held
          return failLinks&&first?route.fulfill({status:400,json:{code:400,msg:'计算服务拒绝请求：{"detail":"跨井归算缺少明确源/目标CRS"}'}}):route.fulfill({json:links})
        }
        const survey=endpoint.match(/\/registry\/versions\/(\d+)$/)
        if(survey){
          const id=Number(survey[1])
          if(id===103)await heldDesign
          if(id===104&&failNeighbor)return route.fulfill({status:500,json:{code:500,msg:'版本读取失败'}})
          if(id===103&&failDesign)return route.fulfill({status:400,json:{code:400,msg:'计算服务拒绝请求：{"detail":"跨井归算缺少明确源/目标CRS"}'}})
          const bore=id>=200?32:id===102?33:id===104?34:31
          return route.fulfill({json:version(id,bore)})
        }
        unexpected.push(endpoint);return route.fulfill({status:500,json:{code:500,msg:'unexpected request'}})
      })
      try{
        await page.goto(base+'/__task_loading__');await page.waitForFunction(()=>window.previewReady)
        await page.waitForFunction(()=>window.task.detail.value?.survey_version_id===101)
        assert.deepEqual(await page.evaluate(()=>window.task.bores.value.map(row=>row.wellbore_id)),[31,32])
        assert.equal(await page.evaluate(()=>window.task.boreId.value),31)
        if(holdLinks){
          assert.equal(await page.evaluate(()=>window.task.inputsLoading.value),true)
          assert.equal(await page.evaluate(()=>window.task.inputIssue()),'轨迹数据正在加载')
          assert.match(await page.locator('output').innerText(),/"md":139/)
          if(switchBore){
            await page.evaluate(()=>{window.task.boreId.value=32})
            await page.waitForFunction(()=>window.task.detail.value?.survey_version_id===201&&!window.task.inputsLoading.value)
          }else if(switchVersion){
            await page.evaluate(()=>{window.task.versionId.value=111;window.task.loadDetail()})
            await page.waitForFunction(()=>window.task.detail.value?.survey_version_id===111&&!window.task.inputsLoading.value)
          }
          release()
        }
        await page.waitForFunction(()=>!window.task.inputsLoading.value)
        if(holdDesign){
          assert.equal(await page.evaluate(()=>window.task.inputIssue()),'')
          assert.ok((await page.evaluate(()=>window.task.baseTrajectories.value.map(row=>row.id))).includes('actual'))
          releaseDesign()
        }
        if(failDesign&&!switchBore&&!switchVersion)await page.waitForFunction(()=>window.task.dataError.value.includes('设计轨道读取失败'))
        if(switchBore||switchVersion)await page.waitForTimeout(100)
        const snapshot=await page.evaluate(()=>({id:window.task.detail.value?.survey_version_id,error:window.task.dataError.value,issue:window.task.inputIssue(),tracks:window.task.baseTrajectories.value.map(row=>row.id),neighbors:window.task.neighborDetails.value.map(row=>row.survey_version_id)}))
        assert.deepEqual(errors,[]);assert.deepEqual(unexpected,[])
        assert.equal(await page.locator('.el-message--error').count(),0)
        if(switchBore||switchVersion){
          assert.equal(snapshot.id,switchBore?201:111);assert.equal(snapshot.error,'');assert.equal(snapshot.issue,'')
        }else if(failLinks){
          assert.match(snapshot.error,/邻井关系读取失败/);assert.doesNotMatch(snapshot.error,/[{}]/)
          assert.match(snapshot.issue,/邻井关系读取失败/);assert.ok(snapshot.tracks.includes('actual'))
          const blocked=await page.evaluate(()=>{try{window.task.referencePayload();return false}catch{return true}})
          assert.equal(blocked,true)
          if(retry){
            await page.evaluate(()=>window.task.loadNeighborDetails())
            assert.equal(await page.evaluate(()=>window.task.inputIssue()),'')
            assert.deepEqual(await page.evaluate(()=>window.task.neighborDetails.value.map(row=>row.survey_version_id)),[102,104])
          }
        }else if(failNeighbor){
          assert.match(snapshot.issue,/部分邻井轨迹读取失败/);assert.deepEqual(snapshot.neighbors,[102]);assert.ok(snapshot.tracks.includes('actual'))
        }else if(failDesign){
          assert.match(snapshot.error,/设计轨道读取失败/);assert.equal(snapshot.issue,'');assert.ok(snapshot.tracks.includes('actual'));assert.ok(!snapshot.tracks.includes('design'))
        }else{
          assert.equal(snapshot.error,'');assert.equal(snapshot.issue,'');assert.deepEqual(snapshot.neighbors,[102,104])
          const payload=await page.evaluate(()=>window.task.referencePayload())
          assert.equal(payload.reference_survey_version_id,101);assert.equal(payload.neighbors.length,2)
        }
      }finally{if(release)release();if(releaseDesign)releaseDesign();await page.close()}
    }
    for(const name of ['anticollision','replanning']){
      await scenario(name)
      await scenario(name,{holdLinks:true,failLinks:true,retry:true})
      await scenario(name,{failNeighbor:true})
      await scenario(name,{failDesign:true})
      await scenario(name,{holdDesign:true,failDesign:true})
      await scenario(name,{holdLinks:true,failLinks:true,switchBore:true})
      await scenario(name,{holdLinks:true,failLinks:true,switchVersion:true})
    }
    console.log('PASS: both task types show measured data before neighbors; stale cross-field defaults corrected; discovery and version failures prevent incomplete calculations and can be retried; optional design delays/failures are isolated; late responses cannot overwrite a newly selected wellbore or survey version (14 scenarios).')
  }finally{await browser.close()}
})().catch(error=>{console.error(error);process.exitCode=1})
