package com.quanta.demo0.service;

import com.quanta.demo0.user.vo.UserAuthInfoVO;

import java.util.Collection;
import java.util.Map;

/**
 * 作者公开资料的本地读缓存。
 *
 * <p>批量读取会先检查本地缓存，再将所有未命中 ID 合并成一次数据库查询，
 * 避免 Feed 装配退化为逐作者查询。</p>
 */
public interface AuthorProfileCache {

    UserAuthInfoVO get(Long userId);

    Map<Long, UserAuthInfoVO> getAll(Collection<Long> userIds);

    void evict(Long userId);
}
