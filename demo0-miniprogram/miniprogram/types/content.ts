import type { ContentType } from '../constants/content';

/**
 * 与后端 ContentVO JSON 对齐；字段缺失时在 service 层兜底。
 */
export interface ContentVO {
  contentId: number;
  contentType: number;
  title?: string;
  content?: string;
  publishUserId?: number;
  auditStatus?: number;
  createTime?: string;
  images?: string[];
  avatarUrl?: string;
  nickName?: string;
  quantaDepartment?: string;
  quantaBatch?: string;
  liked?: number;
  isLiked?: boolean;
  isCollected?: boolean;
  commentCount?: number;
  collectCount?: number;
  /** 专业问答等场景下由后端扩展时可映射 */
  answerCount?: number;
}

/**
 * 内容卡片展示模型（供 content-card 等组件使用，与 VO 解耦）。
 */
export interface ContentCardModel {
  contentId: number;
  contentType: ContentType;
  title?: string;
  content?: string;
  images?: string[];
  authorId: number;
  authorName: string;
  authorAvatar?: string;
  quantaDepartment?: string;
  quantaBatch?: string;
  likeCount: number;
  collectCount: number;
  commentCount: number;
  liked?: boolean;
  collected?: boolean;
  answerCount?: number;
  createTime?: string;
  /** 我的发布等：0 待审核 1 通过 2 驳回 */
  auditStatus?: number;
}

/** 与后端 ContentReportDTO 对齐 */
export interface ContentReportPayload {
  contentId: number;
  /** 1-5：垃圾广告 / 人身攻击 / 违规内容 / 虚假信息 / 其他 */
  reportType: number;
}
