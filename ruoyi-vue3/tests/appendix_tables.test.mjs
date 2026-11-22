import test from 'node:test'
import assert from 'node:assert/strict'
import {horizontalSegmentLength,standardRows,trajectoryColumns,basicColumns,targetPointColumns} from '../src/utils/appendixTables.js'
import {resolveGridStyle,normalizeTrajectoryStyles} from '../src/utils/trajectoryStyles.js'
test('standard table distinguishes dogleg angle from dogleg per 30 m and uses wellhead offsets',()=>{
 const rows=standardRows([{md:40,inclination:1,azimuth:90,north:4200001,east:19400002,dogleg:1,curvature:Math.PI/180/40},{md:0,inclination:0,azimuth:0,north:4200000,east:19400000,curvature:0}],{origin_north:4200000,origin_east:19400000})
 assert.deepEqual(rows.map(r=>r.md),[0,40]);assert.equal(rows[1].north,1);assert.equal(rows[1].east,2);assert.ok(Math.abs(rows[1].dogleg-.75)<1e-10)
})
test('source curvature and unknown apparent length remain source faithful',()=>{
 const rows=standardRows([{md:35.1,inclination:.4,azimuth:109.12,north:-.04,east:.12,dogleg:.34}],{},true)
 assert.equal(rows[0].dogleg,.34);assert.equal(rows[0].apparent_horizontal_length,undefined)
 assert.equal(trajectoryColumns(true)[8].label,'视平长\nm');assert.equal(trajectoryColumns(false)[8].label,'水平长度\nm')
 assert.equal(basicColumns[2].children[2].label,'补心高度\nm');assert.equal(targetPointColumns[7].label,'靶心距\nm')
})
test('grid overrides inherit engineering values and reject invalid stored values',()=>{
 const saved=normalizeTrajectoryStyles({version:1,grid:{color:'#aabbcc',width:2}})
 assert.deepEqual(resolveGridStyle({grid:{width:1.5}},saved),{color:'#aabbcc',width:1.5})
 assert.deepEqual(normalizeTrajectoryStyles({version:1,grid:{color:'bad',width:999}}).grid,{})
})

test('horizontal length follows minimum curvature through a quarter-circle build',()=>{
 const h=horizontalSegmentLength({md:0,inclination:0,azimuth:90},{md:100,inclination:90,azimuth:90})
 assert.ok(Math.abs(h-200/Math.PI)<1e-7)
 assert.equal(horizontalSegmentLength({md:0,inclination:90,azimuth:90},{md:100,inclination:90,azimuth:90}),100)
})
