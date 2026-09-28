# SPDX-FileCopyrightText: 2026 The clogem-wmark authors
# SPDX-License-Identifier: EPL-2.0
"""Browser smoke test of the built-in UI against a real engine and FFmpeg.

Usage (from the repository root; needs ffmpeg on PATH and Python Playwright):

    python3 test/e2e/ui_smoke.py <command that starts `wmark serve --announce json`>

e.g. python3 test/e2e/ui_smoke.py clojure -M:desktop:web -m watermark.main \
         --home /tmp/wmark-e2e serve --announce json

Test tooling only: Playwright ships its own driver, but nothing here is part of
the product, which has no Node, npm or JavaScript build.
"""
import json, os, subprocess, sys, tempfile, time, urllib.request
from playwright.sync_api import sync_playwright

work = tempfile.mkdtemp(prefix="wmark-e2e-")
clip = os.path.join(work, "clip.mp4")
subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "testsrc2=size=640x360:rate=25:duration=6",
                "-f", "lavfi", "-i", "sine=frequency=440:duration=6",
                # FFmpeg's own encoder: the bundled LGPL builds have no x264
                "-c:v", "mpeg4", "-q:v", "5", "-pix_fmt", "yuv420p", "-c:a", "aac",
                "-shortest", clip], check=True)

# --parent-pid: the engine exits with this script, even when started through a
# wrapper (bb clojure, a shell) that wouldn't pass the termination on
proc = subprocess.Popen(sys.argv[1:] + ["--parent-pid", str(os.getpid())],
                        stdout=subprocess.PIPE, stderr=subprocess.DEVNULL, text=True)
ep = json.loads(proc.stdout.readline())
U, TOKEN = ep["url"], ep["token"]
results, failures = {}, []

def check(name, ok, detail=""):
    results[name] = "ok" if ok else f"FAILED {detail}"
    if not ok:
        failures.append(name)

def api(method, path, body=None):
    req = urllib.request.Request(U + "/api/v1" + path, method=method,
                                 data=json.dumps(body).encode() if body is not None else None,
                                 headers={"Authorization": "Bearer " + TOKEN, "Content-Type": "application/json"})
    try:
        return json.load(urllib.request.urlopen(req))
    except urllib.error.HTTPError as e:
        return {"status": e.code, **json.load(e)}

def until(pred, timeout=20):
    # polled from Python: the page's CSP forbids eval, which wait_for_function needs
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            if pred():
                return True
        except Exception:
            pass
        time.sleep(0.05)
    return False

CSP_PROBE = """
window.__csp = [];
document.addEventListener('securitypolicyviolation',
  e => window.__csp.push(e.violatedDirective + ' ' + (e.blockedURI || '')));
"""

try:
    with sync_playwright() as p:
        browser = p.chromium.launch()
        ctx = browser.new_context(viewport={"width": 1280, "height": 1000})
        ctx.add_init_script(CSP_PROBE)
        page = ctx.new_page()
        console, scripts = [], []
        page.on("console", lambda m: console.append(f"{m.type}: {m.text}") if m.type in ("error", "warning") else None)
        page.on("pageerror", lambda e: console.append(f"pageerror: {e}"))
        page.on("request", lambda r: scripts.append(r.url.replace(U, "")) if r.resource_type == "script" else None)
        frames = []
        page.on("request", lambda r: frames.append(time.time()) if "/ui/frame/" in r.url else None)
        page.on("dialog", lambda d: d.accept())

        page.goto(f"{U}/?token={TOKEN}")
        check("page loads behind the token", until(lambda: page.query_selector("#profiles") is not None))
        check("engine status shown", "ready" in page.inner_text("#engine"), page.inner_text("#engine"))
        check("queue stream opened", until(lambda: page.query_selector("#jobs") is not None))

        page.fill("#newname", "Smoke test")
        page.click("#new-profile")
        check("create", until(lambda: page.inner_text("#message").startswith("Created")), page.inner_text("#message"))
        check("new profile selected", page.inner_text("#profile-title") == "Smoke test")

        # --- the settings form: click to edit, one path at a time (ADR 0011)
        row = lambda rid: page.query_selector(f"#row-{rid}")
        check("form rows rendered", until(lambda: row("logo-opacity") is not None))
        check("defaults marked", "Built-in default" in row("logo-opacity").inner_text(), row("logo-opacity").inner_text())
        page.click("#row-logo-opacity button.value")
        check("click turns a row into its control", until(lambda: page.query_selector("#row-logo-opacity input[type=number]") is not None))
        page.fill("#row-logo-opacity input[type=number]", "70")
        page.press("#row-logo-opacity input[type=number]", "Enter")
        check("Enter saves one setting", until(lambda: "70%" in row("logo-opacity").inner_text()
                                                   and "Set here" in row("logo-opacity").inner_text()),
              row("logo-opacity").inner_text())
        page.click("#row-logo-opacity button.value")
        until(lambda: page.query_selector("#row-logo-opacity input[type=number]") is not None)
        page.fill("#row-logo-opacity input[type=number]", "150")
        page.press("#row-logo-opacity input[type=number]", "Enter")
        check("bad values are explained at the field", until(lambda: "at most 100" in row("logo-opacity").inner_text()),
              row("logo-opacity").inner_text())
        page.press("#row-logo-opacity input[type=number]", "Escape")
        check("Escape cancels", until(lambda: page.query_selector("#row-logo-opacity button.value") is not None))
        page.click("#row-logo-opacity button:has-text('Reset')")
        check("reset to the default", until(lambda: "Built-in default" in row("logo-opacity").inner_text()),
              row("logo-opacity").inner_text())
        page.click("#row-logo-anchor button.value")
        until(lambda: page.query_selector("#row-logo-anchor select") is not None)
        page.select_option("#row-logo-anchor select", "top-left")
        check("an enum is a select that saves on change", until(lambda: "Top left" in row("logo-anchor").inner_text()),
              row("logo-anchor").inner_text())
        page.select_option("select[aria-label='Kind of the new text layer']", "scheduled")
        page.click("button:has-text('Add text layer')")
        check("text layers are cards", until(lambda: page.query_selector("#layer-0") is not None
                                             and "Scheduled" in page.inner_text("#layer-0")))
        check("the canary kind is shown as canary", "Canary" in page.inner_text("#cat-texts")
              and "ubliminal" not in page.inner_text("#cat-texts"))
        page.fill("input[aria-label='Search settings']", "codec")
        check("search filters rows", until(lambda: not page.is_visible("#row-logo-anchor") and page.is_visible("#row-encode-codec")))
        page.fill("input[aria-label='Search settings']", "")

        # --- live preview: the render's own frame
        img_ok = lambda: page.evaluate("(() => { const i = document.querySelector('#preview-frame img');"
                                       " return i && i.complete && i.naturalWidth > 0 ? [i.naturalWidth, i.naturalHeight] : null })()")
        check("preview frame drawn", until(lambda: img_ok() is not None, 60), page.inner_text("#preview"))
        check("preview is the 16:9 sample", img_ok() == [1280, 720], img_ok())
        page.click("#preview .segmented button:has-text('9:16')")
        check("preview follows the shape", until(lambda: img_ok() == [720, 1280], 60), img_ok())
        page.click("#preview .segmented button:has-text('16:9')")
        until(lambda: img_ok() == [1280, 720], 60)

        # --- themes: light, dark, and the choice survives a reload
        page.evaluate("window.scrollTo(0, 0)")
        page.screenshot(path=os.path.join(work, "ui-light.png"), full_page=True)
        page.click(".topbar button:has-text('Dark')")
        check("dark theme", until(lambda: page.get_attribute("body", "data-theme") == "dark"))
        page.wait_for_timeout(300)
        page.evaluate("window.scrollTo(0, 0)")
        page.screenshot(path=os.path.join(work, "ui-dark.png"), full_page=True)
        page.reload()
        check("the theme is remembered", until(lambda: page.get_attribute("body", "data-theme") == "dark"))
        page.click(".topbar button:has-text('Light')")
        check("light theme", until(lambda: page.get_attribute("body", "data-theme") == "light"))
        until(lambda: row("logo-opacity") is not None)

        # --- the JSON view stays for bulk edits
        page.click("summary:has-text('Edit as JSON')")
        settings = {"logo": {"enabled": False},
                    "texts": [{"mode": "continuous", "content": "(c) Studio <b>not bold</b>"}],
                    "output": {"dir": os.path.join(work, "out"), "overwrite?": True}}
        page.fill("#settings", json.dumps(settings, indent=2))
        check("live preview", until(lambda: "unsaved" in page.inner_text("#effective")))
        page.click("#save")
        check("save", until(lambda: page.inner_text("#message").startswith("Saved")), page.inner_text("#message"))
        check("the form follows the JSON", until(lambda: "Off" in row("logo-enabled").inner_text() or
                                                 not page.is_checked("#row-logo-enabled input[type=checkbox]")))

        page.fill("#inputs", f'"{clip}"')
        page.click("#run")
        check("queued", until(lambda: page.inner_text("#message").startswith("Queued 1 file")), page.inner_text("#message"))
        seen = []
        def progress():
            el = page.query_selector("#jobs progress")
            if el:
                v = el.get_attribute("value")
                if not seen or seen[-1] != v:
                    seen.append(v)
            return page.query_selector("#jobs li.done") or page.query_selector("#jobs li.failed")
        check("render finished", until(progress, 120))
        check("render succeeded", page.query_selector("#jobs li.done") is not None, page.inner_text("#jobs"))
        results["progress values seen"] = seen
        check("activity log", until(lambda: "done" in page.inner_text("#activity")))
        check("output written", os.path.isfile(os.path.join(work, "out", "clip_wm.mp4")))
        check("latest recorded", until(lambda: "(last run)" in page.inner_text("#profiles")))

        rev = api("GET", "/profiles/smoke-test")["profile/rev"]
        api("PUT", "/profiles/smoke-test", {"settings": settings, "if-rev": rev})   # another editor
        page.click("#save")
        check("stale save refused", until(lambda: "changed since it was loaded" in page.inner_text("#message")),
              page.inner_text("#message"))

        page.click("#profiles button:has-text('Smoke test')")      # reload the profile
        check("reload clears the conflict", until(lambda: page.inner_text("#message") == ""), page.inner_text("#message"))
        page.click("#row-output-overwrite- .switch")
        check("a switch saves on change", until(lambda: page.inner_text("#message").startswith("Saved")
                                                and not page.is_checked("#row-output-overwrite- input")),
              page.inner_text("#message"))
        page.fill("#name", "Smoke copy")
        t_switch = time.time()
        page.click("#duplicate")
        check("duplicate", until(lambda: page.inner_text("#profile-title") == "Smoke copy"),
              page.inner_text("#message"))
        page.wait_for_timeout(4000)
        after = [t for t in frames if t >= t_switch]
        check("a profile switch asks for one frame, not a stream of them", 1 <= len(after) <= 2, len(after))
        page.click("#delete")
        check("delete (after confirm)", until(lambda: page.inner_text("#message") == "Deleted."))

        page.screenshot(path=os.path.join(work, "ui.png"), full_page=True)
        check("only datastar.js loaded", set(scripts) == {"/datastar.js"}, scripts)   # once per page load
        check("no CSP violations", page.evaluate("window.__csp") == [], page.evaluate("window.__csp"))
        check("no console errors or warnings", console == [], console)
        browser.close()
finally:
    proc.terminate()
    proc.wait(10)

for k, v in results.items():
    print(f"{k}: {v}")
print("screenshots:", ", ".join(os.path.join(work, f) for f in ["ui.png", "ui-light.png", "ui-dark.png"]))
sys.exit(1 if failures else 0)
