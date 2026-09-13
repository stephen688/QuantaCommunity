import request from './request'

/** 用户登录与基本信息 — UserController（非 /admin 前缀） */
export const authApi = {
  login: (code) => request.post('/user/login', { code }),
  getUserInfo: () => request.get('/user/info'),
  /** 从后端获取当前用户的真实角色和权限（安全上下文）。 */
  getSecurityContext: () => request.get('/user/security-context'),
}

export default authApi
