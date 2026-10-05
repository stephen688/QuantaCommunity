package com.quanta.demo0.feed.service;

import com.quanta.demo0.feed.dto.RecommendQueryDTO;
import com.quanta.demo0.platform.common.result.ScrollResult;
import com.quanta.demo0.feed.dto.RecommendVisitor;
import com.quanta.demo0.feed.vo.RecommendPageVO;

/** Feed 推荐流查询端口。 */
public interface FeedQueryService {

    ScrollResult recommend(RecommendQueryDTO recommendQueryDTO);

    /** 增量推荐协议；旧请求或热度场景保留原分页，访客由HTTP门面认证。 */
    RecommendPageVO recommend(RecommendQueryDTO query, RecommendVisitor visitor);
}
