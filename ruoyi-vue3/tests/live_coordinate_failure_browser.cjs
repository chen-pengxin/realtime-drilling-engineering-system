// Exercise the actual live composable with isolated HTTP responses, not a user session.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const {chromium} = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const base = process.env.DRILLING_FRONTEND_URL || 'http://127.0.0.1:5173'
const hash = JSON.parse(fs.readFileSync(path.join(__dirname, '../node_modules/.vite/deps/_metadata.json'), 'utf8')).browserHash
const own = {wellbore_id:31, well_id:21, owner_field_id:1, field_id:1, actual_version_id:101, engineering_status:'DRILLING'}
const external = {wellbore_id:59, well_id:20, owner_field_id:3, field_id:3, actual_version_id:143}
const localTest = {wellbore_id:227, well_id:220, owner_field_id:14, field_id:14, actual_version_id:309}
const stations = [{md:109, inclination:1, azimuth:30, north:6477887, east:437506, tvd:109}, {md:139, inclination:2, azimuth:31, north:6477888, east:437507, tvd:139}]
const reference = {survey_version_id:101, well_id:21, quality_status:'READY', stations}
const neighbor = {wellbore_id:32, well_id:22, survey_version_id:102, usable:true, included:true, relationship_confirmed:true, coordinate_compatible:true, quality_status:'READY', source_review_status:'CONFIRMED'}
const incompatible = {wellbore_id:227, survey_version_id:309, usable:false, included:false, coordinate_compatible:false, requested_included:false}

;(async () => {
  const transformed = await (await fetch(base + '/src/views/drilling/layout/RealtimeMonitor.vue')).text()
  const liveModule = transformed.match(/from\s*['"]([^'"]*useLiveDrilling\.js[^'"]*)['"]/)[1]
  const html = `<!doctype html><html><meta charset="utf-8"><div id="app"></div><script type="module">
    import {createApp,h} from '/node_modules/.vite/deps/vue.js?v=${hash}';
    import {createPinia} from '/node_modules/.vite/deps/pinia.js?v=${hash}';
    const app=createApp({render:()=>window.live?h('output',JSON.stringify({md:window.live.latest.value?.md,tracks:window.live.trajectories.value.length,error:window.live.error.value})):null});app.use(createPinia());
    const {default:useUserStore}=await import('/src/store/modules/user.js');const user=useUserStore();user.permissions=['*:*:*'];user.roles=['admin'];
    const {useDrillingContext}=await import('/src/views/drilling/composables/useDrillingContext.js');useDrillingContext().fieldId.value=1;
    const {useLiveDrilling}=await import('${liveModule}');window.live=useLiveDrilling();app.mount('#app');window.previewReady=true;
  </script></html>`
  const browser = await chromium.launch({headless:true, executablePath:process.env.DRILLING_BROWSER_EXECUTABLE})
  try {
    async function scenario({links = [neighbor, incompatible], failNeighbors = false, failVersion = false, holdNeighbors = false} = {}) {
      const page = await browser.newPage(), errors = [], unexpected = [], scans = []
      let release
      const held = holdNeighbors ? new Promise(resolve => {release = resolve}) : Promise.resolve()
      page.on('pageerror', error => errors.push(String(error)))
      await page.addInitScript(() => localStorage.setItem('drilling.liveBoreId.1', '59'))
      await page.route('**/__live_coordinate_test__', route => route.fulfill({contentType:'text/html', body:html}))
      await page.route('**/dev-api/**', async route => {
        const url = new URL(route.request().url()), endpoint = url.pathname.replace('/dev-api', '')
        if (endpoint.endsWith('/registry/wellbores')) return route.fulfill({json:[external, localTest, own]})
        if (endpoint.endsWith('/registry/versions/101')) return route.fulfill({json:reference})
        if (endpoint.endsWith('/registry/engine-confirmation')) return route.fulfill({json:{confirmed:true, configHash:'same-config', configuration:{prediction:{}}}})
        if (endpoint.endsWith('/registry/wellbores/31/neighbors')) {
          await held
          return failNeighbors ? route.fulfill({status:400, json:{code:400, msg:'计算服务拒绝请求：{"detail":"跨井归算缺少明确源/目标CRS"}'}}) : route.fulfill({json:links})
        }
        if (endpoint.endsWith('/registry/versions/102')) {
          assert.equal(url.searchParams.get('viewReferenceId'), '101')
          return failVersion ? route.fulfill({status:500,json:{code:500,msg:'邻井版本读取失败'}}) : route.fulfill({json:{survey_version_id:102, stations, quality_status:'READY'}})
        }
        if (endpoint.endsWith('/anti-collision/network-scan')) {
          const input = route.request().postDataJSON();scans.push(input)
          return route.fulfill({json:{neighbor_count:1,operational_risk_level:'BLUE',results:[],failed_pairs:[]}})
        }
        unexpected.push(endpoint);return route.fulfill({status:500,json:{code:500,msg:'unexpected request'}})
      })
      await page.goto(base + '/__live_coordinate_test__')
      await page.waitForFunction(() => window.previewReady)
      await page.evaluate(() => {window.refreshDone=false;window.live.refresh({force:true}).finally(()=>{window.refreshDone=true})})
      await page.waitForFunction(() => window.live.latest.value?.md===139)
      assert.equal(await page.evaluate(() => window.live.boreId.value), 31)
      assert.deepEqual(await page.evaluate(() => window.live.bores.value.map(row=>row.wellbore_id)), [31])
      assert.equal(await page.evaluate(() => localStorage.getItem('drilling.liveBoreId.1')), '31')
      if (holdNeighbors) {
        assert.equal(await page.evaluate(() => window.live.loading.value), true)
        assert.equal(await page.evaluate(() => window.live.trajectories.value.length), 1)
        assert.match(await page.locator('output').innerText(), /"md":139/)
        release()
      }
      await page.waitForFunction(() => window.refreshDone)
      assert.deepEqual(errors, []);assert.deepEqual(unexpected, [])
      assert.equal(await page.locator('.el-message--error').count(), 0)
      const snapshot = await page.evaluate(() => ({error:window.live.error.value,notice:window.live.neighborNotice.value,tracks:window.live.trajectories.value.length,network:window.live.network.value}))
      await page.close()
      return {snapshot, scans}
    }

    const failed = await scenario({holdNeighbors:true, failNeighbors:true})
    assert.equal(failed.snapshot.error, '跨井归算缺少明确源/目标CRS')
    assert.equal(failed.snapshot.tracks, 1);assert.equal(failed.snapshot.network, null)
    const successful = await scenario()
    assert.equal(successful.snapshot.error, '');assert.equal(successful.snapshot.tracks, 2)
    assert.equal(successful.snapshot.network.neighbor_count, 1)
    assert.equal(successful.scans.length, 1)
    assert.deepEqual(successful.scans[0].neighbors, [{well_id:22,survey_version_id:102}])
    assert.deepEqual(successful.scans[0].reference.stations, stations)
    assert.match(successful.snapshot.notice, /1 个井眼坐标基准不一致/)
    const brokenVersion = await scenario({failVersion:true})
    assert.match(brokenVersion.snapshot.error, /部分邻井轨迹读取失败/)
    assert.equal(brokenVersion.snapshot.tracks, 1);assert.equal(brokenVersion.scans.length, 0)
    const manuallyIncluded = await scenario({links:[neighbor,{...incompatible,requested_included:true}]})
    assert.match(manuallyIncluded.snapshot.error, /已选邻井坐标基准不一致/)
    assert.equal(manuallyIncluded.snapshot.network, null);assert.equal(manuallyIncluded.scans.length, 0)
    console.log('PASS: stale cross-field selection corrected; measured tracks render before delayed/failed neighbors; unrelated local coordinates excluded; compatible neighbors evaluated; failed or explicitly selected incompatible neighbors cannot produce a safe result.')
  } finally {await browser.close()}
})().catch(error => {console.error(error);process.exitCode=1})
