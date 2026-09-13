/** @typedef {'CONTENT' | 'ANSWER' | 'COMMENT'} ModerationTargetType */

/**
 * @param {ModerationTargetType} targetType
 * @param {number | string} targetId
 */
export function moderationKey(targetType, targetId) {
  return `${targetType}:${targetId}`
}

const DECISION_LABEL = {
  PASS: 'AI 建议通过',
  REJECT: 'AI 建议驳回',
  MANUAL: 'AI 建议人工复核',
  ERROR: 'AI 审核异常',
}

const DECISION_TAG = {
  PASS: 'success',
  REJECT: 'danger',
  MANUAL: 'warning',
  ERROR: 'info',
}

/**
 * @param {string | undefined | null} decision
 */
export function labelModerationDecision(decision) {
  if (!decision) return '—'
  return DECISION_LABEL[decision] ?? decision
}

/**
 * @param {string | undefined | null} decision
 */
export function moderationDecisionTagType(decision) {
  if (!decision) return 'info'
  return DECISION_TAG[decision] ?? 'info'
}

/**
 * @param {string | undefined | null} labels
 */
export function formatModerationLabels(labels) {
  if (!labels || labels === '[]') return '—'
  try {
    const parsed = JSON.parse(labels)
    if (Array.isArray(parsed) && parsed.length > 0) {
      return parsed.join('、')
    }
  } catch {
    /* plain text fallback */
  }
  const text = String(labels).trim()
  return text || '—'
}

/**
 * @param {Record<string, object>} map
 * @param {ModerationTargetType} targetType
 * @param {number | string | undefined | null} targetId
 */
export function pickModeration(map, targetType, targetId) {
  if (!map || targetId === undefined || targetId === null) return null
  return map[moderationKey(targetType, targetId)] ?? null
}

/**
 * @param {Array<{ targetType: ModerationTargetType, targetId: number | string }>} items
 */
export function buildModerationQueries(items) {
  return items
    .filter((item) => item?.targetId !== undefined && item?.targetId !== null && item?.targetId !== '')
    .map((item) => ({
      targetType: item.targetType,
      targetId: Number(item.targetId),
    }))
    .filter((item) => Number.isFinite(item.targetId) && item.targetId > 0)
}
