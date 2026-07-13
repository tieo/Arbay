# Reliable car scraping

Goal: a dependable scan of the German car market for a given vehicle (first target:
VW Crafter). Reliability means two things: each site is fetched by an engine that
actually gets through, and a crawler that silently returns wrong results is treated
as broken, not as successful.

## Evidence base (probed 2026-07-12)

| Site | Plain HTTP | Notes |
|---|---|---|
| AutoScout24 | 200 | `?query=` is ignored by the site; path URL `/lst/volkswagen/crafter` returns real Crafters, numberOfResults 1565 |
| Kleinanzeigen | 200 | autos category `/s-autos/volkswagen-crafter/k0c216` works, 307 Crafter mentions |
| mobile.de | 403 | Akamai challenge on every non-browser engine, including curl_cffi with chrome/safari/firefox impersonation and homepage priming. Browser only. |
| eBay.de | 403 | Known: needs non-headless Chromium (Argon2 PoW). Existing path. |
| heycar (hey.car) | 200 | `/gebrauchtwagen/vw/crafter`, 1.4 MB page, 636 Crafter mentions. Not crawled yet. |
| TruckScout24 | 200 | `/transporter/gebraucht?makeModel=volkswagen%20crafter`, 24 Crafter mentions. Not crawled yet. Crafter is a commercial van, this stock is missing from car portals. |

Development machine: no Xvfb, no Playwright browsers, no curl_cffi. Fallback steps
2 to 4 are dead in local runs; only plain HTTP works. curl_cffi runs via
`uv run --with curl_cffi` plus `LD_LIBRARY_PATH` for libstdc++ (proven in probes).

Container: Dockerfile copies only the fat jar onto the Playwright base image.
`CurlCffiClient` shells out to `python3` with curl_cffi installed, nothing installs
it. Must be verified and fixed, otherwise step 2 of the fallback chain is dead in
production too.

## Design

### 1. Relevance guard (kills the silent-garbage failure mode)

The AutoScout24 bug class: site ignores the query, returns its default feed, crawler
parses it fine, status tracker records success. Invisible.

Change in `trackedSearch` (Crawler.kt): after a non-empty result set, compute the
fraction of listings whose title or description contains at least one significant
query token (reuse RelevanceFilter normalization, so "vw" matches "Volkswagen"
via its alias handling). If the set has 5 or more listings and the fraction is
below 0.2, record a new `ErrorType.IRRELEVANT_RESULTS` with an error snapshot and
log the top titles. Results still pass through (RelevanceFilter downstream drops
the junk), but the platform status now shows the truth: the search did not happen.

### 2. AutoScout24 fix

Replace the query URL with a path resolver:

- Parse the query against a static make list (German market makes, lowercase
  slugs). Query "volkswagen crafter 2020" resolves to make `volkswagen`, model
  `crafter`, remainder stays for RelevanceFilter.
- URL: `https://www.autoscout24.de/lst/{make}[/{model}]?atype=C&cy=D&sort=standard&ustate=N%2CU&page=N`.
  `atype=C` includes vans (verified: Crafter listings appear), so no category
  split is needed here.
- No make match: fall back to the current `?query=` URL. The relevance guard then
  reports it if the site ignores it.
- Pagination: `page=` up to `SearchQuery.maxPages` (default from CrawlerConfig),
  stop early when a page yields no new listings. 1565 results at 20 per page make
  single-page fetching pointless.

### 3. mobile.de

Facts force browser-only: skip HTTP and curl_cffi entirely for this platform, they
always fail and waste about 35 s per search. Add an engine hint so a crawler can
declare `browserOnly`, and `fetchWithFallback` starts at step 3.

Open point to verify in the browser (cannot be probed without one): whether
`vc=Car` includes Crafter listings or the Transporter category is separate. Plan:
fetch both the Car and the VanUpTo7500 variants of the search, merge, dedupe by
listing id. If reference data for make/model ids (`ms=` parameter) is reachable
once a browser session exists, prefer ids over free text `q=`.

### 4. Kleinanzeigen

Works over plain HTTP. Improvement: when the query resolves to make plus model,
use the autos category path `/s-autos/{query-slug}/k0c216` instead of the generic
search, which cuts unrelated categories (spare parts, toys). Keep the generic URL
as fallback. Extend `KleinanzeigenUrlBuilder` and its test.

### 5. New crawlers: heycar, TruckScout24

Both reachable by plain HTTP, both parse-only work:

- **HeycarCrawler**: URL `/gebrauchtwagen/{make}/{model}`; page is a React app,
  check for embedded JSON state first (`__NEXT_DATA__` or similar), fall back to
  DOM selectors. New `PlatformId.HEYCAR`.
- **TruckScout24Crawler**: URL
  `/transporter/gebraucht?makeModel={make}%20{model}`; new
  `PlatformId.TRUCKSCOUT24`. Covers the commercial-van stock the car portals miss.
  Verify during implementation that `makeModel` filters instead of being ignored
  (same trap as AutoScout24; the relevance guard also covers it permanently).

Both crawlers are car-only and join the car platform group, not GENERAL_PLATFORMS.

### 6. Car platform group

`CrawlerRoutes` has GENERAL_PLATFORMS excluding car sites. Add CAR_PLATFORMS
(AutoScout24, mobile.de, Kleinanzeigen, eBay.de, heycar, TruckScout24). The stream
route already accepts an explicit `platforms=` list, so the client can request the
car group; additionally, when the query resolves to a known make plus model, the
default group becomes CAR_PLATFORMS server-side.

### 7. Runtime dependencies

Container (production):

- Verify the Playwright base image: python3 present? Xvfb present? curl_cffi
  absent for sure.
- Dockerfile: install python3/pip if missing and `pip install curl_cffi`
  (imperative install is fine inside the image build). Xvfb ships with Playwright
  images for headed runs; verify, add the package if not.
- Smoke test inside the container: run the jar, call
  `/api/crawler/test/BACKMARKET_DE` (exercises curl_cffi) and
  `/api/crawler/test/EBAY_DE` (exercises non-headless Chromium).

Development machine (NixOS, declarative):

- Add a dev shell to the repo (`shell.nix`) with python3 including curl-cffi,
  Xvfb, and `playwright-driver.browsers` with `PLAYWRIGHT_BROWSERS_PATH` set, so
  the full fallback chain is testable natively. The Playwright JVM version pinned
  in gradle must match the nixpkgs driver version; if it cannot, run local
  browser tests through the container instead and keep the dev shell for
  curl_cffi only.

### 8. Verification

- Parser unit tests from captured live HTML fixtures (CrawlerParserTest pattern)
  for AutoScout24 path URL, heycar, TruckScout24, Kleinanzeigen autos category.
- URL resolver unit tests: query text to per-site URL, including fallbacks.
- Live end-to-end per platform: `/api/crawler/test/{platform}?q=Volkswagen
  Crafter` must return listings whose titles contain "Crafter"; zero such titles
  fails the check even when the raw count is high.
- The relevance guard plus error snapshots serve as the permanent canary; no
  separate scheduled canary in this iteration.

## Build order

1. Relevance guard plus `IRRELEVANT_RESULTS` (generic, catches everything else).
2. AutoScout24 path resolver plus pagination, with fixtures and tests.
3. Kleinanzeigen autos category URL.
4. heycar and TruckScout24 crawlers.
5. Car platform group routing.
6. Dockerfile runtime deps plus container smoke test (curl_cffi, Xvfb, browsers).
7. mobile.de browser-only path, category verification, id-based search if
   reachable.
8. Full live Crafter scan across the group, compare counts per site, save
   platform status to memory.
