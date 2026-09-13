/** 内存 mock 状态：从静态种子深拷贝，支持审核 / 删除 / 处理举报 等变更 */
import { STATIC_SEED } from './staticData'

export function createSeedState() {
  return JSON.parse(JSON.stringify(STATIC_SEED))
}

let state = createSeedState()

export function getMockState() {
  return state
}

export function resetMockState() {
  state = createSeedState()
}
