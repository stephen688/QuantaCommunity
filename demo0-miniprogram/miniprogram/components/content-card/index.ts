import { CONTENT_TYPE_LIFE, CONTENT_TYPE_PROFESSIONAL } from '../../constants/content';
import { ROUTES } from '../../constants/route';
import { ensureLoggedIn } from '../../services/auth.service';
import { setContentLike, setContentCollect } from '../../services/interaction.service';
import type { ContentCardModel } from '../../types/content';
import type { CollectResultVO, LikeResultVO } from '../../types/interaction';
import { formatCompactNumber, formatRelativeTime, formatSummary } from '../../utils/format';
import { normalizeImages } from '../../utils/image';

Component({
  properties: {
    item: {
      type: Object,
      value: {} as ContentCardModel,
    },
    showAuthor: {
      type: Boolean,
      value: true,
    },
    /** 标题下方展示作者（与 showAuthor 互斥：有 showAuthor 时不展示标题下作者） */
    authorUnderTitle: {
      type: Boolean,
      value: false,
    },
    /** 我的发布等：展示审核状态胶囊 */
    showAuditBadge: {
      type: Boolean,
      value: false,
    },
    /** 底部赞/藏/评是否可点击操作 */
    interactiveStats: {
      type: Boolean,
      value: true,
    },
  },
  data: {
    isLife: false,
    isPro: false,
    imageList: [] as string[],
    hasSingleImage: false,
    hasMultiImages: false,
    summary: '',
    likeText: '0',
    collectText: '0',
    commentText: '0',
    answerText: '',
    showAnswer: false,
    metaTimeText: '',
    auditLabel: '',
    auditTone: '',
    liked: false,
    collected: false,
    likeLoading: false,
    collectLoading: false,
  },
  observers: {
    item() {
      this.applyItem();
    },
  },
  lifetimes: {
    attached() {
      this.applyItem();
    },
  },
  methods: {
    applyItem() {
      const raw = this.data.item as ContentCardModel | Record<string, never>;
      const item = raw && typeof raw === 'object' ? raw : ({} as ContentCardModel);
      const ct = item.contentType;
      const isLife = ct === CONTENT_TYPE_LIFE;
      const isPro = ct === CONTENT_TYPE_PROFESSIONAL;
      const imageList = normalizeImages(item.images).slice(0, 5);
      const hasSingleImage = imageList.length === 1;
      const hasMultiImages = imageList.length > 1;
      const summary = formatSummary(item.content || item.title, isPro ? 100 : 72);
      const answerText =
        item.answerCount !== undefined && item.answerCount !== null
          ? `${formatCompactNumber(item.answerCount)} 回答`
          : '';
      const metaTimeText = formatRelativeTime(item.createTime);
      const audit = this.data.showAuditBadge
        ? resolveAuditBadge(item.auditStatus)
        : { label: '', tone: '' };
      this.setData({
        isLife,
        isPro,
        imageList,
        hasSingleImage,
        hasMultiImages,
        summary,
        likeText: formatCompactNumber(item.likeCount),
        collectText: formatCompactNumber(item.collectCount),
        commentText: formatCompactNumber(item.commentCount),
        answerText,
        showAnswer: isPro && Boolean(answerText),
        metaTimeText,
        auditLabel: audit.label,
        auditTone: audit.tone,
        liked: item.liked === true,
        collected: item.collected === true,
      });
    },
    onTap() {
      const item = this.properties.item as ContentCardModel;
      this.triggerEvent('cardtap', { item });
    },
    onAuthorRowTap(e: WechatMiniprogram.CustomEvent<{ userId?: number }>) {
      this.triggerEvent('tapauthor', { userId: e.detail?.userId });
    },
    async onLikeTap() {
      if (!this.data.interactiveStats || this.data.likeLoading || this.data.collectLoading) {
        return;
      }
      const item = this.data.item as ContentCardModel;
      if (!item?.contentId) {
        return;
      }
      const loggedIn = await ensureLoggedIn();
      if (!loggedIn) {
        return;
      }
      this.setData({ likeLoading: true });
      const res = await setContentLike(item.contentId, item.liked !== true);
      this.setData({ likeLoading: false });
      if (!res.ok) {
        wx.showToast({ title: res.message.slice(0, 14) || '操作失败', icon: 'none' });
        return;
      }
      this.applyInteractionPatch(applyLikeResult(item, res.data));
    },
    async onCollectTap() {
      if (!this.data.interactiveStats || this.data.likeLoading || this.data.collectLoading) {
        return;
      }
      const item = this.data.item as ContentCardModel;
      if (!item?.contentId) {
        return;
      }
      const loggedIn = await ensureLoggedIn();
      if (!loggedIn) {
        return;
      }
      this.setData({ collectLoading: true });
      const res = await setContentCollect(item.contentId, item.collected !== true);
      this.setData({ collectLoading: false });
      if (!res.ok) {
        wx.showToast({ title: res.message.slice(0, 14) || '操作失败', icon: 'none' });
        return;
      }
      this.applyInteractionPatch(applyCollectResult(item, res.data));
    },
    onCommentTap() {
      if (!this.data.interactiveStats) {
        return;
      }
      const item = this.data.item as ContentCardModel;
      if (!item?.contentId) {
        return;
      }
      if (item.contentType === CONTENT_TYPE_LIFE) {
        wx.navigateTo({ url: `${ROUTES.DETAIL_LIFE}?contentId=${item.contentId}` });
        return;
      }
      if (item.contentType === CONTENT_TYPE_PROFESSIONAL) {
        wx.navigateTo({ url: `${ROUTES.DETAIL_PRO}?contentId=${item.contentId}` });
        return;
      }
      wx.showToast({ title: '内容类型异常', icon: 'none' });
    },
    applyInteractionPatch(patch: Partial<ContentCardModel>) {
      const item = this.data.item as ContentCardModel;
      const next = { ...item, ...patch };
      this.setData({
        liked: next.liked === true,
        collected: next.collected === true,
        likeText: formatCompactNumber(next.likeCount),
        collectText: formatCompactNumber(next.collectCount),
      });
      this.triggerEvent('interactionchange', { item: next });
    },
  },
});

function resolveAuditBadge(raw: unknown): { label: string; tone: string } {
  const st = typeof raw === 'number' && Number.isFinite(raw) ? Math.floor(raw) : NaN;
  if (st === 0) {
    return { label: '审核中', tone: 'pending' };
  }
  if (st === 1) {
    return { label: '已通过', tone: 'approved' };
  }
  if (st === 2) {
    return { label: '已驳回', tone: 'rejected' };
  }
  return { label: '', tone: '' };
}

function applyLikeResult(item: ContentCardModel, vo: LikeResultVO): Partial<ContentCardModel> {
  const liked = vo.isLiked === true;
  const likeCount =
    vo.likedCount === undefined || vo.likedCount === null
      ? item.likeCount
      : Math.max(0, Number(vo.likedCount) || 0);
  return { liked, likeCount };
}

function applyCollectResult(item: ContentCardModel, vo: CollectResultVO): Partial<ContentCardModel> {
  const collected = vo.isCollect === true;
  const collectCount =
    vo.collectCount === undefined || vo.collectCount === null
      ? item.collectCount
      : Math.max(0, Number(vo.collectCount) || 0);
  return { collected, collectCount };
}
