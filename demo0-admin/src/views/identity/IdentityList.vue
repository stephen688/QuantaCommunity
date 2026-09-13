<script setup>
import { computed, reactive, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { User, Stamp, Clock, CircleCheck, CircleClose } from '@element-plus/icons-vue'
import PageHeader from '../../components/PageHeader.vue'
import TableEmptyState from '../../components/TableEmptyState.vue'
import { usePermission } from '../../composables/usePermission'
import { identityExamApi } from '../../api/identityExam'

const { hasAuthority } = usePermission()

const tableLoading = ref(false)
const list = ref([])
const total = ref(0)
const initialComplete = ref(false)
const loadError = ref(null)

const query = reactive({
  auditStatus: undefined,
  applyRange: [],
  pageNum: 1,
  pageSize: 10,
})

const drawerVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)

const rejectVisible = ref(false)
const rejectLoading = ref(false)
const rejectReason = ref('')

const auditLabel = {
  0: '待审核',
  1: '已通过',
  2: '已驳回',
}

function labelAudit(v) {
  if (v === undefined || v === null) return '-'
  return auditLabel[v] ?? String(v)
}

function portalIdentityAuditBadgeMod(v) {
  if (v === 0) return 'warning'
  if (v === 1) return 'success'
  if (v === 2) return 'danger'
  return 'muted'
}

function buildPageParams() {
  const p = {
    pageNum: query.pageNum,
    pageSize: query.pageSize,
  }
  if (query.auditStatus !== undefined && query.auditStatus !== null && query.auditStatus !== '') {
    p.auditStatus = query.auditStatus
  }
  if (Array.isArray(query.applyRange) && query.applyRange.length === 2) {
    p.startTime = query.applyRange[0]
    p.endTime = query.applyRange[1]
  }
  return p
}

function friendlyListError(err) {
  const status = err?.response?.status
  const dataMsg = err?.response?.data?.msg
  if (status === 502 || status === 503) return '服务暂时不可用（网关或上游异常），请稍后重试。'
  if (status === 504) return '网关超时，请稍后重试。'
  if (!err?.response) return '无法连接服务器，请确认网络或服务是否已启动。'
  return dataMsg || err?.message || '列表加载失败，请重试。'
}

async function fetchList() {
  loadError.value = null
  tableLoading.value = true
  try {
    const data = await identityExamApi.page(buildPageParams())
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
  query.applyRange = []
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

function mergeIdentityDetail(row, apiDetail) {
  return {
    ...row,
    ...apiDetail,
    authId: row.authId ?? apiDetail?.authId,
    userId: row.userId ?? apiDetail?.userId,
    auditStatus: apiDetail?.auditStatus ?? row.auditStatus,
  }
}

async function openDetail(row) {
  drawerVisible.value = true
  detail.value = mergeIdentityDetail(row, null)
  detailLoading.value = true
  try {
    const apiDetail = await identityExamApi.getById(row.authId)
    detail.value = mergeIdentityDetail(row, apiDetail)
  } catch {
    drawerVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

async function submitAudit(auditResult, reason = '') {
  const authId = detail.value?.authId
  if (!authId) return
  const payload = {
    authId,
    auditResult,
    auditRemark: reason,
  }
  await identityExamApi.audit(payload)
  ElMessage.success(auditResult === 1 ? '审核已通过' : '已驳回')
  rejectVisible.value = false
  await fetchList()
  try {
    const apiDetail = await identityExamApi.getById(authId)
    detail.value = mergeIdentityDetail(detail.value, apiDetail)
  } catch {
    drawerVisible.value = false
  }
}

async function doPass() {
  await submitAudit(1)
}

function openReject() {
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
    await submitAudit(2, reason)
  } finally {
    rejectLoading.value = false
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
  <div class="identity-list portal-page">
    <PageHeader
      :icon="Stamp"
      title="身份认证审核"
      description="审核用户的 Quanta 成员认证申请"
      :badge="headerBadge"
      :badge-tone="headerBadgeTone"
    />
    <el-card shadow="never" class="portal-panel toolbar-card">
      <el-form :inline="true" :model="query" class="filter-form" label-width="88px" @submit.prevent="onSearch">
        <el-form-item label="审核状态">
          <el-select v-model="query.auditStatus" placeholder="全部" clearable class="filter-input-sm">
            <el-option label="待审核" :value="0" />
            <el-option label="已通过" :value="1" />
            <el-option label="已驳回" :value="2" />
          </el-select>
        </el-form-item>
        <el-form-item label="申请时间">
          <el-date-picker
            v-model="query.applyRange"
            type="daterange"
            start-placeholder="开始日期"
            end-placeholder="结束日期"
            value-format="YYYY-MM-DD"
            range-separator="至"
            class="filter-input-range"
          />
        </el-form-item>
        <el-form-item class="filter-actions">
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

      <div v-if="showListSkeleton" class="list-skeleton" aria-busy="true" aria-label="加载身份认证列表">
        <el-skeleton :rows="8" animated />
      </div>

      <div v-show="!showListSkeleton" class="table-wrap">
        <el-table
          v-loading="tableLoading"
          class="portal-table identity-table"
          :data="list"
          row-key="authId"
          size="small"
          element-loading-text="加载中…"
          style="width: 100%"
        >
          <template #empty>
            <TableEmptyState
              v-if="!loadError"
              :icon="Stamp"
              title="暂无认证申请"
              description="可调整筛选条件后重试。"
            />
            <TableEmptyState
              v-else
              error
              title="列表未展示"
              description="请查看上方提示或使用「重试」。"
            />
          </template>

          <el-table-column prop="authId" label="认证ID" width="88" align="center" />
          <el-table-column prop="userId" label="用户ID" width="88" align="center" />
          <el-table-column prop="realName" label="真实姓名" width="108" show-overflow-tooltip />
          <el-table-column prop="schoolId" label="学号" width="120" show-overflow-tooltip />
          <el-table-column label="审核状态" width="112" align="center">
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
                  {{ row.statusText || labelAudit(row.auditStatus) }}
                </span>
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="申请时间" width="168" />
          <el-table-column label="操作" width="112" fixed="right" align="right" header-align="right">
            <template #default="{ row }">
              <el-button type="primary" plain size="small" class="action-detail" @click="openDetail(row)">查看</el-button>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <div v-show="!showListSkeleton" class="pager">
        <el-pagination
          :current-page="query.pageNum"
          :page-size="query.pageSize"
          :total="total"
          :page-sizes="[10, 20, 50]"
          layout="total, sizes, prev, pager, next, jumper"
          background
          size="small"
          @current-change="onPageChange"
          @size-change="onSizeChange"
        />
      </div>
    </el-card>

    <el-drawer
      v-model="drawerVisible"
      title="认证申请详情"
      size="520px"
      destroy-on-close
      class="portal-drawer"
    >
      <el-skeleton v-if="detailLoading" :rows="8" animated />
      <div v-else-if="detail" class="portal-detail-stack">
        <div class="portal-detail-summary">
          <div class="portal-detail-summary-icon" aria-hidden="true">
            <el-icon><Stamp /></el-icon>
          </div>
          <div class="portal-detail-summary-main">
            <h3 class="portal-detail-summary-title">{{ detail.realName || detail.nickName || '—' }}</h3>
            <div class="portal-detail-summary-meta">
              <span class="portal-badge portal-badge--muted"> 认证 {{ detail.authId ?? '—' }} </span>
              <span class="portal-badge portal-badge--muted"> 用户 {{ detail.userId ?? '—' }} </span>
              <span
                class="portal-badge"
                :class="'portal-badge--' + portalIdentityAuditBadgeMod(detail.auditStatus)"
              >
                {{ labelAudit(detail.auditStatus) }}
              </span>
            </div>
            <p class="portal-detail-summary-sub">
              申请 {{ detail.createTime ?? '—' }} · 更新 {{ detail.updateTime ?? '—' }}
            </p>
          </div>
        </div>
        <el-descriptions class="portal-details-in-drawer" title="申请人信息" :column="1" border>
          <el-descriptions-item label="用户ID">{{ detail.userId ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="昵称">{{ detail.nickName ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="真实姓名">{{ detail.realName ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="学号">{{ detail.schoolId ?? '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-descriptions class="portal-details-in-drawer" title="组织与批次" :column="1" border>
          <el-descriptions-item label="部门">{{ detail.quantaDepartment ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="批次">{{ detail.quantaBatch ?? '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-descriptions class="portal-details-in-drawer" title="审核与备注" :column="1" border>
          <el-descriptions-item label="认证ID">{{ detail.authId ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="审核状态">{{ labelAudit(detail.auditStatus) }}</el-descriptions-item>
          <el-descriptions-item label="驳回原因">{{ detail.auditRemark || '-' }}</el-descriptions-item>
          <el-descriptions-item label="申请时间">{{ detail.createTime ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ detail.updateTime ?? '-' }}</el-descriptions-item>
        </el-descriptions>
      </div>
      <template v-if="detail && !detailLoading && detail.auditStatus === 0" #footer>
        <div v-if="hasAuthority('IDENTITY_AUDIT')" class="drawer-footer">
          <el-popconfirm title="确定审核通过该申请？" @confirm="doPass">
            <template #reference>
              <el-button type="success">通过</el-button>
            </template>
          </el-popconfirm>
          <el-button type="warning" @click="openReject">驳回</el-button>
        </div>
      </template>
    </el-drawer>

    <el-dialog v-model="rejectVisible" title="驳回认证申请" width="480px" class="portal-dialog-accent" destroy-on-close>
      <p v-if="detail" class="dialog-hint">
        认证 ID：{{ detail.authId }} — {{ detail.realName || detail.nickName || '申请人' }}
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

<style scoped>
.filter-input-sm {
  width: 140px;
}

.filter-input-range {
  width: 340px;
}

.filter-actions {
  margin-left: 4px;
}

.muted {
  color: var(--el-text-color-placeholder);
}

@media (max-width: 992px) {
  .filter-input-range {
    width: min(100%, 460px);
  }
}
</style>
