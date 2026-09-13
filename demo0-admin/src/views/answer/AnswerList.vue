<script setup>
import { computed, reactive, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { ChatLineRound, Clock, CircleCheck, CircleClose } from '@element-plus/icons-vue'
import PageHeader from '../../components/PageHeader.vue'
import TableEmptyState from '../../components/TableEmptyState.vue'
import { usePermission } from '../../composables/usePermission'
import { answerApi } from '../../api/answer'
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

const query = reactive({
  contentId: '',
  auditStatus: undefined,
  pageNum: 1,
  pageSize: 10,
})

const rejectVisible = ref(false)
const rejectLoading = ref(false)
const rejectTarget = ref(null)
const rejectReason = ref('')

const detailVisible = ref(false)
const detailLoading = ref(false)
const answerDetail = ref(null)
const detailModeration = ref(null)

const postTypeLabel = { 1: '生活求助', 2: '专业问答' }
const postAuditLabel = { 0: '待审核', 1: '已通过', 2: '已驳回' }

function buildPageParams() {
  const p = { pageNum: query.pageNum, pageSize: query.pageSize }
  const idStr = String(query.contentId ?? '').trim()
  if (idStr) {
    const n = Number(idStr)
    if (!Number.isNaN(n) && n > 0) p.contentId = n
  }
  if (query.auditStatus !== undefined && query.auditStatus !== null && query.auditStatus !== '') {
    p.auditStatus = query.auditStatus
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
      rows.map((row) => ({ targetType: 'ANSWER', targetId: row.answerId })),
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
    const data = await answerApi.page(buildPageParams())
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
  query.contentId = ''
  query.auditStatus = undefined
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

function labelAudit(v) {
  if (v === undefined || v === null) return '-'
  return auditLabel[v] ?? String(v)
}

function labelPostType(v) {
  if (v === undefined || v === null) return '-'
  return postTypeLabel[v] ?? String(v)
}

function labelPostAudit(v) {
  if (v === undefined || v === null) return '-'
  return postAuditLabel[v] ?? String(v)
}

function portalAnswerAuditBadgeMod(v) {
  if (v === 0) return 'warning'
  if (v === 1) return 'success'
  if (v === 2) return 'danger'
  return 'muted'
}

function portalPostAuditBadgeMod(v) {
  if (v === 0) return 'warning'
  if (v === 1) return 'success'
  if (v === 2) return 'danger'
  return 'muted'
}

function aiRecord(row) {
  return pickModeration(moderationMap.value, 'ANSWER', row?.answerId)
}

async function openDetail(row) {
  detailVisible.value = true
  answerDetail.value = row ? { ...row } : null
  detailLoading.value = false
  detailModeration.value = aiRecord(row)
  if (!detailModeration.value && row?.answerId) {
    try {
      detailModeration.value = await moderationApi.latest({
        targetType: 'ANSWER',
        targetId: row.answerId,
      })
    } catch {
      detailModeration.value = null
    }
  }
}

function onDetailClosed() {
  answerDetail.value = null
  detailModeration.value = null
}

function contentPreview(text) {
  if (!text) return '—'
  const t = String(text).replace(/\s+/g, ' ').trim()
  return t.length > 80 ? `${t.slice(0, 80)}…` : t
}

async function doPass(row) {
  await answerApi.audit({
    contentId: row.answerId,
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
    await answerApi.audit({
      contentId: rejectTarget.value.answerId,
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

async function doDelete(answerId) {
  await answerApi.remove(answerId)
  ElMessage.success('已删除')
  await fetchList()
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
  <div class="answer-list portal-page">
    <PageHeader
      :icon="ChatLineRound"
      title="回答管理"
      description="审核专业回答内容"
      :badge="headerBadge"
      :badge-tone="headerBadgeTone"
    />
    <el-card shadow="never" class="portal-panel toolbar-card">
      <el-form :inline="true" :model="query" class="filter-form" label-width="96px" @submit.prevent="onSearch">
        <el-form-item label="帖子 ID">
          <el-input v-model="query.contentId" placeholder="可选，数字" clearable style="width: 160px" />
        </el-form-item>
        <el-form-item label="审核状态">
          <el-select v-model="query.auditStatus" placeholder="全部" clearable style="width: 140px">
            <el-option label="待审核" :value="0" />
            <el-option label="已通过" :value="1" />
            <el-option label="已驳回" :value="2" />
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

      <div v-if="showListSkeleton" class="list-skeleton" aria-busy="true" aria-label="加载回答列表">
        <el-skeleton :rows="8" animated />
      </div>

      <el-table
        v-show="!showListSkeleton"
        v-loading="tableLoading"
        class="portal-table"
        :data="list"
        row-key="answerId"
        size="small"
        style="width: 100%"
      >
        <template #empty>
          <TableEmptyState
            v-if="!loadError"
            :icon="ChatLineRound"
            title="暂无符合条件的回答"
            description="尝试调整筛选条件后重试。"
          />
          <TableEmptyState
            v-else
            error
            title="列表未展示"
            description="请查看上方提示或使用「重试」。"
          />
        </template>
        <el-table-column prop="answerId" label="回答 ID" width="96" />
        <el-table-column prop="questionId" label="帖子 ID" width="96" />
        <el-table-column prop="userId" label="发布者" width="100" />
        <el-table-column label="正文摘要" min-width="200" show-overflow-tooltip>
          <template #default="{ row }">{{ contentPreview(row.content) }}</template>
        </el-table-column>
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
        <el-table-column label="操作" width="280" fixed="right" align="right" header-align="right">
          <template #default="{ row }">
            <div class="row-actions">
              <el-button type="primary" plain size="small" class="action-detail" @click="openDetail(row)">
                查看详情
              </el-button>
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
                title="确认删除该回答？（软删除）"
                @confirm="doDelete(row.answerId)"
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

    <el-drawer
      v-model="detailVisible"
      title="回答详情"
      size="520px"
      destroy-on-close
      class="portal-drawer"
      @closed="onDetailClosed"
    >
      <el-skeleton v-if="detailLoading" animated :rows="8" />
      <div v-else-if="answerDetail" class="portal-detail-stack">
        <div class="portal-detail-summary">
          <div class="portal-detail-summary-icon" aria-hidden="true">
            <el-icon><ChatLineRound /></el-icon>
          </div>
          <div class="portal-detail-summary-main">
            <h3 class="portal-detail-summary-title portal-detail-summary-title--clamp">
              {{ answerDetail.questionTitle || '—' }}
            </h3>
            <div class="portal-detail-summary-meta">
              <span class="portal-badge portal-badge--muted">回答 {{ answerDetail.answerId }}</span>
              <span class="portal-badge portal-badge--muted">帖子 {{ answerDetail.questionId }}</span>
              <span
                class="portal-badge"
                :class="'portal-badge--' + portalAnswerAuditBadgeMod(answerDetail.auditStatus)"
              >
                {{ labelAudit(answerDetail.auditStatus) }}
              </span>
            </div>
            <p class="portal-detail-summary-sub">
              作者 {{ answerDetail.userId ?? '—' }} · {{ answerDetail.createTime ?? '—' }}
            </p>
          </div>
        </div>
        <el-descriptions class="portal-details-in-drawer" title="回答内容" :column="1" border>
          <el-descriptions-item label="正文">
            <pre class="portal-readonly-block">{{ answerDetail.content ?? '—' }}</pre>
          </el-descriptions-item>
          <el-descriptions-item label="驳回原因">{{ answerDetail.rejectReason || '—' }}</el-descriptions-item>
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
        <el-descriptions class="portal-details-in-drawer" title="关联帖子（问题）" :column="1" border>
          <el-descriptions-item label="帖子 ID">{{ answerDetail.questionId ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="标题">{{ answerDetail.questionTitle ?? '—' }}</el-descriptions-item>
          <el-descriptions-item label="问题正文">
            <pre class="portal-readonly-block">{{ answerDetail.questionContent ?? '—' }}</pre>
          </el-descriptions-item>
          <el-descriptions-item label="内容类型">{{ labelPostType(answerDetail.questionContentType) }}</el-descriptions-item>
          <el-descriptions-item label="帖子审核">
            <span
              class="portal-badge"
              :class="'portal-badge--' + portalPostAuditBadgeMod(answerDetail.questionAuditStatus)"
            >
              {{ labelPostAudit(answerDetail.questionAuditStatus) }}
            </span>
          </el-descriptions-item>
        </el-descriptions>
      </div>
      <template v-if="detailVisible && !detailLoading && !answerDetail">
        <el-alert
          type="warning"
          title="未能加载回答详情"
          description="接口异常或记录已删除。"
          show-icon
          :closable="false"
        />
      </template>
    </el-drawer>

    <el-dialog
      v-model="rejectVisible"
      title="驳回回答"
      width="480px"
      class="portal-dialog-accent"
      destroy-on-close
      @closed="rejectTarget = null"
    >
      <p v-if="rejectTarget" class="dialog-hint">
        回答 ID：{{ rejectTarget.answerId }} — 帖子 {{ rejectTarget.questionId }}
      </p>
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
