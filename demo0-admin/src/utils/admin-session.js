import { hasManagementRole } from './admin-access.js'

/** 管理会话流程：复用服务器身份；异常时清除尚未确认权限的本地会话。 */
export async function loginAdminSession({ api, store, username, password, code, useDevCode = false, isDevelopment = false }) {
  try {
    if (useDevCode && !isDevelopment) throw new Error('开发登录仅在开发环境可用')
    const login = useDevCode
      ? await api.login(code)
      : await api.loginWithPassword({ username: username.trim(), password })
    if (!login?.token) throw new Error('登录响应缺少 token')
    store.setSession({ token: login.token, userId: login.id, nickName: login.nickName, avatarUrl: login.avatarUrl, isAdmin: false })
    const context = await api.getSecurityContext()
    store.setSecurityContext(context || {})
    if (!hasManagementRole(context)) throw new Error('当前账号无管理权限')
    const info = await api.getUserInfo()
    store.setSession({ token: login.token, userId: login.id, nickName: info?.nickName ?? login.nickName, avatarUrl: info?.avatarUrl ?? login.avatarUrl, isAdmin: true })
    return login
  } catch (error) {
    store.clearSession()
    throw error
  }
}

/** 服务端撤销成功或会话已经失效才自动清本地；网络失败交给页面明确处理。 */
export async function logoutAdminSession({ api, store }) {
  try {
    await api.logout()
  } catch (error) {
    if (error?.response?.status !== 401) throw error
  }
  store.clearSession()
}
