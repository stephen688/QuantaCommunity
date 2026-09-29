package com.quanta.demo0.feed.service;

import com.quanta.demo0.feed.dto.RecommendQueryDTO;
import com.quanta.demo0.platform.common.result.ScrollResult;

/** Feed 推荐流查询端口。 */
public interface FeedQueryService {

    ScrollResult recommend(RecommendQueryDTO recommendQueryDTO);
}
