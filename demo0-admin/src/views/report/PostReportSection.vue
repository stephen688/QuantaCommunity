<script setup>
import { computed, reactive, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Clock, Refresh, CircleCheck, CircleClose, Document } from '@element-plus/icons-vue'
import { contentApi } from '../../api/content'
import { publicReadApi } from '../../api/publicRead'
import { usePermission } from '../../composables/usePermission'
import TableEmptyState from '../../components/TableEmptyState.vue'

const { hasAuthority } = usePermission()

const tableLoading = ref(false)
const list = ref([])
const total = ref(0)
const initialComplete = ref(false)
const loadError = ref(null)

const query = reactive({
  status: undefined,
  pageNum: 1,
  pageSize: 10,
})

const handleVisible = ref(false)
const handleLoading = ref(false)
const currentReport = ref(null)
const handleForm = reactive({
  handleResult: 1,
  handleRemark: '',
})

const drawerVisible = ref(false)
const detailLoading = ref(false)
const detailReport = ref(null)
const contentDetail = ref(null)

const handleTargetLoading = ref(false)
const handleTargetDetail = ref(null)

const postTypeLabel = { 1: '生活求助', 2: '专业问答' }
const postAuditLabel = { 0: '待审核', 1: '已通过', 2: '已驳回' }

const reportTypeLabel = {
  1: '垃圾广告',
  2: '人身攻击',
  3: '违规内容',
  4: '虚假信息',
  5: '其他',
}

const statusLabel = {
  0: '待处理',
  1: '处理中',
  2: '已处理',
  3: '已驳回',
}

const handleResultSource = [
  { value: 1, label: '删除帖子', requiredAuthority: 'CONTENT_DELETE' },
  { value: 2, label: '警告用户' },
  { value: 3, label: '删除帖子 + 警告用户', requiredAuthority: 'CONTENT_DELETE' },
  { value: 4, label: '驳回举报' },
]

const handleResultOptions = computed(() =>
  handleResultSource.filter((opt) => {
    if (!opt.requiredAuthority) {
      return true
    }
    return hasAuthority(opt.requiredAuthority)
  })
)

function buildPageParams() {
  const p = { pageNum: query.pageNum, pageSize: query.pageSize }
  if (query.status !== undefined && query.status !== null && query.status !== '') {
    p.status = query.status
  }
  return p
}

async function fetchList() {
  loadError.value = null
  tableLoading.value = true
  try {
    const data = await contentApi.reportPage(buildPageParams())
    total.value = Number(data?.total ?? 0)
    list.value = Array.isArray(data?.records) ? data.records : []
  } catch (err) {
    list.value = []
    total.value = 0
    loadError.value = { message: friendlyListError(err) }
  } finally {
    tableLoading.value = false
    initialComplete.value = true
  }
}

function friendlyListError(err) {
  const status = err?.response?.status
  const dataMsg = err?.response?.data?.msg
  if (status === 502 || status === 503) return '服务暂时不可用（网关或上游异常），请稍后重试。'
  if (status === 504) return '网关超时，请稍后重试。'
  if (!err?.response) return '无法连接服务器，请确认网络或服务是否已启动。'
  return dataMsg || err?.message || '列表加载失败，请重试。'
}

function dismissError() {
  loadError.value = null
}

function retryFetch() {
  fetchList()
}

function onSearch() {
  query.pageNum = 1
  fetchList()
}

function onReset() {
  query.status = undefined
  query.pageNum = 1
  query.pageSize = 10
  fetchList()
}

function onPageChange(p) {
  query.pageNum = p
  fetchList()
}

function onSizeChange(s) {
  query.pageSize = s
  query.pageNum = 1
  fetchList()
}

function labelReportType(v) {
  if (v === undefined || v === null) return '-'
  return reportTypeLabel[v] ?? String(v)
}

function labelStatus(v) {
  if (v === undefined || v === null) return '-'
  return statusLabel[v] ?? String(v)
}

function statusTagType(v) {
  if (v === 0) return 'warning'
  if (v === 1) return 'info'
  if (v === 2) return 'success'
  if (v === 3) return 'danger'
  return ''
}

function labelPostType(v) {
  if (v === undefined || v === null) return '-'
  return postTypeLabel[v] ?? String(v)
}

function labelPostAudit(v) {
  if (v === undefined || v === null) return '-'
  return postAuditLabel[v] ?? String(v)
}

function portalReportStatusBadgeMod(v) {
  if (v === 0) return 'warning'
  if (v === 1) return 'info'
  if (v === 2) return 'success'
  if (v === 3) return 'danger'
  return 'muted'
}

function portalPostAuditBadgeMod(v) {
  if (v === 0) return 'warning'
  if (v === 1) return 'success'
  if (v === 2) return 'danger'
  return 'muted'
}

function canHandle(row) {
  return row.status === 0 || row.status === 1
}

async function openDetail(row) {
  detailReport.value = row
  contentDetail.value = null
  drawerVisible.value = true
  detailLoading.value = true
  try {
    contentDetail.value = await publicReadApi.getContentDetail(row.contentId)
  } catch {
    contentDetail.value = null
  } finally {
    detailLoading.value = false
  }
}

function onDetailClosed() {
  detailReport.value = null
  contentDetail.value = null
}

async function openHandle(row) {
  currentReport.value = row
  handleForm.handleResult = 1
  handleForm.handleRemark = ''
  handleTargetDetail.value = null
  handleVisible.value = true
  handleTargetLoading.value = true
  try {
    handleTargetDetail.value = await publicReadApi.getContentDetail(row.contentId)
  } catch {
    handleTargetDetail.value = null
  } finally {
    handleTargetLoading.value = false
  }
}

async function submitHandle() {
  if (!currentReport.value) return
  handleLoading.value = true
  try {
    await contentApi.reportHandle({
      reportId: currentReport.value.id,
      handleResult: handleForm.handleResult,
      handleRemark: handleForm.handleRemark?.trim() || undefined,
    })
    ElMessage.success('处理成功')
    handleVisible.value = false
    await fetchList()
  } finally {
    handleLoading.value = false
  }
}

function onHandleDialogClosed() {
  currentReport.value = null
  handleTargetDetail.value = null
}

const showListSkeleton = computed(() => tableLoading.value && !initialComplete.value)

onMounted(() => {
  fetchList()
})
</script>

<template>
  <div class="report-section">
    <el-card shadow="never" class="toolbar-card portal-panel">
      <el-form :inline="true" :model="query" class="filter-form" @submit.prevent="onSearch">
        <el-form-item label="处理状态">
          <el-select v-model="query.status" placeholder="全部" clearable style="width: 140px">
            <el-option label="待处理" :value="0" />
            <el-option label="处理中" :value="1" />
            <el-option label="已处理" :value="2" />
            <el-option label="已驳回" :value="3" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" native-type="submit">搜索</el-button>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="table-card portal-panel">
      <div v-if="loadError" class="list-error-bar" role="status">
        <span class="list-error-text">{{ loadError.message }}</span>
        <div class="list-error-actions">
          <el-button type="primary" link class="list-error-retry" @click="retryFetch">重试</el-button>
          <el-button text class="list-error-dismiss" @click="dismissError">关闭</el-button>
        </div>
      </div>

      <div v-if="showListSkeleton" class="list-skeleton" aria-busy="true" aria-label="加载帖子举报列表">
        <el-skeleton :rows="8" animated />
      </div>

      <el-table
        v-show="!showListSkeleton"
        v-loading="tableLoading"
        class="portal-table"
        :data="list"
        row-key="id"
        size="small"
        style="width: 100%"
      >
        <template #empty>
          <TableEmptyState
            v-if="!loadError"
            :icon="Document"
            title="暂无符合条件的帖子举报"
            description="尝试调整筛选条件后重试。"
          />
          <TableEmptyState
            v-else
            error
            title="列表未展示"
            description="请查看上方提示或使用「重试」。"
          />
        </template>
        <el-table-column prop="id" label="举报ID" width="88" />
        <el-table-column prop="contentId" label="帖子ID" width="88" />
        <el-table-column label="举报人" width="144" show-overflow-tooltip>
          <template #default="{ row }">
            <span v-if="row.reporterNickName">{{ row.reporterNickName }}</span>
            <span v-else class="muted">—</span>
            <span class="muted reporter-id"> {{ row.reporterId }}</span>
          </template>
        </el-table-column>
        <el-table-column label="类型" width="112" align="center">
          <template #default="{ row }">{{ labelReportType(row.reportType) }}</template>
        </el-table-column>
        <el-table-column label="状态" width="120" align="center">
          <template #default="{ row }">
            <el-tag :type="statusTagType(row.status)" size="small" class="status-tag">
              <span class="status-tag-inner">
                <el-icon v-if="row.status === 0" :size="14"><Clock /></el-icon>
                <el-icon v-else-if="row.status === 1" :size="14"><Refresh /></el-icon>
                <el-icon v-else-if="row.status === 2" :size="14"><CircleCheck /></el-icon>
                <el-icon v-else-if="row.status === 3" :size="14"><CircleClose /></el-icon>
                {{ labelStatus(row.status) }}
              </span>
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="目标帖子" min-width="168" show-overflow-tooltip>
          <template #default="{ row }">{{ row.targetTitle || '—' }}</template>
        </el-table-column>
        <el-table-column label="举报说明" min-width="160" show-overflow-tooltip>
          <template #default="{ row }">{{ row.reportReason || '—' }}</template>
        </el-table-column>
        <el-table-column prop="createTime" label="举报时间" width="170" />
        <el-table-column prop="handleRemark" label="备注" min-width="120" show-overflow-tooltip />
        <el-table-column label="操作" width="200" fixed="right" align="right" header-align="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button type="primary" plain size="small" class="action-detail" @click="openDetail(row)">
                查看详情
              </el-button>
              <el-button v-if="canHandle(row) && hasAuthority('REPORT_HANDLE')" type="primary" plain size="small" @click="openHandle(row)">
                处理
              </el-button>
            </div>
          </template>
        </el-table-column>
      </el-table>

      <div v-show="!showListSkeleton" class="pager">
        <el-pagination
          :current-page="query.pageNum"
          :page-size="query.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </div>
    </el-card>

    <el-drawer
      v-model="drawerVisible"
      title="帖子举报详情"
      size="520px"
      destroy-on-close
      class="portal-drawer"
      @closed="onDetailClosed"
    >
      <el-skeleton v-if="detailLoading" animated :rows="8" />
      <div v-else-if="detailReport" class="portal-detail-stack">
        <div class="portal-detail-summary">
          <div class="portal-detail-summary-icon" aria-hidden="true">
            <el-icon><Document /></el-icon>
          </div>
          <div class="portal-detail-summary-main">
            <h3 class="portal-detail-summary-title portal-detail-summary-title--clamp">
              {{ detailReport.targetTitle || contentDetail?.title || '—' }}
            </h3>
            <div class="portal-detail-summary-meta">
              <span class="portal-badge portal-badge--muted">举报 {{ detailReport.id }}</span>
              <span class="portal-badge portal-badge--muted">帖子 {{ detailReport.contentId }}</span>
              <span class="portal-badge portal-badge--muted">{{ labelReportType(detailReport.reportType) }}</span>
              <span
                class="portal-badge"
                :class="'portal-badge--' + portalReportStatusBadgeMod(detailReport.status)"
              >
                {{ labelStatus(detailReport.status) }}
              </span>
            </div>
            <p class="portal-detail-summary-sub">
              举报人 {{ detailReport.reporterNickName || '—' }}（{{ detailReport.reporterId }}）·
              {{ detailReport.createTime ?? '—' }}
            </p>
          </div>
        </div>
        <el-descriptions class="portal-details-in-drawer" title="举报与处置" :column="1" border>
          <el-descriptions-item label="举报说明">
            <pre class="portal-readonly-block">{{ detailReport.reportReason || '（未填写）' }}</pre>
          </el-descriptions-item>
          <el-descriptions-item label="备注">{{ detailReport.handleRemark || '—' }}</el-descriptions-item>
        </el-descriptions>
        <template v-if="contentDetail">
          <el-descriptions class="portal-details-in-drawer" title="被举报帖子（当前快照）" :column="1" border>
            <el-descriptions-item label="标题">{{ contentDetail.title ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="正文">
              <pre class="portal-readonly-block">{{ contentDetail.content ?? '—' }}</pre>
            </el-descriptions-item>
            <el-descriptions-item label="类型">{{ labelPostType(contentDetail.contentType) }}</el-descriptions-item>
            <el-descriptions-item label="发布者 ID">{{ contentDetail.publishUserId ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="审核">
              <span
                class="portal-badge"
                :class="'portal-badge--' + portalPostAuditBadgeMod(contentDetail.auditStatus)"
              >
                {{ labelPostAudit(contentDetail.auditStatus) }}
              </span>
            </el-descriptions-item>
            <el-descriptions-item label="发布时间">{{ contentDetail.createTime ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="驳回原因">{{ contentDetail.rejectReason || '—' }}</el-descriptions-item>
          </el-descriptions>
        </template>
        <el-alert
          v-else
          type="warning"
          title="未能加载帖子原文"
          description="可能被删除或无权限查看，仍可依据举报说明与其它字段处置。"
          :closable="false"
          show-icon
        />
      </div>
    </el-drawer>

    <el-dialog
      v-model="handleVisible"
      title="处理帖子举报"
      width="560px"
      class="portal-dialog-accent"
      destroy-on-close
      @closed="onHandleDialogClosed"
    >
      <template v-if="currentReport">
        <el-descriptions class="portal-details-in-drawer portal-dialog-detail-desc" title="举报工单" :column="1" border>
          <el-descriptions-item label="帖子 ID">{{ currentReport.contentId }}</el-descriptions-item>
          <el-descriptions-item label="投诉类型">{{ labelReportType(currentReport.reportType) }}</el-descriptions-item>
          <el-descriptions-item label="举报人">
            {{ currentReport.reporterNickName || '—' }}（{{ currentReport.reporterId }}）
          </el-descriptions-item>
          <el-descriptions-item label="举报时间">{{ currentReport.createTime }}</el-descriptions-item>
          <el-descriptions-item label="工单状态">{{ labelStatus(currentReport.status) }}</el-descriptions-item>
          <el-descriptions-item label="举报说明">
            <pre class="portal-readonly-block">{{ currentReport.reportReason || '（未填写）' }}</pre>
          </el-descriptions-item>
        </el-descriptions>
        <div v-loading="handleTargetLoading" class="report-handle-target">
          <el-descriptions
            v-if="handleTargetDetail"
            class="portal-details-in-drawer portal-dialog-detail-desc"
            title="被举报帖子（当前快照）"
            :column="1"
            border
          >
            <el-descriptions-item label="标题">{{ handleTargetDetail.title ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="正文">
              <pre class="portal-readonly-block">{{ handleTargetDetail.content ?? '—' }}</pre>
            </el-descriptions-item>
            <el-descriptions-item label="类型">{{ labelPostType(handleTargetDetail.contentType) }}</el-descriptions-item>
            <el-descriptions-item label="发布者 ID">{{ handleTargetDetail.publishUserId ?? '—' }}</el-descriptions-item>
            <el-descriptions-item label="审核">{{ labelPostAudit(handleTargetDetail.auditStatus) }}</el-descriptions-item>
          </el-descriptions>
          <el-alert
            v-else-if="!handleTargetLoading"
            type="warning"
            title="未加载到帖子正文"
            :closable="false"
            show-icon
            class="report-handle-alert"
          />
        </div>
        <el-form label-width="100px" class="report-handle-form">
          <el-form-item label="处理结果" required>
            <el-radio-group v-model="handleForm.handleResult">
              <el-radio v-for="opt in handleResultOptions" :key="opt.value" :label="opt.value">
                {{ opt.label }}
              </el-radio>
            </el-radio-group>
          </el-form-item>
          <el-form-item label="处理备注">
            <el-input
              v-model="handleForm.handleRemark"
              type="textarea"
              :rows="3"
              maxlength="500"
              show-word-limit
              placeholder="可选"
            />
          </el-form-item>
        </el-form>
      </template>
      <template #footer>
        <el-button @click="handleVisible = false">取消</el-button>
        <el-button type="primary" :loading="handleLoading" @click="submitHandle">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<style scoped>
.report-section {
  display: flex;
  flex-direction: column;
  gap: 12px;
}

.status-tag-inner {
  display: inline-flex;
  align-items: center;
  gap: 4px;
}

.muted {
  color: var(--el-text-color-placeholder);
}

.reporter-id {
  font-size: 12px;
  font-variant-numeric: tabular-nums;
}

.report-handle-target {
  margin-top: 16px;
}

.report-handle-alert {
  margin-top: 4px;
}

.report-handle-form {
  margin-top: 16px;
}

:deep(.portal-dialog-detail-desc.el-descriptions) {
  --el-descriptions-table-border: 1px solid var(--admin-border);
  --el-descriptions-item-bordered-label-background: var(--admin-surface-soft);
}
</style>
