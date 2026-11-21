import test from 'node:test'
import assert from 'node:assert/strict'
import {isEvaluationNeighbor,setNeighborIncluded,neighborChanges,hasIncompatibleNeighborSelection} from '../src/utils/neighborSelection.js'

const automatic=()=>({wellbore_id:10,included:true,relationship_confirmed:true,relationship_source:'AUTO',survey_version_id:20,quality_status:'READY',source_review_status:'CONFIRMED',version_policy:'CURRENT_ACTUAL'})

test('automatic neighbors participate without a manual save',()=>{
  const row=automatic()
  assert.equal(isEvaluationNeighbor(row),true)
  assert.deepEqual(neighborChanges([row]),[])
})

test('manual exclusions and additions save only edited relationships',()=>{
  const row=automatic(),untouched=automatic()
  setNeighborIncluded(row,false)
  assert.equal(isEvaluationNeighbor(row),false)
  assert.deepEqual(neighborChanges([row,untouched]),[{wellboreId:10,included:false,versionPolicy:'CURRENT_ACTUAL',pinnedVersionId:null}])
  const outside={...automatic(),included:false,relationship_confirmed:false,relationship_source:'AVAILABLE'}
  setNeighborIncluded(outside,true)
  assert.equal(isEvaluationNeighbor(outside),false)
  assert.equal(neighborChanges([outside])[0].included,true)
  assert.equal(isEvaluationNeighbor({...outside,selection_dirty:false,relationship_confirmed:true}),true)
})

test('clearing all neighbors still produces exclusions that can be saved',()=>{
  const rows=[automatic(),{...automatic(),wellbore_id:11}]
  rows.forEach(row=>setNeighborIncluded(row,false))
  assert.equal(rows.filter(isEvaluationNeighbor).length,0)
  assert.equal(neighborChanges(rows).length,2)
})

test('unchanged bulk selection does not freeze automatic relationships as manual',()=>{
  const row=automatic()
  setNeighborIncluded(row,true)
  assert.deepEqual(neighborChanges([row]),[])
})

test('unusable versions never enter evaluation and pinned changes retain version identity',()=>{
  assert.equal(isEvaluationNeighbor({...automatic(),source_review_status:'PENDING'}),false)
  const row={...automatic(),version_policy:'PINNED',selection_dirty:true}
  assert.equal(neighborChanges([row])[0].pinnedVersionId,20)
})

test('an incompatible saved neighbor can be explicitly excluded even though its checkbox is disabled',()=>{
  const row={...automatic(),included:false,usable:false,coordinate_compatible:false,requested_included:true}
  assert.equal(hasIncompatibleNeighborSelection([row]),true)
  setNeighborIncluded(row,false)
  assert.equal(hasIncompatibleNeighborSelection([row]),false)
  assert.deepEqual(neighborChanges([row]),[{wellboreId:10,included:false,versionPolicy:'CURRENT_ACTUAL',pinnedVersionId:null}])
})
