/**
 * 管理端访问判定工具：把后端安全上下文转换为路由、菜单和操作的统一门槛。
 * 这里只做前端可见性判断；所有真实接口仍由服务端权限强制校验。
 */

export const MANAGEMENT_ROLES = Object.freeze([
  'SUPER_ADMIN',
  'CONTENT_AUDITOR',
  'OPERATIONS_ADMIN',
])

function asList(value) {
  if (Array.isArray(value)) {
    return value.filter((item) => typeof item === 'string' && item.trim())
  }
  if (typeof value === 'string' && value.trim()) {
    return [value]
  }
  return []
}

/**
 * 判断安全上下文是否包含至少一个受支持的管理角色。
 * @param {{ roles?: string[] }} context
 */
export function hasManagementRole(context = {}) {
  const roles = asList(context?.roles)
  return MANAGEMENT_ROLES.some((role) => roles.includes(role))
}

/**
 * 判断安全上下文是否拥有任一给定权限。
 * @param {{ authorities?: string[] }} context
 * @param {string|string[]} required
 */
export function canUseAdminAuthority(context = {}, required) {
  const authorities = new Set(asList(context?.authorities))
  const requiredAuthorities = asList(required)
  return requiredAuthorities.length > 0
    && requiredAuthorities.some((authority) => authorities.has(authority))
}

/**
 * 判断路由或菜单元数据是否可访问。roles 与 authority 同时提供时两者都必须满足，
 * 同一字段内采用任一匹配，方便一个入口同时支持多个后端角色/权限。
 * @param {{ roles?: string[], authorities?: string[] }} context
 * @param {{ roles?: string|string[], authority?: string|string[], authorities?: string|string[] }} meta
 */
export function canAccessAdminPage(context = {}, meta = {}) {
  const roles = asList(context?.roles)
  const requiredRoles = asList(meta?.roles)
  if (requiredRoles.length > 0 && !requiredRoles.some((role) => roles.includes(role))) {
    return false
  }

  const requiredAuthorities = meta?.authority ?? meta?.authorities
  if (requiredAuthorities !== undefined && !canUseAdminAuthority(context, requiredAuthorities)) {
    return false
  }

  return true
}
