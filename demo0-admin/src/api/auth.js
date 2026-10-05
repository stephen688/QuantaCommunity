import request from './request'

/** 管理密码登录及会话，复用用户资料与安全上下文。 */
export const authApi = {
  loginWithPassword: (data) => request.post('/admin/auth/login', data, { skipGlobalError: true }),
  login: (code) => request.post('/user/login', { code }),
  getUserInfo: () => request.get('/user/info'),
  /** 从后端获取当前用户的真实角色和权限（安全上下文）。 */
  getSecurityContext: () => request.get('/user/security-context'),
  logout: () => request.post('/admin/auth/logout', undefined, { skipGlobalError: true }),
}

export default authApi
