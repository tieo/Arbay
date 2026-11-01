#!/usr/bin/env python3
"""
curl_cffi-based HTTP fetcher for Arbay server.
Impersonates Chrome 131 TLS/HTTP2 fingerprint to bypass Cloudflare Bot Management and Akamai.

Usage:
  cffi_fetch.py fetch <url> [prime_url]
      Fetch a URL and print raw HTML to stdout.
      If prime_url is provided, visits it first to establish session cookies.

  cffi_fetch.py refurbed <query>
      Search Refurbed: calls /search-autosuggest API then fetches product pages concurrently.
      Outputs JSON array: [{title, url, id, price_cents, image_url}, ...]
"""
import sys
import json
import re
import time
from concurrent.futures import ThreadPoolExecutor
from urllib.parse import quote

from curl_cffi import requests as cf_requests

BASE_HEADERS = {
    'Accept-Language': 'de-DE,de;q=0.9,en-US;q=0.8,en;q=0.7',
    'Accept': 'text/html,application/xhtml+xml,application/xml;q=0.9,image/avif,image/webp,*/*;q=0.8',
    'Upgrade-Insecure-Requests': '1',
    'Cache-Control': 'max-age=0',
    'Sec-Fetch-Dest': 'document',
    'Sec-Fetch-Mode': 'navigate',
    'Sec-Fetch-Site': 'none',
    'Sec-Fetch-User': '?1',
}


def make_session():
    s = cf_requests.Session(impersonate='chrome136')
    s.headers.update(BASE_HEADERS)
    return s


# Exit codes used by cmd_fetch (must stay in sync with CurlCffiClient.kt)
# 2 = HTTP 403 blocked, 3 = HTTP 429 rate limited, 4 = HTTP 503, 5 = other non-200
def _http_exit_code(status: int) -> int:
    if status == 403: return 2
    if status == 429: return 3
    if status == 503: return 4
    return 5


_INTERSTITIAL_I = re.compile(r'var i = (\d+); var j = i \+ Number\("(\d+)" \+ "(\d+)"\)')
_INTERSTITIAL_TOKEN = re.compile(r'"bm-verify":\s*"([^"]+)"')


def interstitial_answer(html):
    """
    Akamai's interstitial page, served as HTTP 200 in place of the page, posts a token and a
    proof of work to /_sec/verify and reloads. The work is one sum written into the page
    (j = i + Number("9448" + "98178")). Returns the JSON body to post, or None for any other page.
    """
    if '/_sec/verify?provider=interstitial' not in html:
        return None
    m_i = _INTERSTITIAL_I.search(html)
    m_token = _INTERSTITIAL_TOKEN.search(html)
    if not m_i or not m_token:
        return None
    return {'bm-verify': m_token.group(1), 'pow': int(m_i.group(1)) + int(m_i.group(2) + m_i.group(3))}


def get_through_interstitial(s, url, timeout=30):
    """GET url, answering Akamai's interstitial with the same session up to twice."""
    r = s.get(url, timeout=timeout)
    for _ in range(2):
        if r.status_code != 200:
            return r
        answer = interstitial_answer(r.text)
        if answer is None:
            return r
        sys.stderr.write('answering an Akamai interstitial\n')
        origin = re.match(r'https?://[^/]+', url).group(0)
        verify = s.post(
            origin + '/_sec/verify?provider=interstitial', json=answer, timeout=timeout,
            headers={'Content-Type': 'application/json', 'Referer': r.url, 'Sec-Fetch-Mode': 'cors', 'Sec-Fetch-Dest': 'empty', 'Sec-Fetch-Site': 'same-origin'},
        )
        try:
            location = verify.json().get('location')
        except Exception:
            location = None
        r = s.get(origin + location if location and location.startswith('/') else (location or url), timeout=timeout)
    return r


def cmd_fetch(url, prime_url=None):
    """Fetch URL, optionally priming with homepage first. Writes raw HTML bytes to stdout."""
    s = make_session()
    if prime_url:
        try:
            s.get(prime_url, timeout=20)
            time.sleep(2.0)
        except Exception:
            pass
        s.headers['Sec-Fetch-Site'] = 'same-origin'
        s.headers['Referer'] = prime_url.rstrip('/') + '/'
    r = get_through_interstitial(s, url)
    if r.status_code not in (200,):
        sys.stderr.write(f'HTTP {r.status_code}\n')
        sys.exit(_http_exit_code(r.status_code))
    sys.stdout.buffer.write(r.content)


def cmd_refurbed_search(query):
    """
    Search Refurbed via autosuggest API + concurrent product page price extraction.
    Outputs JSON array of {title, url, id, price_cents, image_url} to stdout.
    """
    s = make_session()

    json_headers = {
        **BASE_HEADERS,
        'Accept': 'application/json, */*',
        'Sec-Fetch-Site': 'same-origin',
        'Sec-Fetch-Dest': 'empty',
        'Sec-Fetch-Mode': 'cors',
        'Referer': 'https://www.refurbed.de/',
    }
    page_headers = {
        **BASE_HEADERS,
        'Sec-Fetch-Site': 'same-origin',
        'Referer': 'https://www.refurbed.de/',
    }

    # Fetch autosuggest results (product names + slugs)
    try:
        r = s.get(
            f'https://www.refurbed.de/search-autosuggest/?query={quote(query)}&category_types=&limit=8&sort_by=popular',
            timeout=10,
            headers=json_headers,
        )
    except Exception as e:
        sys.stderr.write(f'refurbed autosuggest request failed: {e}\n')
        sys.exit(5)
    if r.status_code != 200:
        sys.stderr.write(f'HTTP {r.status_code}\n')
        sys.exit(_http_exit_code(r.status_code))
    try:
        data = r.json()
    except Exception as e:
        sys.stderr.write(f'HTTP {r.status_code} (non-JSON autosuggest — likely blocked): {e}\n')
        sys.exit(4)

    suggestions = data.get('suggestions', [])
    if not suggestions:
        sys.stdout.write('[]')
        return

    # Build items list (up to 5 products; skip accessories by checking name relevance)
    items = []
    for sug in suggestions[:8]:
        name = sug.get('product_name', '')
        link = sug.get('product_link', '')
        if not link or not name:
            continue
        # Extract slug-based ID from product link: /p/samsung-galaxy-s25/ -> samsung-galaxy-s25
        slug = link.strip('/').split('/')[-1] if '/' in link else link
        items.append({
            'title': name,
            'url': f'https://www.refurbed.de{link}',
            'id': slug,
            'price_cents': None,
            'image_url': None,
        })

    # Fetch each product page to extract lowest price (price2 in page data) and image
    def fetch_product(item):
        try:
            r = s.get(item['url'], timeout=15, headers=page_headers)
            if r.status_code != 200:
                return item
            text = r.text

            # price2 is the lowest available price on the page
            m = re.search(r'"price"\s*:\s*"[\d.]+"\s*,\s*"price2"\s*:\s*"([\d.]+)"', text)
            if not m:
                m = re.search(r'"price2"\s*:\s*"([\d.]+)"', text)
            if m:
                price_float = float(m.group(1))
                item['price_cents'] = int(round(price_float * 100))

            # Extract first product image from files.refurbed.com
            m_img = re.search(r'"image"\s*:\s*"(https://files\.refurbed\.com/[^"]+)"', text)
            if m_img:
                item['image_url'] = m_img.group(1)
        except Exception:
            pass
        return item

    with ThreadPoolExecutor(max_workers=5) as ex:
        results = list(ex.map(fetch_product, items))

    valid = [r for r in results if r.get('price_cents')]
    sys.stdout.write(json.dumps(valid, ensure_ascii=False))


if __name__ == '__main__':
    if len(sys.argv) < 3:
        sys.stderr.write('Usage: cffi_fetch.py fetch <url> [prime_url] | refurbed <query>\n')
        sys.exit(1)
    cmd = sys.argv[1]
    if cmd == 'fetch':
        cmd_fetch(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
    elif cmd == 'refurbed':
        cmd_refurbed_search(sys.argv[2])
    else:
        sys.stderr.write(f'Unknown command: {cmd}\n')
        sys.exit(1)
