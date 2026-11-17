import {computed} from 'vue'
import {useRoute,useRouter} from 'vue-router'
import useUserStore from '@/store/modules/user'

// Related management pages keep their original permission boundaries after
// consolidation into contextual tabs. Backend permissions still apply.
export function useManagementTabs(definitions){
  const route=useRoute(),router=useRouter(),user=useUserStore()
  const tabs=computed(()=>definitions.filter(t=>user.allowedModules.includes(t.module||t.name)))
  const active=computed({get:()=>tabs.value.some(t=>t.name===route.query.tab)?route.query.tab:tabs.value[0]?.name,set:name=>router.replace({query:{...route.query,tab:name}})})
  return {tabs,active}
}
