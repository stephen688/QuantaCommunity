import type { ContentType } from '../constants/content';

export type PublishContentType = ContentType;

export const PUBLISH_TITLE_MAX_LEN = 50;
export const PUBLISH_BODY_MAX_LEN = 500;
export const PUBLISH_IMAGE_MAX_COUNT = 5;

/** 发布表单 UI 状态 */
export interface PublishFormState {
  contentType: PublishContentType;
  title: string;
  content: string;
  images: string[];
  submitting: boolean;
  uploading: boolean;
  errorMessage: string;
}

/** 对应后端 ContentDTO */
export interface ContentPublishPayload {
  contentType: PublishContentType;
  title: string;
  content: string;
  images: string[];
}
