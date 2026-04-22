from __future__ import annotations

import json
from pathlib import Path

from fastapi.testclient import TestClient

from tactical_ai.api.main import app

SAMPLE = Path(__file__).resolve().parent.parent / "tactical_ai" / "sample_snapshot.json"


def test_health():
    with TestClient(app) as client:
        r = client.get("/health")
    assert r.status_code == 200
    assert r.json()["status"] == "healthy"


def test_ready_after_lifespan():
    with TestClient(app) as client:
        r = client.get("/ready")
    assert r.status_code == 200
    body = r.json()
    assert body.get("decision_warmup_ok") is True


def test_v1_decide_ok():
    sample = json.loads(SAMPLE.read_text(encoding="utf-8"))
    payload_inner = dict(sample)
    body = {
        "schema_version": "1.0",
        "correlation_id": "test-corr-1",
        "timestamp_ms": 0,
        "event_type": "GAME_TICK",
        "payload": payload_inner,
    }
    with TestClient(app) as client:
        r = client.post("/v1/decide", json=body)
    assert r.status_code == 200
    data = r.json()
    assert data["status"] == "ok"
    assert data["correlation_id"] == "test-corr-1"
    assert data["payload"] is not None
    assert "action" in data["payload"]
    assert "aim_delta" in data["payload"]


def test_v1_decide_bad_schema():
    body = {
        "schema_version": "99.0",
        "correlation_id": "x",
        "timestamp_ms": 0,
        "event_type": "GAME_TICK",
        "payload": {},
    }
    with TestClient(app) as client:
        r = client.post("/v1/decide", json=body)
    assert r.status_code == 200
    data = r.json()
    assert data["status"] == "error"
    assert data["error_code"] == "SCHEMA_VERSION_UNSUPPORTED"


def test_decide_flat_sample():
    sample = json.loads(SAMPLE.read_text(encoding="utf-8"))
    with TestClient(app) as client:
        r = client.post("/decide", json=sample, headers={"X-Correlation-ID": "cid-2"})
    assert r.status_code == 200
    data = r.json()
    assert data["status"] == "ok"
    assert data["correlation_id"] == "cid-2"


def test_import_package():
    import tactical_ai  # noqa: F401
    from tactical_ai.agent import TacticalAgent  # noqa: F401


def test_demo_disabled_in_test_env():
    with TestClient(app) as client:
        r = client.get("/demo")
    assert r.status_code == 404
