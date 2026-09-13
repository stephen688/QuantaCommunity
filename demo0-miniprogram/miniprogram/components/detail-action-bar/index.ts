Component({
  properties: {
    liked: { type: Boolean, value: false },
    collected: { type: Boolean, value: false },
    likeCount: { type: Number, value: 0 },
    collectCount: { type: Number, value: 0 },
    commentCount: { type: Number, value: 0 },
    /** 为 true 时禁止重复点击（点赞/收藏等请求中） */
    loading: { type: Boolean, value: false },
    /** 固定在视口底部并留出安全区 */
    fixed: { type: Boolean, value: false },
    composerPlaceholder: { type: String, value: '说点什么…' },
    showComposerTrigger: { type: Boolean, value: true },
    /** 回答详情等场景可隐藏收藏 */
    showCollect: { type: Boolean, value: true },
  },
  data: {
    likeCountText: '0',
    collectCountText: '0',
    commentCountText: '0',
  },
  observers: {
    likeCount(n: number) {
      this.setData({ likeCountText: formatCount(n) });
    },
    collectCount(n: number) {
      this.setData({ collectCountText: formatCount(n) });
    },
    commentCount(n: number) {
      this.setData({ commentCountText: formatCount(n) });
    },
  },
  lifetimes: {
    attached() {
      this.setData({
        likeCountText: formatCount(this.data.likeCount),
        collectCountText: formatCount(this.data.collectCount),
        commentCountText: formatCount(this.data.commentCount),
      });
    },
  },
  methods: {
    onComposerTriggerTap() {
      this.triggerEvent('composeropen');
    },
    onLikeTap() {
      if (this.data.loading) {
        return;
      }
      this.triggerEvent('like');
    },
    onCollectTap() {
      if (this.data.loading) {
        return;
      }
      this.triggerEvent('collect');
    },
    onCommentTap() {
      this.triggerEvent('composeropen');
    },
  },
});

function formatCount(n: unknown): string {
  const v = typeof n === 'number' && Number.isFinite(n) ? Math.max(0, Math.floor(n)) : 0;
  if (v > 9999) {
    return `${(v / 10000).toFixed(1).replace(/\.0$/, '')}万`;
  }
  return String(v);
}
