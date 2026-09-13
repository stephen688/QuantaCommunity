<script setup>
import { WarningFilled } from '@element-plus/icons-vue'

/**
 * 列表表格统一空态 / 错误态（与 portal-minimal `.table-empty` 配套）。
 */
defineProps({
  title: {
    type: String,
    required: true,
  },
  description: {
    type: String,
    default: '',
  },
  /** 为 true 时使用错误样式（列表加载失败后的占位） */
  error: {
    type: Boolean,
    default: false,
  },
  /** 可选 Element Plus 图标；空态展示图形区 */
  icon: {
    type: [Object, Function],
    default: null,
  },
})
</script>

<template>
  <div
    :class="['table-empty', { 'table-empty--error': error }]"
    role="status"
    :aria-live="error ? 'assertive' : 'polite'"
  >
    <div v-if="icon && !error" class="table-empty-graphic" aria-hidden="true">
      <el-icon :size="40" class="table-empty-icon">
        <component :is="icon" />
      </el-icon>
      <span class="table-empty-line" />
    </div>
    <div v-else-if="error" class="table-empty-graphic table-empty-graphic--error" aria-hidden="true">
      <el-icon :size="36" class="table-empty-icon table-empty-icon--error">
        <WarningFilled />
      </el-icon>
      <span class="table-empty-line table-empty-line--error" />
    </div>
    <p class="table-empty-title">{{ title }}</p>
    <p v-if="description" class="table-empty-desc">{{ description }}</p>
  </div>
</template>
