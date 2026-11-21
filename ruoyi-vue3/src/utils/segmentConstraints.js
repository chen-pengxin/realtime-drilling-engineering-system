export function segmentTypes(mode, count) {
  return Array.from({length:Number(count)},(_,i)=>mode==='arc'?'arc':
    (i%2===0)===(mode==='straight-arc')?'straight':'arc')
}

export function segmentRows(mode,count,defaults,existing=[]) {
  return segmentTypes(mode,count).map((type,index)=>{
    const old=existing.find(r=>Number(r.segment)===index+1)||existing[index]||{}
    return {segment:index+1,type,minLength:old.minLength??old.length_m?.[0]??defaults.minLength,
      maxLength:old.maxLength??old.length_m?.[1]??defaults.maxLength,
      minDls:type==='straight'?0:old.type==='straight'?defaults.minDls:old.minDls??old.dls_deg_per_30m?.[0]??defaults.minDls,
      maxDls:type==='straight'?0:old.type==='straight'?defaults.maxDls:old.maxDls??old.dls_deg_per_30m?.[1]??defaults.maxDls}
  })
}

export function segmentBoundsPayload(mode,count,rows) {
  const types=segmentTypes(mode,count)
  if(rows.length!==Number(count))throw Error('请配置每个轨迹段的约束')
  return types.map((type,index)=>{
    const row=rows[index]
    if(Number(row.segment)!==index+1)throw Error('轨迹段顺序不正确')
    const values=[row.minLength,row.maxLength,row.minDls,row.maxDls]
    if(!values.every(v=>typeof v==='number'&&Number.isFinite(v))||row.minLength<=0||row.minLength>row.maxLength||row.minDls<0||row.minDls>row.maxDls)throw Error('段长须为正数，狗腿度须非负，且下限不能大于上限')
    return {length_m:[row.minLength,row.maxLength],dls_deg_per_30m:type==='straight'?[0,0]:[row.minDls,row.maxDls]}
  })
}
