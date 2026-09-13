<script setup>
import { useRouter } from 'vue-router'

const router = useRouter()

function goBack() {
  router.replace('/dashboard')
}
</script>

<template>
  <main class="forbidden-page" aria-labelledby="forbidden-heading">
    <div class="forbidden-card">
      <div class="forbidden-icon" aria-hidden="true">
        <svg viewBox="0 0 24 24" width="64" height="64" fill="none" stroke="currentColor" stroke-width="1.5">
          <path d="M12 22s8-4 8-10V5l-8-3-8 3v7c0 6 8 10 8 10z" stroke-linecap="round" stroke-linejoin="round" />
          <path d="M9 12h6M12 9v6" stroke-linecap="round" />
        </svg>
      </div>
      <h1 id="forbidden-heading" class="forbidden-title">403</h1>
      <p class="forbidden-subtitle">抱歉，你没有权限访问该页面</p>
      <p class="forbidden-desc">
        你的角色或权限不足以打开该功能，<br />请联系超级管理员申请授权。
      </p>
      <el-button type="primary" size="large" class="forbidden-back-btn" @click="goBack">
        返回控制台
      </el-button>
    </div>
  </main>
</template>

<style scoped>
.forbidden-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 24px;
  background: var(--admin-bg-soft, #f6f7f9);
  box-sizing: border-box;
}

.forbidden-card {
  max-width: 420px;
  width: 100%;
  text-align: center;
  padding: 48px 32px;
  border-radius: 20px;
  background: #fff;
  border: 1px solid var(--admin-border, #e4e7ec);
  box-shadow:
    0 0 0 1px rgba(15, 23, 42, 0.03),
    0 12px 32px -8px rgba(15, 23, 42, 0.1);
}

.forbidden-icon {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 96px;
  height: 96px;
  border-radius: 50%;
  margin-bottom: 24px;
  background: var(--admin-warning-soft, #fff7ed);
  color: var(--admin-warning, #f97316);
}

.forbidden-title {
  margin: 0 0 8px;
  font-size: 3rem;
  font-weight: 800;
  letter-spacing: -0.03em;
  color: var(--admin-text, #1f2937);
  line-height: 1;
}

.forbidden-subtitle {
  margin: 0 0 16px;
  font-size: 1.125rem;
  font-weight: 600;
  color: var(--admin-text, #1f2937);
}

.forbidden-desc {
  margin: 0 0 28px;
  font-size: 0.9375rem;
  line-height: 1.7;
  color: var(--admin-text-muted, #6b7280);
}

.forbidden-back-btn {
  height: 44px;
  padding: 0 28px;
  font-weight: 600;
  border-radius: 10px;
}
</style>
