#!/usr/bin/env python3
"""
Generic stealth fetcher: real Google Chrome via zendriver (undetected CDP) under Xvfb, for pages
behind Cloudflare/Akamai/etc. that curl_cffi and Playwright Chromium cannot pass but a genuine
headed browser can. Unlike mobilede_fetch.py (mobile.de pagination + block heuristics), this waits
for a caller-supplied marker to appear in the rendered DOM, so it returns the page only once the
client-rendered content the crawler needs is actually present, not the empty post-challenge shell.

Usage:
  stealth_fetch.py <url> <wait_marker> [wait_seconds] [min_matches]
      Load <url>, poll until <wait_marker> (a literal substring, e.g. a data-qa attribute) occurs at
      least <min_matches> times in the page HTML or <wait_seconds> elapses, then print the HTML to
      stdout. min_matches guards against a lone preload/script reference to the marker satisfying the
      wait before the client-rendered grid actually paints (a single card selector string in a JS
      bundle is not the grid). Default min_matches is 1.

Requires: zendriver (pip), google-chrome-stable (apt), a display (run under xvfb-run).
Exit codes: 2 = marker never appeared (blocked or layout changed), 1 = launch/other error.
"""
import sys
import os
import asyncio

import zendriver as zd

CHROME = os.environ.get("STEALTH_CHROME", "/usr/bin/google-chrome-stable")


async def run(url: str, marker: str, wait_s: float, min_matches: int) -> str | None:
    kwargs = {"headless": False}
    if os.path.exists(CHROME):
        kwargs["browser_executable_path"] = CHROME
    browser = await zd.start(**kwargs)
    try:
        page = await browser.get(url)
        html = ""
        deadline = wait_s
        waited = 0.0
        while waited < deadline:
            await asyncio.sleep(2)
            waited += 2
            html = await page.get_content()
            if marker and html.count(marker) >= min_matches:
                # Small settle so a grid still painting finishes its first batch.
                await asyncio.sleep(1.5)
                return await page.get_content()
        # Marker never showed. Return what we have only if it is clearly a full page, not a
        # challenge stub, so the caller can still try to parse.
        return html if len(html) > 40000 else None
    finally:
        await browser.stop()


def main() -> None:
    if len(sys.argv) < 3:
        sys.stderr.write("usage: stealth_fetch.py <url> <wait_marker> [wait_seconds]\n")
        sys.exit(1)
    url = sys.argv[1]
    marker = sys.argv[2]
    wait_s = float(sys.argv[3]) if len(sys.argv) > 3 else 30.0
    min_matches = int(sys.argv[4]) if len(sys.argv) > 4 else 1
    try:
        html = asyncio.run(run(url, marker, wait_s, min_matches))
    except Exception as e:
        sys.stderr.write(f"error: {e}\n")
        sys.exit(1)
    if html is None:
        sys.stderr.write("marker not found; page blocked or changed\n")
        sys.exit(2)
    sys.stdout.write(html)


if __name__ == "__main__":
    main()
