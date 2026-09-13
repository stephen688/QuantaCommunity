<script setup>
import { computed, reactive, ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { User } from '@element-plus/icons-vue'
import PageHeader from '../../components/PageHeader.vue'
import TableEmptyState from '../../components/TableEmptyState.vue'
import { usePermission } from '../../composables/usePermission'
import { userApi } from '../../api/user'
import { adminRoleApi } from '../../api/adminRole'

const { hasAuthority } = usePermission()

const roleLabelMap = {
  SUPER_ADMIN: '超级管理员',
  CONTENT_AUDITOR: '内容审核员',
  OPERATIONS_ADMIN: '运营管理员',
}

function formatRoles(roles) {
  if (!Array.isArray(roles) || roles.length === 0) {
    return '—'
  }
  return roles.map((role) => roleLabelMap[role] || role).join('、')
}

const tableLoading = ref(false)
const list = ref([])
const total = ref(0)
const initialComplete = ref(false)
const loadError = ref(null)

const query = reactive({
  nickName: '',
  accountStatus: undefined,
  pageNum: 1,
  pageSize: 10,
})

const drawerVisible = ref(false)
const detailLoading = ref(false)
const detail = ref(null)

function buildPageParams() {
  const p = {
    pageNum: query.pageNum,
    pageSize: query.pageSize,
  }
  const name = query.nickName?.trim()
  if (name) p.nickName = name
  if (query.accountStatus !== undefined && query.accountStatus !== null && query.accountStatus !== '') {
    p.accountStatus = query.accountStatus
  }
  return p
}

function friendlyListError(err) {
  const status = err?.response?.status
  const dataMsg = err?.response?.data?.msg
  if (status === 502 || status === 503) {
    return '服务暂时不可用（网关或上游异常），请稍后重试。'
  }
  if (status === 504) return '网关超时，请稍后重试。'
  if (!err?.response) {
    return '无法连接服务器，请确认网络或服务是否已启动。'
  }
  return dataMsg || err?.message || '列表加载失败，请重试。'
}

async function fetchList() {
  loadError.value = null
  tableLoading.value = true
  try {
    const data = await userApi.page(buildPageParams())
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
  query.nickName = ''
  query.accountStatus = undefined
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

async function confirmBan(id) {
  await userApi.ban(id)
  await afterBanUnban()
}

async function confirmUnban(id) {
  await userApi.unban(id)
  await afterBanUnban()
}

const authStatusLabel = {
  0: '未认证',
  1: '审核中',
  2: '已认证',
  3: '审核不通过',
}

function labelAuth(v) {
  if (v === undefined || v === null) return '-'
  return authStatusLabel[v] ?? String(v)
}

function labelAccount(v) {
  if (v === 1) return '封禁'
  if (v === 0) return '正常'
  return '-'
}

/** 详情抽屉摘要区：与 portal-badge 语义一致 */
function portalAuthBadgeMod(v) {
  if (v === 0) return 'muted'
  if (v === 1) return 'warning'
  if (v === 2) return 'success'
  if (v === 3) return 'danger'
  return 'muted'
}

function portalAccountBadgeMod(v) {
  return v === 1 ? 'danger' : 'success'
}

function accountTagType(v) {
  return v === 1 ? 'danger' : 'success'
}

async function loadUserDetail(userId) {
  const [userInfo, roles] = await Promise.all([
    userApi.getById(userId),
    adminRoleApi.getUserRoles(userId).catch(() => []),
  ])
  return { ...userInfo, roles }
}

async function openDetail(row) {
  drawerVisible.value = true
  detail.value = null
  detailLoading.value = true
  try {
    detail.value = await loadUserDetail(row.id)
  } catch {
    drawerVisible.value = false
  } finally {
    detailLoading.value = false
  }
}

async function afterBanUnban() {
  ElMessage.success('操作成功')
  await fetchList()
  if (detail.value?.id) {
    try {
      detail.value = await loadUserDetail(detail.value.id)
    } catch {
      /* ignore */
    }
  }
}

const showListSkeleton = computed(() => tableLoading.value && !initialComplete.value)

const headerBadge = computed(() => {
  if (!initialComplete.value) return ''
  return `共 ${total.value} 条`
})

onMounted(() => {
  fetchList()
})
</script>

<template>
  <div class="user-list portal-page">
    <PageHeader
      :icon="User"
      title="用户管理"
      description="管理注册用户、封禁异常账号"
      :badge="headerBadge"
      badge-tone="muted"
    />
    <el-card shadow="never" class="portal-panel toolbar-card">
      <el-form
        :inline="true"
        :model="query"
        class="filter-form"
        label-width="88px"
        @submit.prevent="onSearch"
      >
        <el-form-item label="昵称">
          <el-input v-model="query.nickName" placeholder="昵称关键词" clearable class="filter-input-nick" />
        </el-form-item>
        <el-form-item label="账号状态">
          <el-select v-model="query.accountStatus" placeholder="全部" clearable class="filter-input-sm">
            <el-option label="正常" :value="0" />
            <el-option label="封禁" :value="1" />
          </el-select>
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

      <div v-if="showListSkeleton" class="list-skeleton" aria-busy="true" aria-label="加载用户列表">
        <el-skeleton :rows="8" animated />
      </div>

      <div v-show="!showListSkeleton" class="table-wrap">
        <el-table
          v-loading="tableLoading"
          class="portal-table user-table"
          :data="list"
          row-key="id"
          size="small"
          header-cell-class-name="user-table-header"
          element-loading-text="加载中…"
          style="width: 100%"
        >
          <template #empty>
            <TableEmptyState
              v-if="!loadError"
              :icon="User"
              title="暂无符合条件的用户"
              description="尝试放宽筛选条件，或点击「重置」后重新搜索。"
            />
            <TableEmptyState
              v-else
              error
              title="列表未展示"
              description="请查看上方提示或使用「重试」。"
            />
          </template>

          <el-table-column prop="id" label="ID" width="80" align="center" />
          <el-table-column label="头像" width="72" align="center">
            <template #default="{ row }">
              <el-avatar v-if="row.avatarUrl" :size="34" :src="row.avatarUrl" />
              <span v-else class="muted">—</span>
            </template>
          </el-table-column>
          <el-table-column prop="nickName" label="昵称" min-width="120" show-overflow-tooltip />
          <el-table-column label="账号状态" width="100" align="center">
            <template #default="{ row }">
              <el-tag :type="accountTagType(row.accountStatus)" size="small">
                {{ labelAccount(row.accountStatus) }}
              </el-tag>
            </template>
          </el-table-column>
          <el-table-column prop="createTime" label="创建时间" width="170" align="left" />
          <el-table-column label="操作" width="248" fixed="right" align="right" header-align="right">
            <template #default="{ row }">
              <div class="action-cell">
                <el-button type="primary" plain size="small" class="action-detail" @click="openDetail(row)">
                  查看详情
                </el-button>
                <template v-if="row.accountStatus === 1 && hasAuthority('USER_BAN')">
                  <el-popconfirm title="确定解封该用户？" width="220" @confirm="confirmUnban(row.id)">
                    <template #reference>
                      <el-button type="success" plain size="small" class="action-mod" @click.stop>解封</el-button>
                    </template>
                  </el-popconfirm>
                </template>
                <template v-else-if="hasAuthority('USER_BAN')">
                  <el-popconfirm title="确定封禁该用户？" width="220" @confirm="confirmBan(row.id)">
                    <template #reference>
                      <el-button type="danger" plain size="small" class="action-mod" @click.stop>封禁</el-button>
                    </template>
                  </el-popconfirm>
                </template>
              </div>
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
      title="用户详情"
      size="520px"
      destroy-on-close
      class="portal-drawer"
    >
      <el-skeleton v-if="detailLoading" :rows="8" animated />
      <div v-else-if="detail" class="portal-detail-stack">
        <div class="portal-detail-summary">
          <el-image
            v-if="detail.avatarUrl"
            class="portal-detail-summary-thumb"
            :src="detail.avatarUrl"
            :preview-src-list="[detail.avatarUrl]"
            fit="cover"
            preview-teleported
          />
          <div v-else class="portal-detail-summary-thumb portal-detail-summary-thumb--ph">
            <el-icon><User /></el-icon>
          </div>
          <div class="portal-detail-summary-main">
            <h3 class="portal-detail-summary-title">{{ detail.nickName || '—' }}</h3>
            <div class="portal-detail-summary-meta">
              <span class="portal-badge portal-badge--muted"> ID {{ detail.id }} </span>
              <span class="portal-badge" :class="'portal-badge--' + portalAuthBadgeMod(detail.authStatus)">
                {{ labelAuth(detail.authStatus) }}
              </span>
              <span class="portal-badge" :class="'portal-badge--' + portalAccountBadgeMod(detail.accountStatus)">
                {{ labelAccount(detail.accountStatus) }}
              </span>
            </div>
            <p class="portal-detail-summary-sub">
              创建 {{ detail.createTime ?? '—' }} · 更新 {{ detail.updateTime ?? '—' }}
            </p>
          </div>
        </div>
        <el-descriptions class="portal-details-in-drawer" title="基本信息" :column="1" border>
          <el-descriptions-item label="OpenID">{{ detail.openid ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="昵称">{{ detail.nickName ?? '-' }}</el-descriptions-item>
        </el-descriptions>
        <el-descriptions class="portal-details-in-drawer" title="权限与状态" :column="1" border>
          <el-descriptions-item label="认证状态">{{ labelAuth(detail.authStatus) }}</el-descriptions-item>
          <el-descriptions-item label="当前角色">{{ formatRoles(detail.roles) }}</el-descriptions-item>
          <el-descriptions-item label="账号状态">{{ labelAccount(detail.accountStatus) }}</el-descriptions-item>
        </el-descriptions>
        <el-descriptions class="portal-details-in-drawer" title="时间记录" :column="1" border>
          <el-descriptions-item label="创建时间">{{ detail.createTime ?? '-' }}</el-descriptions-item>
          <el-descriptions-item label="更新时间">{{ detail.updateTime ?? '-' }}</el-descriptions-item>
        </el-descriptions>
      </div>
      <template v-if="detail && !detailLoading" #footer>
        <div class="drawer-footer">
          <template v-if="detail.accountStatus === 1 && hasAuthority('USER_BAN')">
            <el-popconfirm title="确定解封该用户？" @confirm="confirmUnban(detail.id)">
              <template #reference>
                <el-button type="success">解封</el-button>
              </template>
            </el-popconfirm>
          </template>
          <template v-else-if="hasAuthority('USER_BAN')">
            <el-popconfirm title="确定封禁该用户？" @confirm="confirmBan(detail.id)">
              <template #reference>
                <el-button type="danger">封禁</el-button>
              </template>
            </el-popconfirm>
          </template>
        </div>
      </template>
    </el-drawer>
  </div>
</template>

<style scoped>
.filter-input-nick {
  width: 180px;
}

.filter-input-sm {
  width: 128px;
}

.filter-actions {
  margin-left: 4px;
}

.user-table :deep(.user-table-header .cell) {
  font-weight: 600;
}

.user-table :deep(.el-table__body .el-table__cell) {
  vertical-align: middle;
}

.muted {
  color: var(--el-text-color-placeholder);
}
</style>
