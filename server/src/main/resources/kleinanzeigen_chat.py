#!/usr/bin/env python3
"""
The user's own Kleinanzeigen account, driven through a real Chrome that keeps its profile on disk.

Kleinanzeigen has no API for buyers. Its website's message box talks to gateway.kleinanzeigen.de with
a bearer token that the site hands out to a signed-in browser, so every call here runs as fetch()
inside a page of that browser: the cookies, the fingerprint and the IP are the ones the user signed
in with, and the traffic looks like the site's own.

Signing in is done by the user, by hand, in this same browser over noVNC (captcha_gate.start_viewer):
no password ever reaches Arbay, and the session lives in the profile directory across restarts.

Protocol: one JSON object per line on stdin, one JSON answer per line on stdout, in order.
  {"op": "status"}                                  -> {"ok": true, "signedIn": bool, "userId": .., "name": ..}
  {"op": "signin"}                                  -> opens the login page and the viewer
  {"op": "signin_done"}                             -> closes the viewer
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

import captcha_gate

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

    async def start(self) -> None:
        os.makedirs(PROFILE, exist_ok=True)
        kwargs = {"headless": False, "user_data_dir": PROFILE}
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
        raw = await self.tab.evaluate(script, await_promise=True)
        return json.loads(raw)

    async def session(self) -> tuple[str, int]:
        if self.token and self.user_id:
            return self.token, self.user_id
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
        await self.tab.get(LOGIN_URL)
        captcha_gate.start_viewer(os.environ.get("DISPLAY", ":97"))
        return {"ok": True}

    async def signin_done(self) -> dict:
        captcha_gate.stop_viewer()
        self.token = None
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
