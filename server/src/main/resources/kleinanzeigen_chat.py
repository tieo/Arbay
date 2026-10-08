#!/usr/bin/env python3
"""
The user's own Kleinanzeigen account, driven through a real Chrome that keeps its profile on disk.

Kleinanzeigen has no API for buyers. Its website's message box talks to gateway.kleinanzeigen.de with
a bearer token that the site hands out to a signed-in browser, so every call here runs as fetch()
inside a page of that browser: the cookies, the fingerprint and the IP are the ones the user signed
in with, and the traffic looks like the site's own.

Signing in is done by the user from inside Arbay: the sidecar says what the login page asks (e-mail,
password, a code) with a picture of the page, types what the user enters, and passes clicks on the
picture through for anything else the page shows. What is typed goes to the page and nowhere else;
the session then lives in the profile directory across restarts.

The login host refuses browsers that do not act like a person ("IP-Bereich vorübergehend gesperrt",
shown to this browser while the user's own browser on the same address signed in). So on the login
page nothing runs inside the page: it is read through the DOM and screenshots only, and every click
and key press is real X input on the browser's display (xdotool), with the mouse travelling there
and keys pressed at a person's pace. The site's own pages are reached the way a person does, from the
home page through its "Einloggen" link, and a signed-out status check never opens the login page.

Protocol: one JSON object per line on stdin, one JSON answer per line on stdout, in order.
  {"op": "status"}                                  -> {"ok": true, "signedIn": bool, "userId": .., "name": ..}
  {"op": "signin"}                                  -> opens the login page; answers like signin_state
  {"op": "signin_state"}                            -> {"ok": true, "step": "email"|"password"|"code"|"other"|"done", "error": .., "picture": <jpeg base64>, "width": .., "height": ..}
  {"op": "signin_fill", "value": ".."}              -> types into the field the page asks for and submits; answers like signin_state
  {"op": "signin_click", "x": .., "y": ..}          -> clicks the page at a point of its picture; answers like signin_state
  {"op": "signin_done"}                             -> leaves the login page
  {"op": "conversations", "page": 0, "size": 30}    -> {"ok": true, "data": <gateway payload>}
  {"op": "conversation", "id": ".."}                -> {"ok": true, "data": <gateway payload>}
  {"op": "reply", "id": "..", "text": ".."}         -> {"ok": true}
  {"op": "read", "id": ".."}                        -> {"ok": true}
  {"op": "contact", "adId": "..", "text": ".."}     -> {"ok": true, "conversationId": ".."|null, "requests": [...]}
Failures answer {"ok": false, "error": "..", "signedOut": bool}.

Requires: zendriver, google-chrome-stable, xdotool, a display in DISPLAY (Xvfb, started by the server).
"""
import asyncio
import json
import math
import os
import random
import sys
from urllib.parse import urlparse

import zendriver as zd
from zendriver import cdp

CHROME = os.environ.get("STEALTH_CHROME", "/usr/bin/google-chrome-stable")
PROFILE = os.environ.get("ARBAY_CHAT_PROFILE", os.path.expanduser("~/.arbay/chat-profile"))
ROOT = "https://www.kleinanzeigen.de"
GATEWAY = "https://gateway.kleinanzeigen.de"
LOGIN_URL = ROOT + "/m-einloggen.html"
LOGIN_HOST = "login.kleinanzeigen.de"


class SignedOut(Exception):
    pass


class Hand:
    """The mouse and keyboard of the browser's X display, moved the way a person moves them."""

    def __init__(self) -> None:
        self.x, self.y = 640.0, 450.0

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
                await self.xdo("key", "Return")
            else:
                await self.xdo("type", "--delay", "0", "--", ch)
            await asyncio.sleep(random.uniform(low, high))

    async def key(self, *names: str) -> None:
        await self.xdo("key", *names)


class Chat:
    def __init__(self) -> None:
        self.browser = None
        self.tab = None
        self.token = None
        self.user_id = None
        self.signing = False
        self.hand = Hand()
        # Where the page's top left corner sits on the display, measured once on the site's own page.
        self.origin: tuple[float, float] | None = None

    async def start(self) -> None:
        os.makedirs(PROFILE, exist_ok=True)
        # The window fills the display the user signs in on, and a restart (the server's, which
        # ends Chrome abruptly) leaves no restore prompt over the page.
        kwargs = {"headless": False, "user_data_dir": PROFILE,
                  "browser_args": ["--window-position=0,0", "--window-size=1280,900", "--hide-crash-restore-bubble"]}
        if os.path.exists(CHROME):
            kwargs["browser_executable_path"] = CHROME
        self.browser = await zd.start(**kwargs)
        # The home page shows to everyone; the message box would send a signed-out browser to log in.
        self.tab = await self.browser.get(ROOT + "/")
        await asyncio.sleep(3)

    async def url(self) -> str:
        """Where the page is, from the browser's history rather than from inside the page."""
        # While a link takes the page to another site, the browser is briefly between two pages.
        for attempt in range(20):
            try:
                index, entries = await self.tab.send(cdp.page.get_navigation_history())
                return entries[index].url if entries else ""
            except Exception:
                if attempt == 19:
                    raise
                await asyncio.sleep(0.5)
        return ""

    async def host(self) -> str:
        return urlparse(await self.url()).hostname or ""

    async def viewport(self) -> tuple[int, int]:
        metrics = await self.tab.send(cdp.page.get_layout_metrics())
        visual = metrics[4] if len(metrics) > 4 and metrics[4] is not None else metrics[1]
        return int(visual.client_width), int(visual.client_height)

    async def calibrate(self) -> tuple[float, float]:
        """The display position of the page's corner, read off one real mouse move over the site's page."""
        if self.origin:
            return self.origin
        if await self.host() == LOGIN_HOST:
            await self.tab.get(ROOT + "/")
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
        for element in await self.tab.query_selector_all(selector):
            try:
                position = await element.get_position()
            except Exception:
                position = None
            if position and position.width > 0 and position.height > 0:
                shown.append((element, position))
        return shown

    async def press(self, element, position=None) -> None:
        """Click [element] with the display's mouse, somewhere inside it rather than its exact centre."""
        await self.tab.send(cdp.dom.scroll_into_view_if_needed(backend_node_id=element.backend_node_id))
        await asyncio.sleep(random.uniform(0.2, 0.5))
        position = await element.get_position() or position
        ox, oy = await self.calibrate()
        x = position.left + position.width * random.uniform(0.3, 0.7)
        y = position.top + position.height * random.uniform(0.35, 0.65)
        await self.hand.click(ox + x, oy + y)

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
            raw = await self.tab.evaluate(script, await_promise=True)
        except Exception as e:
            # A fetch that cannot leave the page says nothing about why; where the page is does.
            raise RuntimeError(f"fetch {url} failed on {await self.where()}: {str(e).splitlines()[0] if str(e) else type(e).__name__}")
        return json.loads(raw)

    async def where(self) -> str:
        try:
            return await self.tab.evaluate("location.href + ' (' + document.title + ')'")
        except Exception as e:
            return f"an unreadable page ({type(e).__name__})"

    async def on_site(self) -> None:
        """Bring the page back to the main site, whose fetches carry the session.

        Signed out, the site sends every page to its login host, where a fetch to the main site is
        cross-origin and fails; that is the signed-out state, not an error. While the user is signing
        in, the page is theirs and is left where it is."""
        if await self.host() == "www.kleinanzeigen.de":
            return
        if self.signing:
            raise SignedOut("signing in")
        await self.tab.get(ROOT + "/")
        await asyncio.sleep(3)
        if await self.host() != "www.kleinanzeigen.de":
            raise SignedOut("the site sends the browser to its login page")

    async def session(self) -> tuple[str, int]:
        if self.token and self.user_id:
            return self.token, self.user_id
        await self.on_site()
        r = await self.fetch(ROOT + "/m-access-token.json")
        if r.get("redirected"):
            raise SignedOut("the site sends the token request to its login page")
        token = r["headers"].get("authorization")
        if r["status"] != 200 or not token:
            raise SignedOut("no access token; the session is signed out")
        v = await self.fetch(ROOT + "/messagebox-api/view")
        try:
            user_id = int(json.loads(v["text"])["user"]["id"])
        except Exception as e:
            raise SignedOut(f"no user in the message box view: {e}")
        self.token, self.user_id = token, user_id
        return token, user_id

    async def gateway(self, path: str, method: str = "GET", payload: dict | None = None, retry: bool = True):
        token, _ = await self.session()
        headers = {"Authorization": token, "Accept": "application/json"}
        if payload is not None:
            headers["Content-Type"] = "application/json"
        r = await self.fetch(GATEWAY + path, method, headers, json.dumps(payload) if payload is not None else None)
        if r["status"] in (401, 403) and retry:
            # The bearer token lives minutes; a fresh one comes from the same cookies.
            self.token = None
            return await self.gateway(path, method, payload, retry=False)
        if r["status"] in (401, 403):
            raise SignedOut(f"gateway answered {r['status']}")
        if r["status"] >= 400:
            raise RuntimeError(f"gateway {method} {path} answered {r['status']}: {r['text'][:200]}")
        return json.loads(r["text"]) if r["text"].strip() else None

    async def status(self) -> dict:
        try:
            _, user_id = await self.session()
        except SignedOut:
            return {"ok": True, "signedIn": False}
        except Exception as e:
            sys.stderr.write(f"status: {e}\n")
            return {"ok": True, "signedIn": False, "problem": str(e)}
        name = None
        try:
            v = await self.fetch(ROOT + "/messagebox-api/view")
            user = json.loads(v["text"]).get("user") or {}
            name = user.get("name") or user.get("displayName")
        except Exception:
            pass
        return {"ok": True, "signedIn": True, "userId": user_id, "name": name}

    async def signin(self) -> dict:
        """Open the login page the way a person does: the home page, then its "Einloggen" link."""
        self.token = None
        self.signing = True
        await self.calibrate()
        if await self.host() != "www.kleinanzeigen.de":
            await self.tab.get(ROOT + "/")
            await asyncio.sleep(random.uniform(2.5, 4))
        await self.accept_cookies()
        await self.hand.move(random.uniform(300, 900), random.uniform(250, 600))
        await asyncio.sleep(random.uniform(0.6, 1.5))
        links = await self.visible("a[href*='m-einloggen']")
        if links:
            await self.press(*links[0])
        else:
            await self.tab.get(LOGIN_URL)
        for _ in range(20):
            await asyncio.sleep(0.5)
            if await self.host() != "www.kleinanzeigen.de":
                break
        await asyncio.sleep(random.uniform(1.5, 2.5))
        return await self.signin_state()

    async def accept_cookies(self) -> None:
        """The consent banner covers the page until it is answered, for a person as for Arbay."""
        for selector in ("#gdpr-banner-accept", "button[data-testid='gdpr-banner-accept']"):
            buttons = await self.visible(selector)
            if buttons:
                await self.press(*buttons[0])
                await asyncio.sleep(random.uniform(0.8, 1.5))
                return

    STEP_FIELDS = {
        "password": "input[type=password]",
        "code": "input[autocomplete=one-time-code], input[name=code], input[inputmode=numeric]",
        "email": "input[type=email], input[name=username], input[name=email]",
    }

    async def signin_state(self) -> dict:
        """What the login page asks for now, read from its visible fields through the DOM."""
        if await self.host() == "www.kleinanzeigen.de":
            self.signing = False
            self.token = None
            status = await self.status()
            if status.get("signedIn"):
                return {"ok": True, "step": "done"}
            self.signing = True
        step = "other"
        for name, selector in self.STEP_FIELDS.items():
            if await self.visible(selector):
                step = name
                break
        errors = []
        for element, _ in await self.visible("[role=alert], .ulp-input-error-message, .error, [id*=error]"):
            text = (element.text_all or "").strip()
            if text:
                errors.append(text)
        if not errors and step == "other":
            # A page that refuses the browser outright says so in its heading.
            for element, _ in await self.visible("h1"):
                text = (element.text_all or "").strip()
                if "gesperrt" in text.lower():
                    errors.append(text)
        # The page's own buttons, so the app can offer them as buttons rather than a picture to click.
        actions = []
        for element, position in await self.visible("main button, form button, main [role=button], input[type=submit]"):
            label = (element.text_all or element.attrs.get("value") or "").strip()
            if label and len(label) <= 40 and all(a["label"] != label for a in actions):
                actions.append({"label": label, "x": position.left + position.width / 2, "y": position.top + position.height / 2})
        shot = await self.tab.send(cdp.page.capture_screenshot(format_="jpeg", quality=80))
        width, height = await self.viewport()
        return {"ok": True, "step": step, "error": " ".join(errors) or None, "picture": shot, "width": width, "height": height,
                "actions": actions[:6]}

    async def signin_fill(self, value: str) -> dict:
        state = await self.signin_state()
        selector = self.STEP_FIELDS.get(state["step"])
        if not selector:
            return state
        fields = await self.visible(selector)
        if not fields:
            return state
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
            if (await self.signin_state_quick()) != state["step"]:
                break
        await asyncio.sleep(1.5)
        return await self.signin_state()

    async def signin_state_quick(self) -> str:
        if await self.host() == "www.kleinanzeigen.de":
            return "done"
        for name, selector in self.STEP_FIELDS.items():
            if await self.visible(selector):
                return name
        return "other"

    async def signin_click(self, x: float, y: float) -> dict:
        """A tap on the page's picture, made with the display's mouse at the same point."""
        ox, oy = await self.calibrate()
        await self.hand.click(ox + x, oy + y)
        await asyncio.sleep(3)
        return await self.signin_state()

    async def signin_done(self) -> dict:
        self.token = None
        self.signing = False
        await self.tab.get(ROOT + "/m-nachrichten.html")
        await asyncio.sleep(2)
        return await self.status()

    async def conversations(self, page: int, size: int) -> dict:
        _, uid = await self.session()
        return {"ok": True, "data": await self.gateway(f"/messagebox/api/users/{uid}/conversations?page={page}&size={size}")}

    async def conversation(self, cid: str) -> dict:
        _, uid = await self.session()
        return {"ok": True, "data": await self.gateway(f"/messagebox/api/users/{uid}/conversations/{cid}?contentWarnings=true")}

    async def reply(self, cid: str, text: str) -> dict:
        _, uid = await self.session()
        await self.gateway(f"/messagebox/api/users/{uid}/conversations/{cid}?warnPhoneNumber=true", "POST", {"message": text})
        return {"ok": True}

    async def read(self, cid: str) -> dict:
        _, uid = await self.session()
        await self.gateway(f"/messagebox/api/users/{uid}/conversations/read?ids={cid}", "POST")
        return {"ok": True}

    async def contact(self, ad_id: str, text: str) -> dict:
        """The first message about an ad, written into the ad page's own contact form.

        The request the form sends is recorded and returned, so the call can be read off the
        live site rather than guessed."""
        _, uid = await self.session()
        existing = await self.find_conversation(uid, ad_id)
        if existing:
            await self.reply(existing, text)
            return {"ok": True, "conversationId": existing, "requests": []}
        await self.tab.get(f"{ROOT}/s-anzeige/{ad_id}")
        await asyncio.sleep(3)
        await self.tab.evaluate("""
            window.__arbaySent = [];
            const of = window.fetch;
            window.fetch = async (...a) => { window.__arbaySent.push({url: String(a[0]), method: (a[1]||{}).method||'GET', body: String((a[1]||{}).body||'').slice(0, 500)}); return of(...a); };
            const oo = XMLHttpRequest.prototype.open, os = XMLHttpRequest.prototype.send;
            XMLHttpRequest.prototype.open = function(m, u) { this.__a = {method: m, url: String(u)}; return oo.apply(this, arguments); };
            XMLHttpRequest.prototype.send = function(b) { if (this.__a) window.__arbaySent.push({...this.__a, body: String(b||'').slice(0, 500)}); return os.apply(this, arguments); };
            true
        """)
        await self.accept_cookies()
        await self.tab.select("textarea[name='message'], #viewad-contact-form textarea, form textarea", timeout=15)
        fields = await self.visible("textarea[name='message'], #viewad-contact-form textarea, form textarea")
        if not fields:
            raise RuntimeError("the ad page shows no message field")
        await self.press(*fields[0])
        await asyncio.sleep(random.uniform(0.4, 0.9))
        await self.hand.type(text, fast=True)
        await asyncio.sleep(random.uniform(0.8, 1.6))
        buttons = await self.visible("#viewad-contact-form button[type='submit'], form button[type='submit']")
        if not buttons:
            raise RuntimeError("the ad page shows no send button")
        await self.press(*buttons[0])
        await asyncio.sleep(4)
        sent = json.loads(await self.tab.evaluate("JSON.stringify(window.__arbaySent || [])"))
        cid = None
        for _ in range(5):
            cid = await self.find_conversation(uid, ad_id)
            if cid:
                break
            await asyncio.sleep(2)
        await self.tab.get(ROOT + "/")
        if not cid:
            raise RuntimeError("the contact form was sent but no conversation about the ad appeared")
        return {"ok": True, "conversationId": cid, "requests": sent}

    async def find_conversation(self, uid: int, ad_id: str) -> str | None:
        data = await self.gateway(f"/messagebox/api/users/{uid}/conversations?page=0&size=50")
        for c in (data or {}).get("conversations") or []:
            if str(c.get("adId")) == str(ad_id) and str(c.get("role", "")).upper() == "BUYER":
                return c.get("id")
        return None


async def handle(chat: Chat, req: dict) -> dict:
    op = req.get("op")
    if op == "status":
        return await chat.status()
    if op == "signin":
        return await chat.signin()
    if op == "signin_done":
        return await chat.signin_done()
    if op == "signin_state":
        return await chat.signin_state()
    if op == "signin_fill":
        return await chat.signin_fill(req["value"])
    if op == "signin_click":
        return await chat.signin_click(float(req["x"]), float(req["y"]))
    if op == "conversations":
        return await chat.conversations(int(req.get("page", 0)), int(req.get("size", 30)))
    if op == "conversation":
        return await chat.conversation(req["id"])
    if op == "reply":
        return await chat.reply(req["id"], req["text"])
    if op == "read":
        return await chat.read(req["id"])
    if op == "contact":
        return await chat.contact(str(req["adId"]), req["text"])
    return {"ok": False, "error": f"unknown op {op}"}


async def main() -> None:
    chat = Chat()
    await chat.start()
    loop = asyncio.get_running_loop()
    reader = asyncio.StreamReader()
    await loop.connect_read_pipe(lambda: asyncio.StreamReaderProtocol(reader), sys.stdin)
    print(json.dumps({"ok": True, "ready": True}), flush=True)
    while True:
        line = await reader.readline()
        if not line:
            break
        try:
            req = json.loads(line)
            answer = await handle(chat, req)
        except SignedOut as e:
            chat.token = None
            answer = {"ok": False, "signedOut": True, "error": str(e)}
        except Exception as e:
            answer = {"ok": False, "signedOut": False, "error": f"{type(e).__name__}: {e}"}
        print(json.dumps(answer), flush=True)
    await chat.browser.stop()


if __name__ == "__main__":
    asyncio.run(main())
