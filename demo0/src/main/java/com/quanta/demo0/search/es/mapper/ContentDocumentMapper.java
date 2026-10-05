package com.quanta.demo0.search.es.mapper;

import co.elastic.clients.elasticsearch.ElasticsearchClient;
import co.elastic.clients.elasticsearch._types.ElasticsearchException;
import co.elastic.clients.elasticsearch._types.Result;
import co.elastic.clients.elasticsearch.core.BulkRequest;
import co.elastic.clients.elasticsearch.core.BulkResponse;
import co.elastic.clients.elasticsearch.core.DeleteRequest;
import co.elastic.clients.elasticsearch.core.DeleteResponse;
import co.elastic.clients.elasticsearch.core.IndexRequest;
import co.elastic.clients.elasticsearch.core.SearchResponse;
import com.quanta.demo0.search.es.document.ContentDocument;
import com.quanta.demo0.search.es.query.ElasticsearchQueryFactory;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 搜索域内容文档 Mapper。
 *
 * <p>只负责 content 索引的 ES 文档读写和序列化，不查询 MySQL，也不承载内容可见性规则。</p>
 *
 * ============================================================
 * 【为什么叫 Mapper？把它当"ES 文档的 DAO"来读】
 * ============================================================
 * 和 MyBatis 的 Mapper 对位：一个索引当一张"表"，index/bulk/delete/search
 * 分别是 insert/批量写/delete/select。它只认 ContentDocument 这一层的形状，
 * **MySQL 的事实与可见性判断都留在 ContentIndexServiceImpl**，本类不做业务
 * 决策 —— 这样 ES 换客户端/换索引名时业务层无感。
 *
 * ============================================================
 * 【文档 _id 为什么显式用 contentId，而不是让 ES 自动生成？】
 * ============================================================
 * 同一个 _id 重复 index 就是整篇覆盖，**这正是 upsert 幂等的根基**：
 * 事件重复投递、全量重建重跑都只是"再写一遍"，不会产生重复文档，
 * MySQL→ES 的同步因此天然可重放。
 */
@Component
@Slf4j
@RequiredArgsConstructor
public class ContentDocumentMapper {

    private static final String INDEX_NAME = "content";

    private final ElasticsearchClient client;
    private final ElasticsearchQueryFactory queryFactory;

    /**
     * 写入或覆盖一个内容文档。
     *
     * 【幂等】_id=contentId（见类注释），重复调用是覆盖写不是追加。
     */
    public void index(ContentDocument document) throws IOException {
        IndexRequest<Map<String, Object>> request = IndexRequest.of(builder -> builder
                .index(INDEX_NAME)
                .id(String.valueOf(document.getContentId()))
                .document(toSource(document)));
        client.index(request);
    }

    /**
     * 批量写入或删除内容文档。
     *
     * 【为什么用 bulk 而不是循环调 index()】一次 HTTP 往返携带全部操作，
     * 全量重建一批 1000 条时逐条写就是 1000 次请求。
     * 【坑】bulk 调用本身不抛业务错误：单条失败记在返回的 BulkResponse
     * items 里，调用方必须检查 response.errors()（见 ContentIndexServiceImpl）。
     * upsert 与 delete 可以混在同一个请求里，顺序按添加顺序执行。
     */
    public BulkResponse bulk(List<ContentDocument> upsertDocuments, List<Long> deleteIds) throws IOException {
        BulkRequest.Builder builder = new BulkRequest.Builder();
        for (ContentDocument document : upsertDocuments) {
            builder.operations(operation -> operation.index(index -> index
                    .index(INDEX_NAME)
                    .id(String.valueOf(document.getContentId()))
                    .document(toSource(document))));
        }
        for (Long id : deleteIds) {
            builder.operations(operation -> operation.delete(delete -> delete
                    .index(INDEX_NAME)
                    .id(String.valueOf(id))));
        }
        return client.bulk(builder.build());
    }

    /**
     * 删除内容文档；ES 中不存在时按幂等成功处理。
     *
     * 【为什么两种 404 都要接】删除一个不存在的文档，客户端可能拿到
     * result=NotFound 的正常响应，也可能收到 status=404 的 ElasticsearchException
     * （例如索引本身不存在时），两条路径都得当成"已删除"返回，否则同一
     * 删除事件重放一次就误报失败。
     */
    public void delete(Long contentId) {
        try {
            DeleteRequest request = DeleteRequest.of(builder -> builder
                    .index(INDEX_NAME)
                    .id(String.valueOf(contentId)));
            DeleteResponse response = client.delete(request);
            if (response.result() == Result.NotFound) {
                log.debug("ES 内容文档不存在，视为幂等成功，id={}", contentId);
            }
        } catch (ElasticsearchException e) {
            if (e.status() == 404) {
                log.debug("ES 内容文档不存在，视为幂等成功，id={}", contentId);
                return;
            }
            throw new IllegalStateException("ES 删除内容失败，id=" + contentId, e);
        } catch (Exception e) {
            throw new IllegalStateException("ES 删除内容异常，id=" + contentId, e);
        }
    }

    /**
     * 执行内容索引查询。
     *
     * 【职责切分】过滤/排序/高亮等查询语义都在 ElasticsearchQueryFactory
     * 里拼装，本方法只负责提交并按 Map 反序列化（字段解析在调用方做）。
     */
    @SuppressWarnings("rawtypes")
    public SearchResponse<Map> search(String keyword, Integer contentType, int from, int size) throws IOException {
        return client.search(queryFactory.contentSearch(keyword, contentType, from, size), Map.class);
    }

    /**
     * 组装 ES _source。字段名与 ElasticsearchIndexInitializer 里 mapping 的
     * properties 一一对应（camelCase），LinkedHashMap 保证写入顺序稳定、
     * 便于 Kibana 里排查。
     *
     * 【坑】新增字段要同时改三处：initializer 的 mapping、本方法、
     * ContentIndexServiceImpl.fromSource —— 漏掉 mapping 会落到动态映射，
     * 漏掉 fromSource 会查出 null。esSearchScore 刻意不写入（读时回填）。
     */
    private Map<String, Object> toSource(ContentDocument document) {
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("contentId", document.getContentId());
        source.put("contentType", document.getContentType());
        source.put("title", document.getTitle());
        source.put("content", document.getContent());
        source.put("publishUserId", document.getPublishUserId());
        source.put("auditStatus", document.getAuditStatus());
        source.put("liked", document.getLiked());
        source.put("collectCount", document.getCollectCount());
        source.put("commentCount", document.getCommentCount());
        source.put("createTime", document.getCreateTime());
        source.put("isDeleted", document.getIsDeleted());
        return source;
    }
}
