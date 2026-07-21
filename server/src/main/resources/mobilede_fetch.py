#!/usr/bin/env python3
"""
Stealth browser fetcher for Akamai-protected pages (mobile.de).

Uses zendriver (undetected CDP) driving real Google Chrome, headed under a virtual
display (Xvfb). This combination passes Akamai Bot Manager where TLS impersonation,
Playwright/patchright, and ungoogled-chromium all fail: real Chrome supplies a clean
fingerprint, zendriver hides the Runtime.enable automation leak, and a headed window
(even under Xvfb) avoids the headless-environment signal.

Usage:
  mobilede_fetch.py <url> [max_pages] [wait_seconds]
      Solve the Akamai challenge once, then page through `pageNumber=2..max_pages` in the
      same warmed session (much cheaper than one solve per page), pausing between pages to
      stay polite. Each page's HTML is printed to stdout and flushed as soon as it loads,
      terminated by a sentinel line, so the caller can parse and surface pages live.

Requires: zendriver (pip), google-chrome-stable (apt), a display (run under xvfb-run).
Exit codes: 2 = the Akamai challenge never cleared on page 1 (a genuine block),
            1 = launch/navigation error (e.g. a CDP timeout — NOT a block).
"""
import sys
import os
import asyncio

import zendriver as zd

import captcha_gate

PAGE_BREAK = "\n<!--ARBAY_PAGE_BREAK-->\n"
CHROME = os.environ.get("MOBILEDE_CHROME", "/usr/bin/google-chrome-stable")

# Markers that prove the search result page actually rendered.
RESULT_MARKERS = ('data-testid="result-list', 'data-testid="listing-title', "/fahrzeuge/details")
# Markers of the Akamai interstitial / block page — the only thing that counts as "still blocked".
CHALLENGE_MARKERS = ("sec-if-cpt", "zugriff verweigert", "access denied", "captcha-delivery")


def has_results(html: str) -> bool:
    return any(m in html for m in RESULT_MARKERS)


def is_challenge(html: str) -> bool:
    t = html.lower()
    return any(m in t for m in CHALLENGE_MARKERS)


def with_page(url: str, page: int) -> str:
    if page <= 1:
        return url
    sep = "&" if "?" in url else "?"
    return f"{url}{sep}pageNumber={page}"


async def load(page, url: str, wait_s: float) -> str | None:
    """Navigate to `url` and poll until the results render or the wait elapses. Returns the page
    HTML once results are present, or a fully-rendered no-results page (a valid answer). Returns
    None only when the Akamai challenge never clears. A transient CDP error during a poll is ignored
    and retried; a hard navigation failure propagates to the caller (classified as an error, not a
    block)."""
    p = await page.get(url)
    html = ""
    for _ in range(int(wait_s // 1.5) + 1):
        await asyncio.sleep(1.5)
        try:
            html = await p.get_content()
        except Exception:
            continue
        if has_results(html):
            return html
        if is_challenge(html):
            continue
        # A settled page that is neither results nor a challenge is a real page (e.g. 0 hits).
        if len(html) > 40000:
            return html
    # Auto-wait exhausted. If still on the Akamai challenge, expose this live browser over noVNC so a
    # human can solve it in place — the token binds to this session's IP and fingerprint, so no other
    # browser can solve it for us. Continue once solved.
    if is_challenge(html) or not html:
        display = os.environ.get("DISPLAY", ":99")
        solved = await captcha_gate.await_human_solve(
            p, lambda h: has_results(h) or (not is_challenge(h) and len(h) > 40000), display)
        return (await p.get_content()) if solved else None
    return html if len(html) > 40000 else None


def emit(html: str) -> None:
    sys.stdout.write(html)
    sys.stdout.write(PAGE_BREAK)
    sys.stdout.flush()


async def run(url: str, max_pages: int, wait_s: float) -> bool:
    """Streams each parsed page to stdout as it loads. Returns True if page 1 was blocked."""
    kwargs = {"headless": False}
    if os.path.exists(CHROME):
        kwargs["browser_executable_path"] = CHROME
    browser = await zd.start(**kwargs)
    try:
        first = await load(browser, url, wait_s)
        if first is None:
            return True
        emit(first)
        for page in range(2, max_pages + 1):
            await asyncio.sleep(1.0)  # brief rate limit between pages
            html = await load(browser, with_page(url, page), 12.0)
            if html is None or "/fahrzeuge/details" not in html:
                break
            emit(html)
        return False
    finally:
        await browser.stop()


def main() -> None:
    if len(sys.argv) < 2:
        sys.stderr.write("usage: mobilede_fetch.py <url> [max_pages] [wait_seconds]\n")
        sys.exit(1)
    url = sys.argv[1]
    max_pages = int(sys.argv[2]) if len(sys.argv) > 2 else 1
    wait_s = float(sys.argv[3]) if len(sys.argv) > 3 else 30.0
    try:
        is_blocked = asyncio.run(run(url, max_pages, wait_s))
    except Exception as e:
        sys.stderr.write(f"error: {e}\n")
        sys.exit(1)
    if is_blocked:
        sys.stderr.write("blocked: challenge did not resolve\n")
        sys.exit(2)


if __name__ == "__main__":
    main()
