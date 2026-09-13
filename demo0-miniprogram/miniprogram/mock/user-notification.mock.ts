import type { NotificationVO } from '../types/notification';
import type { UserInfoVO } from '../types/user';

/**
 * 我的页 mock 用户；联调前可改字段验收缺省与展示。
 * 未读角标由 `getMockNotificationUnreadCount()` 根据通知列表 `isRead` 动态计算。
 */
export const MOCK_USER_INFO: UserInfoVO = {
  userId: 98009,
  nickname: '演示用户（Mock）',
  nickName: '演示用户（Mock）',
  avatarUrl: '',
  bio: '当前为静态数据阶段，将 USE_MOCK 设为 false 即可联调真实接口。',
  authStatus: 2,
  quantaDepartment: '前端部',
  quantaBatch: '2024届',
};

/** 通知列表 Mock（分页由 service 切片） */
export const MOCK_NOTIFICATION_LIST: NotificationVO[] = [
  {
    id: 501,
    type: 'LIKE_CONTENT',
    typeDesc: '赞了你的内容',
    content: '有人点赞了你的帖子《周末想带娃去露营》',
    isRead: 0,
    createTime: '2026-05-12 09:10:00',
    payload: '{"contentId":1001,"contentType":1}',
  },
  {
    id: 502,
    type: 'COMMENT_ON_CONTENT',
    typeDesc: '评论了你的内容',
    content: '用户「答主1」评论：同问，蹲一个详细步骤',
    isRead: 0,
    createTime: '2026-05-11 18:22:00',
    payload: '{"contentId":2001,"contentType":2}',
  },
  {
    id: 503,
    type: 'SYSTEM',
    typeDesc: '系统通知',
    content: '欢迎使用 demo0，可在「我的」完善资料与实名认证。',
    isRead: 1,
    createTime: '2026-05-10 12:00:00',
  },
  {
    id: 504,
    type: 'AUDIT_RESULT',
    typeDesc: '审核结果',
    content: '你发布的内容已通过审核',
    isRead: 1,
    createTime: '2026-05-09 08:30:00',
    payload: '{"contentId":1003}',
  },
  {
    id: 505,
    type: 'FOLLOW',
    typeDesc: '新增关注',
    content: '生活用户2 关注了你',
    isRead: 0,
    createTime: '2026-05-08 20:15:00',
  },
  {
    id: 506,
    type: 'COLLECT_CONTENT',
    typeDesc: '收藏了你的内容',
    content: '有人收藏了你的帖子《MySQL 索引失效的常见场景有哪些》',
    isRead: 1,
    createTime: '2026-05-07 16:40:00',
    payload: '{"contentId":2002,"contentType":2}',
  },
  {
    id: 507,
    type: 'SYSTEM',
    typeDesc: '活动提醒',
    content: '本周优质内容征集活动进行中，欢迎参与发布。',
    isRead: 0,
    createTime: '2026-05-06 10:05:00',
  },
  {
    id: 508,
    type: 'LIKE_CONTENT',
    typeDesc: '赞了你的内容',
    content: '有人点赞了你的帖子《南方梅雨季防潮经验》',
    isRead: 1,
    createTime: '2026-05-05 11:11:00',
    payload: '{"contentId":1010,"contentType":1}',
  },
  {
    id: 509,
    type: 'COMMENT_ON_CONTENT',
    typeDesc: '评论了你的内容',
    content: '用户「生活用户3」评论：感谢分享，已收藏',
    isRead: 0,
    createTime: '2026-05-04 19:50:00',
    payload: '{"contentId":1005,"contentType":1}',
  },
  {
    id: 510,
    type: 'SYSTEM',
    typeDesc: '安全提示',
    content: '请勿在站内透露支付密码等敏感信息。',
    isRead: 1,
    createTime: '2026-05-03 07:20:00',
  },
  {
    id: 511,
    type: 'AUDIT_RESULT',
    typeDesc: '审核结果',
    content: '你发布的内容需要修改后重新提交',
    isRead: 0,
    createTime: '2026-05-02 14:33:00',
  },
  {
    id: 512,
    type: 'LIKE_CONTENT',
    typeDesc: '赞了你的内容',
    content: '有人点赞了你的回答相关内容',
    isRead: 1,
    createTime: '2026-05-01 09:00:00',
  },
];

/**
 * 未读角标由列表动态计算，标记已读后角标会下降。
 * `MOCK_UNREAD_COUNT` 与列表中 `isRead !== 1` 条数保持一致，便于人工改数据时对照。
 */
export const MOCK_UNREAD_COUNT = 6;

/** Mock 未读数：与列表 `isRead` 联动，供自定义 TabBar 与通知页验收 */
export function getMockNotificationUnreadCount(): number {
  return MOCK_NOTIFICATION_LIST.reduce((n, x) => n + (x.isRead === 1 ? 0 : 1), 0);
}
