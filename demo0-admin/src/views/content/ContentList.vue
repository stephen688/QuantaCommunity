<script setup>
import { computed, reactive, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { Document, Clock, CircleCheck, CircleClose } from '@element-plus/icons-vue'
import PageHeader from '../../components/PageHeader.vue'
import TableEmptyState from '../../components/TableEmptyState.vue'
import { usePermission } from '../../composables/usePermission'
import { contentApi } from '../../api/content'
import { moderationApi } from '../../api/moderation'
import {
  buildModerationQueries,
  formatModerationLabels,
  labelModerationDecision,
  moderationDecisionTagType,
  pickModeration,
} from '../../utils/moderation'

const { hasAuthority } = usePermission()

const tableLoading = ref(false)
const list = ref([])
const total = ref(0)
const initialComplete = ref(false)
const loadError = ref(null)
const moderationMap = ref({})
const detailModeration = ref(null)

const query = reactive({
  auditStatus: undefined,
  contentType: undefined,
  pageNum: 1,
  pageSize: 10,
})

const rejectVisible = ref(false)
const rejectLoading = ref(false)
const rejectTarget = ref(null)
const rejectReason = ref('')
const detailVisible = ref(false)
const detailRow = ref(null)

function buildPageParams() {
  const p = { pageNum: query.pageNum, pageSize: query.pageSize }
  if (query.auditStatus !== undefined && query.auditStatus !== null && query.auditStatus !== '') {
    p.auditStatus = query.auditStatus
  }
  if (query.contentType !== undefined && query.contentType !== null && query.contentType !== '') {
    p.contentType = query.contentType
  }
  return p
}

async function loadModerationForRows(rows) {
  if (!Array.isArray(rows) || rows.length === 0) {
    moderationMap.value = {}
    return
  }
  try {
    const queries = buildModerationQueries(
      rows.map((row) => ({ targetType: 'CONTENT', targetId: row.contentId })),
    )
    moderationMap.value = queries.length ? ((await moderationApi.batchLatest(queries)) ?? {}) : {}
  } catch {
    moderationMap.value = {}
  }
}

async function fetchList() {
  loadError.value = null
  tableLoading.value = true
  try {
    const data = await contentApi.page(buildPageParams())
    total.value = Number(data?.total ?? 0)
    list.value = Array.isArray(data?.records) ? data.records : []
    await loadModerationForRows(list.value)
  } catch (err) {
    list.value = []
    total.value = 0
    moderationMap.value = {}
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
  query.auditStatus = undefined
  query.contentType = undefined
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

const auditLabel = { 0: '待审核', 1: '已通过', 2: '已驳回' }
const typeLabel = { 1: '生活求助', 2: '专业问答' }

function labelAudit(v) {
  if (v === undefined || v === null) return '-'
  return auditLabel[v] ?? String(v)
}

function labelType(v) {
  if (v === undefined || v === null) return '-'
  return typeLabel[v] ?? String(v)
}

function portalContentAuditBadgeMod(v) {
  if (v === 0) return 'warning'
  if (v === 1) return 'success'
  if (v === 2) return 'danger'
  return 'muted'
}

async function doPass(row) {
  await contentApi.audit({
    contentId: row.contentId,
    auditResult: 1,
  })
  ElMessage.success('已通过')
  await fetchList()
}

function openReject(row) {
  rejectTarget.value = row
  rejectReason.value = ''
  rejectVisible.value = true
}

async function submitReject() {
  const reason = rejectReason.value?.trim()
  if (!reason) {
    ElMessage.warning('请填写驳回原因')
    return
  }
  rejectLoading.value = true
  try {
    await contentApi.audit({
      contentId: rejectTarget.value.contentId,
      auditResult: 2,
      rejectReason: reason,
    })
    ElMessage.success('已驳回')
    rejectVisible.value = false
    await fetchList()
  } finally {
    rejectLoading.value = false
  }
}

async function doDelete(contentId) {
  await contentApi.remove(contentId)
  ElMessage.success('已删除')
  await fetchList()
}

function aiRecord(row) {
  return pickModeration(moderationMap.value, 'CONTENT', row?.contentId)
}

async function openDetail(row) {
  detailRow.value = row
  detailModeration.value = aiRecord(row)
  detailVisible.value = true
  if (!detailModeration.value && row?.contentId) {
    try {
      detailModeration.value = await moderationApi.latest({
        targetType: 'CONTENT',
        targetId: row.contentId,
      })
    } catch {
      detailModeration.value = null
    }
  }
}

const showListSkeleton = computed(() => tableLoading.value && !initialComplete.value)

const headerBadge = computed(() => {
  if (!initialComplete.value) return ''
  if (query.auditStatus === 0) return `待审核共 ${total.value} 条`
  return `共 ${total.value} 条`
})

const headerBadgeTone = computed(() => (query.auditStatus === 0 ? 'warning' : 'muted'))

onMounted(() => {
  fetchList()
})
</script>

<template>
  <div class="content-list portal-page">
    <PageHeader
      :icon="Document"
      title="帖子管理"
      description="审核帖子内容、删除违规帖子"
      :badge="headerBadge"
      :badge-tone="headerBadgeTone"
    />
    <el-card shadow="never" class="portal-panel toolbar-card">
      <el-form :inline="true" :model="query" class="filter-form" @submit.prevent="onSearch">
        <el-form-item label="审核状态">
          <el-select v-model="query.auditStatus" placeholder="全部" clearable style="width: 140px">
            <el-option label="待审核" :value="0" />
            <el-option label="已通过" :value="1" />
            <el-option label="已驳回" :value="2" />
          </el-select>
        </el-form-item>
        <el-form-item label="内容类型">
          <el-select v-model="query.contentType" placeholder="全部" clearable style="width: 140px">
            <el-option label="生活求助" :value="1" />
            <el-option label="专业问答" :value="2" />
          </el-select>
        </el-form-item>
        <el-form-item>
          <el-button type="primary" native-type="submit">搜索</el-button>
          <el-button @click="onReset">重置</el-button>
        </el-form-item>
      </el-form>
    </el-card>

    <el-card shadow="never" class="portal-panel table-card">
      <div v-if="loadError" class="list-error-bar" role="status">
        <span class="list-error-text">{{ loadError.message }}</span>
        <div class="list-error-actions">
          <el-button type="primary" link class="list-error-retry" @click="retryFetch">重试</el-button>
          <el-button text class="list-error-dismiss" @click="dismissError">关闭</el-button>
        </div>
      </div>

      <div v-if="showListSkeleton" class="list-skeleton" aria-busy="true" aria-label="加载帖子列表">
        <el-skeleton :rows="8" animated />
      </div>

      <el-table
        v-show="!showListSkeleton"
        v-loading="tableLoading"
        class="portal-table"
        :data="list"
        row-key="contentId"
        size="small"
        style="width: 100%"
      >
        <template #empty>
          <TableEmptyState
            v-if="!loadError"
            :icon="Document"
            title="暂无符合条件的帖子"
            description="尝试调整筛选条件后重试。"
          />
          <TableEmptyState
            v-else
            error
            title="列表未展示"
            description="请查看上方提示或使用「重试」。"
          />
        </template>
        <el-table-column prop="contentId" label="ID" width="88" />
        <el-table-column prop="title" label="标题" min-width="160" show-overflow-tooltip />
        <el-table-column label="类型" width="100" align="center">
          <template #default="{ row }">{{ labelType(row.contentType) }}</template>
        </el-table-column>
        <el-table-column prop="publishUserId" label="发布者" width="100" />
        <el-table-column label="审核" width="112" align="center">
          <template #default="{ row }">
            <el-tag
              :type="row.auditStatus === 0 ? 'warning' : row.auditStatus === 2 ? 'danger' : 'success'"
              size="small"
              class="audit-tag"
            >
              <span class="audit-tag-inner">
                <el-icon v-if="row.auditStatus === 0" :size="14"><Clock /></el-icon>
                <el-icon v-else-if="row.auditStatus === 1" :size="14"><CircleCheck /></el-icon>
                <el-icon v-else :size="14"><CircleClose /></el-icon>
                {{ labelAudit(row.auditStatus) }}
              </span>
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="AI 建议" width="140" align="center">
          <template #default="{ row }">
            <template v-if="aiRecord(row)">
              <el-tag :type="moderationDecisionTagType(aiRecord(row).decision)" size="small">
                {{ labelModerationDecision(aiRecord(row).decision) }}
              </el-tag>
            </template>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column prop="createTime" label="发布时间" width="170" />
        <el-table-column label="操作" width="320" fixed="right" align="center">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button type="primary" plain size="small" @click.stop="openDetail(row)">查看</el-button>
              <template v-if="row.auditStatus === 0 && hasAuthority('CONTENT_AUDIT')">
                <el-popconfirm title="确认审核通过？" @confirm="doPass(row)">
                  <template #reference>
                    <el-button type="success" plain size="small" @click.stop>通过</el-button>
                  </template>
                </el-popconfirm>
                <el-button type="warning" plain size="small" @click.stop="openReject(row)">驳回</el-button>
              </template>
              <el-popconfirm
                v-if="hasAuthority('CONTENT_DELETE')"
                title="确认删除该帖子？（软删除）"
                @confirm="doDelete(row.contentId)"
              >
                <template #reference>
                  <el-button type="danger" plain size="small" @click.stop>删除</el-button>
                </template>
              </el-popconfirm>
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

    <el-drawer v-model="detailVisible" title="帖子详情" size="520px" destroy-on-close class="portal-drawer">
      <div v-if="detailRow" class="portal-detail-stack">
        <div class="portal-detail-summary">
          <div class="portal-detail-summary-icon" aria-hidden="true">
            <el-icon><Document /></el-icon>
          </div>
          <div class="portal-detail-summary-main">
            <h3 class="portal-detail-summary-title portal-detail-summary-title--clamp">
              {{ detailRow.title || '（无标题）' }}
            </h3>
            <div class="portal-detail-summary-meta">
              <span class="portal-badge portal-badge--muted"> ID {{ detailRow.contentId ?? '—' }} </span>
              <span class="portal-badge portal-badge--muted">{{ labelType(detailRow.contentType) }}</span>
              <span
                class="portal-badge"
                :class="'portal-badge--' + portalContentAuditBadgeMod(detailRow.auditStatus)"
              >
                {{ labelAudit(detailRow.auditStatus) }}
              </span>
            </div>
            <p class="portal-detail-summary-sub">
              发布 {{ detailRow.createTime ?? '—' }} · 发布者 {{ detailRow.publishUserId ?? '—' }}
            </p>
          </div>
        </div>
        <el-descriptions class="portal-details-in-drawer" title="内容与发布" :column="1" border>
          <el-descriptions-item label="标题">{{ detailRow.title ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="正文">
            <pre class="portal-readonly-block">{{ detailRow.content ?? '-' }}</pre>
          </el-descriptions-item>
          <el-descriptions-item label="内容类型">{{ labelType(detailRow.contentType) }}</el-descriptions-item>
          <el-descriptions-item label="发布者ID">{{ detailRow.publishUserId ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="发布时间">{{ detailRow.createTime ?? '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-descriptions class="portal-details-in-drawer" title="审核信息" :column="1" border>
          <el-descriptions-item label="审核状态">{{ labelAudit(detailRow.auditStatus) }}</el-descriptions-item>
          <el-descriptions-item label="驳回原因">{{ detailRow.rejectReason || '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-descriptions
          v-if="detailModeration"
          class="portal-details-in-drawer"
          title="AI 审核"
          :column="1"
          border
        >
          <el-descriptions-item label="建议">
            {{ labelModerationDecision(detailModeration.decision) }}
          </el-descriptions-item>
          <el-descriptions-item label="风险等级">{{ detailModeration.riskLevel || '—' }}</el-descriptions-item>
          <el-descriptions-item label="风险标签">
            {{ formatModerationLabels(detailModeration.labels) }}
          </el-descriptions-item>
          <el-descriptions-item label="AI 驳回原因">
            {{ detailModeration.rejectReason || '—' }}
          </el-descriptions-item>
          <el-descriptions-item label="来源">{{ detailModeration.provider || '—' }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ detailModeration.updateTime || '—' }}</el-descriptions-item>
        </el-descriptions>
      </div>
    </el-drawer>

    <el-dialog
      v-model="rejectVisible"
      title="驳回帖子"
      width="480px"
      class="portal-dialog-accent"
      destroy-on-close
      @closed="rejectTarget = null"
    >
      <p v-if="rejectTarget" class="dialog-hint">帖子 ID：{{ rejectTarget.contentId }} — {{ rejectTarget.title }}</p>
      <p v-if="rejectTarget && aiRecord(rejectTarget)" class="dialog-hint dialog-hint--ai">
        AI 建议：{{ labelModerationDecision(aiRecord(rejectTarget).decision) }}
        <template v-if="formatModerationLabels(aiRecord(rejectTarget).labels) !== '—'">
          · 标签：{{ formatModerationLabels(aiRecord(rejectTarget).labels) }}
        </template>
      </p>
      <el-input
        v-model="rejectReason"
        type="textarea"
        :rows="4"
        maxlength="500"
        show-word-limit
        placeholder="请填写驳回原因"
      />
      <template #footer>
        <el-button @click="rejectVisible = false">取消</el-button>
        <el-button type="primary" :loading="rejectLoading" @click="submitReject">提交</el-button>
      </template>
    </el-dialog>
  </div>
</template>
