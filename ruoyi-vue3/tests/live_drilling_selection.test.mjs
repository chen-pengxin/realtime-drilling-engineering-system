import test from 'node:test'
import assert from 'node:assert/strict'
import { liveWellboresForField, liveRequestMessage, incompatibleNeighborNotice } from '../src/utils/liveDrillingSelection.js'
import { isEvaluationNeighbor } from '../src/utils/neighborSelection.js'

test('a linked offset cannot become the live wellbore of another field',()=>{
  const rows=[
    {wellbore_id:59,field_id:3,owner_field_id:3,context_field_id:1},
    {wellbore_id:12,field_id:1,owner_field_id:1,context_field_id:1},
    {wellbore_id:227,field_id:14,owner_field_id:14,context_field_id:1}
  ]
  assert.deepEqual(liveWellboresForField(rows,1).map(row=>row.wellbore_id),[12])
  assert.deepEqual(liveWellboresForField(rows,3).map(row=>row.wellbore_id),[59])
  assert.deepEqual(liveWellboresForField([{wellbore_id:9}],1),[])
})
test('API error bodies are not displayed as raw JSON',()=>{
  assert.equal(liveRequestMessage({message:'计算服务拒绝请求： {"detail":"缺少深度基准高程"}'}),'缺少深度基准高程')
  assert.equal(liveRequestMessage({message:'计算服务拒绝请求： {"detail":[{"loc":["body"],"msg":"invalid"}]}'}),'实时数据读取失败')
  assert.equal(liveRequestMessage({response:{data:{msg:'测斜版本不存在'}}}),'测斜版本不存在')
})
test('incompatible coordinate frames cannot enter evaluation through an old saved selection',()=>{
  const row={included:true,relationship_confirmed:true,survey_version_id:309,quality_status:'READY',source_review_status:'CONFIRMED'}
  assert.equal(isEvaluationNeighbor(row),true)
  assert.equal(isEvaluationNeighbor({...row,coordinate_compatible:false}),false)
  assert.equal(isEvaluationNeighbor({...row,usable:false}),false)
  assert.equal(incompatibleNeighborNotice([{coordinate_compatible:true},{coordinate_compatible:false}]),'1 个井眼坐标基准不一致，未参与防碰评价')
})
