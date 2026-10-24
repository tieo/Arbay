#!/usr/bin/env python3
"""
The Chrome eBay is read through: one real Google Chrome, kept running for the life of the server,
with its profile on disk.

eBay's edge turns away clients that do not look like a person's browser: curl_cffi with Chrome's
TLS fingerprint, Playwright's browsers and a freshly started Chrome each got its error page ("Something
went wrong on our end") while a person's Chrome on the same address read eBay normally. This
browser is that person's browser: started once, its cookies kept, every page read in the same tab,
one after another, the way someone browsing eBay reads it. Measured on 2026-10-24: twelve searches
and item pages in a row in two to four seconds each, at a time every other client got the error page.

Protocol: one JSON request per line on stdin, one JSON answer per line on stdout.
  {"op": "get", "url": "...", "markers": ["s-card", ...], "timeout": 30}
      -> {"ok": true, "html": "...", "url": "<final url>", "page": "content"|"errorpage"|"challenge"|"unknown"}
  {"op": "cookies"} -> {"ok": true, "cookies": {...}, "userAgent": "..."}
The first line written is {"ok": true, "ready": true}.
"""
import asyncio
import json
import os
import sys

import zendriver as zd

CHROME = os.environ.get("STEALTH_CHROME", "/usr/bin/google-chrome-stable")
PROFILE = os.environ.get("ARBAY_EBAY_PROFILE", os.path.expanduser("~/.arbay/ebay-profile"))


def what_page(html: str, markers: list[str]) -> str:
    """Whether a page is the one asked for, eBay's error page, or its proof-of-work challenge."""
    if any(m in html for m in markers):
        return "content"
    if "went wrong" in html and len(html) < 20000:
        return "errorpage"
    if "splashui" in html or "challenge" in html[:5000].lower():
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

    async def get(self, url: str, markers: list[str], timeout: float) -> dict:
        await self.tab.get(url)
        html, page = "", "unknown"
        waited = 0.0
        while waited < timeout:
            await asyncio.sleep(1)
            waited += 1
            html = await self.tab.get_content()
            page = what_page(html, markers)
            # A challenge solves itself in a real browser and then shows the page; anything else
            # that is not the page yet is still loading.
            if page in ("content", "errorpage"):
                break
        final = await self.tab.evaluate("location.href")
        return {"ok": True, "html": html, "url": final, "page": page}

    async def cookies(self) -> dict:
        jar = {c.name: c.value for c in await self.browser.cookies.get_all() if "ebay" in (c.domain or "")}
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
                answer = await asyncio.wait_for(b.get(req["url"], req.get("markers") or [], timeout), timeout + 15)
            elif op == "cookies":
                answer = await b.cookies()
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
