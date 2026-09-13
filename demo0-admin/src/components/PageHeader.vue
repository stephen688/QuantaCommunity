<script setup>
/**
 * 列表页统一页头：图标色块 + 标题 + 描述 + 可选徽章（与计划 B5 / ui-ux-pro-max 一致：无 emoji、清晰层次）。
 */
defineProps({
  /** Element Plus 图标组件 */
  icon: {
    type: [Object, Function],
    default: null,
  },
  title: {
    type: String,
    required: true,
  },
  description: {
    type: String,
    default: '',
  },
  /** 可选角标文案，例如「待审核 3 条」 */
  badge: {
    type: String,
    default: '',
  },
  /** 徽章语义：primary | warning | muted */
  badgeTone: {
    type: String,
    default: 'primary',
    validator: (v) => ['primary', 'warning', 'muted'].includes(v),
  },
})
</script>

<template>
  <header class="page-header portal-fade-in-up">
    <div class="page-header-main">
      <div v-if="icon" class="page-header-icon" aria-hidden="true">
        <el-icon :size="22">
          <component :is="icon" />
        </el-icon>
      </div>
      <div class="page-header-text">
        <h1 class="page-header-title">{{ title }}</h1>
        <p v-if="description" class="page-header-desc">{{ description }}</p>
      </div>
    </div>
    <div class="page-header-aside">
      <span
        v-if="badge"
        class="page-header-badge portal-badge"
        :class="`portal-badge--${badgeTone}`"
      >
        {{ badge }}
      </span>
      <slot name="actions" />
    </div>
  </header>
</template>

<style scoped>
.page-header {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px 24px;
  margin-bottom: 4px;
  padding-bottom: 12px;
  border-bottom: 1px solid color-mix(in srgb, var(--admin-border) 65%, transparent);
}

.page-header-main {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  min-width: 0;
  flex: 1;
}

.page-header-icon {
  flex-shrink: 0;
  width: 44px;
  height: 44px;
  border-radius: var(--admin-radius);
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--admin-primary-subtle);
  color: var(--admin-primary);
  border: 1px solid color-mix(in srgb, var(--admin-primary) 14%, var(--admin-border));
  transition: var(--admin-transition);
}

.page-header:hover .page-header-icon {
  border-color: color-mix(in srgb, var(--admin-primary) 28%, var(--admin-border));
  box-shadow: var(--shadow-xs);
}

.page-header-text {
  min-width: 0;
}

.page-header-title {
  margin: 0;
  font-size: clamp(1.125rem, 2vw, 1.25rem);
  font-weight: 700;
  line-height: 1.35;
  color: var(--admin-text);
  letter-spacing: -0.02em;
}

.page-header-desc {
  margin: 6px 0 0;
  font-size: 13px;
  line-height: 1.55;
  color: var(--admin-text-muted);
  max-width: 42rem;
}

.page-header-aside {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  flex-wrap: wrap;
  gap: 10px;
  flex-shrink: 0;
}

.page-header-badge {
  white-space: nowrap;
}

.page-header-aside :deep(.el-button) {
  cursor: pointer;
  transition: var(--admin-transition);
}

.page-header-aside :deep(.el-button:focus-visible) {
  outline: 2px solid var(--admin-primary);
  outline-offset: 2px;
}

@media (max-width: 640px) {
  .page-header {
    gap: 12px;
    padding-bottom: 10px;
  }

  .page-header-aside {
    width: 100%;
    justify-content: flex-start;
  }
}

@media (prefers-reduced-motion: reduce) {
  .page-header {
    animation: none;
  }

  .page-header-icon {
    transition: none;
  }
}
</style>
