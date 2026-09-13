/** 与后端 LikeResultVO 对齐 */
export interface LikeResultVO {
  isLiked?: boolean | null;
  likedCount?: number | null;
}

/** 与后端 CollectResultVO 对齐 */
export interface CollectResultVO {
  isCollect?: boolean | null;
  collectCount?: number | null;
}
