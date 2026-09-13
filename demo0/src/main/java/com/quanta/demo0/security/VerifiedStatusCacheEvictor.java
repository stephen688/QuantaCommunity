package com.quanta.demo0.security;

import com.quanta.demo0.constant.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 用户认证状态缓存失效器。
 *
 * 核心职责：
 * 1. 在数据库事务提交成功后删除认证状态缓存；
 * 2. 防止认证通过、驳回后继续读取旧缓存；
 * 3. 缓存删除失败时只记录告警，由缓存TTL兜底恢复。
 */
@Component
@Slf4j
public class VerifiedStatusCacheEvictor {

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    /**
     * 数据库事务提交成功后删除认证状态缓存。
     * 如果当前没有事务，则直接删除缓存。
     */
    public void evictAfterCommit(Long userId) {
        if (userId == null) {
            return;
        }

        /*
         * 当前存在真实数据库事务时，注册事务提交回调。
         *
         * 只有事务成功提交才会执行afterCommit；
         * 如果事务回滚，缓存不会被误删。
         */
        if (TransactionSynchronizationManager.isActualTransactionActive()
                && TransactionSynchronizationManager.isSynchronizationActive()) {

            TransactionSynchronizationManager.registerSynchronization(
                    new TransactionSynchronization() {
                        @Override
                        public void afterCommit() {
                            evict(userId);
                        }
                    }
            );

            return;
        }

        /*
         * 非事务场景下没有必要等待，
         * 直接删除缓存即可。
         */
        evict(userId);
    }

    /**
     * 执行实际的Redis缓存删除。
     */
    private void evict(Long userId) {
        String cacheKey =
                RedisConstants.SECURITY_VERIFIED_KEY + userId;

        try {
            Boolean deleted =
                    stringRedisTemplate.delete(cacheKey);

            log.info(
                    "用户认证状态缓存已失效，userId={}，deleted={}",
                    userId,
                    deleted
            );
        } catch (Exception exception) {
            /*
             * 此时数据库已经提交，不能再回滚业务结果。
             *
             * 删除失败只记录告警，
             * 最多等待5分钟TTL到期后自动恢复。
             */
            log.warn(
                    "用户认证状态缓存删除失败，userId={}，message={}",
                    userId,
                    exception.getMessage()
            );
        }
    }
}