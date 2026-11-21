/* External timing experiment. Only run against the disposable verification DB. */
const fs = require('node:fs')
const path = require('node:path')
const assert = require('node:assert/strict')
const crypto = require('node:crypto')
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const root = path.resolve(__dirname, '../..')
const local = path.join(root, '.local-runtime/model-comparison')
const env = JSON.parse(fs.readFileSync(path.join(local, 'environment.json')))
assert.match(env.database, /^drilling_verify_[a-z0-9_]+$/)
assert.equal(env.java_port, 18080)
assert.equal(env.frontend_port, 15173)
const backend = 'http://127.0.0.1:18080'
const frontend = 'http://127.0.0.1:15173'
const output = path.resolve(process.env.DRILLING_TIMING_OUTPUT || path.join(root, 'docs/verification/replanning-model-comparison/browser'))
fs.mkdirSync(output, { recursive: true })
const rows = [], errors = []
const models = ['minimum_curvature', 'constant_toolface']
const labels = ['最小曲率平面圆弧', '恒工具面恒曲率空间曲线']
const scenes = (process.env.DRILLING_TIMING_SCENES || 'ABC').split('')

;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.DRILLING_BROWSER_EXECUTABLE, args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-webgl', '--enable-unsafe-swiftshader'] })
  try {
    const context = await browser.newContext({ viewport: { width: 1700, height: 1080 } })
    const sessionFile = path.join(local, 'session.json')
    if (fs.existsSync(sessionFile)) {
      await context.addCookies(JSON.parse(fs.readFileSync(sessionFile)))
    } else {
      const login = await context.request.post(backend + '/login', { form: { username: 'admin', password: '123456', rememberMe: 'false' } })
      assert.equal((await login.json()).code, 0, 'isolated bootstrap login failed')
      const changed = await context.request.put(backend + '/api/access/me/password', { data: { oldPassword: '123456', newPassword: 'Qa' + crypto.randomBytes(6).toString('hex') + '7!' } })
      assert.equal((await changed.json()).code, 0, 'isolated initial password change failed')
      await context.addCookies([{ name: 'Admin-Token', value: 'shiro-session', url: frontend }])
      fs.writeFileSync(sessionFile, JSON.stringify(await context.cookies()), { mode: 0o600 })
    }
    const me = await context.request.get(backend + '/api/access/me')
    assert.equal((await me.json()).code, 0, 'isolated session invalid')
    for (const scene of scenes) {
      const field = 'ABC'.indexOf(scene) + 1, bore = (field - 1) * 10 + 1
      for (let modelIndex = 0; modelIndex < models.length; modelIndex++) {
        const page = await context.newPage()
        page.setDefaultTimeout(30000)
        const cdp = await context.newCDPSession(page)
        await cdp.send('Network.enable', { maxTotalBufferSize: 268435456, maxResourceBufferSize: 134217728 })
        page.on('pageerror', e => errors.push(String(e)))
        // A timing experiment must not overlap the 30s background live scan.
        // The normal page/task/visualization requests remain real.
        let backgroundRequests = 0
        await page.route('**/api/algorithm/network-scan', route => { backgroundRequests++; return route.fulfill({ status: 200, contentType: 'application/json', body: '{"status":"COMPLETED","results":[],"summary":{},"alerts":[]}' }) })
        await page.addInitScript(id => {
          localStorage.setItem('drilling.fieldId', String(id))
          const originalOpen = XMLHttpRequest.prototype.open
          XMLHttpRequest.prototype.open = function(method, url, ...args) {
            if (String(url).includes('/trajectory-plan/solve')) this.addEventListener('load', () => {
              window.__replyReceived = performance.now()
              window.__solveReply = JSON.parse(this.responseText)
            })
            return originalOpen.call(this, method, url, ...args)
          }
        }, field)
        await page.goto(frontend + '/drilling/tasks/replanning?wellboreId=' + bore, { waitUntil: 'domcontentloaded' })
        await page.waitForFunction(() => document.querySelector('.task-form'))
        const dialog = page.getByRole('dialog', { name: '确认工程评价配置' })
        await page.waitForFunction(() => [...document.querySelectorAll('.js-plotly-plot')].filter(p => p.data?.length >= 11).length === 3, null, { timeout: 90000 })
        await page.waitForTimeout(1000)
        if (await dialog.isVisible()) {
          await dialog.locator('.confirm-summary').waitFor()
          await dialog.locator('.el-checkbox__label').click()
          await dialog.getByRole('button', { name: '确认配置', exact: true }).click()
          await dialog.waitFor({ state: 'hidden' })
        }
        await page.waitForFunction(() => [...document.querySelectorAll('.js-plotly-plot')].filter(p => p.data?.length >= 11).length === 3, null, { timeout: 90000 })
        await page.getByText('求解设置', { exact: true }).click()
        const modelField = page.locator('.el-form-item').filter({ has: page.getByText('圆弧段计算模型', { exact: true }) })
        await modelField.locator('.el-select').click()
        await page.getByRole('option', { name: labels[modelIndex], exact: true }).click()
        await page.evaluate(() => {
          window.__timing = { start: null, graphTimes: {} }
          const plots = [...document.querySelectorAll('.js-plotly-plot')]
          plots.forEach((plot, i) => plot.on('plotly_afterplot', () => {
            const timing = window.__timing
            if (timing.start !== null && plot.data?.some(t => t.name === '再规划方案') && timing.graphTimes[i] == null) timing.graphTimes[i] = performance.now() - timing.start
          }))
          const button = [...document.querySelectorAll('button')].find(b => b.textContent.trim() === '生成规划方案')
          button.addEventListener('click', () => { window.__timing.start = performance.now() }, { capture: true, once: true })
        })
        const responsePromise = page.waitForResponse(r => r.url().includes('/api/algorithm/trajectory-plan/solve') && r.request().method() === 'POST', { timeout: 110000 })
        await page.getByRole('button', { name: '生成规划方案', exact: true }).click()
        const response = await responsePromise
        const rawResponseText = await response.text().catch(() => '')
        await page.waitForFunction(() => window.__solveReply != null, null, { timeout: 110000 })
        const responseElapsed = await page.evaluate(() => window.__replyReceived - window.__timing.start)
        const data = await page.evaluate(() => {
          const r = window.__solveReply
          return { status: r.status, message: r.message, constraint_violations: r.constraint_violations,
            total_length: r.total_length, target_error: r.target_error, iterations: r.iterations,
            trajectory_length: r.trajectory?.length || 0, timing: r.timing,
            minimum_sf: r.solver_metadata?.minimum_sf,
            configuration: r.solver_metadata?.configuration,
            solver_success: r.solver_metadata?.report?.selected_candidate_solver_success,
            solver_messages: r.solver_metadata?.report?.runs?.map(x => x.message) }
        })
        const request = response.request().postDataJSON()
        if (response.status() !== 200) { console.error('SOLVE_ERROR', rawResponseText, JSON.stringify(data)); throw new Error('solve HTTP '+response.status()) }
        assert.equal(data.status, 'SUCCESS', JSON.stringify({ status: data.status, message: data.message, violations: data.constraint_violations }))
        assert.equal(request.obstacle_versions.length, 9)
        assert.equal(data.configuration.arc_segment_model, models[modelIndex])
        await page.waitForFunction(() => Object.keys(window.__timing.graphTimes).length === 3, null, { timeout: 30000 })
        const timing = await page.evaluate(() => window.__timing)
        await page.screenshot({ path: path.join(output, `${scene}-${models[modelIndex]}-plots.png`), fullPage: true, mask: [page.locator('.avatar-container')] })
        const tableStart = await page.evaluate(() => performance.now())
        await page.getByText('轨道表', { exact: true }).click()
        await page.locator('.main-column .el-table__body tr').first().waitFor()
        const expectedRows = data.trajectory_length
        await page.waitForFunction(count => document.querySelectorAll('.main-column .el-table__body tr').length >= count, expectedRows)
        const tableSeconds = await page.evaluate(start => new Promise(resolve => requestAnimationFrame(() => requestAnimationFrame(() => resolve((performance.now() - start) / 1000)))), tableStart)
        const graphSeconds = Math.max(...Object.values(timing.graphTimes)) / 1000
        const completeVisibleSeconds = graphSeconds + tableSeconds
        const row = {
          scene, arc_segment_model: models[modelIndex], status: data.status,
          total_length_m: data.total_length, position_error_m: data.target_error,
          minimum_sf: data.minimum_sf,
          iterations: data.iterations, solver_success: data.solver_success, solver_messages: data.solver_messages, trajectory_rows: expectedRows,
          obstacle_versions: request.obstacle_versions.length,
          response_seconds: responseElapsed / 1000,
          graph_render_complete_seconds: graphSeconds,
          graph_afterplots_seconds: Object.values(timing.graphTimes).map(t => t / 1000),
          table_tab_render_seconds: tableSeconds,
          complete_visible_seconds: completeVisibleSeconds,
          gateway_non_algorithm_seconds: Math.max(0, Number(data.timing?.gateway_total_seconds || 0) - Number(data.timing?.algorithm_http_seconds || 0)),
          timing: data.timing, background_scan_suppressed: backgroundRequests > 0,
          input_sha256: crypto.createHash('sha256').update(JSON.stringify(request)).digest('hex'),
        }
        rows.push(row)
        fs.writeFileSync(path.join(output, 'timings.json'), JSON.stringify({ scope: 'isolated_verification_only', viewport: [1700, 1080], browser: await browser.version(), rows, errors }, null, 2) + '\n')
        console.log(JSON.stringify(row))
        await page.close()
      }
    }
    assert.deepEqual(errors, [])
  } finally { await browser.close() }
})().catch(e => { console.error(e); fs.writeFileSync(path.join(output, 'failure.json'), JSON.stringify({ error: String(e), rows, errors }, null, 2)); process.exitCode = 1 })
