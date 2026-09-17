"""infra/content_sync —— RAG 内容源（C-3 改形契约：内容源同步消费归 bot）。

职责：ContentSyncClient（GET /bot/content/sync 增量拉社区内容——[C-3 契约形状，demo0 D6 落地后
      联调校准]）；FakeContentSource（合成样本——政策 3 条 + 社区帖 5 条，合成测试数据非真实政策；
      真实政策源=GDUFS_skill 仓库灌入，见 M3 计划 Task 13 注记）；ingest_content（拉全量 →
      index.upsert_docs 的摄取编排）。
边界：政策文档通道（POST /bot/knowledge/policy-docs）的推送方向属 demo0 侧运营录入，
      bot 侧 M3 只消费同步接口；定时摄取不在 M3（手动端点触发，自动化归 M5）。
"""

from collections.abc import Sequence
from typing import Literal, Protocol

from pydantic import BaseModel, ConfigDict, Field, model_validator

from quanta_bot.infra.main_service import MainServiceClient


class SyncDoc(BaseModel):
    """同步文档（C-3 契约形状；alias 兼容 demo0 驼峰字段——[D6 联调校准点]）。"""

    model_config = ConfigDict(populate_by_name=True)

    doc_id: str = Field(default="", alias="docId")
    doc_kind: Literal["POLICY", "POST", "ANSWER"] = Field(alias="docKind")
    content_id: int | str | None = Field(default=None, alias="contentId")
    answer_id: int | str | None = Field(default=None, alias="answerId")
    title: str = ""
    content: str = ""
    create_time: str = Field(default="", alias="createTime")
    update_time: str = Field(default="", alias="updateTime")
    updated_at: str = Field(default="", alias="updatedAt")

    @model_validator(mode="before")
    @classmethod
    def _derive_compatibility_fields(cls, value: object) -> object:
        """用 C-3 权威 id/time 字段补齐旧 SyncDoc 形状，保持 Qdrant 点 id 稳定。"""
        if not isinstance(value, dict):
            return value
        normalized = dict(value)
        if not normalized.get("docId") and not normalized.get("doc_id"):
            answer_id = normalized.get("answerId", normalized.get("answer_id"))
            content_id = normalized.get("contentId", normalized.get("content_id"))
            if answer_id is not None and answer_id != "":
                normalized["docId"] = f"answer:{answer_id}"
            elif content_id is not None and content_id != "":
                normalized["docId"] = f"content:{content_id}"
        if not normalized.get("updatedAt") and not normalized.get("updated_at"):
            normalized["updatedAt"] = normalized.get("updateTime") or normalized.get("update_time")
        return normalized

    @model_validator(mode="after")
    def _validate_id_and_update_time(self) -> "SyncDoc":
        """同步记录必须有可复现的点 id；旧字段缺失时沿用 C-3 update/create 时间。"""
        if not self.doc_id:
            raise ValueError("SyncDoc 缺少 docId、contentId 或 answerId")
        if not self.updated_at:
            self.updated_at = self.update_time or self.create_time
        return self


class ContentSource(Protocol):
    """内容源端口（真客户端 / 合成 fake 同形状——摄取编排不感知差异）。"""

    async def fetch_sync(
        self, since: str, page_size: int = 50, page_num: int = 1
    ) -> tuple[list[SyncDoc], bool]: ...


class ContentSyncClient:
    """demo0 内容同步真客户端（复用 MainServiceClient.get_json——鉴权/剥壳同源）。"""

    def __init__(self, main_service: MainServiceClient) -> None:
        self._main_service = main_service

    async def fetch_sync(
        self, since: str, page_size: int = 50, page_num: int = 1
    ) -> tuple[list[SyncDoc], bool]:
        """单页拉取（分页循环由 ingest_content 编排；本方法保持单页语义便于测）。"""
        data = await self._main_service.get_json(
            "/bot/content/sync",
            params={"since": since, "pageNum": page_num, "pageSize": page_size},
        )
        items = data.get("items", data.get("list", [])) if isinstance(data, dict) else []
        has_more = bool(data.get("hasMore", False)) if isinstance(data, dict) else False
        return [SyncDoc.model_validate(item) for item in items], has_more


class FakeContentSource:
    """合成内容源（合成测试数据，非真实政策；真实政策随 demo0 D6 / GDUFS_skill 灌入替换）。"""

    _SAMPLES: tuple[SyncDoc, ...] = (
        SyncDoc(
            doc_id="policy-1",
            doc_kind="POLICY",
            title="奖助学金评审流程",
            content="奖学金每年 9 月启动评审，10 月公示；助学金按学期申请，需提交家庭经济情况说明。",
            updated_at="2026-09-01T00:00:00",
        ),
        SyncDoc(
            doc_id="policy-2",
            doc_kind="POLICY",
            title="学分修读与免修",
            content="每学期修读不超过 30 学分；满足课程大纲要求的可申请免修，免修不免考。",
            updated_at="2026-09-01T00:00:00",
        ),
        SyncDoc(
            doc_id="policy-3",
            doc_kind="POLICY",
            title="社区版规",
            content="禁止广告引流与人身攻击；技术提问请带上下文；违规帖将被下沉或删除。",
            updated_at="2026-09-01T00:00:00",
        ),
        SyncDoc(
            doc_id="post-1",
            doc_kind="POST",
            title="考研还是就业",
            content="大三了很纠结，考研怕考不上，就业怕起点低，想听学长建议。",
            updated_at="2026-09-02T00:00:00",
        ),
        SyncDoc(
            doc_id="post-2",
            doc_kind="POST",
            title="高数期末怎么复习",
            content="历年题为主，错题本第二轮，重点章是级数和多元微分。",
            updated_at="2026-09-03T00:00:00",
        ),
        SyncDoc(
            doc_id="post-3",
            doc_kind="POST",
            title="保研边缘人自述",
            content="排名卡在保研线边缘，一边准备夏令营一边不敢落下专业课。",
            updated_at="2026-09-04T00:00:00",
        ),
        SyncDoc(
            doc_id="post-4",
            doc_kind="POST",
            title="实验室招新FAQ",
            content="招新笔试考基础算法，面试聊项目经历，大二大三都可报名。",
            updated_at="2026-09-05T00:00:00",
        ),
        SyncDoc(
            doc_id="post-5",
            doc_kind="POST",
            title="选课求指点",
            content="选修课在深度学习导论和数据库系统之间纠结，听说前者给分一般但干货多。",
            updated_at="2026-09-06T00:00:00",
        ),
    )

    async def fetch_sync(
        self, since: str, page_size: int = 50, page_num: int = 1
    ) -> tuple[list[SyncDoc], bool]:
        return list(self._SAMPLES), False


class ContentIndex(Protocol):
    """内容索引端口（QdrantContentIndex 实现；摄取编排只依赖 upsert_docs）。"""

    async def upsert_docs(self, docs: Sequence[SyncDoc]) -> int: ...


async def ingest_content(source: ContentSource, index: ContentIndex) -> int:
    """摄取编排：源全量（分页循环至 has_more=False）→ index.upsert_docs，返回总条数。"""
    all_docs: list[SyncDoc] = []
    since = "1970-01-01"  # 全量起点（增量水位线随 M5 定时摄取再引入）
    page_num = 1
    while True:
        docs, has_more = await source.fetch_sync(since, page_size=50, page_num=page_num)
        all_docs.extend(docs)
        if not has_more or not docs:
            break
        page_num += 1
    if not all_docs:
        return 0
    return await index.upsert_docs(all_docs)
