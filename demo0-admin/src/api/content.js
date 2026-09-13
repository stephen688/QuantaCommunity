import request from './request'

/** 管理端内容/帖子 — AdminContentController */
export const contentApi = {
  page: (params) => request.get('/admin/content/page', { params }),
  audit: (data) => request.post('/admin/content/audit', data),
  remove: (contentId) => request.delete(`/admin/content/${contentId}`),
  reportPage: (params) => request.get('/admin/content/report/page', { params }),
  reportHandle: (data) => request.post('/admin/content/report/handle', data),
}

export default contentApi
