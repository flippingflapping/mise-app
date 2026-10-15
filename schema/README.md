# Recipe schema (v1)

`recipe.schema.json` is **the contract** between the converter, the server and the app.
It is JSON Schema 2020-12. Every recipe file carries `"schema_version": 1`.

Mirrors (kept in sync by tests on both sides — `pytest shared` and `./gradlew :core:test`):

* Python / Pydantic: `shared/mise_shared/models.py`
* Kotlin / kotlinx.serialization: `android/core/src/main/kotlin/app/mise/core/model/Recipe.kt`

## Recipe

| Field | Required | Notes |
|---|---|---|
| `schema_version` | ✔ | `1` |
| `id` | ✔ | slug, `^[a-z0-9]+(-[a-z0-9]+)*$`, also the file name (`<id>.json`) |
| `title` | ✔ | |
| `description` | | one or two sentences |
| `yield` | ✔ | `{ base_servings > 0, label? }` → "Serves 2 portions of kothu" |
| `times` | | `{ prep_minutes?, cook_minutes?, total_minutes? }` |
| `advance_prep` | | strings shown in a banner at the top ("Marinate the lamb overnight") |
| `tags` | | lower-case strings |
| `ingredients` | ✔ | flat list; the UI groups by `group` in first-seen order |
| `steps` | ✔ | at least one |
| `notes` | | the author's notes / tips / variations |
| `cook_notes` | | **your** notes, separate from the author's |
| `related_recipes` | | ids of linked recipes (may point to recipes you don't have yet) |
| `source` | | `{ type: book/web/family/other, title?, author?, page?, url?, original_filename? }` |
| `source_text_sha256` | | sha256 of the raw input (line endings normalised to `\n`) — used for idempotent conversion |
| `review_flags` | | `[{ path, message }]` — anything the converter was unsure about |
| `status` | ✔ | `needs_review` or `ready` |
| `image` | | optional image file name next to the recipe |
| `user` | | *served only*: annotations merged in from `<id>.user.json` |
| `created_at`, `updated_at` | | ISO 8601 date-times |

## Ingredient

| Field | Required | Notes |
|---|---|---|
| `id` | ✔ | 4-digit string, unique within the recipe (`"0001"`) |
| `group` | | section name ("Marinade") |
| `name` | ✔ | full name ("lamb neck or shoulder fillets") |
| `short_name` | ✔ | how it reads in a step ("lamb") |
| `prep` | | "diced into 4cm (1½in) pieces" |
| `amount` | ✔ (nullable) | number, or `null` for "to taste" |
| `amount_max` | | upper end of a range ("10–12") |
| `unit` | ✔ (nullable) | `g kg ml l tsp tbsp cup fl_oz oz lb pinch`, or `null` for countable items |
| `optional` | | default `false` |
| `substitutes` | | "or 3 tbsp canned chopped tomatoes" |
| `scales` | | default `true`; `false` for things that shouldn't scale |
| `aisle` | | shopping-list aisle (phase 2) |

The same ingredient used in two roles gets two ids (ginger in the marinade and in the curry).

## Step

| Field | Required | Notes |
|---|---|---|
| `id` | ✔ | unique within the recipe (`s1`, `s2`, …) |
| `title` | ✔ | short label; used as timer name and cooking-mode header |
| `content` | ✔ | instruction text with placeholders |
| `timer_seconds` | | positive; the lower bound for a range |
| `timer_max_seconds` | | ≥ `timer_seconds`; upper bound for "15–20 minutes" |
| `heat` | | `low medium_low medium medium_high high` |

### Placeholders

* `{0001}` → scaled amount + unit + short_name, e.g. "500 g lamb" (a tappable chip in the app)
* `{0001:name}` → short_name only ("return the lamb to the pan")

Validators reject placeholders that reference unknown ids or are malformed, and warn about
ingredients that no step mentions. The app renders an unknown id as a ⚠ warning chip.

## The `<id>.user.json` sidecar

Written by the server when the app saves annotations. Never touches `<id>.json`.

```json
{ "favorite": true, "reviewed": true, "cook_notes": ["less chilli"],
  "last_cooked": "2026-10-15", "cook_log": ["2026-10-01", "2026-10-15"],
  "updated_at": "2026-10-15T19:02:11Z" }
```

On read the server merges it in: `cook_notes` replace the recipe's, `reviewed: true` sets
`status` to `ready`, and the rest appears under `user`.

## Evolving the schema

1. Bump `schema_version` (and the `const`), document the change here.
2. Add a `migrateNtoN+1` step in `RecipeMigrations.kt` and a test for it.
3. Update both model mirrors; the contract tests will tell you if they drift.

The app ignores unknown fields and unknown enum values, so a newer file degrades gracefully on
an older app.
