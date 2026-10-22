import json
import logging
import shutil
import time

import pytest
from fastapi.testclient import TestClient

from mise_server.app import create_app
from mise_server.library import RecipeLibrary, id_for_path, start_watcher
from mise_server.pairing import pairing_uri, terminal_qr
from mise_shared.paths import FIXTURES_DIR
from mise_shared.validation import validate_recipe

RID = "sri-lankan-lamb-curry-for-kothu"
TOKEN = "test-token-123"
AUTH = {"Authorization": f"Bearer {TOKEN}"}


@pytest.fixture
def folder(tmp_path):
    d = tmp_path / "recipes"
    d.mkdir()
    shutil.copy(FIXTURES_DIR / "expected" / f"{RID}.json", d / f"{RID}.json")
    shutil.copy(FIXTURES_DIR / "raw" / "lamb-curry-for-kothu.txt", d / f"{RID}.source.txt")
    return d


@pytest.fixture
def library(folder):
    lib = RecipeLibrary(folder)
    lib.load_all()
    return lib


@pytest.fixture
def client(library):
    app = create_app(library, TOKEN, pairing=[("x", "mise://pair?url=x&token=y")], local_hosts={"testclient"})
    return TestClient(app)


def test_requests_are_logged_without_the_token(client, caplog):
    with caplog.at_level(logging.INFO, logger="mise.requests"):
        client.get("/api/v1/health")
        client.get("/api/v1/recipes", headers={"Authorization": "Bearer nope"})
        client.get("/api/v1/recipes", headers={"Authorization": f"Bearer {TOKEN}"})
    text = caplog.text
    assert "GET /api/v1/health → 200" in text
    assert "→ 401" in text and "pair the phone again" in text
    assert TOKEN not in text and "nope" not in text


def test_health_needs_no_token(client):
    r = client.get("/api/v1/health")
    assert r.status_code == 200
    assert r.json()["schema_version"] == 1
    assert "token" not in r.text


@pytest.mark.parametrize("headers", [{}, {"Authorization": "Bearer nope"}, {"Authorization": TOKEN}])
def test_everything_else_needs_the_token(client, headers):
    for path in ("/api/v1/recipes", f"/api/v1/recipes/{RID}", f"/api/v1/recipes/{RID}/source"):
        assert client.get(path, headers=headers).status_code == 401
    assert client.put(f"/api/v1/recipes/{RID}/annotations", json={}, headers=headers).status_code == 401


def test_list_and_etag(client):
    r = client.get("/api/v1/recipes", headers=AUTH)
    assert r.status_code == 200
    body = r.json()
    assert body["ids"] == [RID]
    s = body["recipes"][0]
    assert s["id"] == RID and s["status"] == "needs_review" and s["etag"].startswith('"')
    assert set(s) == {"id", "title", "tags", "status", "updated_at", "etag"}
    etag = r.headers["etag"]
    assert client.get("/api/v1/recipes", headers={**AUTH, "If-None-Match": etag}).status_code == 304


def test_since_filter(client):
    assert len(client.get("/api/v1/recipes?since=2026-10-14T00:00:00Z", headers=AUTH).json()["recipes"]) == 1
    later = client.get("/api/v1/recipes?since=2030-01-01T00:00:00Z", headers=AUTH).json()
    assert later["recipes"] == [] and later["ids"] == [RID]  # ids always complete, for deletions
    assert client.get("/api/v1/recipes?since=yesterday", headers=AUTH).status_code == 400


def test_get_recipe_with_etag(client):
    r = client.get(f"/api/v1/recipes/{RID}", headers=AUTH)
    assert r.status_code == 200
    assert validate_recipe(r.json()).ok
    assert client.get(f"/api/v1/recipes/{RID}", headers={**AUTH, "If-None-Match": r.headers["etag"]}).status_code == 304
    assert client.get("/api/v1/recipes/nope", headers=AUTH).status_code == 404
    assert client.get("/api/v1/recipes/..%2Fconfig", headers=AUTH).status_code == 404


def test_source_text(client, folder):
    r = client.get(f"/api/v1/recipes/{RID}/source", headers=AUTH)
    assert r.status_code == 200 and "MAKES ENOUGH FOR ABOUT 2 PORTIONS" in r.text
    (folder / f"{RID}.source.txt").unlink()
    assert client.get(f"/api/v1/recipes/{RID}/source", headers=AUTH).status_code == 404


def test_annotations_go_to_sidecar_and_merge_on_read(client, folder, library):
    recipe_file = folder / f"{RID}.json"
    before = recipe_file.read_bytes()
    etag_before = client.get(f"/api/v1/recipes/{RID}", headers=AUTH).headers["etag"]
    body = {"favorite": True, "reviewed": True, "cook_notes": ["Less chilli."], "last_cooked": "2026-10-15",
            "cook_log": ["2026-10-15"]}
    r = client.put(f"/api/v1/recipes/{RID}/annotations", json=body, headers=AUTH)
    assert r.status_code == 200
    merged = r.json()
    assert recipe_file.read_bytes() == before  # never rewritten
    sidecar = json.loads((folder / f"{RID}.user.json").read_text(encoding="utf-8"))
    assert sidecar["favorite"] is True and "updated_at" in sidecar
    assert merged["status"] == "ready"
    assert merged["cook_notes"] == ["Less chilli."]
    assert merged["user"]["favorite"] is True and merged["user"]["last_cooked"] == "2026-10-15"
    assert validate_recipe(merged).ok
    assert r.headers["etag"] != etag_before
    # partial update keeps the other fields
    r2 = client.put(f"/api/v1/recipes/{RID}/annotations", json={"favorite": False}, headers=AUTH).json()
    assert r2["user"]["favorite"] is False and r2["cook_notes"] == ["Less chilli."]
    # the change shows up in the listing for sync
    s = client.get("/api/v1/recipes", headers=AUTH).json()["recipes"][0]
    assert s["status"] == "ready" and s["updated_at"] >= sidecar["updated_at"]


def test_annotations_reject_unknown_fields(client):
    r = client.put(f"/api/v1/recipes/{RID}/annotations", json={"rating": 5}, headers=AUTH)
    assert r.status_code == 422


def test_invalid_files_are_skipped_not_fatal(folder, caplog):
    (folder / "broken.json").write_text("{not json", encoding="utf-8")
    bad = json.loads((folder / f"{RID}.json").read_text(encoding="utf-8"))
    bad["id"] = "bad-units"
    bad["ingredients"][0]["unit"] = "handful"
    (folder / "bad-units.json").write_text(json.dumps(bad), encoding="utf-8")
    other = dict(bad, id="something-else")
    (folder / "mismatch.json").write_text(json.dumps(other), encoding="utf-8")
    lib = RecipeLibrary(folder)
    lib.load_all()
    assert lib.ids() == [RID]
    assert set(lib.invalid) == {"broken", "bad-units", "mismatch"}
    assert "doesn't match the file name" in lib.invalid["mismatch"]
    assert any("Skipping" in r.message for r in caplog.records)


def test_image_endpoint(client, folder, library):
    assert client.get(f"/api/v1/recipes/{RID}/image", headers=AUTH).status_code == 404
    (folder / f"{RID}.jpg").write_bytes(b"\xff\xd8\xff fake jpeg")
    library.reload([RID])
    assert client.get(f"/api/v1/recipes/{RID}", headers=AUTH).json()["image"] == f"{RID}.jpg"
    r = client.get(f"/api/v1/recipes/{RID}/image", headers=AUTH)
    assert r.status_code == 200 and r.content.startswith(b"\xff\xd8")


def test_watcher_picks_up_changes(folder, library):
    thread, stop = start_watcher(library)
    try:
        time.sleep(0.5)
        new = json.loads((folder / f"{RID}.json").read_text(encoding="utf-8"))
        new["id"] = "second-recipe"
        new["title"] = "Second"
        (folder / "second-recipe.json").write_text(json.dumps(new), encoding="utf-8")
        deadline = time.time() + 10
        while time.time() < deadline and "second-recipe" not in library.ids():
            time.sleep(0.1)
        assert "second-recipe" in library.ids()
        (folder / "second-recipe.json").unlink()
        deadline = time.time() + 10
        while time.time() < deadline and "second-recipe" in library.ids():
            time.sleep(0.1)
        assert "second-recipe" not in library.ids()
    finally:
        stop.set()
        thread.join(timeout=5)


def test_pairing_page_only_on_localhost(library):
    local = TestClient(create_app(library, TOKEN, pairing=[("u", "mise://pair?url=u&token=secret")], local_hosts={"testclient"}))
    assert "<svg" in local.get("/").text
    remote = TestClient(create_app(library, TOKEN, pairing=[("u", "mise://pair?url=u&token=secret")], local_hosts=set()))
    assert "secret" not in remote.get("/").text


def test_pairing_helpers():
    uri = pairing_uri("http://192.168.1.20:8765", "a+b/c")
    assert uri == "mise://pair?url=http%3A%2F%2F192.168.1.20%3A8765&token=a%2Bb%2Fc"
    assert len(terminal_qr(uri).splitlines()) > 10


def test_id_for_path(tmp_path):
    assert id_for_path(tmp_path / "abc.json") == "abc"
    assert id_for_path(tmp_path / "abc.user.json") == "abc"
    assert id_for_path(tmp_path / "abc.source.txt") == "abc"
    assert id_for_path(tmp_path / "abc.JPG") == "abc"
    assert id_for_path(tmp_path / ".tmp-123.json") is None
    assert id_for_path(tmp_path / "Bad Name.json") is None


def test_edits_are_stored_served_and_validated(client, folder):
    edits = {"yield": {"base_servings": 4}, "ingredients": {"0009": {"name": "cinnamon sticks", "amount": 2}},
             "checked_flags": ["ingredients[8].amount"]}
    r = client.put(f"/api/v1/recipes/{RID}/annotations", json={"edits": edits, "reviewed": True}, headers=AUTH)
    assert r.status_code == 200
    served = r.json()
    assert served["user"]["edits"]["ingredients"]["0009"]["amount"] == 2
    assert served["yield"]["base_servings"] == 2  # served as converted; the app applies the edits
    side = json.loads((folder / f"{RID}.user.json").read_text(encoding="utf-8"))
    assert side["edits"]["yield"] == {"base_servings": 4}  # stored by its JSON name, not yield_
    assert client.get(f"/api/v1/recipes/{RID}", headers=AUTH).json()["user"]["edits"]["checked_flags"] == ["ingredients[8].amount"]

    bad = {"edits": {"ingredients": {"0002": {"name": "ginger", "amount": 3, "amount_max": 1, "unit": "tsp"}}}}
    r = client.put(f"/api/v1/recipes/{RID}/annotations", json=bad, headers=AUTH)
    assert r.status_code == 422 and "less than amount" in r.text
    # Other annotations leave the edits alone; {} clears them.
    client.put(f"/api/v1/recipes/{RID}/annotations", json={"favorite": True}, headers=AUTH)
    assert client.get(f"/api/v1/recipes/{RID}", headers=AUTH).json()["user"]["edits"]["yield"]["base_servings"] == 4
    client.put(f"/api/v1/recipes/{RID}/annotations", json={"edits": {}}, headers=AUTH)
    assert "edits" not in client.get(f"/api/v1/recipes/{RID}", headers=AUTH).json()["user"]
