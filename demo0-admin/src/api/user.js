import request from './request'

/** 管理端用户 — AdminUserController */
export const userApi = {
  page: (params) => request.get('/admin/user/page', { params }),
  getById: (id) => request.get(`/admin/user/${id}`),
  ban: (userId) => request.post(`/admin/user/ban/${userId}`),
  unban: (userId) => request.post(`/admin/user/unban/${userId}`),
}

export default userApi
