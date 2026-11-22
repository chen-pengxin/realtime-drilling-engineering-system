<template>
  <div class="scope-picker">
    <p class="scope-help">勾选上级会授权其全部下级。只授权一个井眼时，展开到该井眼再勾选。</p>
    <el-input v-model="filter" placeholder="搜索区块、井场、井或井眼" clearable/>
    <div class="tools">
      <el-button link type="primary" :disabled="!matches.size" @click="apply(matches,true)">{{ term ? '选中搜索结果' : '全选' }}</el-button>
      <el-button link :disabled="!matches.size" @click="apply(matches,false)">{{ term ? '取消搜索结果' : '取消全部' }}</el-button>
    </div>
    <el-tree ref="tree" v-loading="loading" :data="roots" node-key="id" :default-expanded-keys="roots.map(n=>n.id)"
      :filter-node-method="(value,node)=>node.path.toLowerCase().includes(value)" empty-text="没有匹配的工程节点">
      <template #default="{data}">
        <el-checkbox :model-value="states.get(data.id)==='full'" :indeterminate="states.get(data.id)==='partial'"
          @click.stop @change="checked=>apply(new Set([data.id]),checked)">
          <span class="kind">{{ data.kind }}</span>{{ data.label }}
        </el-checkbox>
      </template>
    </el-tree>
    <details v-if="selectedPaths.length"><summary>已选授权范围（{{ selectedPaths.length }}）</summary>
      <ul><li v-for="node in selectedPaths" :key="node.id">{{node.path}}</li></ul>
    </details>
    <span v-else class="kind">尚未选择工程节点</span>
  </div>
</template>
<script setup>
import {computed,ref,onMounted,watch,nextTick} from 'vue'
import request from '@/utils/request'
const props=defineProps({modelValue:{type:Array,default:()=>[]}})
const emit=defineEmits(['update:modelValue'])
const roots=ref([]),tree=ref(),filter=ref(''),loading=ref(true)
const term=computed(()=>filter.value.trim().toLowerCase())
const kinds={block:'区块',field:'井场',well:'井',bore:'井眼'}
const nodes=computed(()=>{
  const result=new Map()
  function add(node){result.set(node.id,node);node.children.forEach(add)}
  roots.value.forEach(add)
  return result
})
const matches=computed(()=>new Set([...nodes.value.values()].filter(n=>n.path.toLowerCase().includes(term.value)).map(n=>n.id)))
const selectedPaths=computed(()=>props.modelValue.map(id=>nodes.value.get(id)).filter(Boolean))
const states=computed(()=>{
  const selected=new Set(props.modelValue), result=new Map()
  function visit(node,inherited){
    const full=inherited || selected.has(node.id)
    const childStates=node.children.map(child=>visit(child,full))
    const state=full?'full':childStates.some(s=>s!=='none')?'partial':'none'
    result.set(node.id,state)
    return state
  }
  roots.value.forEach(n=>visit(n,false))
  return result
})
function apply(targets,adding){
  const result=[]
  function visit(node){
    if(targets.has(node.id)){
      if(adding)result.push(node.id)
      return true
    }
    const start=result.length
    let modified=false
    for(const child of node.children)if(visit(child))modified=true
    if(states.value.get(node.id)==='full' && (!modified || adding)){
      result.splice(start)
      result.push(node.id)
    }
    return modified
  }
  roots.value.forEach(visit)
  emit('update:modelValue',result)
}
watch(term,text=>tree.value?.filter(text))
onMounted(async()=>{
  try{
    const {data}=await request({url:'/api/access/hierarchy'})
    const index=new Map(),top=[]
    for(const row of data){
      let parent
      for(const [type,kind] of Object.entries(kinds)){
        if(row[type+'Id']==null)break
        const id=type+':'+row[type+'Id'],label=row[type+'Name']
        if(!index.has(id)){
          const node={id,label,kind,path:parent?parent.path+' / '+label:label,children:[]}
          index.set(id,node)
          const siblings=parent?parent.children:top
          siblings.push(node)
        }
        parent=index.get(id)
      }
    }
    roots.value=top
    await nextTick()
    if(term.value)tree.value?.filter(term.value)
  }finally{loading.value=false}
})
</script>
<style scoped>
.scope-picker{width:100%;min-width:0}
.scope-help{margin:0 0 8px;line-height:1.5}
.tools{display:flex;gap:12px}
.el-tree,ul{max-height:290px;overflow:auto}
.kind{margin-right:8px;color:var(--el-text-color-secondary);font-size:12px}
summary{cursor:pointer}ul{padding-left:20px}
</style>
