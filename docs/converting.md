# Converting your recipes

The converter turns raw recipe text — pasted from a cookbook scan, a website, an old note — into the
app's structured format, using Claude. Each recipe is converted **once**; after that it's plain JSON
in your `recipes/` folder, and the app never needs the AI again.

In the commands below, `convert` means:

* Windows: `powershell -ExecutionPolicy Bypass -File scripts\convert.ps1`
* macOS / Linux: `scripts/convert.sh`

## 1. One-time: a Claude subscription or an API key

Either works. `convert` tells you which one it's using before it sends anything.

**A. Your Claude subscription (Pro/Max), through Claude Code**

1. Install Claude Code: <https://claude.com/claude-code> (Windows: the native installer is best;
   see the note below).
2. Run `claude` once in a terminal and log in with your Claude account. Check with `claude auth status`.

That's it. Conversions run through `claude -p` one recipe at a time (about 20 seconds to a couple
of minutes each) and count towards your plan's usage limits. If you hit a limit, `convert` reports
it and `convert retry` picks up where it stopped. No batch mode on this route.

**B. An API key (pay per use)**

1. Create a key at <https://platform.claude.com/> (Settings → API keys) and add some credit.
2. Set it in the terminal you'll run the converter from (it's read from the environment only — never
   stored in a file):
   * Windows PowerShell: `$env:ANTHROPIC_API_KEY = "sk-ant-..."`
   * macOS / Linux: `export ANTHROPIC_API_KEY=sk-ant-...`

With an API key you get a cost estimate first, and batch mode (half price) for bigger runs.

**Which one is used:** `backend = "auto"` (the default) uses the API key when it's set, otherwise
Claude Code. Force one with `convert --backend claude-code run` / `--backend api`, or set `backend`
in `config.toml`. When Claude Code is used, Mise removes any API key from its environment, so it
really uses your subscription.

*Windows note:* if Claude Code was installed with npm, `claude` is a `.cmd` script that can mangle
the JSON schema on the command line; Mise notices and puts the schema in the prompt instead (it
still validates every answer). The native installer (`claude.exe`) avoids this.

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

In the app, tap the **Needs review** badge (or **Check them** on the recipe). Mise walks you through
each doubt: the converter's note, the matching line from your original text, and that ingredient,
step or serving count ready to fix. **Looks right** moves on; change something and it's **Save & next**.
Once everything is checked the recipe counts as reviewed. Anything else can be changed any time with
the ✏️ on the recipe. App edits are stored in `recipes/<id>.user.json` next to your notes, never in the
converted file, so they can be undone.

For changes the app can't make yet (step wording), edit `recipes/<id>.json` in any text editor (field
guide: [schema/README.md](../schema/README.md)) — the server reloads it instantly and skips it, with a
log message, if you break it.

Your own scribbles from the pasted text ("try cumin seeds next time") end up in **My notes** in the
app, not in the method.

## Writing a recipe from a request

`convert ask "a quick weeknight red lentil dal for 4, mild"` writes a new recipe into `recipes/`
(the phone's *Add recipe → Describe one* does the same through the server). It uses the same
format rules as conversions, with its own instructions (`converter/prompts/write_recipe.md`): pick
dependable amounts and times, respect every constraint in the request, cook meat and fish safely,
and flag the judgement calls. The recipe is always **Needs review**, with a first note saying Claude
wrote it; `<id>.source.txt` keeps the request, so *View original text* shows what was asked.

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
backend = "auto"                 # auto | api | claude-code (see section 1)
inbox_dir = "inbox"
model = "claude-sonnet-5-5"      # current Sonnet; check https://docs.claude.com for newer models
max_tokens = 32000
effort = "medium"                # low | medium | high — more effort = more thinking = more cost
concurrency = 3                  # parallel requests in --sync mode
batch_threshold = 5              # more pending recipes than this → batch mode
refusal_fallback = true          # sync mode: let the API retry on a fallback model if it declines
poll_seconds = 30

[converter.claude_code]          # only for backend = claude-code
command = "claude"               # name on PATH or a full path
model = ""                       # empty = `model` above; aliases like "sonnet" / "opus" work
timeout_seconds = 900

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
