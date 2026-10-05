<script setup>
import { computed, reactive, ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { User } from '@element-plus/icons-vue'
import PageHeader from '../../components/PageHeader.vue'
import TableEmptyState from '../../components/TableEmptyState.vue'
import { usePermission } from '../../composables/usePermission'
import { userApi } from '../../api/user'
import { adminRoleApi } from '../../api/adminRole'
import router from '../../router'
import {
  ADMIN_ROLE_OPTIONS,
  isCurrentUser,
  isProtectedSelfRevoke,
  isRoleMutationDisabled,
} from '../../utils/admin-role.js'

const { hasAuthority, userStore } = usePermission()

const roleLabelMap = Object.fromEntries(
  ADMIN_ROLE_OPTIONS.map((role) => [role.code, role.label]),
)

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
const roleReadState = ref('idle')
const roleReadError = ref('')
const roleActionLoading = reactive({})
let detailRequestGeneration = 0
let detailRequestUserId = null

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
  return userApi.getById(userId)
}

function beginDetailRequest(userId) {
  detailRequestGeneration += 1
  detailRequestUserId = userId
  return detailRequestGeneration
}

function isCurrentDetailRequest(userId, generation) {
  return drawerVisible.value
    && generation === detailRequestGeneration
    && String(detailRequestUserId) === String(userId)
}

function invalidateDetailRequest() {
  detailRequestGeneration += 1
  detailRequestUserId = null
}

function resetRoleReadState() {
  roleReadState.value = 'idle'
  roleReadError.value = ''
}

function onDrawerVisibilityChange(visible) {
  drawerVisible.value = visible
  if (!visible) {
    invalidateDetailRequest()
    detail.value = null
    detailLoading.value = false
    resetRoleReadState()
  }
}

/**
 * 读取详情抽屉中的真实角色。
 *
 * 读取失败时保留错误态和重试入口，不能把服务端错误伪装成空角色。
 */
async function refreshRoles(userId, generation = detailRequestGeneration) {
  if (!isCurrentDetailRequest(userId, generation)) {
    return false
  }
  roleReadState.value = 'loading'
  roleReadError.value = ''
  try {
    const roles = await adminRoleApi.getUserRoles(userId)
    if (!Array.isArray(roles)) {
      throw new Error('角色数据格式错误')
    }
    if (!isCurrentDetailRequest(userId, generation)) {
      return false
    }
    detail.value = { ...detail.value, roles }
    roleReadState.value = 'ready'
    return true
  } catch (err) {
    if (!isCurrentDetailRequest(userId, generation)) {
      return false
    }
    roleReadState.value = 'error'
    roleReadError.value = `角色读取失败：${friendlyListError(err)}`
    detail.value = { ...detail.value, roles: null }
    return false
  } finally {
    if (isCurrentDetailRequest(userId, generation) && roleReadState.value === 'loading') {
      roleReadState.value = 'error'
      roleReadError.value = '角色读取失败：请求未完成，请重试。'
    }
  }
}

async function openDetail(row) {
  drawerVisible.value = true
  const generation = beginDetailRequest(row.id)
  detail.value = null
  detailLoading.value = true
  resetRoleReadState()
  try {
    const userInfo = await loadUserDetail(row.id)
    if (!isCurrentDetailRequest(row.id, generation)) {
      return
    }
    detail.value = { ...userInfo, roles: null }
  } catch {
    if (isCurrentDetailRequest(row.id, generation)) {
      onDrawerVisibilityChange(false)
    }
    return
  } finally {
    if (isCurrentDetailRequest(row.id, generation)) {
      detailLoading.value = false
    }
  }
  if (!isCurrentDetailRequest(row.id, generation)) {
    return
  }
  await refreshRoles(row.id, generation)
}

function roleActionKey(userId, roleCode) {
  return `${userId}:${roleCode}`
}

function isRoleActionBusy(userId, roleCode) {
  return Boolean(roleActionLoading[roleActionKey(userId, roleCode)])
}

function hasAssignedRole(roleCode) {
  return Array.isArray(detail.value?.roles) && detail.value.roles.includes(roleCode)
}

function isProtectedRoleRevoke(roleCode) {
  return isProtectedSelfRevoke({
    targetUserId: detail.value?.id,
    currentUserId: userStore.userId,
    roleCode,
    roles: detail.value?.roles,
  })
}

function roleMutationDisabled(roleCode) {
  return isRoleMutationDisabled({
    hasRoleManage: hasAuthority('ROLE_MANAGE'),
    rolesLoaded: roleReadState.value === 'ready' && Array.isArray(detail.value?.roles),
    busy: Object.keys(roleActionLoading).length > 0,
    protectedSelfRevoke: hasAssignedRole(roleCode) && isProtectedRoleRevoke(roleCode),
  })
}

function roleMutationTitle(roleCode) {
  if (hasAssignedRole(roleCode) && isProtectedRoleRevoke(roleCode)) {
    return '不能撤销自己唯一的超级管理员角色'
  }
  if (roleReadState.value !== 'ready' || !Array.isArray(detail.value?.roles)) {
    return '角色读取成功后才能操作'
  }
  return ''
}

async function retryRoleRead() {
  const userId = detail.value?.id
  const generation = detailRequestGeneration
  if (userId !== undefined && userId !== null) {
    await refreshRoles(userId, generation)
  }
}

/**
 * 授予或撤销一个管理角色。
 *
 * 变更当前登录用户后服务端会撤销会话，页面立即清理本地状态并回到登录页。
 */
async function changeRole(roleCode) {
  if (!detail.value
      || roleReadState.value !== 'ready'
      || !Array.isArray(detail.value.roles)
      || !hasAuthority('ROLE_MANAGE')) {
    return
  }

  const userId = detail.value.id
  const generation = detailRequestGeneration
  const revoke = hasAssignedRole(roleCode)
  if (revoke && isProtectedRoleRevoke(roleCode)) {
    ElMessage.warning('不能撤销自己唯一的超级管理员角色')
    return
  }
  const actionKey = roleActionKey(userId, roleCode)
  if (Object.keys(roleActionLoading).length > 0 || isRoleActionBusy(userId, roleCode)) {
    return
  }
  roleActionLoading[actionKey] = true

  const roleLabel = roleLabelMap[roleCode] || roleCode
  const actionLabel = revoke ? '撤销' : '授予'
  try {
    try {
      await ElMessageBox.confirm(
        `确认${actionLabel}${roleLabel}？角色变更会立即刷新该用户的登录权限。`,
        `确认${actionLabel}角色`,
        {
          confirmButtonText: `确认${actionLabel}`,
          cancelButtonText: '取消',
          type: 'warning',
        },
      )
    } catch {
      return
    }
    if (!isCurrentDetailRequest(userId, generation)) {
      return
    }
    if (revoke) {
      await adminRoleApi.revoke(userId, roleCode)
    } else {
      await adminRoleApi.grant(userId, roleCode)
    }

    if (isCurrentUser(userId, userStore.userId)) {
      const redirect = router.currentRoute.value.fullPath
      ElMessage.warning('当前登录账号的角色已变更，请重新登录')
      userStore.clearSession()
      await router.replace({ name: 'Login', query: { redirect } })
      return
    }

    if (!isCurrentDetailRequest(userId, generation)) {
      return
    }
    ElMessage.success(`${roleLabel}${actionLabel}成功`)
    const refreshed = await refreshRoles(userId, generation)
    if (!refreshed && isCurrentDetailRequest(userId, generation)) {
      ElMessage.warning('角色已更新，但列表刷新失败，请点击重试')
    }
  } catch (err) {
    if (err?.response?.status !== 401 && err?.response?.status !== 403) {
      ElMessage.error(friendlyListError(err))
    }
  } finally {
    delete roleActionLoading[actionKey]
  }
}

async function afterBanUnban() {
  ElMessage.success('操作成功')
  await fetchList()
  const userId = detail.value?.id
  const generation = detailRequestGeneration
  if (userId === undefined || userId === null || !isCurrentDetailRequest(userId, generation)) {
    return
  }
  try {
    const userInfo = await loadUserDetail(userId)
    if (!isCurrentDetailRequest(userId, generation)) {
      return
    }
    detail.value = { ...userInfo, roles: detail.value.roles }
    await refreshRoles(userId, generation)
  } catch {
    if (isCurrentDetailRequest(userId, generation)) {
      ElMessage.error('详情刷新失败，请稍后重试')
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
      :model-value="drawerVisible"
      @update:model-value="onDrawerVisibilityChange"
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
          <el-descriptions-item label="当前角色">
            <span v-if="roleReadState === 'loading'" class="role-read-muted">读取中…</span>
            <span v-else-if="roleReadState === 'error'" class="role-read-error">{{ roleReadError }}</span>
            <span v-else>{{ formatRoles(detail.roles) }}</span>
          </el-descriptions-item>
          <el-descriptions-item label="账号状态">{{ labelAccount(detail.accountStatus) }}</el-descriptions-item>
        </el-descriptions>
        <div v-if="hasAuthority('ROLE_MANAGE')" class="role-manager" data-testid="role-manager">
          <div class="role-manager-heading">
            <div>
              <h4>角色管理</h4>
              <p>仅可操作服务器支持的三种管理角色</p>
            </div>
            <el-tag v-if="roleReadState === 'ready'" type="success" size="small">已读取</el-tag>
            <el-tag v-else-if="roleReadState === 'loading'" type="info" size="small">读取中</el-tag>
            <el-tag v-else type="danger" size="small">读取失败</el-tag>
          </div>
          <div v-if="roleReadState === 'error'" class="role-read-retry">
            <span>{{ roleReadError }}</span>
            <el-button type="primary" link size="small" @click="retryRoleRead">重试</el-button>
          </div>
          <div class="role-manager-list">
            <div v-for="role in ADMIN_ROLE_OPTIONS" :key="role.code" class="role-manager-row">
              <span class="role-manager-label">{{ role.label }}</span>
              <el-tag v-if="hasAssignedRole(role.code)" type="success" size="small">已拥有</el-tag>
              <el-tag v-else type="info" size="small">未拥有</el-tag>
              <el-button
                :type="hasAssignedRole(role.code) ? 'danger' : 'primary'"
                plain
                size="small"
                :loading="isRoleActionBusy(detail.id, role.code)"
                :disabled="roleMutationDisabled(role.code)"
                :title="roleMutationTitle(role.code)"
                @click="changeRole(role.code)"
              >
                {{ hasAssignedRole(role.code) ? '撤销' : '授予' }}
              </el-button>
            </div>
          </div>
        </div>
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

.role-read-muted {
  color: var(--el-text-color-secondary);
}

.role-read-error {
  color: var(--el-color-danger);
}

.role-manager {
  padding: 14px 16px;
  border: 1px solid var(--admin-border);
  border-radius: 8px;
  background: var(--admin-surface-soft);
}

.role-manager-heading {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 12px;
}

.role-manager-heading h4 {
  margin: 0;
  color: var(--admin-text);
  font-size: 14px;
}

.role-manager-heading p {
  margin: 4px 0 0;
  color: var(--admin-text-muted);
  font-size: 12px;
}

.role-read-retry {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  margin-bottom: 10px;
  color: var(--el-color-danger);
  font-size: 12px;
}

.role-manager-list {
  display: grid;
  gap: 8px;
}

.role-manager-row {
  display: flex;
  align-items: center;
  gap: 8px;
  min-height: 32px;
}

.role-manager-label {
  flex: 1;
  color: var(--admin-text);
  font-size: 13px;
}
</style>
