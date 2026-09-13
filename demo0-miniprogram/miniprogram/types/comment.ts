/** 与后端 CommentAddDTO 对齐 */
export interface CommentAddDTO {
  contentId: number;
  answerId?: number | null;
  parentId?: number | null;
  replyCommentId?: number | null;
  replyUserId?: number | null;
  content: string;
  imageUrls?: string[] | null;
}

/** 与后端 CommentPageDTO 对齐 */
export interface CommentPageDTO {
  contentId: number;
  answerId?: number | null;
  pageNum: number;
  pageSize: number;
  sortType?: number;
}

/** 与后端 ReplyPageDTO 对齐 */
export interface ReplyPageDTO {
  parentCommentId: number;
  contentId: number;
  answerId?: number | null;
  pageNum: number;
  pageSize: number;
  sortType?: number;
}

/** 后端 CommentPageVO.list 单条 Map 结构（见 CommentServiceImpl） */
export interface CommentRowVO {
  commentId?: unknown;
  userId?: unknown;
  contentId?: unknown;
  answerId?: unknown;
  parentId?: unknown;
  replyUserId?: unknown;
  content?: unknown;
  createTime?: unknown;
  likeCount?: unknown;
  replyCount?: unknown;
  isLiked?: unknown;
  avatarUrl?: unknown;
  nickName?: unknown;
  quantaBatch?: unknown;
  quantaDepartment?: unknown;
  replyAvatarUrl?: unknown;
  replyNickName?: unknown;
  replyCommentId?: unknown;
  replyQuantaDepartment?: unknown;
  replyQuantaBatch?: unknown;
  replyList?: unknown;
  isContentAuthor?: unknown;
  isAnswerAuthor?: unknown;
}

export interface CommentItemModel {
  commentId: number;
  userId: number;
  nickName: string;
  avatarUrl?: string;
  content: string;
  createTime?: string;
  likeCount: number;
  liked: boolean;
  replyCount?: number;
  /** 一级评论 id；二级回复与后端一致，指向所属一级 */
  parentId?: number;
  answerId?: number;
  /** 被回复的评论 id（二级回复时有值） */
  replyCommentId?: number;
  /** 被回复用户 id */
  replyUserId?: number;
  /** 被回复用户昵称，用于「A 回复 B」 */
  replyNickName?: string;
  replyAvatarUrl?: string;
  replies?: CommentItemModel[];
  /** 楼中楼：展开回复列表请求中 */
  replyLoading?: boolean;
  /** 楼中楼：回复列表加载失败，可重试 */
  replyLoadError?: boolean;
}

/** 与后端 CommentPageVO 对齐 */
export interface CommentPageVO {
  pageNum?: number | null;
  pageSize?: number | null;
  total?: number | null;
  hasMore?: boolean | null;
  list?: CommentRowVO[] | null;
}

/** 与后端 CommentReportDTO 对齐 */
export interface CommentReportPayload {
  commentId: number;
  /** 1-5 */
  reportType: number;
}
