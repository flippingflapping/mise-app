# Mise — build plan

Personal recipe app for Android, a PC companion server, and a one-time LLM converter.
This file is the living plan: architecture, layout, milestones, assumptions, and the
decisions I made on my own (with the choice and why). Progress is tracked at the bottom.

---

## 1. Architecture

```
  raw recipe text (.txt/.md)                         Android phone
  ┌──────────┐   convert run    ┌──────────────┐     ┌────────────────────────────────┐
  │  inbox/  │ ───────────────► │  recipes/    │     │  UI (Compose, MVVM, UDF)       │
  └──────────┘  Claude API      │  <id>.json   │     │        │                       │
        ▲       (sync | batch)  │  <id>.source │     │  RecipeRepository (offline-1st)│
        │                       │  <id>.user   │◄────┤   ├─ Room cache (always read)  │
   you paste                    └──────┬───────┘ LAN │   └─ RecipeSource (pluggable)  │
                                       │ watch      │      ├─ LanServerSource (default)│
                                ┌──────▼───────┐ HTTP│      ├─ FolderImportSource (SAF)│
                                │ mise server  │◄────┤      ├─ BundledSampleSource     │
                                │ FastAPI      │Bearer      └─ CloudApiSource (stub)   │
                                └──────────────┘     │  :core (pure Kotlin) scaling,  │
                                                     │  units, formatting, placeholders│
                                                     └────────────────────────────────┘
```

* **The contract** is `schema/recipe.schema.json` (JSON Schema 2020-12, `schema_version: 1`).
  Python (Pydantic) and Kotlin (kotlinx.serialization) models mirror it; tests on both sides
  validate the fixtures against the schema *and* round-trip them through the models.
* **The recipes folder is the source of truth.** Plain JSON on disk, one file per recipe, plus
  the original text and a user sidecar. Easy to back up, diff, sync, or hand-edit.
* **The app never talks to an LLM.** All conversion happens on the PC.
* **The app reads only from Room.** Sources feed Room; the UI observes Room. That is what
  makes it fully usable in the kitchen with the PC off.

### Files in the recipes folder

| File | Written by | Purpose |
|---|---|---|
| `<id>.json` | converter (or you) | the recipe — never rewritten by the app or server |
| `<id>.source.txt` | converter | the raw input, for "View original" |
| `<id>.user.json` | server (from the app) | annotations: my notes, favorite, reviewed, cook log |
| `<id>.jpg/.png/.webp` | you (optional) | recipe photo |
| `.mise/manifest.json` | converter | idempotency manifest (source → hash → id → status) and the in-flight batch id, so an interrupted run resumes |
| `.mise/failed/…` | converter | raw model output of failed conversions, for inspection |

---

## 2. Repo layout

```
/schema/recipe.schema.json      the contract (+ README.md documenting every field)
/fixtures/raw/                  messy raw input
/fixtures/expected/             reference structured output
/shared/mise_shared/            Python: Pydantic models, schema + semantic validation, config
/converter/mise_converter/      Python CLI `convert` (run / status / retry)
/converter/prompts/             versioned conversion prompt
/server/mise_server/            Python FastAPI server `mise-server`
/{shared,converter,server}/tests/
/pyproject.toml                 ONE Python project/venv for converter + server
/scripts/                       setup / run scripts for PowerShell and bash
/android/                       Gradle project: :core (pure Kotlin/JVM) + :app (Android)
/docs/                          converting guide, screenshots placeholders
/recipes/  /inbox/              gitignored defaults
```

---

## 3. Milestones & progress

| # | Milestone | Status |
|---|---|---|
| M1 | Contract: schema, fixtures, Pydantic + Kotlin models, cross-validation tests | ✅ |
| M2 | `:core` scaling, units, formatting, placeholders, timer state machine + tests | ✅ |
| M3 | Converter: sync, batch + resume, validators, retry loop, manifest | ✅ |
| M4 | Server: endpoints, auth, sidecars, ETags, folder watching, QR pairing | ✅ |
| M5 | App read-only: Library + Detail, bundled sample, LAN source, Room cache | ✅ |
| M6 | Cooking mode + timers (foreground service, notifications, multi-timer tray) | ✅ |
| M7 | Settings, QR pairing, folder import, annotations sync, polish | ✅ |
| M8 | Docs: README setup guide, converting guide | ✅ |
| F1 | Add recipes from the phone (server inbox + background conversion) | ✅ |
| F2 | Shopping lists from several recipes | ✅ |
| F3 | Meal planner: schedule several dishes backwards from a serve time | ✅ |

---

## 4. Toolchain (checked at build time, Oct 2026)

| Thing | Version | Note |
|---|---|---|
| Gradle | 9.8.1 (wrapper) | current stable |
| Android Gradle Plugin | 9.4.1 | current stable; uses AGP 9 built-in Kotlin |
| Kotlin | 2.4.21 | |
| KSP | 2.3.12 | (KSP versions are decoupled from Kotlin since 2.3) |
| compileSdk / targetSdk | 37 | latest stable platform; minSdk 26 |
| Compose BOM | 2026.09.00 | Material 3 |
| Hilt | 2.60.1 · Room 2.8.5 · WorkManager 2.12.0 · DataStore 1.2.1 · Ktor 3.6.0 · kotlinx.serialization 1.11.0 | |
| Python | ≥ 3.10 | anthropic 1.15, FastAPI 0.143, Pydantic 2.14, jsonschema 4.26, watchfiles 1.3 |
| Claude model | `claude-sonnet-5-5` | current Sonnet tier per docs.claude.com models overview |

---

## 5. Decisions I made (open questions, answered)

**Data model**
1. **Extra optional fields** beyond the brief: `image` (filename), `ingredient.aisle` (for the
   phase-2 shopping list), and `user` (annotations merged in by the server; never written by the converter).
2. **Schema strictness**: the JSON Schema is strict (`additionalProperties: false`) so the converter
   and server catch typos. The *app* is lenient (`ignoreUnknownKeys`) so a newer recipe file never
   crashes an older app. Older `schema_version`s are migrated in `:core` (`RecipeMigrations`); a
   newer version is parsed best-effort and flagged.
3. **Step ids** are free strings (`s1`…); ingredient ids are 4-digit strings as specified.
4. **`created_at`/`updated_at`/`source_text_sha256`/`status`** are filled in by converter code, not
   the model — the model never sees or invents them.

**Display logic (`:core`)**
5. **Countables**: whole numbers by default (min 1). Halves are allowed when the authored amount
   was already fractional, or when the noun is in a small "halvable" list (onion, lemon, lime,
   avocado, tomato, pepper, potato, apple, orange, cucumber, aubergine/eggplant, cabbage…).
6. **Spoons** never convert to ml / g, but they do promote/demote: tsp→tbsp at ≥ 3 tsp when the
   result is a clean fraction (or ≥ 6 tsp), tbsp→tsp below 1 tbsp, tbsp→cup at ≥ 16 tbsp in
   Original/US mode (in Metric mode ≥ 16 tbsp becomes ml, since a cup isn't metric).
7. **US volumes**: ≥ ¼ cup → cups (nearest ⅛), smaller → tbsp/tsp. fl oz is kept only in Original
   mode when authored that way.
8. **Rounding**: g/ml nearest 1 below 50, nearest 5 above (per brief); kg/l nearest 0.05 shown as a
   fraction glyph only on exact quarters, else a short decimal ("1.1 kg", "1.35 kg"); oz nearest ¼;
   lb nearest ⅛ (gives the brief's "2¼ lb lamb" and keeps 500 g honest as "1⅛ lb" rather than "1 lb");
   tsp nearest ⅛ (with ⅓/⅔), tbsp nearest ¼ (with ⅓/⅔), cups nearest ⅛ (with ⅓/⅔ — ⅓ cup is a real measure).
   **At scale 1 with no unit conversion, authored values are shown exactly as written.**
9. **Servings stepper**: steps of ½ at or below 2 servings, steps of 1 above; minimum ½.
   Scale-by-ingredient can produce any factor; the stepper snaps back onto the grid on the next tap.
10. **Placeholders**: `{0013}` with a single pinch reads "a pinch of salt"; null amounts read
    "salt, to taste"; unknown ids render a ⚠ warning chip with the raw id. No automatic
    pluralisation (short_name is authored at the base count).

**Converter**
11. **Structured outputs** (`output_config.format` with a JSON schema) rather than forced tool use:
    the current Sonnet (`claude-sonnet-5-5`) rejects forced `tool_choice`, and structured outputs
    guarantee parseable JSON. The docs cap a strict schema at 24 optional and 16 union-typed
    properties, so the converter sends an **LLM-facing schema** derived from the canonical one:
    every property required, `""`/`0` meaning "absent", only `amount` nullable and `unit`/`heat` as
    nullable enums. Code normalises that back into the canonical shape and then validates
    deterministically (schema + semantic checks). The model is never trusted.
12. **Prompt caching**: the long system prompt (instructions + worked fixture example) is one block
    with `cache_control`; the per-recipe text is the only variable part.
13. **Thinking/effort**: adaptive thinking (the model default) with `effort = "medium"` in config;
    `max_tokens = 32000`; sync mode streams to avoid HTTP timeouts.
14. **Refusal handling**: `stop_reason == "refusal"` is recorded as a failure. Sync mode opts into the
    server-side `fallbacks: "default"` beta (config `refusal_fallback = true`); the Batches API
    rejects that parameter so batch mode doesn't send it.
15. **Output location**: `convert run` writes straight into the recipes folder (`--out` defaults to
    it) so the server picks new recipes up immediately.
16. **Batch default**: `--batch` when more than 5 recipes are pending (configurable), else `--sync`.
17. **Retry-with-errors**: on validation failure, one follow-up turn sends the errors back. In batch
    mode, the retry runs synchronously after the batch ends (a second batch for a handful of fixes
    would add up to 24h latency for little saving).
18. **Ids**: the model proposes the slug; code slugifies it and de-duplicates against the library
    (`-2`, `-3`…). A re-conversion of the same source file keeps its previous id.

**Server**
19. **Port 8765**, token auto-generated (`secrets.token_urlsafe(24)`) into `config.toml` on first run.
20. **Sync protocol**: `GET /recipes` returns summaries with per-recipe ETags plus the full id list
    (so the app can delete recipes removed from the folder); the list itself carries an ETag, so an
    unchanged library costs one 304. `since` is supported for future cloud backends.
21. **Annotations merge**: the sidecar's `cook_notes` replace the converter's (the app edits the full
    list, seeded from the converter's suggestions); `reviewed: true` turns `status` into `ready`;
    favorite / last-cooked / cook log appear under `user`. The served `updated_at` and ETag reflect
    the sidecar, so annotation changes sync like any other change.
22. **Folder watching** with `watchfiles` (debounced reload of just the changed ids).
23. **Pairing QR** encodes `mise://pair?url=…&token=…`. The app registers that deep link, so even the
    stock phone camera can pair it; the in-app scanner uses Google's code scanner (no camera
    permission needed). Manual entry is the fallback.

**App**
24. **Single active source** (Settings: PC server / Folder / Demo). The bundled sample is loaded on
    first run so the app is never empty; it is replaced when you connect a real source.
25. **Annotations** live in Room first (works offline) and are pushed with a dirty flag when the source
    supports it (LAN). Folder and Demo sources keep annotations on the phone only.
26. **Timers**: a `specialUse` foreground service owns the ongoing notification while any timer runs;
    each timer also gets an exact alarm (`USE_EXACT_ALARM` on 33+, `SCHEDULE_EXACT_ALARM` ≤ 32,
    inexact fallback if denied) as a backstop if the process dies. Timer state is persisted, so the
    tray survives process death. Ranged timers: count to min → "Check now" (alert) → "+1 min" /
    "To max" / "Done". Waits over 3 hours (or overnight steps) get no countdown and point to the
    advance-prep banner.
27. **Two-pane** layout at ≥ 720 dp width (tablets, unfolded foldables) using window size, not a
    device check.
28. **Cleartext HTTP** is enabled app-wide (Android can't whitelist IP ranges); documented in the
    README with the trade-off. TODO left for self-signed HTTPS.
29. **Text size** setting multiplies on top of the system font scale (both apply).

---

## 6. Assumptions

* Single user, single household, trusted home Wi-Fi; the bearer token is a guard against
  housemates' devices and casual scans, not against a determined attacker on the LAN.
* Recipes are in English with metric/imperial mixes as in the fixture.
* The PC runs Windows, macOS or Linux with Python ≥ 3.10 installed.
* No Play Store distribution: the APK is sideloaded, which is why `USE_EXACT_ALARM` and a
  `specialUse` foreground service are acceptable without Play policy review.

---

## 7. Out of scope (designed for, not built)

PDF/photo import (the converter's input layer has a registry with stubs), cloud backend
(`CloudApiSource` stub), shopping list UI (data model ready: `aisle`, scaling engine), HTTPS,
recipe editing beyond notes and review status.

---

## 8. Progress log

_(updated after each milestone)_

**M1 — done.** `schema/recipe.schema.json` + docs; fixtures; Pydantic models + schema/semantic
validators (`pytest shared`: 18 tests); Kotlin models + lenient/canonical JSON + schema-version
migrations (`./gradlew :core:test`: contract tests validate fixtures and Kotlin output against the
schema with networknt json-schema-validator).
* Changed the expected fixture's cook note from "Try adding cumin seeds next time (about 1 tsp,
  with the whole spices)." to "Try adding cumin seeds next time." — the fixture is also the
  converter's few-shot example, and the original invented a quantity the source never gave.
* Added `source_text_sha256`, `created_at`, `updated_at` to the expected fixture.
* Gradle resolves Maven Central artifacts through Google's mirror first, because Maven Central's
  CDN returns HTTP 429 to some shared networks (it did in the build environment).

**M2 — done.** `:core` (pure Kotlin/JVM, no Android): `AmountRenderer` (scaling, Original/Metric/US,
promotion/demotion, rounding, countables, ranges, non-scaling, to taste), `Fractions` (glyphs, thirds),
`Servings` stepper + `ScaleByIngredient`, `StepRenderer` (placeholders → chips, warning chips for
missing ids, spoken text for TalkBack, ingredients-used-by-step), `KitchenTimer` state machine
(check-now / +1 min / to max / pause), `StepTimers` policy (overnight → advance prep), `RecipeSearch`,
`PairingUri`. 46 table-driven tests, including the brief's "2¼ lb lamb" and "10–12 curry leaves".

**M3 — done.** `convert run | status | retry`. Sync mode (threaded, `concurrency` in config) and
batch mode (Message Batches API; batch id persisted in the manifest before polling, so Ctrl+C or a
crash resumes the same batch). Structured outputs with an LLM-facing schema (within the documented
24-optional / 16-union limits — a test enforces it), cached system prompt with the fixture as worked
example (filled in from `fixtures/` at runtime so it can't drift), adaptive thinking at effort
`medium`. Deterministic post-validation; one retry turn with the errors (thinking blocks passed back
unchanged); raw output of failures kept in `.mise/failed/`. Refusal / max_tokens are not retried.
Tests: 28 (fake client for the pipeline, plus the real SDK against a mock HTTP transport to pin the
wire format). The opt-in live test is skipped without `ANTHROPIC_API_KEY`; **it has not been run in
the build environment (no key there)** — run `pytest -m live` once with your key.

**M4 — done.** `mise-server` (FastAPI + uvicorn): `/api/v1/health` (open), `/recipes` (summaries +
complete id list + list ETag → 304), `/recipes/{id}` (ETag/304), `/source`, `/image`,
`PUT /annotations` (sidecar only; merged on read). Constant-time bearer-token check. Schema +
semantic validation on load; invalid files logged and skipped. `watchfiles` watcher reloads only the
changed ids. Startup banner prints the LAN URL, token and a terminal QR; `http://localhost:<port>/`
shows the QR as SVG **only to the PC itself** (it contains the token). Run scripts for bash and
PowerShell in `scripts/`. 16 server tests (incl. a real watcher test) + a manual smoke test of the
binary. The PowerShell scripts could not be executed in the (Linux) build environment.

**M5–M7 — done** (built together; one commit for the app, one for verification and fixes).
* Data: `RecipeSource` interface + `LanServerSource` (Ktor/OkHttp, list ETag → 304, friendly errors),
  `FolderImportSource` (SAF folder and/or individually imported files), `BundledSampleSource` (the
  fixture itself, packaged from `fixtures/` at build time), `CloudApiSource` stub. Offline-first
  `RecipeRepository`: Room is the only thing the UI reads; sync pushes dirty annotations, then pulls by
  ETag and removes recipes the source no longer has. Room v2 with a hand-written 1→2 migration and a
  migration test against the genuinely exported v1 schema. Sync on open, pull-to-refresh, optional
  WorkManager job (6 h, unmetered).
* UI (Compose, Material 3, dynamic colour, dark mode): Library (search, tag chips, favourites,
  recently cooked, needs-review badge, two panes ≥ 720 dp), Detail (everything in §4.2), Cooking mode
  (pager, huge text, ingredients-used, keep-awake, system bars hidden), Settings (QR via Google code
  scanner + `mise://pair` deep link with confirmation, manual entry, test connection, folder picker),
  View original.
* Timers: `TimerStore` (persisted) + `TimerController` + `specialUse` foreground service + exact-alarm
  backstop + alarm-channel notifications with +1 min / To max / Done actions. If notifications are
  blocked, the service rings and vibrates itself.
* Verification: 33 app tests (repository, HTTP client with MockEngine, sources, Room migration, timer
  persistence, and Robolectric UI tests that render the real screens and write the README
  screenshots); a live end-to-end test of the Kotlin client against the running Python server
  (passed); `lintDebug` clean with `checkDependencies` (covers `:core`).

Things worth knowing:
* **No emulator/device in the build environment** (no KVM), so the app has been verified with
  Robolectric rendering, unit tests and lint — not on a physical phone. Foreground-service timing
  with the screen locked, the code scanner and SAF folder picking need a real device to confirm.
* Lint caught `URLEncoder.encode(String, Charset)` (API 33) in both `:app` and `:core`; it would have
  crashed pairing and sync on Android 8–12. Fixed, and lint now checks `:core` too.
* The screenshots revealed a timer-tray layout bug (squeezed countdown); fixed.
* Under Robolectric, any text field inside a dialog over the detail screen never lets Compose go idle
  (reproduced with stock Material components only). The scale-by-ingredient dialog is therefore
  tested on its own plus through the ViewModel. I also swapped its unit dropdown for filter chips.
* Room's exported schemas are added to **debug** assets because Robolectric only reads the variant's
  merged assets (a few KB; not in release builds).
* Gradle uses Google's Maven Central mirror first (Maven Central returned HTTP 429 here).

**M8 — done.** README (5-minute PC + phone setup for a non-Android developer, firewall, autostart,
security notes, backups, troubleshooting, developer notes), `docs/converting.md`, screenshots in
`docs/screenshots/` generated from the real UI, CI workflow (`.github/workflows/ci.yml`) that runs
all tests and publishes the debug APK as a downloadable artifact.

## 9. Next steps (not built)

* PDF / photo import in the converter (reader stubs exist in `inputs.py`).
* Optional HTTPS with a self-signed certificate pinned at pairing.
* `CloudApiSource`.

**Fix (after first real-device use): pairing QR used an unreachable 172.x address.** The server
picked the default-route interface, which on Windows with WSL/Hyper-V (or Docker/VPNs) is a virtual
adapter. It now enumerates adapters by name (`ifaddr`), ranks real Wi-Fi/Ethernet and 192.168/10.x
first, flags virtual adapters, prints every address with its adapter name, shows a QR per address on
the localhost pairing page, and accepts an override (`--advertise` / `server.advertise_host`).

**Fix: Android 17 local network protection.** Targeting API 37 means LAN connections are blocked
(they time out) unless the user grants `ACCESS_LOCAL_NETWORK` ("Nearby devices", runtime). The app
now declares it, asks on launch when the PC server is the source and before pairing / test / sync,
and explains the permission (with a link to App info) when it's missing. Found on a real device:
the phone's browser reached the server but the app timed out.

**F1–F3 — added after first use.**
* *Add recipe:* the app posts pasted/shared text to `POST /api/v1/recipes/import`; the server writes
  it to `inbox/from-phone/` and, only if its own process has `ANTHROPIC_API_KEY`, converts it on a
  worker thread with the existing sync converter (manifest stays the source of truth). The app polls
  `GET /api/v1/imports/{id}` and syncs when done. Without a key the file waits for `convert run`.
  Jobs are in memory; a server restart forgets them but the inbox file remains.
* *Shopping lists* (`:core` `ShoppingList`): ingredients are merged by a normalised name (text after
  a comma or in brackets dropped), summed per dimension in base units and rendered with the usual
  formatter; counts and "to taste" stay separate; aisles from keyword rules. The list is a snapshot
  (Room tables, schema v3) so ticking survives recipe edits.
* *Meal planner* (`:core` `FeastPlanner`): reverse list scheduling for **one cook**. Each dish gets a
  mise-en-place task then its steps in order; steps with a timer of ≥ 5 min are hands-off after
  2 active minutes, so other dishes' active work fills those gaps. Untimed steps are estimated from
  length and ingredient count. Overnight/very long waits become "ahead of time" tasks. Done tasks are
  stored; the plan is recomputed live, with a "minutes behind" figure and a one-tap serve-time shift.
  Reminders use exact alarms (falls back to inexact if not allowed). Heuristics, not ground truth.

**Meal planner: blocks instead of minute-by-minute times** (after first use: a list of times like
9:13 and 9:43 was stressful to read). All mise en place now comes first as one block, ending when
the cooking starts. The cooking is cut into blocks at gaps of 10+ minutes with nothing to do (shown
as "Free time"), then by dish; stretches of a dish under 10 minutes join the neighbouring block, so
juggling several dishes reads as one block ("Bring it all together" for 3+ dishes). Block starts are
rounded down to 5 minutes (the first to 15) and durations shown as "about 20 min" / "about 1½ hours".
Inside a block: an ordered checklist with no clock times. The first block with anything left is the
"Now" card; others are one line each. "Running late" only appears from 10 minutes, rounded to 5,
and pushing dinner back lands on a round 5 minutes. Reminders fire per block, not per step. The
scheduler underneath is unchanged apart from prep moving to the front.

**Calm / Pro style per meal plan.** Calm = prep-first blocks above. Pro = the original
time-efficient schedule (each dish's mise en place scheduled like a step, right before the dish, so it
can fill other dishes' hands-off time), still shown as blocks but with exact times on each step and
no separate prep block. Stored per plan (Room v4, `feasts.style`, default CALM); new plans default to
the style of the most recently created plan, so no extra setting.
