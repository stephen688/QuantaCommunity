package com.quanta.demo0.search.service.impl;

import com.github.pagehelper.Page;
import com.quanta.demo0.content.service.ContentQueryService;
import com.quanta.demo0.content.vo.ContentVO;
import com.quanta.demo0.platform.common.result.PageVO;
import com.quanta.demo0.platform.security.context.BaseContext;
import com.quanta.demo0.search.dto.SearchDTO;
import com.quanta.demo0.search.es.document.ContentDocument;
import com.quanta.demo0.search.entity.SearchHistory;
import com.quanta.demo0.search.exception.SearchFailedException;
import com.quanta.demo0.search.mapper.SearchMapper;
import com.quanta.demo0.search.service.ContentIndexService;
import com.quanta.demo0.search.service.ContentSearchService;
import com.quanta.demo0.user.service.AuthorProfileCache;
import com.quanta.demo0.user.vo.UserAuthInfoVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * 搜索域内容检索实现。
 *
 * <p>Elasticsearch 只负责召回；作者资料和图片通过各自领域服务补齐，
 * 搜索历史作为非关键副作用失败时仅记录日志。</p>
 *
 * ============================================================
 * 【为什么可见性过滤主要放在"写入时"而不是"查询时"？】
 * ============================================================
 * 真正的口径控制发生在索引写入侧：ContentIndexServiceImpl.upsertByContentId
 * 先查 MySQL 快照（ContentSnapshotVO），不满足 is_deleted=0 AND audit_status=1
 * 的内容直接删除 ES 文档而不是写入。于是 ES 里存在的文档天然都是
 * "已审核通过、未删除"的，查询链路（本类）不必再回 MySQL 逐条复核，
 * 搜索对 MySQL 的依赖只剩作者/图片补齐；ES 查询里的 isDeleted=0、
 * auditStatus=1 两个 filter（ElasticsearchQueryFactory.contentSearch）
 * 只是同步窗口期的兜底，防止脏数据漏出。**宁可写入时多判一次，不让查询时处处设防。**
 *
 * ============================================================
 * 【ES 列表与 MySQL 详情怎么分工？】
 * ============================================================
 * ES 文档（ContentDocument）只存列表页要展示的快照字段（标题、正文、各类计数），
 * 图片不在 ES 中——toVO 里每条结果再调 contentQueryService.getContentImageUrls
 * 回 MySQL 补图片 URL；用户点开详情则走 content 域的 getContentDetail
 * （内容详情多级缓存），不经过 ES。列表页默认每页 10 条，逐条补图代价可控。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class ContentSearchServiceImpl implements ContentSearchService {

    private final ContentIndexService contentIndexService;
    private final SearchMapper searchMapper;
    private final AuthorProfileCache authorProfileCache;
    private final ContentQueryService contentQueryService;

    /**
     * 校验搜索条件、执行分页检索并组装内容响应。
     *
     * <p>校验放在这里而不是 Controller：RAG 等其他调用方复用同一检索链路时，
     * 也能拿到相同的参数口径（关键词必填、trim 后 ≤50 字、contentType 仅 1/2）。</p>
     */
    @Override
    public PageVO<ContentVO> searchContent(SearchDTO searchDTO) {
        // 【入口校验】非法参数直接抛 SearchFailedException，由全局异常处理器统一转 Result，
        // 而不是静默修正——搜索词为空搜全表没有业务意义。
        if (searchDTO == null || StringUtils.isBlank(searchDTO.getKeyword())) {
            throw new SearchFailedException("搜索关键词不能为空");
        }
        String keyword = searchDTO.getKeyword().trim();
        if (keyword.length() > 50) {
            throw new SearchFailedException("搜索关键词不能超过50字");
        }
        if (searchDTO.getContentType() != null
                && searchDTO.getContentType() != 1
                && searchDTO.getContentType() != 2) {
            throw new SearchFailedException("内容类型必须为1或2");
        }

        // current/pageSize 缺省或非法时回落 1/10。SearchDTO 上的 @Builder.Default 只对
        // 代码里 Builder 创建的对象生效，HTTP GET 绑定走无参构造 + setter，仍需这里兜底。
        int current = searchDTO.getCurrent() == null || searchDTO.getCurrent() <= 0 ? 1 : searchDTO.getCurrent();
        int pageSize = searchDTO.getPageSize() == null || searchDTO.getPageSize() <= 0 ? 10 : searchDTO.getPageSize();
        Page<ContentDocument> page = contentIndexService.searchContent(
                keyword, searchDTO.getContentType(), current, pageSize);
        List<ContentDocument> contents = page.getResult();
        // 历史记录在拿到结果后写入：即使命中 0 条也记（"用户搜过这个词"与结果多少无关）。
        recordHistory(BaseContext.getCurrentId(), keyword);

        if (contents == null || contents.isEmpty()) {
            return PageVO.<ContentVO>builder()
                    .list(List.of())
                    .pageNum(current)
                    .pageSize(pageSize)
                    .total(page.getTotal())
                    .totalPage(0)
                    .hasMore(false)
                    .build();
        }

        // 【作者批量补齐】先把本页结果里的作者 ID 去重，再一次性查作者资料缓存，
        // 避免逐条结果单查一次作者；查不到的作者用空 UserAuthInfoVO 兜底——
        // 头像昵称显示为空可以接受，但不能让整条搜索结果因此丢失。
        List<Long> authorIds = contents.stream().map(ContentDocument::getPublishUserId).distinct().toList();
        Map<Long, UserAuthInfoVO> authors = authorProfileCache.getAll(authorIds);
        if (authors == null) {
            authors = Collections.emptyMap();
        }
        Map<Long, UserAuthInfoVO> authorMap = authors;
        List<ContentVO> contentVOList = contents.stream()
                .map(content -> toVO(content,
                        authorMap.getOrDefault(content.getPublishUserId(), new UserAuthInfoVO())))
                .toList();
        // PageHelper 的 Page 只带回 total + 本页数据，总页数与 hasMore 在这里手动换算，
        // 组装成平台统一的 PageVO 返回给前端。
        long total = page.getTotal();
        int totalPages = (int) Math.ceil(total / (double) pageSize);
        return PageVO.<ContentVO>builder()
                .list(contentVOList)
                .pageNum(current)
                .pageSize(pageSize)
                .total(total)
                .totalPage(totalPages)
                .hasMore(current < totalPages)
                .build();
    }

    /**
     * 写入一条搜索历史。
     *
     * <p>【非关键副作用】这是搜索主链路上的旁路写：失败只记 error 日志、不向上抛，
     * 保证"历史完整性 < 搜索可用性"。写策略是"同 (userId, keyword) 已存在则只刷新
     * create_time，否则插入新行"——一个用户一个关键词只占一行，
     * 也让 create_time 兼职"最近搜索时间"（详见 SearchHistory 类注释）。</p>
     */
    private void recordHistory(Long userId, String keyword) {
        try {
            SearchHistory existing = searchMapper.selectByUserIdAndKeyword(userId, keyword);
            if (existing != null) {
                searchMapper.updateSearchTime(userId, keyword, LocalDateTime.now());
            } else {
                searchMapper.insertSearchHistory(SearchHistory.builder()
                        .userId(userId)
                        .keyword(keyword)
                        .isDeleted(0)
                        .createTime(LocalDateTime.now())
                        .build());
            }
        } catch (Exception e) {
            log.error("记录搜索历史失败:userId={},keyword={}", userId, keyword, e);
        }
    }

    /**
     * ES 快照 + 作者资料 + 图片 URL 组装成列表页 ContentVO。
     *
     * <p>【取舍】isLiked/isCollected 恒为 false：列表页不做"我是否点赞/收藏过"的
     * 个性化判断（那是详情页与交互域的职责），避免列表查询被用户行为表拖慢。
     * 计数字段允许为 null（ES source 手工反序列化而来），统一归零展示。</p>
     */
    private ContentVO toVO(ContentDocument content, UserAuthInfoVO author) {
        return ContentVO.builder()
                .contentId(content.getContentId())
                .contentType(content.getContentType())
                .title(content.getTitle())
                .content(content.getContent())
                .liked(content.getLiked() == null ? 0 : content.getLiked())
                .commentCount(content.getCommentCount() == null ? 0 : content.getCommentCount())
                .collectCount(content.getCollectCount() == null ? 0 : content.getCollectCount())
                .publishUserId(content.getPublishUserId())
                .avatarUrl(author.getAvatarUrl())
                .nickName(author.getNickName())
                .quantaDepartment(author.getQuantaDepartment())
                .quantaBatch(author.getQuantaBatch())
                .auditStatus(content.getAuditStatus())
                .createTime(content.getCreateTime())
                .images(contentQueryService.getContentImageUrls(content.getContentId()))
                .isLiked(false)
                .isCollected(false)
                .build();
    }
}
