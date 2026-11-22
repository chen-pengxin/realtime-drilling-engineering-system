<template>
  <DrillingPageFrame title="井眼轨迹预测">
    <nav class="pose-subnav" aria-label="井眼轨迹预测子页面">
      <router-link v-if="user.allowedModules.includes('prediction')" :to="{ name: 'DrillingPredictionOverview' }">预测展示</router-link>
      <router-link v-if="user.allowedModules.includes('models')" :to="{ name: 'DrillingPredictionModels' }">模型仓库</router-link>
    </nav>
    <router-view />
  </DrillingPageFrame>
</template>
<script setup>
import useUserStore from '@/store/modules/user'
const user=useUserStore()
import DrillingPageFrame from '../../layout/DrillingPageFrame.vue'
import './prediction.css'
</script>
