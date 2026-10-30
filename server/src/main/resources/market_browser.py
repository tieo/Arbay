#!/usr/bin/env python3
"""
What every market account sidecar shares: one real Chrome that keeps the user's session in its
profile, driven the way a person drives it, and the sign-in made by the user from inside Arbay.

A market's login pages refuse browsers that do not act like a person, so nothing runs inside a login
page: it is read through the DOM and screenshots only, and every click and key press is real X input
on the browser's display (xdotool), the mouse travelling there and keys pressed at a person's pace.
The user signs in on a picture of the page: the sidecar says what the page asks (e-mail, password, a
code), types what the user enters, and passes taps on the picture through for anything else.

A market's sidecar subclasses [MarketBrowser] with its home page, its hosts, how a signed-out page
shows, and its own operations, and runs [serve] with a handler for those operations.

Protocol: one JSON object per line on stdin, one JSON answer per line on stdout, in order; the first
line written is {"ok": true, "ready": true}. Failures answer {"ok": false, "error": "..",
"signedOut": bool}, and "stuck": true where only a new browser will answer.
  {"op": "status"}                                  -> {"ok": true, "signedIn": bool, "name": ..}
  {"op": "signin"}                                  -> opens the login page; answers like signin_state
  {"op": "signin_state"}                            -> {"ok": true, "step": "email"|"password"|"code"|"other"|"done", "error": .., "picture": <jpeg base64>, "width": .., "height": ..}
  {"op": "signin_fill", "value": ".."}              -> types into the field the page asks for and submits; answers like signin_state
  {"op": "signin_click", "x": .., "y": ..}          -> clicks the screen at a point of its picture; answers like signin_state
  {"op": "signin_keys", "text": "..", "key": ".."}  -> types text, or presses one named key (X keysym); answers like signin_state
  {"op": "signin_done"}                             -> leaves the login page; answers like status
  {"op": "set_cookies", "cookies": [{name, value, domain, path, secure, httpOnly, expires}]}
                                                    -> signed in with a session the user already has; answers like status

Requires: zendriver, google-chrome-stable, xdotool, a display in DISPLAY (Xvfb, started by the server).
"""
import asyncio
import base64
import io
import json
import math
import os
import random
import sys
import time
from urllib.parse import urlparse

import zendriver as zd
from zendriver import cdp

CHROME = os.environ.get("STEALTH_CHROME", "/usr/bin/google-chrome-stable")


def log(line: str) -> None:
    """One line to the server's log of this browser, with the time, for finding what is slow."""
    sys.stderr.write(f"{time.strftime('%H:%M:%S')} {line}\n")
    sys.stderr.flush()


async def within(what: str, call, seconds: float = 8):
    """[call] with a time limit of its own: a page call whose answer is lost while the page changes
    would otherwise wait for good, and every request after it with it."""
    try:
        return await asyncio.wait_for(call, timeout=seconds)
    except asyncio.TimeoutError:
        log(f"{what} gave no answer within {seconds:.0f}s")
        raise RuntimeError(f"the page did not answer ({what})")


class SignedOut(Exception):
    pass


class Stuck(Exception):
    """The browser no longer answers, and only a new one will."""


class Hand:
    """The mouse and keyboard of the browser's X display, moved the way a person moves them.

    Characters the X keyboard layout lacks (ä, ü, ß, €) xdotool types by remapping a spare key on
    the fly, and typed at speed some of them are lost. Those go in through the browser's own text
    input ([insert]), the way an input method puts in a character, still as trusted input."""

    def __init__(self, newline: str = "Return") -> None:
        self.x, self.y = 640.0, 450.0
        self.insert = None
        # The key a line break is typed with: a message form that sends on Return takes Shift+Return.
        self.newline = newline

    @staticmethod
    async def xdo(*args: str) -> None:
        proc = await asyncio.create_subprocess_exec("xdotool", *args, stdout=asyncio.subprocess.DEVNULL, stderr=asyncio.subprocess.PIPE)
        _, err = await proc.communicate()
        if proc.returncode != 0:
            raise RuntimeError(f"xdotool {args[0]} failed: {err.decode().strip()}")

    async def move(self, x: float, y: float) -> None:
        """Travel to (x, y) on a slightly bent path, quick in the middle and slow at both ends."""
        sx, sy = self.x, self.y
        distance = math.hypot(x - sx, y - sy)
        steps = max(8, min(45, int(distance / 18)))
        # One bend point off the straight line, so no two paths are the same.
        bx = (sx + x) / 2 + random.uniform(-0.25, 0.25) * distance
        by = (sy + y) / 2 + random.uniform(-0.25, 0.25) * distance
        chain: list[str] = []
        for i in range(1, steps + 1):
            t = i / steps
            t = t * t * (3 - 2 * t)
            px = (1 - t) ** 2 * sx + 2 * (1 - t) * t * bx + t ** 2 * x
            py = (1 - t) ** 2 * sy + 2 * (1 - t) * t * by + t ** 2 * y
            chain += ["mousemove", str(round(px)), str(round(py)), "sleep", f"{random.uniform(0.006, 0.02):.3f}"]
        await self.xdo(*chain)
        self.x, self.y = x, y

    async def click(self, x: float, y: float) -> None:
        await self.move(x, y)
        await asyncio.sleep(random.uniform(0.08, 0.3))
        await self.xdo("mousedown", "1", "sleep", f"{random.uniform(0.05, 0.13):.3f}", "mouseup", "1")

    async def type(self, text: str, fast: bool = False) -> None:
        """Key by key, each after a pause of its own."""
        low, high = (0.03, 0.09) if fast else (0.07, 0.22)
        for ch in text:
            # "type" takes every argument after it as text, so each key is a call of its own.
            if ch == "\n":
                await self.xdo("key", self.newline)
            elif ch.isascii() or self.insert is None:
                await self.xdo("type", "--delay", "0", "--", ch)
            else:
                await self.insert(ch)
            await asyncio.sleep(random.uniform(low, high))

    async def key(self, *names: str) -> None:
        await self.xdo("key", *names)


class MarketBrowser:
    """One market's signed-in browser. A subclass names its pages and hosts:

    [HOME] is the page every visit starts from, [SITE_HOSTS] the hosts whose pages carry the session,
    [SIGNED_OUT] a selector shown on a site page only while signed out, [LOGIN_LINK] the selector of
    the site's own sign-in link, and [CONSENT] the selectors of its cookie banner's accept button."""

    NAME = "the market"
    HOME = ""
    SITE_HOSTS: tuple = ()
    SIGNED_OUT = ""
    LOGIN_LINK = ""
    CONSENT: tuple = ()
    PROFILE_ENV = ""
    # The payment service the market's checkout hands over to, signed in to in this same browser.
    PAYMENT = None

    STEP_FIELDS = {
        "password": "input[type=password]",
        "code": "input[autocomplete=one-time-code], input[name=code], input[inputmode=numeric]",
        "email": "input[type=email], input[name=username], input[name=email], input[name=userid], input[autocomplete=username]",
    }

    # The keys the apps pass through by name, as X keysyms.
    KEYS = {"Return", "BackSpace", "Tab", "ISO_Left_Tab", "Escape", "Delete", "Left", "Right", "Up", "Down", "Home", "End", "space"}

    def __init__(self) -> None:
        self.browser = None
        self.tab = None
        self.signing = False
        self.hand = Hand()
        self.hand.insert = lambda ch: self.tab.send(cdp.input_.insert_text(text=ch))
        # Where the page's top left corner sits on the display, measured once on the site's own page.
        self.origin: tuple[float, float] | None = None
        self.profile = os.environ.get(self.PROFILE_ENV, os.path.expanduser(f"~/.arbay/{self.PROFILE_ENV.lower()}"))

    async def start(self) -> None:
        os.makedirs(self.profile, exist_ok=True)
        # The server starts this sidecar only once the one before it has ended, so a lock left in
        # the profile is always stale; Chrome would read it as a live owner and refuse to start.
        for name in ("SingletonLock", "SingletonSocket", "SingletonCookie"):
            try:
                os.unlink(os.path.join(self.profile, name))
            except FileNotFoundError:
                pass
        # The window fills the display the user signs in on, and a restart (the server's, which
        # ends Chrome abruptly) leaves no restore prompt over the page.
        kwargs = {"headless": False, "user_data_dir": self.profile,
                  "browser_args": ["--window-position=0,0", "--window-size=1280,900", "--hide-crash-restore-bubble",
                                   # No bar over the page warning of the automation's own flags.
                                   "--test-type",
                                   # Pages left behind stay alive in the back/forward cache, each in
                                   # a renderer of its own, and filled the container's memory. The
                                   # last --disable-features is the one Chrome reads, so zendriver's
                                   # own are repeated here.
                                   "--disable-features=IsolateOrigins,site-per-process,DisableLoadExtensionCommandLineSwitch,BackForwardCache",
                                   "--renderer-process-limit=6",
                                   # Ad frames each took a process of their own while only
                                   # site isolation's feature was off; this switch ends it.
                                   "--disable-site-isolation-trials"]}
        if os.path.exists(CHROME):
            kwargs["browser_executable_path"] = CHROME
        self.browser = await zd.start(**kwargs)
        self.tab = await self.browser.get(self.HOME)
        await asyncio.sleep(3)

    # --- the page ---

    async def url(self) -> str:
        """Where the page is, from the browser's history rather than from inside the page."""
        # While a link takes the page to another site, the browser is briefly between two pages.
        for attempt in range(20):
            try:
                index, entries = await within("navigation history", self.tab.send(cdp.page.get_navigation_history()))
                return entries[index].url if entries else ""
            except Exception:
                if attempt == 19:
                    raise
                await asyncio.sleep(0.5)
        return ""

    async def host(self) -> str:
        return urlparse(await self.url()).hostname or ""

    async def on_site_host(self) -> bool:
        return await self.host() in self.SITE_HOSTS

    async def viewport(self) -> tuple[int, int]:
        metrics = await within("layout metrics", self.tab.send(cdp.page.get_layout_metrics()))
        visual = metrics[4] if len(metrics) > 4 and metrics[4] is not None else metrics[1]
        return int(visual.client_width), int(visual.client_height)

    async def picture(self) -> dict:
        """The browser's whole screen as the user would see it: the page, the address bar that says
        which site it is, and the browser's own dialogs over it. A passkey prompt is the browser's,
        not the page's: photographed from the page alone it was never shown, and took every tap."""
        def grab() -> dict:
            from PIL import ImageGrab
            screen = ImageGrab.grab(xdisplay=os.environ.get("DISPLAY")).convert("RGB")
            out = io.BytesIO()
            screen.save(out, "JPEG", quality=80)
            return {"picture": base64.b64encode(out.getvalue()).decode(), "width": screen.width, "height": screen.height}
        return await asyncio.get_running_loop().run_in_executor(None, grab)

    async def calibrate(self) -> tuple[float, float]:
        """The display position of the page's corner, read off one real mouse move over the site's page."""
        if self.origin:
            return self.origin
        if not await self.on_site_host():
            # A sign-in page is the user's: leaving it to measure lost what they were typing into.
            if self.signing:
                raise RuntimeError("the page's place on the display is unknown on the sign-in page")
            await self.tab.get(self.HOME)
            await asyncio.sleep(3)
        await self.tab.evaluate("window.__arbayOrigin = null; addEventListener('mousemove', e => { window.__arbayOrigin = [e.screenX - e.clientX, e.screenY - e.clientY]; }, {once: true}); true")
        await self.hand.move(random.uniform(500, 800), random.uniform(350, 550))
        await asyncio.sleep(0.3)
        found = await self.tab.evaluate("JSON.stringify(window.__arbayOrigin)")
        origin = json.loads(found) if isinstance(found, str) else None
        if not origin:
            raise RuntimeError("could not tell where the page sits on the display")
        self.origin = (float(origin[0]), float(origin[1]))
        return self.origin

    async def visible(self, selector: str) -> list:
        """Elements matching [selector] that take up room on the page, read through the DOM."""
        shown = []
        for element in await within(f"query {selector[:30]}", self.tab.query_selector_all(selector)):
            try:
                position = await within("element position", element.get_position(), 4)
            except Exception:
                position = None
            if position and position.width > 0 and position.height > 0:
                shown.append((element, position))
        return shown

    async def press(self, element, position=None) -> None:
        """Click [element] with the display's mouse, somewhere inside it rather than its exact centre."""
        await within("scroll into view", self.tab.send(cdp.dom.scroll_into_view_if_needed(backend_node_id=element.backend_node_id)))
        await asyncio.sleep(random.uniform(0.2, 0.5))
        position = await element.get_position() or position
        ox, oy = await self.calibrate()
        x = position.left + position.width * random.uniform(0.3, 0.7)
        y = position.top + position.height * random.uniform(0.35, 0.65)
        await self.hand.click(ox + x, oy + y)

    async def go(self, url: str, settle: float = 3) -> None:
        """Opens [url] as a person does, by its address, and waits for it to settle. A page whose
        renderer was ended ("Aw, Snap!": the container's memory limit ends the biggest one first)
        answers nothing again, so a page that does not answer is replaced and [url] opened anew."""
        await self.tab.get(url)
        # The page's place on the display is measured again on the next site page, except during
        # a sign-in: its pages are not measured (see calibrate), and the window has not moved.
        if not self.signing:
            self.origin = None
        await asyncio.sleep(settle)
        try:
            await within("page alive", self.tab.evaluate("1"), 10)
        except Exception as e:
            log(f"{url} gave a page that does not answer ({e}); opening a new one")
            await self.replace_page()
            await self.tab.get(url)
            await asyncio.sleep(settle)

    async def fetch(self, url: str, method: str = "GET", headers: dict | None = None, body: str | None = None) -> dict:
        """fetch() inside the signed-in page; answers status, headers and text."""
        script = """
            (async () => {
                const r = await fetch(%s, {method: %s, headers: %s, body: %s, credentials: 'include', redirect: 'manual'});
                if (r.type === 'opaqueredirect') return JSON.stringify({status: 302, headers: {}, text: '', redirected: true});
                const h = {};
                r.headers.forEach((v, k) => { h[k] = v; });
                return JSON.stringify({status: r.status, headers: h, text: await r.text()});
            })()
        """ % (json.dumps(url), json.dumps(method), json.dumps(headers or {}), json.dumps(body))
        try:
            raw = await within("fetch", self.tab.evaluate(script, await_promise=True), 20)
        except Exception as e:
            if "ConnectionClosed" not in type(e).__name__ and "did not answer" not in str(e):
                raise RuntimeError(f"fetch {url} failed on {await self.where()}: {str(e).splitlines()[0] if str(e) else type(e).__name__}")
            # The page was closed, crashed or froze; every call after would fail the same way.
            log(f"page lost ({type(e).__name__}); opening a new one")
            try:
                await self.replace_page()
                raw = await within("fetch", self.tab.evaluate(script, await_promise=True), 20)
            except Exception as again:
                raise Stuck(f"a new page did not answer either: {type(again).__name__}: {again}")
        return json.loads(raw)

    async def replace_page(self) -> None:
        """Work in a new page and close the old one. The new one opens first: the browser quits with its last page."""
        old = self.tab
        self.tab = await self.browser.get(self.HOME, new_tab=True)
        self.origin = None
        try:
            await within("close page", self.browser.connection.send(cdp.target.close_target(target_id=old.target.target_id)), 5)
        except Exception as e:
            log(f"closing the old page failed: {type(e).__name__}")
        await asyncio.sleep(3)

    async def where(self) -> str:
        try:
            return await within("location", self.tab.evaluate("location.href + ' (' + document.title + ')'"), 5)
        except Exception as e:
            return f"an unreadable page ({type(e).__name__})"

    async def accept_cookies(self) -> None:
        """The consent banner covers the page until it is answered, for a person as for Arbay."""
        for selector in self.CONSENT:
            buttons = await self.visible(selector)
            if buttons:
                await self.press(*buttons[0])
                await asyncio.sleep(random.uniform(0.8, 1.5))
                return

    async def page_detail(self) -> dict:
        """The page an operation failed on, to keep and read afterwards."""
        detail = {"url": await self.url()}
        try:
            detail.update(await self.picture())
            detail["html"] = await within("page html", self.tab.get_content(), 10)
        except Exception as e:
            detail["error"] = f"{type(e).__name__}: {e}"
        return detail

    # --- the account ---

    async def signed_in_here(self) -> bool:
        """Whether the site page now open shows the signed-in header."""
        return not await self.tab.query_selector_all(self.SIGNED_OUT)

    async def account_name(self) -> str | None:
        """The name the signed-in header greets the user with; a market reads it in its own way."""
        return None

    async def ensure_signed_in(self) -> None:
        """Brings the page to the site and fails as signed out where the site shows it is."""
        if self.signing:
            raise SignedOut("signing in")
        if not await self.on_site_host():
            await self.go(self.HOME)
        if not await self.signed_in_here():
            raise SignedOut(f"not signed in to {self.NAME}")

    async def status(self) -> dict:
        # While the user signs in, the page is theirs: a status check that took it back to the home
        # page threw away the login page the user was on.
        if self.signing:
            return {"ok": True, "signedIn": False}
        try:
            if not await self.on_site_host():
                await self.go(self.HOME)
            if not await self.signed_in_here():
                return {"ok": True, "signedIn": False}
            return {"ok": True, "signedIn": True, "name": await self.account_name(), "payment": self.PAYMENT}
        except Exception as e:
            log(f"status: {e}")
            return {"ok": True, "signedIn": False, "problem": str(e)}

    # --- signing in ---

    async def signin(self) -> dict:
        """Open the login page the way a person does: the home page, then its sign-in link."""
        self.signing = True
        if not await self.on_site_host():
            await self.go(self.HOME, random.uniform(2.5, 4))
        await self.calibrate()
        await self.accept_cookies()
        await self.hand.move(random.uniform(300, 900), random.uniform(250, 600))
        await asyncio.sleep(random.uniform(0.6, 1.5))
        links = await self.visible(self.LOGIN_LINK)
        if not links:
            self.signing = False
            if await self.signed_in_here():
                return {"ok": True, "step": "done"}
            raise RuntimeError(f"{self.NAME} shows no sign-in link")
        await self.press(*links[0])
        await asyncio.sleep(random.uniform(3, 4.5))
        return await self.signin_state()

    async def signin_state(self) -> dict:
        """What the login page asks for now, read from its visible fields through the DOM."""
        if await self.on_site_host() and not await self.visible(", ".join(self.STEP_FIELDS.values())):
            if await self.signed_in_here():
                self.signing = False
                return {"ok": True, "step": "done"}
        step = await self.step_quick()
        errors = []
        for element, _ in await self.visible("[role=alert], [aria-live=assertive], .error, [id*=error], [class*=error]"):
            text = (element.text_all or "").strip()
            if text and len(text) < 300:
                errors.append(text)
        return {"ok": True, "step": step, "error": " ".join(dict.fromkeys(errors)) or None, **await self.picture()}

    async def step_quick(self) -> str:
        """The field being typed into; else the first the page shows still empty, in the order a
        login asks them: a page that asks e-mail and password together (willhaben) asks the e-mail
        first. With every shown field filled, the last one shown is what the page still waits on."""
        for name in ("email", "password", "code"):
            focused = await self.tab.evaluate(
                f"(() => {{ const e = document.activeElement; return !!e && e.matches({json.dumps(self.STEP_FIELDS[name])}); }})()")
            if focused:
                return name
        shown = []
        for name in ("email", "password", "code"):
            for element, _ in await self.visible(self.STEP_FIELDS[name]):
                shown.append(name)
                value = await self.tab.evaluate(
                    f"(document.querySelector({json.dumps(self.STEP_FIELDS[name])}) || {{}}).value || ''")
                if not value:
                    return name
                break
        return shown[-1] if shown else "other"

    async def signin_fill(self, value: str) -> dict:
        step = await self.step_quick()
        selector = self.STEP_FIELDS.get(step)
        fields = await self.visible(selector) if selector else []
        if not fields:
            return await self.signin_state()
        await self.press(*fields[0])
        await asyncio.sleep(random.uniform(0.3, 0.7))
        # Whatever the field held already goes, as a person clears it.
        await self.hand.key("ctrl+a")
        await asyncio.sleep(random.uniform(0.1, 0.25))
        await self.hand.key("BackSpace")
        await asyncio.sleep(random.uniform(0.2, 0.5))
        await self.hand.type(value)
        await asyncio.sleep(random.uniform(0.4, 1.0))
        await self.hand.key("Return")
        for _ in range(16):
            await asyncio.sleep(0.5)
            if await self.step_quick() != step:
                break
        await asyncio.sleep(1.5)
        return await self.signin_state()

    async def signin_click(self, x: float, y: float) -> dict:
        """A tap on the screen's picture, made with the display's mouse at the same point."""
        await self.hand.click(x, y)
        await asyncio.sleep(0.8)
        return await self.signin_state()

    async def signin_keys(self, text: str | None, key: str | None) -> dict:
        """What the user typed on the page's picture, typed into the page as they typed it. With
        nothing on the page taking keys, the field the page asks for is clicked first, as a person
        clicks into a form before typing."""
        if text:
            editing = await self.tab.evaluate(
                "(() => { const e = document.activeElement; return !!e && (e.isContentEditable || ['INPUT', 'TEXTAREA'].includes(e.tagName)); })()")
            if not editing:
                selector = self.STEP_FIELDS.get(await self.step_quick())
                fields = await self.visible(selector) if selector else []
                if fields:
                    await self.press(*fields[0])
                    await asyncio.sleep(random.uniform(0.2, 0.4))
            await self.hand.type(text, fast=True)
        if key:
            if key not in self.KEYS:
                raise RuntimeError(f"no key {key}")
            await self.hand.key(key)
        await asyncio.sleep(0.4 if key != "Return" else 1.2)
        return await self.signin_state()

    async def signin_done(self) -> dict:
        self.signing = False
        await self.go(self.HOME, 2)
        return await self.status()

    async def set_cookies(self, cookies: list) -> dict:
        """The site's cookies from a session the user already has, set in this browser so it is
        signed in too; nothing of the user's password passes."""
        params = []
        for c in cookies:
            param = cdp.network.CookieParam(name=c["name"], value=c["value"], domain=c["domain"], path=c.get("path") or "/",
                                            secure=bool(c.get("secure")), http_only=bool(c.get("httpOnly")))
            if c.get("expires") is not None:
                param.expires = cdp.network.TimeSinceEpoch(float(c["expires"]))
            params.append(param)
        await within("set cookies", self.tab.send(cdp.network.set_cookies(params)))
        self.signing = False
        await self.go(self.HOME)
        return await self.status()

    # --- the operations every market answers ---

    async def handle_common(self, op: str, req: dict) -> dict | None:
        if op == "status":
            return await self.status()
        if op == "signin":
            return await self.signin()
        if op == "signin_state":
            return await self.signin_state()
        if op == "signin_fill":
            return await self.signin_fill(req["value"])
        if op == "signin_click":
            return await self.signin_click(float(req["x"]), float(req["y"]))
        if op == "signin_keys":
            return await self.signin_keys(req.get("text"), req.get("key"))
        if op == "signin_done":
            return await self.signin_done()
        if op == "set_cookies":
            return await self.set_cookies(req.get("cookies") or [])
        return None

    async def handle(self, req: dict) -> dict:
        """A market's own operations; the common ones are answered before this is asked."""
        return {"ok": False, "error": f"unknown op {req.get('op')}"}


async def serve(market: MarketBrowser) -> None:
    await market.start()
    loop = asyncio.get_running_loop()
    reader = asyncio.StreamReader(limit=1 << 22)
    await loop.connect_read_pipe(lambda: asyncio.StreamReaderProtocol(reader), sys.stdin)
    print(json.dumps({"ok": True, "ready": True}), flush=True)
    while True:
        line = await reader.readline()
        if not line:
            break
        req: dict = {}
        started = time.monotonic()
        try:
            req = json.loads(line)
            op = req.get("op")

            async def answer_for() -> dict:
                return await market.handle_common(op, req) or await market.handle(req)

            # A page call that never returns would hold every request after it.
            answer = await asyncio.wait_for(answer_for(), timeout=float(req.get("timeout") or 50))
        except asyncio.TimeoutError:
            answer = {"ok": False, "signedOut": False, "stuck": True, "error": f"{req.get('op')} took too long"}
        except Stuck as e:
            answer = {"ok": False, "signedOut": False, "stuck": True, "error": str(e)}
        except SignedOut as e:
            answer = {"ok": False, "signedOut": True, "error": str(e)}
        except Exception as e:
            answer = {"ok": False, "signedOut": False, "error": f"{type(e).__name__}: {e}"}
        log(f"{req.get('op')} {time.monotonic() - started:.1f}s ok={answer.get('ok')}")
        print(json.dumps(answer), flush=True)
    await market.browser.stop()
