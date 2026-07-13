# EU used-vehicle market analysis (for cross-border sourcing into Germany)

Purpose: decide which countries are worth crawling to source used cars/vans (first
target: VW Crafter) for import into Germany, and how each market's tax and border
rules affect the real landed price. Research dated 2026-07-12. Claims came from
web research (marketplace docs, tax authorities, robots.txt, scraper write-ups),
not live listing scraping; the numeric price gaps are the weakest part and must be
confirmed by the scanner itself once it runs.

## Headline: the premise is mostly inverted

On a **matched vehicle**, Germany is one of the cheapest markets in Western and
Northern Europe. France, Belgium, Netherlands, Luxembourg, Finland are net
*importers* of German cars; their national-average prices look lower only because
their stock skews smaller and older (fleet mix), not because the same van is
cheaper. Straight price arbitrage into Germany mostly does not exist there.

Where sourcing can still pay:

- **East** (Czech, Poland) — genuinely lower price levels, the classic cheap source.
- **Denmark** and **Sweden** — for specific mechanisms (below), not blanket cheapness.
- Everything else — scan only for the occasional mispriced individual listing.

Two border rules dominate every verdict:

- **EU membership** removes customs duty and (for genuinely-used private purchases:
  older than 6 months AND over 6,000 km) import VAT. Non-EU origin (Switzerland,
  Norway, UK) adds ~10% duty + 19% German import VAT (Einfuhrumsatzsteuer).
- **Left-hand drive.** UK and Ireland are right-hand drive: legal to register but a
  real daily handicap (headlight law, overtaking sightlines, resale), and van
  RHD-to-LHD conversion is not economic. Everyone else drives on the right.

## Ranked verdict (best to worst source for German import)

| Rank | Country | EU | Landed-cost friction | Why |
|---|---|---|---|
| 1 | Czech Republic | Yes | none | Lower price level, less "buying back German re-exports" than Poland. Best of the cheap-East group. |
| 2 | Denmark | Yes | none (used private) | Closest logistics (~330 km), euro-pegged. Reg-tax is embedded BUT refundable on export (eksportgodtgørelse); vans are tax-capped. Rank by (price − expected refund). |
| 3 | Poland | Yes | none | Big volume, ~7% cheaper aggregate, but >40% of its used imports are German cars re-sold at a markup. Needs origin-filtering to avoid buying German stock back. |
| 4 | Sweden | Yes | none (used private) | Largest, least tax-distorted Nordic market; van section on Bytbil. But used prices are rising on export demand; SEK FX risk; price gap vs Germany unproven. |
| 5 | Austria | Yes | none | Same currency, close, but no verified price advantage. Opportunistic only. |
| 6 | Netherlands | Yes | none (used private) | Prices above Germany, EXCEPT the BPM export-refund creates genuine sub-market deals; Marktplaats has an official OAuth2 API (easiest legit crawl). Targeted scan. |
| 7 | Belgium | Yes | none | Matched prices higher, but the company-car/EV return glut throws wholesale-priced outliers; 2dehands has weak bot protection. |
| — | France | Yes | none | More expensive; France imports German cars. Sites are DataDome-hard. No. |
| — | Luxembourg | Yes | none | Highest prices in the West group, negligible volume. No. |
| — | Finland | Yes | none (used private) | EUR (no FX) but price gradient runs the wrong way; autovero inflates the floor; export refund decays to ~0 on old vans; farthest to ship. No/maybe. |
| — | Switzerland | No | ~10% duty + 19% VAT | Non-EU, and Swiss prices are already higher. Customs stack erases any gap. tutti.ch/AutoScout24.ch are Cloudflare-hard. No. |
| — | Norway | No (EEA) | 19% VAT always; ~10% duty unless EEA origin proven | Non-EU friction plus an EV-hollowed, shrinking diesel-van pool. Dealers-only if at all. No/marginal. |
| — | UK | No | ~31% (car) to ~45% (van) duty+VAT | RHD AND non-EU. A Crafter panel van lands in the higher goods-vehicle duty bracket. Actively bad. No. |
| — | Ireland | Yes | none | EU/euro but RHD, and already pricier than Germany (VRT). DoneDeal ToS bans aggregator use. No. |

## Export-refund mechanism (changes the math for DK, FI, NL)

Several countries embed a high one-time registration tax in the sticker price, then
refund most of it when the vehicle is deregistered and exported:

- **Denmark** eksportgodtgørelse: refund ≈ (tax an equivalent used car would owe
  today) − 15%, prorated by depreciation, ~9-week processing. Vans are capped low
  to begin with (large panel vans capped at 47,000 DKK), so the distortion is mild.
- **Finland** autovero export refund: min €500, capped at tax paid, decays steeply
  with age — near-worthless on older vans.
- **Netherlands** BPM export refund: partial, age-depreciated, on re-registration in
  another EU state within 13 weeks.

The refund accrues to whoever files it (usually the seller/export dealer), so the
scanner's real signal is **(asking price − expected export refund)**, and the buyer
must target export dealers or plan to file it. This is a correction to the naive
"registration tax is sunk" assumption.

## Marketplaces and crawl difficulty

Per country, the leaders and their bot posture. Attribution of anti-bot vendors
rests on scraper write-ups and case studies, not raw header capture; verify before
building.

- **Czech**: sauto.cz (Seznam, custom protection), tipcars.com (Cloudflare),
  auto.bazos.cz (plain nginx, easiest), autoscout24.cz.
- **Denmark**: bilbasen.dk (disallows query-string crawling, sitemaps ok),
  dba.dk (`/mobility/search/car`, permissive). No CDN WAF seen.
- **Poland**: otomoto.pl (CloudFront, no heavy WAF), autoscout24.pl.
- **Sweden**: blocket.se (dominant, ToS bans crawling, needs residential proxies),
  bytbil.com (dealer-only, `/transportbil`, clean URL filters, light defenses —
  softest technical target).
- **Netherlands**: marktplaats.nl (official OAuth2 API — cheapest legit crawl),
  autoscout24.nl (Akamai), gaspedaal.nl (hard-blocked WAF).
- **Belgium**: 2dehands.be/2ememain.be (public REST, weak protection),
  autoscout24.be (Akamai).
- **Austria**: willhaben.at (already a platform here, currently IP-blocked from our
  server), autoscout24.at.
- **France**: leboncoin.fr, lacentrale.fr (both DataDome, homepage 403s),
  autoscout24.fr (Akamai).
- **Norway**: finn.no (~80% share, ToS bans crawling, API business-gated).
- **Finland**: nettiauto.com (SSO-redirect hurdle), tori.fi (server-rendered,
  crawlable).

**AutoScout24 is pan-European** on one backend: the existing crawler reaches every
country above by switching the `cy=` country parameter and the ccTLD, so multi-country
AutoScout24 is nearly free to add. Its per-country domains sit behind Akamai, so it
needs the browser fetch path, not plain HTTP.

## Recommended build order

1. AutoScout24 multi-country via `cy=` (cheapest expansion, covers CZ/PL/AT/NL/BE/
   the lot in one crawler). Browser fetch path for Akamai.
2. Czech: auto.bazos.cz (easiest) then sauto.cz.
3. Denmark: dba.dk then bilbasen.dk, with an (asking − export-refund) estimate.
4. Sweden: bytbil.com `/transportbil` (van-specific, soft).
5. Netherlands: marktplaats.nl via its official API.

## Open items to resolve before committing capital

- German-side VAT on EU used imports: one source raised a Fahrzeugeinzelbesteuerung
  (19%) that appears to contradict the EU used-vehicle exemption. Needs a dedicated
  legal check with a Steuerberater.
- Whether a foreign (German) buyer can practically capture the Danish/Finnish/Dutch
  export refunds, or whether they always accrue to the local seller.
- No clean numeric Nordic/East-vs-Germany matched-price dataset exists publicly. The
  scanner itself is the tool to measure the real gaps.
