import test from 'node:test'
import assert from 'node:assert/strict'
import { buildEvaluationStations } from '../src/utils/liveEvaluationStations.js'

const measured = [{ md: 109 }, { md: 139 }]

test('preserves nonzero measured history and appends a later forecast', () => {
  assert.deepEqual(buildEvaluationStations(measured, { md: 149 }), [
    { md: 109 }, { md: 139 }, { md: 149 }
  ])
})

test('does not append an out-of-order or duplicate forecast', () => {
  assert.deepEqual(buildEvaluationStations(measured, { md: 100 }), measured)
  assert.deepEqual(buildEvaluationStations(measured, { md: 139 }), measured)
})

test('does not replace measured history when prediction is unavailable', () => {
  assert.deepEqual(buildEvaluationStations(measured, null), measured)
  assert.deepEqual(buildEvaluationStations([], { md: 149 }), [])
})
