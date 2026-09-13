package com.quanta.demo0.config;

import com.quanta.demo0.properties.RecommendProperties;
import com.quanta.demo0.service.ContentExposureService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import static com.quanta.demo0.constant.RedisConstants.RECOMMEND_ALL_KEY;
import static com.quanta.demo0.constant.RedisConstants.RECOMMEND_HOT_ALL_KEY;

/**
 * 应用启动后检查推荐流 Redis；若为空则从 MySQL 已通过内容预热。
 * <p>
 * 解决：dev-seed 只写 MySQL、Redis 重启/清空后推荐页为空的问题。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RecommendFeedInitializer implements ApplicationRunner {

    private final RecommendProperties recommendProperties;
    private final ContentExposureService contentExposureService;
    private final StringRedisTemplate stringRedisTemplate;

    @Override
    public void run(ApplicationArguments args) {
        if (!recommendProperties.isWarmupOnStartup()) {
            log.info("[Recommend] warmup-on-startup=false，跳过推荐流预热");
            return;
        }


        Long allSize = stringRedisTemplate.opsForZSet().zCard(RECOMMEND_ALL_KEY);
        Long hotSize = stringRedisTemplate.opsForZSet().zCard(RECOMMEND_HOT_ALL_KEY);
        long existingAll = allSize != null ? allSize : 0L;
        long existingHot = hotSize != null ? hotSize : 0L;
        if (existingAll > 0 && existingHot > 0) {
            log.info("[Recommend] Redis 推荐流已有 latest={} hot={} 条，跳过预热", existingAll, existingHot);
            return;
        }

        log.warn("[Recommend] Redis 推荐流不完整（latest={} hot={}），开始从 MySQL 预热...", existingAll, existingHot);
        int warmed = contentExposureService.warmUpRecommendRedisFromDb(
                recommendProperties.getWarmupBatchSize());
        Long after = stringRedisTemplate.opsForZSet().zCard(RECOMMEND_ALL_KEY);
        log.info("[Recommend] 预热完成：写入 {} 条，Redis content:recommend:all 当前 {} 条",
                warmed, after != null ? after : 0);
    }
}
