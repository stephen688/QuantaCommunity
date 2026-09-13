import type {
  CommentAddDTO,
  CommentItemModel,
  CommentPageDTO,
  CommentPageVO,
  CommentReportPayload,
  CommentRowVO,
  ReplyPageDTO,
} from '../types/comment';
import type { LikeResultVO } from '../types/interaction';
import { USE_MOCK } from '../constants/request';
import { mockDelay } from '../mock/delay';
import { request, type RequestResult } from '../utils/request';
import { withDefaultAvatar } from '../utils/image';

/** 线程键：`{contentId}:q` 问题评论；`{contentId}:a:{answerId}` 回答评论（与后端 answerId 语义一致） */
export function commentMockThreadKey(contentId: number, answerId?: number | null): string {
  const a = answerId === undefined || answerId === null ? NaN : Number(answerId);
  if (Number.isFinite(a) && a > 0) {
    return `${contentId}:a:${a}`;
  }
  return `${contentId}:q`;
}

function mockRepliesStorageKey(threadKey: string, parentCommentId: number): string {
  return `${threadKey}::${parentCommentId}`;
}

function parentCommentIdSeed(contentId: number, answerId?: number | null): number {
  const a = answerId === undefined || answerId === null ? NaN : Number(answerId);
  if (Number.isFinite(a) && a > 0) {
    return a * 1000 + 1;
  }
  return contentId * 10 + 1;
}

const mockCommentsByThread = new Map<string, CommentRowVO[]>();
/** 一级评论 id + 线程 -> 完整回复池（Mock 分页 / 展开更多） */
const mockFullRepliesByParentKey = new Map<string, CommentRowVO[]>();
let mockCommentSeq = 9100000;

function buildMockRepliesForParent(
  parentCommentId: number,
  parentUserId: number,
  parentNick: string,
): CommentRowVO[] {
  const base = parentCommentId * 100;
  return [
    {
      commentId: base + 1,
      userId: 601,
      nickName: 'Mock小明',
      parentId: parentCommentId,
      replyUserId: parentUserId,
      replyNickName: parentNick,
      content: '同意，这条思路很清楚。',
      createTime: '2026-05-10 10:05:00',
      likeCount: 1,
      isLiked: false,
    } as CommentRowVO,
    {
      commentId: base + 2,
      userId: 602,
      nickName: 'Mock小红',
      parentId: parentCommentId,
      replyUserId: 601,
      replyNickName: 'Mock小明',
      content: '可以再补充一点边界情况吗？',
      createTime: '2026-05-10 11:20:00',
      likeCount: 0,
      isLiked: false,
    } as CommentRowVO,
    {
      commentId: base + 3,
      userId: 603,
      nickName: 'Mock小刚',
      parentId: parentCommentId,
      replyUserId: 602,
      replyNickName: 'Mock小红',
      content: '我按这个试过了，可行。',
      createTime: '2026-05-10 14:00:00',
      likeCount: 3,
      isLiked: false,
    } as CommentRowVO,
    {
      commentId: base + 4,
      userId: 604,
      nickName: 'Mock小李',
      parentId: parentCommentId,
      replyUserId: 603,
      replyNickName: 'Mock小刚',
      content: '+1，收藏了。',
      createTime: '2026-05-11 09:12:00',
      likeCount: 0,
      isLiked: false,
    } as CommentRowVO,
    {
      commentId: base + 5,
      userId: 605,
      nickName: 'Mock小王',
      parentId: parentCommentId,
      replyUserId: 601,
      replyNickName: 'Mock小明',
      content: '感谢分享，后面我也跟一下。',
      createTime: '2026-05-11 16:40:00',
      likeCount: 0,
      isLiked: false,
    } as CommentRowVO,
  ];
}

function seedDefaultMockComments(contentId: number, answerId?: number | null): void {
  const threadKey = commentMockThreadKey(contentId, answerId);
  if (mockCommentsByThread.has(threadKey)) {
    return;
  }
  const parentCommentId = parentCommentIdSeed(contentId, answerId);
  const onAnswer =
    answerId !== undefined && answerId !== null && Number.isFinite(Number(answerId)) && Number(answerId) > 0;
  const parentNick = onAnswer ? 'Mock答友' : 'Mock课代表';
  const parentUserId = onAnswer ? 801 : 501;
  const fullReplies = buildMockRepliesForParent(parentCommentId, parentUserId, parentNick);
  mockFullRepliesByParentKey.set(mockRepliesStorageKey(threadKey, parentCommentId), fullReplies);
  const intro = onAnswer
    ? `【回答 #${answerId}】挂在本回答下的 Mock 讨论（可折叠回复），与问题评论区数据隔离。`
    : '这是一条本地 Mock 评论（带多条可折叠回复）。将 USE_MOCK 设为 false 并配置后端后即可走真实数据。';
  const secondId = parentCommentId + 1;
  mockCommentsByThread.set(threadKey, [
    {
      commentId: parentCommentId,
      userId: onAnswer ? 801 : 501,
      nickName: onAnswer ? 'Mock答友' : 'Mock课代表',
      content: intro,
      createTime: '2026-05-10 09:30:00',
      likeCount: 2,
      isLiked: false,
      replyCount: fullReplies.length,
      replyList: fullReplies.slice(0, 2),
      answerId: onAnswer ? Number(answerId) : undefined,
    } as CommentRowVO,
    {
      commentId: secondId,
      userId: 502,
      nickName: 'Mock路人甲',
      content: onAnswer
        ? '另一条仅出现在该回答下的讨论，不会出现在问题评论列表。'
        : '另一条一级评论，没有嵌套回复，方便对比样式。',
      createTime: '2026-05-12 08:00:00',
      likeCount: 0,
      isLiked: false,
      answerId: onAnswer ? Number(answerId) : undefined,
    } as CommentRowVO,
  ]);
}

function toInt(v: unknown): number | undefined {
  if (v === undefined || v === null) {
    return undefined;
  }
  const n = typeof v === 'number' ? v : Number(v);
  return Number.isFinite(n) ? Math.trunc(n) : undefined;
}

function toBool(v: unknown): boolean {
  return v === true;
}

export function mapCommentRowToItem(
  row: CommentRowVO,
  topLevelParentIdHint?: number,
): CommentItemModel | null {
  const commentId = toInt(row.commentId);
  const userId = toInt(row.userId);
  if (commentId === undefined || userId === undefined) {
    return null;
  }
  const nick = (row.nickName !== undefined && row.nickName !== null && String(row.nickName).trim())
    ? String(row.nickName).trim()
    : '匿名用户';
  const replyUserIdRaw = toInt(row.replyUserId);
  const replyCommentIdRaw = toInt(row.replyCommentId);
  const replyNickRaw =
    row.replyNickName !== undefined && row.replyNickName !== null
      ? String(row.replyNickName).trim()
      : '';
  const repliesRaw = row.replyList;
  let replies: CommentItemModel[] | undefined;
  if (Array.isArray(repliesRaw)) {
    replies = [];
    for (const r of repliesRaw) {
      const m = mapCommentRowToItem(r as CommentRowVO, commentId);
      if (m) {
        replies.push(m);
      }
    }
    if (replies.length === 0) {
      replies = undefined;
    }
  }
  const parentIdRaw = toInt(row.parentId);
  const parentIdResolved =
    parentIdRaw !== undefined && parentIdRaw > 0
      ? parentIdRaw
      : topLevelParentIdHint !== undefined && topLevelParentIdHint > 0
        ? topLevelParentIdHint
        : undefined;
  return {
    commentId,
    userId,
    nickName: nick,
    avatarUrl: withDefaultAvatar(
      row.avatarUrl === undefined || row.avatarUrl === null ? undefined : String(row.avatarUrl),
    ),
    content: row.content !== undefined && row.content !== null ? String(row.content) : '',
    createTime:
      row.createTime === undefined || row.createTime === null ? undefined : String(row.createTime),
    likeCount: toInt(row.likeCount) ?? 0,
    liked: toBool(row.isLiked),
    replyCount: toInt(row.replyCount),
    parentId: parentIdResolved,
    answerId: toInt(row.answerId),
    replyCommentId: replyCommentIdRaw !== undefined && replyCommentIdRaw > 0 ? replyCommentIdRaw : undefined,
    replyUserId: replyUserIdRaw !== undefined && replyUserIdRaw > 0 ? replyUserIdRaw : undefined,
    replyNickName: replyNickRaw || undefined,
    replyAvatarUrl:
      row.replyAvatarUrl === undefined || row.replyAvatarUrl === null
        ? undefined
        : withDefaultAvatar(String(row.replyAvatarUrl)),
    replies,
  };
}

export function mapCommentPageToItems(page: CommentPageVO): CommentItemModel[] {
  const raw = page.list ?? [];
  const out: CommentItemModel[] = [];
  for (const row of raw) {
    const item = mapCommentRowToItem(row);
    if (item) {
      out.push(item);
    }
  }
  return out;
}

export function getCommentList(params: CommentPageDTO): Promise<RequestResult<CommentPageVO>> {
  if (!Number.isFinite(params.contentId) || params.contentId <= 0) {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '内容不存在或参数无效',
    });
  }
  if (USE_MOCK) {
    return (async () => {
      await mockDelay();
      seedDefaultMockComments(params.contentId, params.answerId);
      const threadKey = commentMockThreadKey(params.contentId, params.answerId);
      const all = mockCommentsByThread.get(threadKey) ?? [];
      const pageNum = params.pageNum < 1 ? 1 : params.pageNum;
      const pageSize = params.pageSize < 1 ? 10 : params.pageSize;
      const start = (pageNum - 1) * pageSize;
      const slice = all.slice(start, start + pageSize);
      return {
        ok: true,
        data: {
          pageNum,
          pageSize,
          total: all.length,
          hasMore: start + slice.length < all.length,
          list: slice,
        },
      };
    })();
  }
  const data: Record<string, string | number> = {
    contentId: params.contentId,
    pageNum: params.pageNum < 1 ? 1 : params.pageNum,
    pageSize: params.pageSize < 1 ? 10 : params.pageSize,
  };
  if (params.answerId !== undefined && params.answerId !== null) {
    data.answerId = params.answerId;
  }
  if (params.sortType !== undefined) {
    data.sortType = params.sortType;
  }
  return request<CommentPageVO>({
    method: 'GET',
    url: '/comment/list',
    data: data as Record<string, unknown>,
  });
}

export function getReplyList(params: ReplyPageDTO): Promise<RequestResult<CommentPageVO>> {
  if (!Number.isFinite(params.parentCommentId) || params.parentCommentId <= 0) {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '评论不存在或参数无效',
    });
  }
  if (!Number.isFinite(params.contentId) || params.contentId <= 0) {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '内容不存在或参数无效',
    });
  }
  const data: Record<string, string | number> = {
    parentCommentId: params.parentCommentId,
    contentId: params.contentId,
    pageNum: params.pageNum < 1 ? 1 : params.pageNum,
    pageSize: params.pageSize < 1 ? 10 : params.pageSize,
  };
  if (params.answerId !== undefined && params.answerId !== null) {
    data.answerId = params.answerId;
  }
  if (params.sortType !== undefined) {
    data.sortType = params.sortType;
  }
  if (USE_MOCK) {
    return (async () => {
      await mockDelay();
      seedDefaultMockComments(params.contentId, params.answerId);
      const threadKey = commentMockThreadKey(params.contentId, params.answerId);
      const full =
        mockFullRepliesByParentKey.get(
          mockRepliesStorageKey(threadKey, params.parentCommentId),
        ) ?? [];
      const pageNum = params.pageNum < 1 ? 1 : params.pageNum;
      const pageSize = params.pageSize < 1 ? 10 : params.pageSize;
      const start = (pageNum - 1) * pageSize;
      const slice = full.slice(start, start + pageSize);
      return {
        ok: true,
        data: {
          pageNum,
          pageSize,
          total: full.length,
          hasMore: start + slice.length < full.length,
          list: slice,
        },
      };
    })();
  }
  return request<CommentPageVO>({
    method: 'GET',
    url: '/comment/replyList',
    data: data as Record<string, unknown>,
  });
}

export function sendComment(payload: CommentAddDTO): Promise<RequestResult<number>> {
  if (!Number.isFinite(payload.contentId) || payload.contentId <= 0) {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '内容不存在或参数无效',
    });
  }
  if (!payload.content || !payload.content.trim()) {
    return Promise.resolve({ ok: false, errorType: 'invalidData', message: '评论内容不能为空' });
  }
  if (USE_MOCK) {
    return (async () => {
      await mockDelay();
      seedDefaultMockComments(payload.contentId, payload.answerId);
      const threadKey = commentMockThreadKey(payload.contentId, payload.answerId);
      const list = [...(mockCommentsByThread.get(threadKey) ?? [])];
      const parentId = toInt(payload.parentId);
      mockCommentSeq += 1;
      const id = mockCommentSeq;
      const row: CommentRowVO = {
        commentId: id,
        userId: 999,
        nickName: '我',
        content: payload.content.trim(),
        createTime: new Date().toISOString().slice(0, 19).replace('T', ' '),
        likeCount: 0,
        isLiked: false,
        parentId: parentId !== undefined ? parentId : undefined,
        answerId:
          payload.answerId !== undefined && payload.answerId !== null
            ? Number(payload.answerId)
            : undefined,
      };
      if (parentId !== undefined && parentId > 0) {
        const pIdx = list.findIndex((r) => Number(r.commentId) === parentId);
        if (pIdx < 0) {
          return { ok: false, errorType: 'invalidData', message: '父评论不存在' };
        }
        const parent = { ...list[pIdx] } as CommentRowVO;
        const rk = mockRepliesStorageKey(threadKey, parentId);
        const full = [...(mockFullRepliesByParentKey.get(rk) ?? [])];
        const replyToId = toInt(payload.replyCommentId);
        if (replyToId !== undefined && replyToId > 0) {
          const target = findMockCommentRowById(replyToId);
          const nick =
            target &&
            target.nickName !== undefined &&
            target.nickName !== null &&
            String(target.nickName).trim()
              ? String(target.nickName).trim()
              : '';
          if (nick) {
            row.replyNickName = nick;
          }
          const tuid = target ? toInt(target.userId) : undefined;
          if (tuid !== undefined && tuid > 0) {
            row.replyUserId = tuid;
          }
          row.replyCommentId = replyToId;
        }
        row.parentId = parentId;
        full.push(row);
        mockFullRepliesByParentKey.set(rk, full);
        const prevReplies = (parent.replyList as CommentRowVO[]) ?? [];
        parent.replyList = [...prevReplies, row];
        parent.replyCount = (Number(parent.replyCount) || 0) + 1;
        list[pIdx] = parent;
        mockCommentsByThread.set(threadKey, list);
        return { ok: true, data: id };
      }
      list.push(row);
      mockCommentsByThread.set(threadKey, list);
      return { ok: true, data: id };
    })();
  }
  const body: Record<string, unknown> = {
    contentId: payload.contentId,
    content: payload.content.trim(),
  };
  if (payload.answerId !== undefined && payload.answerId !== null) {
    body.answerId = payload.answerId;
  }
  if (payload.parentId !== undefined && payload.parentId !== null) {
    body.parentId = payload.parentId;
  }
  if (payload.replyCommentId !== undefined && payload.replyCommentId !== null) {
    body.replyCommentId = payload.replyCommentId;
  }
  if (payload.replyUserId !== undefined && payload.replyUserId !== null) {
    body.replyUserId = payload.replyUserId;
  }
  if (payload.imageUrls && payload.imageUrls.length > 0) {
    body.imageUrls = payload.imageUrls;
  }
  return request<number>({
    method: 'POST',
    url: '/comment/send',
    data: body,
  });
}

export function likeComment(commentId: number, liked: boolean): Promise<RequestResult<LikeResultVO>> {
  if (!Number.isFinite(commentId) || commentId <= 0) {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '评论不存在或参数无效',
    });
  }
  if (typeof liked !== 'boolean') {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '点赞状态无效',
    });
  }
  if (USE_MOCK) {
    return (async () => {
      await mockDelay();
      const row = findMockCommentRowById(commentId);
      if (!row) {
        return { ok: false, errorType: 'invalidData', message: '评论不存在' };
      }
      const lc = Number(row.likeCount) || 0;
      const nextCount = row.isLiked === liked
        ? lc
        : liked
          ? lc + 1
          : Math.max(0, lc - 1);
      row.isLiked = liked;
      row.likeCount = nextCount;
      return { ok: true, data: { isLiked: liked, likedCount: nextCount } };
    })();
  }
  return request<LikeResultVO>({
    method: 'POST',
    url: `/comment/like/${commentId}`,
    data: { liked },
  });
}

function findMockCommentRowById(commentId: number): CommentRowVO | undefined {
  for (const rows of mockCommentsByThread.values()) {
    for (const r of rows) {
      if (Number(r.commentId) === commentId) {
        return r;
      }
      const nested = r.replyList;
      if (Array.isArray(nested)) {
        for (const rr of nested) {
          if (Number((rr as CommentRowVO).commentId) === commentId) {
            return rr as CommentRowVO;
          }
        }
      }
    }
  }
  for (const full of mockFullRepliesByParentKey.values()) {
    const hit = full.find((x) => Number(x.commentId) === commentId);
    if (hit) {
      return hit;
    }
  }
  return undefined;
}

function removeMockCommentById(commentId: number): boolean {
  for (const [threadKey, rows] of mockCommentsByThread) {
    const idx = rows.findIndex((r) => Number(r.commentId) === commentId);
    if (idx >= 0) {
      const next = rows.slice(0, idx).concat(rows.slice(idx + 1));
      mockCommentsByThread.set(threadKey, next);
      return true;
    }
    let changed = false;
    const nextRows = rows.map((parent) => {
      const rl = parent.replyList as CommentRowVO[] | undefined;
      if (!Array.isArray(rl)) {
        return parent;
      }
      const fi = rl.findIndex((x) => Number(x.commentId) === commentId);
      if (fi < 0) {
        return parent;
      }
      changed = true;
      const nr = rl.slice(0, fi).concat(rl.slice(fi + 1));
      const pid = Number(parent.commentId);
      const rk = mockRepliesStorageKey(threadKey, pid);
      const full = mockFullRepliesByParentKey.get(rk);
      if (full) {
        mockFullRepliesByParentKey.set(
          rk,
          full.filter((x) => Number(x.commentId) !== commentId),
        );
      }
      return {
        ...parent,
        replyList: nr,
        replyCount: Math.max(0, (Number(parent.replyCount) || 0) - 1),
      };
    });
    if (changed) {
      mockCommentsByThread.set(threadKey, nextRows);
      return true;
    }
  }
  for (const [rk, full] of mockFullRepliesByParentKey) {
    const fi = full.findIndex((x) => Number(x.commentId) === commentId);
    if (fi < 0) {
      continue;
    }
    const sep = rk.indexOf('::');
    if (sep <= 0) {
      continue;
    }
    const threadKey = rk.slice(0, sep);
    const parentId = Number(rk.slice(sep + 2));
    const nextFull = full.slice(0, fi).concat(full.slice(fi + 1));
    mockFullRepliesByParentKey.set(rk, nextFull);
    const top = mockCommentsByThread.get(threadKey);
    if (top) {
      const pIdx = top.findIndex((r) => Number(r.commentId) === parentId);
      if (pIdx >= 0) {
        const parent = { ...top[pIdx] } as CommentRowVO;
        const rl = (parent.replyList as CommentRowVO[]) ?? [];
        parent.replyList = rl.filter((x) => Number(x.commentId) !== commentId);
        parent.replyCount = Math.max(0, (Number(parent.replyCount) || 0) - 1);
        const copy = top.slice();
        copy[pIdx] = parent;
        mockCommentsByThread.set(threadKey, copy);
      }
    }
    return true;
  }
  return false;
}

/** 删除评论 */
export function deleteComment(commentId: number): Promise<RequestResult<void>> {
  if (!Number.isFinite(commentId) || commentId <= 0) {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '评论不存在或参数无效',
    });
  }
  if (USE_MOCK) {
    return (async () => {
      await mockDelay();
      if (!removeMockCommentById(commentId)) {
        return { ok: false, errorType: 'invalidData', message: '评论不存在' };
      }
      return { ok: true, data: undefined as void };
    })();
  }
  return request<void>({
    method: 'DELETE',
    url: `/comment/delete/${commentId}`,
  });
}

/** 举报评论 */
export function reportComment(payload: CommentReportPayload): Promise<RequestResult<void>> {
  if (!Number.isFinite(payload.commentId) || payload.commentId <= 0) {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '评论不存在或参数无效',
    });
  }
  const rt = Number(payload.reportType);
  if (!Number.isFinite(rt) || rt < 1 || rt > 5) {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '举报类型无效',
    });
  }
  if (USE_MOCK) {
    return (async () => {
      await mockDelay();
      return { ok: true, data: undefined as void };
    })();
  }
  return request<void>({
    method: 'POST',
    url: '/comment/report',
    data: {
      commentId: payload.commentId,
      reportType: rt,
    },
  });
}
