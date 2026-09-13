"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.mapAnswerVOToModel = mapAnswerVOToModel;
exports.getAnswerList = getAnswerList;
exports.getAnswerDetail = getAnswerDetail;
exports.publishAnswer = publishAnswer;
exports.likeAnswer = likeAnswer;
exports.acceptAnswer = acceptAnswer;
exports.deleteAnswer = deleteAnswer;
const content_1 = require("../constants/content");
const request_1 = require("../constants/request");
const delay_1 = require("../mock/delay");
const recommend_pool_1 = require("../mock/recommend-pool");
const request_2 = require("../utils/request");
const image_1 = require("../utils/image");
function mapAnswerVOToModel(vo) {
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
        authorAvatar: (0, image_1.withDefaultAvatar)(vo.avatarUrl),
        quantaBatch: vo.quantaBatch ?? undefined,
        content: vo.content ?? '',
        likeCount: vo.likeCount ?? 0,
        commentCount: vo.commentCount ?? 0,
        isAccepted: vo.isAccepted === 1,
        liked: vo.isLiked === true,
        createTime: vo.createTime === undefined || vo.createTime === null ? undefined : String(vo.createTime),
    };
}
const mockPublishedAnswers = new Map();
let mockAnswerSeq = 8800000;
const mockDeletedAnswerIds = new Set();
const mockAcceptedByQuestion = new Map();
const mockAnswerLikeMeta = new Map();
function isMockQuestion(questionId) {
    const hit = (0, recommend_pool_1.findMockContentById)(questionId);
    if (hit?.contentType === content_1.CONTENT_TYPE_PROFESSIONAL) {
        return true;
    }
    return questionId >= 2000 && questionId < 3000;
}
function baseMockAnswers(questionId) {
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
function seedAnswerLikeMeta(row) {
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
function findAnswerRowInMock(answerId) {
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
function resolveQuestionIdForAnswer(answerId) {
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
function applyAnswerMockFields(row) {
    const id = Number(row.answerId);
    const qid = Number(row.questionId);
    seedAnswerLikeMeta(row);
    const meta = mockAnswerLikeMeta.get(id);
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
function listMockAnswers(questionId) {
    const extra = mockPublishedAnswers.get(questionId) ?? [];
    const merged = [...baseMockAnswers(questionId), ...extra];
    return merged
        .filter((r) => !mockDeletedAnswerIds.has(Number(r.answerId)))
        .map(applyAnswerMockFields);
}
function getAnswerList(questionId) {
    if (!Number.isFinite(questionId) || questionId <= 0) {
        return Promise.resolve({
            ok: false,
            errorType: 'invalidData',
            message: '问题不存在或参数无效',
        });
    }
    if (request_1.USE_MOCK) {
        return (async () => {
            await (0, delay_1.mockDelay)();
            return { ok: true, data: listMockAnswers(questionId) };
        })();
    }
    return (0, request_2.request)({
        method: 'GET',
        url: `/answer/list/${questionId}`,
    });
}
function getAnswerDetail(answerId) {
    if (!Number.isFinite(answerId) || answerId <= 0) {
        return Promise.resolve({
            ok: false,
            errorType: 'invalidData',
            message: '回答不存在或参数无效',
        });
    }
    if (request_1.USE_MOCK) {
        return (async () => {
            await (0, delay_1.mockDelay)();
            const row = findAnswerRowInMock(answerId);
            if (!row) {
                return { ok: false, errorType: 'invalidData', message: '回答不存在' };
            }
            return { ok: true, data: applyAnswerMockFields(row) };
        })();
    }
    return (0, request_2.request)({
        method: 'GET',
        url: `/answer/${answerId}`,
    });
}
function publishAnswer(payload) {
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
    if (request_1.USE_MOCK) {
        return (async () => {
            await (0, delay_1.mockDelay)();
            const list = mockPublishedAnswers.get(payload.questionId) ?? [];
            mockAnswerSeq += 1;
            const row = {
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
    return (0, request_2.request)({
        method: 'POST',
        url: '/answer/publish',
        data: {
            questionId: payload.questionId,
            content: payload.content.trim(),
        },
    });
}
/** 点赞 / 取消点赞回答 */
function likeAnswer(answerId) {
    if (!Number.isFinite(answerId) || answerId <= 0) {
        return Promise.resolve({
            ok: false,
            errorType: 'invalidData',
            message: '回答不存在或参数无效',
        });
    }
    if (request_1.USE_MOCK) {
        return (async () => {
            await (0, delay_1.mockDelay)();
            const row = findAnswerRowInMock(answerId);
            if (!row) {
                return { ok: false, errorType: 'invalidData', message: '回答不存在' };
            }
            seedAnswerLikeMeta(row);
            const meta = mockAnswerLikeMeta.get(answerId);
            const nextLiked = !meta.isLiked;
            const adjustedCount = nextLiked ? meta.likeCount + 1 : Math.max(0, meta.likeCount - 1);
            mockAnswerLikeMeta.set(answerId, { isLiked: nextLiked, likeCount: adjustedCount });
            return {
                ok: true,
                data: { isLiked: nextLiked, likedCount: adjustedCount },
            };
        })();
    }
    return (0, request_2.request)({
        method: 'POST',
        url: `/answer/like/${answerId}`,
    });
}
/** 采纳回答 */
function acceptAnswer(answerId) {
    if (!Number.isFinite(answerId) || answerId <= 0) {
        return Promise.resolve({
            ok: false,
            errorType: 'invalidData',
            message: '回答不存在或参数无效',
        });
    }
    if (request_1.USE_MOCK) {
        return (async () => {
            await (0, delay_1.mockDelay)();
            const qid = resolveQuestionIdForAnswer(answerId);
            if (qid === undefined) {
                return { ok: false, errorType: 'invalidData', message: '回答不存在' };
            }
            mockAcceptedByQuestion.set(qid, answerId);
            return { ok: true, data: undefined };
        })();
    }
    return (0, request_2.request)({
        method: 'POST',
        url: `/answer/accept/${answerId}`,
    });
}
/** 删除回答 */
function deleteAnswer(answerId) {
    if (!Number.isFinite(answerId) || answerId <= 0) {
        return Promise.resolve({
            ok: false,
            errorType: 'invalidData',
            message: '回答不存在或参数无效',
        });
    }
    if (request_1.USE_MOCK) {
        return (async () => {
            await (0, delay_1.mockDelay)();
            if (!findAnswerRowInMock(answerId)) {
                return { ok: false, errorType: 'invalidData', message: '回答不存在' };
            }
            const qid = resolveQuestionIdForAnswer(answerId);
            if (qid !== undefined && mockAcceptedByQuestion.get(qid) === answerId) {
                mockAcceptedByQuestion.delete(qid);
            }
            mockDeletedAnswerIds.add(answerId);
            return { ok: true, data: undefined };
        })();
    }
    return (0, request_2.request)({
        method: 'DELETE',
        url: `/answer/${answerId}`,
    });
}
