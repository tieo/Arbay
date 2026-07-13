#!/usr/bin/env python3
"""
Stealth browser fetcher for Akamai-protected pages (mobile.de).

Uses zendriver (undetected CDP) driving real Google Chrome, headed under a virtual
display (Xvfb). This combination passes Akamai Bot Manager where TLS impersonation,
Playwright/patchright, and ungoogled-chromium all fail: real Chrome supplies a clean
fingerprint, zendriver hides the Runtime.enable automation leak, and a headed window
(even under Xvfb) avoids the headless-environment signal. Verified live returning
mobile.de search results.

Usage:
  mobilede_fetch.py <url> [max_pages] [wait_seconds]
      Solve the Akamai challenge once, then page through `pageNumber=2..max_pages` in the
      same warmed session (much cheaper than one solve per page), pausing between pages to
      stay polite. Pages are printed to stdout separated by a sentinel line.

Requires: zendriver (pip), google-chrome-stable (apt), a display (run under xvfb-run).
Exit codes: 2 = still blocked on page 1, 1 = launch/other error.
"""
import sys
import os
import asyncio

import zendriver as zd

PAGE_BREAK = "\n<!--ARBAY_PAGE_BREAK-->\n"
CHROME = os.environ.get("MOBILEDE_CHROME", "/usr/bin/google-chrome-stable")


def blocked(html: str) -> bool:
    t = html.lower()
    return "sec-if-cpt" in t or "zugriff verweigert" in t or "access denied" in t or len(t) < 20000


def with_page(url: str, page: int) -> str:
    if page <= 1:
        return url
    sep = "&" if "?" in url else "?"
    return f"{url}{sep}pageNumber={page}"


async def load(page, url: str, wait_s: float) -> str | None:
    p = await page.get(url)
    html = ""
    for _ in range(int(wait_s // 3) + 1):
        await asyncio.sleep(3)
        html = await p.get_content()
        if not blocked(html):
            return html
    return None


async def run(url: str, max_pages: int, wait_s: float) -> tuple[bool, list[str]]:
    kwargs = {"headless": False}
    if os.path.exists(CHROME):
        kwargs["browser_executable_path"] = CHROME
    browser = await zd.start(**kwargs)
    pages: list[str] = []
    try:
        first = await load(browser, url, wait_s)
        if first is None:
            return True, []
        pages.append(first)
        for page in range(2, max_pages + 1):
            await asyncio.sleep(2.0)  # rate limit between pages
            html = await load(browser, with_page(url, page), 12.0)
            if html is None or "/fahrzeuge/details" not in html:
                break
            pages.append(html)
        return False, pages
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
        is_blocked, pages = asyncio.run(run(url, max_pages, wait_s))
    except Exception as e:
        sys.stderr.write(f"error: {e}\n")
        sys.exit(1)
    if is_blocked:
        sys.stderr.write("blocked: challenge did not resolve\n")
        sys.exit(2)
    sys.stdout.write(PAGE_BREAK.join(pages))


if __name__ == "__main__":
    main()
