import { CONTENT_TYPE_PROFESSIONAL } from '../constants/content';
import { USE_MOCK } from '../constants/request';
import type { AnswerModel, AnswerPublishPayload, AnswerVO } from '../types/detail';
import type { LikeResultVO } from '../types/interaction';
import { mockDelay } from '../mock/delay';
import { findMockContentById } from '../mock/recommend-pool';
import { request, type RequestResult } from '../utils/request';
import { withDefaultAvatar } from '../utils/image';

export function mapAnswerVOToModel(vo: AnswerVO): AnswerModel | null {
  const aid = vo.answerId === undefined || vo.answerId === null ? NaN : Number(vo.answerId);
  const qid = vo.questionId === undefined || vo.questionId === null ? NaN : Number(vo.questionId);
  if (!Number.isFinite(aid) || !Number.isFinite(qid)) {
    return null;
  }
  const name = (vo.nickName && String(vo.nickName).trim()) || '匿名用户';
  const uidRaw = vo.userId === undefined || vo.userId === null ? NaN : Number(vo.userId);
  const authorId = Number.isFinite(uidRaw) && uidRaw > 0 ? uidRaw : undefined;
  return {
    answerId: aid,
    questionId: qid,
    authorId,
    authorName: name,
    authorAvatar: withDefaultAvatar(vo.avatarUrl),
    quantaBatch: vo.quantaBatch ?? undefined,
    content: vo.content ?? '',
    likeCount: vo.likeCount ?? 0,
    commentCount: vo.commentCount ?? 0,
    isAccepted: vo.isAccepted === 1,
    liked: vo.isLiked === true,
    createTime:
      vo.createTime === undefined || vo.createTime === null ? undefined : String(vo.createTime),
  };
}

const mockPublishedAnswers = new Map<number, AnswerVO[]>();
let mockAnswerSeq = 8800000;

const mockDeletedAnswerIds = new Set<number>();
const mockAcceptedByQuestion = new Map<number, number>();
const mockAnswerLikeMeta = new Map<number, { isLiked: boolean; likeCount: number }>();

function isMockQuestion(questionId: number): boolean {
  const hit = findMockContentById(questionId);
  if (hit?.contentType === CONTENT_TYPE_PROFESSIONAL) {
    return true;
  }
  return questionId >= 2000 && questionId < 3000;
}

function baseMockAnswers(questionId: number): AnswerVO[] {
  if (!isMockQuestion(questionId)) {
    return [];
  }
  return [
    {
      answerId: questionId * 100 + 1,
      questionId,
      userId: 91001,
      nickName: 'Mock答主甲',
      avatarUrl: '',
      content: '这是 Mock 回答示例 A：先说明思路，再给出可执行步骤。',
      likeCount: 8,
      commentCount: 2,
      isAccepted: 1,
      createTime: '2026-05-11 10:20:00',
    },
    {
      answerId: questionId * 100 + 2,
      questionId,
      userId: 91002,
      nickName: 'Mock答主乙',
      avatarUrl: '',
      content: 'Mock 回答示例 B：补充常见坑与排查顺序。',
      likeCount: 3,
      commentCount: 0,
      isAccepted: 0,
      createTime: '2026-05-12 16:05:00',
    },
  ];
}

function seedAnswerLikeMeta(row: AnswerVO): void {
  const id = row.answerId === undefined || row.answerId === null ? NaN : Number(row.answerId);
  if (!Number.isFinite(id)) {
    return;
  }
  if (!mockAnswerLikeMeta.has(id)) {
    mockAnswerLikeMeta.set(id, {
      isLiked: false,
      likeCount: Number(row.likeCount) || 0,
    });
  }
}

function findAnswerRowInMock(answerId: number): AnswerVO | undefined {
  for (const [, rows] of mockPublishedAnswers) {
    const hit = rows.find((r) => Number(r.answerId) === answerId);
    if (hit) {
      return hit;
    }
  }
  for (let qid = 2000; qid < 3000; qid += 1) {
    if (!isMockQuestion(qid)) {
      continue;
    }
    const hit = baseMockAnswers(qid).find((r) => Number(r.answerId) === answerId);
    if (hit) {
      return hit;
    }
  }
  return undefined;
}

function resolveQuestionIdForAnswer(answerId: number): number | undefined {
  for (const [qid, rows] of mockPublishedAnswers) {
    if (rows.some((r) => Number(r.answerId) === answerId)) {
      return qid;
    }
  }
  for (let qid = 2000; qid < 3000; qid += 1) {
    if (!isMockQuestion(qid)) {
      continue;
    }
    if (baseMockAnswers(qid).some((r) => Number(r.answerId) === answerId)) {
      return qid;
    }
  }
  return undefined;
}

function applyAnswerMockFields(row: AnswerVO): AnswerVO {
  const id = Number(row.answerId);
  const qid = Number(row.questionId);
  seedAnswerLikeMeta(row);
  const meta = mockAnswerLikeMeta.get(id)!;
  const acceptedId = mockAcceptedByQuestion.get(qid);
  let isAccepted = row.isAccepted;
  if (acceptedId !== undefined) {
    isAccepted = acceptedId === id ? 1 : 0;
  }
  return {
    ...row,
    likeCount: meta.likeCount,
    isLiked: meta.isLiked,
    isAccepted,
  };
}

function listMockAnswers(questionId: number): AnswerVO[] {
  const extra = mockPublishedAnswers.get(questionId) ?? [];
  const merged = [...baseMockAnswers(questionId), ...extra];
  return merged
    .filter((r) => !mockDeletedAnswerIds.has(Number(r.answerId)))
    .map(applyAnswerMockFields);
}

export function getAnswerList(questionId: number): Promise<RequestResult<AnswerVO[]>> {
  if (!Number.isFinite(questionId) || questionId <= 0) {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '问题不存在或参数无效',
    });
  }
  if (USE_MOCK) {
    return (async () => {
      await mockDelay();
      return { ok: true, data: listMockAnswers(questionId) };
    })();
  }
  return request<AnswerVO[]>({
    method: 'GET',
    url: `/answer/list/${questionId}`,
  });
}

export function getAnswerDetail(answerId: number): Promise<RequestResult<AnswerVO>> {
  if (!Number.isFinite(answerId) || answerId <= 0) {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '回答不存在或参数无效',
    });
  }
  if (USE_MOCK) {
    return (async () => {
      await mockDelay();
      const row = findAnswerRowInMock(answerId);
      if (!row) {
        return { ok: false, errorType: 'invalidData', message: '回答不存在' };
      }
      return { ok: true, data: applyAnswerMockFields(row) };
    })();
  }
  return request<AnswerVO>({
    method: 'GET',
    url: `/answer/${answerId}`,
  });
}

export function publishAnswer(
  payload: AnswerPublishPayload,
): Promise<RequestResult<AnswerVO>> {
  if (!Number.isFinite(payload.questionId) || payload.questionId <= 0) {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '问题不存在或参数无效',
    });
  }
  if (!payload.content || !payload.content.trim()) {
    return Promise.resolve({ ok: false, errorType: 'invalidData', message: '回答内容不能为空' });
  }
  if (USE_MOCK) {
    return (async () => {
      await mockDelay();
      const list = mockPublishedAnswers.get(payload.questionId) ?? [];
      mockAnswerSeq += 1;
      const row: AnswerVO = {
        answerId: mockAnswerSeq,
        questionId: payload.questionId,
        userId: 98009,
        nickName: '我',
        avatarUrl: '',
        content: payload.content.trim(),
        likeCount: 0,
        commentCount: 0,
        isAccepted: 0,
        createTime: new Date().toISOString().slice(0, 19).replace('T', ' '),
      };
      mockPublishedAnswers.set(payload.questionId, list.concat(row));
      mockAnswerLikeMeta.set(mockAnswerSeq, { isLiked: false, likeCount: 0 });
      return { ok: true, data: applyAnswerMockFields(row) };
    })();
  }
  return request<AnswerVO>({
    method: 'POST',
    url: '/answer/publish',
    data: {
      questionId: payload.questionId,
      content: payload.content.trim(),
    },
  });
}

/** 设置回答点赞状态 */
export function likeAnswer(answerId: number, liked: boolean): Promise<RequestResult<LikeResultVO>> {
  if (!Number.isFinite(answerId) || answerId <= 0) {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '回答不存在或参数无效',
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
      const row = findAnswerRowInMock(answerId);
      if (!row) {
        return { ok: false, errorType: 'invalidData', message: '回答不存在' };
      }
      seedAnswerLikeMeta(row);
      const meta = mockAnswerLikeMeta.get(answerId)!;
      const nextLiked = liked;
      const adjustedCount = nextLiked === meta.isLiked
        ? meta.likeCount
        : nextLiked
          ? meta.likeCount + 1
          : Math.max(0, meta.likeCount - 1);
      mockAnswerLikeMeta.set(answerId, { isLiked: nextLiked, likeCount: adjustedCount });
      return {
        ok: true,
        data: { isLiked: nextLiked, likedCount: adjustedCount },
      };
    })();
  }
  return request<LikeResultVO>({
    method: 'POST',
    url: `/answer/like/${answerId}`,
    data: { liked },
  });
}

/** 采纳回答 */
export function acceptAnswer(answerId: number): Promise<RequestResult<void>> {
  if (!Number.isFinite(answerId) || answerId <= 0) {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '回答不存在或参数无效',
    });
  }
  if (USE_MOCK) {
    return (async () => {
      await mockDelay();
      const qid = resolveQuestionIdForAnswer(answerId);
      if (qid === undefined) {
        return { ok: false, errorType: 'invalidData', message: '回答不存在' };
      }
      mockAcceptedByQuestion.set(qid, answerId);
      return { ok: true, data: undefined as void };
    })();
  }
  return request<void>({
    method: 'POST',
    url: `/answer/accept/${answerId}`,
  });
}

/** 删除回答 */
export function deleteAnswer(answerId: number): Promise<RequestResult<void>> {
  if (!Number.isFinite(answerId) || answerId <= 0) {
    return Promise.resolve({
      ok: false,
      errorType: 'invalidData',
      message: '回答不存在或参数无效',
    });
  }
  if (USE_MOCK) {
    return (async () => {
      await mockDelay();
      if (!findAnswerRowInMock(answerId)) {
        return { ok: false, errorType: 'invalidData', message: '回答不存在' };
      }
      const qid = resolveQuestionIdForAnswer(answerId);
      if (qid !== undefined && mockAcceptedByQuestion.get(qid) === answerId) {
        mockAcceptedByQuestion.delete(qid);
      }
      mockDeletedAnswerIds.add(answerId);
      return { ok: true, data: undefined as void };
    })();
  }
  return request<void>({
    method: 'DELETE',
    url: `/answer/${answerId}`,
  });
}
