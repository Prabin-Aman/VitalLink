"""Declarative base for every table. All tables live in the private `vitallink` schema."""

from enum import StrEnum

from sqlalchemy import Enum, MetaData
from sqlalchemy.orm import DeclarativeBase

from app.core.db import SCHEMA

# Predictable constraint names, so migrations and models agree.
NAMING = {
    "ix": "%(table_name)s_%(column_0_N_name)s_idx",
    "uq": "%(table_name)s_%(column_0_N_name)s_key",
    "ck": "%(table_name)s_%(constraint_name)s",
    "fk": "%(table_name)s_%(column_0_name)s_fkey",
    "pk": "%(table_name)s_pkey",
}


class Base(DeclarativeBase):
    metadata = MetaData(schema=SCHEMA, naming_convention=NAMING)


def pg_enum(enum: type[StrEnum], name: str) -> Enum:
    """A Postgres enum created by a migration (never by SQLAlchemy), stored by value."""
    return Enum(
        enum,
        name=name,
        schema=SCHEMA,
        create_type=False,
        values_callable=lambda e: [m.value for m in e],
    )
