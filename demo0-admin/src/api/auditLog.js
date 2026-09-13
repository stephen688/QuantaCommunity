import request from './request'

export const auditLogApi = {
  page(params) {
    return request.get('/admin/audit-logs/page', { params })
  },
  detail(id) {
    return request.get(`/admin/audit-logs/${id}`)
  },
}

export default auditLogApi
