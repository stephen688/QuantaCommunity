import { formatRelativeTime } from '../../utils/format';
import { commentNeedsReplyFetch } from '../../utils/detail-shared';
import type { CommentItemModel } from '../../types/comment';

type CommentLike = Pick<
  CommentItemModel,
  | 'commentId'
  | 'userId'
  | 'nickName'
  | 'avatarUrl'
  | 'content'
  | 'createTime'
  | 'likeCount'
  | 'liked'
  | 'replyCount'
  | 'replyNickName'
  | 'replies'
  | 'replyLoading'
  | 'replyLoadError'
>;

type Row = CommentLike & {
  _rowKey: string;
  _displayName: string;
  _letter: string;
  _timeText: string;
  _likeText: string;
  _collapsedReplies: Row[];
  _expandedReplies: Row[];
  _hasMoreReplies: boolean;
  _expandText: string;
  _showExpanded: boolean;
  _replyLoading: boolean;
  _replyLoadError: boolean;
};

Component({
  properties: {
    items: {
      type: Array,
      value: [] as CommentLike[],
    },
    /** 首屏加载（尚无数据） */
    loading: {
      type: Boolean,
      value: false,
    },
    loadingMore: { type: Boolean, value: false },
    loadMoreError: { type: Boolean, value: false },
    finished: { type: Boolean, value: true },
    emptyTitle: { type: String, value: '暂无评论' },
    emptyDesc: { type: String, value: '快来抢沙发吧' },
    emptyActionText: { type: String, value: '' },
    /** 有列表数据时是否展示底栏 */
    footerVisible: {
      type: Boolean,
      value: true,
    },
    /** 是否展示「更多」（Phase3 详情页不传 true，仅长按出菜单） */
    showMore: {
      type: Boolean,
      value: false,
    },
    /** 一级/二级 longpress → commentmore */
    enableLongPressMore: {
      type: Boolean,
      value: true,
    },
    defaultReplyLimit: {
      type: Number,
      value: 2,
    },
  },
  data: {
    rows: [] as Row[],
    expandedMap: {} as Record<string, boolean>,
    initialLoading: false,
    showEmpty: false,
    showFooter: false,
  },
  observers: {
    items(list: CommentLike[]) {
      this.applyRows(Array.isArray(list) ? list : []);
    },
    loading() {
      this.applyVisibility();
    },
    footerVisible() {
      this.applyVisibility();
    },
  },
  lifetimes: {
    attached() {
      const list = Array.isArray(this.data.items) ? (this.data.items as CommentLike[]) : [];
      this.applyRows(list);
      this.applyVisibility();
    },
  },
  methods: {
    applyRows(list: CommentLike[]) {
      const rows = list.map((it, idx) =>
        enrich(it, idx, this.data.expandedMap, this.data.defaultReplyLimit, 0),
      );
      this.setData({ rows });
      this.applyVisibility();
    },
    applyVisibility() {
      const list = this.data.rows;
      const loading = Boolean(this.data.loading);
      const initialLoading = loading && list.length === 0;
      const showEmpty = !loading && list.length === 0;
      const showFooter = Boolean(this.data.footerVisible) && list.length > 0;
      this.setData({ initialLoading, showEmpty, showFooter });
    },
    onLikeTap(e: { currentTarget?: { dataset?: { id?: unknown } } }) {
      const id = Number(e.currentTarget?.dataset?.id);
      if (!Number.isFinite(id)) {
        return;
      }
      this.triggerEvent('like', { commentId: id });
    },
    onFooterRetry() {
      this.triggerEvent('retry');
    },
    onEmptyAction() {
      this.triggerEvent('emptyaction', {});
    },
    onMoreTap(e: { currentTarget?: { dataset?: { id?: unknown; userId?: unknown } } }) {
      if (!this.data.showMore) {
        return;
      }
      const id = Number(e.currentTarget?.dataset?.id);
      const userId = Number(e.currentTarget?.dataset?.userId);
      if (!Number.isFinite(id)) {
        return;
      }
      this.triggerEvent('commentmore', {
        commentId: id,
        depth: 0,
        userId: Number.isFinite(userId) ? userId : 0,
      });
    },
    onStopLongPress() {
      /* 阻止冒泡到行级 longpress，回复/点赞区不弹出菜单 */
    },
    onLongPressMore(e: {
      currentTarget?: { dataset?: { id?: unknown; userId?: unknown; depth?: unknown } };
    }) {
      if (!this.data.enableLongPressMore) {
        return;
      }
      const id = Number(e.currentTarget?.dataset?.id);
      const userId = Number(e.currentTarget?.dataset?.userId);
      const depthRaw = Number(e.currentTarget?.dataset?.depth);
      const depth = depthRaw === 1 ? 1 : 0;
      if (!Number.isFinite(id) || !id) {
        return;
      }
      wx.vibrateShort({ type: 'light', fail: () => {} });
      this.triggerEvent('commentmore', {
        commentId: id,
        depth,
        userId: Number.isFinite(userId) ? userId : 0,
      });
    },
    onAuthorTap(e: { currentTarget?: { dataset?: { userId?: unknown } } }) {
      const userId = e.currentTarget?.dataset?.userId;
      this.triggerEvent('tapauthor', { userId });
    },
    onReplyTap(e: { currentTarget?: { dataset?: { commentId?: unknown; userId?: unknown; nickName?: unknown } } }) {
      const commentId = Number(e.currentTarget?.dataset?.commentId);
      const userId = Number(e.currentTarget?.dataset?.userId);
      const nickNameRaw = e.currentTarget?.dataset?.nickName;
      const nickName = typeof nickNameRaw === 'string' ? nickNameRaw : '';
      if (!Number.isFinite(commentId) || !Number.isFinite(userId)) {
        return;
      }
      this.triggerEvent('reply', { commentId, userId, nickName });
    },
    onRetryReplies(e: { currentTarget?: { dataset?: { commentId?: unknown } } }) {
      const commentId = Number(e.currentTarget?.dataset?.commentId);
      if (!Number.isFinite(commentId)) {
        return;
      }
      this.triggerEvent('loadreplies', { commentId });
    },
    onToggleReplies(e: {
      currentTarget?: { dataset?: { key?: unknown; expanded?: unknown; commentId?: unknown } };
    }) {
      const key = e.currentTarget?.dataset?.key;
      if (typeof key !== 'string' || !key) {
        return;
      }
      const expanded = e.currentTarget?.dataset?.expanded === true;
      const commentId = Number(e.currentTarget?.dataset?.commentId);
      const row = this.data.rows.find((r) => r._rowKey === key);
      if (row?._replyLoading) {
        return;
      }
      if (!expanded) {
        const current = this.data.expandedMap || {};
        const next: Record<string, boolean> = { ...current, [key]: true };
        this.setData({ expandedMap: next });
        this.applyRows(Array.isArray(this.data.items) ? (this.data.items as CommentLike[]) : []);
        if (Number.isFinite(commentId)) {
          const items = Array.isArray(this.data.items) ? (this.data.items as CommentLike[]) : [];
          const top = items.find((c) => c.commentId === commentId);
          if (commentNeedsReplyFetch(top as CommentItemModel | undefined)) {
            this.triggerEvent('loadreplies', { commentId });
          }
        }
        return;
      }
      const current = this.data.expandedMap || {};
      const next: Record<string, boolean> = { ...current, [key]: !current[key] };
      this.setData({ expandedMap: next });
      this.applyRows(Array.isArray(this.data.items) ? (this.data.items as CommentLike[]) : []);
      this.triggerEvent('togglereplies', { rowKey: key, expanded: next[key] === true });
    },
    onLoadReplies(e: { currentTarget?: { dataset?: { commentId?: unknown } } }) {
      const commentId = Number(e.currentTarget?.dataset?.commentId);
      if (!Number.isFinite(commentId)) {
        return;
      }
      this.triggerEvent('loadreplies', { commentId });
    },
  },
});

function enrich(
  it: CommentLike,
  index: number,
  expandedMap: Record<string, boolean>,
  defaultReplyLimit: number,
  depth: number,
): Row {
  const name = (it.nickName && String(it.nickName).trim()) || '匿名用户';
  const letter = name.slice(0, 1);
  const lc =
    typeof it.likeCount === 'number' && Number.isFinite(it.likeCount) ? Math.max(0, it.likeCount) : 0;
  const cid = typeof it.commentId === 'number' && Number.isFinite(it.commentId) ? it.commentId : NaN;
  const _rowKey = Number.isFinite(cid) ? `c-${cid}` : `i-${index}`;
  const limit = Number.isFinite(defaultReplyLimit) && defaultReplyLimit > 0 ? Math.floor(defaultReplyLimit) : 2;
  const repliesRaw = depth > 0 ? [] : Array.isArray(it.replies) ? it.replies : [];
  const collapsedSource = repliesRaw.slice(0, Math.min(limit, repliesRaw.length));
  const expandedSource = repliesRaw.slice();
  const _collapsedReplies = collapsedSource.map((row, idx) =>
    enrich(row, idx, expandedMap, limit, depth + 1),
  );
  const _expandedReplies = expandedSource.map((row, idx) =>
    enrich(row, idx, expandedMap, limit, depth + 1),
  );
  const replyCountRaw =
    typeof it.replyCount === 'number' && Number.isFinite(it.replyCount) ? Math.max(0, it.replyCount) : 0;
  const totalReplyCount = depth > 0 ? repliesRaw.length : Math.max(replyCountRaw, repliesRaw.length);
  const _hasMoreReplies = depth > 0 ? false : totalReplyCount > _collapsedReplies.length;
  const _showExpanded = expandedMap[_rowKey] === true;
  const hiddenCount = Math.max(0, totalReplyCount - _collapsedReplies.length);
  const _replyLoading = depth === 0 && it.replyLoading === true;
  const _replyLoadError = depth === 0 && it.replyLoadError === true && !_replyLoading;
  let _expandText = _showExpanded ? '收起回复' : `展开 ${hiddenCount} 条回复`;
  if (_replyLoading) {
    _expandText = '加载回复中…';
  }
  return {
    ...it,
    _rowKey,
    _displayName: name,
    _letter: letter,
    _timeText: formatRelativeTime(it.createTime),
    _likeText: lc > 9999 ? `${(lc / 10000).toFixed(1).replace(/\.0$/, '')}万` : String(lc),
    _collapsedReplies,
    _expandedReplies,
    _hasMoreReplies: depth > 0 ? false : _hasMoreReplies || _replyLoadError,
    _expandText,
    _showExpanded,
    _replyLoading,
    _replyLoadError,
  };
}
