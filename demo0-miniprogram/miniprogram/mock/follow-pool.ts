import { CONTENT_TYPE_LIFE, CONTENT_TYPE_PROFESSIONAL } from '../constants/content';
import type { ContentType } from '../constants/content';
import type { ContentVO } from '../types/content';
import { mockPostImages } from './mock-images';

function life(id: number, title: string, extra?: Partial<ContentVO>): ContentVO {
  return {
    contentId: id,
    contentType: CONTENT_TYPE_LIFE,
    title,
    content: `【关注·生活】${title} — mock 动态摘要。`,
    publishUserId: 7001,
    nickName: '关注的好友A',
    avatarUrl: '',
    images: [],
    liked: 5 + (id % 18),
    collectCount: id % 9,
    commentCount: id % 14,
    createTime: `2026-05-06 09:${String(id % 55).padStart(2, '0')}:00`,
    ...extra,
  };
}

function pro(id: number, title: string, extra?: Partial<ContentVO>): ContentVO {
  return {
    contentId: id,
    contentType: CONTENT_TYPE_PROFESSIONAL,
    title,
    content: `【关注·专业】${title} — mock 问答摘要。`,
    publishUserId: 7002,
    nickName: '关注的好友B',
    avatarUrl: '',
    images: [],
    liked: 20 + (id % 60),
    collectCount: id % 11,
    commentCount: id % 22,
    answerCount: 2 + (id % 9),
    createTime: `2026-05-07 16:${String(id % 58).padStart(2, '0')}:00`,
    ...extra,
  };
}

/** 全部 Tab：有数据，用于与「生活分区空」对比 */
const FOLLOW_ALL: ContentVO[] = [
  life(5001, '好友刚发了条周末市集打卡', { images: mockPostImages(5001) }),
  pro(5002, '好友提问：TypeScript 严格模式迁移踩坑', { images: mockPostImages(5002) }),
  life(5003, '小区团购水果怎么挑'),
  pro(5004, '讨论：微服务拆分粒度'),
  life(5005, '求推荐好用的筋膜枪', { images: mockPostImages(5005) }),
  pro(5006, '缓存一致性：先删缓存还是先写库'),
  life(5007, '家庭收纳：鞋柜怎么规划'),
  pro(5008, '消息队列重复消费怎么处理', { images: mockPostImages(5008) }),
  life(5009, '阳台种菜入门'),
  pro(5010, 'OAuth2 与 OpenID Connect 区别'),
  life(5011, '新手跑步心率区间怎么控', { images: mockPostImages(5011, 2) }),
  pro(5012, '限流算法令牌桶如何实现'),
  life(5013, '露营帐篷怎么选'),
  pro(5014, 'ES 写入抖动如何排查', { images: mockPostImages(5014) }),
  life(5015, '宠物医院怎么选'),
];

/** 生活分区：空列表，用于演示「关注的人还没有生活内容」 */
const FOLLOW_LIFE: ContentVO[] = [];

const FOLLOW_PRO: ContentVO[] = [
  pro(5101, '关注动态：如何设计可观测性指标', { images: mockPostImages(5101) }),
  pro(5102, '关注动态：灰度发布要注意什么'),
  pro(5103, '关注动态：前后端字段契约怎么维护', { images: mockPostImages(5103) }),
  pro(5104, '关注动态：数据库连接池打满怎么应急'),
  pro(5105, '关注动态：小程序 setData 性能优化', { images: mockPostImages(5105) }),
  pro(5106, '关注动态：幂等键放在 header 还是 body'),
  pro(5107, '关注动态：日志链路 traceId 如何贯通', { images: mockPostImages(5107) }),
  pro(5108, '关注动态：多环境配置如何管理'),
];

export function getFollowMockPool(contentType?: ContentType): ContentVO[] {
  if (contentType === CONTENT_TYPE_LIFE) {
    return FOLLOW_LIFE;
  }
  if (contentType === CONTENT_TYPE_PROFESSIONAL) {
    return FOLLOW_PRO;
  }
  return FOLLOW_ALL;
}
