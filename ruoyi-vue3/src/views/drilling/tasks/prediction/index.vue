<template>
  <DrillingPageFrame title="井眼轨迹预测">
    <nav class="pose-subnav" aria-label="井眼轨迹预测子页面">
      <router-link :to="{ name: 'DrillingPredictionOverview' }">预测展示</router-link>
      <router-link :to="{ name: 'DrillingPredictionModels' }">模型仓库</router-link>
    </nav>
    <router-view />
  </DrillingPageFrame>
</template>
<script setup>
import DrillingPageFrame from '../../layout/DrillingPageFrame.vue'
import './prediction.css'
</script>
