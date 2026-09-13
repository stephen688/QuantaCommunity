import type { ContentType } from '../constants/content';

/**
 * 详情页稳定展示模型（与 ContentVO 解耦，字段统一为前端命名）。
 */
export interface ContentDetailModel {
  contentId: number;
  contentType: ContentType;
  title?: string;
  content?: string;
  images: string[];
  authorId: number;
  authorName: string;
  authorAvatar?: string;
  quantaDepartment?: string;
  quantaBatch?: string;
  likeCount: number;
  commentCount: number;
  collectCount: number;
  liked: boolean;
  collected: boolean;
  createTime?: string;
}

/** 与后端 AnswerVO JSON 对齐 */
export interface AnswerVO {
  answerId?: number | null;
  questionId?: number | null;
  userId?: number | null;
  nickName?: string | null;
  avatarUrl?: string | null;
  quantaBatch?: string | null;
  content?: string | null;
  likeCount?: number | null;
  commentCount?: number | null;
  isAccepted?: number | null;
  createTime?: string | null;
  /** Mock 或后端扩展：当前用户是否已赞 */
  isLiked?: boolean | null;
}

/** 专业区回答列表展示模型 */
export interface AnswerModel {
  answerId: number;
  questionId: number;
  /** 答主用户 ID；缺失时不可跳转主页 */
  authorId?: number;
  authorName: string;
  authorAvatar?: string;
  quantaBatch?: string;
  content: string;
  likeCount: number;
  commentCount: number;
  isAccepted: boolean;
  /** 当前用户是否已赞（Mock 或后端扩展） */
  liked?: boolean;
  createTime?: string;
}

/** 对应后端 AnswerDTO */
export interface AnswerPublishPayload {
  questionId: number;
  content: string;
}
