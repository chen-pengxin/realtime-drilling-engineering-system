/* Uses an existing authenticated session; never changes account or field settings. */
const fs = require('fs')
const path = require('path')
const { chromium } = require(process.env.PLAYWRIGHT_MODULE || 'playwright')
const base = process.env.DRILLING_FRONTEND_URL || 'http://127.0.0.1:5173'
const backend = process.env.DRILLING_BACKEND_URL || 'http://127.0.0.1:8080'
const session = process.env.DRILLING_SESSION_ID
if (!session) throw Error('Set DRILLING_SESSION_ID to the current authenticated JSESSIONID')
const out = path.resolve(process.env.DRILLING_BROWSER_REPORT || 'artifacts/engineering-test-cases')
fs.mkdirSync(out, { recursive: true })
const results = [], errors = []
;(async () => {
  const browser = await chromium.launch({ headless: true, executablePath: process.env.DRILLING_BROWSER_EXECUTABLE,
    args: ['--use-gl=angle', '--use-angle=swiftshader', '--enable-webgl', '--enable-unsafe-swiftshader'] })
  try {
    const ctx = await browser.newContext({ viewport: { width: 1600, height: 1050 }, colorScheme: 'dark' })
    await ctx.addCookies([{ name: 'JSESSIONID', value: session, url: base },
      { name: 'Admin-Token', value: 'shiro-session', url: base }])
    const fields = await (await ctx.request.get(backend + '/api/drilling/fields',
      { headers: { 'X-Requested-With': 'XMLHttpRequest' } })).json()
    for (const letter of ['A', 'B', 'C']) {
      const field = fields.find(f => f.field_code === 'TEST-' + letter)
      if (!field) throw Error('Missing ordinary test field ' + letter)
      const page = await ctx.newPage()
      page.on('pageerror', e => errors.push(String(e)))
      await page.addInitScript(id => localStorage.setItem('drilling.fieldId', String(id)), field.field_id)
      await page.goto(base + '/drilling/tasks/replanning', { waitUntil: 'domcontentloaded' })
      await page.waitForFunction(() => Array.from(document.querySelectorAll('.js-plotly-plot'))
        .some(p => p.data?.length >= 11), null, { timeout: 90000 })
      if (await page.evaluate(() => document.documentElement.classList.contains('dark'))) throw Error('Default theme must be light')
      const text = await page.locator('body').innerText()
      if (!text.includes('井1') || !text.includes('测试案例 ' + letter)) throw Error('Wrong default wellbore')
      if ((await page.locator('.sidebar-container').innerText()).includes('测试案例')) throw Error('A/B/C must not have a separate menu')
      if (text.includes('项目管理') || text.includes('规划场景')) throw Error('Obsolete engineering controls')
      if (letter === 'A') {
        await page.getByText('求解设置', { exact: true }).click()
        await page.locator('.el-form-item').filter({ hasText: '轨迹模型' }).locator('.el-select').click()
        await page.getByRole('option', { name: '直线—圆弧', exact: true }).click()
        await page.getByRole('option', { name: '直线—圆弧', exact: true }).waitFor({ state: 'hidden' })
        await page.locator('.el-form-item').filter({ hasText: '轨迹段数' }).locator('.el-select').click()
        await page.getByRole('option', { name: '1 段（直线）', exact: true }).waitFor({ state: 'visible' })
        const labels = await page.getByRole('option').filter({ visible: true }).allTextContents()
        for (const label of ['1 段（直线）', '2 段（直线—圆弧）', '3 段（直线—圆弧—直线）']) {
          if (!labels.some(value => value.trim() === label)) throw Error('Missing segment sequence: ' + label + '; options=' + JSON.stringify(labels))
        }
        await page.getByRole('option', { name: '3 段（直线—圆弧—直线）', exact: true }).click()
        await page.getByRole('option', { name: '3 段（直线—圆弧—直线）', exact: true }).waitFor({ state: 'hidden' })
      }
      await page.waitForTimeout(300)
      await page.screenshot({ path: path.join(out, 'case-' + letter + '-replanning.png'), fullPage: true,
        mask: [page.locator('.avatar-container')] })
      results.push({ case: letter, field_id: field.field_id, default_drilling_well: '井1',
        loaded_trajectories: await page.locator('.js-plotly-plot').first().evaluate(p => p.data.length), light_theme: true })
      if (letter === 'A') {
        await page.setViewportSize({ width: 390, height: 844 })
        if (await page.evaluate(() => document.documentElement.scrollWidth > innerWidth + 3)) throw Error('Mobile viewport overflow')
        await page.screenshot({ path: path.join(out, 'replanning-mobile.png'), fullPage: true,
          mask: [page.locator('.avatar-container')] })
      }
      await page.close()
    }
    if (errors.length) throw Error(errors.join('\n'))
    fs.writeFileSync(path.join(out, 'browser-results.json'), JSON.stringify({ success: true, results, errors }, null, 2))
    console.log('PASS: ordinary A/B/C fields, default drilling wellbores, 11 trajectories, light theme, straight-arc sequence and mobile width')
  } finally { await browser.close() }
})().catch(e => { console.error(e); process.exitCode = 1 })
