"""Imports every module's models so Base.metadata knows all tables (Alembic, drift check)."""


def load_all() -> None:
    import app.core.audit  # noqa: F401
    import app.modules.account.models  # noqa: F401
    import app.modules.health.models  # noqa: F401
    import app.modules.sharing.models  # noqa: F401
    import app.modules.training.models  # noqa: F401
