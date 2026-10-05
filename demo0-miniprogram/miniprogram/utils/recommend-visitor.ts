import { createSubmissionToken } from './submission';
import { getLoginUserId, getStorageSafe, getToken, setStorageSafe } from './storage';

const RECOMMEND_GUEST_ID_KEY = 'RECOMMEND_GUEST_ID';

let memoryGuestId: string | undefined;
let guestIdPromise: Promise<string> | undefined;

export interface RecommendVisitor {
  guestId: string;
  actorKey: string;
}

function isUuid(value: unknown): value is string {
  return (
    typeof value === 'string' &&
    /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/i.test(value)
  );
}

/**
 * 生成或读取本地游客标识。
 * 标识只用于推荐会话与曝光去重；本地存储异常时保留本次运行内存值。
 */
export async function getRecommendGuestId(): Promise<string> {
  const stored = getStorageSafe<unknown>(RECOMMEND_GUEST_ID_KEY);
  if (isUuid(stored)) {
    memoryGuestId = stored;
    return stored;
  }
  if (memoryGuestId) {
    return memoryGuestId;
  }
  if (guestIdPromise) {
    return guestIdPromise;
  }

  guestIdPromise = createSubmissionToken()
    .then((guestId) => {
      memoryGuestId = guestId;
      setStorageSafe(RECOMMEND_GUEST_ID_KEY, guestId);
      return guestId;
    })
    .finally(() => {
      guestIdPromise = undefined;
    });
  return guestIdPromise;
}

/** 当前推荐队列的本地身份键，不作为服务端身份提交。 */
export function getRecommendActorKey(guestId: string): string {
  const token = getToken();
  const userId = token ? getLoginUserId() : undefined;
  return userId ? `u:${userId}` : `g:${guestId}`;
}

/** 读取推荐请求所需的游客头和本地队列身份。 */
export async function getRecommendVisitor(): Promise<RecommendVisitor> {
  const guestId = await getRecommendGuestId();
  return { guestId, actorKey: getRecommendActorKey(guestId) };
}
