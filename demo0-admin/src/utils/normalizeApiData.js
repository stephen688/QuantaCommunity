const DATE_TIME_KEYS = new Set([
  'createTime',
  'updateTime',
  'handleTime',
  'auditTime',
  'nextRetryTime',
  'lockedUntil',
  'lastReplayTime',
  'sentTime',
  'processedTime',
  'generatedAt',
])

/**
 * Jackson 有时将 LocalDateTime 序列化为 [y,m,d,h,mi,s] 数组，统一为展示用字符串。
 */
export function formatDateTimeValue(value) {
  if (value == null || value === '') return value
  if (typeof value === 'string') return value
  if (Array.isArray(value) && value.length >= 3) {
    const [y, m, d, h = 0, min = 0, s = 0, nano = 0] = value
    const pad = (n) => String(n).padStart(2, '0')
    const base = `${y}-${pad(m)}-${pad(d)} ${pad(h)}:${pad(min)}:${pad(s)}`
    if (nano) {
      const ms = String(Math.floor(Number(nano) / 1_000_000)).padStart(3, '0')
      return `${base}.${ms}`
    }
    return base
  }
  if (value instanceof Date && !Number.isNaN(value.getTime())) {
    return value.toLocaleString('zh-CN', { hour12: false })
  }
  return value
}

export function normalizeApiData(data) {
  if (data == null) return data
  if (Array.isArray(data)) {
    return data.map((item) => normalizeApiData(item))
  }
  if (typeof data !== 'object') return data

  const out = {}
  for (const [key, val] of Object.entries(data)) {
    if (DATE_TIME_KEYS.has(key)) {
      out[key] = formatDateTimeValue(val)
    } else {
      out[key] = normalizeApiData(val)
    }
  }
  return out
}
