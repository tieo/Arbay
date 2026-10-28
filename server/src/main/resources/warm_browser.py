#!/usr/bin/env python3
"""
A Chrome a site is read through: one real Google Chrome, kept running for the life of the server,
with its profile on disk. The server runs one per site that needs it (eBay, the search engines),
each with its own profile and display.

Some sites turn away clients that do not look like a person's browser. eBay's edge served its
error page to curl_cffi with Chrome's TLS fingerprint, to Playwright's browsers and to a freshly
started Chrome, while a person's Chrome on the same address read eBay normally; Google answers
anything that does not run its scripts with "access denied". This browser is that person's
browser: started once, its cookies kept, every page read in the same tab, one after another.
Measured on 2026-10-24 for eBay: twelve searches and item pages in a row in two to four seconds
each, at a time every other client got the error page.

Protocol: one JSON request per line on stdin, one JSON answer per line on stdout.
  {"op": "get", "url": "...", "markers": ["s-card", ...], "errors": ["went wrong"],
   "accept": ["Alle akzeptieren"], "timeout": 30}
      -> {"ok": true, "html": "...", "url": "<final url>", "page": "content"|"errorpage"|"challenge"|"refused"|"unknown"}
     "accept" names the buttons of a consent screen to press when one stands before the page.
  {"op": "cookies", "domain": "ebay"} -> {"ok": true, "cookies": {...}, "userAgent": "..."}
The first line written is {"ok": true, "ready": true}.
"""
import asyncio
import json
import os
import sys

import zendriver as zd

CHROME = os.environ.get("STEALTH_CHROME", "/usr/bin/google-chrome-stable")
PROFILE = os.environ.get("ARBAY_BROWSER_PROFILE", os.path.expanduser("~/.arbay/browser-profile"))


def what_page(html: str, markers: list[str], errors: list[str], refusals: list[str] = ()) -> str:
    """Whether a page is the one asked for, the site's error page, a challenge in front of it, or
    the site refusing the browser outright (a check that does not solve itself)."""
    if any(m in html for m in markers):
        return "content"
    if any(r in html for r in refusals):
        return "refused"
    if any(e in html for e in errors) and len(html) < 20000:
        return "errorpage"
    head = html[:8000].lower()
    if "splashui" in html or "challenge" in head or "/sorry/" in head or "unusual traffic" in head:
        return "challenge"
    return "unknown"


class Browser:
    def __init__(self) -> None:
        self.browser = None
        self.tab = None

    async def start(self) -> None:
        os.makedirs(PROFILE, exist_ok=True)
        # The server starts this sidecar only once the one before it has ended, so a lock left in
        # the profile by a Chrome the server ended is always stale.
        for name in ("SingletonLock", "SingletonSocket", "SingletonCookie"):
            try:
                os.unlink(os.path.join(PROFILE, name))
            except FileNotFoundError:
                pass
        kwargs = {"headless": False, "user_data_dir": PROFILE,
                  "browser_args": ["--window-position=0,0", "--window-size=1280,900", "--hide-crash-restore-bubble"]}
        if os.path.exists(CHROME):
            kwargs["browser_executable_path"] = CHROME
        self.browser = await zd.start(**kwargs)
        self.tab = await self.browser.get("about:blank")

    async def press_consent(self, labels: list[str]) -> bool:
        """Presses the first button carrying one of [labels], where a consent screen shows one."""
        if not labels:
            return False
        pressed = await self.tab.evaluate(
            """((labels) => {
                const buttons = [...document.querySelectorAll('button, [role=button], input[type=submit]')];
                const b = buttons.find(e => labels.some(l => (e.innerText || e.value || '').trim().toLowerCase() === l.toLowerCase()));
                if (!b) return false;
                b.click();
                return true;
            })(%s)""" % json.dumps(labels)
        )
        return bool(pressed)

    async def get(self, url: str, markers: list[str], errors: list[str], accept: list[str], timeout: float, refusals: list[str] = ()) -> dict:
        await self.tab.get(url)
        html, page = "", "unknown"
        waited = 0.0
        pressed = False
        while waited < timeout:
            await asyncio.sleep(1)
            waited += 1
            html = await self.tab.get_content()
            page = what_page(html, markers, errors, refusals)
            # A challenge solves itself in a real browser and then shows the page; anything else
            # that is not the page yet is still loading, or waiting on its consent screen.
            if page in ("content", "errorpage", "refused"):
                break
            if not pressed and page == "unknown":
                pressed = await self.press_consent(accept)
        final = await self.tab.evaluate("location.href")
        return {"ok": True, "html": html, "url": final, "page": page}

    async def cookies(self, domain: str) -> dict:
        jar = {c.name: c.value for c in await self.browser.cookies.get_all() if domain in (c.domain or "")}
        return {"ok": True, "cookies": jar, "userAgent": await self.tab.evaluate("navigator.userAgent")}


async def main() -> None:
    b = Browser()
    await b.start()
    loop = asyncio.get_event_loop()
    reader = asyncio.StreamReader(limit=1 << 20)
    await loop.connect_read_pipe(lambda: asyncio.StreamReaderProtocol(reader), sys.stdin)
    print(json.dumps({"ok": True, "ready": True}), flush=True)
    while True:
        line = await reader.readline()
        if not line:
            break
        try:
            req = json.loads(line)
            op = req.get("op")
            timeout = float(req.get("timeout") or 30)
            if op == "get":
                answer = await asyncio.wait_for(
                    b.get(req["url"], req.get("markers") or [], req.get("errors") or [], req.get("accept") or [], timeout, req.get("refusals") or []),
                    timeout + 15,
                )
            elif op == "cookies":
                answer = await b.cookies(req.get("domain") or "")
            else:
                answer = {"ok": False, "error": f"unknown op {op}"}
        except asyncio.TimeoutError:
            answer = {"ok": False, "error": "page did not load in time", "stuck": True}
        except Exception as e:
            answer = {"ok": False, "error": str(e)[:300]}
        print(json.dumps(answer), flush=True)
    await b.browser.stop()


if __name__ == "__main__":
    asyncio.run(main())
