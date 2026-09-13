<script setup>
import { computed, reactive, ref, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useUserStore } from '../../stores/user'
import {
  CircleCheck,
  User,
  ArrowRight,
  Document,
  ChatLineRound,
  Warning,
  ChatDotRound,
  Stamp,
} from '@element-plus/icons-vue'
import { contentApi } from '../../api/content'
import { answerApi } from '../../api/answer'
import { commentApi } from '../../api/comment'
import { identityExamApi } from '../../api/identityExam'

const router = useRouter()
const userStore = useUserStore()

const timeLabel = ref('')
let clockTimer = null

function tickClock() {
  const d = new Date()
  timeLabel.value = d.toLocaleString('zh-CN', {
    weekday: 'long',
    month: 'long',
    day: 'numeric',
    hour: '2-digit',
    minute: '2-digit',
  })
}

/** @typedef {'loading' | 'error' | number} StatValue */

/** @type {{ pendingContent: StatValue, pendingAnswer: StatValue, pendingPostReport: StatValue, pendingCommentReport: StatValue, pendingIdentity: StatValue }} */
const stats = reactive({
  pendingContent: 'loading',
  pendingAnswer: 'loading',
  pendingPostReport: 'loading',
  pendingCommentReport: 'loading',
  pendingIdentity: 'loading',
})

const shortcuts = [
  { path: '/user', label: '用户管理', icon: User },
  { path: '/content', label: '帖子管理', icon: Document },
  { path: '/answer', label: '回答管理', icon: ChatLineRound },
  { path: '/comment', label: '评论管理', icon: ChatDotRound },
  { path: '/report', label: '举报管理', icon: Warning },
  { path: '/identity', label: '身份认证', icon: Stamp },
]

function go(path) {
  router.push(path)
}

function formatStat(v) {
  if (v === 'loading') return '…'
  if (v === 'error') return '—'
  return String(v)
}

function isPendingStat(v) {
  return typeof v === 'number' && v > 0
}

const welcomeName = computed(
  () => userStore.nickName || userStore.username || '管理员',
)

async function fetchTotals() {
  const jobs = [
    {
      key: 'pendingContent',
      run: () => contentApi.page({ auditStatus: 0, pageSize: 1 }),
    },
    {
      key: 'pendingAnswer',
      run: () => answerApi.page({ auditStatus: 0, pageSize: 1 }),
    },
    {
      key: 'pendingPostReport',
      run: () => contentApi.reportPage({ status: 0, pageSize: 1 }),
    },
    {
      key: 'pendingCommentReport',
      run: () => commentApi.reportPage({ status: 0, pageSize: 1 }),
    },
    {
      key: 'pendingIdentity',
      run: () => identityExamApi.page({ auditStatus: 0, pageSize: 1 }),
    },
  ]

  await Promise.all(
    jobs.map(async ({ key, run }) => {
      try {
        const data = await run()
        stats[key] = Number(data?.total ?? 0)
      } catch {
        stats[key] = 'error'
      }
    }),
  )
}

const recentEvents = ref([])

async function fetchRecentEvents() {
  try {
    const [contentRes, identityRes] = await Promise.allSettled([
      contentApi.page({ pageNum: 1, pageSize: 3 }),
      identityExamApi.page({ pageNum: 1, pageSize: 3 }),
    ])
    const events = []
    if (contentRes.status === 'fulfilled' && contentRes.value) {
      const list = contentRes.value?.records ?? []
      for (const item of list) {
        events.push({
          id: `c-${item.id ?? item.contentId}`,
          type: 'content',
          text: `新帖子：${(item.title ?? '').slice(0, 28) || '无标题'}`,
          time: formatTimeLabel(item.createTime),
        })
      }
    }
    if (identityRes.status === 'fulfilled' && identityRes.value) {
      const list = identityRes.value?.records ?? []
      for (const item of list) {
        events.push({
          id: `i-${item.id ?? item.authId}`,
          type: 'identity',
          text: `认证申请：${(item.realName ?? '').slice(0, 8) || '未知用户'}`,
          time: formatTimeLabel(item.createTime ?? item.applyTime),
        })
      }
    }
    events.sort((a, b) => b.time.localeCompare(a.time))
    recentEvents.value = events.slice(0, 5)
  } catch {
    /* silently ignore */
  }
}

function formatTimeLabel(raw) {
  if (!raw) return ''
  try {
    return new Date(raw).toLocaleString('zh-CN', {
      month: '2-digit',
      day: '2-digit',
      hour: '2-digit',
      minute: '2-digit',
    })
  } catch {
    return ''
  }
}

onMounted(() => {
  tickClock()
  clockTimer = setInterval(tickClock, 60_000)
  fetchTotals()
  fetchRecentEvents()
})

onUnmounted(() => {
  if (clockTimer) clearInterval(clockTimer)
})
</script>

<template>
  <div class="dash-wrap">
    <section class="dash-hero" aria-label="欢迎信息">
      <div class="dash-hero-main">
        <h2 class="dash-hero-title">欢迎回来，{{ welcomeName }}</h2>
        <p class="dash-hero-time">{{ timeLabel }}</p>
      </div>
      <div class="dash-hero-aside">
        <span
          class="dash-hero-badge"
          :class="userStore.hasAnyManagementRole() ? 'dash-hero-badge--strong' : ''"
        >
          <el-icon class="dash-hero-badge-icon" :size="14"><CircleCheck /></el-icon>
          {{ userStore.hasAnyManagementRole() ? '管理端用户' : '已登录' }}
        </span>
      </div>
    </section>

    <section class="dash-stats" aria-label="待处理统计">
      <div class="dash-stats-grid">
        <button
          type="button"
          class="dash-stat card-lift"
          :class="{ 'dash-stat--pending': isPendingStat(stats.pendingContent) }"
          @click="go('/content')"
        >
          <div class="dash-stat-top">
            <div class="dash-stat-icon">
              <el-icon :size="22"><Document /></el-icon>
            </div>
            <span class="dash-stat-num count-animate">{{ formatStat(stats.pendingContent) }}</span>
          </div>
          <p class="dash-stat-label">待审核帖子</p>
          <span class="dash-stat-link">查看 <el-icon :size="14"><ArrowRight /></el-icon></span>
        </button>

        <button
          type="button"
          class="dash-stat card-lift"
          :class="{ 'dash-stat--pending': isPendingStat(stats.pendingAnswer) }"
          @click="go('/answer')"
        >
          <div class="dash-stat-top">
            <div class="dash-stat-icon">
              <el-icon :size="22"><ChatLineRound /></el-icon>
            </div>
            <span class="dash-stat-num count-animate">{{ formatStat(stats.pendingAnswer) }}</span>
          </div>
          <p class="dash-stat-label">待审核回答</p>
          <span class="dash-stat-link">查看 <el-icon :size="14"><ArrowRight /></el-icon></span>
        </button>

        <button
          type="button"
          class="dash-stat card-lift"
          :class="{ 'dash-stat--pending': isPendingStat(stats.pendingPostReport) }"
          @click="go('/report')"
        >
          <div class="dash-stat-top">
            <div class="dash-stat-icon">
              <el-icon :size="22"><Warning /></el-icon>
            </div>
            <span class="dash-stat-num count-animate">{{ formatStat(stats.pendingPostReport) }}</span>
          </div>
          <p class="dash-stat-label">待处理帖子举报</p>
          <span class="dash-stat-link">查看 <el-icon :size="14"><ArrowRight /></el-icon></span>
        </button>

        <button
          type="button"
          class="dash-stat card-lift"
          :class="{ 'dash-stat--pending': isPendingStat(stats.pendingCommentReport) }"
          @click="go('/report')"
        >
          <div class="dash-stat-top">
            <div class="dash-stat-icon">
              <el-icon :size="22"><ChatDotRound /></el-icon>
            </div>
            <span class="dash-stat-num count-animate">{{ formatStat(stats.pendingCommentReport) }}</span>
          </div>
          <p class="dash-stat-label">待处理评论举报</p>
          <span class="dash-stat-link">查看 <el-icon :size="14"><ArrowRight /></el-icon></span>
        </button>

        <button
          type="button"
          class="dash-stat card-lift"
          :class="{ 'dash-stat--pending': isPendingStat(stats.pendingIdentity) }"
          @click="go('/identity')"
        >
          <div class="dash-stat-top">
            <div class="dash-stat-icon">
              <el-icon :size="22"><Stamp /></el-icon>
            </div>
            <span class="dash-stat-num count-animate">{{ formatStat(stats.pendingIdentity) }}</span>
          </div>
          <p class="dash-stat-label">待审核身份认证</p>
          <span class="dash-stat-link">查看 <el-icon :size="14"><ArrowRight /></el-icon></span>
        </button>
      </div>
    </section>

    <section class="dash-shortcuts" aria-label="快捷入口">
      <h3 class="dash-shortcuts-title">快捷入口</h3>
      <div class="dash-shortcuts-grid">
        <button
          v-for="item in shortcuts"
          :key="item.path"
          type="button"
          class="dash-shortcut card-lift"
          @click="go(item.path)"
        >
          <span class="dash-shortcut-icon">
            <el-icon :size="22"><component :is="item.icon" /></el-icon>
          </span>
          <span class="dash-shortcut-label">{{ item.label }}</span>
          <el-icon class="dash-shortcut-arrow" :size="16"><ArrowRight /></el-icon>
        </button>
      </div>
    </section>

    <section class="dash-activity" aria-label="最近动态">
      <h3 class="dash-activity-title">最近动态</h3>
      <div class="dash-activity-list">
        <div v-for="event in recentEvents" :key="event.id" class="dash-activity-item">
          <span class="dash-activity-dot" :class="'dash-activity-dot--' + event.type" />
          <span class="dash-activity-text">{{ event.text }}</span>
          <span class="dash-activity-time">{{ event.time }}</span>
        </div>
        <div v-if="!recentEvents.length" class="dash-activity-empty">暂无动态</div>
      </div>
    </section>

    <div class="dash-tip">
      <p class="dash-tip-text">提示：更多数据与操作请从左侧菜单进入对应列表页。</p>
    </div>
  </div>
</template>

<style scoped>
.dash-wrap {
  max-width: 72rem;
  margin: 0 auto;
}

.dash-stat,
.dash-shortcut {
  cursor: pointer;
}

.dash-hero {
  position: relative;
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px 24px;
  margin-bottom: 24px;
  padding: 22px 24px 22px 28px;
  border-radius: var(--admin-radius-lg);
  overflow: hidden;
  background: linear-gradient(
    135deg,
    var(--admin-primary-subtle) 0%,
    color-mix(in srgb, var(--admin-primary-subtle) 40%, var(--admin-surface)) 36%,
    var(--admin-surface) 55%
  );
  color: var(--admin-text);
  border: 1px solid var(--admin-border);
  box-shadow: var(--shadow-xs);
}

.dash-hero::before {
  content: '';
  position: absolute;
  left: 0;
  top: 0;
  bottom: 0;
  width: 4px;
  background: var(--admin-primary);
  border-radius: var(--admin-radius-lg) 0 0 var(--admin-radius-lg);
}

.dash-hero-main {
  position: relative;
  z-index: 1;
  min-width: 0;
}

.dash-hero-title {
  margin: 0;
  font-size: 1.375rem;
  font-weight: 700;
  line-height: 1.25;
  letter-spacing: -0.02em;
}

.dash-hero-time {
  margin: 8px 0 0;
  font-size: 0.875rem;
  line-height: 1.45;
  color: var(--admin-text-muted);
}

.dash-hero-aside {
  position: relative;
  z-index: 1;
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.dash-hero-badge {
  display: inline-flex;
  align-items: center;
  gap: 6px;
  padding: 6px 12px;
  font-size: 0.75rem;
  font-weight: 600;
  line-height: 1.2;
  border-radius: 999px;
  color: var(--admin-primary-active);
  background: var(--admin-surface);
  border: 1px solid color-mix(in srgb, var(--admin-primary) 20%, var(--admin-border));
}

.dash-hero-badge--strong {
  background: var(--admin-primary-soft);
  border-color: color-mix(in srgb, var(--admin-primary) 28%, var(--admin-border));
}

.dash-hero-badge-icon {
  flex-shrink: 0;
}

.dash-stats {
  margin-bottom: 28px;
}

.dash-stats-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 16px;
}

@media (min-width: 640px) {
  .dash-stats-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (min-width: 1100px) {
  .dash-stats-grid {
    grid-template-columns: repeat(3, 1fr);
  }
}

@media (min-width: 1400px) {
  .dash-stats-grid {
    grid-template-columns: repeat(5, 1fr);
  }
}

.dash-stat {
  display: flex;
  flex-direction: column;
  align-items: flex-start;
  gap: 8px;
  margin: 0;
  padding: 18px 18px 16px;
  text-align: left;
  font-family: inherit;
  cursor: pointer;
  background: var(--admin-surface);
  border: 1px solid var(--admin-border);
  border-radius: var(--admin-radius-lg);
  transition: box-shadow 0.2s ease, border-color 0.2s ease, transform 0.2s ease;
}

.dash-stat:hover {
  border-color: color-mix(in srgb, var(--admin-primary) 35%, var(--admin-border));
  box-shadow: var(--shadow-sm);
  transform: translateY(-2px);
}

.dash-stat:focus-visible {
  outline: none;
  border-color: var(--admin-primary);
  box-shadow: var(--shadow-sm), 0 0 0 3px var(--admin-primary-ring);
}

.dash-stat--pending {
  border-color: color-mix(in srgb, var(--admin-warning) 42%, var(--admin-border));
  background: linear-gradient(
    165deg,
    var(--admin-surface) 0%,
    color-mix(in srgb, var(--admin-warning-soft) 55%, var(--admin-surface)) 100%
  );
}

.dash-stat--pending .dash-stat-num {
  color: var(--admin-warning);
}

.dash-stat--pending .dash-stat-icon {
  background: var(--admin-warning-soft);
  color: var(--admin-warning);
}

.dash-stat-top {
  display: flex;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 12px;
}

.dash-stat-icon {
  width: 44px;
  height: 44px;
  border-radius: var(--admin-radius);
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--admin-primary-soft);
  color: var(--admin-primary);
}

.dash-stat-num {
  font-size: 2rem;
  font-weight: 700;
  font-variant-numeric: tabular-nums;
  color: var(--admin-text);
  line-height: 1.1;
}

.dash-stat-label {
  margin: 0;
  font-size: 0.875rem;
  color: var(--admin-text-muted);
}

.dash-stat-link {
  display: inline-flex;
  align-items: center;
  gap: 4px;
  margin-top: 4px;
  font-size: 0.8125rem;
  font-weight: 600;
  color: var(--admin-primary);
}

.dash-shortcuts {
  margin-bottom: 28px;
}

.dash-shortcuts-title {
  margin: 0 0 12px;
  font-size: 0.9375rem;
  font-weight: 600;
  color: var(--admin-text);
}

.dash-shortcuts-grid {
  display: grid;
  grid-template-columns: 1fr;
  gap: 12px;
}

@media (min-width: 520px) {
  .dash-shortcuts-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}

@media (min-width: 900px) {
  .dash-shortcuts-grid {
    grid-template-columns: repeat(3, 1fr);
  }
}

.dash-shortcut {
  position: relative;
  display: flex;
  align-items: center;
  gap: 12px;
  width: 100%;
  margin: 0;
  padding: 14px 16px;
  text-align: left;
  font-family: inherit;
  cursor: pointer;
  color: var(--admin-text);
  background: var(--admin-surface);
  border: 1px solid var(--admin-border);
  border-radius: var(--admin-radius-lg);
  transition: box-shadow 0.2s ease, border-color 0.2s ease, transform 0.2s ease;
}

.dash-shortcut:hover {
  border-color: color-mix(in srgb, var(--admin-primary) 30%, var(--admin-border));
  box-shadow: var(--shadow-sm);
  transform: translateY(-2px);
}

.dash-shortcut:focus-visible {
  outline: none;
  border-color: var(--admin-primary);
  box-shadow: var(--shadow-sm), 0 0 0 3px var(--admin-primary-ring);
}

.dash-shortcut-icon {
  flex-shrink: 0;
  width: 44px;
  height: 44px;
  border-radius: var(--admin-radius);
  display: flex;
  align-items: center;
  justify-content: center;
  background: var(--admin-primary-soft);
  color: var(--admin-primary);
}

.dash-shortcut-label {
  flex: 1;
  font-size: 0.9375rem;
  font-weight: 600;
  min-width: 0;
}

.dash-shortcut-arrow {
  flex-shrink: 0;
  color: var(--admin-text-muted);
  transition: color 0.15s ease;
}

.dash-shortcut:hover .dash-shortcut-arrow {
  color: var(--admin-primary);
}

.dash-tip {
  background: var(--admin-primary-subtle);
  border: 1px solid var(--admin-border);
  border-radius: var(--admin-radius-lg);
  padding: 16px;
}

.dash-tip-text {
  margin: 0;
  font-size: 0.875rem;
  color: var(--admin-text-label);
  line-height: 1.5;
}

@media (prefers-reduced-motion: reduce) {
  .dash-stat:hover,
  .dash-shortcut:hover {
    transform: none;
  }
}

/* ===== Recent Activity Feed ===== */
.dash-activity {
  margin-bottom: 28px;
}
.dash-activity-title {
  margin: 0 0 14px;
  font-size: 0.9375rem;
  font-weight: 600;
  color: var(--admin-text);
}
.dash-activity-list {
  display: flex;
  flex-direction: column;
  gap: 2px;
}
.dash-activity-item {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 10px 14px;
  border-radius: var(--admin-radius);
  background: var(--admin-surface);
  border: 1px solid var(--admin-border);
  transition: border-color 0.2s ease;
}
.dash-activity-item:hover {
  border-color: var(--admin-border-strong);
}
.dash-activity-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
}
.dash-activity-dot--content {
  background: var(--admin-primary);
}
.dash-activity-dot--identity {
  background: var(--admin-success);
}
.dash-activity-dot--report {
  background: var(--admin-danger);
}
.dash-activity-text {
  flex: 1;
  font-size: 0.875rem;
  color: var(--admin-text);
  min-width: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}
.dash-activity-time {
  font-size: 0.75rem;
  color: var(--admin-text-muted);
  flex-shrink: 0;
}
.dash-activity-empty {
  padding: 20px;
  text-align: center;
  font-size: 0.875rem;
  color: var(--admin-text-muted);
}
</style>
