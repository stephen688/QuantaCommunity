import request from './request'

/** 管理端身份认证审核 — IdentityExamController */
export const identityExamApi = {
  page: (params) => request.get('/admin/identityExam/page', { params }),
  getById: (authId) => request.get(`/admin/identityExam/userAuth/detail/${authId}`),
  audit: (data) => request.post('/admin/identityExam/audit', data),
}

export default identityExamApi
