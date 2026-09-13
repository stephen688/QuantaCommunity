<script setup>
import { ref } from 'vue'
import { Warning } from '@element-plus/icons-vue'
import PageHeader from '../../components/PageHeader.vue'
import PostReportSection from './PostReportSection.vue'
import CommentReportSection from './CommentReportSection.vue'

const activeTab = ref('post')
</script>

<template>
  <div class="report-manage portal-page">
    <PageHeader
      :icon="Warning"
      title="举报管理"
      description="处理用户举报的帖子和评论"
    />
    <el-tabs v-model="activeTab" type="card" class="report-tabs report-tabs--modern">
      <el-tab-pane label="帖子举报" name="post" lazy>
        <PostReportSection />
      </el-tab-pane>
      <el-tab-pane label="评论举报" name="comment" lazy>
        <CommentReportSection />
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<style scoped>
.report-manage.portal-page {
  gap: 12px;
}

.report-tabs--modern :deep(.el-tabs__header) {
  margin: 0;
  border-bottom: 1px solid var(--admin-border);
  background: var(--admin-bg-2);
}

.report-tabs--modern :deep(.el-tabs__nav-wrap) {
  padding: 0 4px;
}

.report-tabs--modern :deep(.el-tabs__item) {
  cursor: pointer;
  border: 1px solid transparent;
  border-bottom: none;
  border-radius: var(--admin-radius) var(--admin-radius) 0 0;
  margin: 8px 4px 0;
  padding: 0 18px;
  height: 40px;
  line-height: 40px;
  font-weight: 600;
  color: var(--admin-text-muted);
  background: transparent;
  transition: color 0.15s ease, background 0.15s ease, box-shadow 0.15s ease;
}

.report-tabs--modern :deep(.el-tabs__item:hover) {
  color: var(--admin-primary);
}

.report-tabs--modern :deep(.el-tabs__item.is-active) {
  color: var(--admin-primary);
  background: var(--admin-surface);
  border-color: var(--admin-border);
  border-bottom-color: var(--admin-surface);
  box-shadow: inset 0 -2px 0 var(--admin-primary);
}

.report-tabs--modern :deep(.el-tabs__content) {
  padding: 16px;
  background: var(--admin-surface);
  border: 1px solid var(--admin-border);
  border-top: none;
  border-radius: 0 0 var(--admin-radius-lg) var(--admin-radius-lg);
}
</style>
