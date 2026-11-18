// Keep one authoritative request per user/field. Late responses from another
// route or field must never change a dialog, including after a successful POST.
export function isEngineeringEvaluationRoute(path = '') {
  return /^\/drilling\/(realtime(?:\/|$)|tasks\/(anticollision|replanning)(?:\/|$)|basic\/realtime-data(?:\/|$))/.test(path)
}

const LOGIN_SESSION_KEY = 'drilling.engine-confirmation.login-session'
const randomSessionId = () => globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(36).slice(2)}`

export function readLoginSessionId(storage = globalThis.sessionStorage) {
  try {
    let value = storage.getItem(LOGIN_SESSION_KEY)
    if (!value) {
      value = randomSessionId()
      storage.setItem(LOGIN_SESSION_KEY, value)
    }
    return value
  } catch {
    return randomSessionId()
  }
}

export function rotateLoginSessionId(storage = globalThis.sessionStorage) {
  const value = randomSessionId()
  try { storage.setItem(LOGIN_SESSION_KEY, value) } catch {}
  return value
}

export function createEngineConfirmation({ state, context, read, submit, onConfirmed }) {
  let serial = 0, pending = null, recheck = false
  // Cache the engineering configuration version with the confirmation. A new
  // configuration version triggers one read; the server hash decides whether
  // it actually changed safety parameters.
  const confirmedScopes = new Map()
  const scope = value => `${value.sessionId || ''}:${value.userId || ''}:${value.fieldId || ''}:${value.wellboreId || ''}`
  const storageKey = (value, snapshot) => [
    'drilling.engine-confirmation', value.sessionId, value.userId, value.fieldId,
    value.wellboreId, snapshot.sessionHash, snapshot.configHash
  ].map(part => encodeURIComponent(String(part || ''))).join(':')
  const wasConfirmed = (value, snapshot) => {
    const cached = confirmedScopes.get(scope(value))
    if (cached && cached.configHash === snapshot.configHash) return true
    try { return sessionStorage.getItem(storageKey(value, snapshot)) === '1' } catch { return false }
  }
  const rememberConfirmed = (value, snapshot) => {
    confirmedScopes.set(scope(value), { configHash: snapshot.configHash, configVersion: value.configVersion })
    try { sessionStorage.setItem(storageKey(value, snapshot), '1') } catch {}
  }
  const active = value => !!value.fieldId && value.eligible

  function reset() {
    serial++; pending = null; recheck = false
    Object.assign(state, { visible: false, busy: false, snapshot: null, checked: false, error: '', fieldId: null, wellboreId: null })
  }

  function run(kind, force = false) {
    const selected = context()
    if (!active(selected)) { reset(); return Promise.resolve() }
    if (pending && pending.scope === scope(selected)) {
      recheck = true
      return pending.promise
    }
    if (kind === 'confirm' && (!state.snapshot || !state.checked || state.busy || String(state.fieldId) !== String(selected.fieldId))) return Promise.resolve()
    if (String(state.fieldId) !== String(selected.fieldId) || String(state.wellboreId || '') !== String(selected.wellboreId || '')) {
      Object.assign(state, { visible: false, snapshot: null, checked: false, error: '', fieldId: selected.fieldId, wellboreId: selected.wellboreId || null })
    }
    const token = ++serial, snapshot = state.snapshot
    const valid = () => token === serial && active(context()) && scope(context()) === scope(selected)
    state.busy = true
    const operation = { scope: scope(selected), promise: null }
    pending = operation
    operation.promise = (async () => {
      try {
        if (kind === 'confirm') {
          await submit(selected.fieldId, snapshot.configHash)
          if (!valid()) return
          rememberConfirmed(selected, snapshot)
          Object.assign(state, { snapshot: { ...snapshot, confirmed: true }, visible: false, checked: false, error: '' })
          onConfirmed?.(selected.fieldId)
        } else {
          const value = await read(selected.fieldId)
          if (!valid()) return
          if (state.snapshot?.configHash !== value.configHash) state.checked = false
          // The server record is field-scoped for backward compatibility. It
          // cannot prove that this browser session reviewed this wellbore.
          // Only an acknowledgement made in the current session and exact
          // field/wellbore/config scope can close the mandatory dialog.
          const confirmed = !!value.confirmed && wasConfirmed(selected, value)
          if (confirmed) rememberConfirmed(selected, value)
          Object.assign(state, { snapshot: { ...value, confirmed }, visible: !confirmed, error: '' })
        }
      } catch (error) {
        if (!valid()) return
        state.checked = false
        state.error = error?.message || '无法读取工程评价参数，请重试'
        // Transport/authentication failures are not evidence that parameters
        // changed. Never trap the user in an empty mandatory dialog.
        if (kind === 'check') { state.visible = false; state.snapshot = null }
        if (kind === 'confirm' && /配置已切换/.test(state.error)) recheck = true
      } finally {
        if (pending === operation) {
          pending = null
          state.busy = false
          if (recheck) { recheck = false; await run('check', true) }
        }
      }
    })()
    return operation.promise
  }

  return { check: (options = {}) => run('check', !!options.force), confirm: () => run('confirm'), reset }
}
