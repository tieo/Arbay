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

Requires: zendriver, google-chrome-stable, a display in DISPLAY (Xvfb, started by the server).
"""
import asyncio
import json
import os
import sys

import zendriver as zd

CHROME = os.environ.get("STEALTH_CHROME", "/usr/bin/google-chrome-stable")
PROFILE = os.environ.get("ARBAY_CHAT_PROFILE", os.path.expanduser("~/.arbay/chat-profile"))
ROOT = "https://www.kleinanzeigen.de"
GATEWAY = "https://gateway.kleinanzeigen.de"
LOGIN_URL = ROOT + "/m-einloggen.html"


class SignedOut(Exception):
    pass


class Chat:
    def __init__(self) -> None:
        self.browser = None
        self.tab = None
        self.token = None
        self.user_id = None
        self.signing = False

    async def start(self) -> None:
        os.makedirs(PROFILE, exist_ok=True)
        # The window fills the display the user signs in on, and a restart (the server's, which
        # ends Chrome abruptly) leaves no restore prompt over the page.
        kwargs = {"headless": False, "user_data_dir": PROFILE,
                  "browser_args": ["--window-position=0,0", "--window-size=1280,900", "--hide-crash-restore-bubble"]}
        if os.path.exists(CHROME):
            kwargs["browser_executable_path"] = CHROME
        self.browser = await zd.start(**kwargs)
        self.tab = await self.browser.get(ROOT + "/m-nachrichten.html")
        await asyncio.sleep(3)

    async def fetch(self, url: str, method: str = "GET", headers: dict | None = None, body: str | None = None) -> dict:
        """fetch() inside the signed-in page; answers status, headers and text."""
        script = """
            (async () => {
                const r = await fetch(%s, {method: %s, headers: %s, body: %s, credentials: 'include'});
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
        here = await self.tab.evaluate("location.host")
        if here == "www.kleinanzeigen.de":
            return
        if self.signing:
            raise SignedOut("signing in")
        await self.tab.get(ROOT + "/m-nachrichten.html")
        await asyncio.sleep(3)
        if await self.tab.evaluate("location.host") != "www.kleinanzeigen.de":
            raise SignedOut("the site sends the browser to its login page")

    async def session(self) -> tuple[str, int]:
        if self.token and self.user_id:
            return self.token, self.user_id
        await self.on_site()
        r = await self.fetch(ROOT + "/m-access-token.json")
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
        self.token = None
        self.signing = True
        await self.tab.get(LOGIN_URL)
        await asyncio.sleep(3)
        return await self.signin_state()

    async def signin_state(self) -> dict:
        """What the login page asks for now, read from its visible fields."""
        found = await self.tab.evaluate("""
            (() => {
                const shown = e => e && e.offsetParent !== null && !e.disabled;
                const pick = sel => [...document.querySelectorAll(sel)].find(shown);
                const err = [...document.querySelectorAll('[role=alert], .ulp-input-error-message, .error, [id*=error]')]
                    .filter(shown).map(e => e.innerText.trim()).filter(Boolean).join(' ');
                let step = 'other';
                if (pick('input[type=password]')) step = 'password';
                else if (pick('input[autocomplete=one-time-code], input[name=code], input[inputmode=numeric]')) step = 'code';
                else if (pick('input[type=email], input[name=username], input[name=email]')) step = 'email';
                return JSON.stringify({step, error: err, host: location.host});
            })()
        """)
        info = json.loads(found)
        if info["host"] == "www.kleinanzeigen.de":
            self.signing = False
            self.token = None
            status = await self.status()
            if status.get("signedIn"):
                return {"ok": True, "step": "done"}
        picture = await self.tab.screenshot_b64(format="jpeg")
        size = json.loads(await self.tab.evaluate("JSON.stringify([innerWidth, innerHeight])"))
        return {"ok": True, "step": info["step"], "error": info["error"] or None, "picture": picture, "width": size[0], "height": size[1]}

    async def signin_fill(self, value: str) -> dict:
        state = await self.signin_state()
        selector = {
            "email": "input[type=email], input[name=username], input[name=email]",
            "password": "input[type=password]",
            "code": "input[autocomplete=one-time-code], input[name=code], input[inputmode=numeric]",
        }.get(state["step"])
        if not selector:
            return state
        fields = await self.tab.select_all(selector)
        field = None
        for f in fields:
            if await f.apply("(e) => e.offsetParent !== null"):
                field = f
                break
        field = field or fields[0]
        await field.click()
        await field.clear_input()
        await field.send_keys(value)
        # The page's own submit, as pressing Enter in its form would.
        await field.apply("(e) => { const b = e.form && e.form.querySelector('button[type=submit], button[name=action]'); if (b) b.click(); else if (e.form) e.form.requestSubmit(); }")
        await asyncio.sleep(4)
        return await self.signin_state()

    async def signin_click(self, x: float, y: float) -> dict:
        await self.tab.mouse_click(x, y)
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
        field = await self.tab.select("textarea[name='message'], #viewad-contact-form textarea, form textarea", timeout=15)
        await field.click()
        await field.clear_input()
        await field.send_keys(text)
        await asyncio.sleep(1)
        button = await self.tab.select("#viewad-contact-form button[type='submit'], form button[type='submit']", timeout=10)
        await button.click()
        await asyncio.sleep(4)
        sent = json.loads(await self.tab.evaluate("JSON.stringify(window.__arbaySent || [])"))
        cid = None
        for _ in range(5):
            cid = await self.find_conversation(uid, ad_id)
            if cid:
                break
            await asyncio.sleep(2)
        await self.tab.get(ROOT + "/m-nachrichten.html")
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
