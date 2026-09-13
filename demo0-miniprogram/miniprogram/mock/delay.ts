import { MOCK_DELAY_MS } from '../constants/request';

/** 模拟网络耗时，便于观察骨架屏与加载态 */
export function mockDelay(ms: number = MOCK_DELAY_MS): Promise<void> {
  return new Promise((resolve) => {
    setTimeout(resolve, ms);
  });
}
