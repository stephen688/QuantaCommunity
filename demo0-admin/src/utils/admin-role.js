/**
 * 管理端角色操作辅助。
 *
 * 角色代码必须与服务端白名单保持一致；该模块只负责前端展示和
 * 交互前置判断，最终授权仍由服务端 ROLE_MANAGE 强制校验。
 */
export const ADMIN_ROLE_OPTIONS = Object.freeze([
  Object.freeze({ code: 'CONTENT_AUDITOR', label: '内容审核员' }),
  Object.freeze({ code: 'OPERATIONS_ADMIN', label: '运营管理员' }),
  Object.freeze({ code: 'SUPER_ADMIN', label: '超级管理员' }),
])

/**
 * 判断目标用户是否就是当前登录用户。
 */
export function isCurrentUser(targetUserId, currentUserId) {
  if (targetUserId === undefined || targetUserId === null || currentUserId === undefined || currentUserId === null) {
    return false
  }
  return String(targetUserId) === String(currentUserId)
}

/**
 * 前端提示并阻止撤销当前账号唯一的超级管理员角色。
 * 服务端仍保留同一约束，避免前端判断被绕过。
 */
export function isProtectedSelfRevoke({ targetUserId, currentUserId, roleCode, roles = [] } = {}) {
  return isCurrentUser(targetUserId, currentUserId)
    && roleCode === 'SUPER_ADMIN'
    && roles.filter((role) => role === 'SUPER_ADMIN').length === 1
    && roles.length === 1
}

/**
 * 角色数据尚未成功读取或已有操作进行时，不允许点击角色变更按钮。
 */
export function isRoleMutationDisabled({
  hasRoleManage = false,
  rolesLoaded = false,
  busy = false,
  protectedSelfRevoke = false,
} = {}) {
  return !hasRoleManage || !rolesLoaded || busy || protectedSelfRevoke
}
