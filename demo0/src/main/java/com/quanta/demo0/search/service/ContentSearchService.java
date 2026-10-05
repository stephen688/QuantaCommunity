package com.quanta.demo0.search.service;

import com.quanta.demo0.content.vo.ContentVO;
import com.quanta.demo0.platform.common.result.PageVO;
import com.quanta.demo0.search.dto.SearchDTO;

/**
 * 搜索域内容检索服务。
 *
 * <p>封装检索、作者快照与内容展示数据组装，不向 Controller 暴露搜索适配器。</p>
 */
public interface ContentSearchService {

    /**
     * 按关键词分页检索内容。
     *
     * <p>【边界】keyword 必填且 trim 后 ≤50 字，contentType 仅允许 1/2，
     * 违规抛 SearchFailedException；current/pageSize 缺省回落 1/10。
     * 返回的 ContentVO 是 ES 快照 + 作者资料缓存 + 图片 URL 的组装结果，
     * 其 isLiked/isCollected 恒为 false（列表页不做个性化状态）；
     * 可见性由索引写入侧保证（只写"已审核且未删除"的文档），查询时不再回 MySQL 复核。</p>
     */
    PageVO<ContentVO> searchContent(SearchDTO searchDTO);
}
