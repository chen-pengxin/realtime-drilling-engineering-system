// Exercise real Vue components with isolated API responses, never business data.
const assert = require('node:assert/strict')
const fs = require('node:fs')
const path = require('node:path')
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const base = process.env.DRILLING_FRONTEND_URL || 'http://127.0.0.1:5173'
const output = path.resolve(process.env.DRILLING_BROWSER_REPORT || '../.local-runtime/catalog-actions-browser')
fs.mkdirSync(output, { recursive: true })
const hash = JSON.parse(fs.readFileSync(path.join(__dirname, '../node_modules/.vite/deps/_metadata.json'), 'utf8')).browserHash
const fieldNode = -2000000000001
const nodes = [
  { node_id: -1000000000001, node_type: 'BLOCK', scope_type: 'BLOCK', block_id: 1, node_name: '测试区块' },
  { node_id: fieldNode, parent_id: -1000000000001, node_type: 'FIELD', scope_type: 'FIELD', field_id: 1, block_id: 1, node_name: '测试井场' },
  { node_id: 11, parent_id: fieldNode, node_type: 'WELL', field_id: 1, well_id: 21, node_name: '测试井', node_code: 'W1' },
  { node_id: 12, parent_id: 11, node_type: 'WELLBORE', field_id: 1, well_id: 21, wellbore_id: 31, node_name: '主井眼', node_code: 'W1-0' },
  { node_id: 13, parent_id: 12, node_type: 'PLAN', field_id: 1, well_id: 21, wellbore_id: 31, node_name: '设计方案', node_code: 'P1' },
  { node_id: 15, parent_id: fieldNode, node_type: 'WELL', field_id: 1, well_id: 22, node_name: '引用井', node_code: 'W2', properties: { reference_only: true } },
  { node_id: 16, parent_id: 15, node_type: 'WELLBORE', field_id: 1, well_id: 22, wellbore_id: 32, node_name: '引用井眼', node_code: 'W2-0', properties: { reference_only: true } }
].map(n => ({ status: 'ACTIVE', row_version: 1, ...n }))
const sourceFile = { node_id: 13, field_id: 1, source_field_id: 1, document_id: 81, source_id: 71, file_id: 61, edition_no: 2, original_name: '原始设计.pdf', document_role: 'TRAJECTORY_REPORT', trajectory_kind: 'DESIGN' }
const versions = [
  { survey_version_id: 101, wellbore_id: 31, trajectory_kind: 'ACTUAL', version_no: 1, is_current: 0 },
  { survey_version_id: 102, wellbore_id: 31, trajectory_kind: 'ACTUAL', version_no: 2, is_current: 1 }
].map(v => ({ ...v, well_id: 21, well_code: 'W1', wellbore_code: 'W1-0', source_format: 'txt', original_name: '原始测斜.txt', station_count: 2, max_md: 100 }))
function html(permissions) {
  return `<!doctype html><html lang="zh-CN"><meta charset="utf-8"><link rel="stylesheet" href="/node_modules/element-plus/dist/index.css">
  <style>body{margin:0;color:#303133;background:#f5f7fa;font-family:Arial,sans-serif}.app-container{padding:16px}.sr-only{position:absolute;width:1px;height:1px;overflow:hidden;clip:rect(0,0,0,0)}.el-form-item__content{min-width:0}</style>
  <div id="app"></div><script type="module">
  import {createApp,h} from '/node_modules/.vite/deps/vue.js?v=${hash}';
  import {createPinia} from '/node_modules/.vite/deps/pinia.js?v=${hash}';
  import {createRouter,createMemoryHistory} from '/node_modules/.vite/deps/vue-router.js?v=${hash}';
  import ElementPlus from '/node_modules/.vite/deps/element-plus.js?v=${hash}';
  import * as icons from '/node_modules/.vite/deps/@element-plus_icons-vue.js?v=${hash}';
  const app=createApp({render:()=>h(Catalog)});app.use(createPinia());
  const {default:useUserStore}=await import('/src/store/modules/user.js');const user=useUserStore();user.permissions=${JSON.stringify(permissions)};user.roles=['engineer'];
  const {useDrillingContext}=await import('/src/views/drilling/composables/useDrillingContext.js');const context=useDrillingContext();context.fieldId.value=1;context.fields.value=[];window.context=context;
  const Catalog=(await import('/src/views/drilling/basic/CatalogPage.vue')).default;
  const router=createRouter({history:createMemoryHistory(),routes:[{path:'/:pathMatch(.*)*',component:{render:()=>null}}]});app.use(router).use(ElementPlus);
  app.component('Pagination',{template:'<div></div>'});for(const [name,component] of Object.entries(icons))app.component(name,component);
  app.directive('hasPermi',{mounted(el,{value}){if(!user.permissions.includes('*:*:*')&&!value.some(p=>user.permissions.includes(p)))el.remove()}});
  await router.push('/drilling/basic/catalog');await router.isReady();app.mount('#app');window.previewReady=true;
  </script></html>`
}

;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.DRILLING_BROWSER_EXECUTABLE })
  const allErrors = []
  async function fixture(permissions) {
    const page = await browser.newPage({ viewport: { width: 1600, height: 1000 } })
    page.setDefaultTimeout(12000)
    const calls = [], mutations = [], unexpected = []
    page.on('pageerror', error => allErrors.push(String(error)))
    await page.route('**/__catalog_actions__', route => route.fulfill({ contentType: 'text/html', body: html(permissions) }))
    await page.route('**/dev-api/**', async route => {
      const request = route.request(), endpoint = new URL(request.url()).pathname.replace('/dev-api', ''), method = request.method()
      calls.push({ endpoint, method })
      if (method !== 'GET') mutations.push({ endpoint, method, body: request.postData() })
      let body
      if (endpoint === '/api/drilling/fields') body = [{ field_id: 1, field_name: '测试井场', block_id: 1, block_name: '测试区块' }]
      else if (/\/fields\/\d+\/wells$/.test(endpoint)) body = []
      else if (endpoint === '/api/drilling/library/tree') body = nodes
      else if (/\/library\/nodes\/[-\d]+$/.test(endpoint) && method === 'GET') {
        const id = Number(endpoint.split('/').at(-1))
        body = { node: nodes.find(n => n.node_id === id), files: [sourceFile], total: 1, trajectoryVersions: versions, revisions: [], children: [] }
      } else if (endpoint === `/api/drilling/fields/1/library/nodes/${fieldNode}/documents` && method === 'POST') body = { documentId: 82 }
      else if (/\/registry\/sources$/.test(endpoint) && method === 'GET') body = { rows: [{ source_id: 71, original_name: '井场说明.pdf', source_format: 'pdf', parse_status: 'PARSED' }], total: 1 }
      else if (/\/registry\/sources$/.test(endpoint) && method === 'POST') body = { source_id: 72, field_id: 1, original_name: '井场说明.pdf', edition_no: 3 }
      else if (/\/registry\/sources\/72\/review$/.test(endpoint)) body = { status: 'CONFIRMED' }
      else if (/\/library\/documents\/81$/.test(endpoint)) body = { ...sourceFile, latest_edition_no: 2 }
      else if (/\/registry\/versions\/10[12]$/.test(endpoint)) body = { source_file_id: 61, reference: {}, stations: [{ md: 0, inclination: 0, azimuth: 0 }, { md: 100, inclination: 2, azimuth: 20 }] }
      else if (/\/registry\/wellbores\/31\/neighbors$/.test(endpoint)) body = []
      else if (/\/registry\/versions\/101\/activate$/.test(endpoint)) body = {}
      else { unexpected.push(`${method} ${endpoint}`); return route.fulfill({ status: 500, json: { code: 500, msg: 'Unexpected test request' } }) }
      await route.fulfill({ json: body })
    })
    await page.goto(`${base}/__catalog_actions__`)
    await page.waitForFunction(() => window.previewReady)
    await page.locator('.library-content .el-table').waitFor()
    const tree = name => page.locator('.library-tree-panel .el-tree-node__content').filter({ has: page.locator('.tree-node>span', { hasText: new RegExp(`^${name}$`) }) })
    const dialog = () => page.locator('.el-dialog:visible').last()
    await tree('主井眼').click()
    await page.getByRole('tab', { name: '测斜数据', exact: true }).waitFor()
    return { page, calls, mutations, unexpected, tree, dialog }
  }
  try {
    // Read-only accounts see records, but no mutation actions in lists or details.
    {
      const f = await fixture(['drilling:well:list', 'drilling:file:list'])
      await f.page.getByRole('tab', { name: '测斜数据', exact: true }).click()
      assert.equal(await f.page.getByRole('button', { name: '设为当前', exact: true }).count(), 0)
      await f.page.getByRole('button', { name: '数据', exact: true }).first().click()
      await f.dialog().getByRole('button', { name: '轨道归算表', exact: true }).waitFor()
      for (const name of ['完整新版本', '增量导入', '复测修订', '修订测量参数']) assert.equal(await f.dialog().getByRole('button', { name, exact: true }).count(), 0)
      assert.deepEqual(f.mutations, []); assert.deepEqual(f.unexpected, [])
      await f.page.close()
    }
    // Data-only permission retains the reduction table without exposing source-file exports.
    {
      const f = await fixture(['drilling:well:list'])
      assert.equal(await f.page.getByRole('tab', { name: '文件管理', exact: true }).count(), 0)
      await f.page.getByRole('tab', { name: '测斜数据', exact: true }).click()
      assert.equal(await f.page.getByRole('button', { name: '归算表', exact: true }).count(), 2)
      for (const name of ['来源原件', '来源格式', '资料包', '设为当前']) assert.equal(await f.page.getByRole('button', { name, exact: true }).count(), 0)
      await f.page.getByRole('button', { name: '数据', exact: true }).first().click()
      await f.dialog().getByRole('button', { name: '轨道归算表', exact: true }).waitFor()
      assert.equal(await f.dialog().getByRole('button', { name: '来源原件', exact: true }).count(), 0)
      assert.deepEqual(f.unexpected, []); await f.page.close()
    }
    // Linking a file directly to a field never calls the create-well endpoint.
    {
      const f = await fixture(['drilling:well:list', 'drilling:file:list', 'drilling:file:add'])
      await f.page.getByRole('tab', { name: '文件管理', exact: true }).click()
      assert.equal(await f.page.getByRole('button', { name: '上传文件', exact: true }).count(), 0)
      await f.page.getByRole('button', { name: '选用已有文件', exact: true }).click()
      await f.dialog().getByRole('button', { name: '添加', exact: true }).waitFor()
      assert.match(await f.dialog().innerText(), /所属位置：测试区块 \/ 测试井场/)
      await f.page.evaluate(() => { window.context.fieldId.value = 2 })
      await f.dialog().getByRole('button', { name: '添加', exact: true }).click()
      await f.page.getByText('文件已添加', { exact: true }).waitFor()
      assert.equal(f.mutations.length, 1)
      assert.equal(f.mutations[0].endpoint, `/api/drilling/fields/1/library/nodes/${fieldNode}/documents`)
      assert.deepEqual(JSON.parse(f.mutations[0].body), { sourceId: 71, documentRole: 'REFERENCE', trajectoryKind: '' })
      assert.deepEqual(f.unexpected, []); await f.page.close()
    }
    // Upload uses the selected field as the default and retains the original filename.
    {
      const f = await fixture(['drilling:well:list', 'drilling:file:list', 'drilling:well:survey:import'])
      await f.page.getByRole('tab', { name: '文件管理', exact: true }).click()
      await f.page.getByRole('button', { name: '上传文件', exact: true }).click()
      assert.match(await f.dialog().innerText(), /工程层级[\s\S]*测试区块 \/ 测试井场/)
      await f.dialog().locator('input[type=file]').setInputFiles({ name: '井场说明.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-test') })
      await f.dialog().getByRole('button', { name: '上传并关联', exact: true }).click()
      await f.page.getByText('已关联入库 1 个文件', { exact: true }).waitFor()
      assert.equal(f.mutations.length, 1)
      assert.match(f.mutations[0].body, /name="nodeId"\r\n\r\n-2000000000001/)
      assert.match(f.mutations[0].body, /filename="井场说明.pdf"/)
      await f.page.screenshot({ path: path.join(output, 'field-upload.png'), fullPage: true })
      await f.dialog().getByRole('button', { name: '完成', exact: true }).click()
      await f.page.getByRole('button', { name: '上传文件', exact: true }).waitFor()
      assert.deepEqual(f.unexpected, []); await f.page.close()
    }
    // New file editions are locked to the file row's node, not the aggregate field.
    {
      const f = await fixture(['*:*:*'])
      await f.page.getByRole('tab', { name: '文件管理', exact: true }).click()
      await f.page.getByRole('button', { name: '上传新版本', exact: true }).click()
      await f.dialog().getByText('最新文件版次', { exact: true }).waitFor()
      assert.equal(await f.dialog().getByRole('button', { name: '应用到全部文件', exact: true }).count(), 0)
      await f.dialog().locator('input[type=file]').setInputFiles({ name: '更新设计.pdf', mimeType: 'application/pdf', buffer: Buffer.from('%PDF-new') })
      await f.dialog().getByRole('button', { name: '保存新文件版本', exact: true }).click()
      await f.page.getByText('已保存第 3 版文件', { exact: true }).waitFor()
      assert.match(f.mutations[0].body, /name="nodeId"\r\n\r\n13/)
      assert.match(f.mutations[0].body, /name="documentId"\r\n\r\n81/)
      assert.match(f.mutations[0].body, /name="expectedEditionNo"\r\n\r\n2/)
      assert.deepEqual(f.unexpected, []); await f.page.close()
    }
    // References cannot become writable merely because an administrator opens them.
    {
      const f = await fixture(['*:*:*'])
      await f.tree('引用井眼').click()
      for (const name of ['上传文件', '选用已有文件', '编辑井眼', '添加资料', '修改系统名称', '上传新版本']) assert.equal(await f.page.getByRole('button', { name, exact: true }).count(), 0)
      await f.page.getByRole('tab', { name: '测斜数据', exact: true }).click()
      assert.equal(await f.page.getByRole('button', { name: '设为当前', exact: true }).count(), 0)
      assert.deepEqual(f.unexpected, []); await f.page.close()
    }
    assert.deepEqual(allErrors, [])
    console.log('PASS: read-only/data-only/file-management permissions, field upload, direct file linking, frozen field context, locked file editions, and reference-only actions; no business database writes.')
  } finally { await browser.close() }
})().catch(error => { console.error(error); process.exitCode = 1 })
