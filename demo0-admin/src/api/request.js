import axios from 'axios'
import { ElMessage } from 'element-plus'
import { getActivePinia } from 'pinia'
import router from '../router'
import { useUserStore } from '../stores/user'
import { installApiMock } from '../mock/installApiMock'
import { normalizeApiData } from '../utils/normalizeApiData'

const rawBase = import.meta.env.VITE_API_BASE
const baseURL =
  rawBase === undefined || rawBase === null || String(rawBase).trim() === ''
    ? ''
    : String(rawBase).replace(/\/$/, '')

const request = axios.create({
  baseURL,
  timeout: 30_000,
})

/**
 * 为 true 时所有 /admin/* 走前端内存静态数据（见 src/mock/staticData.js）。
 * 开发环境默认开启，除非设置 VITE_USE_MOCK=false；生产由 VITE_USE_STATIC_DATA 控制。
 */
const useStaticAdminData =
  import.meta.env.VITE_USE_STATIC_DATA === 'true' ||
  import.meta.env.VITE_USE_MOCK === 'true' ||
  (import.meta.env.DEV && import.meta.env.VITE_USE_MOCK !== 'false')

if (useStaticAdminData) {
  installApiMock(request)
}

function clearAuth() {
  const pinia = getActivePinia()
  if (pinia) {
    useUserStore(pinia).clearSession()
  } else {
    localStorage.removeItem('token')
    localStorage.removeItem('isAdmin')
    localStorage.removeItem('userId')
    localStorage.removeItem('nickName')
    localStorage.removeItem('avatarUrl')
  }
}

function handleUnauthorized(msg) {
  clearAuth()
  ElMessage.error(msg || '登录失效，请重新登录')
  const current = router.currentRoute.value
  if (current.name !== 'Login') {
    router.replace({ name: 'Login', query: { redirect: current.fullPath } })
  }
}

function extractRetryAfterSeconds(error) {
  const headers = error.response?.headers
  const raw = headers?.['retry-after'] ?? headers?.['Retry-After']
  const parsed = Number(raw)
  return Number.isFinite(parsed) && parsed > 0 ? parsed : undefined
}

request.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers = config.headers ?? {}
    config.headers.authorization = token
  }
  return config
})

request.interceptors.response.use(
  (response) => {
    const payload = response.data
    if (payload && typeof payload === 'object' && 'code' in payload) {
      if (payload.code === 200) {
        return normalizeApiData(payload.data)
      }
      if (payload.code === 401) {
        handleUnauthorized(payload.msg)
        return Promise.reject(new Error(payload.msg || '401'))
      }
      if (payload.code === 403) {
        if (!response.config.skipGlobalError) {
          ElMessage.error(payload.msg || '没有权限执行该操作')
        }
        return Promise.reject(new Error(payload.msg || '403'))
      }
      if (payload.code === 429) {
        if (!response.config.skipGlobalError) {
          ElMessage.error(payload.msg || '操作过于频繁，请稍后再试')
        }
        return Promise.reject(new Error(payload.msg || '429'))
      }
      if (!response.config.skipGlobalError) {
        ElMessage.error(payload.msg || '请求失败')
      }
      return Promise.reject(new Error(payload.msg || 'error'))
    }
    return payload
  },
  (error) => {
    const status = error.response?.status
    const data = error.response?.data
    if (status === 401) {
      const msg = data?.msg || error.message
      handleUnauthorized(msg)
      return Promise.reject(error)
    }
    if (status === 403) {
      if (!error.config?.skipGlobalError) {
        ElMessage.error(data?.msg || '没有权限执行该操作')
      }
      return Promise.reject(error)
    }
    if (status === 429) {
      const retryAfter = extractRetryAfterSeconds(error)
      const suffix = retryAfter ? `，请 ${retryAfter} 秒后重试` : '，请稍后再试'
      if (!error.config?.skipGlobalError) {
        ElMessage.error((data?.msg || '操作过于频繁') + suffix)
      }
      return Promise.reject(error)
    }
    if (!error.config?.skipGlobalError) {
      ElMessage.error(data?.msg || error.message || '网络异常')
    }
    return Promise.reject(error)
  },
)

export default request
