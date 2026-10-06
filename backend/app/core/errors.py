"""One error shape for every failure (docs/05-api.md §5.1):

    {"error": {"code", "message", "detail", "request_id"}}

Clients switch on `code`, never on `message`.
"""

from typing import Any

from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from starlette.exceptions import HTTPException as StarletteHTTPException


class ApiError(Exception):
    """Raise from services; the handler turns it into the error envelope."""

    def __init__(self, status: int, code: str, message: str, detail: Any = None) -> None:
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.detail = detail


def _envelope(request: Request, status: int, code: str, message: str, detail: Any = None) -> JSONResponse:
    request_id = getattr(request.state, "request_id", None)
    body = {"error": {"code": code, "message": message, "detail": detail, "request_id": request_id}}
    return JSONResponse(body, status_code=status, headers={"X-Request-ID": request_id or ""})


# Status → code for errors raised by FastAPI/Starlette themselves (unknown route, wrong method…).
_HTTP_CODES = {401: "unauthorized", 403: "forbidden", 404: "not_found", 405: "method_not_allowed"}


def install_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(ApiError)
    async def api_error(request: Request, exc: ApiError) -> JSONResponse:
        return _envelope(request, exc.status, exc.code, exc.message, exc.detail)

    @app.exception_handler(StarletteHTTPException)
    async def http_error(request: Request, exc: StarletteHTTPException) -> JSONResponse:
        code = _HTTP_CODES.get(exc.status_code, "error")
        return _envelope(request, exc.status_code, code, str(exc.detail))

    @app.exception_handler(RequestValidationError)
    async def validation_error(request: Request, exc: RequestValidationError) -> JSONResponse:
        # Field locations and messages only; never echo the submitted values (health data).
        fields = [{"loc": list(e["loc"]), "msg": e["msg"]} for e in exc.errors()]
        return _envelope(request, 422, "invalid_request", "The request body or query is invalid.", fields)

    @app.exception_handler(Exception)
    async def unexpected(request: Request, exc: Exception) -> JSONResponse:
        return _envelope(request, 500, "internal_error", "Something went wrong on our side.")
