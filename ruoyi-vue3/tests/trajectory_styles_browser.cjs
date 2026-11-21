// Real Vue / Plotly rendering in an isolated browser; no business API writes.
const assert=require('node:assert/strict')
const fs=require('node:fs')
const path=require('node:path')
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright')
const base=process.env.DRILLING_FRONTEND_URL||'http://127.0.0.1:5173'
const hash=JSON.parse(fs.readFileSync(path.join(__dirname,'../node_modules/.vite/deps/_metadata.json'),'utf8')).browserHash
const output=path.resolve(__dirname,'../../.local-runtime/trajectory-styles-browser')
const html=`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><link rel="stylesheet" href="/node_modules/element-plus/dist/index.css">
<style>body{margin:0;color:#303133;background:#fff;font-family:Arial,sans-serif}#app{padding:12px}.prediction-fixture{height:320px;margin-top:20px}</style>
<div id="app"></div><script type="module">
import {createApp,ref,h} from '/node_modules/.vite/deps/vue.js?v=${hash}';
import {createPinia} from '/node_modules/.vite/deps/pinia.js?v=${hash}';
import {createRouter,createMemoryHistory} from '/node_modules/.vite/deps/vue-router.js?v=${hash}';
import ElementPlus from '/node_modules/.vite/deps/element-plus.js?v=${hash}';
import * as icons from '/node_modules/.vite/deps/@element-plus_icons-vue.js?v=${hash}';
import Workspace from '/src/views/drilling/layout/EngineeringWorkspace.vue';
import PredictionPlots from '/src/views/drilling/tasks/prediction/PredictionPlots.vue';
import useUserStore from '/src/store/modules/user.js';
import useAppearance from '/src/store/modules/trajectoryAppearance.js';
import {useDrillingContext} from '/src/views/drilling/composables/useDrillingContext.js';
window.engineeringContext=useDrillingContext();
const points=[0,100,200,300].map((md,i)=>({md,north:i*100,east:i*40,tvd:md,inclination:i*5,azimuth:20}));
const bore=id=>({block_id:1,block_name:'测试区块',field_id:1,field_name:'测试井场',well_id:id,well_name:'井'+id,wellbore_id:id,wellbore_name:'主井眼'});
const tracks=ref([
...['ACTUAL','DESIGN','PLANNED','PREDICTED'].map(kind=>({...bore(1),id:kind,kind,label:kind,points,key_mds:[0,300]})),
{...bore(2),id:'neighbor-101',kind:'NEIGHBOR',trajectory_kind:'ACTUAL',label:'邻井实钻2',points},
{...bore(2),id:'neighbor-102',kind:'NEIGHBOR',trajectory_kind:'DESIGN',label:'邻井设计2',points},
{...bore(3),id:'neighbor-103',kind:'NEIGHBOR',trajectory_kind:'ACTUAL',label:'邻井实钻3',points}
]);
const app=createApp({setup(){return()=>[h(Workspace,{title:'轨迹再规划',taskType:'replanning',trajectories:tracks.value,riskSegments:[{start_md:100,end_md:200,risk_level:'RED'}]}),h('div',{class:'prediction-fixture'},[h(PredictionPlots,{wellbore:bore(1),measured:points,predicted:[{...points[3],md:400,north:400,tvd:400}]})])]}});
const pinia=createPinia();app.use(pinia).use(ElementPlus);
const user=useUserStore();user.token='fixture';user.id='style-test-user';window.user=user;
window.appearance=useAppearance();window.tracks=tracks;
const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{render:()=>null}}]});app.use(router);
for(const [name,component] of Object.entries(icons))app.component(name,component);
await router.push('/');await router.isReady();app.mount('#app');window.fixtureReady=true;
</script></html>`

;(async()=>{
  const appearanceSource=await(await fetch(base+'/src/store/modules/trajectoryAppearance.js')).text()
  const contextUrl=appearanceSource.match(/from\s*['"]([^'"]*useDrillingContext\.js[^'"]*)['"]/)[1]
  const browser=await chromium.launch({headless:true,executablePath:process.env.DRILLING_BROWSER_EXECUTABLE,args:['--use-gl=angle','--use-angle=swiftshader','--enable-unsafe-swiftshader']})
  const page=await browser.newPage({viewport:{width:1700,height:1100}}),errors=[],requests=[]
  page.setDefaultTimeout(20000)
  page.on('pageerror',e=>errors.push(String(e)))
  await page.route('**/__trajectory_styles__',route=>route.fulfill({contentType:'text/html',body:html.replace("'/src/views/drilling/composables/useDrillingContext.js'",JSON.stringify(contextUrl))}))
  await page.route('**/dev-api/**',route=>{requests.push(route.request().url());return route.fulfill({status:500,json:{msg:'No business API expected'}})})
  const plots=()=>page.locator('.trajectory-plot .plot-canvas')
  const trace=async(name,index=0)=>plots().nth(index).evaluate((el,name)=>{const t=el.data.find(t=>t.name===name);return t?{color:t.line.color,width:t.line.width,dash:t.line.dash,opacity:t.opacity,mode:t.mode,markers:t.marker.size}:null},name)
  async function waitColor(name,color){await page.waitForFunction(([name,color])=>[...document.querySelectorAll('.trajectory-plot .plot-canvas')].every(el=>el.data?.find(t=>t.name===name)?.line.color===color),[name,color])}
  async function openStyles(){await page.getByRole('button',{name:'视图设置',exact:true}).click();if(await page.getByRole('button',{name:'轨迹样式',exact:true}).getAttribute('aria-expanded')!=='true')await page.getByRole('button',{name:'轨迹样式',exact:true}).click();await page.getByRole('button',{name:'应用样式',exact:true}).waitFor()}
  try{
    await page.goto(base+'/__trajectory_styles__');await page.waitForFunction(()=>window.fixtureReady)
    await page.waitForFunction(()=>document.querySelectorAll('.trajectory-plot .plot-canvas.js-plotly-plot').length===3&&document.querySelector('.pose-chart')?.data?.length===2)
    assert.equal((await trace('ACTUAL')).mode,'lines')
    assert.deepEqual((await trace('DESIGN')).markers,[5,0,0,5])
    await openStyles()
    await page.getByRole('textbox',{name:'轨迹颜色值',exact:true}).fill('#123abc')
    await page.getByRole('spinbutton',{name:'轨迹线宽',exact:true}).fill('2.75');await page.getByRole('spinbutton',{name:'轨迹线宽',exact:true}).press('Tab')
    await page.getByRole('spinbutton',{name:'轨迹不透明度',exact:true}).fill('60');await page.getByRole('spinbutton',{name:'轨迹不透明度',exact:true}).press('Tab')
    await page.locator('.trajectory-style-settings .el-form-item').filter({hasText:'线型'}).locator('.el-select').click()
    await page.getByRole('option',{name:'点划线',exact:true}).click()
    await page.getByRole('button',{name:'应用样式',exact:true}).click()
    await waitColor('ACTUAL','#123abc')
    for(let i=0;i<3;i++)assert.deepEqual(await trace('ACTUAL',i),{color:'#123abc',width:2.75,dash:'dashdot',opacity:.6,mode:'lines',markers:[0,0,0,0]})
    assert.equal((await trace('危险 MD 100–200')).color,'#dc2626')
    assert.equal((await trace('危险 MD 100–200')).opacity,1)
    assert.equal((await trace('危险 MD 100–200')).width,3)
    await page.waitForFunction(()=>[...document.querySelectorAll('.pose-chart')].every(el=>el.data?.[0].line.color==='#123abc'&&el.data[0].line.width===2.75))

    // Hierarchical parent selection applies an override to both neighboring bores.
    await page.locator('.trajectory-style-settings .el-form-item').filter({hasText:'井眼角色'}).locator('.el-select').click()
    await page.getByRole('option',{name:'邻井',exact:true}).click()
    await page.locator('.trajectory-style-settings .el-radio').filter({hasText:'指定井眼'}).click()
    await page.locator('.trajectory-style-settings .wellbore-select .el-select').click()
    await page.locator('.el-select-dropdown:visible .el-tree-node__content').filter({hasText:'测试区块'}).first().locator('.el-checkbox').click()
    await page.getByRole('textbox',{name:'轨迹颜色值',exact:true}).click()
    await page.getByRole('textbox',{name:'轨迹颜色值',exact:true}).fill('#8b5cf6')
    await page.getByRole('button',{name:'应用样式',exact:true}).click()
    await waitColor('邻井实钻2','#8b5cf6');await waitColor('邻井实钻3','#8b5cf6')
    assert.equal((await trace('邻井设计2')).color,'#b9a8d0')
    assert.deepEqual(await page.evaluate(()=>Object.keys(window.appearance.preferences.wellbores)),['2','3'])

    // Model/segment styles reach all 2D/3D and prediction renderers independently.
    await page.evaluate(()=>{
      for(const [type,color] of [['DESIGN','#008844'],['PLANNED','#dd6600'],['PREDICTED','#aa3377']])window.appearance.apply({role:'REFERENCE',type,style:{color,width:1.75,dash:'dash',nodeSize:8}})
    })
    await waitColor('DESIGN','#008844');await waitColor('PLANNED','#dd6600');await waitColor('PREDICTED','#aa3377')
    assert.deepEqual((await trace('PLANNED')).markers,[8,0,0,8])
    assert.equal((await trace('PREDICTED')).mode,'lines')
    await page.waitForFunction(()=>[...document.querySelectorAll('.pose-chart')].every(el=>el.data?.[1].line.color==='#aa3377'))
    await page.evaluate(()=>{window.tracks.value=window.tracks.value.map(t=>({...t,id:t.id==='neighbor-101'?'neighbor-999':t.id}))})
    await waitColor('邻井实钻2','#8b5cf6')

    // Reload and account switching retain only the appropriate account's styles.
    await page.reload();await page.waitForFunction(()=>window.fixtureReady);await waitColor('ACTUAL','#123abc');await waitColor('邻井实钻2','#8b5cf6')
    await page.evaluate(()=>{window.user.id='other-user'})
    await waitColor('ACTUAL','#2563eb');await waitColor('邻井实钻2','#9ca3af')
    await page.evaluate(()=>{window.user.id='style-test-user'})
    await waitColor('ACTUAL','#123abc')
    await page.evaluate(()=>{window.appearance.apply({role:'NEIGHBOR',type:'ACTUAL',wellboreIds:[2],reset:true})})
    await waitColor('邻井实钻2','#9ca3af');await waitColor('邻井实钻3','#8b5cf6')
    await openStyles()
    fs.mkdirSync(output,{recursive:true})
    await page.screenshot({path:path.join(output,'desktop.png'),fullPage:true})
    await page.setViewportSize({width:390,height:844})
    await page.getByRole('textbox',{name:'轨迹颜色值',exact:true}).scrollIntoViewIfNeeded()
    assert.equal(await page.evaluate(()=>document.documentElement.scrollWidth<=innerWidth+1),true)
    await page.screenshot({path:path.join(output,'mobile.png'),fullPage:true})
    await page.getByRole('button',{name:'清除全部视图调整',exact:true}).click()
    await page.getByRole('button',{name:'重置',exact:true}).click()
    await waitColor('ACTUAL','#2563eb');await waitColor('PREDICTED','#16a34a');await waitColor('邻井实钻3','#9ca3af')
    // A page adjustment only overrides changed fields from the shared engineering policy.
    await page.evaluate(()=>{
      window.engineeringContext.currentConfig.value={algorithm_defaults_json:JSON.stringify({visualization:{version:1,styles:{REFERENCE:{ACTUAL:{color:'#112233',width:2,dash:'dot'}}},wellbores:{}}})}
    })
    await waitColor('ACTUAL','#112233')
    await page.evaluate(()=>{window.appearance.apply({role:'REFERENCE',type:'ACTUAL',style:{...window.appearance.styleFor({kind:'ACTUAL'}),color:'#aabbcc'}})})
    await waitColor('ACTUAL','#aabbcc')
    assert.deepEqual(await page.evaluate(()=>window.appearance.preferences.styles.REFERENCE.ACTUAL),{color:'#aabbcc'})
    await page.evaluate(()=>{
      window.engineeringContext.currentConfig.value={algorithm_defaults_json:JSON.stringify({visualization:{version:1,styles:{REFERENCE:{ACTUAL:{color:'#223344',width:3,dash:'dash'}}},wellbores:{}}})}
    })
    await waitColor('ACTUAL','#aabbcc')
    await page.waitForFunction(()=>document.querySelector('.trajectory-plot .plot-canvas').data.find(t=>t.name==='ACTUAL').line.width===3)
    await page.getByRole('button',{name:'恢复工程配置',exact:true}).click()
    await waitColor('ACTUAL','#223344')
    await page.evaluate(()=>{window.user.token=''})
    assert.deepEqual(await page.evaluate(()=>window.appearance.preferences),{version:1,styles:{},wellbores:{}})
    assert.deepEqual(errors,[]);assert.deepEqual(requests,[])
    console.log('PASS: real 3D/vertical/horizontal and prediction plots, editable styles, hierarchy batch overrides, semantic segment/node styles, risk colors, version persistence, reload/account isolation, reset, mobile layout; no business API requests.')
  }catch(error){
    fs.mkdirSync(output,{recursive:true})
    await page.screenshot({path:path.join(output,'failure.png'),fullPage:true}).catch(()=>{})
    console.error({errors,requests,body:(await page.locator('body').innerText()).slice(0,3000),plots:await plots().count()})
    throw error
  }finally{await browser.close()}
})().catch(error=>{console.error(error);process.exitCode=1})
