# LLM Router

On-device OpenAI-compatible LLM router for Android (`bd.desertfox.llmrouter`).

A small, offline-first app that sits on your phone and exposes a local OpenAI-compatible endpoint so apps like RikkaHub can talk to all your providers through one address.

## What it does

- **Providers**: add OpenAI-compatible providers (base URL + API key), fetch their model lists.
- **Models**: enable/disable individual models per provider.
- **Combos**: group models into an ordered chain.
- **Single / Combo activation**: activate one model or one combo; the local server routes requests through it.
- **Ordered fallback**: when a model in the chain fails, the next one takes over.
- **Request log**: status and latency per request.

## HTTP API (127.0.0.1, port 8790 by default)

- `POST /v1/chat/completions` — OpenAI-compatible, streaming and non-streaming. If the requested `model` matches a combo name, that combo's chain runs (with ordered fallback); otherwise the active chain runs.
- `GET /v1/models` — the active chain's models, plus every combo exposed as a selectable model (owned_by `LLM Router combo`).

## Build

Plain Kotlin compiled against `android.jar` (compileSdk 34, minSdk 24), packaged with aapt2 + d8, signed with a debug key. No Gradle, no dependencies beyond org.json (from the Android platform).

```
kotlinc -jvm-target 17 -include-runtime -cp android.jar \
  -d app.jar app/src/main/java/bd/desertfox/llmrouter/*.kt
```

## Notes

- v0.4.0: combos exposed on `/v1/models`, request routing by combo name.
- The server listens on loopback only; nothing leaves the device except calls to the providers you added.
