# Datastar spike (throwaway, superseded)

Evidence for the Datastar assessment of 26 September 2026, kept for the record.
**It is not built or tested with the project.** The production UI is `web/`,
which differs in two ways:
- it writes Datastar's event format itself instead of using the Clojure SDK
  (a release candidate), and checks it against the SDK's official cases;
- it covers the full UI, with tests.

This spike served the wmark local UI as server-rendered HTML plus SSE, and
called the Core API and security middleware of that time without changing them.

- `src/ds_spike/html.clj`: a 36-line hiccup renderer that escapes by default.
- `src/ds_spike/ui.clj`: the page, four Datastar endpoints, the queue stream,
  the CSP nonce, and a cookie check for UI routes.
- `public/datastar.js` (not copied here; identical to
  `web/resources/public/datastar.js`): Datastar v1.0.4, vendored unmodified
  (MIT). No npm. SHA-256 `727844adfc825ee651fb93c544a2a739986f9a21820a94524b35f0cac470cf91`.
- `drive.py`, `xss.py`: Playwright checks in Chromium. `spike.png` is the end
  state.

## What was run

Classpath: the wmark repository (`kernel/src`, `src`, `resources`,
`desktop/src`, `desktop/resources`), http-kit 2.8.1, data.json, malli, and
Datastar Clojure SDK 1.0.0-RC11 (`libraries/sdk/src/main`,
`libraries/sdk/resources`, `libraries/sdk-http-kit/src/main` from
github.com/starfederation/datastar-clojure).

```
python3 drive.py java -cp "$CP" clojure.main -m ds-spike.ui \
  HOME_DIR CLIP.mp4 LOGO.png OUT_DIR public
```

With the Clojure CLI, the equivalent is the repository's `:desktop` alias plus
`dev.data-star.clojure/sdk` and `dev.data-star.clojure/http-kit`
`{:mvn/version "1.0.0-RC11"}`, with http-kit pinned to 2.8.1. That form was not
run here: the sandbox had no Maven access.

## Results (Chromium, JDK 25, FFmpeg 6.1.1)

| Check | Result |
|---|---|
| Scripts loaded | `/datastar.js` only: 33.5 KB minified, 13.4 KB gzipped |
| CSP | No `unsafe-eval`, per-response nonce: 0 violations. Without the nonce: blocked, and the queue never rendered |
| Fallback display | Values from `latest` shown as "from your last run"; edits come back as "set here" |
| Validation | An invalid opacity returns an error fragment |
| Live render | 30 s 720p clip rendered in 17 s; the browser saw 33 distinct progress values |
| Traffic | 47 SSE events, 7,946 bytes of HTML for the whole session |
| Save conflicts | A stale save shows "This profile changed since it was loaded..." |
| Dropped stream | The page reconnected (streams opened 1 → 2) and re-rendered the queue |
| Hostile strings | A profile name and text carrying `data-on:*` and `<img onerror>` rendered as text; 0 injected elements, 0 requests fired |
