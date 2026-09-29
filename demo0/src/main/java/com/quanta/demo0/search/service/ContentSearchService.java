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

    PageVO<ContentVO> searchContent(SearchDTO searchDTO);
}
