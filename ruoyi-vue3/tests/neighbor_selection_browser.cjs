// Render the production selector with isolated fixture data. No login or
// business API writes are performed; run against the Vite development server.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const base = process.env.DRILLING_FRONTEND_URL || 'http://127.0.0.1:5173'
const out = path.resolve(process.env.DRILLING_BROWSER_REPORT || '../.local-runtime/neighbor-selector')
fs.mkdirSync(out, { recursive: true })
const html = `<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<link rel="stylesheet" href="/node_modules/element-plus/dist/index.css">
<style>body{margin:0;background:#f5f7fa;font:14px Arial,sans-serif;color:#303133}main{width:310px;padding:16px;background:white;box-sizing:border-box}h3{font-size:15px;margin:0 0 14px}</style>
<main><h3>参与扫描的邻井</h3><div id="app"></div></main><script type="module">
import {createApp,h,ref} from '/node_modules/.vite/deps/vue.js';
import {createPinia} from '/node_modules/.vite/deps/pinia.js';
import ElementPlus from '/node_modules/.vite/deps/element-plus.js';
import * as icons from '/node_modules/.vite/deps/@element-plus_icons-vue.js';
import Selector from '/src/views/drilling/layout/NeighborSelector.vue';
const common={block_id:1,block_name:'测试区块',field_id:1,field_name:'测试井场',version_no:1,trajectory_kind:'ACTUAL',quality_status:'READY',source_review_status:'CONFIRMED',usable:true,within_search_range:true,version_policy:'CURRENT_ACTUAL',manual_search_radius_m:3000};
const rows=ref([{...common,well_id:1,well_name:'井1',wellbore_id:11,wellbore_name:'主井眼',survey_version_id:21,included:true,relationship_confirmed:true,relationship_source:'AUTO',horizontal_box_distance_m:0},{...common,well_id:2,well_name:'井2',wellbore_id:12,wellbore_name:'主井眼',survey_version_id:22,included:false,relationship_confirmed:false,relationship_source:'AVAILABLE',horizontal_box_distance_m:1500}]);
window.neighborRows=rows;window.saveCount=0;
const app=createApp({render:()=>h(Selector,{modelValue:rows.value,fieldId:1,onSave:()=>window.saveCount++})});
app.use(createPinia()).use(ElementPlus);for(const [key,component]of Object.entries(icons))app.component(key,component);app.mount('#app');window.previewReady=true;
</script></html>`

;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.DRILLING_BROWSER_EXECUTABLE })
  try {
    const page = await browser.newPage({ viewport: { width: 800, height: 1000 } })
    const errors = []
    page.on('pageerror', e => errors.push(String(e)))
    await page.route('**/__neighbor_preview__', route => route.fulfill({ contentType: 'text/html', body: html }))
    await page.goto(base + '/__neighbor_preview__')
    await page.waitForFunction(() => window.previewReady, null, { timeout: 30000 })
    assert.match(await page.locator('body').innerText(), /已选 1 \/ 2/)
    assert.equal(await page.getByRole('button', { name: '保存邻井关系' }).isDisabled(), true)
    // Expand the actual hierarchy to expose both the automatic and manual well.
    for (let i = 0; i < 4; i++) {
      const icons = page.locator('.el-tree-node__expand-icon:not(.is-leaf):not(.expanded)')
      if (await icons.count()) await icons.first().click()
    }
    assert.match(await page.locator('body').innerText(), /1500 m/)
    assert.equal(await page.getByText('自动', { exact: true }).count(), 1)
    assert.doesNotMatch(await page.locator('body').innerText(), /候选关系|尚未进行防碰评价|勾选并保存后/)
    await page.screenshot({ path: path.join(out, 'neighbors-desktop.png'), fullPage: true })
    await page.getByRole('button', { name: '全选', exact: true }).click()
    assert.match(await page.locator('body').innerText(), /已选 2 \/ 2/)
    assert.equal(await page.evaluate(() => window.neighborRows.value[1].selection_dirty), true)
    await page.getByRole('button', { name: '清空', exact: true }).click()
    assert.match(await page.locator('body').innerText(), /已选 0 \/ 2/)
    await page.getByRole('button', { name: '保存邻井关系' }).click()
    assert.equal(await page.evaluate(() => window.saveCount), 1)
    // A disabled stale relationship still needs a way to save its exclusion.
    await page.evaluate(() => {
      const sample=window.neighborRows.value[0]
      window.neighborRows.value=[{...sample,well_id:3,well_name:'井3',wellbore_id:13,survey_version_id:23,usable:false,included:false,coordinate_compatible:false,requested_included:true,selection_dirty:false}]
    })
    await page.waitForTimeout(100)
    for (let i=0;i<4;i++) {
      const collapsed=page.locator('.el-tree-node__expand-icon:not(.is-leaf):not(.expanded)')
      if(await collapsed.count())await collapsed.first().click()
    }
    assert.match(await page.locator('body').innerText(), /坐标基准不一致/)
    await page.getByRole('button', {name:'取消选择',exact:true}).click()
    assert.equal(await page.evaluate(() => window.neighborRows.value[0].requested_included), false)
    assert.equal(await page.evaluate(() => window.neighborRows.value[0].selection_dirty), true)
    await page.getByRole('button', {name:'保存邻井关系'}).click()
    assert.equal(await page.evaluate(() => window.saveCount), 2)
    await page.setViewportSize({ width: 390, height: 844 })
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
    await page.screenshot({ path: path.join(out, 'neighbors-mobile.png'), fullPage: true })
    assert.deepEqual(errors, [])
    console.log('PASS: automatic selection, manual additions, clear/save, incompatible saved-neighbor exclusion, concise labels and mobile width (isolated component data).')
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
