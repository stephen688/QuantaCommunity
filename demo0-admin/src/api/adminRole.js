import request from './request'

export const adminRoleApi = {
  /**
   * 查询指定用户当前拥有的角色列表。
   * @param {number|string} userId
   */
  getUserRoles(userId) {
    return request.get(`/admin/roles/user/${userId}`)
  },

  /**
   * 授予角色。
   * @param {number|string} userId
   * @param {string} roleCode
   */
  grant(userId, roleCode) {
    return request.post(`/admin/roles/${userId}/${roleCode}`)
  },

  /**
   * 撤销角色。
   * @param {number|string} userId
   * @param {string} roleCode
   */
  revoke(userId, roleCode) {
    return request.delete(`/admin/roles/${userId}/${roleCode}`)
  },
}

export default adminRoleApi
