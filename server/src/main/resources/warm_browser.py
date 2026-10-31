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
     Search's check to its slider and drags the slider as far as brave_captcha.py reads it;
     "recaptcha" answers the reCAPTCHA on Google's own "unusual traffic" page (solve_recaptcha).
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
# Puzzles a reCAPTCHA is answered through before giving up: a passed grid is often followed by
# another one.
RECAPTCHA_ROUNDS = 5
# Seconds a reCAPTCHA takes at most, its rounds of puzzles and new squares included.
RECAPTCHA_S = 75


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


def keep_rejected(state: dict, thing: str, n: int, chosen: list) -> None:
    """Keeps a grid Google refused, its picture and the squares clicked, in ARBAY_RECAPTCHA_DUMP:
    puzzles the reader got wrong are what its next training needs."""
    import base64
    import hashlib
    folder = os.environ.get("ARBAY_RECAPTCHA_DUMP")
    if not folder or not state.get("picture"):
        return
    try:
        os.makedirs(folder, exist_ok=True)
        data = base64.b64decode(state["picture"].split(",", 1)[1])
        key = hashlib.sha1(data).hexdigest()[:16]
        with open(os.path.join(folder, key + ".png"), "wb") as f:
            f.write(data)
        with open(os.path.join(folder, "rejected.jsonl"), "a") as f:
            f.write(json.dumps({"t": time.time(), "picture": key, "word": state.get("word"), "thing": thing, "n": n, "chosen": chosen}) + "\n")
    except Exception as e:
        print(f"recaptcha: could not keep the refused grid: {e}", file=sys.stderr, flush=True)


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
                                   "--disable-renderer-backgrounding",
                                   # Pages left behind stay alive in the back/forward cache, each in
                                   # a renderer of its own: three tabs walking through result pages
                                   # held twelve renderers and 2 GB. The last --disable-features is
                                   # the one Chrome reads, so zendriver's own are repeated here.
                                   "--disable-features=IsolateOrigins,site-per-process,DisableLoadExtensionCommandLineSwitch,BackForwardCache",
                                   # Ad frames each took a process of their own while only
                                   # site isolation's feature was off; this switch keeps them in
                                   # their page's. No cap on renderers: under one, tabs share a
                                   # process and a busy page held Google's tab past its deadline.
                                   "--disable-site-isolation-trials"]}
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
        """Answers Yandex's puzzle on [tab]: the symbol puzzle by clicking the icons the strip shows in
        its order, the typed-text puzzle by typing the two words it shows. True when the page went
        on past it."""
        import random
        import urllib.request
        import yandex_captcha
        h = await tab.get_content()
        if "AdvancedCaptcha_audio" in h:
            await (await tab.select('button[data-testid="challenge-type"]', timeout=5)).mouse_click()
            await asyncio.sleep(3)
            h = await tab.get_content()
        jar = "; ".join(f"{c.name}={c.value}" for c in await self.browser.cookies.get_all() if "yandex" in (c.domain or ""))
        agent = await tab.evaluate("navigator.userAgent")

        def fetch(u):
            req = urllib.request.Request(html_lib.unescape(u), headers={"Cookie": jar, "User-Agent": agent})
            return urllib.request.urlopen(req, timeout=20).read()

        loop = asyncio.get_running_loop()
        # The stricter kind, typed text: two words along a curve, read and typed into its field.
        if "AdvancedCaptcha_image" in h and "TaskImage-Img" not in h:
            import yandex_text
            shown = re.search(r'AdvancedCaptcha-View.*?<img[^>]*src="([^"]*captchaimage[^"]+)"', h, re.S)
            if not shown:
                return False
            picture = await loop.run_in_executor(None, fetch, shown.group(1))
            answer = await loop.run_in_executor(None, yandex_text.solve, picture)
            field = await tab.select('input[name="rep"]', timeout=5)
            await field.mouse_click()
            await asyncio.sleep(random.uniform(0.4, 0.9))
            for ch in answer:
                await field.send_keys(ch)
                await asyncio.sleep(random.uniform(0.05, 0.18))
            await asyncio.sleep(random.uniform(0.5, 1.2))
            await (await tab.select('button[data-testid="submit"]', timeout=5)).mouse_click()
            await asyncio.sleep(5)
            passed = "AdvancedCaptcha" not in await tab.get_content()
            # An answer Yandex took is a picture read right: kept, where a folder is named, to train on.
            keep = os.environ.get("ARBAY_TEXT_PUZZLES")
            if keep:
                os.makedirs(keep, exist_ok=True)
                name = f"{keep}/{int(time.time() * 1000)}_{'ok' if passed else 'no'}"
                open(name + ".png", "wb").write(picture)
                open(name + ".txt", "w").write(answer)
            return passed
        pic = re.search(r'AdvancedCaptcha-ImageWrapper.*?<img[^>]*src="([^"]+)"', h, re.S)
        strip = re.search(r'class="TaskImage-Img"[^>]*src="([^"]+)"|src="([^"]+)"[^>]*class="TaskImage-Img"', h)
        if not pic or not strip:
            return False
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

    async def click_at(self, tab, x: float, y: float) -> None:
        """A click at x, y with the pointer coming from a little way off, as a person's would."""
        import random
        await self.pointer(tab, "mouseMoved", x + random.uniform(-30, 30), y + random.uniform(-20, 20), held=False)
        await asyncio.sleep(random.uniform(0.08, 0.2))
        await self.pointer(tab, "mouseMoved", x, y, held=False)
        await asyncio.sleep(random.uniform(0.05, 0.15))
        await self.pointer(tab, "mousePressed", x, y, held=True)
        await asyncio.sleep(random.uniform(0.05, 0.12))
        await self.pointer(tab, "mouseReleased", x, y, held=False)

    # Reads reCAPTCHA's two frames from the page holding them, where they share its origin (Google's
    # own pages): the box's state, and the puzzle's word, grid, picture and the page positions of
    # its squares and buttons. Squares a dynamic grid put in place of clicked ones are listed with
    # their address and picture.
    RECAPTCHA_STATE = """(async () => {
        const doc = sel => { const f = document.querySelector(sel); try { return f && f.contentDocument ? [f, f.contentDocument] : null; } catch (e) { return null; } };
        const at = (frame, el) => { const a = frame.getBoundingClientRect(), b = el.getBoundingClientRect();
                                    return [a.left + b.left + b.width / 2, a.top + b.top + b.height / 2]; };
        const data = async src => { const blob = await (await fetch(src)).blob();
                                    return await new Promise(r => { const fr = new FileReader(); fr.onload = () => r(fr.result); fr.readAsDataURL(blob); }); };
        const out = {};
        const a = doc('iframe[src*="api2/anchor"], iframe[src*="enterprise/anchor"]');
        if (a) { const box = a[1].querySelector('#recaptcha-anchor'); if (box) { out.box = at(a[0], box); out.checked = box.getAttribute('aria-checked') === 'true'; } }
        const b = doc('iframe[src*="api2/bframe"], iframe[src*="enterprise/bframe"]');
        if (!b || b[0].getBoundingClientRect().height < 100) return out;
        const d = b[1], table = d.querySelector('table[class*="rc-imageselect-table"]');
        if (!table) return out;
        const desc = d.querySelector('.rc-imageselect-desc-wrapper');
        out.word = (desc && desc.querySelector('strong') || {}).innerText || '';
        out.text = desc ? desc.innerText : '';
        out.grid = table.className.match(/table-(\\d+)/)[1];
        out.tiles = [...d.querySelectorAll('td.rc-imageselect-tile')].map(td => at(b[0], td));
        out.selected = [...d.querySelectorAll('td.rc-imageselect-tile')].map(td => td.classList.contains('rc-imageselect-tileselected'));
        const whole = d.querySelector('img.rc-image-tile-33, img.rc-image-tile-44');
        out.picture = whole ? await data(whole.src) : null;
        out.single = {};
        const tds = [...d.querySelectorAll('td.rc-imageselect-tile')];
        for (let i = 0; i < tds.length; i++) { const img = tds[i].querySelector('img.rc-image-tile-11');
            if (img && img.complete && !tds[i].classList.contains('rc-imageselect-dynamic-selected')) out.single[i] = [img.src, await data(img.src)]; }
        out.verify = at(b[0], d.querySelector('#recaptcha-verify-button'));
        const reload = d.querySelector('#recaptcha-reload-button'); if (reload) out.reload = at(b[0], reload);
        out.wrong = [...d.querySelectorAll('.rc-imageselect-incorrect-response, .rc-imageselect-error-select-more, .rc-imageselect-error-dynamic-more')]
            .some(e => e.offsetParent !== null && getComputedStyle(e).display !== 'none');
        return out;
    })()"""

    async def recaptcha_state(self, tab) -> dict:
        state = await tab.evaluate(self.RECAPTCHA_STATE, await_promise=True)
        return state if isinstance(state, dict) else {}

    async def solve_recaptcha(self, tab) -> bool:
        """Answers a reCAPTCHA on a page of Google's own (its "unusual traffic" page): ticks the box,
        and where a picture grid follows, clicks the squares recaptcha_tiles.py reads as showing the
        asked object, new squares included until none shows it, then confirms. True when the box
        ended ticked or the page went on past it."""
        import base64
        import random
        import recaptcha_tiles
        from zendriver import cdp
        if not recaptcha_tiles.available():
            print("recaptcha: the CLIP model is not downloaded yet", file=sys.stderr, flush=True)
            return False
        await tab.send(cdp.emulation.set_focus_emulation_enabled(True))
        state = await self.recaptcha_state(tab)
        if "box" not in state:
            return False
        if not state.get("checked"):
            await self.click_at(tab, *state["box"])
            await asyncio.sleep(random.uniform(3, 4.5))
        for _ in range(RECAPTCHA_ROUNDS):
            state = await self.recaptcha_state(tab)
            if state.get("checked") or "box" not in state:
                return True
            if "grid" not in state:
                await asyncio.sleep(2)
                continue
            thing = recaptcha_tiles.obj(state["word"])
            n = {"33": 3, "44": 4}.get(state["grid"])
            if thing is None or n is None or not state.get("picture"):
                print(f"recaptcha: new puzzle for '{state.get('word')}' in a {state.get('grid')} grid", file=sys.stderr, flush=True)
                await self.click_at(tab, *state["reload"])
                await asyncio.sleep(random.uniform(2.5, 4))
                continue
            picture = recaptcha_tiles.decode(base64.b64decode(state["picture"].split(",", 1)[1]))
            if picture is None:
                await self.click_at(tab, *state["reload"])
                await asyncio.sleep(random.uniform(2.5, 4))
                continue
            chosen = await asyncio.get_running_loop().run_in_executor(None, recaptcha_tiles.choose, picture, n, thing)
            for i in chosen:
                await self.click_at(tab, *state["tiles"][i])
                await asyncio.sleep(random.uniform(0.3, 0.8))
            # A grid that puts a new square in place of each clicked one until none shows the
            # object: each new square is read once its picture differs from the one clicked.
            if n == 3 and chosen:
                was = {i: (state.get("single") or {}).get(str(i), [None])[0] for i in chosen}
                for _ in range(12):
                    if not was:
                        break
                    await asyncio.sleep(random.uniform(1.5, 2.5))
                    s = await self.recaptcha_state(tab)
                    for key, (src, data) in (s.get("single") or {}).items():
                        i = int(key)
                        if i not in was or src == was[i]:
                            continue
                        del was[i]
                        square = recaptcha_tiles.decode(base64.b64decode(data.split(",", 1)[1]))
                        if square is not None and await asyncio.get_running_loop().run_in_executor(None, recaptcha_tiles.shows, square, thing):
                            await self.click_at(tab, *s["tiles"][i])
                            was[i] = src
                            await asyncio.sleep(random.uniform(0.3, 0.8))
            await asyncio.sleep(random.uniform(0.6, 1.2))
            await self.click_at(tab, *state["verify"])
            await asyncio.sleep(random.uniform(3, 4.5))
            if (await self.recaptcha_state(tab)).get("wrong"):
                keep_rejected(state, thing, n, chosen)
        state = await self.recaptcha_state(tab)
        return bool(state.get("checked")) or "box" not in state

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
            if page == "challenge" and solve == "recaptcha" and solved < 1 and "recaptcha" in html and timeout - waited > RECAPTCHA_S:
                solved += 1
                try:
                    await asyncio.wait_for(self.solve_recaptcha(tab), RECAPTCHA_S)
                except Exception as e:
                    print(f"recaptcha: {e}", file=sys.stderr, flush=True)
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
            # A page that hung costs its own tab, not the browser: restarting it for one slow
            # Mojeek page ended the reads of the other tabs, Google's among them. The browser
            # counts as stuck only where it cannot open a tab in its place.
            hung = tab
            try:
                tab = await asyncio.wait_for(self.fresh_tab(), 15)
            except Exception:
                return {"ok": False, "error": "page did not load in time", "stuck": True}
            try:
                await asyncio.wait_for(hung.close(), 5)
            except Exception:
                pass
            return {"ok": False, "error": "page did not load in time"}
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
