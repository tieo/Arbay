#!/usr/bin/env python3
"""
rnet-based HTTP fetcher for Arbay server.

rnet wraps the Rust `wreq` client, which emulates a current Chrome TLS + HTTP/2
fingerprint including the post-quantum X25519MLKEM768 key share that Akamai and
Cloudflare check for as of 2026. This passes TLS-gated sites that curl_cffi's older
profiles no longer clear (e.g. Geizhals, willhaben) without a browser.

Usage:
  rnet_fetch.py fetch <url> [prime_url]
      Fetch a URL and print raw HTML to stdout. Warms prime_url first (or the
      target's own origin) to establish session cookies.

Exit codes mirror cffi_fetch.py: 2=403, 3=429, 4=503, 5=other non-200, 1=error.
"""
import sys
import re
import asyncio
from urllib.parse import urlsplit

import rnet
from rnet import Impersonate

HEADERS = {
    "Accept-Language": "de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7",
    "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8",
    "Upgrade-Insecure-Requests": "1",
    "Sec-Fetch-Dest": "document",
    "Sec-Fetch-Mode": "navigate",
    "Sec-Fetch-Site": "none",
    "Sec-Fetch-User": "?1",
}


def status_int(resp) -> int:
    sc = getattr(resp, "status", None)
    try:
        return int(sc)
    except (TypeError, ValueError):
        m = re.search(r"\d{3}", str(sc))
        return int(m.group()) if m else 0


async def run(url: str, prime: str | None) -> tuple[int, str]:
    client = rnet.Client(impersonate=Impersonate.Chrome136)
    origin = prime or f"{urlsplit(url).scheme}://{urlsplit(url).netloc}/"
    try:
        await client.get(origin, headers=HEADERS)
    except Exception:
        pass
    resp = await client.get(url, headers={**HEADERS, "Referer": origin})
    return status_int(resp), await resp.text()


def main() -> None:
    if len(sys.argv) < 3 or sys.argv[1] != "fetch":
        sys.stderr.write("usage: rnet_fetch.py fetch <url> [prime_url]\n")
        sys.exit(1)
    url = sys.argv[2]
    prime = sys.argv[3] if len(sys.argv) > 3 else None
    try:
        status, text = asyncio.run(run(url, prime))
    except Exception as e:
        sys.stderr.write(f"error: {e}\n")
        sys.exit(1)
    if status != 200:
        sys.stderr.write(f"HTTP {status}\n")
        sys.exit({403: 2, 429: 3, 503: 4}.get(status, 5))
    sys.stdout.write(text)


if __name__ == "__main__":
    main()
