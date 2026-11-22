<template>
  <el-dialog
    v-bind="$attrs"
    :model-value="modelValue"
    :title="undefined"
    class="resizable-dialog-host"
    :width="effectiveWidth"
    :fullscreen="isFullscreen"
    @update:model-value="emit('update:modelValue', $event)"
    @open="emit('open')"
    @close="emit('close')"
    @closed="emit('closed')"
  >
    <template #header>
      <div class="resizable-dialog-header">
        <span class="resizable-dialog-title">{{ title }}</span>
        <span class="resizable-dialog-actions">
          <el-button text circle size="small" title="缩小" aria-label="缩小弹窗" @click.stop="zoomOut">−</el-button>
          <el-button text circle size="small" title="放大" aria-label="放大弹窗" @click.stop="zoomIn">＋</el-button>
          <el-button text circle size="small" title="恢复默认大小" aria-label="恢复弹窗大小" @click.stop="resetSize">↺</el-button>
          <el-button text circle size="small" :title="isFullscreen ? '退出放大' : '铺满窗口'" :aria-label="isFullscreen ? '退出放大' : '铺满窗口'" @click.stop="toggleFullscreen">{{ isFullscreen ? '⤢' : '⤡' }}</el-button>
        </span>
      </div>
    </template>
    <slot />
    <template #footer><slot name="footer" /></template>
  </el-dialog>
</template>

<script setup>
import { computed, ref, watch } from 'vue'

defineOptions({ inheritAttrs: false })
const props = defineProps({
  modelValue: { type: Boolean, default: false },
  title: { type: String, default: '' },
  width: { type: [String, Number], default: 'min(620px, calc(100vw - 32px))' }
})
const emit = defineEmits(['update:modelValue', 'close', 'open', 'closed'])
const sizeMode = ref('base')
const isFullscreen = ref(false)
const effectiveWidth = computed(() => {
  if (sizeMode.value === 'wide') return 'min(90vw, 1200px)'
  if (sizeMode.value === 'narrow') return 'min(420px, calc(100vw - 32px))'
  return props.width
})
function zoomIn() { isFullscreen.value = false; sizeMode.value = 'wide' }
function zoomOut() { isFullscreen.value = false; sizeMode.value = 'narrow' }
function resetSize() { isFullscreen.value = false; sizeMode.value = 'base' }
function toggleFullscreen() { isFullscreen.value = !isFullscreen.value }
watch(() => props.modelValue, value => { if (!value) resetSize() })
</script>

<style>
.resizable-dialog-header{display:flex;align-items:center;justify-content:space-between;gap:12px;width:100%}
.resizable-dialog-title{font-size:16px;font-weight:600;color:var(--el-text-color-primary);overflow:hidden;text-overflow:ellipsis;white-space:nowrap}
.resizable-dialog-actions{display:flex;align-items:center;gap:2px;flex:none}
.resizable-dialog-actions :deep(.el-button){font-size:18px;line-height:1;min-width:26px;height:26px;padding:0}
.resizable-dialog-host.el-dialog:not(.is-fullscreen){resize:both;overflow:auto;min-width:360px;min-height:220px}
</style>
