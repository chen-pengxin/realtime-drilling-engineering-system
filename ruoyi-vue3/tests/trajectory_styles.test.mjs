import test from 'node:test'
import assert from 'node:assert/strict'
import { defaultTrajectoryStyle, emptyTrajectoryStyles, normalizeTrajectoryStyles, resolveTrajectoryStyle, setTrajectoryStyle, trajectoryCategory } from '../src/utils/trajectoryStyles.js'
import { riskColors } from '../src/utils/trajectoryColors.js'

test('wellbore role and trajectory type remain independent', () => {
  assert.deepEqual(trajectoryCategory({kind:'NEIGHBOR',trajectory_kind:'DESIGN'}),{role:'NEIGHBOR',type:'DESIGN'})
  assert.deepEqual(trajectoryCategory({id:'neighbor-123',trajectory_kind:'ACTUAL'}),{role:'NEIGHBOR',type:'ACTUAL'})
  assert.deepEqual(trajectoryCategory({kind:'PLANNED'}),{role:'REFERENCE',type:'PLANNED'})
})

test('specific wellbore styles survive version changes and inherit global styles after reset', () => {
  const base=emptyTrajectoryStyles()
  const global=setTrajectoryStyle(base,{role:'NEIGHBOR',type:'ACTUAL',style:{color:'#123456',width:2,dash:'dot',opacity:.8}})
  const custom=setTrajectoryStyle(global,{role:'NEIGHBOR',type:'ACTUAL',wellboreIds:[31,32],style:{color:'#654321',width:3,dash:'dashdot',opacity:.6}})
  for(const id of [31,32])for(const version of [101,201]) {
    const style=resolveTrajectoryStyle(custom,{id:`neighbor-${version}`,wellbore_id:id,kind:'NEIGHBOR'})
    assert.equal(style.color,'#654321');assert.equal(style.width,3)
  }
  assert.equal(resolveTrajectoryStyle(custom,{kind:'NEIGHBOR',wellbore_id:33}).color,'#123456')
  assert.equal(resolveTrajectoryStyle(custom,{kind:'ACTUAL',wellbore_id:31}).color,defaultTrajectoryStyle('REFERENCE','ACTUAL').color)
  assert.equal(resolveTrajectoryStyle(custom,{kind:'NEIGHBOR',trajectory_kind:'DESIGN',wellbore_id:31}).color,defaultTrajectoryStyle('NEIGHBOR','DESIGN').color)
  const reset=setTrajectoryStyle(custom,{role:'NEIGHBOR',type:'ACTUAL',wellboreIds:[31],reset:true})
  assert.equal(resolveTrajectoryStyle(reset,{kind:'NEIGHBOR',wellbore_id:31}).color,'#123456')
  assert.equal(resolveTrajectoryStyle(reset,{kind:'NEIGHBOR',wellbore_id:32}).color,'#654321')
  assert.deepEqual(base,emptyTrajectoryStyles())
})

test('invalid stored style fields and wellbore keys cannot break plotting', () => {
  const preferences=normalizeTrajectoryStyles({version:1,styles:{REFERENCE:{ACTUAL:{color:'red',width:-1,opacity:0,dash:'bad',nodeSize:999,unrelated:'data'},DESIGN:{color:'#ABCDEF',width:2}}},wellbores:{'neighbor-11':{REFERENCE:{ACTUAL:{width:8}}},31:{REFERENCE:{PREDICTED:{width:4}}}}})
  assert.deepEqual(resolveTrajectoryStyle(preferences,{kind:'ACTUAL'}),defaultTrajectoryStyle('REFERENCE','ACTUAL'))
  assert.equal(resolveTrajectoryStyle(preferences,{kind:'DESIGN'}).color,'#abcdef')
  assert.equal(resolveTrajectoryStyle(preferences,{kind:'PREDICTED',wellbore_id:31}).width,4)
  assert.equal(preferences.wellbores['neighbor-11'],undefined)
  assert.deepEqual(normalizeTrajectoryStyles({version:2,styles:preferences.styles}),emptyTrajectoryStyles())
})

test('risk classification colors are independent from user trajectory styling', () => {
  setTrajectoryStyle(emptyTrajectoryStyles(),{role:'REFERENCE',type:'ACTUAL',style:{color:'#ffffff'}})
  assert.deepEqual(riskColors,{RED:'#dc2626',ORANGE:'#ea580c',YELLOW:'#ca8a04',BLUE:'#2563eb'})
})

test('view changes inherit unpublished and published engineering style fields correctly', () => {
  const engineering=setTrajectoryStyle(emptyTrajectoryStyles(),{role:'REFERENCE',type:'ACTUAL',style:{color:'#123456',width:2,dash:'dot'}})
  const local=setTrajectoryStyle(emptyTrajectoryStyles(),{role:'REFERENCE',type:'ACTUAL',style:{color:'#654321'}})
  assert.equal(resolveTrajectoryStyle(local,{kind:'ACTUAL'},engineering).width,2)
  const published=setTrajectoryStyle(engineering,{role:'REFERENCE',type:'ACTUAL',style:{color:'#abcdef',width:3,dash:'dash'}})
  const resolved=resolveTrajectoryStyle(local,{kind:'ACTUAL'},published)
  assert.equal(resolved.color,'#654321');assert.equal(resolved.width,3);assert.equal(resolved.dash,'dash')
  assert.equal(resolveTrajectoryStyle(emptyTrajectoryStyles(),{kind:'ACTUAL'},published).color,'#abcdef')
})
