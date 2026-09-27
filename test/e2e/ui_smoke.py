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
        page.on("dialog", lambda d: d.accept())

        page.goto(f"{U}/?token={TOKEN}")
        check("page loads behind the token", until(lambda: page.query_selector("#profiles") is not None))
        check("engine status shown", "ready" in page.inner_text("#engine"), page.inner_text("#engine"))
        check("queue stream opened", until(lambda: page.query_selector("#jobs") is not None))

        page.fill("#newname", "Smoke test")
        page.click("#new-profile")
        check("create", until(lambda: page.inner_text("#message").startswith("Created")), page.inner_text("#message"))
        check("new profile selected", page.inner_text("#profile-title") == "Smoke test")

        settings = {"logo": {"enabled": False},
                    "texts": [{"mode": "continuous", "content": "(c) Studio <b>not bold</b>"}],
                    "output": {"dir": os.path.join(work, "out"), "overwrite?": True}}
        page.fill("#settings", json.dumps(settings, indent=2))
        check("live preview", until(lambda: "unsaved" in page.inner_text("#effective")))
        page.click("#save")
        check("save", until(lambda: page.inner_text("#message").startswith("Saved")), page.inner_text("#message"))

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
        page.fill("#name", "Smoke copy")
        page.click("#duplicate")
        check("duplicate", until(lambda: page.inner_text("#profile-title") == "Smoke copy"),
              page.inner_text("#message"))
        page.click("#delete")
        check("delete (after confirm)", until(lambda: page.inner_text("#message") == "Deleted."))

        page.screenshot(path=os.path.join(work, "ui.png"), full_page=True)
        check("only datastar.js loaded", scripts == ["/datastar.js"], scripts)
        check("no CSP violations", page.evaluate("window.__csp") == [], page.evaluate("window.__csp"))
        check("no console errors or warnings", console == [], console)
        browser.close()
finally:
    proc.terminate()
    proc.wait(10)

for k, v in results.items():
    print(f"{k}: {v}")
print("screenshot:", os.path.join(work, "ui.png"))
sys.exit(1 if failures else 0)
