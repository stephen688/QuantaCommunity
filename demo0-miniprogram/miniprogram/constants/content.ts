/** 生活 */
export const CONTENT_TYPE_LIFE = 1 as const;
/** 专业 */
export const CONTENT_TYPE_PROFESSIONAL = 2 as const;
/** 前端筛选：全部（不传后端 contentType） */
export const CONTENT_TYPE_ALL = 'all' as const;

export type ContentType = typeof CONTENT_TYPE_LIFE | typeof CONTENT_TYPE_PROFESSIONAL;

export type ContentTypeFilter = typeof CONTENT_TYPE_ALL | ContentType;
