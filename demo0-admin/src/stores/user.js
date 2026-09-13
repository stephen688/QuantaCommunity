import { defineStore } from 'pinia'
import { ref } from 'vue'

const TOKEN_KEY = 'token'
const ADMIN_KEY = 'isAdmin'
const USER_ID_KEY = 'userId'
const NICK_NAME_KEY = 'nickName'
const AVATAR_URL_KEY = 'avatarUrl'

export const useUserStore = defineStore('user', () => {
  const token = ref(localStorage.getItem(TOKEN_KEY) ?? '')
  const isAdmin = ref(localStorage.getItem(ADMIN_KEY) === '1')
  const userId = ref(localStorage.getItem(USER_ID_KEY) ?? '')
  const nickName = ref(localStorage.getItem(NICK_NAME_KEY) ?? '')
  const avatarUrl = ref(localStorage.getItem(AVATAR_URL_KEY) ?? '')
  /** 顶栏展示名，与 nickName 同步 */
  const username = ref(nickName.value || '')

  /**
   * 安全上下文由后端 /user/security-context 返回，
   * 是前端权限判断的唯一事实来源。
   */
  const roles = ref([])
  const authorities = ref([])
  const verified = ref(false)
  const securityLoaded = ref(false)

  function setSession(payload = {}) {
    if ('token' in payload) {
      token.value = payload.token ?? ''
      if (token.value) {
        localStorage.setItem(TOKEN_KEY, token.value)
      } else {
        localStorage.removeItem(TOKEN_KEY)
      }
    }

    if ('isAdmin' in payload) {
      isAdmin.value = Boolean(payload.isAdmin)
      localStorage.setItem(ADMIN_KEY, isAdmin.value ? '1' : '0')
    }

    if (payload.userId != null && payload.userId !== '') {
      userId.value = String(payload.userId)
      localStorage.setItem(USER_ID_KEY, userId.value)
    }

    const nextNick =
      typeof payload.nickName === 'string' && payload.nickName.trim()
        ? payload.nickName.trim()
        : typeof payload.username === 'string' && payload.username.trim()
          ? payload.username.trim()
          : null

    if (nextNick) {
      nickName.value = nextNick
      username.value = nextNick
      localStorage.setItem(NICK_NAME_KEY, nextNick)
    }

    if (typeof payload.avatarUrl === 'string') {
      avatarUrl.value = payload.avatarUrl
      if (payload.avatarUrl) {
        localStorage.setItem(AVATAR_URL_KEY, payload.avatarUrl)
      } else {
        localStorage.removeItem(AVATAR_URL_KEY)
      }
    }
  }

  function setSecurityContext(context = {}) {
    roles.value = Array.isArray(context.roles) ? context.roles : []
    authorities.value = Array.isArray(context.authorities) ? context.authorities : []
    verified.value = Boolean(context.verified)
    securityLoaded.value = true
  }

  function hasAuthority(code) {
    if (!securityLoaded.value) {
      return false
    }
    return authorities.value.some((authority) => authority === code)
  }

  /**
   * 是否有任一后台管理角色（用于登录后判断是否允许进入管理端）。
   */
  function hasAnyManagementRole() {
    return roles.value.length > 0
  }

  function clearSession() {
    token.value = ''
    isAdmin.value = false
    userId.value = ''
    nickName.value = ''
    avatarUrl.value = ''
    username.value = ''
    roles.value = []
    authorities.value = []
    verified.value = false
    securityLoaded.value = false
    localStorage.removeItem(TOKEN_KEY)
    localStorage.removeItem(ADMIN_KEY)
    localStorage.removeItem(USER_ID_KEY)
    localStorage.removeItem(NICK_NAME_KEY)
    localStorage.removeItem(AVATAR_URL_KEY)
  }

  return {
    token,
    isAdmin,
    userId,
    nickName,
    avatarUrl,
    username,
    roles,
    authorities,
    verified,
    securityLoaded,
    setSession,
    setSecurityContext,
    hasAuthority,
    hasAnyManagementRole,
    clearSession,
  }
})
