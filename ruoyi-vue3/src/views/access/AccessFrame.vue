<template><section :class="business?'app-container demo-page':'app-container'"><h1 class="sr-only">{{ title }}</h1><div v-if="$slots.actions" class="management-actions"><slot name="actions" /></div><slot /></section></template>
<script setup>
import './access.css'
defineProps({title:String,business:Boolean})
</script>
<style scoped>.demo-page{min-height:calc(100vh - 84px);background:var(--el-bg-color-page);color:var(--el-text-color-primary);}.demo-page h3{color:var(--el-text-color-primary);}</style>
