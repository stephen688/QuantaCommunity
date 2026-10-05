import request from './request'

/** 管理端政策源文档 — 仅操作 /admin/knowledge/policy-docs。 */
export const policyDocApi = {
  page: (params) => request.get('/admin/knowledge/policy-docs/page', { params }),
  detail: (docId) => request.get(`/admin/knowledge/policy-docs/${encodeURIComponent(docId)}`),
  upsert: (data) => request.post('/admin/knowledge/policy-docs', data),
  softDelete: (docId) => request.delete(`/admin/knowledge/policy-docs/${encodeURIComponent(docId)}`),
}

export default policyDocApi
