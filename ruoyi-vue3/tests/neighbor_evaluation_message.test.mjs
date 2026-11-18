import test from 'node:test'
import assert from 'node:assert/strict'
import { neighborEvaluationMessage } from '../src/utils/neighborEvaluationMessage.js'

test('reports automatically discovered candidates that still need confirmation', () => {
  assert.match(neighborEvaluationMessage([{ auto_candidate: true }, { auto_candidate: true }]), /自动识别 2 口邻井候选/)
  assert.match(neighborEvaluationMessage([{ auto_candidate: true }]), /尚未进行防碰评价/)
})

test('distinguishes no candidate from saved but unusable versions', () => {
  assert.match(neighborEvaluationMessage([]), /未识别到邻井候选/)
  assert.match(neighborEvaluationMessage([{ relationship_confirmed: true, included: 1 }]), /测斜版本当前不可用/)
})

test('does not call an already confirmed but excluded candidate an unconfirmed relation', () => {
  assert.match(neighborEvaluationMessage([{ auto_candidate: true, relationship_confirmed: true, included: 0 }]), /当前关系未纳入扫描/)
  assert.doesNotMatch(neighborEvaluationMessage([{ auto_candidate: true, relationship_confirmed: true, included: 0 }]), /需在邻井树中确认/)
})
