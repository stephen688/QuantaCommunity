package com.quanta.demo0.content.service;

import com.quanta.demo0.content.dto.ContentDTO;
import com.quanta.demo0.content.vo.ContentVO;

/**
 * 内容写入用例。
 *
 * <p>承载内容发布和作者删除事务；查询、互动与推荐由各自领域服务负责。</p>
 */
public interface ContentCommandService {

    ContentVO publish(ContentDTO contentDTO);

    void deleteContent(Long contentId);
}
