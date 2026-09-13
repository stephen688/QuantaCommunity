import { DEFAULT_PAGE_SIZE } from '../../constants/request';
import { ROUTES } from '../../constants/route';
import { setFollowUser } from '../../services/follow.service';
import { getAuthStatus } from '../../services/user-auth.service';
import {
  getUserProfile,
  getUserPublicContents,
  mapUserProfileVOToModel,
} from '../../services/user.service';
import type { ApiErrorType } from '../../types/api';
import type { ContentCardModel } from '../../types/content';
import type { UserProfileModel, UserProfileVO } from '../../types/user';
import { isRealNameRequiredMessage } from '../../utils/detail-shared';
import { feedFullScreenError } from '../../utils/feed-error';
import {
  createPageRefreshSnapshot,
  markRefreshConsumed,
  shouldReloadOnShow,
  type PageRefreshSnapshot,
} from '../../utils/page-refresh';
import { patchContentCardInList } from '../../utils/content-card-list';
import { navigateFromContentCardEvent } from '../../utils/content-card-navigate';

function parseUserId(raw: string | undefined): number | null {
  if (raw === undefined || raw === null || raw === '') {
    return null;
  }
  const n = Number(raw);
  if (!Number.isFinite(n) || n <= 0 || !Number.isInteger(n)) {
    return null;
  }
  return n;
}

async function mapProfileWithAuthAudit(vo: UserProfileVO) {
  let audit: number | undefined;
  if (vo.isSelf === true) {
    const authRes = await getAuthStatus();
    if (authRes.ok && authRes.data?.auditStatus !== undefined && authRes.data?.auditStatus !== null) {
      audit = authRes.data.auditStatus;
    }
  }
  return mapUserProfileVOToModel(vo, audit);
}

Page({
  data: {
    invalidQuery: false,
    userIdNum: 0,
    profile: null as UserProfileModel | null,
    list: [] as ContentCardModel[],
    loading: true,
    refreshing: false,
    loadingMore: false,
    finished: false,
    errorType: '' as ApiErrorType | '',
    errorMessage: '',
    stateTitle: '',
    stateActionText: '',
    needAuthVerify: false,
    listLoadError: false,
    listErrorMessage: '',
    listNeedAuthVerify: false,
    current: 1,
    pageSize: DEFAULT_PAGE_SIZE,
    loadMoreError: false,
    followLoading: false,
  },

  _loadMoreLock: false,
  _refreshSnapshot: createPageRefreshSnapshot() as PageRefreshSnapshot,
  _pageHasShown: false,

  onLoad(options: Record<string, string | undefined>) {
    const uid = parseUserId(options.userId);
    if (uid === null) {
      this.setData({
        invalidQuery: true,
        loading: false,
      });
      return;
    }
    this.setData({
      userIdNum: uid,
      invalidQuery: false,
    });
    void this.loadInitial(false);
  },

  onShow() {
    if (!this._pageHasShown) {
      this._pageHasShown = true;
      return;
    }
    if (
      !this.data.profile?.isSelf ||
      !shouldReloadOnShow(this._refreshSnapshot, ['userProfile', 'userAuth'])
    ) {
      return;
    }
    markRefreshConsumed(this._refreshSnapshot, ['userProfile', 'userAuth']);
    void this.loadInitial(true);
  },

  onPullDownRefresh() {
    if (this.data.invalidQuery) {
      wx.stopPullDownRefresh();
      return;
    }
    this.setData({ refreshing: true });
    void this.loadInitial(true);
  },

  onReachBottom() {
    if (
      this.data.invalidQuery ||
      this.data.listLoadError ||
      this.data.loading ||
      this.data.refreshing ||
      this.data.loadingMore ||
      this.data.finished ||
      !this.data.list.length
    ) {
      return;
    }
    void this.loadMore();
  },

  onStateAction() {
    if (this.data.needAuthVerify) {
      wx.navigateTo({ url: `${ROUTES.PROFILE_EDIT}?intent=verify` });
      return;
    }
    void this.loadInitial(false);
  },

  onListStateAction() {
    if (this.data.listNeedAuthVerify) {
      wx.navigateTo({ url: `${ROUTES.PROFILE_EDIT}?intent=verify` });
      return;
    }
    void this.reloadListFirstPage();
  },

  onFooterRetry() {
    this.setData({ loadMoreError: false });
    void this.loadMore();
  },

  onEditProfile() {
    wx.navigateTo({ url: ROUTES.PROFILE_EDIT });
  },

  async onFollowTap() {
    const p = this.data.profile;
    const uid = this.data.userIdNum;
    if (!p || p.isSelf || this.data.followLoading || !uid) {
      return;
    }
    this.setData({ followLoading: true });
    try {
      const res = await setFollowUser(uid, p.isFollowed !== true);
      if (!res.ok) {
        wx.showToast({
          title: (res.message && String(res.message).trim()) || '操作失败',
          icon: 'none',
        });
        return;
      }
      const d = res.data;
      const nextFollowed = d.isFollowed === true;
      let fc = p.followerCount;
      const rawFc = d.followerCount ?? d.followCount;
      if (rawFc !== undefined && rawFc !== null && Number.isFinite(Number(rawFc))) {
        fc = Math.max(0, Number(rawFc));
      } else {
        fc = nextFollowed ? fc + 1 : Math.max(0, fc - 1);
      }
      this.setData({
        'profile.isFollowed': nextFollowed,
        'profile.followerCount': fc,
      });
    } catch {
      wx.showToast({ title: '网络异常', icon: 'none' });
    } finally {
      this.setData({ followLoading: false });
    }
  },

  onCardTap(e: WechatMiniprogram.CustomEvent<{ item: ContentCardModel }>) {
    navigateFromContentCardEvent(e);
  },

  onCardInteractionChange(e: WechatMiniprogram.CustomEvent<{ item: ContentCardModel }>) {
    const next = e.detail?.item;
    if (!next?.contentId) {
      return;
    }
    this.setData({
      list: patchContentCardInList(this.data.list, next),
    });
  },

  async loadInitial(fromRefresh: boolean) {
    const uid = this.data.userIdNum;
    if (!uid || this.data.invalidQuery) {
      return;
    }

    if (!fromRefresh) {
      this.setData({
        loading: true,
        profile: null,
        list: [],
        current: 1,
        finished: false,
        errorType: '',
        errorMessage: '',
        stateTitle: '',
        stateActionText: '',
        needAuthVerify: false,
        listLoadError: false,
        listErrorMessage: '',
        listNeedAuthVerify: false,
        loadMoreError: false,
      });
    }

    try {
      const [profileRes, contentsRes] = await Promise.all([
        getUserProfile(uid),
        getUserPublicContents(uid, 1, this.data.pageSize),
      ]);

      if (!fromRefresh) {
        if (!profileRes.ok) {
          if (isRealNameRequiredMessage(profileRes.message || '')) {
            this.setData({
              loading: false,
              errorType: 'server',
              stateTitle: '需完成实名认证',
              errorMessage: profileRes.message || '请先完成实名认证',
              stateActionText: '去认证',
              needAuthVerify: true,
              profile: null,
              list: [],
              listLoadError: false,
              listNeedAuthVerify: false,
            });
            return;
          }
          const err = feedFullScreenError(profileRes);
          this.setData({
            loading: false,
            profile: null,
            list: [],
            listLoadError: false,
            listNeedAuthVerify: false,
            needAuthVerify: false,
            errorType: err.errorType,
            stateTitle: err.stateTitle,
            errorMessage: err.errorMessage,
            stateActionText: err.stateActionText,
          });
          return;
        }

        const model = await mapProfileWithAuthAudit(profileRes.data);
        if (!model) {
          this.setData({
            loading: false,
            profile: null,
            list: [],
            listLoadError: false,
            listNeedAuthVerify: false,
            needAuthVerify: false,
            errorType: 'invalidData',
            stateTitle: '数据异常',
            errorMessage: '用户主页数据异常',
            stateActionText: '重新加载',
          });
          return;
        }

        wx.setNavigationBarTitle({ title: model.nickname || '用户主页' });

        if (!contentsRes.ok) {
          if (isRealNameRequiredMessage(contentsRes.message || '')) {
            this.setData({
              loading: false,
              profile: model,
              list: [],
              current: 1,
              finished: true,
              listLoadError: true,
              listErrorMessage: contentsRes.message || '请先完成实名认证',
              listNeedAuthVerify: true,
              needAuthVerify: false,
            });
            return;
          }
          this.setData({
            loading: false,
            profile: model,
            list: [],
            current: 1,
            finished: true,
            listLoadError: true,
            listErrorMessage:
              (contentsRes.message && String(contentsRes.message).trim()) || '加载失败',
            listNeedAuthVerify: false,
            needAuthVerify: false,
          });
          return;
        }

        const { list, hasMore } = contentsRes.data;
        markRefreshConsumed(this._refreshSnapshot, ['userProfile', 'userAuth']);

        this.setData({
          loading: false,
          profile: model,
          list,
          current: 1,
          finished: !hasMore || list.length < this.data.pageSize,
          listLoadError: false,
          listErrorMessage: '',
          listNeedAuthVerify: false,
          needAuthVerify: false,
          errorType: '',
          errorMessage: '',
          stateTitle: '',
          stateActionText: '',
        });
        return;
      }

      let profileToast = false;
      let contentsToast = false;

      if (profileRes.ok) {
        const model = await mapProfileWithAuthAudit(profileRes.data);
        if (model) {
          wx.setNavigationBarTitle({ title: model.nickname || '用户主页' });
          this.setData({ profile: model });
        } else if (this.data.profile) {
          profileToast = true;
        }
      } else if (this.data.profile) {
        profileToast = true;
      }

      if (contentsRes.ok) {
        const { list, hasMore } = contentsRes.data;
        this.setData({
          list,
          current: 1,
          finished: !hasMore || list.length < this.data.pageSize,
          listLoadError: false,
          listErrorMessage: '',
          listNeedAuthVerify: false,
          loadMoreError: false,
        });
      } else if (this.data.list.length) {
        contentsToast = true;
      } else {
        const msg = (contentsRes.message && String(contentsRes.message).trim()) || '加载失败';
        this.setData({
          list: [],
          current: 1,
          finished: true,
          listLoadError: true,
          listErrorMessage: msg,
          listNeedAuthVerify: isRealNameRequiredMessage(msg),
        });
      }

      if (profileToast) {
        wx.showToast({ title: '资料刷新失败', icon: 'none' });
      }
      if (contentsToast) {
        wx.showToast({ title: '内容刷新失败', icon: 'none' });
      }
    } catch {
      if (!fromRefresh) {
        this.setData({
          loading: false,
          profile: null,
          list: [],
          listLoadError: false,
          listNeedAuthVerify: false,
          needAuthVerify: false,
          ...feedFullScreenError({
            ok: false,
            errorType: 'network',
            message: '请求异常，请稍后重试',
          }),
        });
      } else {
        wx.showToast({ title: '刷新失败', icon: 'none' });
      }
    } finally {
      if (fromRefresh) {
        wx.stopPullDownRefresh();
        this.setData({ refreshing: false });
      }
    }
  },

  async reloadListFirstPage() {
    const uid = this.data.userIdNum;
    if (!uid || !this.data.profile) {
      return;
    }
    this.setData({ listLoadError: false, listErrorMessage: '', listNeedAuthVerify: false });
    try {
      const res = await getUserPublicContents(uid, 1, this.data.pageSize);
      if (!res.ok) {
        const msg = (res.message && String(res.message).trim()) || '加载失败';
        this.setData({
          listLoadError: true,
          listErrorMessage: msg,
          listNeedAuthVerify: isRealNameRequiredMessage(msg),
          list: [],
          current: 1,
          finished: true,
        });
        return;
      }
      const { list, hasMore } = res.data;
      this.setData({
        list,
        current: 1,
        finished: !hasMore || list.length < this.data.pageSize,
        listLoadError: false,
        listErrorMessage: '',
        listNeedAuthVerify: false,
      });
    } catch {
      this.setData({
        listLoadError: true,
        listErrorMessage: '网络异常，请稍后重试',
        listNeedAuthVerify: false,
        list: [],
        current: 1,
        finished: true,
      });
    }
  },

  async loadMore() {
    if (this._loadMoreLock) {
      return;
    }
    const uid = this.data.userIdNum;
    if (
      !uid ||
      this.data.loading ||
      this.data.refreshing ||
      this.data.loadingMore ||
      this.data.finished ||
      !this.data.list.length
    ) {
      return;
    }
    this._loadMoreLock = true;
    this.setData({ loadingMore: true, loadMoreError: false });
    try {
      const next = this.data.current + 1;
      const res = await getUserPublicContents(uid, next, this.data.pageSize);
      if (!res.ok) {
        this.setData({ loadMoreError: true });
        return;
      }
      const { list: nextList, hasMore } = res.data;
      const merged = [...this.data.list];
      const seen = new Set(merged.map((x) => x.contentId));
      for (const c of nextList) {
        if (!seen.has(c.contentId)) {
          seen.add(c.contentId);
          merged.push(c);
        }
      }
      this.setData({
        list: merged,
        current: next,
        finished: !hasMore || nextList.length < this.data.pageSize,
      });
    } catch {
      this.setData({ loadMoreError: true });
    } finally {
      this._loadMoreLock = false;
      this.setData({ loadingMore: false });
    }
  },
});
