#!/usr/bin/env python3
"""
The user's own eBay account, driven through a real Chrome that keeps its profile on disk (see
market_browser.py for how it is driven and how the user signs in). One account answers for every eBay
site Arbay reads: an item is reached by its number on ebay.de whichever site listed it.

This browser is the user's and only the user's: the crawler reads eBay through a Chrome of its own
(ebay_browser.py), so the pace of a search never lands on the account.
"""
import asyncio

from market_browser import MarketBrowser, serve


class Ebay(MarketBrowser):
    NAME = "eBay"
    HOME = "https://www.ebay.de/"
    SITE_HOSTS = ("www.ebay.de",)
    # The header's "Einloggen" link shows only while signed out.
    SIGNED_OUT = "a[href*='signin.ebay.de'][href*='sgfl=gh']"
    LOGIN_LINK = SIGNED_OUT
    CONSENT = ("#gdpr-banner-accept", "button[data-testid='gdpr-banner-accept']")
    PROFILE_ENV = "ARBAY_EBAY_CHAT_PROFILE"

    async def account_name(self) -> str | None:
        name = await self.tab.evaluate(
            "(document.querySelector('.gh-identity__greeting, #gh-ug') || {}).innerText || ''")
        name = (name or "").strip().removeprefix("Hallo").strip(" !")
        return name or None


if __name__ == "__main__":
    asyncio.run(serve(Ebay()))
