import { createRouter, createWebHistory } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useUserStore } from '../stores/user'
import { authApi } from '../api/auth'
import { canAccessAdminPage, hasManagementRole } from '../utils/admin-access'

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  routes: [
    {
      path: '/login',
      name: 'Login',
      component: () => import('../views/login/Login.vue'),
      meta: { public: true },
    },
    {
      path: '/403',
      name: 'Forbidden',
      component: () => import('../views/error/Forbidden.vue'),
      meta: { public: true, title: '无权限访问' },
    },
    {
      path: '/',
      component: () => import('../layouts/DefaultLayout.vue'),
      children: [
        { path: '', redirect: '/dashboard' },
        {
          path: 'dashboard',
          name: 'Dashboard',
          meta: { title: '控制台' },
          component: () => import('../views/dashboard/Dashboard.vue'),
        },
        {
          path: 'user',
          name: 'UserList',
          meta: { title: '用户管理', authority: 'USER_BAN' },
          component: () => import('../views/user/UserList.vue'),
        },
        {
          path: 'content',
          name: 'ContentList',
          meta: { title: '帖子管理', authority: 'CONTENT_READ_ADMIN' },
          component: () => import('../views/content/ContentList.vue'),
        },
        {
          path: 'content/report',
          redirect: '/report',
        },
        {
          path: 'report',
          name: 'ReportManage',
          meta: { title: '举报管理', authority: 'CONTENT_READ_ADMIN' },
          component: () => import('../views/report/ReportManage.vue'),
        },
        {
          path: 'knowledge',
          name: 'PolicyDocList',
          meta: { title: '政策知识库', roles: ['OPERATIONS_ADMIN'] },
          component: () => import('../views/knowledge/PolicyDocList.vue'),
        },
        {
          path: 'answer',
          name: 'AnswerList',
          meta: { title: '回答管理', authority: 'CONTENT_READ_ADMIN' },
          component: () => import('../views/answer/AnswerList.vue'),
        },
        {
          path: 'comment',
          name: 'CommentList',
          meta: { title: '评论管理', authority: 'CONTENT_READ_ADMIN' },
          component: () => import('../views/comment/CommentList.vue'),
        },
        {
          path: 'identity',
          name: 'IdentityList',
          meta: { title: '身份认证', authority: 'IDENTITY_AUDIT' },
          component: () => import('../views/identity/IdentityList.vue'),
        },
        {
          path: 'events',
          name: 'EventCenter',
          meta: { title: '事件中心', authority: 'EVENT_READ' },
          component: () => import('../views/event/EventCenter.vue'),
        },
        {
          path: 'audit-logs',
          name: 'AuditLogList',
          meta: { title: '审计日志', authority: 'AUDIT_LOG_READ' },
          component: () => import('../views/audit/AuditLogList.vue'),
        },
      ],
    },
  ],
})

async function loadSecurityContext(userStore) {
  if (userStore.securityLoaded) {
    return
  }
  try {
    const ctx = await authApi.getSecurityContext()
    userStore.setSecurityContext(ctx || {})
  } catch {
    userStore.clearSession()
  }
}

router.beforeEach(async (to) => {
  const userStore = useUserStore()

  if (to.meta.public) {
    if (to.name === 'Login' && userStore.token) {
      const raw = to.query.redirect
      if (typeof raw === 'string' && raw.startsWith('/') && !raw.startsWith('//')) {
        return { path: raw }
      }
      return { name: 'Dashboard' }
    }
    return true
  }

  if (!userStore.token) {
    return { name: 'Login', query: { redirect: to.fullPath } }
  }

  await loadSecurityContext(userStore)

  if (!userStore.token) {
    return { name: 'Login', query: { redirect: to.fullPath } }
  }

  if (!hasManagementRole(userStore)) {
    ElMessage.warning('当前账号无管理权限')
    userStore.clearSession()
    return { name: 'Login' }
  }

  if (!canAccessAdminPage(userStore, to.meta)) {
    return { name: 'Forbidden' }
  }

  return true
})

export default router
