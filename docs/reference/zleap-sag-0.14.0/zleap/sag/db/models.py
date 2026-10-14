"""
SQLAlchemy ORM模型定义

所有数据库表的定义
"""

# pylint: disable=not-callable
# SQLAlchemy's func.now() is callable at runtime but Pylint doesn't recognize it

from datetime import datetime
from decimal import Decimal
from enum import StrEnum
from typing import Optional

from sqlalchemy import (
    CHAR,
    JSON,
    BigInteger,
    Boolean,
    DateTime,
    ForeignKey,
    Index,
    Integer,
    Numeric,
    String,
    Text,
    TypeDecorator,
    UniqueConstraint,
    false,
    func,
    or_,
    text,
    true,
)
from sqlalchemy.dialects.mysql import DATETIME as MYSQL_DATETIME
from sqlalchemy.dialects.mysql import LONGTEXT, MEDIUMTEXT
from sqlalchemy.orm import Mapped, mapped_column, relationship

from zleap.sag.db.base import Base
from zleap.sag.db.version import CURRENT_SCHEMA_VERSION

# ── 可移植列类型 ───────────────────────────────────────────────
# MySQL 保持专属类型(生成的 DDL 与现网逐字节一致,由 DDL 快照守卫);
# PostgreSQL / SQLite 回退到通用类型(TEXT / BYTEA / BLOB)。
_LongText = Text().with_variant(LONGTEXT(), "mysql")
_MediumText = Text().with_variant(MEDIUMTEXT(), "mysql")
_MicrosecondDateTime = DateTime().with_variant(MYSQL_DATETIME(fsp=6), "mysql")


class _UnpaddedChar(TypeDecorator):
    """CHAR(n) 列,读取时剥离尾部空格填充。

    PostgreSQL 的 bpchar 会把值补齐到声明宽度后返回,非 UUID 的短 ID
    (例如 ``sample-zleap-20260808_130847``)因此带上尾部空格。这类值被原样
    写入向量库的 text 列时,填充会固化下来,导致按 ``data_source_id`` 等值
    过滤检索不到任何行。MySQL 的 CHAR 不返回填充,此处统一兜住方言差异。

    DDL 仍逐字节等同于 CHAR(n),不影响 MySQL schema 快照。
    """

    impl = CHAR
    cache_ok = True

    def process_result_value(self, value, dialect):  # noqa: ARG002 - 签名由 SQLAlchemy 约定
        if isinstance(value, str):
            return value.rstrip(" ")
        return value


_Uuid = _UnpaddedChar(36)


class SAGSchemaMeta(Base):
    """SAG 关系 schema 的持久模式标记。"""

    __tablename__ = "sag_schema_meta"

    id: Mapped[int] = mapped_column(Integer, primary_key=True, autoincrement=False)
    storage_mode: Mapped[str] = mapped_column(String(16), nullable=False)
    schema_version: Mapped[int] = mapped_column(
        Integer,
        nullable=False,
        server_default=text(str(CURRENT_SCHEMA_VERSION)),
    )


class SAGOperation(Base):
    """Durable command ledger for process and delete operations."""

    __tablename__ = "sag_operation"

    operation_id: Mapped[str] = mapped_column(String(191), primary_key=True)
    data_source_id: Mapped[str] = mapped_column(String(191), nullable=False)
    source_id: Mapped[str | None] = mapped_column(String(191), nullable=True)
    operation_type: Mapped[str] = mapped_column(String(32), nullable=False)
    idempotency_key: Mapped[str] = mapped_column(String(191), nullable=False)
    request_digest: Mapped[str] = mapped_column(String(128), nullable=False)
    payload_digest: Mapped[str] = mapped_column(String(128), nullable=False)
    fence_scope: Mapped[str] = mapped_column(String(512), nullable=False)
    fence_token: Mapped[int] = mapped_column(BigInteger, nullable=False)
    status: Mapped[str] = mapped_column(String(32), nullable=False)
    checkpoint: Mapped[str] = mapped_column(String(64), nullable=False)
    generation_id: Mapped[str | None] = mapped_column(String(191), nullable=True)
    owner_id: Mapped[str | None] = mapped_column(String(191), nullable=True)
    lease_expires_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    heartbeat_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    attempts: Mapped[int] = mapped_column(Integer, nullable=False, server_default=text("0"))
    request_payload: Mapped[dict] = mapped_column(JSON, nullable=False)
    work_payload: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    result_payload: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    error_code: Mapped[str | None] = mapped_column(String(64), nullable=True)
    error_message: Mapped[str | None] = mapped_column(Text, nullable=True)
    retryable: Mapped[bool | None] = mapped_column(Boolean, nullable=True)
    cleanup_completed_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    cleanup_error: Mapped[str | None] = mapped_column(Text, nullable=True)
    created_time: Mapped[datetime] = mapped_column(
        DateTime, nullable=False, server_default=func.now()
    )
    updated_time: Mapped[datetime] = mapped_column(
        DateTime, nullable=False, server_default=func.now(), onupdate=func.now()
    )

    __table_args__ = (
        UniqueConstraint(
            "data_source_id",
            "idempotency_key",
            name="uq_sag_operation_data_source_id_idempotency_key",
        ),
        Index("idx_sag_operation_scope_status", "fence_scope", "status"),
        Index("idx_sag_operation_lease", "status", "lease_expires_at"),
        Index(
            "idx_sag_operation_cleanup",
            "status",
            "retryable",
            "cleanup_completed_at",
        ),
        Index(
            "idx_sag_operation_retention",
            "status",
            "retryable",
            "updated_time",
        ),
        Index("idx_sag_operation_source", "data_source_id", "source_id"),
    )


class SAGFence(Base):
    """Highest accepted fence token for one mutation scope."""

    __tablename__ = "sag_fence"

    fence_scope: Mapped[str] = mapped_column(String(512), primary_key=True)
    fence_token: Mapped[int] = mapped_column(BigInteger, nullable=False)
    operation_id: Mapped[str] = mapped_column(String(191), nullable=False)
    updated_time: Mapped[datetime] = mapped_column(
        DateTime, nullable=False, server_default=func.now(), onupdate=func.now()
    )

    __table_args__ = (Index("idx_sag_fence_operation", "operation_id"),)


class SAGDataSourceManifest(Base):
    """Visibility and revision record for one tenant/knowledge namespace."""

    __tablename__ = "sag_data_source_manifest"

    data_source_id: Mapped[str] = mapped_column(String(191), primary_key=True)
    revision: Mapped[int] = mapped_column(BigInteger, nullable=False, server_default=text("0"))
    active: Mapped[bool] = mapped_column(Boolean, nullable=False, server_default=true())
    deleted_time: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    created_time: Mapped[datetime] = mapped_column(
        DateTime, nullable=False, server_default=func.now()
    )
    updated_time: Mapped[datetime] = mapped_column(
        DateTime, nullable=False, server_default=func.now(), onupdate=func.now()
    )


class SAGSourceManifest(Base):
    """Authoritative active derived generation for one caller-owned source."""

    __tablename__ = "sag_source_manifest"

    data_source_id: Mapped[str] = mapped_column(String(191), primary_key=True)
    source_id: Mapped[str] = mapped_column(String(191), primary_key=True)
    source_type: Mapped[str] = mapped_column(String(32), nullable=False)
    active_generation_id: Mapped[str | None] = mapped_column(String(191), nullable=True)
    operation_id: Mapped[str] = mapped_column(String(191), nullable=False)
    fence_scope: Mapped[str] = mapped_column(String(512), nullable=False)
    fence_token: Mapped[int] = mapped_column(BigInteger, nullable=False)
    status: Mapped[str] = mapped_column(String(32), nullable=False)
    source_version: Mapped[str | None] = mapped_column(String(128), nullable=True)
    chunk_version: Mapped[str | None] = mapped_column(String(128), nullable=True)
    extract_version: Mapped[str | None] = mapped_column(String(128), nullable=True)
    chunk_count: Mapped[int] = mapped_column(Integer, nullable=False, server_default=text("0"))
    event_count: Mapped[int] = mapped_column(Integer, nullable=False, server_default=text("0"))
    revision: Mapped[int] = mapped_column(BigInteger, nullable=False, server_default=text("0"))
    pending_cleanup_generations: Mapped[list | None] = mapped_column(JSON, nullable=True)
    cleanup_required: Mapped[bool] = mapped_column(
        Boolean,
        nullable=False,
        server_default=false(),
    )
    cleanup_error: Mapped[str | None] = mapped_column(Text, nullable=True)
    cleanup_updated_at: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    deleted_time: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    created_time: Mapped[datetime] = mapped_column(
        DateTime, nullable=False, server_default=func.now()
    )
    updated_time: Mapped[datetime] = mapped_column(
        DateTime, nullable=False, server_default=func.now(), onupdate=func.now()
    )

    __table_args__ = (
        Index("idx_sag_source_manifest_status", "data_source_id", "status"),
        Index("idx_sag_source_manifest_generation", "active_generation_id"),
        Index("idx_sag_source_manifest_cleanup", "cleanup_required", "updated_time"),
    )


class SAGSearchSession(Base):
    """Bounded server-side result snapshot backing deterministic cursor pages."""

    __tablename__ = "sag_search_session"

    session_id: Mapped[str] = mapped_column(String(191), primary_key=True)
    request_digest: Mapped[str] = mapped_column(String(64), nullable=False)
    cursor_key: Mapped[str] = mapped_column(String(128), nullable=False)
    scope_digest: Mapped[str] = mapped_column(String(64), nullable=False)
    data_source_ids: Mapped[list] = mapped_column(JSON, nullable=False)
    source_ids: Mapped[list] = mapped_column(JSON, nullable=False)
    revision_payload: Mapped[dict] = mapped_column(JSON, nullable=False)
    result_payload: Mapped[dict] = mapped_column(JSON, nullable=False)
    result_kind: Mapped[str] = mapped_column(String(16), nullable=False)
    total: Mapped[int] = mapped_column(Integer, nullable=False)
    page_size: Mapped[int] = mapped_column(Integer, nullable=False)
    expires_at: Mapped[datetime] = mapped_column(DateTime, nullable=False)
    created_time: Mapped[datetime] = mapped_column(
        DateTime, nullable=False, server_default=func.now()
    )
    updated_time: Mapped[datetime] = mapped_column(
        DateTime, nullable=False, server_default=func.now(), onupdate=func.now()
    )

    __table_args__ = (Index("idx_sag_search_session_expiry", "expires_at"),)


class DataSource(Base):
    """信息源配置表"""

    __tablename__ = "data_source"

    # 主键：UUID
    id: Mapped[str] = mapped_column(_Uuid, primary_key=True)

    # 信息源基本信息
    name: Mapped[str] = mapped_column(String(100), nullable=False)
    description: Mapped[str | None] = mapped_column(String(255))

    # 时间戳
    created_time: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), nullable=False
    )
    updated_time: Mapped[datetime | None] = mapped_column(DateTime, onupdate=func.now())

    # 关系
    articles: Mapped[list["Article"]] = relationship(
        "Article",
        back_populates="data_source",
        cascade="all, delete-orphan",
    )
    source_events: Mapped[list["SourceEvent"]] = relationship(
        "SourceEvent",
        back_populates="data_source",
        cascade="all, delete-orphan",
    )
    entity_types: Mapped[list["EntityType"]] = relationship(
        "EntityType",
        back_populates="data_source",
        cascade="all, delete-orphan",
    )
    entities: Mapped[list["Entity"]] = relationship(
        "Entity",
        back_populates="data_source",
        cascade="all, delete-orphan",
    )
    conversations: Mapped[list["ChatConversation"]] = relationship(
        "ChatConversation",
        back_populates="data_source",
        cascade="all, delete-orphan",
    )

    def __repr__(self) -> str:
        return f"<DataSource(id={self.id}, name={self.name})>"


class KBDocument(Base):
    """知识库文档表（兼容主系统 kb_document 结构，当前不绑定外键）"""

    __tablename__ = "kb_document"
    __table_args__ = {"comment": "文档表"}

    id: Mapped[str] = mapped_column(String(191), primary_key=True)
    name: Mapped[str] = mapped_column(String(191), nullable=False, comment="文档名称")
    file_size: Mapped[int | None] = mapped_column(Integer, nullable=True, comment="文件大小")
    file_type: Mapped[str | None] = mapped_column(String(50), nullable=True, comment="文件类型")
    data_source_id: Mapped[str] = mapped_column(_Uuid, nullable=False, comment="信息源ID")
    doc_type: Mapped[str] = mapped_column(
        String(30), default="article", nullable=False, comment="文档类型"
    )
    uploader_id: Mapped[int | None] = mapped_column(Integer, nullable=True, comment="上传者ID")
    parse_status: Mapped[str | None] = mapped_column(
        String(36), nullable=True, comment="文档解析状态VAR36"
    )
    parse_task_id: Mapped[str | None] = mapped_column(
        String(191), nullable=True, comment="解析任务ID"
    )
    source_file_url: Mapped[str | None] = mapped_column(
        String(2000), nullable=True, comment="源文件地址"
    )
    pdf_url: Mapped[str | None] = mapped_column(String(2000), nullable=True, comment="pdf file url")
    md_file_url: Mapped[str | None] = mapped_column(
        String(2000), nullable=True, comment="markdown file url"
    )
    last_sync_time: Mapped[datetime | None] = mapped_column(
        DateTime, nullable=True, comment="上次开始事项同步时间"
    )
    parse_result_url: Mapped[str | None] = mapped_column(
        String(2000), nullable=True, comment="解析结果地址"
    )
    document_metadata: Mapped[dict | None] = mapped_column(JSON, nullable=True, comment="元数据")
    copied_from: Mapped[str | None] = mapped_column(
        String(191), nullable=True, comment="复制来源记录ID，记录此记录是从哪个document复制而来"
    )
    created_time: Mapped[datetime] = mapped_column(
        DateTime, default=func.now(), nullable=False, comment="创建时间"
    )
    updated_time: Mapped[datetime | None] = mapped_column(
        DateTime, onupdate=func.now(), nullable=True, comment="更新时间"
    )

    def __repr__(self) -> str:
        return f"<KBDocument(id={self.id}, name={self.name[:30]})>"


class ArticleParseStatus(StrEnum):
    """文章解析状态"""

    PENDING = "PENDING"
    PARSING = "PARSING"
    PARSED = "PARSED"
    EXTRACTING = "EXTRACTING"
    COMPLETED = "COMPLETED"
    PARSE_FAILED = "PARSE_FAILED"
    EXTRACTION_FAILED = "EXTRACTION_FAILED"
    FAILED = "FAILED"
    PENDING_RETRY_V2 = "PENDING_RETRY_V2"


class Article(Base):
    """文章表"""

    __tablename__ = "article"

    # 主键：UUID
    id: Mapped[str] = mapped_column(_Uuid, primary_key=True)

    # 信息源配置ID：UUID（外键）
    data_source_id: Mapped[str] = mapped_column(
        _Uuid,
        ForeignKey("data_source.id", ondelete="CASCADE", onupdate="CASCADE"),
        nullable=False,
        index=True,
    )

    # 基本信息
    title: Mapped[str] = mapped_column(String(500), nullable=False)
    document_id: Mapped[str | None] = mapped_column(
        String(191), nullable=True, comment="关联Document ID"
    )
    summary: Mapped[str | None] = mapped_column(Text)
    content: Mapped[str | None] = mapped_column(_LongText)

    # 分类和标签
    category: Mapped[str | None] = mapped_column(String(50), index=True)
    tags: Mapped[dict | None] = mapped_column(JSON)  # List[str]

    # 状态：PENDING, COMPLETED, FAILED, PROCESSING
    status: Mapped[str] = mapped_column(String(20), default="PENDING", nullable=False)
    parse_status: Mapped["ArticleParseStatus"] = mapped_column(
        String(66), default=ArticleParseStatus.PENDING, nullable=False, comment="解析状态"
    )

    sync_date: Mapped[datetime | None] = mapped_column(
        DateTime, nullable=True, comment="同步时间（提取事项）"
    )

    # 处理错误信息（失败时记录）
    error: Mapped[str | None] = mapped_column(Text)

    # 扩展数据：{"url": "", "headings": []}
    extra_data: Mapped[dict | None] = mapped_column(JSON)

    # 时间戳
    created_time: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), nullable=False
    )
    updated_time: Mapped[datetime | None] = mapped_column(DateTime, onupdate=func.now())

    # 关系
    data_source: Mapped["DataSource"] = relationship(
        "DataSource",
        back_populates="articles",
    )
    sections: Mapped[list["ArticleSection"]] = relationship(
        "ArticleSection",
        back_populates="article",
        cascade="all, delete-orphan",
    )
    source_events: Mapped[list["SourceEvent"]] = relationship(
        "SourceEvent",
        back_populates="article",
        cascade="all, delete-orphan",
    )
    entity_types: Mapped[list["EntityType"]] = relationship(
        "EntityType",
        back_populates="article",
        cascade="all, delete-orphan",
    )

    # 索引
    __table_args__ = (
        Index("idx_data_source_id", "data_source_id"),
        Index("idx_data_source_status", "data_source_id", "status"),
        Index("idx_article_document_id", "document_id"),
        Index("idx_category", "category"),
    )

    def __repr__(self) -> str:
        return f"<Article(id={self.id}, title={self.title[:30]})>"


class ArticleSection(Base):
    """文章片段表"""

    __tablename__ = "article_section"

    # 主键：UUID
    id: Mapped[str] = mapped_column(_Uuid, primary_key=True)

    # 文章ID：UUID（外键）
    article_id: Mapped[str] = mapped_column(
        _Uuid,
        ForeignKey("article.id", ondelete="CASCADE", onupdate="CASCADE"),
        nullable=False,
        index=True,
    )

    # 片段信息
    order_index: Mapped[int] = mapped_column(Integer, nullable=False, default=0, comment="排序索引")
    render_group_index: Mapped[int] = mapped_column(
        Integer, nullable=False, default=0, comment="渲染分组索引"
    )
    type: Mapped[str | None] = mapped_column(
        String(50), nullable=True, comment="片段类型：TEXT/IMAGE/CODE/TABLE等"
    )
    rank: Mapped[int] = mapped_column(Integer, nullable=False)
    heading: Mapped[str] = mapped_column(String(500), nullable=False)
    content: Mapped[str] = mapped_column(
        _LongText, nullable=False, comment="处理后的内容（纯文本）"
    )
    raw_content: Mapped[str | None] = mapped_column(
        _LongText, nullable=True, comment="原始内容（可能包含markdown/html）"
    )
    image_url: Mapped[str | None] = mapped_column(
        String(500), nullable=True, comment="图片URL（仅图片类型）"
    )
    length: Mapped[int] = mapped_column(Integer, nullable=False, default=0, comment="内容长度")
    generation_id: Mapped[str | None] = mapped_column(String(191), nullable=True)

    # 扩展数据：{"type": "TEXT|IMAGE|CODE", "length": 0}
    extra_data: Mapped[dict | None] = mapped_column(JSON)

    # 时间戳
    created_time: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), nullable=False
    )
    updated_time: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now(), nullable=False
    )

    # 关系
    article: Mapped["Article"] = relationship(
        "Article",
        back_populates="sections",
    )

    # 索引
    __table_args__ = (
        Index("idx_article_id", "article_id"),
        Index("idx_article_rank", "article_id", "rank"),
        Index("idx_article_order", "article_id", "order_index"),
        Index("idx_article_section_generation", "article_id", "generation_id"),
    )

    def __repr__(self) -> str:
        return f"<ArticleSection(id={self.id}, heading={self.heading[:30]})>"


class ChatConversation(Base):
    """聊天会话；Conversation 是唯一聊天来源载体。"""

    __tablename__ = "chat_conversation"
    __table_args__ = (Index("idx_source_last_msg", "data_source_id", "last_message_time"),)

    id: Mapped[str] = mapped_column(_Uuid, primary_key=True)
    data_source_id: Mapped[str] = mapped_column(
        _Uuid,
        ForeignKey("data_source.id", ondelete="CASCADE", onupdate="CASCADE"),
        nullable=False,
        index=True,
    )
    title: Mapped[str | None] = mapped_column(String(255), nullable=True)
    last_message_time: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    messages_count: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    parse_status: Mapped[str | None] = mapped_column(
        String(66), nullable=True, comment="同步状态（提取事项）"
    )
    sync_date: Mapped[datetime | None] = mapped_column(
        DateTime, nullable=True, comment="同步时间（提取事项）"
    )
    creator_id: Mapped[str | None] = mapped_column(
        String(100), nullable=True, index=True, comment="会话创建者 ID"
    )
    is_main: Mapped[bool] = mapped_column(
        Boolean,
        default=False,
        server_default=false(),
        nullable=False,
        index=True,
        comment="是否为主会话：true=创建者与 Agent 的私聊控制面板",
    )
    source_id: Mapped[str | None] = mapped_column(
        String(191), nullable=True, comment="关联文档ID（kb_document.id）"
    )
    extra_data: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    created_time: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), nullable=False
    )
    updated_time: Mapped[datetime | None] = mapped_column(
        DateTime, onupdate=func.now(), nullable=True
    )
    del_flag: Mapped[int] = mapped_column(
        Integer,
        default=0,
        server_default=text("'0'"),
        nullable=False,
        comment="删除标志（0：正常；1：已删除）",
    )
    delete_time: Mapped[datetime | None] = mapped_column(
        DateTime, nullable=True, comment="删除时间"
    )

    data_source: Mapped["DataSource"] = relationship("DataSource", back_populates="conversations")
    messages: Mapped[list["ChatMessage"]] = relationship(
        "ChatMessage", back_populates="conversation", cascade="all, delete-orphan"
    )


class ChatMessage(Base):
    """聊天消息。"""

    __tablename__ = "chat_message"
    __table_args__ = (
        Index("idx_conv_timestamp", "conversation_id", "timestamp"),
        Index("idx_sender_id", "sender_id"),
        Index("idx_type", "type"),
        Index("idx_del_flag", "del_flag"),
        Index("idx_chat_message_generation", "conversation_id", "generation_id"),
    )

    id: Mapped[str] = mapped_column(_Uuid, primary_key=True)
    conversation_id: Mapped[str] = mapped_column(
        _Uuid,
        ForeignKey("chat_conversation.id", ondelete="CASCADE", onupdate="CASCADE"),
        nullable=False,
        index=True,
    )
    timestamp: Mapped[datetime] = mapped_column(_MicrosecondDateTime, nullable=False)
    content: Mapped[str | None] = mapped_column(_LongText, nullable=True)
    raw_content: Mapped[str | None] = mapped_column(
        _LongText, nullable=True, comment="原始消息内容"
    )
    resource_url: Mapped[str | None] = mapped_column(
        String(1000), nullable=True, comment="资源链接"
    )
    sender_id: Mapped[str | None] = mapped_column(String(100), nullable=True)
    sender_name: Mapped[str | None] = mapped_column(String(100), nullable=True)
    sender_avatar: Mapped[str | None] = mapped_column(String(255), nullable=True)
    sender_title: Mapped[str | None] = mapped_column(String(50), nullable=True)
    type: Mapped[str] = mapped_column(String(50), default="TEXT", nullable=False)
    sender_role: Mapped[str] = mapped_column(String(50), default="USER", nullable=False)
    reply_message_id: Mapped[str | None] = mapped_column(
        _Uuid, nullable=True, comment="回复消息的id"
    )
    group_id: Mapped[str | None] = mapped_column(
        String(100), nullable=True, comment="聚合消息主消息id"
    )
    start_time: Mapped[int | None] = mapped_column(
        Integer, nullable=True, comment="音频会话开始时间(ms)"
    )
    end_time: Mapped[int | None] = mapped_column(
        Integer, nullable=True, comment="音频会话结束时间(ms)"
    )
    generation_id: Mapped[str | None] = mapped_column(String(191), nullable=True)
    extra_data: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    created_time: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), nullable=False
    )
    updated_time: Mapped[datetime | None] = mapped_column(
        DateTime, onupdate=func.now(), nullable=True
    )
    del_flag: Mapped[int] = mapped_column(
        Integer,
        default=0,
        server_default=text("'0'"),
        nullable=False,
        comment="删除标志（0：正常；1：已删除）",
    )
    delete_time: Mapped[datetime | None] = mapped_column(
        DateTime, nullable=True, comment="删除时间"
    )

    conversation: Mapped["ChatConversation"] = relationship(
        "ChatConversation", back_populates="messages"
    )


class EntityType(Base):
    """实体类型定义表"""

    __tablename__ = "entity_type"

    # 主键：UUID
    id: Mapped[str] = mapped_column(_Uuid, primary_key=True)

    # 应用范围：global/source/article
    scope: Mapped[str] = mapped_column(
        String(20),
        nullable=False,
        server_default="global",
        index=True,
        comment="应用范围：global/source/article",
    )

    # 信息源配置ID：NULL表示系统默认类型（外键）
    data_source_id: Mapped[str | None] = mapped_column(
        _Uuid,
        ForeignKey("data_source.id", ondelete="CASCADE", onupdate="CASCADE"),
        nullable=True,
        index=True,
    )

    # 文档ID：仅 scope=article 时有值（外键）
    article_id: Mapped[str | None] = mapped_column(
        _Uuid,
        ForeignKey("article.id", ondelete="CASCADE", onupdate="CASCADE"),
        nullable=True,
        index=True,
        comment="文档ID（仅 scope=article 时有值）",
    )

    # 类型标识符：time, location, person等
    type: Mapped[str] = mapped_column(String(50), nullable=False)

    # 类型名称（显示名称）
    name: Mapped[str] = mapped_column(String(100), nullable=False)

    # 类型描述
    description: Mapped[str | None] = mapped_column(Text)

    # 默认权重（0.00-9.99）
    weight: Mapped[Decimal] = mapped_column(Numeric(3, 2), default=Decimal("1.00"), nullable=False)

    # 相似度匹配阈值（0.000-1.000）- 用于实体向量搜索和去重时的最低相似度要求
    similarity_threshold: Mapped[Decimal] = mapped_column(
        Numeric(4, 3), default=Decimal("0.800"), nullable=False
    )

    # 是否启用
    is_active: Mapped[bool] = mapped_column(Boolean, default=True, nullable=False)

    # 是否为系统默认类型
    is_default: Mapped[bool] = mapped_column(Boolean, default=False, nullable=False)

    # 值格式模板（如 "{number}{unit}"）
    value_format: Mapped[str | None] = mapped_column(String(100))

    # 值约束（JSON 格式，存储枚举列表、数值范围等）
    value_constraints: Mapped[dict | None] = mapped_column(JSON)

    # 扩展数据：{"extraction_prompt": "", "validation_rule": {}}
    extra_data: Mapped[dict | None] = mapped_column(JSON)

    # 时间戳
    created_time: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), nullable=False
    )
    updated_time: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now(), nullable=False
    )

    # 关系
    data_source: Mapped[Optional["DataSource"]] = relationship(
        "DataSource",
        back_populates="entity_types",
    )
    article: Mapped[Optional["Article"]] = relationship(
        "Article",
        back_populates="entity_types",
    )
    entities: Mapped[list["Entity"]] = relationship(
        "Entity",
        back_populates="entity_type",
    )

    # 唯一约束和索引
    __table_args__ = (
        Index(
            "uk_scope_data_source_article_type",
            "scope",
            "data_source_id",
            "article_id",
            "type",
            unique=True,
        ),
        Index("idx_default_active", "is_default", "is_active"),
    )

    def __repr__(self) -> str:
        return f"<EntityType(id={self.id}, type={self.type}, name={self.name})>"


class Entity(Base):
    """实体表（多对多关系：通过 event_entity 关联表与事项关联）"""

    __tablename__ = "entity"

    # 主键：UUID
    id: Mapped[str] = mapped_column(_Uuid, primary_key=True)

    # 信息源配置ID：UUID（外键）
    data_source_id: Mapped[str] = mapped_column(
        _Uuid,
        ForeignKey("data_source.id", ondelete="CASCADE", onupdate="CASCADE"),
        nullable=False,
        index=True,
    )

    # 实体类型ID：UUID（外键）
    entity_type_id: Mapped[str] = mapped_column(
        _Uuid,
        ForeignKey("entity_type.id", ondelete="RESTRICT", onupdate="CASCADE"),
        nullable=False,
        index=True,
    )

    # 类型标识符（冗余字段，便于查询）
    type: Mapped[str] = mapped_column(String(50), nullable=False)

    # 实体信息
    name: Mapped[str] = mapped_column(String(500), nullable=False)
    normalized_name: Mapped[str] = mapped_column(String(500), nullable=False, index=True)

    # 描述
    description: Mapped[str | None] = mapped_column(Text)

    # ========== 类型化值字段（用于统计分析） ==========

    # 值类型标识（int/float/datetime/bool/enum/text）
    value_type: Mapped[str | None] = mapped_column(String(20), index=True)

    # 原始提取文本（保留原始值，如 "199元"）
    value_raw: Mapped[str | None] = mapped_column(Text)

    # 整数值字段
    int_value: Mapped[int | None] = mapped_column(BigInteger, index=True)

    # 浮点数值字段（使用 DECIMAL 保证精度）
    float_value: Mapped[Decimal | None] = mapped_column(Numeric(20, 4), index=True)

    # 日期时间值字段
    datetime_value: Mapped[datetime | None] = mapped_column(DateTime, index=True)

    # 布尔值字段
    bool_value: Mapped[bool | None] = mapped_column(Boolean)

    # 枚举值字段
    enum_value: Mapped[str | None] = mapped_column(String(100), index=True)

    # 单位字段（如 "元", "美元", "公斤"）
    value_unit: Mapped[str | None] = mapped_column(String(50))

    # 解析置信度（0-1）
    value_confidence: Mapped[Decimal | None] = mapped_column(Numeric(5, 4))

    # 扩展数据：{"synonyms": [], "weight": 1.0, "confidence": 1.0}
    extra_data: Mapped[dict | None] = mapped_column(JSON)

    # 时间戳
    created_time: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), nullable=False
    )
    updated_time: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now(), nullable=False
    )

    # 关系
    data_source: Mapped["DataSource"] = relationship(
        "DataSource",
        back_populates="entities",
    )
    entity_type: Mapped["EntityType"] = relationship(
        "EntityType",
        back_populates="entities",
    )
    # 多对多关系：通过 event_entity 关联表
    event_associations: Mapped[list["EventEntity"]] = relationship(
        "EventEntity",
        back_populates="entity",
        cascade="all, delete-orphan",
    )

    # 唯一约束和索引
    __table_args__ = (
        Index("uk_data_source_type_name", "data_source_id", "type", "normalized_name", unique=True),
        Index("idx_data_source_id", "data_source_id"),
        Index("idx_entity_type_id", "entity_type_id"),
        Index("idx_normalized_name", "normalized_name"),
        Index("idx_data_source_type", "data_source_id", "type"),
        # 类型化值复合索引（用于统计查询）
        Index("ix_entity_type_value_type", "type", "value_type"),
        Index("ix_entity_data_source_value_type", "data_source_id", "value_type"),
    )

    def __repr__(self) -> str:
        return f"<Entity(id={self.id}, name={self.name}, type={self.type})>"


class EventEntity(Base):
    """事项-实体关联表（多对多关系）"""

    __tablename__ = "event_entity"
    __table_args__ = (
        Index("uk_event_entity", "event_id", "entity_id", unique=True),
        Index("idx_event_id", "event_id"),
        Index("idx_entity_id", "entity_id"),
    )

    # 主键：UUID
    id: Mapped[str] = mapped_column(_Uuid, primary_key=True)

    # 事项ID：UUID（外键）
    event_id: Mapped[str] = mapped_column(
        _Uuid,
        ForeignKey("source_event.id", ondelete="CASCADE", onupdate="CASCADE"),
        nullable=False,
        index=True,
    )

    # 实体ID：UUID（外键）
    entity_id: Mapped[str] = mapped_column(
        _Uuid,
        ForeignKey("entity.id", ondelete="CASCADE", onupdate="CASCADE"),
        nullable=False,
        index=True,
    )

    # 该实体在此事项中的权重（0.00-9.99）
    weight: Mapped[Decimal] = mapped_column(Numeric(3, 2), default=Decimal("1.00"))

    # 该实体在此事项中的描述/角色（如："某公司CEO"、"天使投资人"）
    description: Mapped[str | None] = mapped_column(Text)

    # 扩展数据：{"confidence": 0.95, "context": ""}
    extra_data: Mapped[dict | None] = mapped_column(JSON)

    # 时间戳
    created_time: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), nullable=False
    )

    # 关系
    event: Mapped["SourceEvent"] = relationship(
        "SourceEvent",
        back_populates="event_associations",
        lazy="noload",  # 防止延迟加载错误
    )
    entity: Mapped["Entity"] = relationship(
        "Entity",
        back_populates="event_associations",
        lazy="noload",  # 防止延迟加载错误
    )

    def __repr__(self) -> str:
        return f"<EventEntity(event_id={self.event_id}, entity_id={self.entity_id}, weight={self.weight})>"


class SourceEvent(Base):
    """源事件表"""

    __tablename__ = "source_event"

    # 主键：UUID
    id: Mapped[str] = mapped_column(_Uuid, primary_key=True)

    # 信息源配置ID：UUID（外键）
    data_source_id: Mapped[str] = mapped_column(
        _Uuid,
        ForeignKey("data_source.id", ondelete="CASCADE", onupdate="CASCADE"),
        nullable=False,
        index=True,
    )

    # 来源标识（多态字段，统一接口）
    source_type: Mapped[str] = mapped_column(
        String(20), nullable=False, index=True, comment="来源类型：ARTICLE/CHAT"
    )
    source_id: Mapped[str] = mapped_column(
        String(100), nullable=False, index=True, comment="来源ID"
    )
    # Managed ``process_source`` writes a complete immutable generation and only
    # exposes it by switching SAGSourceManifest.  Legacy explicit stage calls keep
    # this NULL and retain their historical replace-in-place semantics.
    generation_id: Mapped[str | None] = mapped_column(String(191), nullable=True)

    # 文章ID：UUID（外键）
    article_id: Mapped[str | None] = mapped_column(
        _Uuid,
        ForeignKey("article.id", ondelete="RESTRICT", onupdate="CASCADE"),
        nullable=True,
        index=True,
    )
    conversation_id: Mapped[str | None] = mapped_column(
        _Uuid,
        ForeignKey("chat_conversation.id", ondelete="RESTRICT", onupdate="CASCADE"),
        nullable=True,
        index=True,
    )

    # 事件信息
    title: Mapped[str] = mapped_column(String(255), nullable=False)
    summary: Mapped[str] = mapped_column(_MediumText, nullable=False)
    content: Mapped[str] = mapped_column(_LongText, nullable=False)

    # 事项分类（如：技术、产品、市场、研究、管理等）
    category: Mapped[str | None] = mapped_column(String(50), default="")

    # 关键词列表
    keywords: Mapped[dict | None] = mapped_column(JSON, comment="关键词列表")

    # 业务字段（兼容主系统）
    type: Mapped[str | None] = mapped_column(String(50), nullable=True)
    priority: Mapped[str | None] = mapped_column(String(50), default="")
    status: Mapped[str | None] = mapped_column(String(50), default="")

    # 排序序号
    rank: Mapped[int] = mapped_column(Integer, default=0, nullable=False)

    # 层级结构字段
    level: Mapped[int] = mapped_column(
        Integer, default=0, nullable=False, comment="层级深度（0=顶层）"
    )
    parent_id: Mapped[str | None] = mapped_column(
        _Uuid,
        ForeignKey("source_event.id", ondelete="CASCADE", onupdate="CASCADE"),
        nullable=True,
        index=True,
        comment="父事项ID（自引用）",
    )

    # 时间范围
    start_time: Mapped[datetime | None] = mapped_column(DateTime)
    end_time: Mapped[datetime | None] = mapped_column(DateTime)

    # 原始片段引用
    references: Mapped[dict | None] = mapped_column(JSON)

    # 来源片段ID：UUID（指向 SourceChunk）
    chunk_id: Mapped[str | None] = mapped_column(_Uuid, index=True)

    # 扩展数据：{"keywords": [], "category": "", "priority": "", "status": ""}
    extra_data: Mapped[dict | None] = mapped_column(JSON)

    # 时间戳
    created_time: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), nullable=False
    )
    updated_time: Mapped[datetime] = mapped_column(
        DateTime, server_default=func.now(), onupdate=func.now(), nullable=False
    )

    # 关系
    data_source: Mapped["DataSource"] = relationship(
        "DataSource",
        back_populates="source_events",
    )
    article: Mapped[Optional["Article"]] = relationship(
        "Article",
        back_populates="source_events",
    )
    conversation: Mapped[Optional["ChatConversation"]] = relationship("ChatConversation")
    # 多对多关系：通过 event_entity 关联表
    event_associations: Mapped[list["EventEntity"]] = relationship(
        "EventEntity",
        back_populates="event",
        cascade="all, delete-orphan",
    )
    # 层级关系：父子事项（自引用）
    parent: Mapped[Optional["SourceEvent"]] = relationship(
        "SourceEvent",
        remote_side="SourceEvent.id",
        back_populates="children",
    )
    children: Mapped[list["SourceEvent"]] = relationship(
        "SourceEvent",
        back_populates="parent",
        cascade="all, delete-orphan",
    )

    @property
    def entities(self) -> list["Entity"]:
        """通过关联表访问实体列表"""
        return [assoc.entity for assoc in self.event_associations]

    # 索引
    # 注意：MySQL 不支持在有外键动作的列上使用 CHECK 约束，数据完整性由应用层保证
    __table_args__ = (
        Index("idx_source", "source_type", "source_id"),
        Index(
            "idx_source_event_generation",
            "data_source_id",
            "source_id",
            "generation_id",
        ),
        Index("idx_source_rank", "source_type", "source_id", "rank"),
        Index("idx_data_source_id", "data_source_id"),
        Index("idx_article_id", "article_id"),
        Index("idx_conversation_id", "conversation_id"),
        Index("idx_article_rank", "article_id", "rank"),
        Index("idx_parent_id", "parent_id"),
        Index("idx_level", "level"),
        Index("idx_parent_level", "parent_id", "level"),
        Index("idx_start_time", "start_time"),
        Index("idx_end_time", "end_time"),
        Index("idx_chunk_id", "chunk_id"),
    )

    def __repr__(self) -> str:
        return f"<SourceEvent(id={self.id}, title={self.title[:30]})>"

    @classmethod
    def not_deleted(cls):
        return or_(cls.status.is_(None), cls.status != "DELETED")


class SourceChunk(Base):
    """
    来源片段聚合表 - 聚合ArticleSection句子或ChatMessage句子为片段
    """

    __tablename__ = "source_chunk"

    # 有默认值的字段 - 主键
    id: Mapped[str] = mapped_column(
        _Uuid,
        primary_key=True,
        default=lambda: str(__import__("uuid").uuid4()),
    )

    # 信息源配置ID（必填，外键）
    data_source_id: Mapped[str] = mapped_column(
        _Uuid,
        ForeignKey("data_source.id", ondelete="CASCADE", onupdate="CASCADE"),
        nullable=False,
        index=True,
    )

    # 来源标识（多态字段，主要使用）
    source_type: Mapped[str] = mapped_column(String(20), nullable=False, index=True)
    source_id: Mapped[str] = mapped_column(String(100), nullable=False, index=True)
    generation_id: Mapped[str | None] = mapped_column(String(191), nullable=True)

    # 外键字段（级联删除）
    article_id: Mapped[str | None] = mapped_column(
        _Uuid,
        ForeignKey("article.id", ondelete="CASCADE", onupdate="CASCADE"),
        nullable=True,
        index=True,
    )
    conversation_id: Mapped[str | None] = mapped_column(
        _Uuid,
        ForeignKey("chat_conversation.id", ondelete="CASCADE", onupdate="CASCADE"),
        nullable=True,
        index=True,
    )

    # 可选字段（无默认值但可为空）
    content: Mapped[str | None] = mapped_column(_LongText, nullable=True)
    extra_data: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    references: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    heading: Mapped[str | None] = mapped_column(String(500), nullable=True)
    raw_content: Mapped[str | None] = mapped_column(_LongText, nullable=True)

    # 有默认值的字段
    rank: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    chunk_length: Mapped[int] = mapped_column(Integer, default=0, nullable=False)
    created_time: Mapped[datetime] = mapped_column(
        DateTime, nullable=False, server_default=func.now()
    )
    updated_time: Mapped[datetime] = mapped_column(
        DateTime, nullable=False, server_default=func.now(), onupdate=func.now()
    )

    # 关系
    data_source: Mapped["DataSource"] = relationship("DataSource")
    article: Mapped[Optional["Article"]] = relationship("Article")
    conversation: Mapped[Optional["ChatConversation"]] = relationship("ChatConversation")

    # 索引
    __table_args__ = (
        Index("idx_source", "source_type", "source_id", "rank"),
        Index(
            "idx_source_chunk_generation",
            "data_source_id",
            "source_id",
            "generation_id",
        ),
        Index("idx_data_source_id", "data_source_id"),
        Index("idx_article_id", "article_id"),
        Index("idx_conversation_id", "conversation_id"),
        Index("idx_created", "created_time"),
        {"comment": "来源片段聚合表 - 聚合ArticleSection或ChatMessage为片段"},
    )

    def __repr__(self) -> str:
        return f"<SourceChunk(id={self.id}, source_type={self.source_type}, source_id={self.source_id})>"


__all__ = [
    "SAGSchemaMeta",
    "SAGOperation",
    "SAGFence",
    "SAGDataSourceManifest",
    "SAGSourceManifest",
    "SAGSearchSession",
    "DataSource",
    "KBDocument",
    "ArticleParseStatus",
    "Article",
    "ArticleSection",
    "ChatConversation",
    "ChatMessage",
    "EntityType",
    "Entity",
    "EventEntity",
    "SourceEvent",
    "SourceChunk",
]
