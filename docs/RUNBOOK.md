# Runbook

How to set up, run, test, build and ship wmark. Commands run from the
repository root. **Verified** marks what was run in the build sandbox; the rest
follows the same code paths but needs a machine with the named tool (see
[What hasn't been run](#what-hasnt-been-run)).

## 1. What gets built

| Artifact | What it is | Built from |
|---|---|---|
| `wmark` / `wmark.exe` | The engine: local web UI, REST API, CLI | `:engine` target |
| `wmark-tui` | Terminal client of a running engine | `:tui` target |
| `dist/desktop-server/` | The download: binaries, `bin/ffmpeg`, licenses, checksums | `bundle` task |

All of this is declared as data in `deps.edn` under `:wmark/build-matrix`.
`bb matrix` prints it; `bb lint` checks it against the repository, including
that each component's own `deps.edn` matches its alias.

The commercial editions (the Pro binaries, the hosted backend and the apps)
are built in their own repository, on top of this one, with the same build
tool (`build/`).

## 2. Toolchain

| Tool | Version | Needed for | Check |
|---|---|---|---|
| JDK | 25 (e.g. Temurin 25) | everything | `java -version` |
| Babashka | recent | the task runner (`bb ...`); its built-in `bb clojure` replaces the Clojure CLI | `bb --version` |
| Clojure CLI | 1.12+ (optional with bb) | `clojure -M...` / `-T:build` directly | `clojure --version` |
| FFmpeg | full build, 5.1+ (6.1.1 and 7.0.2 tested) with `drawtext`, `libx264` | rendering; the conformance tests | `ffmpeg -hide_banner -filters \| grep drawtext` |
| GraalVM Community | 25 (for JDK 25), `GRAALVM_HOME` set | native binaries | `$GRAALVM_HOME/bin/native-image --version` |
| C toolchain for native-image | Linux: `gcc`, zlib headers; macOS: Xcode Command Line Tools; Windows: Visual Studio 2022 Build Tools ("Desktop development with C++") | native binaries | |
| C compiler (`cc`) | any | native-engine tests (optional) | `cc --version` |
| Python 3 + Playwright | any recent | browser smoke test (optional) | `python3 -m playwright --version` |

**No Node.js, npm or JavaScript build anywhere.** The web UI's only script is
the vendored `web/resources/public/datastar.js`. Playwright is test tooling;
it brings its own driver.

**FFmpeg.** Use a full build. Minimal builds often lack `drawtext`, and then
text layers are refused.
- Ubuntu 24.04: `apt install ffmpeg` (6.1.1, has everything; verified).
- Windows: a "full" GPL build, e.g. from gyan.dev or BtbN. Put `ffmpeg.exe`
  and `ffprobe.exe` in `bin\` next to `wmark.exe`, or on PATH.
- macOS: Homebrew or a static build.
- Whatever the source, run `wmark doctor` (below): it reports missing filters
  and encoders.

**Ubuntu 24.04 / WSL2, everything in one go (verified package names):**
```bash
sudo apt install openjdk-25-jdk-headless ffmpeg build-essential zlib1g-dev
bash < <(curl -s https://raw.githubusercontent.com/babashka/babashka/master/install)
```
On WSL2, keep the clone on the Linux filesystem (`~/src/clogem-wmark`), not under
`/mnt/c`: builds there are several times slower.

## 3. Daily development

```bash
bb dev                 # engine from source; opens the web UI in your browser
bb dev serve           # same, without opening a browser (prints the URL with its token)
bb tui                 # terminal client; finds the running engine via <home>/runtime/server.edn
bb dev run --logo logo.png --text "(c) Studio" clip.mp4        # CLI render
bb dev run --dry-run --logo logo.png clip.mp4                  # spec, FFmpeg argv and filtergraph only
bb dev doctor          # which engine and FFmpeg were found, and from where
```

- **Data folder.** `--home DIR`, `WMARK_HOME`, else `./wmark-data` if it
  exists, else the OS default (`%APPDATA%\wmark`,
  `~/Library/Application Support/wmark`, `$XDG_CONFIG_HOME/wmark`).
  Profiles live in `<home>/profiles/*.edn`; the studio secret for keyed
  schedules is `<home>/secret.key` (back it up).
- **FFmpeg lookup.** `--ffmpeg PATH` or `WMARK_FFMPEG`, then `./`, then
  `./bin/`, then wmark's folder, then its `bin/`, then PATH.
  `--ffmpeg-search app,app-bin,path` (or `WMARK_FFMPEG_SEARCH`) skips the
  working folder.
- **Native engine.**
  - `--engine native --native-lib PATH` (or `WMARK_ENGINE_LIB`) loads a
    library implementing `native/include/wmark_engine.h`.
  - `bb mock-engine` builds the mock (Linux).
- **REPL.** `clojure -M:dev` (or `bb clojure -M:dev`) has every source and
  test path. A server you can reload against:
  ```clojure
  (require '[watermark.app :as app] '[watermark.core.features :as f] '[watermark.server.http :as http])
  (def sys (app/with-jobs (app/system {:edition :community :entitlements-fn (fn [_] (f/community))} {})))
  (def srv (http/start! sys {}))
  (str (:url srv) "/?token=" (:token srv))    ; open this
  (http/stop! sys srv)
  ```

### The web UI

It is server-rendered Clojure (`web/`), so there is nothing to compile
separately: it ships inside the engine. The page is `/`; its endpoints
live under `/ui/`.

**Signing in.** Open the URL the engine printed. It carries a token and sets
an HttpOnly cookie.

**Editing views.** Change `web/src/watermark/web/views.clj` and reload the
namespace in the REPL (or restart `bb dev`), then refresh the browser.
Styles are in `web/resources/public/app.css`.

**Upgrading Datastar:**
1. Replace `web/resources/public/datastar.js` with the new release's
   `bundles/datastar.js`.
2. Update the version and SHA-256 in `web/resources/public/datastar.LICENSE.txt`.
3. Update the hash in `web/test/watermark/web/assets_test.clj`.
4. Run `bb test` and `bb e2e`.

Never add Datastar Pro: its license forbids use in open-source projects.

**External UI.** `--ui-dir DIR` serves a different UI from a folder, like
Clash's external-ui. It replaces the built-in one and talks to `/api/v1`.

## 4. Tests

```bash
bb test        # every test namespace (clojure -M:dev:test)
bb lint        # build matrix vs repository
bb e2e         # browser smoke test of the web UI (ffmpeg + Python Playwright)
```

`bb test` runs 73 tests (10,554 assertions) in 22 namespaces (**verified**).
Two groups need extra tools and **skip themselves, saying so**, when those
tools are missing:

| Group | Needs | Enable with |
|---|---|---|
| FFmpeg conformance (real renders measured against the reference semantics) | `ffmpeg` on PATH, a TrueType font (DejaVu on Linux) | install FFmpeg |
| Native engine (C mock through Java's FFM API) | `cc` | install a C compiler |

**Golden vectors.** `kernel/test/golden/*.edn` pin the kernel's outputs
(PRNG, seeds, render specs). They are
what a Dart or Swift port must reproduce. After an intentional change,
regenerate them with `WMARK_UPDATE_GOLDEN=1 bb test` and review the diff like
code.

**Single namespace:**
```bash
clojure -M:dev:test -n watermark.web.sse-test
```

**Browser test by hand:**
```bash
python3 test/e2e/ui_smoke.py clojure -M:desktop:web -m watermark.main --home /tmp/w serve --announce json
```
It exercises create, live preview, save, a real render with live progress,
the stale-save refusal, duplicate and delete. It also checks that
`/datastar.js` is the only script, with no CSP violations and no console
errors. **Verified:** 20 checks.

## 5. Builds

### JVM uberjars (any OS)
```bash
clojure -T:build uber :target :engine                   # target/wmark.jar
clojure -T:build uber :target :tui                      # target/wmark-tui.jar
java --enable-native-access=ALL-UNNAMED -jar target/wmark.jar
```
Every target is AOT-compiled with direct linking; the web UI is inside the
engine jar. With Babashka only: `bb clojure -T:build uber :target :engine`.

### Native binaries (GraalVM 25)
```bash
export GRAALVM_HOME=/path/to/graalvm-community-25     # Windows: set GRAALVM_HOME=C:\graalvm-25
bb native                                   # = clojure -T:build native :target :engine
bb native :target :tui                      # target/bin/wmark-tui
target/bin/wmark doctor
```
- `native` builds the uberjar, then runs `native-image -jar` with the target's
  flags from the matrix:
  - `--no-fallback`
  - `-march=compatibility`
  - graal-build-time's `--features`
  - `--install-exit-handlers`
  - `--enable-native-access=ALL-UNNAMED`
- Metadata (resources `public/**`, `fonts/**`, `wmark/**`; the FFM call shapes)
  comes from `desktop/resources/META-INF/native-image/clogem/wmark/reachability-metadata.json`.
- **No cross-compilation.** Build the Linux binary on Linux (WSL2 is fine),
  the Mac binary on a Mac, and the `.exe` on Windows. On Windows, run from the
  "x64 Native Tools Command Prompt for VS 2022". CI does all three:
  `.github/workflows/ci.yml` runs on tags `v*` or by hand.
- **After a native build, click through the UI once.** Also run a render and
  `wmark --engine native --native-lib ... doctor` against the mock, to catch
  missing reachability metadata. If something is missing, rerun the uberjar
  under the tracing agent to collect it:
  `java -agentlib:native-image-agent=config-output-dir=... -jar target/wmark.jar`.

### The download (Stage 1)
```bash
bb native && bb native :target :tui
bb bundle :bundle :desktop-server :ffmpeg-dir /path/to/ffmpeg/bin
```
This writes `dist/desktop-server/` with:
- `wmark(.exe)` and `wmark-tui(.exe)`;
- `bin/ffmpeg(.exe)` and `bin/ffprobe(.exe)`;
- `licenses/` (this repository's `LICENSE` and `NOTICE`), `SHA256SUMS` and
  `README.txt`.

Before shipping, add FFmpeg's license and source offer (GPL builds), sign the
binaries (Authenticode on Windows; Developer ID and notarization on macOS),
and zip the folder.

## 6. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| UI says "This browser isn't signed in" | Open the exact URL the engine printed (it carries the token), or restart it |
| `doctor` says NOT READY | FFmpeg not found: see the trail it prints; put `ffmpeg`/`ffprobe` in `bin/` next to wmark |
| "text layers are unavailable" | This FFmpeg build has no `drawtext`: install a full build |
| `doctor` warns ffprobe comes from another folder | Ship both binaries together in `bin/` |
| Save says the profile "changed since it was loaded" | Another window, the CLI or the API saved first: click the profile to reload it |
| Stream stops updating after sleep | It reconnects by itself (and on tab focus); reload if the engine was restarted with a new token |
| Tests skipped | See the table in section 4 |
| Native build fails on Windows | Run from the x64 Native Tools prompt; check `GRAALVM_HOME` |
| Missing class or resource only in the native binary | Collect metadata with the tracing agent, add it to reachability-metadata.json |

## What hasn't been run

The sandbox that produced this repository could reach GitHub but not Maven
Central or Clojars, and had no GraalVM, Windows or macOS. So these steps have
not been run:
- tools.build's `uber` and `native`;
- a native-image build;
- the Windows and macOS binaries;
- the OS encoders.

What was run:
- The real Clojure CLI (1.12.2) resolved `deps.edn` against a local stand-in
  Maven repository; `bb test`, `bb lint` and `bb e2e` (20 browser checks) ran
  through Babashka in this repository as split out, and `-T:build lint` and
  `matrix` ran with the `build/` library.
- Each target was AOT-compiled with a plain `compile` and its classes were
  counted: the community engine has 2,599 classes (web 129, no commercial
  classes), the TUI 281 (no web or server classes).
- Before the split, the release bundle (with PATH emptied) ran on JDK 25 with
  FFmpeg 6.1.1.

The first CI run on GitHub is the real check for the rest.
