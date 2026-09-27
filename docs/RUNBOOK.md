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
| FFmpeg | a GPL build, 5.1+ (6.1.1, 7.0.2 and 9.0.1 tested) with `perspective`, `drawtext`, `libx264` | rendering; the conformance tests | `ffmpeg -hide_banner -filters \| grep -E 'perspective\|drawtext'` |
| GraalVM Community | 25 (for JDK 25), `GRAALVM_HOME` set | native binaries | `$GRAALVM_HOME/bin/native-image --version` |
| C toolchain for native-image | Linux: `gcc`, zlib headers; macOS: Xcode Command Line Tools; Windows: Visual Studio 2022 Build Tools ("Desktop development with C++") | native binaries | |
| C compiler (`cc`) | any | native-engine tests (optional) | `cc --version` |
| Python 3 + Playwright | any recent | browser smoke test (optional) | `python3 -m playwright --version` |

**No Node.js, npm or JavaScript build anywhere.** The web UI's only script is
the vendored `web/resources/public/datastar.js`. Playwright is test tooling;
it brings its own driver.

**FFmpeg.** Use a full **GPL** build. LGPL builds lack `perspective`, which the
flip needs (FFmpeg builds it only with `--enable-gpl`), and minimal builds
often lack `drawtext`, without which text layers are refused. Release
downloads carry a pinned build (`bb ffmpeg`, below).
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

`bb test` runs 84 tests (10,587 assertions) in 25 namespaces (**verified**,
2026-09-27). CI fails on any `Reflection warning` in its output.
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
  - `--enable-native-access=ALL-UNNAMED`

  GraalVM 25 installs exit handlers in executables by default, so
  `--install-exit-handlers` is gone (it warned as deprecated).
- **The build locale matters.** An image keeps the path and argument charset
  of the machine that built it (`sun.jnu.encoding`,
  [oracle/graal#10237](https://github.com/oracle/graal/issues/10237)). A Linux
  build in a POSIX locale made a binary that couldn't open `vidéo/clip é.mp4`,
  whatever the user's locale. `native` therefore runs native-image in
  `C.UTF-8` on Linux; macOS always uses UTF-8; CI's smoke test checks Windows.
- Metadata (resources `public/**`, `fonts/**`; the FFM call shapes) comes from
  `desktop/resources/META-INF/native-image/clogem/wmark/reachability-metadata.json`.
  The tracing agent, run over the browser suite, a real render, the profile
  commands and the native mock engine, recorded exactly the six downcall and
  one upcall shapes listed there, and no resource or reflection the binary
  lacks (Clojure's namespaces are initialised at build time).
- **No cross-compilation.** Build the Linux binary on Linux (WSL2 is fine),
  the Mac binary on a Mac, and the `.exe` on Windows. On Windows, run from the
  "x64 Native Tools Command Prompt for VS 2022".
- **Smoke-test every build:**
  ```bash
  bb ffmpeg                    # the pinned FFmpeg for this OS -> target/ffmpeg/<platform>/
  bb mock-engine               # optional: the C mock, for the native-engine checks
  bb smoke --bin target/bin --ffmpeg target/ffmpeg/linux-x64/bin --mock target/libwmark_engine.so
  python3 test/e2e/ui_smoke.py target/bin/wmark --home /tmp/w serve --announce json
  ```
  `test/smoke/native.clj` checks `--help`, `version`, `doctor`, a real render
  of a clip in a non-ASCII folder (every frame kept), the API behind the
  token, the sign-in redirect, the UI page and its assets, a `wmark-tui`
  session, and a render through the C ABI. CI's `native` job runs it on
  Linux x64, Windows x64, macOS arm64 and macOS x64 (by hand, or on a pull
  request labelled `native`).

### FFmpeg for the download
```bash
bb ffmpeg                       # this OS;  bb ffmpeg :platform :windows-x64  for another
```
The builds are pinned in `deps.edn` (`:wmark/build-matrix` → `:ffmpeg`): exact
archives by URL and SHA-256, never "latest"
([ADR 0001](adr/0001-ffmpeg-in-release-bundles.md)). A changed or missing file
fails the task. It writes `target/ffmpeg/<platform>/bin/{ffmpeg,ffprobe}` and
`licenses/{COPYING.GPLv3,SOURCE.txt}`; downloads are cached in
`target/downloads/`. To move a pin, change the URL and SHA-256 together, run
`bb lint` (it rejects unpinned or plain-http archives) and the smoke test.

### The download (Stage 1)
```bash
bb native && bb native :target :tui && bb ffmpeg
bb bundle :bundle :desktop-server :ffmpeg-dir target/ffmpeg/linux-x64
bb smoke --bin dist/desktop-server --ffmpeg dist/desktop-server/bin --bundled true
```
This writes `dist/desktop-server/` with:
- `wmark(.exe)` and `wmark-tui(.exe)`;
- `bin/ffmpeg(.exe)` and `bin/ffprobe(.exe)`;
- `licenses/`: this repository's `LICENSE` and `NOTICE`,
  `THIRD-PARTY-wmark.txt` and `THIRD-PARTY-wmark-tui.txt` (every library in
  the binary with its declared license and the license files it ships,
  generated from the resolved dependencies), and `ffmpeg/COPYING.GPLv3` and
  `ffmpeg/SOURCE.txt`;
- `SHA256SUMS` and `README.txt`.

`--bundled true` makes the smoke test find FFmpeg the way a download does:
in `bin/` next to wmark, under the hardened search order.

### Releases
Push a tag `vX.Y.Z` (or `vX.Y.Z-rc.N`) on `main`; `.github/workflows/release.yml`
does the rest and stops at a **draft** release:
1. builds and smoke-tests the bundle on Linux x64, Windows x64, macOS arm64 and
   macOS x64 (and runs the browser suite against the Linux binary);
2. signs in the protected `release` environment, once the owner has set it up:
   Authenticode ([ADR 0002](adr/0002-windows-code-signing.md)); Developer ID and
   notarization for every Mach-O ([ADR 0003](adr/0003-macos-signing-and-library-validation.md));
3. publishes `wmark-<version>-<platform>.zip|tar.gz`, `SHA256SUMS`, a keyless
   Sigstore signature of the checksums and build-provenance attestations
   ([ADR 0004](adr/0004-release-supply-chain.md)).

The draft's notes say which bundles are signed and how to verify a download.
Tags `abi-vN` and `kernel-v*` publish the **engine SDK** (`bb sdk :name abi-v1`
locally): the header, the mock, the JSON Schemas, the golden vectors, README and
ENGINE.md. `abi-vN` must match `WMARK_ENGINE_ABI_VERSION`.

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
| The native UI loads without styles or stays inert (404 for `datastar.js`) | The uberjar missed the components' resources; fixed in `wmark.build/project-dirs`. Rebuild |
| A native binary can't open `vidéo/clip é.mp4` or save "Café" | It was built in a POSIX locale before `native` pinned `C.UTF-8`; rebuild |
| `bb lint` in a cloud session: "Cannot download Clojure tools ... PKIX" | bb's built-in `clojure` uses its own trust store, which a TLS-inspecting proxy breaks. `scripts/cloud-setup.sh` copies the CLI's tools jar into `~/.deps.clj/<version>/ClojureTools/`; do that by hand if the setup script didn't run |
| HTTP 429 from Maven Central while resolving | Rate limiting on a shared egress; run `clojure -P -M:dev:test` once to fill `~/.m2`, then retry |

## What hasn't been run

**Verified on 2026-09-27** (a Linux x64 cloud session: OpenJDK 25.0.4.1,
GraalVM CE 25.0.2, FFmpeg 6.1.1 and the pinned 9.0.1, Chromium 141):
- native builds of the engine (about 2 minutes) and the TUI;
- the smoke test (17 checks) on both binaries, including the C mock through FFM,
  and on the assembled Linux bundle with its bundled FFmpeg (15 checks);
- the browser suite (20 checks) against the native binary and on the JVM;
- the tracing agent over those flows (see "Native binaries");
- `bb ffmpeg` for all four platforms (downloads and checksums; only the Linux
  binaries were run);
- the FFmpeg conformance harness with FFmpeg 9.0.1.

**Verified in CI before this change:** the native builds compile on Windows,
macOS and Linux (their old smoke step only ran `--help`).

**Not run yet:**
- the new smoke test and bundle on Windows and macOS (CI's `native` job, first
  run pending);
- the release and SDK workflows (they run on tags);
- signing and notarization: the certificates don't exist yet (ADRs 0002 and
  0003);
- Windows paths outside the system code page, e.g. Chinese folder names on an
  English Windows: the smoke test's non-ASCII render covers it on the first
  Windows run;
- the OS encoders (Media Foundation, VideoToolbox) on real hardware.
