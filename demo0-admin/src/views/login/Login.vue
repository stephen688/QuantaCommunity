<script setup>
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { authApi } from '../../api/auth'
import { useUserStore } from '../../stores/user'

const router = useRouter()
const route = useRoute()
const userStore = useUserStore()
const loading = ref(false)
const error = ref('')

const form = reactive({
  code: 'test',
})

async function onSubmit() {
  error.value = ''
  const code = form.code?.trim()
  if (!code) {
    error.value = '请输入登录码'
    return
  }
  loading.value = true
  try {
    const loginData = await authApi.login(code)
    if (!loginData?.token) {
      error.value = '登录响应缺少 token'
      return
    }

    userStore.setSession({
      token: loginData.token,
      userId: loginData.id,
      nickName: loginData.nickName,
      avatarUrl: loginData.avatarUrl,
      isAdmin: false,
    })

    const userInfo = await authApi.getUserInfo()
    userStore.setSession({
      token: loginData.token,
      userId: loginData.id,
      nickName: userInfo?.nickName ?? loginData.nickName,
      avatarUrl: userInfo?.avatarUrl ?? loginData.avatarUrl,
      isAdmin: false,
    })

    let securityContext
    try {
      securityContext = await authApi.getSecurityContext()
    } catch {
      userStore.clearSession()
      error.value = '当前账号无管理权限'
      return
    }

    userStore.setSecurityContext(securityContext || {})
    if (!userStore.hasAnyManagementRole()) {
      userStore.clearSession()
      error.value = '当前账号无管理权限'
      return
    }

    userStore.setSession({
      token: loginData.token,
      userId: loginData.id,
      nickName: userInfo?.nickName ?? loginData.nickName,
      avatarUrl: userInfo?.avatarUrl ?? loginData.avatarUrl,
      isAdmin: userStore.hasAnyManagementRole(),
    })

    ElMessage.success('登录成功')
    const raw = route.query.redirect
    const target =
      typeof raw === 'string' && raw.startsWith('/') && !raw.startsWith('//')
        ? raw
        : '/dashboard'
    await router.replace(target)
  } catch (e) {
    userStore.clearSession()
    if (!error.value) {
      error.value = e?.message || '登录失败，请确认后端与 Redis 已启动'
    }
  } finally {
    loading.value = false
  }
}
</script>

<template>
  <main class="login-page" aria-labelledby="login-heading">
    <div class="login-page-bg" aria-hidden="true">
      <div class="login-page-bg__mesh" />
      <div class="login-page-bg__glow login-page-bg__glow--a" />
      <div class="login-page-bg__glow login-page-bg__glow--b" />
      <div class="login-page-bg__glow login-page-bg__glow--c" />
      <div class="login-page-bg__grid" />
    </div>

    <div class="login-split">
      <aside class="login-hero" aria-label="产品说明">
        <div class="login-hero-scrim" aria-hidden="true" />
        <div class="login-hero-inner portal-fade-in-up">
          <div class="login-hero-badge">
            <span class="login-hero-badge-dot" aria-hidden="true" />
            <span>QuantaAdmin</span>
          </div>
          <h2 class="login-hero-title">社区管理后台</h2>
          <p class="login-hero-slogan">审核、举报与身份认证一站完成，数据驱动运营决策。</p>
          <ul class="login-hero-list">
            <li class="login-hero-item">
              <span class="login-hero-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.75">
                  <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" stroke-linecap="round" stroke-linejoin="round" />
                  <path d="m9 12 2 2 4-4" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </span>
              <span>帖子 / 回答 / 评论审核流集中呈现</span>
            </li>
            <li class="login-hero-item">
              <span class="login-hero-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.75">
                  <path d="M10.29 3.86 1.82 18a2 2 0 0 0 1.71 3h16.94a2 2 0 0 0 1.71-3L13.71 3.86a2 2 0 0 0-3.42 0z" stroke-linecap="round" stroke-linejoin="round" />
                  <path d="M12 9v4M12 17h.01" stroke-linecap="round" />
                </svg>
              </span>
              <span>举报工单状态与处理备注可追溯</span>
            </li>
            <li class="login-hero-item">
              <span class="login-hero-icon" aria-hidden="true">
                <svg viewBox="0 0 24 24" width="20" height="20" fill="none" stroke="currentColor" stroke-width="1.75">
                  <path d="M4 19V5M8 19V9M12 19v-6M16 19v-3M20 19V11" stroke-linecap="round" stroke-linejoin="round" />
                </svg>
              </span>
              <span>控制台实时汇总待办与快捷入口</span>
            </li>
          </ul>
        </div>
      </aside>

      <section class="login-panel">
        <header class="login-mobile-brand">
          <span class="login-mobile-mark" aria-hidden="true" />
          <span class="login-mobile-title">QuantaAdmin</span>
        </header>

        <div class="login-panel-inner">
          <div class="login-glass portal-fade-in-up">
            <div class="login-glass-shine" aria-hidden="true" />
            <div class="login-form-head">
              <h1 id="login-heading" class="login-title">欢迎回来</h1>
              <p class="login-sub">登录后继续管理工作台</p>
            </div>

            <el-form class="login-form" label-position="top" @submit.prevent="onSubmit">
              <el-form-item label="登录码">
                <el-input
                  v-model="form.code"
                  size="large"
                  clearable
                  autocomplete="off"
                  placeholder="开发环境默认 test"
                />
              </el-form-item>
              <p v-if="error" class="field-error" role="alert">{{ error }}</p>
              <el-button
                class="login-submit-btn"
                native-type="submit"
                :loading="loading"
                size="large"
              >
                进入控制台
              </el-button>
            </el-form>

            <p class="login-hint">开发环境：使用 test 码登录，需 user_role 表拥有 SUPER_ADMIN 等管理角色</p>
          </div>
        </div>
      </section>
    </div>
  </main>
</template>

<style scoped>
.login-page {
  position: relative;
  isolation: isolate;
  min-height: 100vh;
  margin: 0;
  overflow: hidden;
  box-sizing: border-box;
}

.login-page-bg {
  position: absolute;
  inset: 0;
  z-index: 0;
  pointer-events: none;
}

.login-page-bg__mesh {
  position: absolute;
  inset: -12%;
  background:
    radial-gradient(ellipse 90% 70% at 15% 20%, rgba(254, 102, 3, 0.42), transparent 52%),
    radial-gradient(ellipse 70% 90% at 88% 72%, rgba(255, 140, 66, 0.35), transparent 48%),
    radial-gradient(ellipse 55% 50% at 48% 100%, rgba(30, 90, 76, 0.20), transparent 42%),
    linear-gradient(168deg, #1A120E 0%, #3D2A1F 38%, #1E5A4C 72%, #0D2018 100%);
  background-size: 140% 140%;
  animation: loginMeshShift 22s ease-in-out infinite alternate;
}

.login-page-bg__glow {
  position: absolute;
  border-radius: 50%;
  filter: blur(72px);
  opacity: 0.55;
  mix-blend-mode: screen;
  animation: loginOrbFloat 26s ease-in-out infinite alternate;
}

.login-page-bg__glow--a {
  width: min(52vw, 420px);
  height: min(52vw, 420px);
  top: -8%;
  left: -6%;
  background: radial-gradient(circle at 30% 30%, rgba(255, 140, 66, 0.85), rgba(254, 102, 3, 0.12) 70%, transparent 72%);
  animation-duration: 24s;
}

.login-page-bg__glow--b {
  width: min(48vw, 380px);
  height: min(48vw, 380px);
  bottom: -12%;
  right: -4%;
  background: radial-gradient(circle at 70% 60%, rgba(255, 184, 128, 0.65), rgba(204, 79, 0, 0.16) 68%, transparent 72%);
  animation-duration: 28s;
  animation-delay: -4s;
}

.login-page-bg__glow--c {
  width: min(36vw, 280px);
  height: min(36vw, 280px);
  top: 42%;
  left: 38%;
  background: radial-gradient(circle, rgba(30, 90, 76, 0.25), transparent 68%);
  opacity: 0.35;
  mix-blend-mode: soft-light;
  animation-duration: 20s;
  animation-delay: -8s;
}

.login-page-bg__grid {
  position: absolute;
  inset: 0;
  opacity: 0.22;
  background-image:
    linear-gradient(rgba(255, 255, 255, 0.06) 1px, transparent 1px),
    linear-gradient(90deg, rgba(255, 255, 255, 0.05) 1px, transparent 1px);
  background-size: 48px 48px;
  mask-image: radial-gradient(ellipse 85% 75% at 50% 45%, black 20%, transparent 75%);
}

@keyframes loginMeshShift {
  0% {
    transform: translate(0, 0) scale(1);
    background-position: 0% 40%;
  }
  100% {
    transform: translate(-1.5%, 1.2%) scale(1.04);
    background-position: 100% 60%;
  }
}

@keyframes loginOrbFloat {
  0% {
    transform: translate(0, 0) scale(1);
  }
  100% {
    transform: translate(2.5%, -2%) scale(1.08);
  }
}

.login-split {
  position: relative;
  z-index: 1;
  display: grid;
  grid-template-columns: 1fr 1fr;
  min-height: 100vh;
}

.login-hero {
  position: relative;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: clamp(36px, 7vw, 72px) clamp(28px, 5vw, 56px);
  box-sizing: border-box;
  color: #F5EDE5;
}

.login-hero-scrim {
  position: absolute;
  inset: 0;
  background: linear-gradient(
    115deg,
    rgba(20, 16, 14, 0.75) 0%,
    rgba(61, 42, 31, 0.48) 45%,
    rgba(20, 16, 14, 0.18) 100%
  );
  backdrop-filter: blur(2px);
}

.login-hero-inner {
  position: relative;
  z-index: 1;
  max-width: 24rem;
}

.login-hero-badge {
  display: inline-flex;
  align-items: center;
  gap: 10px;
  padding: 6px 14px 6px 10px;
  margin-bottom: 20px;
  font-size: 0.75rem;
  font-weight: 700;
  letter-spacing: 0.12em;
  text-transform: uppercase;
  color: rgba(245, 238, 230, 0.92);
  background: rgba(255, 255, 255, 0.08);
  border: 1px solid rgba(255, 255, 255, 0.14);
  border-radius: 999px;
  backdrop-filter: blur(10px);
}

.login-hero-badge-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: linear-gradient(135deg, #FF8C42, #FE6603);
  box-shadow: 0 0 14px rgba(254, 102, 3, 0.65);
}

.login-hero-title {
  margin: 0;
  font-size: clamp(2rem, 3.6vw, 2.65rem);
  font-weight: 800;
  line-height: 1.12;
  letter-spacing: -0.02em;
  color: #fff;
  text-shadow: 0 2px 28px rgba(0, 0, 0, 0.35);
}

.login-hero-slogan {
  margin: 18px 0 0;
  font-size: 1.0625rem;
  line-height: 1.65;
  color: rgba(240, 232, 222, 0.90);
  max-width: 26em;
}

.login-hero-list {
  margin: 36px 0 0;
  padding: 0;
  list-style: none;
  display: flex;
  flex-direction: column;
  gap: 14px;
}

.login-hero-item {
  display: flex;
  align-items: center;
  gap: 14px;
  padding: 14px 16px;
  font-size: 0.9375rem;
  line-height: 1.45;
  color: rgba(245, 238, 230, 0.92);
  background: rgba(255, 255, 255, 0.06);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 14px;
  backdrop-filter: blur(12px);
  transition: border-color 0.2s ease, background-color 0.2s ease, transform 0.2s ease;
}

.login-hero-item:hover {
  background: rgba(255, 255, 255, 0.1);
  border-color: rgba(255, 255, 255, 0.18);
  transform: translateX(4px);
}

.login-hero-icon {
  flex-shrink: 0;
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: 12px;
  background: linear-gradient(145deg, rgba(254, 102, 3, 0.30), rgba(204, 79, 0, 0.16));
  border: 1px solid rgba(255, 184, 128, 0.28);
  color: #FFB380;
}

.login-panel {
  display: flex;
  flex-direction: column;
  align-items: stretch;
  justify-content: center;
  padding: clamp(28px, 6vw, 56px) clamp(22px, 4vw, 64px);
  box-sizing: border-box;
}

.login-mobile-brand {
  display: none;
  align-items: center;
  gap: 12px;
  margin-bottom: 24px;
}

.login-mobile-mark {
  width: 11px;
  height: 11px;
  border-radius: 4px;
  background: linear-gradient(135deg, #FE6603, #FF8C42);
  box-shadow: 0 0 16px rgba(254, 102, 3, 0.50);
}

.login-mobile-title {
  font-size: 1.1875rem;
  font-weight: 800;
  color: var(--admin-text);
  letter-spacing: -0.02em;
}

.login-panel-inner {
  width: 100%;
  max-width: 420px;
  margin: 0 auto;
}

.login-glass {
  position: relative;
  padding: clamp(28px, 4vw, 40px) clamp(24px, 3.5vw, 36px) 32px;
  border-radius: 20px;
  background: linear-gradient(145deg, rgba(255, 255, 255, 0.92) 0%, rgba(248, 250, 252, 0.88) 100%);
  border: 1px solid rgba(255, 255, 255, 0.65);
  box-shadow:
    0 0 0 1px rgba(15, 23, 42, 0.04),
    0 24px 48px -12px rgba(15, 23, 42, 0.18),
    0 12px 24px -8px rgba(30, 27, 75, 0.12);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
}

.login-glass-shine {
  pointer-events: none;
  position: absolute;
  top: 0;
  left: 0;
  right: 0;
  height: 42%;
  border-radius: 20px 20px 40% 40%;
  background: linear-gradient(180deg, rgba(255, 255, 255, 0.55), transparent);
  opacity: 0.55;
}

.login-form-head {
  position: relative;
  z-index: 1;
  margin-bottom: 26px;
}

.login-title {
  margin: 0;
  font-size: 1.625rem;
  font-weight: 800;
  letter-spacing: -0.03em;
  color: var(--admin-text);
  line-height: 1.2;
}

.login-sub {
  margin: 10px 0 0;
  font-size: 0.9375rem;
  color: var(--admin-text-muted);
  line-height: 1.5;
}

.login-form {
  position: relative;
  z-index: 1;
}

.login-form :deep(.el-form-item) {
  margin-bottom: 20px;
}

.login-form :deep(.el-form-item__label) {
  font-weight: 700;
  font-size: 0.8125rem;
  color: var(--admin-text-label);
}

.login-form :deep(.el-input__wrapper) {
  border-radius: 12px;
  box-shadow: 0 0 0 1px rgba(148, 163, 184, 0.35) inset;
  transition: box-shadow 0.2s ease, background-color 0.2s ease;
}

.login-form :deep(.el-input__wrapper:hover) {
  box-shadow: 0 0 0 1px rgba(100, 116, 139, 0.45) inset;
}

.login-form :deep(.el-input__wrapper.is-focus) {
  box-shadow: 0 0 0 2px var(--admin-primary-ring) inset !important;
}

.field-error {
  margin: -4px 0 14px;
  font-size: 0.875rem;
  color: var(--admin-danger);
  background: var(--admin-danger-soft);
  padding: 10px 14px;
  border-radius: 12px;
  border: 1px solid color-mix(in srgb, var(--admin-danger) 18%, transparent);
}

.login-submit-btn {
  width: 100%;
  margin-top: 6px;
  height: 48px !important;
  font-weight: 700 !important;
  letter-spacing: 0.02em;
  border: none !important;
  border-radius: 12px !important;
  color: #fff !important;
  cursor: pointer;
  background: linear-gradient(135deg, #FE6603 0%, #FF8C42 48%, #E55A00 100%) !important;
  box-shadow:
    0 4px 16px rgba(254, 102, 3, 0.38),
    0 0 0 1px rgba(255, 255, 255, 0.12) inset;
  transition: transform 0.2s ease, box-shadow 0.2s ease, filter 0.2s ease;
}

.login-submit-btn:hover:not(:disabled),
.login-submit-btn:focus-visible:not(:disabled) {
  transform: translateY(-1px);
  box-shadow:
    0 8px 24px rgba(254, 102, 3, 0.45),
    0 0 0 1px rgba(255, 255, 255, 0.18) inset;
  filter: brightness(1.04);
}

.login-hint {
  position: relative;
  z-index: 1;
  margin: 26px 0 0;
  font-size: 0.75rem;
  color: var(--admin-text-muted);
  text-align: center;
  line-height: 1.55;
}

@media (max-width: 900px) {
  .login-split {
    grid-template-columns: 1fr;
    min-height: 100vh;
  }

  .login-hero {
    display: none;
  }

  .login-mobile-brand {
    display: flex;
  }

  .login-panel {
    flex: 1;
    justify-content: flex-start;
    padding-top: max(20px, env(safe-area-inset-top, 0px));
  }

  .login-glass {
    background: rgba(255, 255, 255, 0.97);
    backdrop-filter: blur(14px);
  }
}

@media (prefers-reduced-motion: reduce) {
  .login-page-bg__mesh,
  .login-page-bg__glow {
    animation: none !important;
  }

  .login-hero-item:hover {
    transform: none;
  }

  .login-submit-btn:hover:not(:disabled),
  .login-submit-btn:focus-visible:not(:disabled) {
    transform: none;
  }
}
</style>
