# Elasticsearch 8 Java Client 原位迁移 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 在不改变现有搜索、RAG、索引校准、Outbox/Inbox 与 HTTP 契约的前提下，将 `demo0` 从 Elasticsearch 7.12.1 `RestHighLevelClient` 原位迁移到 Elasticsearch 8 官方 `ElasticsearchClient`。

**Architecture:** 保留现有 `config -> es/initializer -> es/service -> 既有调用方` 结构，只替换 ES 客户端装配和请求/响应 API；`ElasticSearchService` 接口、`ContentDocument`/`AnswerDocument`、索引名、mapping、查询语义和失败策略均保持不变。MySQL 继续是事实源，搜索索引仍由现有 SearchReconcile Outbox/Inbox 链路最终校准，不引入 Spring Data Repository、双写框架、新索引别名或额外管理接口。

**Tech Stack:** Java 17、Spring Boot 3.5.11、Elasticsearch 8.x、`co.elastic.clients:elasticsearch-java`、Maven、JUnit 5、Mockito、现有 RabbitMQ Outbox/Inbox 与 API 测试集。

**Spec:** `docs/后续demo0优化总方案.md` §4；本计划同时落实用户约束：“按之前 ES7 框架替换，不乱加东西；Task 不拆太小；单元测试只覆盖核心链路，其余验证在大步骤完成后集中执行”。

## Global Constraints

- 只迁移 Elasticsearch 客户端与兼容代码，不改变 HTTP 路径、`Result`、`PageVO`、`ContentVO`、RAG 返回模型和鉴权语义。
- 保留现有索引名 `content`、`answer`；不趁机改用未被当前实现采用的 `EsIndexConstant.CONTENT_INDEX`，不创建 `v2` 索引、alias 或新表。
- 保留现有 mapping：字段、分片/副本数、日期格式、`ik_max_word`/`ik_smart` 分词器全部不变；ES8 运行环境必须预装与服务端版本匹配的 IK 插件。
- 保留现有连接语义：继续读取 `spring.elasticsearch.host/port/scheme`，使用 HTTP 无认证连接；不新增用户名、密码、证书和 TLS 配置。若实际 ES8 节点返回 401、要求 HTTPS 或证书，停止执行并向用户确认，不自行扩大安全配置范围。
- 客户端版本不在项目中手写 patch 版本；删除旧 `<elasticsearch.version>`，由 Spring Boot 3.5.11 BOM 管理 `elasticsearch-java`，ES8 服务端使用与 Maven effective version 相同的 minor 线。
- 不引入 `spring-boot-starter-data-elasticsearch`、Spring Data Repository、新的 ES 抽象层、MapStruct、额外 JSON 库或新的全局配置体系。
- MySQL 是事实源；SearchReconcile 仍按 MySQL 当前状态执行 upsert/delete，Outbox/Inbox、消息字段、队列、ACK、重试和死信语义不变。
- 单元测试只新增一个 `ElasticSearchServiceImplTest`，覆盖可见性判定、请求关键语义、bulk 失败上抛和 RAG 回答降级；不为每个 getter、builder 或客户端方法单独建测试。
- 客户端装配、索引初始化、Service API 与核心单测合并为一个迁移 Task，避免产生“新依赖已切换、旧 Service 无法编译”的中间提交；全量测试与真实 ES8/API/MQ 验收只在 Task 2 集中执行。
- 本轮不做搜索功能翻新、相关度调参、分页方案改造、性能优化、向量库迁移、包结构重构和脚本清理。

---

## 1. 当前框架与迁移后对应关系

| ES7 当前实现 | ES8 原位替换 | 必须保持的行为 |
|---|---|---|
| `RestHighLevelClient` | `co.elastic.clients.elasticsearch.ElasticsearchClient` | 同步调用、异常向现有 Service 策略传播 |
| `RestClient.builder(HttpHost)` | 相同 low-level `RestClient` + `RestClientTransport` | 继续读取 host/port/scheme |
| `CreateIndexRequest.source(mappingJson)` | `CreateIndexRequest.Builder.withJson(StringReader)` | 原 mapping JSON 原样迁移 |
| `IndexRequest.source(...)` | `IndexRequest<Map<String,Object>>.document(source)` | `_id`、索引名和 `_source` 字段不变 |
| `BulkRequest` | `BulkRequest` + `BulkOperation` | 同批 upsert/delete、任一 item 失败即整批失败 |
| `SearchSourceBuilder`/`QueryBuilders` | Java API Client typed DSL | multi_match、filter、排序、高亮、分页不变 |
| `SearchHit#getSourceAsMap` | `Hit<Map>` + 兼容字段转换 | 返回实体、score、highlight、total 及 ES7 存量日期兼容不变 |
| `ElasticsearchStatusException 404` | `DeleteResponse.result()==NotFound` 或客户端 404 异常 | 删除不存在文档仍视为幂等成功 |

### 1.1 不可改变的索引与查询契约

内容索引 `content`：

- `_id = contentId`；只索引 `isDeleted=0 && auditStatus=1` 的 MySQL 当前记录，否则删除 ES 文档。
- 搜索字段：`title`、`content`；`multi_match` 类型 `best_fields`，`fuzziness=AUTO`。
- 可选过滤 `contentType`，固定过滤 `isDeleted=0`、`auditStatus=1`。
- 排序：`_score desc`，再按 `createTime desc`。
- 高亮：`title`、`content`，标签固定 `<em>...</em>`。
- `from=(current-1)*pageSize`；保留 10000 `max_result_window` 防深分页行为。

回答索引 `answer`：

- `_id = answerId`；回答本身或父问题不可见时删除 ES 文档。
- 搜索字段：`questionTitle`、`answerContent`；`multi_match best_fields + fuzziness=AUTO`。
- 固定过滤 `isDeleted=0`、`auditStatus=1`，按 `_score desc` 返回 `topK`。
- ES 异常继续记录日志并返回空列表，让 RAG 保留现有“ES 一路失败、向量路继续”的降级语义。

### 1.2 保持不动的上层边界

- `ElasticSearchService` 的 8 个公开方法签名不变。
- `ContentServiceImpl.searchContent()`、`EsRecallService.recall()` 不改调用方式。
- `SearchReconcileServiceImpl`、`SearchReconcileConsumer`、`SearchReconcileProducer` 和消息模型不改。
- 内容/回答发布、审核、点赞、删除产生 SearchReconcile 事件的写路径不改。
- `application.yml` 的 ES 配置键不改；`scripts/sync_es_bulk.py` 和 `scripts/verify_integrations.py` 只有真实 ES8 验收证明不兼容时才停下向用户确认，本计划不预先修改。

---

## 2. 文件清单

新增：

- `src/test/java/com/quanta/demo0/es/service/ElasticSearchServiceImplTest.java`

修改：

- `pom.xml`
- `src/main/java/com/quanta/demo0/config/ElasticsearchConfig.java`
- `src/main/java/com/quanta/demo0/es/initializer/ElasticsearchIndexInitializer.java`
- `src/main/java/com/quanta/demo0/es/service/ElasticSearchServiceImpl.java`
- `src/main/java/com/quanta/demo0/rag/retrieval/EsRecallService.java`（只更新已经过时的客户端说明）
- `docs/api-test/RESULTS.md`（仅写真实执行结果）
- `docs/后续demo0优化总方案.md`（全部验收完成后回写第 4 项状态）

明确不修改：

- `src/main/java/com/quanta/demo0/es/service/ElasticSearchService.java`
- `src/main/java/com/quanta/demo0/es/document/ContentDocument.java`
- `src/main/java/com/quanta/demo0/es/document/AnswerDocument.java`
- `src/main/java/com/quanta/demo0/constant/EsIndexConstant.java`
- Controller、DTO、VO、Mapper/XML、RabbitMQ 配置、Outbox/Inbox 和小程序/管理端代码。

---

### Task 1: 一次性替换 ES8 客户端、初始化器和现有 Service

**Files:**
- Modify: `demo0/pom.xml`
- Modify: `demo0/src/main/java/com/quanta/demo0/config/ElasticsearchConfig.java`
- Modify: `demo0/src/main/java/com/quanta/demo0/es/initializer/ElasticsearchIndexInitializer.java`
- Modify: `demo0/src/main/java/com/quanta/demo0/es/service/ElasticSearchServiceImpl.java`
- Modify: `demo0/src/main/java/com/quanta/demo0/rag/retrieval/EsRecallService.java`
- Create: `demo0/src/test/java/com/quanta/demo0/es/service/ElasticSearchServiceImplTest.java`

**Interfaces:**
- Produces: 可编译、可测试的 `ElasticsearchClient` Bean 与完整 ES8 Service 实现。
- Preserves: `spring.elasticsearch.host/port/scheme`、索引名、mapping JSON、`ElasticSearchService` 公开方法、查询语义和初始化失败只告警不阻断应用启动的行为。

- [ ] **Step 1: 建立迁移前基线并确认 BOM 版本**

在 `demo0/` 执行：

```powershell
mvn -DskipTests compile
mvn -q help:evaluate '-Dexpression=elasticsearch-client.version' -DforceStdout
```

记录编译退出码和 BOM 管理的 `8.x` 客户端版本；若第一条命令因本任务外已有改动失败，记录原始错误，不通过调整 ES 范围外代码掩盖。

- [ ] **Step 2: 用官方 Java API Client 替换旧依赖**

从 `pom.xml` 删除：

```xml
<elasticsearch.version>7.12.1</elasticsearch.version>
```

以及：

```xml
<dependency>
    <groupId>org.elasticsearch.client</groupId>
    <artifactId>elasticsearch-rest-high-level-client</artifactId>
    <version>${elasticsearch.version}</version>
</dependency>
```

改为不写版本号、交给 Spring Boot BOM 管理：

```xml
<dependency>
    <groupId>co.elastic.clients</groupId>
    <artifactId>elasticsearch-java</artifactId>
</dependency>
```

不增加 Spring Data Elasticsearch starter。

- [ ] **Step 3: 在原 `ElasticsearchConfig` 中装配新客户端**

继续使用当前三个 `@Value` 字段，不引入新的 Properties 类。Bean 关系固定为：

```java
@Bean(destroyMethod = "")
public RestClient elasticsearchRestClient() {
    return RestClient.builder(new HttpHost(host, port, scheme)).build();
}

@Bean(destroyMethod = "close")
public ElasticsearchTransport elasticsearchTransport(
        RestClient elasticsearchRestClient,
        ObjectMapper objectMapper
) {
    return new RestClientTransport(
            elasticsearchRestClient,
            new JacksonJsonpMapper(objectMapper)
    );
}

@Bean
public ElasticsearchClient elasticsearchClient(ElasticsearchTransport transport) {
    return new ElasticsearchClient(transport);
}
```

`RestClientTransport` 作为连接资源所有者在 Spring 关闭时释放 transport 和底层 client；`ObjectMapper` 必须复用 Spring Boot 已配置实例，确保 `LocalDateTime` 序列化规则不另起一套。

- [ ] **Step 4: 原位迁移索引初始化器**

保留 `run()` 与 `createAnswerIndexIfNotExists()` 的结构、两个 mapping 文本和 warn-and-continue 策略，只替换调用：

```java
boolean exists = client.indices()
        .exists(request -> request.index(INDEX_NAME))
        .value();

client.indices().create(request -> request
        .index(INDEX_NAME)
        .withJson(new StringReader(mappingJson))
);
```

回答索引同样使用 `ANSWER_INDEX_NAME` 与原回答 mapping。禁止修改 analyzer、字段、日期 format、分片/副本配置，也不合并或改名两个索引。

- [ ] **Step 5: 确认客户端切换后的预期 RED**

```powershell
mvn -DskipTests compile
```

Expected: 编译会因 `ElasticSearchServiceImpl` 仍引用旧 API 而失败，失败点必须只落在本 Task Phase B 待迁移的旧 `org.elasticsearch.*` 类型；若配置/初始化器自身有新 API 编译错误，先修正后再进入 Phase B。

这是同一个 Task 内的短暂 RED 状态，不提交；禁止为了中间编译通过同时保留新旧两个高层客户端，直接继续下面 Phase B 完成 Service 迁移。

---

#### Task 1 Phase B: 迁移现有 ES Service，集中补核心单元测试

**Files:**
- Create: `demo0/src/test/java/com/quanta/demo0/es/service/ElasticSearchServiceImplTest.java`
- Modify: `demo0/src/main/java/com/quanta/demo0/es/service/ElasticSearchServiceImpl.java`
- Modify: `demo0/src/main/java/com/quanta/demo0/rag/retrieval/EsRecallService.java`

**Interfaces:**
- Consumes: 本 Task Phase A 的 `ElasticsearchClient` Bean。
- Preserves: `ElasticSearchService` 全部公开签名、MySQL 可见性判定、批量计数、异常/降级策略和上层调用方式。

- [ ] **Step 6: 一次性写完核心测试并确认 RED**

创建一个测试类，使用 `@ExtendWith(MockitoExtension.class)`、`@Mock ElasticsearchClient`、`@Mock ContentMapper`、`@Mock QuestionMapper`、`@InjectMocks ElasticSearchServiceImpl`。只包含以下四组核心行为：

```java
@Test
void upsertContentShouldIndexVisibleRowAndDeleteInvisibleRow() throws Exception {
    // auditStatus=1 且 isDeleted=0：捕获 IndexRequest，断言 index=content、id=contentId、
    // _source 仍含 contentId/contentType/title/content/publishUserId/auditStatus/
    // liked/collectCount/commentCount/createTime/isDeleted。
    // auditStatus!=1 或 MySQL 无记录：断言走 DeleteRequest，不发送 IndexRequest。
}

@Test
void bulkFailureShouldRemainVisibleToSearchReconcileRetry() throws Exception {
    // Mapper 返回一个可见帖子；BulkResponse.errors()=true 且 item.error()!=null。
    // 断言 upsertBatchByContentIds 抛异常，不能吞掉失败或伪装成功。
}

@Test
void searchContentShouldPreserveQueryPagingHighlightAndScore() throws Exception {
    // SearchResponse 返回一个 ContentDocument、total=1、score=2.5，
    // highlight.title/content 各有一段；断言 Page total/current/pageSize、
    // Content 的高亮文本和 esSearchScore，并捕获 SearchRequest 校验：
    // index=content、from/size、bool must/filter、两级排序和两个高亮字段仍存在。
}

@Test
void answerSearchFailureShouldKeepRagDegradedEmptyResult() throws Exception {
    // ElasticsearchClient.search 抛 IOException；断言 searchAnswers 返回空列表。
    // 另用不可见父问题调用 upsertByAnswerId，断言删除 answer 文档而非索引。
}
```

为了让请求可直接捕获，生产实现继续采用“先构造 request，再调用 client”的现有风格，例如：

```java
IndexRequest<Map<String, Object>> request = IndexRequest.of(builder -> builder
        .index(INDEX_NAME)
        .id(String.valueOf(contentId))
        .document(source)
);
client.index(request);
```

运行：

```powershell
mvn -Dtest=ElasticSearchServiceImplTest test
```

Expected: RED，原因是 `ElasticSearchServiceImpl` 尚未改为新客户端或新请求类型，而不是测试 fixture 自身语法错误。

- [ ] **Step 7: 原位迁移单条写入、删除与 bulk**

将注入字段替换为 `ElasticsearchClient`，保留现有 Mapper 和方法结构。写入 `_source` 使用私有 `LinkedHashMap<String,Object>` 构造，字段集合严格等于 ES7 代码；不要直接序列化整个 `AnswerDocument`，避免把仅用于返回的 `esSearchScore` 写进索引。

单条内容/回答使用 typed `IndexRequest<Map<String,Object>>`；删除使用 `DeleteRequest`，并同时处理：

```java
DeleteResponse response = client.delete(request);
if (response.result() == Result.NotFound) {
    // 保持删除不存在文档为幂等成功
    return;
}
```

若索引本身不存在导致客户端抛出 HTTP 404，也按当前删除幂等语义处理；其他异常继续按各方法原有异常类型向上抛。

bulk 使用同一个请求混合 index/delete：

```java
BulkRequest.Builder bulk = new BulkRequest.Builder();
bulk.operations(operation -> operation.index(index -> index
        .index(INDEX_NAME)
        .id(String.valueOf(contentId))
        .document(source)
));
bulk.operations(operation -> operation.delete(delete -> delete
        .index(INDEX_NAME)
        .id(String.valueOf(contentId))
));
BulkResponse response = client.bulk(bulk.build());
```

`response.errors()==true` 时汇总 `response.items()` 中非空 `error()` 的 reason，保持“任一 item 失败则本次失败”，让现有 SearchReconcile Inbox 进入重试；`reindexAllFromMySql` 的 total/success/failure/completed 计算口径不变。

- [ ] **Step 8: 原位迁移内容搜索与回答搜索**

内容搜索构建 `SearchRequest`，查询结构固定为：

```java
Query query = BoolQuery.of(bool -> {
    bool.must(must -> must.multiMatch(multi -> multi
            .query(keyword)
            .fields("title", "content")
            .type(TextQueryType.BestFields)
            .fuzziness("AUTO")));
    if (contentType != null) {
        bool.filter(filter -> filter.term(term -> term.field("contentType").value(contentType)));
    }
    bool.filter(filter -> filter.term(term -> term.field("isDeleted").value(0)));
    bool.filter(filter -> filter.term(term -> term.field("auditStatus").value(1)));
    return bool;
})._toQuery();
```

请求继续设置 `from`、`size`、`_score desc`、`createTime desc` 和 `<em>` 高亮。调用：

```java
SearchResponse<Map> response = client.search(request, Map.class);
```

从 `Hit<Map>.source()` 读取原字段，保留 ES7 对 `LocalDateTime`、UTC `...Z` 和 `epoch_millis` 的兼容解析；从 `hit.highlight()` 覆盖 title/content，从 `hit.score()` 写入 `Content.esSearchScore`；total 读取 `response.hits().total().value()`，为空时仍记录告警并按 0 处理。

回答搜索同样使用 `SearchResponse<Map>` 与兼容字段转换，保持原 query/filter/topK/排序与失败返回空列表语义；score 继续从 hit 写入 `AnswerDocument.esSearchScore`。

- [ ] **Step 9: 清除旧客户端引用并更新过时说明**

删除 `ElasticSearchServiceImpl` 中所有 `org.elasticsearch.action.*`、旧 QueryBuilders、旧 SearchHit/HighlightField/SortOrder 导入；`EsRecallService` 注释把“调用 RestHighLevelClient.search()”改为“通过 ElasticSearchService 调用 ElasticsearchClient”，不改运行代码。

检查：

```powershell
rg -n "RestHighLevelClient|RequestOptions|SearchSourceBuilder|QueryBuilders|org\.elasticsearch\.action" src/main/java
```

Expected: 无匹配；low-level `org.elasticsearch.client.RestClient` 只允许出现在 `ElasticsearchConfig`。

- [ ] **Step 10: 集中运行核心单测和编译**

```powershell
mvn -Dtest=ElasticSearchServiceImplTest,ConsumerReliabilityTests test
mvn -DskipTests compile
```

Expected: 新增核心测试全绿；原 `ConsumerReliabilityTests` 继续证明重复 SearchReconcile 事件仍由 Inbox 幂等处理，且 `ElasticSearchService` 调用次数不变；编译退出码为 0。

- [ ] **Step 11: 以一个可编译提交交付完整客户端迁移**

```powershell
git add demo0/pom.xml demo0/src/main/java/com/quanta/demo0/config/ElasticsearchConfig.java demo0/src/main/java/com/quanta/demo0/es/initializer/ElasticsearchIndexInitializer.java demo0/src/main/java/com/quanta/demo0/es/service/ElasticSearchServiceImpl.java demo0/src/main/java/com/quanta/demo0/rag/retrieval/EsRecallService.java demo0/src/test/java/com/quanta/demo0/es/service/ElasticSearchServiceImplTest.java
git commit -m "refactor(search): migrate elasticsearch client to es8"
```

---

### Task 2: 在真实 ES8 上集中回归核心链路并收口文档

**Files:**
- Modify: `demo0/docs/api-test/RESULTS.md`
- Modify: `demo0/docs/后续demo0优化总方案.md`

**Interfaces:**
- Consumes: Task 1 完整代码、现有 `content`/`answer` 索引、SearchReconcile Outbox/Inbox、搜索与 RAG API。
- Produces: 可复核的 ES8 版本、插件、索引、写入、删除、搜索、RAG 降级和全量测试证据。

- [ ] **Step 1: 验证真实 ES8 运行前提，不改仓库连接模型**

当前仓库默认地址为 `http://192.168.100.128:9200`。在启动应用前执行：

```powershell
$esUri = 'http://192.168.100.128:9200'
Invoke-RestMethod -Uri "$esUri/"
Invoke-RestMethod -Uri "$esUri/_cat/plugins?format=json"
```

必须确认：

1. 返回的 `version.number` major 为 8，minor 与 Maven effective `elasticsearch-client.version` 相同；
2. 插件列表包含与该 ES8 patch 兼容的 IK analyzer；
3. 当前 HTTP 无认证连接可用。

若返回 ES7、401、HTTPS/证书错误或缺少 IK 插件，状态记录为 `BLOCKED` 并向用户确认环境处理方式；禁止通过删除 analyzer、改 standard 分词器、在代码里硬编码账号密码来绕过。

- [ ] **Step 2: 在空的开发 ES8 索引上验证初始化和 mapping**

只允许使用确认过的开发节点。优先使用新建空 ES8 实例；若需要删除旧索引，必须先再次核对地址不是生产环境，只操作 `content`、`answer` 两个明确索引。

启动 `demo0`：

```powershell
mvn spring-boot:run
```

启动完成后检查：

```powershell
Invoke-RestMethod -Uri "$esUri/_cat/indices/content,answer?format=json"
Invoke-RestMethod -Uri "$esUri/content/_mapping"
Invoke-RestMethod -Uri "$esUri/answer/_mapping"
```

Expected: 两个索引均存在；mapping 字段、IK analyzer、date format、1 shard/0 replica 与 ES7 定义一致；应用日志无索引初始化异常。

- [ ] **Step 3: 用现有业务链路验证 upsert、搜索、RAG 和 delete**

不新增测试接口，使用现有 `.http` 用例和 SearchReconcile 链路：

1. 执行 `01-auth-user.http` 获取用户/管理员 Token；
2. 执行 `02-upload-publish.http` 的 P2-04/P2-05 发布专业帖和生活帖；
3. 执行 `02-admin-audit.http` 对测试帖子审核通过；
4. 等待 `SEARCH_RECONCILE` Outbox 变为 `SENT`、对应 Inbox 变为 `SUCCESS`；
5. 执行 `04-write.http` P5-04 发布回答，再调用现有 `POST /admin/answer/audit` 审核通过，等待回答索引校准成功；
6. 执行 `03-read.http` P4-05，断言搜索命中测试帖子、分页 total 正确、标题或正文高亮仍带 `<em>`；
7. 执行 `03-read.http` P4-10（`enableAi=false`），断言 ES 帖子/回答召回仍进入现有 RAG 候选，score 非空；
8. 对测试帖子或回答执行现有驳回/删除路径，等待新的 SearchReconcile Inbox `SUCCESS`，再查询 ES，断言文档不存在；重复投递同一事件，断言仍只有一次有效结果且删除不存在文档不会让消费者进入 DEAD。

验证必须同时保存 HTTP 结果、ES 命中、Outbox、Inbox 四段证据；只看到 HTTP 200 不能判定 ES8 迁移通过。

- [ ] **Step 4: 在大步骤末尾统一运行回归**

先确认 Docker 可用，再运行：

```powershell
docker info
mvn test
```

随后只人工执行受影响的搜索/RAG 用例：`03-read.http` P4-05、P4-06、P4-09、P4-10、P4-11，以及回答 SearchReconcile 的通过/删除场景。不扩大到小程序、管理端 UI 或 QuantaBot 人格评测。

结果分层记录：

- 编译与核心单测；
- Maven 全量回归；
- ES8 版本/IK/索引健康；
- SearchReconcile MQ 真链路；
- 搜索 API；
- RAG `enableAi=false`；
- 未执行或被环境阻塞的项目。

- [ ] **Step 5: 回写唯一结果与总方案状态**

仅把实际执行数据写入 `docs/api-test/RESULTS.md`，包括 ES/client 版本、命令退出码、测试数量、测试业务 ID、Outbox/Inbox eventId、索引文档 ID、搜索/RAG 结果与 BLOCKED 项。

只有以下条件全部满足时，才在 `docs/后续demo0优化总方案.md` §4 标记 ES8 升级完成：

- 旧高层客户端依赖和 imports 清零；
- 核心单测、全量 Maven 回归通过；
- 真实 ES8 + IK 初始化成功；
- 内容与回答 upsert/search/delete 通过；
- SearchReconcile 重投幂等通过；
- P4-05 与 P4-10 通过。

否则按证据写 `PARTIAL` 或 `BLOCKED`，不得只因代码编译成功就宣称升级完成。

- [ ] **Step 6: 最终自查并提交验收记录**

```powershell
git diff --check -- demo0
rg -n "RestHighLevelClient|elasticsearch-rest-high-level-client|<elasticsearch.version>7\.12\.1</elasticsearch.version>" demo0
git status --short
```

Expected: `git diff --check` 通过；旧客户端扫描无结果；状态中只包含本计划文件与本任务明确修改的文件，用户已有其他改动保持不动。

```powershell
git add demo0/docs/api-test/RESULTS.md demo0/docs/后续demo0优化总方案.md
git commit -m "docs(search): record elasticsearch 8 migration evidence"
```

---

## 3. 最终验收门禁

1. `pom.xml` 只保留 `co.elastic.clients:elasticsearch-java`，版本由 Spring Boot BOM 管理。
2. 主代码没有 `RestHighLevelClient`、`RequestOptions`、旧 Search/Query/Highlight API。
3. `ElasticSearchService` 方法签名、上层调用方、HTTP 契约和消息契约零变化。
4. `content`/`answer` 索引名、mapping、IK analyzer、日期格式和 `_source` 字段零变化。
5. 内容与回答的 MySQL 可见性判定、delete 幂等、bulk 失败上抛、RAG 回答搜索降级语义零变化。
6. 核心新增单测、原 Consumer 可靠性测试和 Maven 全量测试通过。
7. 真实 ES8 上完成初始化、upsert、bulk、search、highlight、answer recall、delete 与重复校准证据。
8. `RESULTS.md` 如实记录版本、eventId、文档 ID 和未完成项；总方案只有在所有门禁闭环后才标完成。

## 4. 回滚边界

- 代码回滚只需按 Task 2 -> Task 1 逆序回退提交；没有数据库 schema、HTTP、MQ 或消息格式迁移。
- ES 是派生数据，不反向覆盖 MySQL。回滚后重新连接 ES7 时，使用 MySQL 与现有 SearchReconcile 链路重建索引。
- ES8 验收使用独立开发实例或明确的开发索引；不在未确认环境执行通配删除、`_all` 删除或清空整个集群。
- 若新旧客户端搜索结果出现排序、highlight、total 或日期差异，先保留 ES7/ES8 对照证据并修正 API 映射；不得通过放宽断言或修改业务语义让结果“看起来能用”。

## 5. 计划自检

- 范围覆盖：依赖、客户端装配、索引初始化、单条/批量写入、重建、内容搜索、回答搜索、RAG、SearchReconcile、真实 ES8 和文档回写均有明确任务。
- 任务粒度：共 2 个大 Task；依赖/配置/初始化/Service/核心测试合并，真实依赖/全量回归/文档合并。
- 测试粒度：只新增 1 个核心单测类；Task 1 在完整客户端替换后集中跑定向测试，Task 2 才跑全量和真实链路。
- 无额外架构：未加入 Spring Data Repository、新接口、新索引策略、认证/TLS、向量检索或性能改造。
- 类型一致：生产注入类型统一为 `ElasticsearchClient`；搜索读取使用兼容 ES7 日期格式的精确字段 Map，写入也使用精确字段 Map 防止额外字段进入 `_source`。
