# SPDX-FileCopyrightText: 2026 The clogem-wmark authors
# SPDX-License-Identifier: EPL-2.0
"""Drive the Datastar spike in Chromium and report what the assessment needs."""
import json, subprocess, sys, time, urllib.request
from playwright.sync_api import sync_playwright

proc = subprocess.Popen(sys.argv[1:], stdout=subprocess.PIPE, stderr=open("server.err", "w"), text=True)
ep = json.loads(proc.stdout.readline())
U, tok = ep["url"], ep["token"]
out = {}

def api(method, path, body=None):
    req = urllib.request.Request(U + "/api/v1" + path, method=method,
          data=json.dumps(body).encode() if body is not None else None,
          headers={"Authorization": "Bearer " + tok, "Content-Type": "application/json"})
    try: return 200, json.load(urllib.request.urlopen(req))
    except urllib.error.HTTPError as e: return e.code, json.load(e)

CSP_PROBE = """
window.__csp = [];
document.addEventListener('securitypolicyviolation',
  e => window.__csp.push(e.violatedDirective + ' ' + (e.blockedURI || '') + ' ' + (e.sample || '')));
"""

def until(pred, timeout=20):
    # poll from Python: the page's CSP forbids eval, which wait_for_function needs
    t0 = time.time()
    while time.time() - t0 < timeout:
        try:
            if pred(): return True
        except Exception:
            pass
        time.sleep(0.05)
    raise TimeoutError("condition not met")

def cell(page, row): return page.inner_text(f"#{row} td:nth-child(2)") + " | " + page.inner_text(f"#{row} td:nth-child(3)")

try:
    with sync_playwright() as p:
        b = p.chromium.launch()
        ctxt = b.new_context(viewport={"width": 1100, "height": 1000})
        ctxt.add_init_script(CSP_PROBE)
        page = ctxt.new_page()
        console, scripts = [], []
        page.on("console", lambda m: console.append(f"{m.type}: {m.text}") if m.type in ("error", "warning") else None)
        page.on("pageerror", lambda e: console.append(f"pageerror: {e}"))
        page.on("request", lambda r: scripts.append(r.url) if r.resource_type == "script" else None)

        # 1. token bootstrap -> cookie -> server-rendered page; latest fallbacks shown
        page.goto(f"{U}/?token={tok}")
        page.wait_for_selector("#effective")
        until(lambda: page.query_selector("#jobs") is not None)
        out["initial anchor"] = cell(page, "f-logo-anchor")
        out["initial output"] = cell(page, "f-output-dir")

        # 2. edits resolve server-side (debounced POST, fragment patch)
        page.fill("#opacity", "0.4")
        until(lambda: page.inner_text("#f-logo-opacity td:nth-child(3)") == "set here")
        page.select_option("#anchor", "center")
        until(lambda: page.inner_text("#f-logo-anchor td:nth-child(2)") == "center")
        out["after edits: anchor"] = cell(page, "f-logo-anchor")
        out["after edits: opacity"] = cell(page, "f-logo-opacity")
        page.fill("#opacity", "7")
        until(lambda: page.inner_text("#flash") != "")
        out["invalid opacity"] = page.inner_text("#flash")
        page.fill("#opacity", "0.4")
        until(lambda: page.inner_text("#flash") == "")

        # 3. render: live progress over the SSE stream
        seen = []
        page.click("#render")
        t0 = time.time()
        while time.time() - t0 < 120:
            el = page.query_selector("#jobs progress"); v = float(el.get_attribute("value")) if el else None
            if v is not None and (not seen or seen[-1] != v): seen.append(v)
            if page.query_selector("#jobs .job.done") or page.query_selector("#jobs .job.failed"): break
            time.sleep(0.1)
        out["render time s"] = round(time.time() - t0, 1)
        out["job row"] = page.inner_text("#jobs")
        out["distinct progress values seen"] = len(seen)
        out["progress samples"] = [round(x, 2) for x in seen[:: max(1, len(seen) // 8)]]
        out["log"] = page.inner_text("#log").splitlines()
        out["latest now"] = api("GET", "/profiles/latest")[1].get("settings", {}).get("logo")

        # 4. save with revision check; a second editor wins the race
        page.fill("#savename", "Spike")
        page.click("#save")
        until(lambda: page.inner_text("#flash").startswith("Saved"))
        out["save 1"] = page.inner_text("#flash") + " rev=" + page.inner_text("#rev")
        out["other editor PUT"] = api("PUT", "/profiles/spike", {"settings": {"logo": {"anchor": "top-right"}}, "if-rev": 1})[0]
        page.click("#save")
        until(lambda: page.get_attribute("#flash", "class") == "error")
        out["save 2 (stale)"] = page.inner_text("#flash")

        # 5. the stream drops: the page reconnects and re-syncs the whole queue
        before = ctxt.request.get(U + "/ui/debug/stats").json()
        ctxt.request.post(U + "/ui/debug/kick")
        page.wait_for_timeout(2500)
        after = ctxt.request.get(U + "/ui/debug/stats").json()
        out["streams opened before/after kick"] = [before["streams-opened"], after["streams-opened"]]
        out["queue after reconnect"] = page.inner_text("#jobs")
        out["sse events / bytes sent"] = [after["events"], after["bytes"]]

        page.screenshot(path="spike.png", full_page=True)
        out["scripts loaded"] = [s.replace(U, "") for s in scripts]
        out["csp violations (nonce mode)"] = page.evaluate("window.__csp")
        out["console errors"] = console[:]

        # 6. negative control: same page without Datastar's CSP mode
        console.clear()
        page2 = ctxt.new_page()
        page2.on("console", lambda m: console.append(f"{m.type}: {m.text}") if m.type == "error" else None)
        page2.goto(f"{U}/?nonce=off")
        page2.wait_for_timeout(1500)
        out["without nonce: csp violations"] = page2.evaluate("window.__csp")[:2]
        out["without nonce: queue rows rendered"] = len(page2.query_selector_all("#jobs .job"))
        b.close()
finally:
    proc.terminate()
    proc.wait(10)

for k, v in out.items():
    print(f"{k}: {v}")
