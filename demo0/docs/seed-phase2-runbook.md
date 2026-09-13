# Seed Phase2 执行手册（seed-batch-20260526）

## 1. 生成阶段二文件

```bash
python scripts/generate_seed_phase2.py
```

生成文件：

- `src/main/resources/db/seed-batch-20260526.sql`
- `src/main/resources/db/seed-batch-20260526-verify.sql`
- `src/main/resources/db/seed-batch-20260526-rollback.sql`
- `scripts/seed-batch-20260526.manifest.json`

## 2. 执行 SQL 入库（全部待审）

```bash
mysql -h127.0.0.1 -P3306 -uroot -p demo < src/main/resources/db/seed-batch-20260526.sql
```

## 3. 验证待审状态

```bash
mysql -h127.0.0.1 -P3306 -uroot -p demo < src/main/resources/db/seed-batch-20260526-verify.sql
```

期望：

- `content_pending = 50`
- `answers_pending`、`comments_pending` 均大于 0
- audit_status 分组里仅有 `0`（在审核前）

## 4. 调用管理端接口批量审核通过

```bash
# 先 dry-run 看规模
python scripts/approve_seed_batch.py --dry-run

# 实际执行（需要管理员 token）
set ADMIN_TOKEN=你的管理员JWT
python scripts/approve_seed_batch.py --base-url http://127.0.0.1:9191
```

可选驳回演练：

```bash
python scripts/approve_seed_batch.py --reject --reject-reason "seed reject test"
```

## 5. 审核后验证

再次执行：

```bash
mysql -h127.0.0.1 -P3306 -uroot -p demo < src/main/resources/db/seed-batch-20260526-verify.sql
```

期望：`tb_content/tb_question_answer/tb_content_comment` 的分组中，`audit_status=1` 为主。

## 6. 回滚

```bash
mysql -h127.0.0.1 -P3306 -uroot -p demo < src/main/resources/db/seed-batch-20260526-rollback.sql
```

## 7. 小程序自测清单

- 生活区推荐流出现 `L01-L20` 对应标题，且评论入口正常
- 专业区 `Pxx` 帖子详情可见回答；审核通过后回答可见
- 点赞/收藏计数与详情页一致
- 审核通过后，管理端状态变化与 C 端可见性一致
- 回滚后这些标题不再在 C 端展示
