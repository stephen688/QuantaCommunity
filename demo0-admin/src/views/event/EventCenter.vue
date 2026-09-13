<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Connection, Refresh, Tickets } from '@element-plus/icons-vue'
import PageHeader from '../../components/PageHeader.vue'
import TableEmptyState from '../../components/TableEmptyState.vue'
import { usePermission } from '../../composables/usePermission'
import { eventApi } from '../../api/event'

const { hasAuthority } = usePermission()

const eventTypes = [
  'MODERATION_REQUESTED',
  'NOTIFICATION_REQUESTED',
  'FEED_UPSERT_REQUESTED',
  'FEED_DELETE_REQUESTED',
  'HOT_SCORE_RECALCULATE_REQUESTED',
  'SEARCH_RECONCILE_REQUESTED',
]

const activeTab = ref('outbox')
const overviewLoading = ref(false)
const overview = ref({
  outboxCounts: {},
  inboxCounts: {},
  retryDistribution: [],
  averageSendLatencyMs: null,
  longestBacklogSeconds: 0,
})

const outboxLoading = ref(false)
const outboxRows = ref([])
const outboxTotal = ref(0)
const outboxQuery = reactive(createBaseQuery())

const inboxLoading = ref(false)
const inboxRows = ref([])
const inboxTotal = ref(0)
const inboxQuery = reactive({ ...createBaseQuery(), consumerName: '' })

const detailVisible = ref(false)
const detailLoading = ref(false)
const detailKind = ref('outbox')
const detail = ref(null)
const replayLoading = ref(false)

function createBaseQuery() {
  return {
    eventId: '',
    eventType: '',
    aggregateType: '',
    aggregateId: undefined,
    status: '',
    createTimeRange: [],
    pageNum: 1,
    pageSize: 10,
  }
}

function compactParams(query) {
  const params = {
    pageNum: query.pageNum,
    pageSize: query.pageSize,
  }
  for (const key of ['eventId', 'eventType', 'aggregateType', 'aggregateId', 'status', 'consumerName']) {
    if (query[key] !== '' && query[key] !== undefined && query[key] !== null) {
      params[key] = query[key]
    }
  }
  if (Array.isArray(query.createTimeRange) && query.createTimeRange.length === 2) {
    params.createTimeStart = query.createTimeRange[0]
    params.createTimeEnd = query.createTimeRange[1]
  }
  return params
}

async function loadOverview(silent = false) {
  if (!silent) overviewLoading.value = true
  try {
    overview.value = (await eventApi.overview(silent ? { skipGlobalError: true } : {})) ?? overview.value
  } finally {
    if (!silent) overviewLoading.value = false
  }
}

async function loadOutbox() {
  outboxLoading.value = true
  try {
    const data = await eventApi.outboxPage(compactParams(outboxQuery))
    outboxRows.value = Array.isArray(data?.records) ? data.records : []
    outboxTotal.value = Number(data?.total ?? 0)
  } finally {
    outboxLoading.value = false
  }
}

async function loadInbox() {
  inboxLoading.value = true
  try {
    const data = await eventApi.inboxPage(compactParams(inboxQuery))
    inboxRows.value = Array.isArray(data?.records) ? data.records : []
    inboxTotal.value = Number(data?.total ?? 0)
  } finally {
    inboxLoading.value = false
  }
}

async function refreshAll() {
  await Promise.all([loadOverview(), activeTab.value === 'outbox' ? loadOutbox() : loadInbox()])
}

function searchCurrent() {
  if (activeTab.value === 'outbox') {
    outboxQuery.pageNum = 1
    loadOutbox()
  } else {
    inboxQuery.pageNum = 1
    loadInbox()
  }
}

function resetCurrent() {
  const target = activeTab.value === 'outbox' ? outboxQuery : inboxQuery
  const clean = activeTab.value === 'outbox' ? createBaseQuery() : { ...createBaseQuery(), consumerName: '' }
  Object.assign(target, clean)
  activeTab.value === 'outbox' ? loadOutbox() : loadInbox()
}

function switchTab(name) {
  activeTab.value = name
  if (name === 'outbox' && outboxRows.value.length === 0) loadOutbox()
  if (name === 'inbox' && inboxRows.value.length === 0) loadInbox()
}

function selectStatus(kind, status) {
  activeTab.value = kind
  const query = kind === 'outbox' ? outboxQuery : inboxQuery
  query.status = query.status === status ? '' : status
  query.pageNum = 1
  kind === 'outbox' ? loadOutbox() : loadInbox()
}

function changePage(kind, page) {
  const query = kind === 'outbox' ? outboxQuery : inboxQuery
  query.pageNum = page
  kind === 'outbox' ? loadOutbox() : loadInbox()
}

function changePageSize(kind, size) {
  const query = kind === 'outbox' ? outboxQuery : inboxQuery
  query.pageSize = size
  query.pageNum = 1
  kind === 'outbox' ? loadOutbox() : loadInbox()
}

async function openDetail(kind, row) {
  detailKind.value = kind
  detailVisible.value = true
  detailLoading.value = true
  detail.value = null
  try {
    detail.value = kind === 'outbox'
      ? await eventApi.outboxDetail(row.eventId)
      : await eventApi.inboxDetail(row.consumerName, row.eventId)
  } finally {
    detailLoading.value = false
  }
}

async function replay(kind, row) {
  const label = kind === 'outbox' ? '发送事件' : '消费记录'
  await ElMessageBox.confirm(
    `只会重放当前 DEAD ${label}，并保留原 eventId 和审计记录。确认继续？`,
    '确认重放',
    {
      confirmButtonText: '确认重放',
      cancelButtonText: '取消',
      type: 'warning',
    },
  )

  replayLoading.value = true
  try {
    if (kind === 'outbox') {
      await eventApi.replayOutbox(row.eventId)
    } else {
      await eventApi.replayInbox(row.consumerName, row.eventId)
    }
    ElMessage.success('已恢复为待处理状态')
    detailVisible.value = false
    await refreshAll()
  } finally {
    replayLoading.value = false
  }
}

function statusTone(status) {
  if (status === 'SENT' || status === 'SUCCESS') return 'success'
  if (status === 'DEAD') return 'danger'
  if (status === 'PROCESSING' || status === 'RETRYING') return 'warning'
  if (status === 'PENDING') return 'info'
  return 'muted'
}

function shortId(value) {
  const text = String(value ?? '')
  return text.length > 18 ? `${text.slice(0, 8)}…${text.slice(-6)}` : text || '—'
}

function formatPayload(payload) {
  if (!payload) return '—'
  try {
    return JSON.stringify(typeof payload === 'string' ? JSON.parse(payload) : payload, null, 2)
  } catch {
    return String(payload)
  }
}

function formatLatency(value) {
  if (value === null || value === undefined) return '暂无成功样本'
  const ms = Number(value)
  if (ms >= 1000) return `${(ms / 1000).toFixed(2)} 秒`
  return `${ms.toFixed(0)} 毫秒`
}

function formatDuration(seconds) {
  const value = Number(seconds ?? 0)
  if (value < 60) return `${value} 秒`
  if (value < 3600) return `${Math.floor(value / 60)} 分 ${value % 60} 秒`
  return `${Math.floor(value / 3600)} 小时 ${Math.floor((value % 3600) / 60)} 分`
}

const deadTotal = computed(
  () => Number(overview.value?.outboxCounts?.DEAD ?? 0)
    + Number(overview.value?.inboxCounts?.DEAD ?? 0),
)

const headerBadge = computed(() => (deadTotal.value > 0 ? `待处理异常 ${deadTotal.value} 条` : '链路状态正常'))
const headerBadgeTone = computed(() => (deadTotal.value > 0 ? 'warning' : 'primary'))
const currentQuery = computed(() => (activeTab.value === 'outbox' ? outboxQuery : inboxQuery))

onMounted(() => {
  Promise.all([loadOverview(), loadOutbox()])
})
</script>

<template>
  <div class="event-center portal-page">
    <PageHeader
      :icon="Connection"
      title="事件中心"
      description="查看可靠事件投递与消费状态，只对 DEAD 记录执行安全重放"
      :badge="headerBadge"
      :badge-tone="headerBadgeTone"
    >
      <template #actions>
        <el-button :icon="Refresh" :loading="overviewLoading" @click="refreshAll">刷新</el-button>
      </template>
    </PageHeader>

    <section class="event-status-rail" aria-label="事件状态概览">
      <button
        v-for="status in ['PENDING', 'PROCESSING', 'SENT', 'DEAD']"
        :key="`outbox-${status}`"
        type="button"
        class="status-cell"
        :class="{ 'is-active': activeTab === 'outbox' && outboxQuery.status === status }"
        @click="selectStatus('outbox', status)"
      >
        <span class="status-scope">Outbox</span>
        <strong>{{ Number(overview.outboxCounts?.[status] ?? 0) }}</strong>
        <span class="portal-badge" :class="`portal-badge--${statusTone(status)}`">{{ status }}</span>
      </button>
      <button
        v-for="status in ['PROCESSING', 'RETRYING', 'SUCCESS', 'DEAD']"
        :key="`inbox-${status}`"
        type="button"
        class="status-cell"
        :class="{ 'is-active': activeTab === 'inbox' && inboxQuery.status === status }"
        @click="selectStatus('inbox', status)"
      >
        <span class="status-scope">Inbox</span>
        <strong>{{ Number(overview.inboxCounts?.[status] ?? 0) }}</strong>
        <span class="portal-badge" :class="`portal-badge--${statusTone(status)}`">{{ status }}</span>
      </button>
    </section>

    <el-card shadow="never" class="portal-panel event-metrics">
      <div class="metric-item">
        <span>平均发送延迟</span>
        <strong>{{ formatLatency(overview.averageSendLatencyMs) }}</strong>
      </div>
      <div class="metric-item">
        <span>最长积压时间</span>
        <strong>{{ formatDuration(overview.longestBacklogSeconds) }}</strong>
      </div>
      <div class="metric-item metric-item--wide">
        <span>重试分布</span>
        <div class="retry-distribution">
          <span v-if="!overview.retryDistribution?.length" class="metric-empty">暂无事件</span>
          <span v-for="item in overview.retryDistribution" :key="item.retryCount" class="retry-chip">
            {{ item.retryCount }} 次：{{ item.eventCount }} 条
          </span>
        </div>
      </div>
    </el-card>

    <el-card shadow="never" class="portal-panel event-workbench">
      <el-tabs :model-value="activeTab" class="event-tabs" @tab-change="switchTab">
        <el-tab-pane label="Outbox 发送事件" name="outbox" />
        <el-tab-pane label="Inbox 消费记录" name="inbox" />
      </el-tabs>

      <el-form
        :inline="true"
        :model="currentQuery"
        class="filter-form event-filter"
        @submit.prevent="searchCurrent"
      >
        <el-form-item label="事件 ID">
          <el-input
            v-model="currentQuery.eventId"
            clearable
            placeholder="完整 eventId"
            style="width: 210px"
          />
        </el-form-item>
        <el-form-item v-if="activeTab === 'inbox'" label="消费者">
          <el-input v-model="inboxQuery.consumerName" clearable placeholder="consumerName" style="width: 180px" />
        </el-form-item>
        <el-form-item label="事件类型">
          <el-select
            v-model="currentQuery.eventType"
            clearable
            filterable
            placeholder="全部"
            style="width: 230px"
          >
            <el-option v-for="type in eventTypes" :key="type" :label="type" :value="type" />
          </el-select>
        </el-form-item>
        <el-form-item label="状态">
          <el-select
            v-model="currentQuery.status"
            clearable
            placeholder="全部"
            style="width: 150px"
          >
            <el-option
              v-for="status in activeTab === 'outbox'
                ? ['PENDING', 'PROCESSING', 'SENT', 'DEAD']
                : ['PROCESSING', 'RETRYING', 'SUCCESS', 'DEAD']"
              :key="status"
              :label="status"
              :value="status"
            />
          </el-select>
        </el-form-item>
        <el-form-item label="业务对象">
          <el-input
            v-model="currentQuery.aggregateType"
            clearable
            placeholder="类型"
            style="width: 120px"
          />
        </el-form-item>
        <el-form-item label="业务 ID">
          <el-input-number
            v-model="currentQuery.aggregateId"
            :min="1"
            :controls="false"
            placeholder="ID"
            style="width: 120px"
          />
        </el-form-item>
        <el-form-item label="创建时间">
          <el-date-picker
            v-model="currentQuery.createTimeRange"
            type="datetimerange"
            start-placeholder="开始时间"
            end-placeholder="结束时间"
            value-format="YYYY-MM-DD HH:mm:ss"
            style="width: 360px"
          />
        </el-form-item>
        <el-form-item>
          <el-button type="primary" native-type="submit">搜索</el-button>
          <el-button @click="resetCurrent">重置</el-button>
        </el-form-item>
      </el-form>

      <el-table
        v-if="activeTab === 'outbox'"
        v-loading="outboxLoading"
        :data="outboxRows"
        class="portal-table"
        row-key="eventId"
      >
        <template #empty>
          <TableEmptyState :icon="Tickets" title="暂无 Outbox 事件" description="调整筛选条件，或先触发一条业务事件。" />
        </template>
        <el-table-column label="事件 ID" min-width="180">
          <template #default="{ row }"><span class="event-id" :title="row.eventId">{{ shortId(row.eventId) }}</span></template>
        </el-table-column>
        <el-table-column prop="eventType" label="事件类型" min-width="220" show-overflow-tooltip />
        <el-table-column label="业务对象" min-width="150">
          <template #default="{ row }">{{ row.aggregateType }} / {{ row.aggregateId }}</template>
        </el-table-column>
        <el-table-column label="状态" width="126">
          <template #default="{ row }"><span class="portal-badge" :class="`portal-badge--${statusTone(row.status)}`">{{ row.status }}</span></template>
        </el-table-column>
        <el-table-column prop="retryCount" label="重试" width="74" align="center" />
        <el-table-column prop="createTime" label="创建时间" width="178" />
        <el-table-column label="操作" width="144" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="openDetail('outbox', row)">详情</el-button>
            <el-button v-if="row.status === 'DEAD' && hasAuthority('EVENT_REPLAY')" type="danger" link @click="replay('outbox', row)">重放</el-button>
          </template>
        </el-table-column>
      </el-table>

      <el-table
        v-else
        v-loading="inboxLoading"
        :data="inboxRows"
        class="portal-table"
        row-key="id"
      >
        <template #empty>
          <TableEmptyState :icon="Tickets" title="暂无 Inbox 记录" description="消费事件后会在这里留下处理结果。" />
        </template>
        <el-table-column prop="consumerName" label="消费者" min-width="190" show-overflow-tooltip />
        <el-table-column label="事件 ID" min-width="180">
          <template #default="{ row }"><span class="event-id" :title="row.eventId">{{ shortId(row.eventId) }}</span></template>
        </el-table-column>
        <el-table-column prop="eventType" label="事件类型" min-width="220" show-overflow-tooltip />
        <el-table-column label="状态" width="126">
          <template #default="{ row }"><span class="portal-badge" :class="`portal-badge--${statusTone(row.status)}`">{{ row.status }}</span></template>
        </el-table-column>
        <el-table-column prop="retryCount" label="重试" width="74" align="center" />
        <el-table-column prop="createTime" label="创建时间" width="178" />
        <el-table-column label="操作" width="144" fixed="right">
          <template #default="{ row }">
            <el-button type="primary" link @click="openDetail('inbox', row)">详情</el-button>
            <el-button v-if="row.status === 'DEAD' && hasAuthority('EVENT_REPLAY')" type="danger" link @click="replay('inbox', row)">重放</el-button>
          </template>
        </el-table-column>
      </el-table>

      <div class="pager">
        <el-pagination
          v-if="activeTab === 'outbox'"
          background
          :current-page="outboxQuery.pageNum"
          :page-size="outboxQuery.pageSize"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          :total="outboxTotal"
          @current-change="(page) => changePage('outbox', page)"
          @size-change="(size) => changePageSize('outbox', size)"
        />
        <el-pagination
          v-else
          background
          :current-page="inboxQuery.pageNum"
          :page-size="inboxQuery.pageSize"
          :page-sizes="[10, 20, 50, 100]"
          layout="total, sizes, prev, pager, next, jumper"
          :total="inboxTotal"
          @current-change="(page) => changePage('inbox', page)"
          @size-change="(size) => changePageSize('inbox', size)"
        />
      </div>
    </el-card>

    <el-drawer
      v-model="detailVisible"
      :title="detailKind === 'outbox' ? 'Outbox 事件详情' : 'Inbox 消费详情'"
      size="560px"
      destroy-on-close
      class="portal-drawer"
    >
      <div v-loading="detailLoading" class="event-detail">
        <template v-if="detail">
          <div class="detail-badges">
            <span class="portal-badge portal-badge--muted">{{ shortId(detail.eventId) }}</span>
            <span class="portal-badge" :class="`portal-badge--${statusTone(detail.status)}`">{{ detail.status }}</span>
            <span class="portal-badge portal-badge--muted">重试 {{ detail.retryCount ?? 0 }} 次</span>
          </div>
          <el-descriptions :column="1" border class="event-descriptions">
            <el-descriptions-item label="完整事件 ID">{{ detail.eventId }}</el-descriptions-item>
            <el-descriptions-item v-if="detail.consumerName" label="消费者">{{ detail.consumerName }}</el-descriptions-item>
            <el-descriptions-item label="事件类型">{{ detail.eventType }}</el-descriptions-item>
            <el-descriptions-item label="业务对象">{{ detail.aggregateType }} / {{ detail.aggregateId }}</el-descriptions-item>
            <el-descriptions-item label="租约实例">{{ detail.lockedBy || '—' }}</el-descriptions-item>
            <el-descriptions-item label="租约到期">{{ detail.lockedUntil || '—' }}</el-descriptions-item>
            <el-descriptions-item label="最后错误">{{ detail.lastError || '—' }}</el-descriptions-item>
            <el-descriptions-item label="人工重放">{{ detail.replayCount ?? 0 }} 次</el-descriptions-item>
            <el-descriptions-item label="创建时间">{{ detail.createTime || '—' }}</el-descriptions-item>
            <el-descriptions-item label="更新时间">{{ detail.updateTime || '—' }}</el-descriptions-item>
          </el-descriptions>
          <section v-if="detailKind === 'outbox'" class="payload-section">
            <h3>原始 payload</h3>
            <pre>{{ formatPayload(detail.payload) }}</pre>
          </section>
        </template>
      </div>
      <template #footer>
        <el-button @click="detailVisible = false">关闭</el-button>
        <el-button
          v-if="detail?.status === 'DEAD' && hasAuthority('EVENT_REPLAY')"
          type="danger"
          :loading="replayLoading"
          @click="replay(detailKind, detail)"
        >
          确认重放
        </el-button>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.event-center {
  display: grid;
  gap: 16px;
}

.event-status-rail {
  display: grid;
  grid-template-columns: repeat(8, minmax(112px, 1fr));
  overflow-x: auto;
  border: 1px solid var(--admin-border);
  border-radius: var(--admin-radius-lg);
  background: var(--admin-surface);
}

.status-cell {
  min-width: 112px;
  padding: 12px 14px;
  display: grid;
  grid-template-columns: 1fr auto;
  gap: 6px 10px;
  text-align: left;
  font: inherit;
  color: var(--admin-text);
  background: transparent;
  border: none;
  border-right: 1px solid var(--admin-border);
  cursor: pointer;
  transition: var(--admin-transition);
}

.status-cell:last-child {
  border-right: none;
}

.status-cell:hover,
.status-cell.is-active {
  background: var(--admin-primary-subtle);
}

.status-cell.is-active {
  box-shadow: inset 0 -3px 0 var(--admin-primary);
}

.status-cell:focus-visible {
  position: relative;
  outline: 2px solid var(--admin-primary);
  outline-offset: -2px;
}

.status-cell strong {
  grid-row: span 2;
  align-self: center;
  font-size: 22px;
  line-height: 1;
}

.status-scope {
  color: var(--admin-text-muted);
  font-size: 11px;
  font-weight: 700;
  letter-spacing: 0.08em;
  text-transform: uppercase;
}

.status-cell .portal-badge {
  justify-self: start;
}

.event-metrics :deep(.el-card__body) {
  display: grid;
  grid-template-columns: minmax(180px, 0.7fr) minmax(180px, 0.7fr) minmax(260px, 1.6fr);
  gap: 0;
  padding: 0;
}

.metric-item {
  min-height: 74px;
  padding: 14px 18px;
  display: flex;
  flex-direction: column;
  justify-content: center;
  gap: 7px;
  border-right: 1px solid var(--admin-border);
}

.metric-item:last-child {
  border-right: none;
}

.metric-item > span:first-child {
  color: var(--admin-text-muted);
  font-size: 12px;
  font-weight: 600;
}

.metric-item strong {
  color: var(--admin-text);
  font-size: 16px;
}

.retry-distribution {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
}

.retry-chip {
  padding: 4px 8px;
  border-radius: 999px;
  color: var(--admin-text-label);
  background: var(--admin-bg-2);
  font-size: 12px;
}

.metric-empty {
  color: var(--admin-text-muted);
  font-size: 13px;
}

.event-workbench :deep(.el-card__body) {
  padding-top: 8px;
}

.event-tabs :deep(.el-tabs__header) {
  margin-bottom: 16px;
}

.event-filter {
  margin-bottom: 6px;
}

.event-id {
  font-family: ui-monospace, SFMono-Regular, Consolas, monospace;
  font-size: 12px;
  color: var(--admin-text-label);
}

.pager {
  display: flex;
  justify-content: flex-end;
  padding-top: 16px;
}

.event-detail {
  min-height: 180px;
}

.detail-badges {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}

.event-descriptions :deep(.el-descriptions__label) {
  width: 112px;
  color: var(--admin-text-label);
  font-weight: 600;
}

.payload-section {
  margin-top: 20px;
}

.payload-section h3 {
  margin: 0 0 10px;
  font-size: 14px;
  color: var(--admin-text);
}

.payload-section pre {
  max-height: 360px;
  margin: 0;
  padding: 14px;
  overflow: auto;
  border: 1px solid var(--admin-border);
  border-radius: var(--admin-radius);
  color: var(--admin-text-label);
  background: var(--admin-surface-soft);
  font: 12px/1.6 ui-monospace, SFMono-Regular, Consolas, monospace;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}

@media (max-width: 900px) {
  .event-metrics :deep(.el-card__body) {
    grid-template-columns: 1fr;
  }

  .metric-item {
    border-right: none;
    border-bottom: 1px solid var(--admin-border);
  }

  .metric-item:last-child {
    border-bottom: none;
  }
}

@media (prefers-reduced-motion: reduce) {
  .status-cell {
    transition: none;
  }
}
</style>
