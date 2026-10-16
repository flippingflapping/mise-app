import json

from conftest import FakeClient, fixture_llm_output, response
from mise_converter import manifest as mf
from mise_converter.cost import estimate
from mise_converter.engine import Converter
from mise_shared.validation import validate_recipe

RID = "sri-lankan-lamb-curry-for-kothu"


def read_recipe(conv, rid=RID):
    return json.loads((conv.out_dir / f"{rid}.json").read_text(encoding="utf-8"))


def test_sync_conversion_end_to_end(make_converter):
    client = FakeClient([response(fixture_llm_output())])
    conv = make_converter(client)
    docs, _ = conv.plan()
    assert [d.key for d in docs] == ["lamb-curry-for-kothu.txt"]
    report = conv.run_sync(docs)
    assert report.needs_review == [RID] and not report.failed

    recipe = read_recipe(conv)
    assert validate_recipe(recipe).ok
    assert recipe["status"] == "needs_review"  # the cinnamon-stick flag
    assert recipe["source"]["original_filename"] == "lamb-curry-for-kothu.txt"
    assert recipe["source_text_sha256"] == docs[0].sha256
    assert (conv.out_dir / f"{RID}.source.txt").read_text(encoding="utf-8") == docs[0].text
    e = conv.manifest.get("lamb-curry-for-kothu.txt")
    assert e.status == mf.NEEDS_REVIEW and e.recipe_id == RID and e.prompt_version == "1"
    # the request was cacheable and used structured outputs
    assert client.calls[0]["system"][0]["cache_control"]["type"] == "ephemeral"


def test_rerun_is_noop_until_source_changes(make_converter):
    conv = make_converter(FakeClient([response(fixture_llm_output())]))
    conv.run_sync(conv.plan()[0])
    conv2 = make_converter(FakeClient())  # fresh process: reloads manifest
    assert conv2.plan()[0] == []
    assert len(conv2.plan(force=True)[0]) == 1
    src = conv2.inbox / "lamb-curry-for-kothu.txt"
    src.write_text(src.read_text(encoding="utf-8").replace("\n", "\r\n"), encoding="utf-8")
    assert conv2.plan()[0] == []  # line endings don't count as a change
    src.write_text(src.read_text(encoding="utf-8") + "\nServe with roti.\n", encoding="utf-8")
    assert len(conv2.plan()[0]) == 1


def test_changed_source_keeps_id_and_created_at(make_converter):
    conv = make_converter(FakeClient([response(fixture_llm_output())]))
    conv.run_sync(conv.plan()[0])
    created = read_recipe(conv)["created_at"]
    src = conv.inbox / "lamb-curry-for-kothu.txt"
    src.write_text(src.read_text(encoding="utf-8") + "\nextra\n", encoding="utf-8")
    out = fixture_llm_output()
    out["id"] = "a-different-slug"
    conv2 = make_converter(FakeClient([response(out)]))
    conv2.run_sync(conv2.plan()[0])
    assert read_recipe(conv2)["created_at"] == created
    assert not (conv2.out_dir / "a-different-slug.json").exists()


def test_validation_failure_retries_once_with_errors(make_converter):
    bad = fixture_llm_output()
    bad["steps"][0]["content"] += " Add {9999}."
    client = FakeClient([response(bad), response(fixture_llm_output())])
    conv = make_converter(client)
    report = conv.run_sync(conv.plan()[0])
    assert report.needs_review == [RID]
    assert len(client.calls) == 2
    retry_msgs = client.calls[1]["messages"]
    assert [m["role"] for m in retry_msgs] == ["user", "assistant", "user"]
    assert retry_msgs[1]["content"][0]["type"] == "thinking"  # thinking passed back unchanged
    assert "{9999}" in retry_msgs[2]["content"][0]["text"]
    assert (conv.state_dir / "failed" / "lamb-curry-for-kothu.txt.attempt1.errors.txt").exists()


def test_second_failure_marks_failed_and_keeps_raw_output(make_converter):
    bad = fixture_llm_output()
    bad["ingredients"][0]["unit"] = "handful"
    client = FakeClient([response(bad), response(bad)])
    conv = make_converter(client)
    report = conv.run_sync(conv.plan()[0])
    assert report.failed == ["lamb-curry-for-kothu.txt"]
    e = conv.manifest.get("lamb-curry-for-kothu.txt")
    assert e.status == mf.FAILED and "unit" in e.error
    raw = conv.state_dir / "failed" / "lamb-curry-for-kothu.txt.attempt2.output.json"
    assert "handful" in raw.read_text(encoding="utf-8")
    assert not (conv.out_dir / f"{RID}.json").exists()
    # `run` doesn't retry failures; `retry` does
    conv2 = make_converter(FakeClient([response(fixture_llm_output())]))
    assert conv2.plan()[0] == []
    failed = conv2.failed_docs()
    assert [d.key for d in failed] == ["lamb-curry-for-kothu.txt"]
    assert conv2.run_sync(failed).needs_review == [RID]


def test_refusal_is_not_retried(make_converter):
    client = FakeClient([response("", stop_reason="refusal")])
    conv = make_converter(client)
    report = conv.run_sync(conv.plan()[0])
    assert report.failed and len(client.calls) == 1
    assert "refusal" in conv.manifest.get("lamb-curry-for-kothu.txt").error


def test_unreferenced_ingredient_becomes_review_flag(make_converter):
    out = fixture_llm_output()
    out["review_flags"] = []
    out["ingredients"].append({**out["ingredients"][-1], "id": "0027", "name": "bay leaf", "short_name": "bay leaf"})
    conv = make_converter(FakeClient([response(out)]))
    conv.run_sync(conv.plan()[0])
    r = read_recipe(conv)
    assert r["status"] == "needs_review"
    assert any("bay leaf" in f["message"] for f in r["review_flags"])


def test_clean_recipe_is_ready(make_converter):
    out = fixture_llm_output()
    out["review_flags"] = []
    conv = make_converter(FakeClient([response(out)]))
    report = conv.run_sync(conv.plan()[0])
    assert report.converted == [RID]
    assert read_recipe(conv)["status"] == "ready"


def test_id_collision_with_existing_recipe_gets_suffix(make_converter):
    conv = make_converter(FakeClient([response(fixture_llm_output())]))
    conv.out_dir.mkdir(parents=True, exist_ok=True)
    (conv.out_dir / f"{RID}.json").write_text("{}", encoding="utf-8")  # someone else's file
    conv.run_sync(conv.plan()[0])
    assert (conv.out_dir / f"{RID}-2.json").exists()


def test_batch_submit_interrupt_and_resume(make_converter):
    client = FakeClient(batch_states=["in_progress", "in_progress", "ended"])
    conv = make_converter(client)
    docs, _ = conv.plan()
    batch_id = conv.submit_batch(docs)
    req = client.batches[batch_id][0]
    assert "fallbacks" not in req["params"]  # not accepted by the Batches API
    assert req["params"]["system"][0]["cache_control"]["type"] == "ephemeral"
    # interrupted before results: a new process resumes the same batch, without resubmitting
    report = conv.collect_batch(wait=False)
    assert report.still_running == batch_id
    conv2 = make_converter(client)
    assert conv2.manifest.open_batch().id == batch_id
    assert conv2.plan()[0] == []
    assert conv2.manifest.get("lamb-curry-for-kothu.txt").status == mf.SUBMITTED
    report = conv2.collect_batch(wait=True)
    assert report.needs_review == [RID]
    assert len(client.batches) == 1
    assert conv2.manifest.open_batch() is None
    assert make_converter(FakeClient()).plan()[0] == []


def test_batch_result_failure_retries_synchronously(make_converter):
    bad = fixture_llm_output()
    bad["steps"][1]["timer_max_seconds"] = 10
    client = FakeClient([response(fixture_llm_output())], batch_states=["ended"])
    conv = make_converter(client)
    docs, _ = conv.plan()
    bid = conv.submit_batch(docs)
    client.batch_responses[client.batches[bid][0]["custom_id"]] = response(bad)
    report = conv.collect_batch()
    assert report.needs_review == [RID]
    assert len(client.calls) == 1  # the one sync retry
    assert "less than timer_seconds" in client.calls[0]["messages"][2]["content"][0]["text"]


def test_batch_source_changed_while_in_flight(make_converter):
    client = FakeClient(batch_states=["ended"])
    conv = make_converter(client)
    conv.submit_batch(conv.plan()[0])
    src = conv.inbox / "lamb-curry-for-kothu.txt"
    src.write_text("changed", encoding="utf-8")
    conv.collect_batch()
    assert conv.manifest.get("lamb-curry-for-kothu.txt").status == mf.PENDING
    assert len(conv.plan()[0]) == 1


def test_choose_batch_by_threshold(make_converter):
    conv = make_converter(FakeClient())
    assert conv.choose_batch(5, None) is False
    assert conv.choose_batch(6, None) is True
    assert conv.choose_batch(50, False) is False


def test_cost_estimate(make_converter):
    conv = make_converter(FakeClient())
    docs, _ = conv.plan()
    sync = estimate(docs, 20000, conv.cfg.converter.pricing, batch=False)
    batch = estimate(docs, 20000, conv.cfg.converter.pricing, batch=True)
    assert 0 < batch.usd < sync.usd
    assert abs(batch.usd - sync.usd / 2) < 1e-9
    assert "roughly $" in sync.describe()


def test_unsupported_inputs_are_skipped(make_converter, workspace):
    conv = make_converter(FakeClient())
    (conv.inbox / "scan.pdf").write_bytes(b"%PDF-1.7")
    (conv.inbox / "notes.docx").write_bytes(b"PK")
    (conv.inbox / "empty.txt").write_text("  ", encoding="utf-8")
    docs, skipped = conv.plan()
    assert [d.key for d in docs] == ["lamb-curry-for-kothu.txt"]
    assert any("PDF import is designed for" in s for s in skipped)
    assert any("unsupported file type" in s for s in skipped)
    assert any("empty" in s for s in skipped)
