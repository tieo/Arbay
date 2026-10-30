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

Protocol: one JSON request per line on stdin, one JSON answer per line on stdout, carrying the
request's "id". Requests run at once, one per tab (ARBAY_BROWSER_TABS, one by default), and their
answers come in the order they finish.
  {"op": "get", "url": "...", "markers": ["s-card", ...], "errors": ["went wrong"],
   "accept": ["Alle akzeptieren"], "timeout": 30}
      -> {"ok": true, "html": "...", "url": "<final url>", "page": "content"|"errorpage"|"challenge"|"refused"|"unknown"}
     "accept" names the buttons of a consent screen to press when one stands before the page.
     "tick" names, by CSS selector, the "I'm not a robot" box of a check that passes on a click
     (Yandex's checkbox, Mojeek's ALTCHA); it is clicked once, with the mouse, as a person would.
     A check that asks more after the click (a picture puzzle) stays "refused", unless "solve" names
     the puzzle: "yandex" has Yandex's picture puzzle read by yandex_captcha.py and clicked, the
     pointer moving to each icon as a person's would, up to three puzzles; "brave" switches Brave
     Search's check to its slider and drags the slider as far as brave_captcha.py reads it.
  {"op": "cookies", "domain": "ebay"} -> {"ok": true, "cookies": {...}, "userAgent": "..."}
  {"op": "resolve", "urls": [...], "leaving": "google.", "timeout": 10}
      -> {"ok": true, "to": {"<url>": "<where it led>", ...}}
     Opens each of a site's redirects in a tab of their own, one after another, and notes the first
     address off the site it leads to, leaving that page unloaded. Google's result redirects answer
     plain requests with a wait page after a few, and a browser at once.
The first line written is {"ok": true, "ready": true}.
"""
import asyncio
import html as html_lib
import json
import os
import re
import sys
import time

import zendriver as zd

CHROME = os.environ.get("STEALTH_CHROME", "/usr/bin/google-chrome-stable")
PROFILE = os.environ.get("ARBAY_BROWSER_PROFILE", os.path.expanduser("~/.arbay/browser-profile"))
# How long the page behind a ticked box is given to replace the check.
TICK_WAIT_S = 12
# Puzzles answered per page before it counts as refused: Yandex takes about two in five.
PUZZLES = 3
# Seconds one puzzle takes, reading it to its answer: a page answers one only with that much left.
PUZZLE_S = 20


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
    """The Chrome and its tabs: ARBAY_BROWSER_TABS of them, each reading one page at a time, so a
    browser several searches share reads that many pages at once. Each tab is a window of its own,
    in front, since a page in a background tab runs its scripts (a bot check among them) slowly."""

    def __init__(self) -> None:
        self.browser = None
        self.tabs: asyncio.Queue = asyncio.Queue()
        self.count = max(1, int(os.environ.get("ARBAY_BROWSER_TABS", "1")))

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
                  "browser_args": ["--window-position=0,0", "--window-size=1280,900", "--hide-crash-restore-bubble",
                                   "--disable-background-timer-throttling", "--disable-backgrounding-occluded-windows",
                                   "--disable-renderer-backgrounding"]}
        if os.path.exists(CHROME):
            kwargs["browser_executable_path"] = CHROME
        self.browser = await zd.start(**kwargs)
        self.tabs.put_nowait(await self.browser.get("about:blank"))
        for _ in range(self.count - 1):
            self.tabs.put_nowait(await self.browser.get("about:blank", new_window=True))

    async def fresh_tab(self):
        """A tab in place of one a page broke (zendriver's "invalid state" on a page torn down
        mid-answer)."""
        return await self.browser.get("about:blank", new_window=True)

    async def press_consent(self, tab, labels: list[str]) -> bool:
        """Presses the first button carrying one of [labels], where a consent screen shows one."""
        if not labels:
            return False
        pressed = await tab.evaluate(
            """((labels) => {
                const buttons = [...document.querySelectorAll('button, [role=button], input[type=submit]')];
                const b = buttons.find(e => labels.some(l => (e.innerText || e.value || '').trim().toLowerCase() === l.toLowerCase()));
                if (!b) return false;
                b.click();
                return true;
            })(%s)""" % json.dumps(labels)
        )
        return bool(pressed)

    async def tick_box(self, tab, selectors: list[str]) -> bool:
        """Clicks the first of [selectors] the page shows, with the mouse, where one is there."""
        for sel in selectors:
            try:
                box = await tab.select(sel, timeout=1)
            except Exception:
                continue
            if box:
                await box.mouse_click()
                return True
        return False

    async def solve_yandex(self, tab) -> bool:
        """Answers Yandex's picture puzzle on [tab]: the icons the strip shows, clicked in its order.
        True when the page went on past it."""
        import random
        import urllib.request
        import yandex_captcha
        h = await tab.get_content()
        if "AdvancedCaptcha_audio" in h:
            await (await tab.select('button[data-testid="challenge-type"]', timeout=5)).mouse_click()
            await asyncio.sleep(3)
            h = await tab.get_content()
        pic = re.search(r'AdvancedCaptcha-ImageWrapper.*?<img[^>]*src="([^"]+)"', h, re.S)
        strip = re.search(r'class="TaskImage-Img"[^>]*src="([^"]+)"|src="([^"]+)"[^>]*class="TaskImage-Img"', h)
        if not pic or not strip:
            return False
        jar = "; ".join(f"{c.name}={c.value}" for c in await self.browser.cookies.get_all() if "yandex" in (c.domain or ""))
        agent = await tab.evaluate("navigator.userAgent")

        def fetch(u):
            req = urllib.request.Request(html_lib.unescape(u), headers={"Cookie": jar, "User-Agent": agent})
            return urllib.request.urlopen(req, timeout=20).read()

        loop = asyncio.get_running_loop()
        picture = await loop.run_in_executor(None, fetch, pic.group(1))
        order = await loop.run_in_executor(None, fetch, strip.group(1) or strip.group(2))
        points = await loop.run_in_executor(None, yandex_captcha.solve, picture, order)
        img = await tab.select(".AdvancedCaptcha-ImageWrapper img", timeout=5)
        pos = await img.get_position()
        height, width = yandex_captcha.flat(picture).shape[:2]
        sx, sy = pos.width / width, pos.height / height
        await asyncio.sleep(random.uniform(1.5, 3.0))
        cx, cy = pos.left + random.uniform(-40, 40), pos.top + pos.height + 60
        for x, y in points:
            tx = pos.left + x * sx + random.uniform(-1.5, 1.5)
            ty = pos.top + y * sy + random.uniform(-1.5, 1.5)
            steps = random.randint(8, 16)
            for k in range(1, steps + 1):
                f = k / steps
                ease = f * f * (3 - 2 * f)
                await tab.mouse_move(cx + (tx - cx) * ease + random.uniform(-1, 1), cy + (ty - cy) * ease + random.uniform(-1, 1), steps=1)
                await asyncio.sleep(random.uniform(0.012, 0.035))
            await tab.mouse_click(tx, ty)
            cx, cy = tx, ty
            await asyncio.sleep(random.uniform(0.5, 1.1))
        await asyncio.sleep(random.uniform(0.6, 1.2))
        await (await tab.select('button[data-testid="submit"]', timeout=5)).mouse_click()
        await asyncio.sleep(5)
        return "AdvancedCaptcha" not in await tab.get_content()

    async def pointer(self, tab, kind: str, x: float, y: float, held: bool) -> None:
        """One mouse event at x, y: "mouseMoved", "mousePressed" or "mouseReleased", [held] saying
        whether the left button is down while it happens. zendriver's own mouse_move ends in a
        release, which would drop anything being dragged."""
        from zendriver import cdp
        await tab.send(cdp.input_.dispatch_mouse_event(kind, x=x, y=y, button=cdp.input_.MouseButton("left"),
                                                       buttons=1 if held else 0, click_count=1))

    async def solve_brave(self, tab) -> bool:
        """Answers Brave Search's check on [tab] with its slider: the page offers a proof of work
        first, which a browser here does not get through, and the slider beside it. True when the
        page went on past it."""
        import base64
        import random
        import brave_captcha
        from zendriver import cdp
        # Brave's page saw a mouse move but no press until focus was emulated for the tab, though
        # document.hasFocus() was already true.
        await tab.send(cdp.emulation.set_focus_emulation_enabled(True))
        if "captcha-image-canvas" not in await tab.get_content():
            switch = await tab.find("Switch to traditional CAPTCHA", best_match=True, timeout=5)
            await switch.mouse_click()
            for _ in range(20):
                await asyncio.sleep(1)
                if "captcha-image-canvas" in await tab.get_content():
                    break
            await asyncio.sleep(random.uniform(0.8, 1.6))
        shown = await tab.evaluate("[...document.querySelectorAll('canvas.captcha-image-canvas, canvas.captcha-slider-canvas')]"
                                   ".map(c => [c.toDataURL('image/png'), c.getBoundingClientRect().width])")
        if not isinstance(shown, list) or len(shown) != 2:
            return False
        (picture, shown_width), (piece, _) = shown
        found = brave_captcha.solve(base64.b64decode(picture.split(",", 1)[1]), base64.b64decode(piece.split(",", 1)[1]))
        # The canvases are drawn a little narrower than their own 310 pixels, and Brave measures
        # the answer in canvas pixels: unscaled, a long slide overshot by seven and was refused.
        distance = (found["target"][0] - found["piece"][0]) * shown_width / found["width"]
        handle = await (await tab.select("button.captcha-slider-button", timeout=5)).get_position()
        x0, y0 = handle.left + handle.width / 2, handle.top + handle.height / 2
        await self.pointer(tab, "mouseMoved", x0 - random.uniform(25, 50), y0 + random.uniform(15, 35), held=False)
        await asyncio.sleep(random.uniform(0.2, 0.5))
        await self.pointer(tab, "mouseMoved", x0 + random.uniform(-3, 3), y0 + random.uniform(-3, 3), held=False)
        await asyncio.sleep(random.uniform(0.1, 0.3))
        await self.pointer(tab, "mousePressed", x0, y0, held=True)
        await asyncio.sleep(random.uniform(0.08, 0.2))
        steps = random.randint(28, 40)
        for k in range(1, steps + 1):
            f = k / steps
            ease = 1 - (1 - f) ** 3
            await self.pointer(tab, "mouseMoved", x0 + distance * ease + random.uniform(-0.5, 0.5), y0 + random.uniform(-1.2, 1.2), held=True)
            await asyncio.sleep(random.uniform(0.01, 0.028))
        await asyncio.sleep(random.uniform(0.15, 0.35))
        await self.pointer(tab, "mouseReleased", x0 + distance, y0, held=False)
        await asyncio.sleep(5)
        return "captcha-image-canvas" not in await tab.get_content()

    async def get(self, tab, url: str, markers: list[str], errors: list[str], accept: list[str], timeout: float,
                  refusals: list[str] = (), tick: list[str] = (), solve: str = "") -> dict:
        await tab.get(url)
        html, page = "", "unknown"
        # Time as it passes, puzzles answered included: counting only the waits let a page with
        # puzzles run past the request's limit, and the browser was restarted as stuck.
        start = time.monotonic()
        waited = 0.0
        pressed = False
        ticked_at = None
        solved = 0
        while waited < timeout:
            await asyncio.sleep(1)
            waited = time.monotonic() - start
            html = await tab.get_content()
            page = what_page(html, markers, errors, refusals)
            # A check passed by ticking its box is ticked once; the page behind it then loads in its
            # place, given a few seconds to.
            if page in ("refused", "challenge", "unknown") and tick and ticked_at is None:
                if await self.tick_box(tab, tick):
                    ticked_at = waited
                    continue
            if page == "refused" and solve == "yandex" and solved < PUZZLES and "AdvancedCaptcha" in html and timeout - waited > PUZZLE_S:
                solved += 1
                try:
                    await self.solve_yandex(tab)
                except Exception as e:
                    print(f"yandex puzzle: {e}", file=sys.stderr, flush=True)
                ticked_at = time.monotonic() - start
                continue
            if page == "refused" and solve == "brave" and solved < PUZZLES and ("Switch to traditional CAPTCHA" in html or "captcha-image-canvas" in html) and timeout - waited > PUZZLE_S:
                solved += 1
                try:
                    await self.solve_brave(tab)
                except Exception as e:
                    print(f"brave puzzle: {e}", file=sys.stderr, flush=True)
                ticked_at = time.monotonic() - start
                continue
            if page == "refused" and ticked_at is not None and waited - ticked_at < TICK_WAIT_S:
                continue
            # A challenge solves itself in a real browser and then shows the page; anything else
            # that is not the page yet is still loading, or waiting on its consent screen.
            if page in ("content", "errorpage", "refused"):
                break
            if not pressed and page == "unknown":
                pressed = await self.press_consent(tab, accept)
        final = await tab.evaluate("location.href")
        return {"ok": True, "html": html, "url": final, "page": page}

    async def resolve(self, tab, urls: list[str], leaving: str, timeout: float) -> tuple:
        """Where each of [urls] leads, and the tab to give back: a new one where a page broke it."""
        to = {}
        for url in urls:
            try:
                await tab.evaluate("location.href = %s" % json.dumps(url))
            except Exception:
                tab = await self.fresh_tab()
                continue
            waited = 0.0
            while waited < timeout:
                await asyncio.sleep(0.25)
                waited += 0.25
                try:
                    now = await tab.evaluate("location.href")
                except Exception:
                    continue
                if isinstance(now, str) and now.startswith("http") and leaving not in now.split("/")[2]:
                    to[url] = now
                    break
            try:
                await tab.evaluate("window.stop(); location.href = 'about:blank'")
            except Exception:
                tab = await self.fresh_tab()
        return {"ok": True, "to": to}, tab

    async def cookies(self, tab, domain: str) -> dict:
        jar = {c.name: c.value for c in await self.browser.cookies.get_all() if domain in (c.domain or "")}
        return {"ok": True, "cookies": jar, "userAgent": await tab.evaluate("navigator.userAgent")}

    async def answer(self, req: dict) -> dict:
        """One request on whichever tab is free, the tab given back after; a tab that broke is
        replaced."""
        op = req.get("op")
        timeout = float(req.get("timeout") or 30)
        tab = await self.tabs.get()
        try:
            if op == "get":
                return await asyncio.wait_for(
                    self.get(tab, req["url"], req.get("markers") or [], req.get("errors") or [], req.get("accept") or [], timeout,
                             req.get("refusals") or [], req.get("tick") or [], req.get("solve") or ""),
                    timeout + 15,
                )
            if op == "resolve":
                urls = req.get("urls") or []
                answer, tab = await asyncio.wait_for(self.resolve(tab, urls, req.get("leaving") or "", timeout), timeout * len(urls) + 15)
                return answer
            if op == "cookies":
                return await self.cookies(tab, req.get("domain") or "")
            return {"ok": False, "error": f"unknown op {op}"}
        except asyncio.TimeoutError:
            return {"ok": False, "error": "page did not load in time", "stuck": True}
        except Exception as e:
            try:
                tab = await self.fresh_tab()
            except Exception:
                pass
            return {"ok": False, "error": str(e)[:300]}
        finally:
            self.tabs.put_nowait(tab)


async def main() -> None:
    b = Browser()
    await b.start()
    loop = asyncio.get_event_loop()
    reader = asyncio.StreamReader(limit=1 << 20)
    await loop.connect_read_pipe(lambda: asyncio.StreamReaderProtocol(reader), sys.stdin)
    print(json.dumps({"ok": True, "ready": True}), flush=True)

    async def handle(req: dict) -> None:
        answer = await b.answer(req)
        if "id" in req:
            answer["id"] = req["id"]
        print(json.dumps(answer), flush=True)

    running = set()
    while True:
        line = await reader.readline()
        if not line:
            break
        try:
            req = json.loads(line)
        except Exception as e:
            print(json.dumps({"ok": False, "error": str(e)[:300]}), flush=True)
            continue
        task = asyncio.create_task(handle(req))
        running.add(task)
        task.add_done_callback(running.discard)
    await b.browser.stop()


if __name__ == "__main__":
    asyncio.run(main())
