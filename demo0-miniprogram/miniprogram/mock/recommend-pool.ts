import { CONTENT_TYPE_LIFE, CONTENT_TYPE_PROFESSIONAL } from '../constants/content';
import type { ContentType } from '../constants/content';
import type { ContentVO } from '../types/content';
import type { RecommendScene } from '../types/feed';
import { mockPostImages } from './mock-images';

function life(id: number, title: string, extra?: Partial<ContentVO>): ContentVO {
  return {
    contentId: id,
    contentType: CONTENT_TYPE_LIFE,
    title,
    content: `【Mock 生活】附近有没有靠谱的${title.slice(0, 8)}…`,
    publishUserId: 8000 + (id % 4),
    nickName: `生活用户${(id % 4) + 1}`,
    avatarUrl: '',
    images: [],
    liked: 3 + (id % 30),
    collectCount: id % 12,
    commentCount: id % 20,
    createTime: `2026-05-${String(1 + (id % 10)).padStart(2, '0')} 10:${String(id % 60).padStart(2, '0')}:00`,
    ...extra,
  };
}

function pro(id: number, title: string, extra?: Partial<ContentVO>): ContentVO {
  return {
    contentId: id,
    contentType: CONTENT_TYPE_PROFESSIONAL,
    title,
    content: `【Mock 专业】关于「${title.slice(0, 12)}」的摘要说明，便于卡片多行截断展示。`,
    publishUserId: 9000 + (id % 3),
    nickName: `答主${(id % 3) + 1}`,
    avatarUrl: '',
    images: [],
    liked: 10 + (id % 80),
    collectCount: id % 25,
    commentCount: id % 40,
    answerCount: 1 + (id % 15),
    createTime: `2026-05-${String(1 + (id % 8)).padStart(2, '0')} 14:${String(id % 59).padStart(2, '0')}:00`,
    ...extra,
  };
}

const LIFE_POOL: ContentVO[] = [
  life(1001, '周末想带娃去露营，求装备清单', { images: mockPostImages(1001, 3) }),
  life(1002, '小区停水通知怎么看才靠谱'),
  life(1003, '新手做饭：电饭煲能做哪些简单菜', { images: mockPostImages(1003, 2) }),
  life(1004, '搬家前一周要准备什么', { images: mockPostImages(1004, 2) }),
  life(1005, '养猫家庭如何减少沙发粘毛', { images: mockPostImages(1005, 5) }),
  life(1006, '通勤一小时怎么利用碎片时间', { images: mockPostImages(1006, 1) }),
  life(1007, '求推荐性价比高的空气净化器'),
  life(1008, '老旧小区加装电梯要注意什么', { images: mockPostImages(1008, 4) }),
  life(1009, '亲子阅读书单（3-6岁）', { images: mockPostImages(1009, 2) }),
  life(1010, '南方梅雨季防潮经验', { images: mockPostImages(1010, 1) }),
  life(1011, '家庭药箱常备药有哪些'),
  life(1012, '周末短途自驾路线求推荐', { images: mockPostImages(1012, 3) }),
];

const PRO_POOL: ContentVO[] = [
  pro(2001, 'Spring Boot 全局异常处理怎么设计更稳', { images: mockPostImages(2001, 2) }),
  pro(2002, 'MySQL 索引失效的常见场景有哪些'),
  pro(2003, '小程序分包体积超限如何排查', { images: mockPostImages(2003, 1) }),
  pro(2004, 'JWT 刷新令牌与黑名单怎么权衡', { images: mockPostImages(2004, 3) }),
  pro(2005, 'REST 与 RPC 在团队里怎么选', { images: mockPostImages(2005, 5) }),
  pro(2006, '前端列表分页：游标与偏移各自适用场景', { images: mockPostImages(2006, 1) }),
  pro(2007, '日志脱敏有哪些业界实践'),
  pro(2008, '接口幂等性在下单场景如何实现', { images: mockPostImages(2008, 4) }),
  pro(2009, 'Docker 构建缓存命中率低怎么优化', { images: mockPostImages(2009, 2) }),
  pro(2010, '单元测试与集成测试边界怎么划', { images: mockPostImages(2010, 1) }),
];

/** 全部 Tab：生活与专业交错，便于一眼区分 */
const ALL_POOL: ContentVO[] = (() => {
  const out: ContentVO[] = [];
  const n = Math.max(LIFE_POOL.length, PRO_POOL.length);
  for (let i = 0; i < n; i += 1) {
    if (LIFE_POOL[i]) {
      out.push(LIFE_POOL[i]);
    }
    if (PRO_POOL[i]) {
      out.push(PRO_POOL[i]);
    }
  }
  return out;
})();

function scoreOf(item: ContentVO): number {
  return (item.liked ?? 0) * 3 + (item.commentCount ?? 0) * 2 + (item.collectCount ?? 0);
}

function sortByScene(pool: ContentVO[], scene: RecommendScene = 'latest'): ContentVO[] {
  const list = [...pool];
  if (scene === 'hot') {
    list.sort((a, b) => scoreOf(b) - scoreOf(a) || Number(b.contentId) - Number(a.contentId));
    return list;
  }
  list.sort((a, b) => Number(b.contentId) - Number(a.contentId));
  return list;
}

export function getRecommendMockPool(
  contentType?: ContentType,
  scene: RecommendScene = 'latest',
): ContentVO[] {
  if (contentType === CONTENT_TYPE_LIFE) {
    return sortByScene(LIFE_POOL, scene);
  }
  if (contentType === CONTENT_TYPE_PROFESSIONAL) {
    return sortByScene(PRO_POOL, scene);
  }
  return sortByScene(ALL_POOL, scene);
}

/** 按 contentId 查找 Mock 卡片（用于详情页与 USE_MOCK 联调） */
export function findMockContentById(contentId: number): ContentVO | undefined {
  if (!Number.isFinite(contentId)) {
    return undefined;
  }
  return getRecommendMockPool().find((c) => Number(c.contentId) === contentId);
}
