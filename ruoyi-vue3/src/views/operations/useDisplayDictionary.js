import {ref,onMounted} from 'vue'
import useUserStore from '@/store/modules/user'
import request from '@/utils/request'
export function useDisplayDictionary(type) {
  const rows = ref([])
  onMounted(async () => {if(useUserStore().demoOnly) return; rows.value = (await request({url:'/api/reference/display/'+type})).data})
  return rows
}
