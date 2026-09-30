# Runbook

How to set up, run, test, build and ship wmark. Commands run from the
repository root. **Verified** marks what was run in the build sandbox; the rest
follows the same code paths but needs a machine with the named tool (see
[What hasn't been run](#what-hasnt-been-run)).

## 1. What gets built

| Artifact | What it is | Built from |
|---|---|---|
| `wmark` / `wmark.exe` | The engine: local web UI, REST API, CLI | `:engine` target |
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
| FFmpeg | 5.1+ (6.1.1, 7.0.2, 9.0.1 and 9.0.2 tested); a GPL build (`perspective`, `drawtext`, `libx264`) for the v1 conformance tests, any build with `overlay` to render | rendering; the conformance tests | `wmark doctor` |
| GraalVM Community | 25 (for JDK 25), `GRAALVM_HOME` set | native binaries | `$GRAALVM_HOME/bin/native-image --version` |
| C toolchain for native-image | Linux: `gcc`, zlib headers; macOS: Xcode Command Line Tools; Windows: Visual Studio 2022 Build Tools ("Desktop development with C++") | native binaries | |
| C compiler (`cc`) | any | native-engine tests (optional) | `cc --version` |
| Python 3 + Playwright | any recent | browser smoke test (optional) | `python3 -m playwright --version` |
| Dart SDK | 3.13.4 (CI's pin; `scripts/cloud-setup.sh` installs it, checksum-verified) | the kernel on the Dart VM, `bb kernel-dart` (optional locally; CI runs it) | `dart --version` |

**No Node.js, npm or JavaScript build anywhere.** The web UI's only script is
the vendored `web/resources/public/datastar.js`. Playwright is test tooling;
it brings its own driver.

**FFmpeg.** A full **GPL** build renders everything with FFmpeg's own filters
(render spec v1). An **LGPL** build lacks `perspective`, which FFmpeg builds
only with `--enable-gpl`; with one, wmark draws the flip and the text itself
and FFmpeg only composites them (render spec v2, chosen automatically;
docs/adr/0006). Minimal builds without `drawtext` get v2 for text the same
way. Release downloads carry a pinned **LGPL** build (`bb ffmpeg`, below;
[ADR 0001](adr/0001-ffmpeg-in-release-bundles.md)).
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
    library implementing `native/include/wmark_engine.h` (ABI 1 or 2).
  - `bb mock-engine` builds the mock (Linux).
- **Render spec version.** wmark gives an engine v1 where it can draw the
  whole spec, else v2 (wmark draws; the engine composites). `--render-spec 2`
  (or `1`) insists on one, for comparisons; an engine that can't take it
  refuses before any work. v2 bitmaps go to `<home>/work/v2-<uuid>/` and are
  deleted when the render ends.
- **Fonts.** Text layers use the bundled Fira Sans Bold (Latin, Greek,
  Cyrillic; SIL OFL, `resources/fonts/`) unless a layer sets `font-path`.
  FFmpeg reads it from `<home>/cache/wmark.ttf`, extracted on first use and
  refreshed when a new version bundles another font. Render spec v2 needs a
  TrueType-outline font (`glyf`); CFF (`.otf` with `OTTO`) fonts are refused
  with a clear error, and v2 lays text out by advance widths (no kerning or
  shaping, so not for Arabic or Indic scripts yet).
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
bb kernel-dart # the kernel compiled by ClojureDart, its golden vectors on the Dart VM (Dart SDK)
```

`bb test` runs 120 tests (11,081 assertions) in 34 namespaces (**verified**,
2026-09-27, with `WMARK_REQUIRE_LGPL=1`). CI fails on any `Reflection warning` in its output.
Two groups need extra tools and **skip themselves, saying so**, when those
tools are missing:

| Group | Needs | Enable with |
|---|---|---|
| FFmpeg conformance (real renders measured against the reference semantics) | `ffmpeg` on PATH, a TrueType font (DejaVu on Linux) | install FFmpeg |
| v2 conformance on the pinned LGPL FFmpeg the downloads ship (M2's exit test) | `target/ffmpeg/linux-x64/` from `bb ffmpeg`, or `WMARK_FFMPEG_LGPL` | `bb ffmpeg`; CI sets `WMARK_REQUIRE_LGPL=1`, so it can't skip there |
| Native engine (C mock through Java's FFM API, ABI 1 to 3 builds, v2 frames) | `cc` | install a C compiler |
| v2 conformance on the C mock | `cc` and `ffmpeg` | both of the above |

**Golden vectors.** `kernel/test/golden/*.edn` pin the kernel's outputs:
the PRNG, seeds, render specs, render spec v2 down to every bitmap's
SHA-256, the settings schema's verdicts and messages (`schema.edn`), the
settings form (`form.edn`), FFmpeg plans and parsers (`ffmpeg.edn`), the
library's Unicode (`text.edn`) and the profile rules with the text of stored
profiles (`profiles.edn`). Both runtimes compute them from the same inputs
(`kernel/test/watermark/golden_inputs.cljc`): `bb test` on the JVM, and
`bb kernel-dart` on the Dart VM, which compares strictly (24 is not 24.0).
They are also what a Swift or Rust port must reproduce. After an
intentional change, regenerate them with `WMARK_UPDATE_GOLDEN=1 bb test`,
review the diff like code, and run `bb kernel-dart`.

**The library's Unicode tables** (`kernel/src/watermark/util/unicode_data.cljc`,
[ADR 0013](adr/0013-host-logic-into-the-core-library.md), section 2) are
generated from the Unicode Character Database files pinned in
`build/src/wmark/build.clj` (`ucd`, version and SHA-256):
`clojure -T:build unicode` downloads them to `target/ucd/` and rewrites the
file. Only regenerate for a new Unicode version, together with a JDK that
implements it (`watermark.util.unicode-test` compares every code point with
Java), and review the golden diffs: slugs of newly assigned characters may
move.

**The kernel on the Dart VM** ([ADR 0012](adr/0012-the-kernel-on-the-dart-vm.md)).
`kernel/dart` is a ClojureDart project (0.9.20260917, pinned by commit) that
compiles the kernel and two test namespaces to Dart and runs them with
`dart test`: every golden file, every kernel namespace loaded, and what
differs by host. It fetches ClojureDart (a git dependency) and its pub
packages (`crypto`, `test`) once, then compiles ClojureDart's core and the
kernel on every run. **Verified** on 2026-09-30: 14 tests, no warnings, under a minute
from a clean checkout with those caches warm. A compile error
there usually breaks one of the rules in CLAUDE.md, "Core library
portability".

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
java --enable-native-access=ALL-UNNAMED -jar target/wmark.jar
```
Every target is AOT-compiled with direct linking; the web UI is inside the
engine jar. With Babashka only: `bb clojure -T:build uber :target :engine`.

### Native binaries (GraalVM 25)
```bash
export GRAALVM_HOME=/path/to/graalvm-community-25     # Windows: set GRAALVM_HOME=C:\graalvm-25
bb native                                   # = clojure -T:build native :target :engine
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
  `C.UTF-8` on Linux, and macOS always uses UTF-8. On Windows the C runtime
  passes arguments in the process code page while the image decodes them as
  UTF-8: the first Windows build received `vidéo 视频\clip é.mp4` as
  `vid?o ??\clip ?.mp4`. `native` therefore embeds a manifest
  (`build/src/wmark/utf8.manifest`, `-H:NativeLinkerOption=/MANIFESTINPUT:...`)
  that makes UTF-8 the process code page (Windows 10 1903 and later,
  [Microsoft: use the UTF-8 code page](https://learn.microsoft.com/en-us/windows/apps/design/globalizing/use-utf8-code-page)).
- Metadata (resources `public/**`, `fonts/**`; the FFM call shapes) comes from
  `desktop/resources/META-INF/native-image/clogem/wmark/reachability-metadata.json`.
  The tracing agent, run over the browser suite, a real render, the profile
  commands and the native mock engine, recorded exactly the six downcall and
  one upcall shapes listed there, and no resource or reflection the binary
  lacks (Clojure's namespaces are initialised at build time).
- **Intel Macs build on GraalVM CE 25.0.1**, exactly: 25.0.2 dropped macOS x64
  ([ADR 0007](adr/0007-intel-macs-on-graalvm-25-0-1.md)). `deps.edn` pins it
  (`:graalvm {:macos-x64 "25.0.1"}`), and `bb native` refuses any other
  release there. Every other platform takes the latest GraalVM 25.
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
  token, the sign-in redirect, the UI page and its assets, the progress bar,
  `profiles save` and `profiles effective`, and a render through the C ABI. CI's `native` job runs it on
  Linux x64, Windows x64, macOS arm64 and macOS x64 (by hand, or on a pull
  request labelled `native`).

### FFmpeg for the download
```bash
bb ffmpeg                       # this OS;  bb ffmpeg :platform :windows-x64  for another
```
The builds are pinned in `deps.edn` (`:wmark/build-matrix` → `:ffmpeg`), never
"latest" ([ADR 0001](adr/0001-ffmpeg-in-release-bundles.md)). They are **LGPL**
builds (LGPL-3.0-or-later):
- **Linux x64 and Windows x64:** BtbN's LGPL build, an archive by URL and
  SHA-256.
- **macOS arm64 and x64:** no maintained LGPL build exists, so the task
  builds FFmpeg's signed 9.0.2 source release (pinned by URL and SHA-256)
  with the configure recipe in `deps.edn`, on the Mac itself. That takes a
  few minutes once; the result is cached per recipe. It needs the Xcode
  command-line tools, and on Intel Macs `nasm` (`brew install nasm`; a build
  tool, not shipped). The recipe links nothing outside FFmpeg and macOS, and
  needs macOS 11 or later.

A changed or missing file fails the task. It writes
`target/ffmpeg/<platform>/bin/{ffmpeg,ffprobe}` and
`licenses/{COPYING.LGPLv3,COPYING.GPLv3,SOURCE.txt}` (the LGPLv3 adds
permissions to the GPLv3, so both texts ship). On this machine's platform it
then checks the binary against its pin: the license `ffmpeg -L` states, no
`--enable-gpl` or `--enable-nonfree` in an LGPL build, and on macOS no
library outside the OS. Downloads and builds are cached in
`target/downloads/`. To move a pin, change the URL and SHA-256 together, run
`bb lint` (it rejects unpinned or plain-http archives) and the smoke test.

`bb ffmpeg :variant :gpl` fetches the pinned GPL builds instead (BtbN for
Linux and Windows, martin-riedl.de for macOS) into
`target/ffmpeg/<platform>-gpl/`. They add x264, x265 and `perspective`, for
comparisons and render spec v1; the downloads don't ship them.

### The download (Stage 1)
```bash
bb native && bb ffmpeg
bb bundle :bundle :desktop-server :ffmpeg-dir target/ffmpeg/linux-x64
bb smoke --bin dist/desktop-server --ffmpeg dist/desktop-server/bin --bundled true
```
This writes `dist/desktop-server/` with:
- `wmark(.exe)`;
- `bin/ffmpeg(.exe)` and `bin/ffprobe(.exe)`;
- `licenses/`: this repository's `LICENSE` and `NOTICE`,
  `THIRD-PARTY-wmark.txt` (every library in
  the binary with its declared license and the license files it ships,
  generated from the resolved dependencies), and FFmpeg's
  `ffmpeg/COPYING.LGPLv3`, `ffmpeg/COPYING.GPLv3` and `ffmpeg/SOURCE.txt`;
- `SHA256SUMS` and `README.txt`.

`--bundled true` makes the smoke test find FFmpeg the way a download does:
in `bin/` next to wmark, under the hardened search order.

### Releases
Start `.github/workflows/release.yml` on `main` in one of two ways:
- **From the GitHub web UI:** open **Actions**, pick the **release**
  workflow, then **Run workflow**. Keep the branch on `main` and enter the
  version without the `v` (e.g. `0.1.0-rc.1`). The run checks the version
  (`X.Y.Z` or `X.Y.Z-rc.N`, not taken yet). Once every bundle is built and
  smoke-tested, it tags `main`'s commit `v<version>` itself.
- **From git:** push a tag `vX.Y.Z` (or `vX.Y.Z-rc.N`) on a commit of `main`.

Either way it stamps the version into the binaries
(`desktop/resources/wmark/version.txt`, which `wmark version`, `doctor` and
the API report; development builds say `0.2.0-SNAPSHOT`), checks that they
report it, and stops at a **draft** release:
1. builds and smoke-tests the bundle on Linux x64, Windows x64, macOS arm64 and
   macOS x64 (and runs the browser suite against the Linux binary);
2. would sign in the protected `release` environment, but **code signing is
   deferred**: the owner rejected [ADR 0002](adr/0002-windows-code-signing.md)
   (Authenticode) and [ADR 0003](adr/0003-macos-signing-and-library-validation.md)
   (Developer ID, notarization) for now. Those jobs stay in the workflow,
   frozen and skipped, so Windows and macOS bundles are unsigned;
3. publishes `wmark-<version>-<platform>.zip|tar.gz`, `SHA256SUMS`, a keyless
   Sigstore signature of the checksums and build-provenance attestations
   ([ADR 0004](adr/0004-release-supply-chain.md)).

The draft's notes say which bundles are signed and how to verify a download.

### Unsigned downloads
While signing is deferred, the operating system warns before the first run.
Verify the download first (`sha256sum -c SHA256SUMS --ignore-missing`, and the
Sigstore bundle as the release notes show), then:
- **macOS.** Gatekeeper refuses the first launch. Either open System Settings
  → Privacy & Security and click **Open Anyway** after trying once (Apple:
  [Safely open apps on your Mac](https://support.apple.com/en-us/102445)); wmark
  starts `bin/ffmpeg` and `bin/ffprobe` itself, so they may ask too. Or clear
  the quarantine flag of the whole extracted folder in Terminal:
  `xattr -dr com.apple.quarantine wmark-<version>-macos-arm64`.
- **Windows.** SmartScreen shows "Windows protected your PC": click **More
  info**, then **Run anyway**, or run `Unblock-File` in PowerShell on the
  extracted files first. **Smart App Control** blocks unsigned programs with
  no per-app exception
  ([Microsoft's FAQ](https://support.microsoft.com/en-us/windows/security/threat-malware-protection/smart-app-control-frequently-asked-questions)):
  where it is on, it has to be turned off in Windows Security → App & browser
  control. Machines that allow only signed code can't run wmark until
  releases are signed.
- **Linux** has no OS-level code signing; the checksums and attestations are
  the whole story.
Tags `abi-vN` and `kernel-v*` publish the **engine SDK** (`bb sdk :name abi-v2`
locally): the header, the mock, the JSON Schemas (render spec v1 and v2,
settings), the golden vectors with the font render-v2 uses, README and
ENGINE.md. `abi-vN` must match `WMARK_ENGINE_ABI_VERSION`.

## 6. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| UI says "This browser isn't signed in" | Open the exact URL the engine printed (it carries the token), or restart it |
| `doctor` says NOT READY | FFmpeg not found: see the trail it prints; put `ffmpeg`/`ffprobe` in `bin/` next to wmark |
| "text layers are unavailable" | This FFmpeg build has neither `drawtext` nor `overlay`: install a full build. (A build without only `drawtext` works: wmark draws the text itself, render spec v2) |
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
- the smoke test (20 checks) on both binaries, including render spec v2 and
  the C mock through FFM (ABI 2's still decoding included), and on the
  assembled Linux bundle with its bundled FFmpeg (15 checks);
- the smoke test with only the pinned LGPL FFmpeg: the default render picks
  render spec v2 and keeps every frame (17 checks);
- the browser suite (20 checks) against the native binary and on the JVM;
- the tracing agent over those flows (see "Native binaries");
- `bb ffmpeg` for all four platforms (downloads and checksums; only the Linux
  binaries were run);
- the FFmpeg conformance harness with FFmpeg 9.0.1.

**Verified in CI (2026-09-27, `native` job, run 36324745992): every step green**
on Linux x64, Windows x64 (Windows Server 2025), macOS 15 arm64 and macOS 15
Intel:
- the smoke test on the binaries (the FFM mock included, except on Intel macOS,
  where GraalVM has no FFM) and on each assembled bundle;
- the browser suite against the Linux native binary;
- on macOS arm64, the same smoke test under an ad-hoc hardened-runtime
  signature.

The first run found what the Windows manifest and the e2e `PATH` fix address:
non-ASCII arguments arrived mangled on Windows, and the runner had no ffmpeg
for the browser suite.

**Not run yet:**
- the release and SDK workflows (they run on tags);
- signing and notarization: deferred by the owner (ADRs 0002 and 0003);
- opening an unsigned download on a real, quarantined macOS or Windows
  machine (the steps in "Unsigned downloads" follow the vendors' docs);
- the OS encoders (Media Foundation, VideoToolbox) on real hardware.
