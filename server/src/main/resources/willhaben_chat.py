#!/usr/bin/env python3
"""
The user's own willhaben account, driven through a real Chrome that keeps its profile on disk (see
market_browser.py for how it is driven and how the user signs in).
"""
import asyncio

from market_browser import MarketBrowser, serve


class Willhaben(MarketBrowser):
    NAME = "willhaben"
    HOME = "https://www.willhaben.at/iad"
    SITE_HOSTS = ("www.willhaben.at",)
    # The header's "Einloggen" link shows only while signed out.
    SIGNED_OUT = "[data-testid=loginTopLink]"
    LOGIN_LINK = "a[data-testid=loginTopLink]"
    CONSENT = ("#didomi-notice-agree-button",)
    PROFILE_ENV = "ARBAY_WILLHABEN_CHAT_PROFILE"


if __name__ == "__main__":
    asyncio.run(serve(Willhaben()))
