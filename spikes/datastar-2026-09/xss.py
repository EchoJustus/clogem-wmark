# SPDX-FileCopyrightText: 2026 The clogem-wmark authors
# SPDX-License-Identifier: EPL-2.0
"""Hostile strings through the Datastar UI: escaped, inert, no requests fired."""
import json, subprocess, sys, time, urllib.request
from playwright.sync_api import sync_playwright

proc = subprocess.Popen(sys.argv[1:], stdout=subprocess.PIPE, stderr=open("server.err", "w"), text=True)
ep = json.loads(proc.stdout.readline())
U, tok = ep["url"], ep["token"]

def api(method, path, body=None):
    req = urllib.request.Request(U + "/api/v1" + path, method=method,
          data=json.dumps(body).encode() if body is not None else None,
          headers={"Authorization": "Bearer " + tok, "Content-Type": "application/json"})
    try: return urllib.request.urlopen(req).status
    except urllib.error.HTTPError as e: return e.code

name = """Evil" data-on:load="@post('/ui/debug/kick')" x="<img src=x onerror=alert(1)>"""
text = """<b data-on:click="@post('/ui/debug/kick')">$evil</b>"""
print("create hostile profile:", api("POST", "/profiles", {"name": name, "settings": {}}))
fired = []
try:
    with sync_playwright() as p:
        b = p.chromium.launch()
        page = b.new_page()
        page.on("request", lambda r: fired.append(r.url) if "kick" in r.url else None)
        page.on("dialog", lambda d: (fired.append("dialog: " + d.message), d.dismiss()))
        page.goto(f"{U}/?token={tok}")
        page.wait_for_selector("#effective")
        options = [o.inner_text() for o in page.query_selector_all("#profile option")]
        print("profile options as rendered text:", options)
        page.fill("#text", text)
        t0 = time.time()
        while time.time() - t0 < 10 and "click" not in page.inner_text("#f-texts td:nth-child(2)"):
            time.sleep(0.05)
        print("text layer as rendered text:", page.inner_text("#f-texts td:nth-child(2)"))
        page.click("#f-texts td:nth-child(2)")
        print("injected elements:", len(page.query_selector_all("#effective b, #profile img, [x]")))
        page.wait_for_timeout(800)
        print("requests/dialogs triggered by hostile strings:", fired)
        b.close()
finally:
    proc.terminate(); proc.wait(10)
