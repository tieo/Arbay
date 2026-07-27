# Cracking mobile.de — attack plan

mobile.de is behind Akamai Bot Manager. Everything else about the German car
market flows through it, so it is worth a serious, staged effort.

## What we actually know (grounded, not assumed)

- **We crawl from a German residential IP.** That is the best-case address class for
  German car sites, so IP reputation is not the blocker here. Confirm the production
  egress is the same residential line rather than a datacenter VPS before assuming
  this holds in production.
- **mobile.de does not ban us.** It returns HTTP 200 + the Akamai JS challenge
  (~2.6 KB `sec-if-cpt` sensor script) — the same front door a fresh browser gets.
- **Akamai scores in two phases.** Phase 1 = TLS/TCP/HTTP2 fingerprint, before any
  JS. Phase 2 = the `sensor_data` POST (mints `_abck`). If phase 1 is strong,
  phase 2 is "mostly ceremonial"; ~90% of public listing/JSON endpoints pass on a
  perfect TLS fingerprint alone. Akamai trusts the TLS layer more than JS because
  JS is tamperable.
- **Since Jan 2026, post-quantum TLS is a hard tell.** Real Chrome 131+ sends an
  `X25519MLKEM768` key share. A client claiming Chrome without it is flagged at the
  TLS handshake. Our current curl_cffi profile likely omits it — a prime suspect
  for why curl_cffi still gets challenged from a good IP.
- **Endpoints:** `services.mobile.de/search-api` = official, dealer-gated (401).
  `m.mobile.de/consumer/api/search/srp` = internal JSON the mweb app uses, returns
  403 without a valid `_abck`. Static shell + JS bundles load without Akamai.
- **Our current MobileDeCrawler** uses `browserOnly=true` → stock Playwright
  Chromium via Xvfb. Stock Playwright is detectable by Akamai; we have never
  verified this path actually passes. That is unknown, not "working."

## Guiding logic

The wall is the fingerprint/sensor layer, not the IP. So the cheapest wins come
from making our own request look byte-perfect (phase 1), and only escalating to
running/borrowing the sensor (phase 2) if a given endpoint enforces it. Every rung
below is a concrete experiment with a go/no-go gate. Stop at the first that works.

## Architecture: swappable fetch backend

Refactor `MobileDeCrawler` to call a `MobileDeFetcher` strategy so the fetch method
is swappable without touching the parser: `tls-sidecar`, `patched-browser`,
`sensor-api`, `managed-api`. Every rung below plugs in here. Build this first so
experiments are one-line swaps.

---

## Rung 0 — Baseline instrumentation (free, do first)

1. Confirm the production egress IP class (residential line vs VPS). Decides whether
   the address is ever a factor in production.
2. Instrument the existing Playwright path: log final URL, whether `_abck` turned
   `~0~` (trusted), and parsed result count. We may already pass and not know it.
3. Capture a real browser's exact request (DevTools) to `consumer/api/search/srp`:
   full header order, cookies, the `x-...` app headers. This is the reference we
   try to reproduce in Rung 1.

Gate: if the Playwright path already returns listings from our IP → mobile.de is
effectively solved; focus shifts to making it fast (Rung 1 for pagination).

---

## Rung 1 — No browser, TLS-perfect (free, highest value)

Hypothesis: from our residential IP, a browser-perfect TLS fingerprint including the
PQ key share passes phase 1 with no browser and no sensor, for the search page and/or
the `consumer/api` JSON.

Try, in order, hitting both `suchen.mobile.de/fahrzeuge/search.html` and
`m.mobile.de/consumer/api/search/srp` (warm the homepage first for `bm_sz`/`ak_bmsc`):

1. Newest `curl_cffi` chrome profiles (chrome131/133/136) — check if any emit
   `X25519MLKEM768`.
2. `rnet`/`wreq` (Rust, Chrome 136–149 profiles) via `uv run --with rnet`.
3. `bogdanfinn/tls-client` (Go) with a Chrome 133 PSK+PQ profile — the most
   complete H2 + PQ story; run as a tiny sidecar binary.

Gate: any of them returns real HTML/JSON instead of the 2.6 KB challenge → cracked
for free. Wire that client as the `tls-sidecar` backend (JVM shells out to it, same
pattern as curl_cffi). This is the target outcome.

If phase 1 passes for the search page but `consumer/api` still 403s, it enforces the
sensor → carry the search-page path and revisit consumer/api under Rung 2/3.

---

## Rung 2 — A browser that actually beats Akamai (free, self-hosted)

If TLS-alone is refused, run a browser that Akamai does not flag, from our IP. Our
current stock Playwright is the weak link.

1. **Patchright** (drop-in patched Playwright) — fixes CDP `Runtime.Enable` leak and
   injected globals. Node sidecar or via Playwright-Java equivalent.
2. **Camoufox** (C++-patched Firefox, real TLS/JA4) — strongest fingerprint; Akamai
   can catch cross-property mismatches, so validate.
3. **zendriver** (Python, CDP, no webdriver) — lightweight alternative.

Run headed under the Xvfb we already have. The browser mints a real `_abck`; then
optionally hand that cookie set to the Rung 1 TLS client for fast pagination within
the same IP/session (short burst only — the cookie is bound to fingerprint+IP, so
keep the sidecar's fingerprint aligned and expect a limited reuse window).

Gate: patched browser reaches the SRP with results. With our good IP, decent odds.

---

## Rung 3 — Rent the sensor, keep our requests (cheap paid, low upkeep)

If we must produce `sensor_data` and do not want to maintain a generator (they break
on Akamai's ~weekly script rotation):

- **Hyper Solutions** (`akm.hypersolutions.co/v2/sensor`): generate `sensor_data`,
  we POST it from our residential IP with our TLS client, target sets `_abck`.
  ~€1.40/1k solves at 250k/mo, ~3 solves per session. We keep IP + request control;
  they keep up with rotation.
- Capsolver / Salamoonder are the same model as fallbacks.

Needs the Rung 1 TLS sidecar (correct fingerprint) + our IP. JVM gap → sidecar.

Gate: at our volume (a few thousand listings/day), cost is a few € — acceptable if
Rungs 1–2 fail.

---

## Rung 4 — Managed unblocker / API (paid, zero upkeep, fallback)

Swap only the fetch layer, keep our parser:

- **Scrapfly** `asp=True` — explicitly documents AutoScout24 Akamai bypass; near
  certainly handles mobile.de. Returns raw HTML for our parser. ~30 credits/req.
- **Apify** `3x1t/mobile-de-scraper` (~€0.75/1k) — returns parsed listings; least
  integration, but their schema not ours.
- **Bright Data / Oxylabs / Decodo** web-unlocker — raw HTML passthrough + `country=DE`.

Gate: use as the reliable fallback if self-hosting is too flaky. Cheapest reliable
is Scrapfly asp or Apify.

---

## Rung 5 — Official / structural (legit, if reachable)

- **Dealer Search-API** (`services.mobile.de/search-api`): clean JSON, full filters,
  2000-result cap. Needs a mobile.de dealer/API account — pursue if one is
  obtainable; it removes scraping entirely and is the most durable path.
- **Mobile-app BMP API** (`x-acf-sensor-data`): the Android app's endpoint uses a
  different sensor; `xvertile/akamai-bmp-generator` targets it. A separate softer
  surface worth a probe if web stays hard.

---

## Cross-cutting: residential proxy

Only needed if production egress is a datacenter VPS. On a residential line, skip
entirely — that is already the address class everyone else pays for.
If needed: sticky-session residential (~€2–15/GB), same exit IP per session.

---

## Recommended order

1. Rung 0 (instrument; confirm prod IP; capture a real browser request).
2. Rung 1 (PQ-enabled TLS client from our residential IP) — most likely free win.
3. Rung 2 (patchright/camoufox) if TLS-alone refused.
4. Rung 4 (Scrapfly asp) as the reliable fallback while 1–2 are validated.
5. Rung 3 (Hyper Solutions) only if we want self-hosted + cheap and 1–2 failed.
6. Rung 5 (dealer API) in parallel as the durable long-term path.

Kill criterion per rung: if it does not return parseable listings from a stable
request within a bounded try, move down. Do not sink time into a private sensor
generator (Rung 3 self-maintained) — that is a full-time treadmill.
