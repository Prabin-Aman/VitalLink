"""All configuration comes from environment variables (or backend/.env locally)."""

from functools import lru_cache
from typing import Literal

from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    model_config = SettingsConfigDict(env_file=".env", extra="ignore")

    env: Literal["local", "test", "production"] = "local"
    # Only the web portal's origin may call the API from a browser.
    cors_origin: str = "http://localhost:5173"
    # Locally this points at docker-compose.yml's Postgres; on Render at Supabase's session pooler.
    database_url: str | None = None

    # Auth. Production verifies Supabase tokens via JWKS; local/test use dev tokens instead.
    supabase_url: str | None = None
    jwt_audience: str = "authenticated"
    dev_jwt_secret: str | None = None


@lru_cache
def get_settings() -> Settings:
    return Settings()
