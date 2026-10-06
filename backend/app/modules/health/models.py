"""metrics, health_readings, ingest_batches, devices (docs/04-database.md §4.4)."""

import uuid
from datetime import datetime
from enum import StrEnum

from sqlalchemy import (
    ARRAY,
    BigInteger,
    Boolean,
    CheckConstraint,
    Double,
    ForeignKey,
    Identity,
    Index,
    Integer,
    LargeBinary,
    SmallInteger,
    Text,
    UniqueConstraint,
    Uuid,
    func,
)
from sqlalchemy.dialects.postgresql import TIMESTAMP
from sqlalchemy.orm import Mapped, mapped_column

from app.core.audit import Scope
from app.core.ids import uuid7
from app.core.models import Base, pg_enum
from app.modules.health.catalogue import Agg


class ReadingSource(StrEnum):
    healthkit = "healthkit"
    health_connect = "health_connect"
    manual = "manual"


class DeviceKind(StrEnum):
    phone = "phone"
    wearable = "wearable"
    home_monitor = "home_monitor"
    continuous = "continuous"


class MetricRow(Base):
    """The catalogue as stored. Seeded by migrations; app/modules/health/catalogue.py is the same list."""

    __tablename__ = "metrics"
    __table_args__ = (
        CheckConstraint("min_value < max_value", name="range_order"),
        CheckConstraint(
            "normal_low IS NULL OR normal_high IS NULL OR normal_low < normal_high", name="normal_order"
        ),
    )

    id: Mapped[int] = mapped_column(SmallInteger, primary_key=True, autoincrement=False)
    code: Mapped[str] = mapped_column(Text, unique=True)
    scope: Mapped[Scope] = mapped_column(pg_enum(Scope, "share_scope"))
    unit: Mapped[str] = mapped_column(Text)
    aggregation: Mapped[Agg] = mapped_column(pg_enum(Agg, "aggregation"))
    min_value: Mapped[float] = mapped_column(Double)
    max_value: Mapped[float] = mapped_column(Double)
    manual_allowed: Mapped[bool] = mapped_column(Boolean)
    normal_low: Mapped[float | None] = mapped_column(Double)
    normal_high: Mapped[float | None] = mapped_column(Double)


class Reading(Base):
    """One value per metric per 5-minute bucket per source. The primary key is the de-dup key."""

    __tablename__ = "health_readings"
    __table_args__ = (
        CheckConstraint("extract(epoch FROM recorded_at)::bigint % 300 = 0", name="bucket_aligned"),
        CheckConstraint("samples > 0", name="samples_positive"),
    )

    user_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    metric_id: Mapped[int] = mapped_column(
        SmallInteger, ForeignKey("metrics.id", ondelete="RESTRICT"), primary_key=True
    )
    recorded_at: Mapped[datetime] = mapped_column(TIMESTAMP(timezone=True), primary_key=True)
    source: Mapped[ReadingSource] = mapped_column(pg_enum(ReadingSource, "reading_source"), primary_key=True)
    value: Mapped[float] = mapped_column(Double)
    samples: Mapped[int] = mapped_column(SmallInteger)
    updated_at: Mapped[datetime] = mapped_column(TIMESTAMP(timezone=True), server_default=func.now())


class IngestBatch(Base):
    __tablename__ = "ingest_batches"
    __table_args__ = (
        UniqueConstraint("user_id", "idempotency_key", name="ingest_batches_user_key_key"),
        CheckConstraint("octet_length(payload_sha256) = 32", name="hash_length"),
        CheckConstraint("submitted BETWEEN 1 AND 5000", name="submitted_range"),
        CheckConstraint("inserted + updated + unchanged = submitted", name="counts_add_up"),
        Index("ingest_batches_received_at_idx", "received_at"),
    )

    id: Mapped[int] = mapped_column(BigInteger, Identity(always=True), primary_key=True)
    user_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("users.id", ondelete="CASCADE"))
    idempotency_key: Mapped[uuid.UUID] = mapped_column(Uuid)
    payload_sha256: Mapped[bytes] = mapped_column(LargeBinary)
    platform: Mapped[ReadingSource] = mapped_column(pg_enum(ReadingSource, "reading_source"))
    received_at: Mapped[datetime] = mapped_column(TIMESTAMP(timezone=True), server_default=func.now())
    submitted: Mapped[int] = mapped_column(Integer)
    inserted: Mapped[int] = mapped_column(Integer)
    updated: Mapped[int] = mapped_column(Integer)
    unchanged: Mapped[int] = mapped_column(Integer)


class Device(Base):
    __tablename__ = "devices"
    __table_args__ = (
        UniqueConstraint("user_id", "platform", "external_id", name="devices_user_platform_external_key"),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid7)
    user_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("users.id", ondelete="CASCADE"))
    platform: Mapped[ReadingSource] = mapped_column(pg_enum(ReadingSource, "reading_source"))
    external_id: Mapped[str] = mapped_column(Text)
    name: Mapped[str] = mapped_column(Text)
    kind: Mapped[DeviceKind] = mapped_column(pg_enum(DeviceKind, "device_kind"))
    metric_ids: Mapped[list[int]] = mapped_column(ARRAY(SmallInteger))
    first_seen_at: Mapped[datetime] = mapped_column(TIMESTAMP(timezone=True), server_default=func.now())
    last_sync_at: Mapped[datetime] = mapped_column(TIMESTAMP(timezone=True), server_default=func.now())
