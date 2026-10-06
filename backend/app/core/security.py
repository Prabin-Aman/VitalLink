"""Who is calling: verify the Supabase access token on every request (docs/06 §6.3).

Production: asymmetric keys from Supabase's JWKS (cached 10 min), issuer and audience checked.
Local and test: HS256 dev tokens signed with DEV_JWT_SECRET, so the API runs without Supabase.
The two paths never mix: production accepts no HS256 token, whatever its header says.
"""

import time
import uuid
from dataclasses import dataclass
from functools import lru_cache
from typing import Annotated

import jwt
from fastapi import Depends, Header
from starlette.concurrency import run_in_threadpool

from app.core.errors import ApiError
from app.core.settings import Settings, get_settings

DEV_ISSUER = "vitallink-dev"
ASYMMETRIC = ["ES256", "RS256"]


@dataclass(frozen=True)
class Claims:
    subject: uuid.UUID
    email: str
    email_verified: bool
    aal: str  # "aal1" password/OAuth, "aal2" with a second factor
    issued_at: int
    first_name: str | None = None
    last_name: str | None = None

    def is_recent(self, seconds: int = 600) -> bool:
        """For sensitive actions (delete account, create share): signed in within the last 10 minutes."""
        return time.time() - self.issued_at <= seconds


def _unauthorized(message: str = "Sign in again.") -> ApiError:
    return ApiError(401, "unauthorized", message)


@lru_cache
def _jwks(url: str) -> jwt.PyJWKClient:
    return jwt.PyJWKClient(url, cache_keys=True, lifespan=600)


async def _decode(token: str, settings: Settings) -> dict:
    if settings.env in ("local", "test"):
        if not settings.dev_jwt_secret:
            raise _unauthorized("DEV_JWT_SECRET isn't set.")
        return jwt.decode(
            token,
            settings.dev_jwt_secret,
            algorithms=["HS256"],
            audience=settings.jwt_audience,
            issuer=DEV_ISSUER,
            options={"require": ["exp", "iat", "sub"]},
        )
    if not settings.supabase_url:
        raise ApiError(503, "auth_unavailable", "Sign-in isn't configured.")
    issuer = f"{settings.supabase_url.rstrip('/')}/auth/v1"
    key = await run_in_threadpool(_jwks(f"{issuer}/.well-known/jwks.json").get_signing_key_from_jwt, token)
    return jwt.decode(
        token,
        key.key,
        algorithms=ASYMMETRIC,
        audience=settings.jwt_audience,
        issuer=issuer,
        options={"require": ["exp", "iat", "sub"]},
    )


def parse_claims(payload: dict) -> Claims:
    email = payload.get("email")
    if not email:
        raise ApiError(403, "email_required", "VitalLink accounts need an email address.")
    meta = payload.get("user_metadata") or {}
    try:
        subject = uuid.UUID(payload["sub"])
    except ValueError:
        raise _unauthorized() from None
    return Claims(
        subject=subject,
        email=email.strip().lower(),
        # Supabase sets this once the address is confirmed (or the OAuth provider vouches for it).
        email_verified=meta.get("email_verified") is True,
        aal=payload.get("aal", "aal1"),
        issued_at=int(payload["iat"]),
        first_name=meta.get("first_name"),
        last_name=meta.get("last_name"),
    )


async def get_claims(
    settings: Annotated[Settings, Depends(get_settings)],
    authorization: Annotated[str | None, Header()] = None,
) -> Claims:
    """FastAPI dependency: the verified caller, or 401."""
    scheme, _, token = (authorization or "").partition(" ")
    if scheme.lower() != "bearer" or not token:
        raise _unauthorized("Missing bearer token.")
    try:
        payload = await _decode(token, settings)
    except jwt.ExpiredSignatureError:
        raise _unauthorized("Your session has expired.") from None
    except jwt.PyJWTError:
        raise _unauthorized() from None
    return parse_claims(payload)


def mint_dev_token(
    settings: Settings,
    *,
    subject: uuid.UUID,
    email: str,
    email_verified: bool = True,
    first_name: str | None = None,
    last_name: str | None = None,
    ttl: int = 3600,
    issued_at: int | None = None,
) -> str:
    """A token shaped like Supabase's, for local runs and tests. Refused outside local/test."""
    if settings.env not in ("local", "test") or not settings.dev_jwt_secret:
        raise RuntimeError("Dev tokens exist only in local and test environments.")
    now = issued_at or int(time.time())
    meta = {"email_verified": email_verified, "first_name": first_name, "last_name": last_name}
    payload = {
        "sub": str(subject),
        "email": email,
        "aud": settings.jwt_audience,
        "iss": DEV_ISSUER,
        "iat": now,
        "exp": now + ttl,
        "aal": "aal1",
        "user_metadata": {k: v for k, v in meta.items() if v is not None},
    }
    return jwt.encode(payload, settings.dev_jwt_secret, algorithm="HS256")
