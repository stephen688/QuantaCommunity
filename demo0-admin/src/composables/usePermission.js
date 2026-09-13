import { useUserStore } from '../stores/user'

/**
 * 基于后端安全上下文的细粒度权限判断。
 *
 * 不要在 template 中直接写 userStore.hasAuthority(code)，
 * 通过该 composable 统一封装，便于后续扩展为基于角色、 authority、
 * 或业务状态的混合判断。
 */
export function usePermission() {
  const userStore = useUserStore()

  /**
   * 是否拥有某个权限点。
   * @param {string} code
   */
  function hasAuthority(code) {
    return userStore.hasAuthority(code)
  }

  /**
   * 是否拥有任意一个权限点。
   * @param {string[]} codes
   */
  function hasAnyAuthority(codes = []) {
    return codes.some((code) => userStore.hasAuthority(code))
  }

  return {
    userStore,
    hasAuthority,
    hasAnyAuthority,
  }
}
