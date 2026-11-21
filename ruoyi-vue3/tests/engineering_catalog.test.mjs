import test from 'node:test'
import assert from 'node:assert/strict'
import { catalogPath, catalogCreationTarget, catalogAccess, catalogFileTargetAllowed, catalogUploadError } from '../src/utils/engineeringCatalog.js'

const block={node_id:-1000000000001,node_type:'BLOCK',block_id:1,node_name:'测试区块'}
const field={node_id:-2000000000001,parent_id:block.node_id,node_type:'FIELD',field_id:1,node_name:'测试井场'}
const well={node_id:11,parent_id:field.node_id,node_type:'WELL',well_id:21,field_id:1,node_name:'测试井'}
const bore={node_id:12,parent_id:well.node_id,node_type:'WELLBORE',well_id:21,wellbore_id:31,field_id:1,node_name:'主井眼'}
const nodes=[block,field,well,bore]

test('new wells use the selected field and capture its full path',()=>{
  const target=catalogCreationTarget(field,nodes,'WELL')
  assert.equal(target.fieldId,1)
  assert.equal(target.parentId,field.node_id)
  assert.deepEqual(target.path.map(n=>n.node_type),['BLOCK','FIELD'])
  const originalName=target.path[1].node_name
  field.node_name='后续变更'
  assert.equal(target.path[1].node_name,originalName)
  field.node_name='测试井场'
})
test('all creation levels require the right parent',()=>{
  assert.equal(catalogCreationTarget(block,nodes,'FIELD').blockId,1)
  assert.equal(catalogCreationTarget(well,nodes,'WELLBORE').parentId,11)
  assert.equal(catalogCreationTarget(bore,nodes,'PLAN').parentId,12)
  assert.equal(catalogCreationTarget(bore,nodes,'SURVEY').parentId,12)
  assert.throws(()=>catalogCreationTarget(field,nodes,'WELLBORE'))
  assert.throws(()=>catalogCreationTarget(null,nodes,'WELL'))
})
test('missing and cross-field ancestry cannot create an orphan',()=>{
  assert.throws(()=>catalogCreationTarget(field,[field],'WELL'))
  assert.throws(()=>catalogCreationTarget({...well,field_id:2},nodes,'WELLBORE'))
})
test('circular input terminates with an error rather than freezing the page',()=>{
  assert.throws(()=>catalogPath({...well,parent_id:well.node_id},nodes),/循环/)
  assert.deepEqual(catalogPath(bore,nodes).map(n=>n.node_type),['BLOCK','FIELD','WELL','WELLBORE'])
})

test('field files can be managed without granting permission to create wells',()=>{
  const access=catalogAccess(field,['drilling:well:list','drilling:file:list','drilling:file:add'])
  assert.equal(access.attach,true)
  assert.equal(access.upload,false)
  assert.equal(access.writeData,false)
  assert.equal(catalogFileTargetAllowed(field),true)
  assert.equal(catalogFileTargetAllowed({node_type:'INBOX',status:'ACTIVE'}),true)
  assert.equal(catalogFileTargetAllowed(block),false)
})
test('read-only accounts keep viewing and export access without version writes',()=>{
  const access=catalogAccess(bore,['drilling:well:list','drilling:file:list'])
  assert.equal(access.readFiles,true)
  assert.equal(access.readData,true)
  for(const key of ['attach','upload','writeData','renameFile'])assert.equal(access[key],false)
  const dataOnly=catalogAccess(bore,['drilling:well:list'])
  assert.equal(dataOnly.readData,true)
  assert.equal(dataOnly.readFiles,false)
})
test('file mutations require their own permissions and readable file context',()=>{
  assert.equal(catalogAccess(bore,['drilling:well:survey:import']).upload,false)
  assert.equal(catalogAccess(bore,['drilling:file:list','drilling:well:survey:import']).upload,true)
  assert.equal(catalogAccess(bore,['drilling:file:list','drilling:file:upload']).renameFile,true)
  assert.equal(catalogAccess(bore,['drilling:file:list','drilling:file:add']).renameFile,false)
})
test('reference and archived objects remain read-only even for an administrator',()=>{
  for(const node of [{...bore,properties:{reference_only:true}},{...bore,status:'ARCHIVED'}]){
    const access=catalogAccess(node,['*:*:*'])
    for(const key of ['attach','upload','writeData','renameFile'])assert.equal(access[key],false)
    assert.equal(access.readFiles,true)
    assert.ok(catalogUploadError(node,'REFERENCE',''))
  }
})
test('ordinary field reports and classified wellbore trajectories have distinct ownership',()=>{
  assert.equal(catalogUploadError(field,'ENGINEERING_REPORT',''),'')
  assert.equal(catalogUploadError(field,'REFERENCE',''),'')
  assert.ok(catalogUploadError(field,'TRAJECTORY_DATA','ACTUAL'))
  assert.ok(catalogUploadError(well,'TRAJECTORY_DATA','DESIGN'))
  assert.ok(catalogUploadError(bore,'TRAJECTORY_DATA',''))
  assert.equal(catalogUploadError(bore,'TRAJECTORY_DATA','ACTUAL'),'')
  assert.ok(catalogUploadError({...bore,node_type:'PLAN'},'TRAJECTORY_REPORT','ACTUAL'))
  assert.equal(catalogUploadError({...bore,node_type:'SURVEY'},'TRAJECTORY_DATA','ACTUAL'),'')
})
