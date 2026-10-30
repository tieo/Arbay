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
from urllib.parse import urlparse

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
    # The account pays with PayPal, whose own page takes the payment after "Zahlen". It knows this
    # browser only once the user signed in to it here: a session copied in from another browser
    # was gone after the browser's next start.
    PAYMENT = "PayPal"
    PAYMENT_SIGNIN = "https://www.paypal.com/signin"
    PAYMENT_HOME = "https://www.paypal.com/myaccount/summary"

    def __init__(self) -> None:
        super().__init__()
        self.payment_signing = False

    async def account_name(self) -> str | None:
        # The header's avatar carries the member name as its alt text.
        name = await self.tab.evaluate("(document.querySelector('[data-testid=user-menu-button] img[alt]') || {}).alt || ''")
        return (name or "").strip() or None

    async def signin_payment(self) -> dict:
        """Opens PayPal's sign-in page for the user to sign in on, as on the market's own."""
        # Where the page sits on the display is measured on Vinted's own page, before PayPal's,
        # whose login runs no script of Arbay's (see calibrate).
        await self.calibrate()
        self.signing = True
        self.payment_signing = True
        await self.go(self.PAYMENT_SIGNIN, 4)
        for button in await self.visible("#acceptAllButton"):
            await self.press(*button)
            await asyncio.sleep(1)
        return await self.signin_state()

    async def signin_state(self) -> dict:
        # PayPal shows the account's summary once it is signed in. Its pages before that (the
        # device check) carry the summary's address in their query as where to go next, so only
        # the path counts.
        if self.payment_signing and "paypal.com" in await self.host() and urlparse(await self.url()).path.startswith("/myaccount"):
            self.payment_signing = False
            self.signing = False
            await self.go(self.HOME, 2)
            return {"ok": True, "step": "done"}
        return await super().signin_state()

    async def signin_done(self) -> dict:
        self.payment_signing = False
        return await super().signin_done()

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
        await asyncio.sleep(4)
        # Vinted stops a payment whose order changed while the page was open (shipping picked just
        # before it) with "Überprüfe deine Daten"; once closed, the order is read and paid again.
        stopped = await self.visible("[data-testid=checksum-mismatch-error-modal-action-button]")
        if stopped:
            await self.press(*stopped[0])
            await asyncio.sleep(3)
            lines = await self.summary()
            answer = {**answer, **lines}
            if lines["total"] is None or lines["total"] > most:
                await self.go(self.HOME, 2)
                return {**answer, "problem": f"the total passes the limit of {most:.2f} €"}
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
                return {"paid": False, "problem": "PayPal asks to sign in", "paymentSignedOut": True, **await self.page_detail()}
            if "/authflow" in await self.url():
                return {"paid": False, "problem": "PayPal asks to confirm it is you", **await self.page_detail()}
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

    async def relogin_payment(self, wait_confirm: float) -> dict:
        """Signs in to PayPal again on its own, with the password this browser's Chrome keeps for
        it, as a person lets the browser fill in a saved password: the field is clicked, Chrome
        fills it, Enter sends it. PayPal may then ask its account holder to confirm in the PayPal
        app; that is waited for up to [wait_confirm] seconds. Answers whether PayPal is signed in
        now, and if not, why."""
        await self.calibrate()
        self.signing = True
        try:
            # The account's summary answers whether PayPal still knows this browser; its sign-in
            # page asks the password again even of a signed-in browser, and then a confirmation
            # in the PayPal app on top.
            await self.go(self.PAYMENT_HOME, 5)
            if urlparse(await self.url()).path.startswith("/myaccount"):
                return {"ok": True, "signedIn": True}
            for button in await self.visible("#acceptAllButton"):
                await self.press(*button)
                await asyncio.sleep(1)
            # Some visits ask the e-mail on a page of its own first; Chrome fills that one as well.
            if not await self.visible("input[type=password]"):
                email = await self.visible("input[type=email], #email")
                if not email:
                    return {"ok": True, "signedIn": False, "problem": "PayPal shows no sign-in form"}
                await self.press(*email[0])
                await asyncio.sleep(1.5)
                await self.hand.key("Return")
                await asyncio.sleep(4)
            fields = await self.visible("input[type=password]")
            if not fields:
                return {"ok": True, "signedIn": False, "problem": "PayPal shows no password field"}
            await self.press(*fields[0])
            await asyncio.sleep(1.5)
            await self.hand.key("Return")
            for _ in range(20):
                await asyncio.sleep(1)
                if urlparse(await self.url()).path != "/signin":
                    break
            path = urlparse(await self.url()).path
            if path.startswith("/authflow"):
                # The PayPal app is the confirmation that needs nothing typed.
                for option in await self.visible("[data-testid*=push], label, li"):
                    if "PayPal-App" in (option[0].text_all or ""):
                        await self.press(*option)
                        break
                for button in await self.visible("#challenge-submit-button"):
                    await self.press(*button)
                waited = 0.0
                while waited < wait_confirm and urlparse(await self.url()).path.startswith("/authflow"):
                    await asyncio.sleep(3)
                    waited += 3
                path = urlparse(await self.url()).path
            if path.startswith("/myaccount"):
                return {"ok": True, "signedIn": True}
            if await self.visible("input[type=password]"):
                return {"ok": True, "signedIn": False, "problem": "PayPal did not take the saved password"}
            return {"ok": True, "signedIn": False, "problem": "PayPal was not confirmed in its app" if path.startswith("/authflow") else f"PayPal stopped on {path}"}
        finally:
            self.signing = False
            await self.go(self.HOME, 2)

    async def handle(self, req: dict) -> dict:
        if req.get("op") == "relogin_payment":
            return await self.relogin_payment(float(req.get("waitConfirm") or 150))
        if req.get("op") == "signin_payment":
            return await self.signin_payment()
        if req.get("op") == "buy":
            return await self.buy(req["url"], float(req["most"]), bool(req.get("pay")))
        return await super().handle(req)


def euro(text: str | None) -> float | None:
    """"1.193,39 €" as 1193.39."""
    digits = re.sub(r"[^0-9,]", "", text or "")
    return float(digits.replace(",", ".")) if digits else None


if __name__ == "__main__":
    asyncio.run(serve(Vinted()))
