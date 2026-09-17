"""内容源同步测试：契约形状（MockTransport）+ 合成样本 + 摄取编排。"""

import json

import httpx

from quanta_bot.infra.content_sync import (
    ContentSyncClient,
    FakeContentSource,
    SyncDoc,
    ingest_content,
)


class _MainServiceStub:
    """MainServiceClient 形状替身（只暴露 get_json——ContentSyncClient 只依赖这一个方法）。"""

    def __init__(self, pages: list[dict[str, object]]) -> None:
        self._pages = pages
        self.calls: list[dict[str, object]] = []

    async def get_json(self, path: str, params: dict[str, object] | None = None):
        self.calls.append({"path": path, "params": params})
        return self._pages.pop(0) if self._pages else {"list": [], "total": 0, "hasMore": False}


async def test_content_sync_client_contract_shape() -> None:
    """GET /bot/content/sync?since=&pageNum=&pageSize= → items + hasMore（[C-3/D6 联调校准点]）。"""
    stub = _MainServiceStub(
        [
            {
                "list": [
                    {
                        "docId": "d1",
                        "docKind": "POLICY",
                        "title": "奖助学金评审",
                        "content": "每年 9 月评审",
                        "updatedAt": "2026-09-01T00:00:00",
                    },
                    {
                        "docId": "d2",
                        "docKind": "POST",
                        "title": "选课讨论帖",
                        "content": "这门课给分不错",
                        "updatedAt": "2026-09-02T00:00:00",
                    },
                ],
                "total": 2,
                "hasMore": False,
            }
        ]
    )
    client = ContentSyncClient(stub)  # type: ignore[arg-type]
    docs, has_more = await client.fetch_sync(since="1970-01-01")
    assert stub.calls[0]["path"] == "/bot/content/sync"
    params = stub.calls[0]["params"]
    assert params is not None and params.get("since") == "1970-01-01"
    assert params.get("pageNum") == 1
    assert isinstance(docs, list) and len(docs) == 2
    first = docs[0]
    assert isinstance(first, SyncDoc)
    assert first.doc_id == "d1" and first.doc_kind == "POLICY"
    assert first.title == "奖助学金评审" and first.content == "每年 9 月评审"
    assert has_more is False


async def test_content_sync_client_accepts_authoritative_c3_shape() -> None:
    """C-3 权威字段可解析，并从 contentId/answerId 派生稳定 doc_id。"""
    stub = _MainServiceStub(
        [
            {
                "items": [
                    {
                        "contentId": 101,
                        "answerId": None,
                        "docKind": "POST",
                        "content": "选课讨论正文",
                        "createTime": "2026-09-01T00:00:00",
                        "updateTime": "2026-09-02T00:00:00",
                    },
                    {
                        "contentId": 101,
                        "answerId": 202,
                        "docKind": "ANSWER",
                        "content": "回答正文",
                        "createTime": "2026-09-01T00:00:00",
                        "updateTime": "2026-09-03T00:00:00",
                    },
                ],
                "hasMore": False,
            }
        ]
    )
    docs, has_more = await ContentSyncClient(stub).fetch_sync(since="1970-01-01")  # type: ignore[arg-type]

    assert has_more is False
    assert docs[0].content_id == 101
    assert docs[0].answer_id is None
    assert docs[0].create_time == "2026-09-01T00:00:00"
    assert docs[0].update_time == "2026-09-02T00:00:00"
    assert docs[0].updated_at == "2026-09-02T00:00:00"
    assert docs[0].doc_id == "content:101"
    assert docs[1].doc_id == "answer:202"


async def test_content_sync_client_single_page_semantics() -> None:
    """fetch_sync=单页语义（分页循环归 ingest_content 编排——计划 Interfaces 口径）。"""
    page = lambda n, docs: {"list": docs, "total": 4, "hasMore": n < 2}  # noqa: E731
    doc = lambda i: {  # noqa: E731
        "docId": f"d{i}",
        "docKind": "POST",
        "title": f"帖{i}",
        "content": f"内容{i}",
        "updatedAt": "2026-09-01T00:00:00",
    }
    stub = _MainServiceStub([page(1, [doc(1), doc(2)])])
    client = ContentSyncClient(stub)  # type: ignore[arg-type]
    docs, has_more = await client.fetch_sync(since="1970-01-01")
    assert len(stub.calls) == 1  # 单页语义：一次调用即返回
    assert [d.doc_id for d in docs] == ["d1", "d2"]
    assert has_more is True  # hasMore 原样透传（编排层据此决定继续）


async def test_fake_content_source_provides_synthetic_samples() -> None:
    """合成样本：≥3 条 POLICY + ≥5 条社区内容（合成测试数据——真实政策导入走 demo0 D6）。"""
    docs, _ = await FakeContentSource().fetch_sync(since="1970-01-01")
    kinds = {d.doc_kind for d in docs}
    assert "POLICY" in kinds and len(docs) >= 8
    assert sum(1 for d in docs if d.doc_kind == "POLICY") >= 3


class _FakeIndex:
    """摄取编排测试用计数索引（只实现 upsert_docs 被消费的形状）。"""

    def __init__(self) -> None:
        self.upserted: list[SyncDoc] = []

    async def upsert_docs(self, docs: list[SyncDoc]) -> int:
        self.upserted.extend(docs)
        return len(docs)


async def test_ingest_content_upserts_all() -> None:
    """摄取编排：源全量 → index.upsert_docs（条数一致）。"""
    index = _FakeIndex()
    count = await ingest_content(FakeContentSource(), index)  # type: ignore[arg-type]
    docs, _ = await FakeContentSource().fetch_sync(since="1970-01-01")
    assert count == len(docs) == len(index.upserted)


async def test_ingest_content_advances_page_num_until_complete() -> None:
    """hasMore=True 时递增 pageNum，不能重复摄取第一页。"""
    first_page = {
        "list": [
            {
                "docId": "page-1",
                "docKind": "POST",
                "title": "第一页",
                "content": "正文一",
                "updatedAt": "2026-09-01T00:00:00",
            }
        ],
        "hasMore": True,
    }
    second_page = {
        "list": [
            {
                "docId": "page-2",
                "docKind": "POST",
                "title": "第二页",
                "content": "正文二",
                "updatedAt": "2026-09-02T00:00:00",
            }
        ],
        "hasMore": False,
    }
    stub = _MainServiceStub([first_page, second_page])
    index = _FakeIndex()

    count = await ingest_content(ContentSyncClient(stub), index)  # type: ignore[arg-type]

    assert count == 2
    assert [doc.doc_id for doc in index.upserted] == ["page-1", "page-2"]
    assert [call["params"]["pageNum"] for call in stub.calls] == [1, 2]  # type: ignore[index]


async def test_sync_doc_aliases_accept_demo0_field_names() -> None:
    """SyncDoc 接受 demo0 驼峰别名（docId/docKind/updatedAt）——[D6 联调校准点] 宽容解析。"""
    raw = {
        "docId": "x1",
        "docKind": "ANSWER",
        "title": "回答",
        "content": "正文",
        "updatedAt": "2026-09-03T08:00:00",
    }
    parsed = SyncDoc.model_validate(json.loads(json.dumps(raw)))
    assert parsed.doc_id == "x1" and parsed.doc_kind == "ANSWER"


def _unused_httpx_guard() -> None:  # pragma: no cover
    """占位引用 httpx（MockTransport 直调示例留给 D6 联调；此处防未用导入漂移）。"""
    assert httpx is not None
