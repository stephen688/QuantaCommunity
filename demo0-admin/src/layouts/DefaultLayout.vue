<script setup>
import { computed, onBeforeUnmount, onMounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import {
  DataBoard,
  User,
  Document,
  Warning,
  ChatLineRound,
  ChatDotRound,
  Stamp,
  Connection,
  SwitchButton,
  Collection,
} from '@element-plus/icons-vue'
import { useUserStore } from '../stores/user'
import { eventApi } from '../api/event'

const route = useRoute()
const router = useRouter()
const userStore = useUserStore()
const eventDeadCount = ref(0)
let eventOverviewTimer = null

const activeMenu = computed(() => route.path)

const displayUser = computed(
  () => userStore.nickName || userStore.username || '管理员',
)

const displayAvatar = computed(() => userStore.avatarUrl?.trim() || '')

/** 与当前路由对应的顶栏图标（与侧栏菜单一致） */
const routeHeaderIcons = {
  Dashboard: DataBoard,
  UserList: User,
  ContentList: Document,
  ReportManage: Warning,
  AnswerList: ChatLineRound,
  CommentList: ChatDotRound,
  IdentityList: Stamp,
  EventCenter: Connection,
  AuditLogList: Collection,
}

const headerIcon = computed(() => routeHeaderIcons[route.name] || DataBoard)

const menuItems = [
  { index: '/dashboard', icon: DataBoard, label: '控制台' },
  { index: '/user', icon: User, label: '用户管理', authority: 'USER_BAN' },
  { index: '/content', icon: Document, label: '帖子管理', authority: 'CONTENT_AUDIT' },
  { index: '/report', icon: Warning, label: '举报管理', authority: 'REPORT_HANDLE' },
  { index: '/answer', icon: ChatLineRound, label: '回答管理', authority: 'CONTENT_AUDIT' },
  { index: '/comment', icon: ChatDotRound, label: '评论管理', authority: 'CONTENT_AUDIT' },
  { index: '/identity', icon: Stamp, label: '身份认证', authority: 'IDENTITY_AUDIT' },
  { index: '/events', icon: Connection, label: '事件中心', authority: 'EVENT_REPLAY', showBadge: true },
  { index: '/audit-logs', icon: Collection, label: '审计日志', authority: 'AUDIT_LOG_READ' },
]

function canShowMenu(item) {
  if (!item.authority) {
    return true
  }
  return userStore.hasAuthority(item.authority)
}

const roleText = computed(() => {
  if (!userStore.securityLoaded || userStore.roles.length === 0) {
    return '访客'
  }
  const labels = {
    SUPER_ADMIN: '超级管理员',
    CONTENT_AUDITOR: '内容审核员',
    OPERATIONS_ADMIN: '运营管理员',
  }
  return userStore.roles.map((role) => labels[role] || role).join('、')
})

const roleTagType = computed(() => {
  if (userStore.roles.includes('SUPER_ADMIN')) {
    return 'danger'
  }
  if (userStore.roles.includes('OPERATIONS_ADMIN')) {
    return 'warning'
  }
  if (userStore.roles.includes('CONTENT_AUDITOR')) {
    return 'success'
  }
  return 'info'
})

const avatarLetter = computed(() => {
  const name = String(displayUser.value || '').trim()
  if (!name) return '管'
  const ch = name[0]
  return /[a-zA-Z]/.test(ch) ? ch.toUpperCase() : ch
})

function logout() {
  userStore.clearSession()
  ElMessage.success({ message: '已退出登录', duration: 2000 })
  router.replace({ name: 'Login' })
}

async function loadEventDeadCount() {
  try {
    const data = await eventApi.overview({ skipGlobalError: true })
    eventDeadCount.value = Number(data?.outboxCounts?.DEAD ?? 0)
      + Number(data?.inboxCounts?.DEAD ?? 0)
  } catch {
    eventDeadCount.value = 0
  }
}

onMounted(() => {
  loadEventDeadCount()
  eventOverviewTimer = window.setInterval(loadEventDeadCount, 60_000)
})

onBeforeUnmount(() => {
  if (eventOverviewTimer) window.clearInterval(eventOverviewTimer)
})
</script>

<template>
  <el-container class="layout-root layout-portal">
    <el-aside width="220px" class="aside aside--light">
      <div class="brand">
        <div class="brand-mark" aria-hidden="true">
          <svg class="brand-svg" viewBox="0 0 40 40" fill="none" xmlns="http://www.w3.org/2000/svg">
            <path
              d="M20 4L8 9v11c0 7.2 5.1 13.9 12 16 6.9-2.1 12-8.8 12-16V9L20 4z"
              stroke="currentColor"
              stroke-width="1.75"
              stroke-linejoin="round"
            />
            <path
              d="M20 14l-3.5 2.1v4.2c0 2.5 1.8 4.8 3.5 5.5 1.7-.7 3.5-3 3.5-5.5v-4.2L20 14z"
              fill="var(--admin-primary)"
            />
          </svg>
        </div>
        <div class="brand-text">
          <div class="brand-title">QuantaAdmin</div>
          <div class="brand-sub">社区管理后台</div>
        </div>
      </div>

      <el-menu
        :default-active="activeMenu"
        router
        class="portal-menu portal-menu--sidebar"
        :ellipsis="false"
      >
        <template v-for="item in menuItems" :key="item.index">
          <el-menu-item
            v-if="canShowMenu(item)"
            :index="item.index"
          >
            <el-icon :size="18">
              <component :is="item.icon" />
            </el-icon>
            <span>{{ item.label }}</span>
            <el-badge
              v-if="item.showBadge && eventDeadCount > 0"
              :value="eventDeadCount"
              :max="99"
              class="menu-event-badge"
            />
          </el-menu-item>
        </template>
      </el-menu>

      <div class="aside-footer">
        <button type="button" class="aside-logout" @click="logout">
          <el-icon class="aside-logout-icon" :size="18"><SwitchButton /></el-icon>
          <span>退出登录</span>
        </button>
      </div>
    </el-aside>

    <el-container class="layout-main-wrap">
      <el-header class="header">
        <div class="header-left">
          <span class="header-crumb-icon" aria-hidden="true">
            <el-icon :size="22"><component :is="headerIcon" /></el-icon>
          </span>
          <span class="crumb">{{ route.meta.title || '管理后台' }}</span>
        </div>
        <div class="header-right">
          <div class="header-user" title="当前登录账号">
            <el-avatar
              v-if="displayAvatar"
              :size="36"
              :src="displayAvatar"
              class="header-avatar-img"
            />
            <span v-else class="header-avatar" aria-hidden="true">{{ avatarLetter }}</span>
            <div class="header-user-meta">
              <span class="admin-name">{{ displayUser }}</span>
              <el-tag :type="roleTagType" size="small" effect="light" round>
                {{ roleText }}
              </el-tag>
            </div>
          </div>
          <el-button type="primary" link class="logout-btn" @click="logout">退出</el-button>
        </div>
      </el-header>
      <el-main class="main">
        <router-view v-slot="{ Component }">
          <transition name="page-fade" mode="out-in">
            <component :is="Component" />
          </transition>
        </router-view>
      </el-main>
    </el-container>
  </el-container>
</template>

<style scoped>
.layout-root.layout-portal {
  min-height: 100vh;
  background: var(--admin-bg);
}

.aside.aside--light {
  display: flex;
  flex-direction: column;
  background: var(--sidebar-bg);
  border-right: 1px solid var(--sidebar-border);
  color: var(--sidebar-text);
}

.brand {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 20px 16px 18px;
  border-bottom: 1px solid var(--sidebar-border);
}

.brand-mark {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  border-radius: 10px;
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--admin-primary);
  background: linear-gradient(145deg, rgba(254,102,3,0.22), rgba(254,102,3,0.06));
  border: 1px solid rgba(254,102,3,0.2);
}

.brand-svg {
  width: 26px;
  height: 26px;
}

.brand-text {
  min-width: 0;
}

.brand-title {
  font-size: 1.25rem;
  font-weight: 700;
  color: var(--sidebar-text);
  line-height: 1.25;
  letter-spacing: -0.02em;
}

.brand-sub {
  margin-top: 4px;
  font-size: 0.75rem;
  color: var(--sidebar-text-muted);
}

.portal-menu.portal-menu--sidebar {
  flex: 1;
  min-height: 0;
  overflow-y: auto;
  border-right: none !important;
  padding: 10px 0 8px;
  --el-menu-bg-color: transparent;
  --el-menu-hover-bg-color: var(--sidebar-hover-bg);
  --el-menu-active-color: var(--sidebar-active-text);
  --el-menu-text-color: var(--sidebar-text-muted);
  --el-menu-hover-text-color: var(--sidebar-text);
  background: transparent !important;
}

.portal-menu.portal-menu--sidebar :deep(.el-menu-item) {
  margin: 3px 10px;
  border-radius: var(--admin-radius);
  height: 44px;
  line-height: 44px;
  color: var(--sidebar-text-muted);
  transition: background-color 0.18s ease, color 0.18s ease, box-shadow 0.18s ease;
  cursor: pointer;
}

.portal-menu.portal-menu--sidebar :deep(.el-menu-item:hover) {
  color: var(--sidebar-text);
  background: var(--sidebar-hover-bg) !important;
}

.portal-menu.portal-menu--sidebar :deep(.el-menu-item.is-active) {
  background: var(--sidebar-active-bg) !important;
  color: var(--sidebar-active-text) !important;
  font-weight: 600;
  box-shadow: inset 3px 0 0 var(--admin-primary),
              0 0 32px rgba(254,102,3,0.1),
              var(--shadow-xs);
}

.portal-menu.portal-menu--sidebar :deep(.el-menu-item:focus-visible) {
  outline: none;
  box-shadow: 0 0 0 3px var(--admin-primary-ring);
}

.portal-menu.portal-menu--sidebar :deep(.el-menu-item.is-active:focus-visible) {
  box-shadow: inset 3px 0 0 var(--admin-primary), 0 0 0 3px var(--admin-primary-ring);
}

.portal-menu.portal-menu--sidebar :deep(.el-menu-item .el-icon) {
  color: inherit;
}

.menu-event-badge {
  margin-left: auto;
}

.menu-event-badge :deep(.el-badge__content) {
  border: none;
  box-shadow: none;
}

.aside-footer {
  flex-shrink: 0;
  padding: 12px 12px 16px;
  border-top: 1px solid var(--sidebar-border);
}

.aside-logout {
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 8px;
  width: 100%;
  margin: 0;
  padding: 10px 12px;
  font-size: 0.875rem;
  font-weight: 600;
  font-family: inherit;
  color: var(--sidebar-text-muted);
  background: rgba(255, 255, 255, 0.05);
  border: 1px solid var(--sidebar-border);
  border-radius: var(--admin-radius);
  cursor: pointer;
  transition: background-color 0.18s ease, border-color 0.18s ease, color 0.18s ease;
}

.aside-logout:hover {
  color: #E8887A;
  background: rgba(200, 60, 60, 0.12);
  border-color: rgba(200, 60, 60, 0.25);
}

.aside-logout:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--admin-primary-ring);
}

.aside-logout-icon {
  flex-shrink: 0;
}

.layout-main-wrap {
  background: var(--admin-bg);
  min-width: 0;
}

.header {
  position: sticky;
  top: 0;
  z-index: 10;
  height: 60px !important;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 22px;
  background: rgba(255, 255, 255, 0.78);
  backdrop-filter: blur(14px);
  -webkit-backdrop-filter: blur(14px);
  border-bottom: 1px solid var(--admin-border);
  box-shadow: var(--shadow-xs);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 12px;
  min-width: 0;
}

.header-crumb-icon {
  flex-shrink: 0;
  width: 40px;
  height: 40px;
  border-radius: var(--admin-radius);
  display: flex;
  align-items: center;
  justify-content: center;
  color: var(--admin-primary);
  background: var(--admin-primary-subtle);
  border: 1px solid color-mix(in srgb, var(--admin-primary) 14%, var(--admin-border));
}

.crumb {
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--admin-text);
  letter-spacing: -0.01em;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 14px;
  flex-shrink: 0;
}

.header-user {
  display: flex;
  align-items: center;
  gap: 10px;
  min-width: 0;
}

.header-user-meta {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 4px;
  min-width: 0;
}

.header-avatar,
.header-avatar-img {
  flex-shrink: 0;
  width: 36px;
  height: 36px;
  border-radius: 50%;
}

.header-avatar {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.875rem;
  font-weight: 700;
  color: #fff;
  background: linear-gradient(160deg, var(--admin-primary-hover) 0%, var(--admin-primary-active) 100%);
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--admin-primary) 22%, transparent);
}

.header-avatar-img {
  box-shadow: 0 0 0 2px color-mix(in srgb, var(--admin-primary) 22%, transparent);
}

.admin-name {
  font-size: 0.875rem;
  font-weight: 500;
  color: var(--admin-text);
  max-width: 160px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.logout-btn {
  font-size: 0.875rem;
  font-weight: 600;
  padding: 6px 8px;
  border-radius: var(--admin-radius);
  min-height: 32px;
  box-sizing: border-box;
  cursor: pointer;
  transition: color 0.2s ease, opacity 0.2s ease;
}

.logout-btn:hover {
  opacity: 0.85;
}

.logout-btn:focus-visible {
  outline: none;
  box-shadow: 0 0 0 3px var(--admin-primary-ring);
}

.main {
  padding: 20px 22px 28px;
  background: var(--admin-bg);
  box-sizing: border-box;
}

@media (prefers-reduced-motion: reduce) {
  .portal-menu.portal-menu--sidebar :deep(.el-menu-item),
  .aside-logout {
    transition: none;
  }
}
</style>
