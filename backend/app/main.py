"""VitalLink API entry point. Run locally: uvicorn app.main:app --reload"""

import uuid

from fastapi import FastAPI, Request
from fastapi.middleware.cors import CORSMiddleware

from app.core import db
from app.core.errors import ApiError, install_error_handlers
from app.core.settings import get_settings


def create_app() -> FastAPI:
    settings = get_settings()
    app = FastAPI(title="VitalLink API", version="0.1.0")

    @app.middleware("http")
    async def request_id(request: Request, call_next):
        # Returned in a header and in every error body, so a failure can be traced to its log line.
        request.state.request_id = f"req_{uuid.uuid4().hex[:16]}"
        response = await call_next(request)
        response.headers["X-Request-ID"] = request.state.request_id
        return response

    app.add_middleware(
        CORSMiddleware,
        allow_origins=[settings.cors_origin],
        allow_methods=["GET", "POST", "PATCH", "PUT", "DELETE"],
        allow_headers=["Authorization", "Content-Type"],
        expose_headers=["X-Request-ID"],
    )
    install_error_handlers(app)

    @app.get("/health", tags=["ops"])
    async def health() -> dict[str, str]:
        """The process is up."""
        return {"status": "ok"}

    @app.get("/ready", tags=["ops"])
    async def ready() -> dict[str, str]:
        """The database is reachable. The uptime monitor pings this, which also keeps Supabase awake."""
        if not await db.ping():
            raise ApiError(503, "db_unavailable", "The database isn't reachable.")
        return {"status": "ready"}

    return app


app = create_app()
