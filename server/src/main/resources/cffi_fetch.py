#!/usr/bin/env python3
"""
curl_cffi-based HTTP fetcher for Arbay server.
Impersonates Chrome 131 TLS/HTTP2 fingerprint to bypass Cloudflare Bot Management and Akamai.

Usage:
  cffi_fetch.py fetch <url> [prime_url]
      Fetch a URL and print raw HTML to stdout.
      If prime_url is provided, visits it first to establish session cookies.

  cffi_fetch.py idealo <query>
      Search Idealo: calls /suggest API then fetches product pages concurrently.
      Outputs JSON array: [{title, url, id, price_text}, ...]

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
    r = s.get(url, timeout=30)
    if r.status_code not in (200,):
        sys.stderr.write(f'HTTP {r.status_code}\n')
        sys.exit(_http_exit_code(r.status_code))
    sys.stdout.buffer.write(r.content)


def cmd_idealo_search(query):
    """
    Search Idealo via suggest API + concurrent product page title scraping.
    Outputs JSON array of {title, url, id, price_text} to stdout.
    """
    s = make_session()
    # Prime Idealo homepage to establish Akamai session cookies
    try:
        s.get('https://www.idealo.de', timeout=20)
        time.sleep(1.0)
    except Exception:
        pass

    json_headers = {
        **BASE_HEADERS,
        'Accept': 'application/json, */*',
        'Sec-Fetch-Site': 'same-origin',
        'Referer': 'https://www.idealo.de/',
    }
    page_headers = {
        **BASE_HEADERS,
        'Sec-Fetch-Site': 'same-origin',
        'Referer': 'https://www.idealo.de/',
    }

    # Fetch suggest results (up to 20 product entries)
    try:
        r = s.get(
            f'https://www.idealo.de/suggest?q={quote(query)}&max=20',
            timeout=10,
            headers=json_headers,
        )
    except Exception as e:
        sys.stderr.write(f'suggest request failed: {e}\n')
        sys.exit(5)
    # A block serves a 503/403 HTML challenge, not JSON — classify it as the block it is
    # instead of a cryptic "Expecting value" JSON error.
    if r.status_code != 200:
        sys.stderr.write(f'HTTP {r.status_code}\n')
        sys.exit(_http_exit_code(r.status_code))
    try:
        data = r.json()
    except Exception as e:
        sys.stderr.write(f'HTTP {r.status_code} (non-JSON suggest response — likely blocked): {e}\n')
        sys.exit(4)

    product_items = []
    for group in data.get('groups', []):
        for item in group.get('items', []):
            url = item.get('url', '')
            if 'OffersOfProduct' not in url:
                continue
            m = re.search(r'/(\d{5,})_', url)
            product_items.append({
                'url': url,
                'id': m.group(1) if m else None,
                'suggest_title': item.get('titlePlain', ''),
            })

    # Fetch each product page to extract the title (contains best price: "Name ab X,XX €")
    def fetch_price(item):
        try:
            r = s.get(item['url'], timeout=12, headers=page_headers)
            m_title = re.search(r'<title>([^<]+)</title>', r.text)
            if m_title:
                raw = m_title.group(1).replace('&amp;', '&').replace('&#039;', "'")
                # Format: "Product Name ab 123,45 € (Monat Jahr Preise) | ..."
                m_price = re.search(r'\bab\s+((?:\d{1,3}\.)*\d+[,\.]\d{2})\s*€', raw)
                m_name = re.match(r'^(.*?)\s+ab\s+(?:\d{1,3}\.)*\d+[,\.]\d{2}\s*€', raw)
                item['title'] = m_name.group(1).strip() if m_name else item['suggest_title']
                item['price_text'] = m_price.group(1) if m_price else None
        except Exception:
            item['title'] = item['suggest_title']
            item['price_text'] = None
        return item

    with ThreadPoolExecutor(max_workers=6) as ex:
        results = list(ex.map(fetch_price, product_items))

    valid = [r for r in results if r.get('price_text') and r.get('id')]
    sys.stdout.write(json.dumps(valid, ensure_ascii=False))


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
        sys.stderr.write('Usage: cffi_fetch.py fetch <url> [prime_url] | idealo <query> | refurbed <query>\n')
        sys.exit(1)
    cmd = sys.argv[1]
    if cmd == 'fetch':
        cmd_fetch(sys.argv[2], sys.argv[3] if len(sys.argv) > 3 else None)
    elif cmd == 'idealo':
        cmd_idealo_search(sys.argv[2])
    elif cmd == 'refurbed':
        cmd_refurbed_search(sys.argv[2])
    else:
        sys.stderr.write(f'Unknown command: {cmd}\n')
        sys.exit(1)
