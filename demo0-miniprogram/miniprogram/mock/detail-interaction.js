"use strict";
Object.defineProperty(exports, "__esModule", { value: true });
exports.seedInteractionFromContentVO = seedInteractionFromContentVO;
exports.mockSetContentLike = mockSetContentLike;
exports.mockSetContentCollect = mockSetContentCollect;
const store = new Map();
function seedInteractionFromContentVO(vo) {
    const id = Number(vo.contentId);
    if (!Number.isFinite(id) || id <= 0) {
        return;
    }
    if (store.has(id)) {
        return;
    }
    store.set(id, {
        liked: vo.isLiked === true,
        collected: vo.isCollected === true,
        likeCount: typeof vo.liked === 'number' && Number.isFinite(vo.liked) ? Math.max(0, vo.liked) : 0,
        collectCount: typeof vo.collectCount === 'number' && Number.isFinite(vo.collectCount)
            ? Math.max(0, vo.collectCount)
            : 0,
    });
}
function ensureSt(contentId) {
    let st = store.get(contentId);
    if (!st) {
        st = { liked: false, collected: false, likeCount: 0, collectCount: 0 };
        store.set(contentId, st);
    }
    return st;
}
function mockSetContentLike(contentId, liked) {
    const st = ensureSt(contentId);
    if (st.liked !== liked) {
        st.likeCount = Math.max(0, st.likeCount + (liked ? 1 : -1));
        st.liked = liked;
    }
    return { isLiked: st.liked, likedCount: st.likeCount };
}
function mockSetContentCollect(contentId, collected) {
    const st = ensureSt(contentId);
    if (st.collected !== collected) {
        st.collectCount = Math.max(0, st.collectCount + (collected ? 1 : -1));
        st.collected = collected;
    }
    return { isCollect: st.collected, collectCount: st.collectCount };
}
