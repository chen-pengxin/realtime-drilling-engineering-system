import test from 'node:test'
import assert from 'node:assert/strict'
import { neighborEvaluationMessage } from '../src/utils/neighborEvaluationMessage.js'

test('empty states use brief operational status', () => {
  assert.equal(neighborEvaluationMessage([]), '范围内无邻井')
  assert.equal(neighborEvaluationMessage([{ relationship_confirmed: true, included: 1 }]), '邻井版本不可用')
})

test('does not call an already confirmed but excluded candidate an unconfirmed relation', () => {
  assert.equal(neighborEvaluationMessage([{ auto_candidate: true, relationship_confirmed: true, included: 0 }]), '未选择邻井')
})
