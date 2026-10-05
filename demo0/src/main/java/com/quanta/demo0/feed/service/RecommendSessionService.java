package com.quanta.demo0.feed.service;

import com.quanta.demo0.feed.dto.RecommendQueryDTO;
import com.quanta.demo0.feed.dto.RecommendVisitor;
import com.quanta.demo0.feed.vo.RecommendSessionPage;

/** 推荐发现边界：稳定页重放、扩召回与探索，不承担HTTP身份认证。 */
public interface RecommendSessionService {
    /** 按服务器校验过的访客及轮次返回一页；重试原游标不推进下一页。 */
    RecommendSessionPage page(RecommendVisitor visitor, RecommendQueryDTO query);
}
