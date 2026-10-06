"""foods, meal_entries, meal_items, hydration_logs, nutrition_targets (docs/04-database.md §4.5)."""

import uuid
from datetime import datetime
from decimal import Decimal
from enum import StrEnum

from sqlalchemy import CheckConstraint, ForeignKey, Index, Numeric, SmallInteger, Text, Uuid, func, text
from sqlalchemy.dialects.postgresql import TIMESTAMP
from sqlalchemy.orm import Mapped, mapped_column, relationship

from app.core.ids import uuid7
from app.core.models import Base, pg_enum

MACRO = Numeric(6, 1)


class FoodSource(StrEnum):
    seeded = "seeded"
    user = "user"


class MealSlot(StrEnum):
    breakfast = "breakfast"
    lunch = "lunch"
    dinner = "dinner"
    snack = "snack"


class Food(Base):
    """Per 100 g. Seeded foods are shared; a user's own foods belong to them."""

    __tablename__ = "foods"
    __table_args__ = (
        CheckConstraint("(source = 'user') = (owner_user_id IS NOT NULL)", name="owner_matches_source"),
        CheckConstraint(
            "kcal >= 0 AND protein_g >= 0 AND carbs_g >= 0 AND fat_g >= 0 AND fibre_g >= 0",
            name="macros_nonnegative",
        ),
        CheckConstraint("serving_g > 0", name="serving_positive"),
        CheckConstraint("char_length(name) BETWEEN 1 AND 80", name="name_length"),
        Index("foods_owner_user_id_idx", "owner_user_id"),
        Index(
            "foods_seeded_name_key",
            text("lower(name)"),
            unique=True,
            postgresql_where=text("source = 'seeded'"),
        ),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid7)
    name: Mapped[str] = mapped_column(Text)
    brand: Mapped[str | None] = mapped_column(Text)
    source: Mapped[FoodSource] = mapped_column(pg_enum(FoodSource, "food_source"))
    owner_user_id: Mapped[uuid.UUID | None] = mapped_column(Uuid, ForeignKey("users.id", ondelete="CASCADE"))
    serving_g: Mapped[Decimal] = mapped_column(MACRO)
    kcal: Mapped[Decimal] = mapped_column(MACRO)
    protein_g: Mapped[Decimal] = mapped_column(MACRO)
    carbs_g: Mapped[Decimal] = mapped_column(MACRO)
    fat_g: Mapped[Decimal] = mapped_column(MACRO)
    fibre_g: Mapped[Decimal] = mapped_column(MACRO)


class MealEntry(Base):
    __tablename__ = "meal_entries"
    __table_args__ = (
        CheckConstraint("char_length(name) BETWEEN 1 AND 60", name="name_length"),
        Index("meal_entries_user_eaten_idx", "user_id", "eaten_at"),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid7)
    user_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("users.id", ondelete="CASCADE"))
    slot: Mapped[MealSlot] = mapped_column(pg_enum(MealSlot, "meal_slot"))
    name: Mapped[str] = mapped_column(Text)
    eaten_at: Mapped[datetime] = mapped_column(TIMESTAMP(timezone=True))
    created_at: Mapped[datetime] = mapped_column(TIMESTAMP(timezone=True), server_default=func.now())

    items: Mapped[list["MealItem"]] = relationship(
        back_populates="meal", cascade="all, delete-orphan", order_by="MealItem.position", lazy="selectin"
    )


class MealItem(Base):
    """Macros are copied at write time, so editing or deleting a food never rewrites history."""

    __tablename__ = "meal_items"
    __table_args__ = (
        CheckConstraint("quantity_g > 0", name="quantity_positive"),
        CheckConstraint(
            "kcal >= 0 AND protein_g >= 0 AND carbs_g >= 0 AND fat_g >= 0 AND fibre_g >= 0",
            name="macros_nonnegative",
        ),
        CheckConstraint("char_length(label) BETWEEN 1 AND 80", name="label_length"),
        Index("meal_items_meal_id_idx", "meal_id"),
        Index("meal_items_food_id_idx", "food_id"),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid7)
    meal_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("meal_entries.id", ondelete="CASCADE"))
    food_id: Mapped[uuid.UUID | None] = mapped_column(Uuid, ForeignKey("foods.id", ondelete="SET NULL"))
    position: Mapped[int] = mapped_column(SmallInteger)
    label: Mapped[str] = mapped_column(Text)
    quantity_g: Mapped[Decimal] = mapped_column(Numeric(7, 1))
    kcal: Mapped[Decimal] = mapped_column(Numeric(7, 1))
    protein_g: Mapped[Decimal] = mapped_column(MACRO)
    carbs_g: Mapped[Decimal] = mapped_column(MACRO)
    fat_g: Mapped[Decimal] = mapped_column(MACRO)
    fibre_g: Mapped[Decimal] = mapped_column(MACRO)

    meal: Mapped[MealEntry] = relationship(back_populates="items")


class HydrationLog(Base):
    __tablename__ = "hydration_logs"
    __table_args__ = (
        CheckConstraint("ml BETWEEN 1 AND 3000", name="ml_range"),
        Index("hydration_logs_user_logged_idx", "user_id", "logged_at"),
    )

    id: Mapped[uuid.UUID] = mapped_column(Uuid, primary_key=True, default=uuid7)
    user_id: Mapped[uuid.UUID] = mapped_column(Uuid, ForeignKey("users.id", ondelete="CASCADE"))
    logged_at: Mapped[datetime] = mapped_column(TIMESTAMP(timezone=True))
    ml: Mapped[int] = mapped_column(SmallInteger)


class NutritionTargets(Base):
    __tablename__ = "nutrition_targets"
    __table_args__ = (
        CheckConstraint("kcal BETWEEN 800 AND 6000", name="kcal_range"),
        CheckConstraint(
            "protein_g BETWEEN 0 AND 500 AND carbs_g BETWEEN 0 AND 1000 AND fat_g BETWEEN 0 AND 400 "
            "AND fibre_g BETWEEN 0 AND 150",
            name="macro_ranges",
        ),
        CheckConstraint("water_ml BETWEEN 500 AND 8000", name="water_range"),
        CheckConstraint("glass_ml BETWEEN 100 AND 1000", name="glass_range"),
        CheckConstraint("weekly_target_kg BETWEEN -1 AND 1", name="weekly_target_range"),
    )

    user_id: Mapped[uuid.UUID] = mapped_column(
        Uuid, ForeignKey("users.id", ondelete="CASCADE"), primary_key=True
    )
    kcal: Mapped[int] = mapped_column(SmallInteger, server_default="2200")
    protein_g: Mapped[int] = mapped_column(SmallInteger, server_default="130")
    carbs_g: Mapped[int] = mapped_column(SmallInteger, server_default="250")
    fat_g: Mapped[int] = mapped_column(SmallInteger, server_default="70")
    fibre_g: Mapped[int] = mapped_column(SmallInteger, server_default="30")
    water_ml: Mapped[int] = mapped_column(SmallInteger, server_default="2500")
    glass_ml: Mapped[int] = mapped_column(SmallInteger, server_default="250")
    weekly_target_kg: Mapped[Decimal] = mapped_column(Numeric(3, 2), server_default="0")
    updated_at: Mapped[datetime] = mapped_column(
        TIMESTAMP(timezone=True), server_default=func.now(), onupdate=func.now()
    )
