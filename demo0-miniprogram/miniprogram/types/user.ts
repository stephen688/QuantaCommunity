import type { RoutePath } from '../constants/route';
import type { ContentCardModel } from './content';

/** 与后端 UserLoginVO 对齐（POST /user/login） */
export interface UserLoginVO {
  id?: number;
  openid?: string;
  token: string;
  nickName?: string;
  avatarUrl?: string;
}

/**
 * 用户信息（与 /user/info 等接口对齐；部分字段后端暂未返回时可空）。
 */
export interface UserInfoVO {
  userId?: number;
  /** 展示用昵称（可与后端 nickName 映射） */
  nickname?: string;
  nickName?: string;
  avatarUrl?: string;
  bio?: string;
  /** 与公开主页一致时可用于「我的」展示；后端未返回时可空 */
  authStatus?: number;
  quantaDepartment?: string;
  quantaBatch?: string;
}

/** 与后端 UserInfoDTO 对齐（更新资料） */
export interface UserInfoDTO {
  avatarUrl?: string;
  nickName?: string;
}

export interface MineEntry {
  key: string;
  title: string;
  subtitle?: string;
  icon: string;
  route: RoutePath | string;
  badge?: number;
}

/**
 * 与后端 `UserProfileVO`（C 端公开主页）对齐。
 * @see UserProfileVO.java
 */
export interface UserProfileVO {
  userId?: number;
  nickName?: string;
  /** 与 nickName 二选一时的兼容字段 */
  nickname?: string;
  avatarUrl?: string;
  /** 0-未认证 1-审核中 2-已认证 3-审核不通过 */
  authStatus?: number;
  quantaDepartment?: string;
  quantaBatch?: string;
  contentCount?: number;
  followingCount?: number;
  followerCount?: number;
  isFollowed?: boolean;
  isSelf?: boolean;
}

/**
 * 用户主页稳定展示模型：统一昵称字段、数值与认证文案兜底。
 */
export interface UserProfileModel {
  userId: number;
  /** 展示用昵称（由 nickName / nickname 归一） */
  nickname: string;
  avatarUrl: string;
  authStatus: number;
  authStatusText: string;
  quantaDepartment: string;
  quantaBatch: string;
  contentCount: number;
  followingCount: number;
  followerCount: number;
  isFollowed: boolean;
  isSelf: boolean;
}

const AUTH_STATUS_LABELS: Record<number, string> = {
  0: '未认证',
  1: '审核中',
  2: '已认证',
  3: '审核不通过',
};

/**
 * 公开主页 `authStatus` 展示文案（与后端 UserProfileVO 约定一致）。
 */
export function userProfileAuthStatusText(authStatus?: number | null): string {
  const key = authStatus ?? 0;
  return AUTH_STATUS_LABELS[key] ?? '未认证';
}

/** 用户公开内容分页（前端稳定模型） */
export interface UserPublicContentPage {
  list: ContentCardModel[];
  hasMore: boolean;
}

/**
 * 关注/取关结果；兼容后端 `FollowResultVO`（isFollowed、followCount）。
 * followerCount 供页面展示粉丝数增量更新。
 */
export interface FollowUserResult {
  isFollowed?: boolean;
  /** 后端字段名 */
  followCount?: number;
  followerCount?: number;
}
