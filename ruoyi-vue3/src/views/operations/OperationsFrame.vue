<template><AccessFrame :title="title" class="operations-page"><template #actions><slot name="actions" /></template><slot /></AccessFrame></template>
<script setup>import AccessFrame from '@/views/access/AccessFrame.vue';import './operations.css';defineProps({title:String})</script>
