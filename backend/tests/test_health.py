from fastapi.testclient import TestClient

from app.main import app

client = TestClient(app)


def test_health_is_ok_and_has_request_id():
    res = client.get("/health")
    assert res.status_code == 200
    assert res.json() == {"status": "ok"}
    assert res.headers["X-Request-ID"].startswith("req_")


def test_unknown_route_uses_error_envelope():
    res = client.get("/v1/nope")
    assert res.status_code == 404
    error = res.json()["error"]
    assert error["code"] == "not_found"
    assert error["request_id"] == res.headers["X-Request-ID"]
