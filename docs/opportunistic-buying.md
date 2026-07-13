# Opportunistic buying — thinking

Buy wanted items when a trip already takes you near them, instead of making a
special trip. The user keeps a wishlist; a watcher pings when either a good new
offer appears, or a trip takes the user near a place that has a wanted item —
possibly weeks after the want is recorded.

Status: exploration. No decisions, no implementation.

## Reframe: a spatiotemporal join

Supply = offers with locations, appearing and vanishing over time. Demand-presence
= where the user will physically be, which is uncertain and must be predicted. The
feature is the join of the two. The join is hard because the two sides live on
different clocks:

- Offers are **ephemeral** — a good listing sells in days.
- Trips near any given place are **sparse** — maybe weeks apart.

So pre-matching a specific live offer to a future trip almost never hits: by the
time the trip happens, that offer is gone. This kills the naive "compute offer
locations now, geofence them, wait for the user to drive past" design. It only
works for durable supply (shops, standing listings), not marketplace listings.

## The pivot: crawl at trip time, not before

Instead of pre-placing ephemeral offers and waiting, invert it: detect that a trip
is taking the user near an area, then **crawl the wishlist live, scoped to that
area, at that moment.** Offers are fetched when they matter, so ephemerality stops
being a problem. Existing crawlers already accept a location/radius (e.g. the
Kleinanzeigen URL builder), so "wishlist near lat/lng" is buildable on current
infra.

This splits the two triggers cleanly:

- **Trigger a (offer-first, time-based):** global poll of the wishlist in the
  home region → ping on a good new offer. Reuses `FreeItemMonitor`. Ephemerality
  is fine here because the poll is frequent.
- **Trigger b (trip-first, geo-triggered):** detect near-an-area → live
  area-scoped crawl → ping if something good is nearby. Ephemerality dodged
  because the crawl is at trip time.

This matches the user's own framing ("weeks later, whenever a trip happens
nearby") better than any pre-matching scheme.

## Predicting presence — more than GPS

Destination cannot be read from Maps (no such API; confirmed). But GPS trajectory
inference is not the only signal, and probably not the best. Ordered by
signal-strength-per-battery:

1. **Calendar events with a location.** "Climbing Innsbruck, Sat." Parsed days or
   weeks ahead, zero battery, high confidence. Strongest signal and it enables
   genuine foresight (pre-check offers near Innsbruck before Saturday). Needs
   calendar read permission.
2. **Manual trip declaration.** User types "Munich Friday" or shares a Maps place
   to Arbay via the Android share intent (treat a shared place as a declared
   destination). Zero battery, high confidence, works today.
3. **Recurring patterns.** Learned weekly gym / regular routes. Cheap once
   history exists; cold-start.
4. **Arrival detection (GPS, cheap).** Significant-location-change or dwell in a
   non-home cluster → "you are somewhere you don't usually go" → fire the local
   crawl. This is *reactive* (you're already there) but still valuable for a quick
   pickup, and far cheaper than trajectory math.
5. **Trajectory foresight (GPS, premium).** Activity-gated sampling + forward
   corridor projection to ping *before* arrival so a detour is possible. Highest
   battery, most false positives — the premium tier, not the foundation.

Note the split: **arrival** (you're there, simple, dwell detection) vs **heading
toward** (foresight, lets you detour, needs prediction). Arrival alone delivers the
core value; foresight is an upgrade.

## Home-vs-away primitive

Maintain a set of home/frequent areas. Trigger b fires when the user is in, or
predicted to be in, a *non-home* area — a genuine "trip." This is cheaper and more
robust than trajectory cones, and directly expresses "whenever a trip takes me
nearby." The climbing case falls out: arrive in the mountain town → local wishlist
crawl → "drill you wanted, 8 km away."

## Sketch of moving parts (not a plan yet)

- Wishlist: likely reuse bookmarked searches; each item may need max price, radius,
  detour tolerance, urgency. Undecided whether bookmarks carry these or a thin
  overlay does.
- Offer-first poll: existing FreeItemMonitor + scorers.
- Presence sources: calendar, share-intent, arrival detection, later patterns and
  trajectory.
- Geo-triggered local crawl: reuse location-scoped crawlers.
- Notification policy: rate-limit per item per trip, dedupe sources, quiet hours,
  actionable (view / navigate / not-interested → classifier feedback).

## Open questions to resolve before planning

- Offer-location granularity: many platforms give only city/zip, not coordinates.
  Geocode to centroid; radius must absorb the error.
- How patient is a want? Time-pressured (ping anywhere) vs standing (only when a
  trip happens). Probably a per-item flag.
- Foresight vs arrival: is pre-arrival detour value worth the GPS/battery cost, or
  is arrival-time crawling enough for v1?
- Calendar mining: worth the permission and parsing, or start with manual
  declaration + arrival detection?
- Privacy: keep all location on-device; server sees only area-scoped crawl
  queries, never a track.
