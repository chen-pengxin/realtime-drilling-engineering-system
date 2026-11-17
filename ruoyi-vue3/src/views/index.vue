<template><div class="index-redirect" /></template>

<script setup>
import { onMounted } from 'vue'

const router = useRouter()
onMounted(() => router.replace('/drilling/realtime'))
</script>
