#!/usr/bin/env python3
"""
The user's own eBay account, driven through a real Chrome that keeps its profile on disk (see
market_browser.py for how it is driven and how the user signs in). One account answers for every eBay
site Arbay reads: an item is reached by its number on ebay.de whichever site listed it.

This browser is the user's: the crawler reads eBay through a Chrome of its own (warm_browser.py), so
the pace of searching never lands on the account. Only what eBay shows nobody signed out, its sold
listings, is read here.

Protocol, beyond market_browser.py's:
  {"op": "page", "url": "...", "markers": [...], "wait": 25} -> {"ok": true, "html": "...", "url": "...", "page": "content"|"errorpage"|"unknown"}
"""
import asyncio

from market_browser import MarketBrowser, SignedOut, serve


class Ebay(MarketBrowser):
    NAME = "eBay"
    HOME = "https://www.ebay.de/"
    SITE_HOSTS = ("www.ebay.de",)
    # The header's "Einloggen" link shows only while signed out.
    SIGNED_OUT = "a[href*='signin.ebay.de'][href*='sgfl=gh']"
    LOGIN_LINK = SIGNED_OUT
    CONSENT = ("#gdpr-banner-accept", "button[data-testid='gdpr-banner-accept']")
    PROFILE_ENV = "ARBAY_EBAY_CHAT_PROFILE"

    # eBay's own sign-in page. A browser it already knows gets "Willkommen zurück" and the password
    # alone there, which eBay asks again before a message to a seller even of a signed-in browser.
    SIGNIN_PAGE = "https://signin.ebay.de/ws/eBayISAPI.dll?SignIn&ru=https%3A%2F%2Fwww.ebay.de%2F"

    async def signin(self) -> dict:
        """Opens eBay's sign-in page whether or not the browser counts as signed in, so the password
        eBay asks again before messaging can be given; reached from the home page, as a person does."""
        self.signing = True
        if not await self.on_site_host():
            await self.go(self.HOME, 3)
        await self.calibrate()
        await self.accept_cookies()
        links = await self.visible(self.LOGIN_LINK)
        if links:
            await self.press(*links[0])
            await asyncio.sleep(4)
        else:
            await self.go(self.SIGNIN_PAGE, 4)
        return await self.signin_state()

    async def page(self, url: str, markers: list, timeout: float) -> dict:
        """[url] read as the signed-in user, for what eBay shows only to one: its sold listings send
        any other browser to the sign-in page. Answers like warm_browser.py's get."""
        await self.ensure_signed_in()
        await self.tab.get(url)
        html, kind = "", "unknown"
        waited = 0.0
        while waited < timeout:
            await asyncio.sleep(1)
            waited += 1
            html = await self.tab.get_content()
            if any(m in html for m in markers):
                kind = "content"
                break
            if "went wrong" in html and len(html) < 20000:
                kind = "errorpage"
                break
        if "signin.ebay" in await self.url():
            raise SignedOut("eBay asks to sign in again")
        return {"ok": True, "html": html, "url": await self.url(), "page": kind}

    async def handle(self, req: dict) -> dict:
        if req.get("op") == "page":
            return await self.page(req["url"], req.get("markers") or [], float(req.get("wait") or 25))
        return await super().handle(req)

    async def account_name(self) -> str | None:
        name = await self.tab.evaluate(
            "(document.querySelector('.gh-identity__greeting, #gh-ug') || {}).innerText || ''")
        name = (name or "").strip().removeprefix("Hallo").strip(" !")
        return name or None


if __name__ == "__main__":
    asyncio.run(serve(Ebay()))
