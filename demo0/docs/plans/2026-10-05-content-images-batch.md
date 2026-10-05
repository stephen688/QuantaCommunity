# 帖子图片批量查询实施计划

**目标：** 普通搜索、关注流和 RAG 搜索装配结果时，每批非空帖子只执行一次图片查询，空批次不查图片。

**依据：** `../后续demo0优化总方案.md` 的图片批量查询条目及本次用户确认的三处调用范围。

**架构：** 调用方收集帖子 ID，通过 content 域公开 Service 查询；Mapper 用参数化 IN 查询，按 content_id、sort 返回；Service 按帖子 ID 分组，调用方保持原结果顺序。

**边界：** 不改 HTTP 契约、ES 查询、分页、作者缓存、互动状态、审核、缓存或 MQ；无数据库迁移及新增依赖。单帖详情和评论查询保留。展示口径过滤空白 URL，RAG 事实口径保留空值和重复项。保留工作区其他修改。

## 一个整体任务：批量能力与三个入口接入

生产文件（相对于 demo0）：
- `src/main/java/com/quanta/demo0/content/mapper/ContentMapper.java`
- `src/main/resources/mapper/content/ContentMapper.xml`
- `src/main/java/com/quanta/demo0/content/service/ContentQueryService.java`
- `src/main/java/com/quanta/demo0/content/service/impl/ContentQueryServiceImpl.java`
- `src/main/java/com/quanta/demo0/search/service/impl/ContentSearchServiceImpl.java`
- `src/main/java/com/quanta/demo0/feed/service/impl/FollowFeedServiceImpl.java`
- `src/main/java/com/quanta/demo0/rag/generation/RagSearchService.java`

接口：
```java
Map<Long, List<String>> getContentImageUrlsBatch(Collection<Long> contentIds);
Map<Long, List<String>> getContentFactImageUrlsBatch(Collection<Long> contentIds);
List<ContentImage> selectImagesBatchByContentIds(@Param("contentIds") List<Long> contentIds);
```

SQL：
```sql
SELECT content_id, image_url, sort FROM tb_content_image
WHERE content_id IN (/* MyBatis foreach 使用 #{contentId} */)
ORDER BY content_id, sort
```

- [x] 先补定向测试并确认缺失批量能力或原有逐条调用导致失败；检查多帖多图、不串图、空图、两种 URL 口径及查询次数。
- [x] 一次实现底层能力及三个调用入口；不在转换单条 VO 的方法中查图片。
- [x] 统一运行涉及的少量测试，执行一次真实 MySQL 的 Mapper 查询验证参数绑定、分组与 sort 顺序；不执行完整测试套件或额外压测。
- [x] 一次独立审查，修复确认的问题，回写真实验证结果；无 Critical/Important，审查完成后已更新本项状态。

**回滚：** 只撤销上述七个生产文件和本任务新增测试/文档的改动；无需数据库回滚。后续用户已授权重启、提交及推送，交付状态见 RESULTS。

**实测：** 2026-10-05，四个定向测试类共 11 项通过；本地 MyBatis + MySQL 临时表 smoke 通过。后续授权后从本轮暂存内容构建成功，已本地重启 9191 并验证非空 HTTP 搜索及 traceId 回传；未复测 P95。详见 `../api-test/RESULTS.md` S-IMAGE-BATCH。
