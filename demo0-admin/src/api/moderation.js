import request from './request'

/** 管理端 AI 审核记录 — AdminModerationController */
export const moderationApi = {
  latest: (params) => request.get('/admin/moderation/latest', { params }),
  batchLatest: (queries) => request.post('/admin/moderation/latest/batch', queries),
}

export default moderationApi
