<script setup>
import { reactive, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Collection } from '@element-plus/icons-vue'
import PageHeader from '../../components/PageHeader.vue'
import TableEmptyState from '../../components/TableEmptyState.vue'
import { auditLogApi } from '../../api/auditLog'

const tableLoading = ref(false)
const list = ref([])
const total = ref(0)
const initialComplete = ref(false)
const loadError = ref(null)

const query = reactive({
  operatorId: undefined,
  action: undefined,
  targetType: undefined,
  targetId: undefined,
  startTime: undefined,
  endTime: undefined,
  pageNum: 1,
  pageSize: 10,
})

const actionOptions = [
  { value: 'CONTENT_AUDIT', label: '内容审核' },
  { value: 'CONTENT_DELETE', label: '内容删除' },
  { value: 'REPORT_HANDLE', label: '举报处理' },
  { value: 'IDENTITY_AUDIT', label: '身份审核' },
  { value: 'USER_BAN', label: '用户封禁' },
  { value: 'USER_UNBAN', label: '用户解封' },
  { value: 'EVENT_REPLAY', label: '事件重放' },
  { value: 'ROLE_GRANT', label: '角色授予' },
  { value: 'ROLE_REVOKE', label: '角色撤销' },
]

const targetTypeOptions = [
  { value: 'CONTENT', label: '帖子' },
  { value: 'ANSWER', label: '回答' },
  { value: 'COMMENT', label: '评论' },
  { value: 'REPORT', label: '举报' },
  { value: 'IDENTITY_AUTH', label: '身份认证' },
  { value: 'USER', label: '用户' },
  { value: 'OUTBOX', label: '事件发送' },
  { value: 'INBOX', label: '事件消费' },
  { value: 'USER_ROLE', label: '用户角色' },
]

function labelAction(value) {
  return actionOptions.find((item) => item.value === value)?.label || value || '-'
}

function labelTargetType(value) {
  return targetTypeOptions.find((item) => item.value === value)?.label || value || '-'
}

function labelResultStatus(value) {
  if (value === 'SUCCESS') return '成功'
  if (value === 'FAILED') return '失败'
  return value || '-'
}

function resultStatusType(value) {
  if (value === 'SUCCESS') return 'success'
  if (value === 'FAILED') return 'danger'
  return 'info'
}

function buildPageParams() {
  const params = {
    pageNum: query.pageNum,
    pageSize: query.pageSize,
  }
  if (query.operatorId) params.operatorId = query.operatorId
  if (query.action) params.action = query.action
  if (query.targetType) params.targetType = query.targetType
  if (query.targetId) params.targetId = query.targetId
  if (query.startTime) params.startTime = query.startTime
  if (query.endTime) params.endTime = query.endTime
  return params
}

async function fetchList() {
  tableLoading.value = true
  loadError.value = null
  try {
    const res = await auditLogApi.page(buildPageParams())
    list.value = res.list || []
    total.value = res.total || 0
  } catch (e) {
    loadError.value = e?.message || '加载失败'
    ElMessage.error(loadError.value)
  } finally {
    tableLoading.value = false
    initialComplete.value = true
  }
}

function onSearch() {
  query.pageNum = 1
  fetchList()
}

function onReset() {
  query.operatorId = undefined
  query.action = undefined
  query.targetType = undefined
  query.targetId = undefined
  query.startTime = undefined
  query.endTime = undefined
  query.pageNum = 1
  query.pageSize = 10
  fetchList()
}

function onPageChange(page) {
  query.pageNum = page
  fetchList()
}

function onSizeChange(size) {
  query.pageSize = size
  query.pageNum = 1
  fetchList()
}

const detailVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)

async function openDetail(row) {
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = await auditLogApi.detail(row.id)
  } catch (e) {
    ElMessage.error(e?.message || '加载详情失败')
  } finally {
    detailLoading.value = false
  }
}

function formatJson(value) {
  if (value === null || value === undefined || value === '') {
    return '-'
  }
  if (typeof value === 'string') {
    return value
  }
  try {
    return JSON.stringify(value, null, 2)
  } catch {
    return String(value)
  }
}

onMounted(() => {
  fetchList()
})
</script>

<template>
  <div class="audit-log-list portal-page">
    <PageHeader
      :icon="Collection"
      title="审计日志"
      description="查询管理员高风险操作记录，用于安全审计与问题回溯"
    />

    <el-card shadow="never" class="toolbar-card portal-panel">
      <el-form :inline="true" :model="query" class="filter-form" @submit.prevent="onSearch">
        <el-form-item label="操作人ID">
          <el-input v-model.number="query.operatorId" placeholder="管理员用户ID" clearable class="filter-input-sm" />
        </el-form-item>
        <el-form-item label="操作类型">
          <el-select v-model="query.action" placeholder="全部" clearable class="filter-input-sm">
            <el-option
              v-for="item in actionOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="目标类型">
          <el-select v-model="query.targetType" placeholder="全部" clearable class="filter-input-sm">
            <el-option
              v-for="item in targetTypeOptions"
              :key="item.value"
              :label="item.label"
              :value="item.value"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="目标ID">
          <el-input v-model="query.targetId" placeholder="目标ID" clearable class="filter-input-sm" />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" @click="onSearch">查询</el-button>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="table-card portal-panel">
      <el-table
        v-loading="tableLoading"
        :data="list"
        stripe
        class="audit-log-table portal-table"
      >
        <el-table-column prop="id" label="日志ID" width="90" />
        <el-table-column prop="operatorId" label="操作人ID" width="100" />
        <el-table-column prop="operatorRoles" label="当时角色" min-width="140" show-overflow-tooltip />
        <el-table-column label="操作类型" width="120">
          <template #default="{ row }">
            {{ labelAction(row.action) }}
          </template>
        </el-table-column>
        <el-table-column label="目标类型" width="120">
          <template #default="{ row }">
            {{ labelTargetType(row.targetType) }}
          </template>
        </el-table-column>
        <el-table-column prop="targetId" label="目标ID" width="100" show-overflow-tooltip />
        <el-table-column label="结果" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="resultStatusType(row.resultStatus)" size="small">
              {{ labelResultStatus(row.resultStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="clientIp" label="客户端IP" width="130" />
        <el-table-column prop="createdAt" label="操作时间" width="170" />
        <el-table-column label="操作" width="90" fixed="right" align="center">
          <template #default="{ row }">
            <el-button type="primary" link size="small" @click="openDetail(row)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>

      <TableEmptyState
        v-if="initialComplete && !tableLoading && list.length === 0"
        :error="loadError"
        title="暂无审计日志"
        description="当前查询条件没有匹配记录，或审计功能尚未产生数据。"
      />

      <div class="table-pagination">
        <el-pagination
          v-model:current-page="query.pageNum"
          v-model:page-size="query.pageSize"
          :page-sizes="[10, 20, 50, 100]"
          :total="total"
          layout="total, sizes, prev, pager, next, jumper"
          @change="fetchList"
          @size-change="onSizeChange"
          @current-change="onPageChange"
        />
      </div>
    </el-card>

    <el-drawer
      v-model="detailVisible"
      title="审计详情"
      size="560px"
      class="audit-detail-drawer portal-drawer"
      destroy-on-close
    >
      <div v-if="detailLoading" v-loading="detailLoading" class="drawer-loading" />
      <div v-else-if="detail" class="audit-detail-body">
        <el-descriptions class="portal-details-in-drawer" title="基本信息" :column="1" border>
          <el-descriptions-item label="日志ID">{{ detail.id ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="请求ID">{{ detail.requestId ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="操作人ID">{{ detail.operatorId ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="当时角色">{{ detail.operatorRoles ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="操作类型">{{ labelAction(detail.action) }}</el-descriptions-item>
          <el-descriptions-item label="目标类型">{{ labelTargetType(detail.targetType) }}</el-descriptions-item>
          <el-descriptions-item label="目标ID">{{ detail.targetId ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="执行结果">
            <el-tag :type="resultStatusType(detail.resultStatus)" size="small">
              {{ labelResultStatus(detail.resultStatus) }}
            </el-tag>
          </el-descriptions-item>
          <el-descriptions-item label="客户端IP">{{ detail.clientIp ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="User-Agent">{{ detail.userAgent ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="操作时间">{{ detail.createdAt ?? '-' }}</el-descriptions-item>
        </el-descriptions>

        <el-descriptions class="portal-details-in-drawer" title="变更摘要" :column="1" border>
          <el-descriptions-item label="变更前">
            <pre class="json-block">{{ formatJson(detail.beforeSummary) }}</pre>
          </el-descriptions-item>
          <el-descriptions-item label="变更后">
            <pre class="json-block">{{ formatJson(detail.afterSummary) }}</pre>
          </el-descriptions-item>
        </el-descriptions>

        <el-descriptions
          v-if="detail.errorMessage"
          class="portal-details-in-drawer"
          title="失败信息"
          :column="1"
          border
        >
          <el-descriptions-item label="错误信息">
            <pre class="json-block error-block">{{ detail.errorMessage }}</pre>
          </el-descriptions-item>
        </el-descriptions>
      </div>
    </el-drawer>
  </div>
</template>

<style scoped>
.audit-log-list {
  display: grid;
  gap: 16px;
}

.audit-log-table :deep(.cell) {
  white-space: nowrap;
}

.json-block {
  margin: 0;
  padding: 12px;
  background: var(--admin-bg-soft, #f6f7f9);
  border: 1px solid var(--admin-border, #e4e7ec);
  border-radius: 8px;
  font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace;
  font-size: 0.8125rem;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  max-height: 240px;
  overflow: auto;
}

.error-block {
  background: var(--admin-danger-soft, #fef2f2);
  border-color: var(--admin-danger-light, #fecaca);
  color: var(--admin-danger, #b91c1c);
}

.drawer-loading {
  min-height: 200px;
}
</style>
