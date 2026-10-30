#!/usr/bin/env python3
"""
The user's own Vinted account, driven through a real Chrome that keeps its profile on disk (see
market_browser.py for how it is driven and how the user signs in).

Vinted's sign-in page offers Google, Apple, Facebook and e-mail; the user picks theirs on the
picture of the page, and the e-mail form's fields are typed as on every other market.
"""
import asyncio

from market_browser import MarketBrowser, serve


class Vinted(MarketBrowser):
    NAME = "Vinted"
    HOME = "https://www.vinted.de/"
    SITE_HOSTS = ("www.vinted.de",)
    # The header's "Registrieren | Einloggen" link shows only while signed out.
    SIGNED_OUT = "[data-testid=header--login-button]"
    LOGIN_LINK = "a[data-testid=header--login-button]"
    CONSENT = ("#onetrust-accept-btn-handler",)
    PROFILE_ENV = "ARBAY_VINTED_CHAT_PROFILE"


if __name__ == "__main__":
    asyncio.run(serve(Vinted()))
