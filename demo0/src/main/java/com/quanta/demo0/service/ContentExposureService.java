// d:/download/资料/day01/后端初始工程/demo0/src/main/java/com/quanta/demo0/service/ContentExposureService.java
package com.quanta.demo0.service;

import com.quanta.demo0.entity.Content;


/**
 * 内容曝光服务接口：包括写推荐流 Redis、写热度流 Redis、发 Feed、写 ES、写向量数据库
 * */

public interface ContentExposureService {

    /**
     * 内容审核通过后执行曝光
     * 包含：写推荐流 Redis、写热度流 Redis、发 Feed、写 ES、写向量
     */
    void exposeApprovedContent(Content content);

    /**
     * 内容被驳回/删除时清理曝光
     */
    void hideRejectedContent(Long contentId);

    /**
     * 推荐流 Redis 为空时，从 MySQL 已通过内容预热 latest/hot ZSET（不写 ES/MQ）。
     *
     * @return 写入 Redis 的内容条数
     */
    int warmUpRecommendRedisFromDb(int batchSize);
}