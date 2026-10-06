"""Token verification (no database needed)."""

import time
import uuid

import jwt
import pytest
from fastapi.testclient import TestClient

from app.core.security import DEV_ISSUER, mint_dev_token
from app.core.settings import Settings, get_settings
from app.main import app

client = TestClient(app)


def _payload(**overrides) -> dict:
    s = get_settings()
    now = int(time.time())
    base = {
        "sub": str(uuid.uuid4()),
        "email": "a@example.com",
        "aud": s.jwt_audience,
        "iss": DEV_ISSUER,
        "iat": now,
        "exp": now + 600,
    }
    return base | overrides


def _call(token: str):
    return client.get("/v1/me", headers={"Authorization": f"Bearer {token}"})


def _sign(payload: dict, secret: str | None = None) -> str:
    return jwt.encode(payload, secret or get_settings().dev_jwt_secret, algorithm="HS256")


def test_missing_token_is_401():
    res = client.get("/v1/me")
    assert res.status_code == 401
    assert res.json()["error"]["code"] == "unauthorized"


@pytest.mark.parametrize(
    "token",
    [
        _sign(_payload(exp=int(time.time()) - 10)),  # expired
        _sign(_payload(aud="someone-else")),  # wrong audience
        _sign(_payload(iss="https://evil.example")),  # wrong issuer
        _sign(_payload(), secret="not-the-secret-" + "y" * 32),  # wrong key
        _sign({k: v for k, v in _payload().items() if k != "exp"}),  # no expiry
        "not-a-jwt",
    ],
    ids=["expired", "audience", "issuer", "signature", "no-exp", "garbage"],
)
def test_bad_tokens_are_401(token):
    assert _call(token).status_code == 401


def test_token_without_email_is_403():
    payload = _payload()
    del payload["email"]
    res = _call(_sign(payload))
    assert res.status_code == 403
    assert res.json()["error"]["code"] == "email_required"


def test_dev_tokens_cannot_be_minted_in_production():
    with pytest.raises(RuntimeError):
        mint_dev_token(
            Settings(env="production", dev_jwt_secret="x" * 40), subject=uuid.uuid4(), email="a@example.com"
        )


def test_production_never_accepts_hs256(monkeypatch):
    """Even a correctly signed dev token is refused once ENV=production."""
    token = _sign(_payload())
    prod = Settings(env="production", supabase_url="https://example.supabase.co", dev_jwt_secret="x")
    app.dependency_overrides[get_settings] = lambda: prod
    try:
        res = _call(token)
    finally:
        app.dependency_overrides.clear()
    assert res.status_code == 401
