import request from './request'

/** 管理端回答 — AdminAnswerController */
export const answerApi = {
  page: (params) => request.get('/admin/answer/page', { params }),
  audit: (data) => request.post('/admin/answer/audit', data),
  remove: (answerId) => request.delete(`/admin/answer/${answerId}`),
}

export default answerApi
