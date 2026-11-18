import test from 'node:test'
import assert from 'node:assert/strict'
import { createEngineConfirmation, isEngineeringEvaluationRoute, readLoginSessionId, rotateLoginSessionId } from '../src/utils/engineConfirmation.js'

const deferred = () => { let resolve, reject; const promise = new Promise((a, b) => { resolve = a; reject = b }); return { promise, resolve, reject } }
function fixture(read = async () => ({ confirmed: false, configHash: 'a' }), submit = async () => {}) {
  const state = { visible: false, busy: false, checked: false, error: '', snapshot: null, fieldId: null, wellboreId: null }
  const ctx = { fieldId: 3, wellboreId: 101, userId: 1, eligible: true }, confirmed = []
  const gate = createEngineConfirmation({ state, context: () => ctx, read, submit, onConfirmed: id => confirmed.push(id) })
  return { state, ctx, confirmed, ...gate }
}

test('only evaluation workspaces trigger mandatory parameter review', () => {
  for (const route of ['/drilling/realtime', '/drilling/tasks/anticollision', '/drilling/tasks/replanning', '/drilling/basic/realtime-data']) assert.equal(isEngineeringEvaluationRoute(route), true, route)
  for (const route of ['/login', '/system/user', '/drilling/basic/catalog', '/drilling/basic/files', '/drilling/basic/config', '/drilling/tasks/prediction/models', '/drilling/realtime-other']) assert.equal(isEngineeringEvaluationRoute(route), false, route)
})

test('login confirmation session is stable across reloads and rotates after a new login', () => {
  const values = new Map()
  const storage = { getItem: key => values.get(key) || null, setItem: (key, value) => values.set(key, value) }
  const first = readLoginSessionId(storage)
  assert.equal(readLoginSessionId(storage), first)
  const next = rotateLoginSessionId(storage)
  assert.notEqual(next, first)
  assert.equal(readLoginSessionId(storage), next)
})

test('server review is mandatory and cannot be submitted without acknowledgement', async () => {
  let writes = 0
  const gate = fixture(undefined, async () => { writes++ })
  await gate.check(); assert.equal(gate.state.visible, true)
  await gate.confirm(); assert.equal(writes, 0)
  gate.state.checked = true; await gate.confirm()
  assert.equal(writes, 1); assert.equal(gate.state.visible, false)
  assert.deepEqual(gate.confirmed, [3])
})

test('confirmed parameters stay closed after backend rechecks in the same scope', async () => {
  let reads = 0
  const gate = fixture(async () => { reads++; return { confirmed: true, configHash: 'same' } })
  await gate.check(); assert.equal(gate.state.visible, true)
  gate.state.checked = true; await gate.confirm(); assert.equal(gate.state.visible, false)
  for (let i = 0; i < 4; i++) { await gate.check(); assert.equal(gate.state.visible, false) }
  assert.equal(reads, 5)
})

test('a new wellbore is a new confirmation scope, while returning to a confirmed wellbore stays closed', async () => {
  let reads = 0
  const gate = fixture(async () => { reads++; return { confirmed: true, configHash: 'same' } })
  await gate.check(); assert.equal(gate.state.visible, true); assert.equal(gate.state.wellboreId, 101)
  gate.state.checked = true; await gate.confirm(); assert.equal(gate.state.visible, false)
  gate.ctx.wellboreId = 202
  await gate.check(); assert.equal(gate.state.visible, true); assert.equal(gate.state.wellboreId, 202)
  gate.state.checked = true; await gate.confirm(); assert.equal(gate.state.visible, false)
  gate.ctx.wellboreId = 101
  await gate.check(); assert.equal(gate.state.visible, false)
  assert.equal(reads, 3)
})

test('a new login session and a changed configuration each require a fresh review', async () => {
  let reads = 0
  const gate = fixture(async () => { reads++; return { confirmed: true, configHash: `hash-${reads}` } })
  await gate.check(); gate.state.checked = true; await gate.confirm()
  gate.ctx.sessionId = 2
  await gate.check(); assert.equal(gate.state.visible, true)
  gate.state.checked = true; await gate.confirm()
  gate.ctx.configVersion = 2
  await gate.check(); assert.equal(gate.state.visible, true)
  assert.equal(reads, 3)
})

test('unrelated config version changes recheck safety parameters without reopening the dialog', async () => {
  let reads = 0
  const gate = fixture(async () => { reads++; return { confirmed: true, configHash: 'same-safety-parameters' } })
  gate.ctx.configVersion = 1
  await gate.check(); gate.state.checked = true; await gate.confirm()
  gate.ctx.configVersion = 2
  await gate.check()
  assert.equal(reads, 2)
  assert.equal(gate.state.visible, false)
})

test('an explicit backend-required event bypasses the local confirmation cache', async () => {
  let reads = 0
  const gate = fixture(async () => { reads++; return { confirmed: true, configHash: 'same' } })
  await gate.check(); gate.state.checked = true; await gate.confirm()
  await gate.check({ force: true })
  assert.equal(reads, 2)
})

test('configuration events during POST are queued and detect a changed budget', async () => {
  const post = deferred(); let reads = 0
  const gate = fixture(async () => ({ confirmed: false, configHash: ++reads === 1 ? 'a' : 'b' }), () => post.promise)
  await gate.check(); gate.state.checked = true
  const confirmation = gate.confirm(); gate.check(); gate.check()
  post.resolve(); await confirmation
  assert.equal(reads, 2); assert.equal(gate.state.snapshot.configHash, 'b')
  assert.equal(gate.state.visible, true); assert.equal(gate.state.checked, false)
})

test('late responses from another field never override current scope', async () => {
  const first = deferred(), second = deferred()
  const gate = fixture(id => id === 3 ? first.promise : second.promise)
  const old = gate.check(); gate.ctx.fieldId = 4; const current = gate.check()
  second.resolve({ confirmed: true, configHash: 'current' }); await current
  first.resolve({ confirmed: false, configHash: 'old' }); await old
  assert.equal(gate.state.fieldId, 4); assert.equal(gate.state.snapshot.configHash, 'current'); assert.equal(gate.state.visible, true)
})

test('leaving the workspace discards pending errors and closes the dialog', async () => {
  const read = deferred(), gate = fixture(() => read.promise)
  const pending = gate.check(); gate.ctx.eligible = false; await gate.check()
  read.reject(new Error('timeout')); await pending
  assert.equal(gate.state.visible, false); assert.equal(gate.state.error, ''); assert.equal(gate.state.busy, false)
})

test('network failure does not create an empty blocking dialog and retry recovers', async () => {
  let fail = true
  const gate = fixture(async () => { if (fail) throw new Error('系统接口请求超时'); return { confirmed: false, configHash: 'a' } })
  await gate.check(); assert.equal(gate.state.visible, false); assert.match(gate.state.error, /超时/)
  fail = false; await gate.check(); assert.equal(gate.state.visible, true); assert.equal(gate.state.error, '')
})

test('configuration conflict fetches current parameters and requires a fresh acknowledgement', async () => {
  let reads = 0
  const gate = fixture(async () => ({ confirmed: false, configHash: ++reads === 1 ? 'old' : 'new' }), async () => { throw new Error('配置已切换，请重新核对并确认') })
  await gate.check(); gate.state.checked = true; await gate.confirm()
  assert.equal(gate.state.snapshot.configHash, 'new'); assert.equal(gate.state.checked, false); assert.equal(gate.state.visible, true)
})
