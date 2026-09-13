import { getMockState } from './state'

function mockTimestamp() {
  const d = new Date()
  const pad = (n) => String(n).padStart(2, '0')
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}:${pad(d.getSeconds())}`
}

function pathnameOf(config) {
  const raw = config.url || ''
  if (raw.startsWith('http')) {
    try {
      return new URL(raw).pathname
    } catch {
      return raw.split('?')[0]
    }
  }
  const base = String(config.baseURL || '').replace(/\/$/, '')
  const path = raw.startsWith('/') ? raw : `/${raw}`
  const combined = base ? `${base}${path}` : path
  if (combined.startsWith('http')) {
    try {
      return new URL(combined).pathname
    } catch {
      /* fallthrough */
    }
  }
  return combined.split('?')[0]
}

function parseBody(data) {
  if (data == null || data === '') return {}
  if (typeof data === 'string') {
    try {
      return JSON.parse(data)
    } catch {
      return {}
    }
  }
  return data
}

function paginate(records, params) {
  const pageNum = Math.max(1, Number(params.pageNum) || 1)
  const pageSize = Math.min(100, Math.max(1, Number(params.pageSize) || 10))
  const start = (pageNum - 1) * pageSize
  return { total: records.length, records: records.slice(start, start + pageSize) }
}

function textPreview(text, max = 80) {
  if (text === undefined || text === null || text === '') return ''
  const t = String(text).replace(/\s+/g, ' ').trim()
  if (!t) return ''
  return t.length > max ? `${t.slice(0, max)}…` : t
}

function inTimeRange(rowTime, startTime, endTime) {
  if (!startTime && !endTime) return true
  if (!rowTime) return true
  const t = String(rowTime).replace(' ', 'T')
  const row = new Date(t).getTime()
  if (Number.isNaN(row)) return true
  if (startTime) {
    const s = new Date(String(startTime).replace(' ', 'T')).getTime()
    if (!Number.isNaN(s) && row < s) return false
  }
  if (endTime) {
    const e = new Date(String(endTime).replace(' ', 'T')).getTime()
    if (!Number.isNaN(e) && row > e) return false
  }
  return true
}

/**
 * @returns {{ handled: false } | { handled: true, data?: unknown }}
 * `data` 为业务层返回值（与真实后端 data 字段一致）；无 body 的接口可不传 data
 */
export function resolveMock(config) {
  const s = getMockState()
  const method = String(config.method || 'get').toLowerCase()
  const path = pathnameOf(config)
  const params = config.params || {}
  const body = parseBody(config.data)

  if (method === 'get' && path === '/admin/user/page') {
    let rows = [...s.users]
    const name = String(params.nickName ?? '').trim()
    if (name) rows = rows.filter((u) => String(u.nickName || '').includes(name))
    if (params.accountStatus !== undefined && params.accountStatus !== '' && params.accountStatus !== null) {
      const v = Number(params.accountStatus)
      rows = rows.filter((u) => u.accountStatus === v)
    }
    return { handled: true, data: paginate(rows, params) }
  }

  const userDetail = path.match(/^\/admin\/user\/(\d+)$/)
  if (method === 'get' && userDetail) {
    const id = Number(userDetail[1])
    const u = s.users.find((x) => x.id === id)
    return { handled: true, data: u ? { ...u } : null }
  }

  if (method === 'post' && /^\/admin\/user\/ban\/\d+$/.test(path)) {
    const id = Number(path.split('/').pop())
    const u = s.users.find((x) => x.id === id)
    if (u) u.accountStatus = 1
    return { handled: true }
  }

  if (method === 'post' && /^\/admin\/user\/unban\/\d+$/.test(path)) {
    const id = Number(path.split('/').pop())
    const u = s.users.find((x) => x.id === id)
    if (u) u.accountStatus = 0
    return { handled: true }
  }

  if (method === 'get' && path === '/admin/content/page') {
    let rows = [...s.contents]
    if (params.auditStatus !== undefined && params.auditStatus !== '' && params.auditStatus !== null) {
      const v = Number(params.auditStatus)
      rows = rows.filter((r) => r.auditStatus === v)
    }
    if (params.contentType !== undefined && params.contentType !== '' && params.contentType !== null) {
      const v = Number(params.contentType)
      rows = rows.filter((r) => r.contentType === v)
    }
    return { handled: true, data: paginate(rows, params) }
  }

  const contentDetailMatch = path.match(/^\/admin\/content\/(\d+)$/)
  if (method === 'get' && contentDetailMatch) {
    const id = Number(contentDetailMatch[1])
    const row = s.contents.find((c) => c.contentId === id)
    return { handled: true, data: row ? { ...row } : null }
  }

  if (method === 'post' && path === '/admin/content/audit') {
    const row = s.contents.find((c) => c.contentId === Number(body.contentId))
    if (row) {
      row.auditStatus = Number(body.auditResult)
      if (body.rejectReason) row.rejectReason = String(body.rejectReason)
      else if (row.auditStatus === 1) row.rejectReason = ''
    }
    return { handled: true }
  }

  if (method === 'delete' && /^\/admin\/content\/\d+$/.test(path)) {
    const id = Number(path.split('/').pop())
    const i = s.contents.findIndex((c) => c.contentId === id)
    if (i !== -1) s.contents.splice(i, 1)
    return { handled: true }
  }

  if (method === 'get' && path === '/admin/content/report/page') {
    let rows = [...s.postReports]
    if (params.status !== undefined && params.status !== '' && params.status !== null) {
      const v = Number(params.status)
      rows = rows.filter((r) => r.status === v)
    }
    rows = rows.map((r) => {
      const reporter = s.users.find((u) => u.id === r.reporterId)
      const target = s.contents.find((c) => c.contentId === r.contentId)
      return {
        ...r,
        reporterNickName: reporter?.nickName ?? '',
        targetTitle: target?.title ?? '',
        targetSummary: textPreview(target?.content, 120),
      }
    })
    return { handled: true, data: paginate(rows, params) }
  }

  if (method === 'post' && path === '/admin/content/report/handle') {
    const row = s.postReports.find((r) => r.id === Number(body.reportId))
    if (row) {
      const hr = Number(body.handleResult)
      // 与前端选项一致：4 = 驳回举报 → 状态 3，其余视为已处理 → 2
      row.status = hr === 4 ? 3 : 2
      if (body.handleRemark) row.handleRemark = String(body.handleRemark)
    }
    return { handled: true }
  }

  if (method === 'get' && path === '/admin/answer/page') {
    let rows = [...s.answers]
    if (params.contentId) {
      const cid = Number(params.contentId)
      if (!Number.isNaN(cid)) rows = rows.filter((r) => r.questionId === cid)
    }
    if (params.auditStatus !== undefined && params.auditStatus !== '' && params.auditStatus !== null) {
      const v = Number(params.auditStatus)
      rows = rows.filter((r) => r.auditStatus === v)
    }
    return { handled: true, data: paginate(rows, params) }
  }

  const answerDetailMatch = path.match(/^\/admin\/answer\/(\d+)$/)
  if (method === 'get' && answerDetailMatch) {
    const id = Number(answerDetailMatch[1])
    const row = s.answers.find((a) => a.answerId === id)
    if (!row) return { handled: true, data: null }
    const post = s.contents.find((c) => c.contentId === row.questionId)
    return {
      handled: true,
      data: {
        ...row,
        questionTitle: post?.title ?? '',
        questionContent: post?.content ?? '',
        questionContentType: post?.contentType,
        questionAuditStatus: post?.auditStatus,
      },
    }
  }

  if (method === 'post' && path === '/admin/answer/audit') {
    const row = s.answers.find((a) => a.answerId === Number(body.contentId))
    if (row) {
      row.auditStatus = Number(body.auditResult)
      if (row.auditStatus === 2 && body.rejectReason) row.rejectReason = String(body.rejectReason)
      else if (row.auditStatus === 1) row.rejectReason = ''
    }
    return { handled: true }
  }

  if (method === 'delete' && /^\/admin\/answer\/\d+$/.test(path)) {
    const id = Number(path.split('/').pop())
    const i = s.answers.findIndex((a) => a.answerId === id)
    if (i !== -1) s.answers.splice(i, 1)
    return { handled: true }
  }

  if (method === 'get' && path === '/admin/comment/page') {
    let rows = [...s.comments]
    if (params.contentId) {
      const cid = Number(params.contentId)
      if (!Number.isNaN(cid)) rows = rows.filter((r) => r.contentId === cid)
    }
    if (params.auditStatus !== undefined && params.auditStatus !== '' && params.auditStatus !== null) {
      const v = Number(params.auditStatus)
      rows = rows.filter((r) => r.auditStatus === v)
    }
    return { handled: true, data: paginate(rows, params) }
  }

  if (method === 'post' && path === '/admin/comment/audit') {
    const row = s.comments.find((c) => c.commentId === Number(body.commentId))
    if (row) {
      row.auditStatus = Number(body.auditResult)
      if (body.rejectReason) row.rejectReason = String(body.rejectReason)
      else if (row.auditStatus === 1) row.rejectReason = ''
    }
    return { handled: true }
  }

  if (method === 'get' && path === '/admin/moderation/latest') {
    const targetType = String(params.targetType || '').toUpperCase()
    const targetId = Number(params.targetId)
    const key = `${targetType}:${targetId}`
    const record = s.moderationRecords?.[key] ?? null
    return { handled: true, data: record ? { ...record } : null }
  }

  if (method === 'post' && path === '/admin/moderation/latest/batch') {
    const queries = Array.isArray(body) ? body : []
    const result = {}
    for (const q of queries) {
      if (!q?.targetType || q.targetId == null) continue
      const key = `${String(q.targetType).toUpperCase()}:${Number(q.targetId)}`
      const record = s.moderationRecords?.[key]
      if (record) result[key] = { ...record }
    }
    return { handled: true, data: result }
  }

  const commentDetailMatch = path.match(/^\/admin\/comment\/(\d+)$/)
  if (method === 'get' && commentDetailMatch) {
    const id = Number(commentDetailMatch[1])
    const row = s.comments.find((c) => c.commentId === id)
    if (!row) return { handled: true, data: null }
    const post = s.contents.find((c) => c.contentId === row.contentId)
    let relatedAnswer = null
    if (row.answerId) {
      const ans = s.answers.find((a) => a.answerId === row.answerId)
      if (ans) {
        relatedAnswer = {
          answerId: ans.answerId,
          userId: ans.userId,
          content: ans.content,
          auditStatus: ans.auditStatus,
          createTime: ans.createTime,
        }
      }
    }
    return {
      handled: true,
      data: {
        ...row,
        postTitle: post?.title ?? '',
        postContent: post?.content ?? '',
        postPublishUserId: post?.publishUserId,
        relatedAnswer,
      },
    }
  }

  if (method === 'delete' && /^\/admin\/comment\/\d+$/.test(path)) {
    const id = Number(path.split('/').pop())
    const i = s.comments.findIndex((c) => c.commentId === id)
    if (i !== -1) s.comments.splice(i, 1)
    return { handled: true }
  }

  if (method === 'get' && path === '/admin/comment/report/page') {
    let rows = [...s.commentReports]
    if (params.status !== undefined && params.status !== '' && params.status !== null) {
      const v = Number(params.status)
      rows = rows.filter((r) => r.status === v)
    }
    rows = rows.map((r) => {
      const reporter = s.users.find((u) => u.id === r.reporterId)
      const commentRow = s.comments.find((c) => c.commentId === r.commentId)
      const post = commentRow ? s.contents.find((c) => c.contentId === commentRow.contentId) : null
      return {
        ...r,
        reporterNickName: reporter?.nickName ?? '',
        targetPostTitle: post?.title ?? '',
        targetCommentPreview: textPreview(commentRow?.content ?? '', 120),
      }
    })
    return { handled: true, data: paginate(rows, params) }
  }

  if (method === 'post' && path === '/admin/comment/report/handle') {
    const row = s.commentReports.find((r) => r.id === Number(body.reportId))
    if (row) {
      const hr = Number(body.handleResult)
      row.status = hr === 4 ? 3 : 2
      if (body.handleRemark) row.handleRemark = String(body.handleRemark)
    }
    return { handled: true }
  }

  if (method === 'get' && path === '/admin/identityExam/page') {
    let rows = [...s.identities]
    if (params.auditStatus !== undefined && params.auditStatus !== '' && params.auditStatus !== null) {
      const v = Number(params.auditStatus)
      rows = rows.filter((r) => r.auditStatus === v)
    }
    rows = rows.filter((r) => inTimeRange(r.createTime, params.startTime, params.endTime))
    return { handled: true, data: paginate(rows, params) }
  }

  const idDetail = path.match(/^\/admin\/identityExam\/userAuth\/detail\/(\d+)$/)
  if (method === 'get' && idDetail) {
    const authId = Number(idDetail[1])
    const row = s.identities.find((x) => x.authId === authId)
    return { handled: true, data: row ? { ...row } : null }
  }

  if (method === 'post' && path === '/admin/identityExam/audit') {
    const row = s.identities.find((x) => x.authId === Number(body.authId))
    if (row) {
      const ar = Number(body.auditResult)
      row.auditStatus = ar
      row.rejectReason = ar === 2 && body.rejectReason ? String(body.rejectReason) : ''
      row.updateTime = mockTimestamp()
    }
    return { handled: true }
  }

  return { handled: false }
}
