# Converting your recipes

The converter turns raw recipe text — pasted from a cookbook scan, a website, an old note — into the
app's structured format, using Claude. Each recipe is converted **once**; after that it's plain JSON
in your `recipes/` folder, and the app never needs the AI again.

In the commands below, `convert` means:

* Windows: `powershell -ExecutionPolicy Bypass -File scripts\convert.ps1`
* macOS / Linux: `scripts/convert.sh`

## 1. One-time: an API key

1. Create a key at <https://platform.claude.com/> (Settings → API keys) and add some credit.
2. Set it in the terminal you'll run the converter from (it's read from the environment only — never
   stored in a file):
   * Windows PowerShell: `$env:ANTHROPIC_API_KEY = "sk-ant-..."`
   * macOS / Linux: `export ANTHROPIC_API_KEY=sk-ant-...`

## 2. Put recipes in the inbox

One recipe per `.txt` or `.md` file in `inbox/` (sub-folders are fine). Name files however you like
(`lamb-curry-for-kothu.txt`). Don't tidy them up — the converter is built for messy pastes:
ingredient lists split around the method, missing or misplaced step numbers, SHOUTED headings, dual
units like `500g (1lb 2oz)`, and your own scribbled notes ("try cumin next time") mixed in. If you know
the source (book title, page, URL), adding it to the text helps.

PDFs and photos are skipped for now (the input layer is ready for them; see `converter/mise_converter/inputs.py`).

## 3. Convert

```
convert run
```

It lists what's new or changed, shows a rough cost, and asks before sending anything:

```
Model: claude-sonnet-5-5 (effort medium)
  • lamb-curry-for-kothu.txt
1 recipe(s) via synchronous requests: ~758 input tokens (+ ~7,195 cached system-prompt tokens per request), ~6,000 output tokens incl. thinking → roughly $0.08
Go ahead? [y/N]
```

* **A few recipes** are converted straight away, a few at a time (`--sync`).
* **More than 5** go through the **Message Batches API** (`--batch`), which costs half as much but can
  take a while (usually minutes, at most 24 hours). The batch id is saved before waiting, so you can
  press **Ctrl+C** or close the terminal at any time — running `convert run` again picks the same batch
  up, without paying twice. `--no-wait` submits and exits straight away.
* Force either mode with `--sync` / `--batch`. Skip the question with `--yes`. See what would happen
  without sending anything with `--dry-run` (the exact requests are written to
  `recipes/.mise/dry-run-requests.json`).

Results land in `recipes/` as `<id>.json` plus `<id>.source.txt` (the original text, for the app's
"View original text"). If the server is running, the phone sees them on its next sync.

## 4. Review

The converter is told never to invent quantities, ingredients or times. When something is missing or
ambiguous it makes the most reasonable reading and **flags it**, for example:

> Source says "2 9cm (3½in) cinnamon stick" — assumed 1 stick. Confirm whether it should be 2.

Code then double-checks the result (it doesn't trust the model): the JSON schema, that every `{0001}`
amount placeholder in the steps points at a real ingredient, unique ids, sensible timers. Ingredients
that no step uses get a flag too. Any recipe with flags is marked **Needs review** in the app.

In the app, open the recipe, read the *Check the conversion* card, compare with **View original
text**, and tap **Mark as reviewed**. To actually fix something, edit `recipes/<id>.json` in any text
editor (field guide: [schema/README.md](../schema/README.md)) — the server reloads it instantly and skips
it, with a log message, if you break it.

Your own scribbles from the pasted text ("try cumin seeds next time") end up in **My notes** in the
app, not in the method.

## 5. Status, failures and re-runs

```
convert status     # every inbox file: pending / submitted / done / needs review / FAILED (+ the reason)
convert retry      # re-run only the failures
```

* If the first answer fails validation, the converter automatically sends the errors back to the
  model once and asks for a corrected version. If that fails too, the recipe is marked **failed** and
  the raw output is kept in `recipes/.mise/failed/` so you can see what went wrong. Usually a quick
  tidy-up of the source text and `convert retry` fixes it.
* Re-running `convert run` is free when nothing changed: the manifest (`recipes/.mise/manifest.json`)
  remembers each file's fingerprint. **Edit a source file** and it's converted again (keeping the same
  recipe id; your notes in `<id>.user.json` are untouched). `convert run --force` redoes everything.
* Different line endings (Windows vs Mac) don't count as a change.

## Settings

All in `config.toml` (created on first run, next to this project):

```toml
[converter]
inbox_dir = "inbox"
model = "claude-sonnet-5-5"      # current Sonnet; check https://docs.claude.com for newer models
max_tokens = 32000
effort = "medium"                # low | medium | high — more effort = more thinking = more cost
concurrency = 3                  # parallel requests in --sync mode
batch_threshold = 5              # more pending recipes than this → batch mode
refusal_fallback = true          # sync mode: let the API retry on a fallback model if it declines
poll_seconds = 30

[converter.pricing]              # USD per million tokens, used only for the estimate
input_per_mtok = 2.0
output_per_mtok = 10.0
```

## How it works (for the curious)

* The instructions live in [`converter/prompts/convert_recipe.md`](../converter/prompts/convert_recipe.md)
  (versioned; the version is recorded per recipe in the manifest). The fixture pair in `fixtures/` is
  inserted into it as a worked example at run time.
* That long prompt is sent with **prompt caching**, so after the first recipe it costs a fraction of
  the normal price.
* Output uses **structured outputs** (a JSON schema enforced by the API), so the reply is always valid
  JSON. The schema the model fills in is a simplified version of the real one (structured outputs
  limit how many optional fields a schema may have); code maps it back and validates it properly.
