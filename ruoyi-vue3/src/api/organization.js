import request from '@/utils/request'
const base='/api/operations/organization'
export const departments=()=>request({url:`${base}/departments`})
export const posts=()=>request({url:`${base}/posts`})
export const saveDepartment=data=>request({url:`${base}/departments${data.deptId?`/${data.deptId}`:''}`,method:data.deptId?'put':'post',data})
export const deleteDepartment=id=>request({url:`${base}/departments/${id}`,method:'delete'})
export const savePost=data=>request({url:`${base}/posts${data.postId?`/${data.postId}`:''}`,method:data.postId?'put':'post',data})
export const deletePost=id=>request({url:`${base}/posts/${id}`,method:'delete'})
export const fieldOrganizations=()=>request({url:`${base}/fields`})
export const fieldOwners=()=>request({url:`${base}/owners`})
export const saveFieldOrganization=data=>request({url:`${base}/fields/${data.fieldId}`,method:'put',data})
export const menus=()=>request({url:'/api/operations/menus'})
export const navigation=()=>request({url:'/api/reference/navigation'})
export const saveMenu=data=>request({url:`/api/operations/menus${data.menuId?`/${data.menuId}`:''}`,method:data.menuId?'put':'post',data})
export const deleteMenu=id=>request({url:`/api/operations/menus/${id}`,method:'delete'})
export function treeRows(rows,id='deptId',label='deptName'){
 const map=new Map(rows.map(r=>[r[id],{...r,id:r[id],label:r[label],children:[]}]))
 const roots=[]
 for(const row of map.values()){const parent=map.get(row.parentId);if(parent&&parent!==row)parent.children.push(row);else roots.push(row)}
 return roots
}
export const scopeLabels={'1':'全部数据','2':'自定义部门','3':'本部门','4':'本部门及下级','5':'仅本人','6':'指定井场'}
