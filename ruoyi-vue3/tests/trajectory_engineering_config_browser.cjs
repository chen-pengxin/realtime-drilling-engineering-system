// Exercise the actual engineering configuration page against an in-memory API.
const assert=require('node:assert/strict')
const fs=require('node:fs')
const path=require('node:path')
const {chromium}=require(process.env.PLAYWRIGHT_MODULE||'playwright')
const base=process.env.DRILLING_FRONTEND_URL||'http://127.0.0.1:5173'
const hash=JSON.parse(fs.readFileSync(path.join(__dirname,'../node_modules/.vite/deps/_metadata.json'),'utf8')).browserHash
const defaults=JSON.parse(fs.readFileSync(path.join(__dirname,'../src/views/drilling/composables/native-defaults.json'),'utf8'))
;(async()=>{
  const source=await(await fetch(base+'/src/views/drilling/basic/ConfigPage.vue')).text()
  const contextUrl=source.match(/from\s*['"]([^'"]*useDrillingContext\.js[^'"]*)['"]/)[1]
  const html=`<!doctype html><html lang="zh-CN"><meta charset="utf-8"><link rel="stylesheet" href="/node_modules/element-plus/dist/index.css"><div id="app"></div><script type="module">
    import {createApp,h} from '/node_modules/.vite/deps/vue.js?v=${hash}';
    import {createPinia} from '/node_modules/.vite/deps/pinia.js?v=${hash}';
    import {createRouter,createMemoryHistory} from '/node_modules/.vite/deps/vue-router.js?v=${hash}';
    import ElementPlus from '/node_modules/.vite/deps/element-plus.js?v=${hash}';
    import Config from '/src/views/drilling/basic/ConfigPage.vue';
    import useUserStore from '/src/store/modules/user.js';
    import useAppearance from '/src/store/modules/trajectoryAppearance.js';
    import {useDrillingContext} from ${JSON.stringify(contextUrl)};
    const context=useDrillingContext();context.fieldId.value=1;
    const app=createApp({setup(){const style=useAppearance();return()=>[h(Config),h('output',{'data-testid':'effective-style'},JSON.stringify(style.styleFor({kind:'ACTUAL'})))]}});
    app.use(createPinia()).use(ElementPlus);const user=useUserStore();user.id='engineering-style-fixture';user.token='fixture';user.permissions=['*:*:*'];
    window.appearance=useAppearance();window.context=context;
    const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{render:()=>null}}]});app.use(router);
    await router.push('/drilling/basic/config?tab=visualization');await router.isReady();app.mount('#app');window.fixtureReady=true;
  </script></html>`
  let saved=structuredClone(defaults),version=1
  const mutations=[],unexpected=[],errors=[]
  const browser=await chromium.launch({headless:true,executablePath:process.env.DRILLING_BROWSER_EXECUTABLE})
  const page=await browser.newPage({viewport:{width:1500,height:1000}})
  const current=()=>({field_id:1,engineering_config_version:version,config_version:1,coordinate_system:'LOCAL_ENU',north_reference:'GRID_NORTH',length_unit:'m',angle_unit:'deg',tvd_positive:'D',algorithm_defaults_json:JSON.stringify(saved)})
  page.on('pageerror',error=>errors.push(String(error)))
  await page.route('**/__engineering_styles__',route=>route.fulfill({contentType:'text/html',body:html}))
  await page.route('**/dev-api/**',async route=>{
    const request=route.request(),url=new URL(request.url()),endpoint=url.pathname.replace('/dev-api','')
    let body
    if(endpoint==='/api/algorithm/engine/catalog')body={models:[],algorithms:['SLSQP'],trajectory_modes:['arc','arc-straight','straight-arc'],segment_counts:[1,2,3,4,5]}
    else if(endpoint==='/api/drilling/fields')body=[{field_id:1,block_id:1,block_name:'测试区块',field_name:'测试井场'}]
    else if(endpoint.endsWith('/configs/current'))body=current()
    else if(endpoint.endsWith('/configs')&&request.method()==='POST'){
      const payload=request.postDataJSON();mutations.push(payload);saved=payload.algorithmDefaults;version++;body=current()
    }else if(endpoint.endsWith('/configs'))body=[{config_version:version,status:'0'}]
    else if(endpoint.endsWith('/models'))body={models:[]}
    else if(endpoint.endsWith('/registry/wellbores'))body=[{block_id:1,block_name:'测试区块',field_id:1,field_name:'测试井场',well_id:1,well_name:'井1',wellbore_id:1,wellbore_name:'主井眼'}]
    else if(/\/(wells|files|training\/templates)$/.test(endpoint))body=[]
    else{unexpected.push(endpoint);return route.fulfill({status:500,json:{msg:'Unexpected fixture request'}})}
    await route.fulfill({json:body})
  })
  try{
    await page.goto(base+'/__engineering_styles__')
    await page.waitForFunction(()=>window.fixtureReady&&window.context.currentConfig.value?.engineering_config_version===1)
    assert.equal(await page.getByRole('tab',{name:'轨迹可视化',exact:true}).getAttribute('aria-selected'),'true')
    await page.getByRole('textbox',{name:'轨迹颜色值',exact:true}).fill('#234567')
    await page.getByRole('textbox',{name:'轨迹颜色值',exact:true}).press('Tab')
    await page.getByRole('spinbutton',{name:'轨迹线宽',exact:true}).fill('2.50')
    await page.getByRole('spinbutton',{name:'轨迹线宽',exact:true}).press('Tab')
    assert.equal(JSON.parse(await page.getByTestId('effective-style').innerText()).color,'#2563eb')
    await page.getByRole('button',{name:'保存新版本',exact:true}).click()
    await page.waitForFunction(()=>window.context.currentConfig.value?.engineering_config_version===2)
    assert.equal(mutations.length,1)
    assert.equal(mutations[0].engineeringConfigVersion,1)
    assert.equal(saved.visualization.styles.REFERENCE.ACTUAL.color,'#234567')
    assert.equal(saved.visualization.styles.REFERENCE.ACTUAL.width,2.5)
    assert.deepEqual(saved.errorModel,defaults.errorModel)
    assert.equal(JSON.parse(await page.getByTestId('effective-style').innerText()).color,'#234567')
    await page.reload()
    await page.waitForFunction(()=>window.fixtureReady&&window.context.currentConfig.value?.engineering_config_version===2)
    assert.equal(await page.getByRole('textbox',{name:'轨迹颜色值',exact:true}).inputValue(),'#234567')
    assert.equal(JSON.parse(await page.getByTestId('effective-style').innerText()).width,2.5)
    assert.deepEqual(errors,[]);assert.deepEqual(unexpected,[])
    console.log('PASS: engineering configuration tab, automatic form edits, publish-version payload, persisted reload, shared plot defaults updated only after publication; error model preserved; isolated API only.')
  }finally{await browser.close()}
})().catch(error=>{console.error(error);process.exitCode=1})
