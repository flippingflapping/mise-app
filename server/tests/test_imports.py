import json
import shutil

import pytest
from fastapi.testclient import TestClient

from mise_converter.client import LLMResponse
from mise_converter.llm_schema import SYSTEM_FIELDS, denormalize
from mise_server.app import create_app
from mise_server.imports import ImportQueue
from mise_server.library import RecipeLibrary
from mise_shared.config import load_config
from mise_shared.paths import FIXTURES_DIR

TOKEN = "t0ken"
AUTH = {"Authorization": f"Bearer {TOKEN}"}
RAW = (FIXTURES_DIR / "raw" / "lamb-curry-for-kothu.txt").read_text(encoding="utf-8")


def llm_output() -> str:
    data = json.loads((FIXTURES_DIR / "expected" / "sri-lankan-lamb-curry-for-kothu.json").read_text(encoding="utf-8"))
    for f in SYSTEM_FIELDS:
        data.pop(f, None)
    data["source"].pop("original_filename", None)
    return json.dumps(denormalize(data))


class OneShotClient:
    def __init__(self, text):
        self.text, self.calls = text, 0

    def create(self, params):
        self.calls += 1
        return LLMResponse(text=self.text, stop_reason="end_turn", content=[{"type": "text", "text": self.text}])


@pytest.fixture
def cfg(tmp_path):
    return load_config(tmp_path / "config.toml")


def make(cfg, *, can_convert, client=None):
    library = RecipeLibrary(cfg.recipes_dir)
    library.load_all()
    imports = ImportQueue(cfg, library, client_factory=(lambda: client) if client else None, can_convert=can_convert)
    return TestClient(create_app(library, TOKEN, imports=imports)), imports, library


def test_health_reports_import_capability(cfg):
    client, _, _ = make(cfg, can_convert=False)
    h = client.get("/api/v1/health").json()
    assert h["can_import"] is True and h["can_convert"] is False


def test_without_key_text_waits_in_inbox(cfg):
    client, imports, _ = make(cfg, can_convert=False)
    r = client.post("/api/v1/recipes/import", json={"text": RAW, "title": "Lamb curry for kothu", "source": "Hoppers cookbook p. 120"}, headers=AUTH)
    assert r.status_code == 202
    job = r.json()
    assert job["status"] == "saved"
    saved = cfg.converter.inbox_dir / job["filename"]
    assert job["filename"].startswith("from-phone/lamb-curry-for-kothu-")
    text = saved.read_text(encoding="utf-8")
    assert text.startswith("Lamb curry for kothu\n\nSource: Hoppers cookbook p. 120\n\nMAKES ENOUGH")
    assert client.get(f"/api/v1/imports/{job['id']}", headers=AUTH).json()["status"] == "saved"


def test_with_key_it_converts_and_the_library_picks_it_up(cfg):
    fake = OneShotClient(llm_output())
    client, imports, library = make(cfg, can_convert=True, client=fake)
    job = client.post("/api/v1/recipes/import", json={"text": RAW}, headers=AUTH).json()
    assert job["status"] == "queued"
    imports.wait_idle()
    done = client.get(f"/api/v1/imports/{job['id']}", headers=AUTH).json()
    assert done["status"] == "needs_review" and done["recipe_id"] == "sri-lankan-lamb-curry-for-kothu"
    assert fake.calls == 1
    assert "sri-lankan-lamb-curry-for-kothu" in library.ids()
    assert (cfg.recipes_dir / "sri-lankan-lamb-curry-for-kothu.source.txt").exists()
    ids = client.get("/api/v1/recipes", headers=AUTH).json()["ids"]
    assert ids == ["sri-lankan-lamb-curry-for-kothu"]


def test_failed_conversion_is_reported(cfg):
    client, imports, _ = make(cfg, can_convert=True, client=OneShotClient("not json"))
    job = client.post("/api/v1/recipes/import", json={"text": "toast: bread, heat"}, headers=AUTH).json()
    imports.wait_idle()
    status = client.get(f"/api/v1/imports/{job['id']}", headers=AUTH).json()
    assert status["status"] == "failed" and "JSON" in status["error"]


def test_validation_and_auth(cfg):
    client, _, _ = make(cfg, can_convert=False)
    assert client.post("/api/v1/recipes/import", json={"text": RAW}).status_code == 401
    assert client.post("/api/v1/recipes/import", json={"text": ""}, headers=AUTH).status_code == 422
    assert client.post("/api/v1/recipes/import", json={"text": "x" * 100_001}, headers=AUTH).status_code == 422
    assert client.post("/api/v1/recipes/import", json={"text": "x", "colour": 1}, headers=AUTH).status_code == 422
    assert client.get("/api/v1/imports/nope", headers=AUTH).status_code == 404


def test_server_without_imports_says_so(cfg):
    library = RecipeLibrary(cfg.recipes_dir)
    library.load_all()
    client = TestClient(create_app(library, TOKEN))
    assert client.get("/api/v1/health").json()["can_import"] is False
    assert client.post("/api/v1/recipes/import", json={"text": RAW}, headers=AUTH).status_code == 501
