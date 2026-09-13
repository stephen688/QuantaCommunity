import request from './request'

/** 管理端可靠事件中心 — AdminEventController */
export const eventApi = {
  overview: (config = {}) => request.get('/admin/events/overview', config),
  outboxPage: (params) => request.get('/admin/events/outbox/page', { params }),
  outboxDetail: (eventId) => request.get(`/admin/events/outbox/${eventId}`),
  replayOutbox: (eventId) => request.post(`/admin/events/outbox/${eventId}/replay`),
  inboxPage: (params) => request.get('/admin/events/inbox/page', { params }),
  inboxDetail: (consumerName, eventId) =>
    request.get(`/admin/events/inbox/${encodeURIComponent(consumerName)}/${eventId}`),
  replayInbox: (consumerName, eventId) =>
    request.post(`/admin/events/inbox/${encodeURIComponent(consumerName)}/${eventId}/replay`),
}

export default eventApi
