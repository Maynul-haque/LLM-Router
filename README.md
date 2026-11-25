# LLM Router

On-device OpenAI-compatible LLM router for Android (`bd.desertfox.llmrouter`).

LLM Router turns your phone into a small local AI gateway. You add your API keys
from any OpenAI-compatible provider, and the app exposes one local endpoint —
`http://127.0.0.1:8790/v1` — that apps like **RikkaHub** can use as if it were a
single provider. Behind that one address, the router tries your models in order
and falls back automatically when one fails, runs out of credits, or gets
rate-limited. Everything runs on the device: no account, no cloud, no tracking.

## What it does

- **Providers** — add any OpenAI-compatible provider with a base URL and API
  key, fetch its model list, refresh it later, and enable or disable providers.
- **Models** — every fetched model can be enabled or disabled individually.
  Only models your key can actually use are shown as usable.
- **Combos** — group models into an ordered chain. A combo behaves like one
  model: the router tries the first model, then the next, and so on.
- **Single / Combo activation** — activate one model or one combo; the local
  server routes requests through it.
- **Ordered fallback** — when a model in the chain fails, the next one takes
  over. A `402` (out of credits), `429`, `5xx`, timeout, or any other error
  moves on to the next model. Providers that fail with an account error
  (`401`/`402`/`403`) are skipped for the rest of that request, because their
  other models share the same key and would fail identically.
- **Smart testing** — "Test all" probes each model with a tiny `hi` request
  (`max_tokens=1`) and marks it pass only if it returns a real reply. A `429`
  counts as pass (rate-limited proves the key is entitled to the model).
  Fresh passes (last 24 hours) and permanent failures (`401`/`402`/`403`/
  `404`) are skipped, so re-testing is fast; only untested, stale, or
  transiently failed models are re-probed. After a refresh, "Test new models"
  tests only the newly added models.
- **Paid-model detection** — providers that publish pricing (e.g. OpenRouter)
  have their paid models flagged automatically. A paid model shows as **fail**
  even if it replies well, so it never gets picked by mistake. Providers that
  publish no pricing cannot be flagged this way.
- **Hide failed / Hide slow** — hide models that failed their last test, and
  hide passed models slower than 3 seconds. Hidden models also stay out of
  the combo model selector and cannot be added to a combo from anywhere.
- **Quick scroll** — fast-scroll thumb on the Models list and on the combo
  model selector, for lists with hundreds of models.
- **Request log** — status and latency per request, visible in the app.
- **Crash log** — if the app crashes, the last crash log is shown on the next
  launch and can be copied for a bug report.
- **Import** — import provider keys from a JSON file (same format as the
  MinimalRouter phone bundle).

## How it works

### The local server

The app runs a small HTTP server on loopback only (`127.0.0.1`, port `8790`
by default). Only apps on the same phone can reach it — nothing is exposed
to the network. The server speaks the OpenAI API:

- `POST /v1/chat/completions` — chat completions, streaming and
  non-streaming. If the requested `model` matches a combo name, that combo's
  chain runs. If it matches a single model id, that model is tried first and
  the active chain follows as fallback. Otherwise the active chain runs. If
  the active chain resolves empty, the whole enabled set is tried before the
  server answers `503`.
- `GET /v1/models` — all enabled models, plus every combo exposed as a
  selectable model (owned by `LLM Router combo`).

### Request flow

1. A client (e.g. RikkaHub) sends a chat request to the local endpoint.
2. The router picks the chain: the requested combo, the requested model plus
   the active chain, or the active chain.
3. It sends the request to the first model in the chain.
4. On success, the provider's response is passed back unchanged.
5. On failure, the next model in the chain is tried. A model that returns
   `429` is put on a 60-second cooldown (in memory only) and skipped by later
   requests until it cools down. A `200` that carries an error object (or an
   empty reply) counts as a failure too: for streaming, the first chunk is
   checked before `200` headers are sent to the client, so a quota error
   from the first model falls back to the next model in the combo instead
   of being shown as the answer. A model that gives no data for 30s
   (streaming) or 60s (non-streaming) is also failed so the chain moves on.
6. If every model fails, the last error is returned.

### What "usable" means

A model is usable only if it is enabled, its provider is enabled, it did
not fail its last test, and it is not flagged paid. The Models tab, the combo
selector, `/v1/models`, and the fallback chain all follow this rule, so a
model you cannot use never appears where it could be picked.

### Storage and performance

- Providers, models, combos, and settings live in one `config.json`, written
  atomically and debounced (at most once per 2 seconds) so a 100-model test
  run does not rewrite the file hundreds of times.
- The HTTP server reads requests through a buffered stream with hard caps
  (32 KB headers, 10 MB body) and serves them from a bounded thread pool
  (8 core / 16 max, 64-deep queue).
- The app is plain Kotlin against `android.jar` — no Gradle, no OkHttp,
  no extra dependencies beyond `org.json` from the Android platform. It is
  built to stay light on memory and battery.

## Getting started

1. Install the app and open it.
2. Go to **Providers**, tap **+**, and add a provider: a name, its base URL
   (e.g. `https://api.groq.com/openai/v1`), and its API key.
3. Tap **Fetch all** (or Refresh on one provider) to load its models.
4. Tap **Test all** on the **Models** tab. Passing models are the ones your
   key can really use; failed and paid ones are hidden with **Hide failed**.
5. Select models and tap **Activate**, or build a **Combo** and activate it.
6. In RikkaHub (or any OpenAI-compatible client), set:
   - Base URL: `http://127.0.0.1:8790/v1`
   - API key: any value (the router uses your stored keys)
   - Model: a combo name (e.g. `auto`), a model id, or any name — the
     router falls back to the active chain.
7. Make sure the router's server is running (start it from the app). Only
   one server can use port `8790` at a time.

## Build

Plain Kotlin compiled against `android.jar` (compileSdk 34, minSdk 24),
packaged with aapt2 + d8, signed with a debug key. No Gradle, no dependencies
beyond org.json (from the Android platform).

```
kotlinc -jvm-target 17 -include-runtime -cp android.jar \
  -d app.jar app/src/main/java/bd/desertfox/llmrouter/*.kt
```

## Notes

- v0.5.3: combo fallback fixed for 200-with-error replies — a provider that answers 200 with an error body (e.g. free-model quota used up) or an empty reply now falls back to the next model in the combo, in streaming and non-streaming; streaming checks the first chunk before sending 200 headers; quota-like errors get a 60s cooldown; per-model timeout cut from 600s to 30s (streaming) / 60s (non-streaming). No new dependency.
- v0.5.4: launcher icon — adaptive icon (Android 8+), teal-to-indigo gradient with a glowing cyan hub and three nodes; pure vector XML, no PNG, no new dependency.
- v0.5.6: sidebar "Server: ON" display removed (the top ON/OFF pill still toggles the server). Left/right swipe anywhere switches the Providers / Models / Combos tabs (horizontal fling only, vertical scrolling untouched, ignored while the drawer is open). The How-to is now app-generic — no RikkaHub-specific tutorial; any OpenAI-compatible app can use the router.
- v0.5.5: one auto-managed combo only — "auto-best". The v0.5.2 alias combos and the preinstalled "auto" combo are removed on load; auto-best holds every model that actually replied successfully (usable + lastOk), fastest first, and rebuilds itself on every refresh (new successes join, failures leave). No rotation — the order is the fallback order. Custom combos untouched; auto-best is read-only (no rename / add / sort / delete). The "Make fastest combo" drawer option is removed — auto-best does the same job automatically (all successful models, not just 10). The duplicate "Fetch all models" drawer option is removed (Providers tab → Fetch all does the same thing). "About / server info" is split into About (with Creator: Maynul Haque) and Server info, and a "How to use" summary is added to the sidebar.
- v0.5.2: auto-managed alias combos (one combo per model id that exists under 2+ providers, named exactly that id, keys by latency) with safe-form rotation across the model's providers — same model, provider rotates per request; custom combos untouched; greyed unusable models in expanded combo rows with a reason label.
- v0.5.1: Log moved to the sidebar; long-press menu has Activate / Select / Hide this model; Hide-failed stays only in the bottom row; empty fetch no longer wipes models; token usage in the log; cooldown chip; combo health (usable/total); log filter; one-tap fastest combo; port change in the drawer; auto-test new models toggle.
- v0.5.0: smart Test All (skips fresh passes and permanent fails), paid models detected from provider pricing and marked fail, Hide slow (>3s), Test new models after refresh, quick scroll, combo selector hides hidden-provider models, fallback tries every model in the chain (402 no longer stops it), `/v1/models` lists all enabled models.
- v0.4.0: combos exposed on `/v1/models`, request routing by combo name.
- The server listens on loopback only; nothing leaves the device except calls to the providers you added.
- API keys are stored on the device in the app's config. Keep your config backup private.
