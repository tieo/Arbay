#!/usr/bin/env python3
"""
The user's own Vinted account, driven through a real Chrome that keeps its profile on disk (see
market_browser.py for how it is driven and how the user signs in).

Vinted's sign-in page offers Google, Apple, Facebook and e-mail; the user picks theirs on the
picture of the page, and the e-mail form's fields are typed as on every other market.
"""
import asyncio
import json
import re

from market_browser import MarketBrowser, SignedOut, serve


class Vinted(MarketBrowser):
    NAME = "Vinted"
    HOME = "https://www.vinted.de/"
    SITE_HOSTS = ("www.vinted.de",)
    # The header's "Registrieren | Einloggen" link shows only while signed out.
    SIGNED_OUT = "[data-testid=header--login-button]"
    LOGIN_LINK = "a[data-testid=header--login-button]"
    CONSENT = ("#onetrust-accept-btn-handler",)
    PROFILE_ENV = "ARBAY_VINTED_CHAT_PROFILE"

    async def account_name(self) -> str | None:
        # The header's avatar carries the member name as its alt text.
        name = await self.tab.evaluate("(document.querySelector('[data-testid=user-menu-button] img[alt]') || {}).alt || ''")
        return (name or "").strip() or None

    async def summary(self) -> dict:
        """The checkout's price lines in euro, as its order summary shows them."""
        lines = {}
        for key, testid in (("item", "price-item-price"), ("fee", "price-buyer-protection-fee-price"), ("shipping", "price-shipping-price")):
            lines[key] = euro(await self.text_of(f"[data-testid=checkout-order-summary-v2-{testid}]"))
        lines["total"] = euro(await self.text_of("[data-testid=single-checkout-order-summary-total-to-pay-price]"))
        lines["payment"] = (await self.text_of("[data-testid=payment-details--title]") or "").strip() or None
        return lines

    async def text_of(self, selector: str) -> str | None:
        return await self.tab.evaluate(f"(document.querySelector({json.dumps(selector)}) || {{}}).innerText || null")

    async def buy(self, url: str, most: float, pay: bool) -> dict:
        """Opens [url]'s checkout the way a buyer does, with the item page's "Kaufen" button, delivered
        to the door (a pickup point is a choice made per parcel), and reads what it adds up to. With
        [pay], and only with a total of at most [most], it pays with the account's saved method;
        otherwise the checkout is left unpaid, which takes nothing off the market."""
        await self.ensure_signed_in()
        await self.go(url, 4)
        buttons = await self.visible("[data-testid=item-buy-button]")
        if not buttons:
            return {"ok": True, "buyable": False, "paid": False}
        await self.press(*buttons[0])
        for _ in range(30):
            await asyncio.sleep(1)
            if "/checkout" in await self.url():
                break
        else:
            raise RuntimeError(f"Vinted opened no checkout, but {await self.where()}")
        await asyncio.sleep(3)
        home = await self.visible("[data-testid=pickup-option-home]")
        if home:
            await self.press(*home[0])
            await asyncio.sleep(3)
        lines = await self.summary()
        answer = {"ok": True, "buyable": True, "paid": False, **lines}
        if lines["total"] is None:
            raise RuntimeError("the checkout shows no total")
        if not pay or lines["total"] > most:
            await self.go(self.HOME, 2)
            return {**answer, "problem": f"the total passes the limit of {most:.2f} €"} if pay else answer
        submit = await self.visible("[data-testid=single-checkout-order-summary-purchase-button]")
        if not submit:
            raise RuntimeError("the checkout shows no pay button")
        await self.press(*submit[0])
        return {**answer, **await self.approve_payment(lines["total"])}

    async def approve_payment(self, total: float) -> dict:
        """The payment method's own page after "Zahlen": PayPal asks its account holder to agree to
        the amount, signed in to PayPal in this browser. Paid once Vinted shows the order again."""
        for _ in range(30):
            await asyncio.sleep(1)
            if "paypal.com" in await self.host() or "after_payment_redirect" in await self.url():
                break
        await asyncio.sleep(4)
        if "paypal.com" in await self.host():
            if await self.visible("input[type=email], input[type=password], #email, #password"):
                return {"paid": False, "problem": "PayPal asks to sign in", **await self.page_detail()}
            if f"{total:.2f}".replace(".", ",") not in (await self.tab.evaluate("document.body.innerText") or "") \
                    and f"{total:.2f}" not in (await self.tab.evaluate("document.body.innerText") or ""):
                return {"paid": False, "problem": "PayPal shows another amount", **await self.page_detail()}
            agree = await self.visible("#payment-submit-btn, [data-testid=submit-button-initial], button[data-id=payment-submit-btn]")
            if not agree:
                return {"paid": False, "problem": "PayPal shows no pay button", **await self.page_detail()}
            await self.press(*agree[0])
            for _ in range(60):
                await asyncio.sleep(1)
                if await self.on_site_host():
                    break
            await asyncio.sleep(5)
        if not await self.on_site_host():
            return {"paid": False, "problem": "the payment did not come back to Vinted", **await self.page_detail()}
        # A checkout that took the payment no longer offers to pay.
        await asyncio.sleep(3)
        paid = not await self.visible("[data-testid=single-checkout-order-summary-purchase-button]")
        return {"paid": paid, "url": await self.url(), **({} if paid else {"problem": "Vinted shows the checkout unpaid", **await self.page_detail()})}

    async def handle(self, req: dict) -> dict:
        if req.get("op") == "buy":
            return await self.buy(req["url"], float(req["most"]), bool(req.get("pay")))
        return await super().handle(req)


def euro(text: str | None) -> float | None:
    """"1.193,39 €" as 1193.39."""
    digits = re.sub(r"[^0-9,]", "", text or "")
    return float(digits.replace(",", ".")) if digits else None


if __name__ == "__main__":
    asyncio.run(serve(Vinted()))
