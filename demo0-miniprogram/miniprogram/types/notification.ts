import type { PageVO } from './api';

/** 与后端 NotificationVO 对齐 */
export interface NotificationVO {
  id?: number | null;
  actorUserId?: number | null;
  type?: string | null;
  typeDesc?: string | null;
  content?: string | null;
  payload?: string | null;
  isRead?: number | null;
  createTime?: string | null;
}

export type NotificationIconKind = 'system' | 'interact' | 'default';

export interface NotificationItemModel {
  id: number;
  type: string;
  typeDesc: string;
  content: string;
  read: boolean;
  createTime?: string;
  payload?: string;
  actorUserId?: number;
  iconKind: NotificationIconKind;
}

export type NotificationPage = PageVO<NotificationVO>;
