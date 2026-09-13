import request from './request'

/** 管理端评论 — AdminCommentController */
export const commentApi = {
  page: (params) => request.get('/admin/comment/page', { params }),
  audit: (data) => request.post('/admin/comment/audit', data),
  remove: (commentId) => request.delete(`/admin/comment/${commentId}`),
  reportPage: (params) => request.get('/admin/comment/report/page', { params }),
  reportHandle: (data) => request.post('/admin/comment/report/handle', data),
}

export default commentApi
