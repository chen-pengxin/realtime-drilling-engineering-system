// Exercise the production directory component with isolated engineering data.
// All business API calls are intercepted; the real database is never modified.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const base = process.env.DRILLING_FRONTEND_URL || 'http://127.0.0.1:5173'
const output = path.resolve(process.env.DRILLING_BROWSER_REPORT || '../.local-runtime/catalog-browser')
fs.mkdirSync(output, { recursive: true })
const dependencyHash = JSON.parse(fs.readFileSync(path.join(__dirname, '../node_modules/.vite/deps/_metadata.json'), 'utf8')).browserHash
const nodes = [
  { node_id: -1000000000001, node_type: 'BLOCK', scope_type: 'BLOCK', block_id: 1, node_name: '测试区块甲', node_code: 'B1' },
  { node_id: -2000000000001, parent_id: -1000000000001, node_type: 'FIELD', scope_type: 'FIELD', block_id: 1, field_id: 1, node_name: '测试井场甲', node_code: 'F1' },
  { node_id: 11, parent_id: -2000000000001, node_type: 'WELL', field_id: 1, well_id: 21, node_name: '测试井甲', node_code: 'W1', row_version: 2 },
  { node_id: 12, parent_id: 11, node_type: 'WELLBORE', field_id: 1, well_id: 21, wellbore_id: 31, node_name: '主井眼', node_code: 'W1-0', row_version: 3, engineering_status: 'DRILLING' },
  { node_id: 13, parent_id: 12, node_type: 'PLAN', field_id: 1, well_id: 21, wellbore_id: 31, node_name: '设计方案甲', node_code: 'P1', row_version: 1 },
  { node_id: -1000000000002, node_type: 'BLOCK', scope_type: 'BLOCK', block_id: 2, node_name: '测试区块乙', node_code: 'B2' },
  { node_id: -2000000000002, parent_id: -1000000000002, node_type: 'FIELD', scope_type: 'FIELD', block_id: 2, field_id: 2, node_name: '测试井场乙', node_code: 'F2' }
].map(n => ({ status: 'ACTIVE', ...n }))
const fields = nodes.filter(n => n.node_type === 'FIELD').map(n => ({ ...n, field_name: n.node_name, block_name: nodes.find(b => b.block_id === n.block_id && b.node_type === 'BLOCK').node_name }))
const html = `<!doctype html><html lang="zh-CN"><meta charset="utf-8">
<link rel="stylesheet" href="/node_modules/element-plus/dist/index.css">
<style>body{margin:0;color:#303133;background:#f5f7fa;font-family:Arial,sans-serif}.app-container{padding:16px}.sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0,0,0,0)}.el-form-item__content{min-width:0}.el-dialog{max-width:calc(100vw - 32px)}</style>
<div id="app"></div><script type="module">
import {createApp,h} from '/node_modules/.vite/deps/vue.js?v=${dependencyHash}';
import {createPinia} from '/node_modules/.vite/deps/pinia.js?v=${dependencyHash}';
import {createRouter,createMemoryHistory} from '/node_modules/.vite/deps/vue-router.js?v=${dependencyHash}';
import ElementPlus from '/node_modules/.vite/deps/element-plus.js?v=${dependencyHash}';
import * as icons from '/node_modules/.vite/deps/@element-plus_icons-vue.js?v=${dependencyHash}';
localStorage.setItem('drilling.fieldId','1');
let Catalog;
const app=createApp({render:()=>Catalog?h(Catalog):null}),pinia=createPinia();app.use(pinia);
const {default:useUserStore}=await import('/src/store/modules/user.js');
const user=useUserStore();user.permissions=['*:*:*'];user.roles=['admin'];
const {useDrillingContext}=await import('/src/views/drilling/composables/useDrillingContext.js');
const context=useDrillingContext();context.fieldId.value=1;context.fields.value=[];
window.catalogContext=context;
Catalog=(await import('/src/views/drilling/basic/CatalogPage.vue')).default;
const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{render:()=>null}}]});app.use(router).use(ElementPlus);
app.component('Pagination',{template:'<div class="pagination-fixture"></div>'});
app.directive('hasPermi',{mounted(el,{value}){if(!user.permissions.includes('*:*:*')&&!value.some(p=>user.permissions.includes(p)))el.remove()}});
for(const [key,component]of Object.entries(icons))app.component(key,component);
await router.push('/drilling/basic/catalog');await router.isReady();app.mount('#app');window.previewReady=true;
</script></html>`

;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.DRILLING_BROWSER_EXECUTABLE })
  try {
    const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } })
    page.setDefaultTimeout(10000)
    const errors = [], calls = [], creates = [], edits = [], unexpected = []
    page.on('pageerror', e => { errors.push(String(e)); console.error('Browser:', String(e)) })
    page.on('console', message => { if(message.type()==='error') console.error('Browser console:', message.text()) })
    page.on('response', response => { if(response.status()>=400) console.error('HTTP', response.status(), response.url()) })
    await page.route('**/__catalog_preview__', route => route.fulfill({ contentType: 'text/html', body: html }))
    await page.route('**/dev-api/**', async route => {
      const request = route.request(), url = new URL(request.url()), endpoint = url.pathname.replace('/dev-api', '')
      const method = request.method()
      calls.push(`${method} ${endpoint}`)
      let body
      if (endpoint === '/api/drilling/fields') body = fields
      else if (/\/fields\/\d+\/wells$/.test(endpoint)) body = []
      else if (endpoint === '/api/drilling/library/tree') body = nodes
      else if (/\/library\/nodes\/[-\d]+$/.test(endpoint)) {
        const id = Number(endpoint.split('/').at(-1))
        if (method === 'PUT') {
          const payload = request.postDataJSON(); edits.push({ endpoint, payload })
          const row = nodes.find(n => n.node_id === id)
          if (payload.expectedVersion !== row.row_version) return route.fulfill({ status: 409, json: { code: 409, msg: '资料已被他人修改，请刷新' } })
          Object.assign(row, { node_name: payload.name, status: payload.status, row_version: row.row_version + 1 })
          if (payload.engineeringStatus) row.engineering_status = payload.engineeringStatus
          body = row
        } else body = { node: nodes.find(n => n.node_id === id), children: nodes.filter(n => n.parent_id === id), files: [], revisions: [], trajectoryVersions: [], total: 0 }
      } else if (/\/library\/nodes$/.test(endpoint) && method === 'POST') {
        const payload = request.postDataJSON(); creates.push({ endpoint, payload })
        if (payload.name === '重复井') return route.fulfill({ status: 400, json: { code: 400, msg: '同一井场已有该井，请在目录中选择，不能重复创建' } })
        const parent = nodes.find(n => n.node_id === payload.parentId)
        assert.equal(payload.fieldId, parent.field_id)
        const row = { node_id: 100 + creates.length, parent_id: parent.node_id, field_id: parent.field_id, well_id: 60 + creates.length, node_type: payload.nodeType, node_name: payload.name, node_code: payload.code || payload.name, status: 'ACTIVE', row_version: 1 }
        nodes.push(row);body = row
      } else { unexpected.push(`${method} ${endpoint}`);return route.fulfill({ status: 500, json: { code: 500, msg: 'Unexpected fixture request' } }) }
      await route.fulfill({ json: body })
    })
    await page.goto(base + '/__catalog_preview__')
    await page.waitForFunction(() => window.previewReady, null, { timeout: 20000 })
    await page.getByRole('button', { name: '新增井', exact: true }).waitFor()
    const treeNode = name => page.locator('.library-tree-panel .tree-node > span').filter({ hasText: new RegExp('^' + name + '$') })
    const dialog = () => page.locator('.el-dialog:visible').last()
    assert.equal(await page.locator('.library-header').count(), 0)
    assert.doesNotMatch(await page.locator('.node-actions').innerText(), /引用已有井眼|施工状态|编辑节点/)
    await page.screenshot({ path: path.join(output, 'catalog-desktop.png'), fullPage: true })
    // Directory navigation leaves the drilling task's selected field unchanged.
    await treeNode('测试井场乙').click()
    await page.waitForTimeout(80)
    assert.equal(await page.evaluate(() => window.catalogContext.fieldId.value), 1)
    await treeNode('测试井场甲').click()
    await page.getByRole('button', { name: '新增井', exact: true }).click()
    assert.match(await dialog().innerText(), /所属区块[\s\S]*测试区块甲[\s\S]*所属井场[\s\S]*测试井场甲/)
    assert.equal(await dialog().getByText('节点类型', { exact: true }).count(), 0)
    await page.waitForTimeout(300)
    await page.screenshot({ path: path.join(output, 'create-well-ownership.png'), fullPage: true })
    await dialog().locator('.el-form-item').filter({ hasText: /^井名/ }).getByRole('textbox').fill('重复井')
    await dialog().getByRole('button', { name: '确 定', exact: true }).click()
    await page.getByText('同一井场已有该井，请在目录中选择，不能重复创建', { exact: true }).waitFor()
    assert.equal(await page.locator('.el-message--error').count(), 1)
    assert.equal(await dialog().isVisible(), true)
    await dialog().locator('.el-form-item').filter({ hasText: /^井名/ }).getByRole('textbox').fill('新增测试井')
    // A global task context change cannot redirect a form already opened.
    await page.evaluate(() => { window.catalogContext.fieldId.value = 2 })
    await dialog().getByRole('button', { name: '确 定', exact: true }).click()
    await page.getByRole('button', { name: '新增井眼', exact: true }).waitFor()
    assert.equal(creates.at(-1).endpoint, '/api/drilling/fields/1/library/nodes')
    assert.equal(creates.at(-1).payload.parentId, -2000000000001)
    await page.getByRole('button', { name: '编辑井', exact: true }).click()
    assert.match(await dialog().innerText(), /测试区块甲 \/ 测试井场甲/)
    assert.doesNotMatch(await dialog().innerText(), /并发版本/)
    await dialog().locator('.el-form-item').filter({ hasText: /^井名/ }).getByRole('textbox').fill('修改后的测试井')
    await dialog().getByRole('button', { name: '保 存', exact: true }).click()
    await treeNode('修改后的测试井').waitFor()
    assert.equal(edits.at(-1).payload.expectedVersion, 1)
    assert.equal(edits.at(-1).endpoint, '/api/drilling/fields/1/library/nodes/102')
    // Files and child wells are mutually exclusive lists.
    await treeNode('测试井场甲').click()
    await page.getByRole('tab', { name: '文件管理', exact: true }).click()
    assert.equal(await page.locator('.library-content .el-table').count(), 1)
    assert.doesNotMatch(await page.locator('.library-content .el-table').innerText(), /修改后的测试井/)
    await treeNode('主井眼').click()
    assert.equal(await page.getByRole('button', { name: '施工状态', exact: true }).count(), 0)
    await page.getByRole('button', { name: '编辑井眼', exact: true }).click()
    assert.match(await dialog().innerText(), /井眼状态[\s\S]*正在钻进/)
    await page.waitForTimeout(300)
    await page.screenshot({ path: path.join(output, 'edit-wellbore.png'), fullPage: true })
    await dialog().getByRole('button', { name: '取 消', exact: true }).click()
    await page.getByRole('button', { name: '添加资料', exact: true }).hover()
    await page.getByRole('menuitem', { name: '新增实钻测斜资料', exact: true }).click()
    assert.match(await dialog().innerText(), /新增实钻测斜资料[\s\S]*所属井眼[\s\S]*主井眼/)
    await dialog().getByRole('button', { name: '取 消', exact: true }).click()
    await page.setViewportSize({ width: 390, height: 844 })
    await treeNode('测试井场甲').click()
    await page.waitForTimeout(300)
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth > innerWidth), false)
    await page.screenshot({ path: path.join(output, 'catalog-mobile.png'), fullPage: true })
    assert.deepEqual(unexpected, [])
    assert.deepEqual(errors, [])
    assert.equal(calls.some(call => /neighbors|horizontal-bounds|confirmation|anticollision/.test(call)), false)
    console.log('PASS: captured ownership, no global field changes, create/edit, one error message, exclusive lists, wellbore state, clear actions and mobile layout; no calculation requests or database writes.')
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
