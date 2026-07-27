# The results screen

Written before the screen, and consulted while changing it. Its purpose is to settle in advance the
decisions that otherwise get made one control at a time, which is how the screen came to show eight
things at once and no listings above the fold.

## The one job

**Show what is for sale, cheapest and most relevant first, from every market at once.**

If a change does not serve that sentence, it belongs behind a tap.

## What the person is doing

1. Hunting a used machine across markets they would otherwise search one by one. They want to see
   the best offers immediately and judge in seconds whether anything is worth pursuing.
2. Deciding whether a price is good, without doing arithmetic.
3. Narrowing — price, market, junk words — without losing their place in the list.
4. Killing junk for good, so a blocked word stays blocked.
5. Saving the search and coming back to what is new.
6. Seeing where a thing is, because they may drive to it.

## Tiers, by how often they are needed

Every element belongs to exactly one tier. The tier decides where it lives, not how important it
feels while being written.

**Tier 1 — the canvas.** Needed on every visit, so it is the screen itself, starting at the top:
the listings. Each row carries a thumbnail, the title, the price, which market it came from, where
it is, and how old the ad is.

**Tier 2 — one tap away.** Needed on some visits: the price band, which markets to search, blocked
words, sort order. These live in a filter sheet behind a single control that also says how many
filters are active. Not laid out on the canvas.

**Tier 3 — on request.** Diagnostic or occasional: the price distribution, sold history, per-market
coverage and errors, the crawl's progress once finished. Behind a tap, in a sheet of their own.

## Rules

- One number for a price. "From €300" is the figure; median, spread and distribution live in the
  price sheet. Never three summary lines competing above the fold.
- One count on screen at a time, attached to the control it describes. A header saying 188 while a
  chip says 50 is a bug, not a detail.
- Progress is a thin line that removes itself. While markets are still answering, one slim bar and
  one short line; when they are done, both disappear. It is never a headline.
- At most five controls visible at once. Everything else is behind the filter sheet.
- A listing is reachable without scrolling. If the first row is below the fold, something in tier 2
  or 3 has crept onto the canvas.

## Not this screen

Named so they cannot creep back in one control at a time:

- **No image carousel.** A strip of photos from unrelated listings decides nothing; it cost the
  whole first screen and showed a broken placeholder when a URL failed.
- **No permanent progress banner.** "Searching 32/38 platforms…" as a headline competes with the
  first result.
- **No stacked price summaries.** Best price, median, and "used from" are three answers to one
  question.
- **No filter row per filter.** Each new filter goes into the sheet, never onto a new row of the
  canvas.
- **No control without a count or a state.** A chip that cannot say what it is doing is noise.

## Judging a change

- Cover the screenshot after five seconds. If the first thing recalled is not "these are the
  offers", the change is not done.
- Walk Nielsen's heuristics, and in particular: does the screen ask anyone to reconcile two numbers,
  and is anything shown that the job in the first line does not need?
- Render the screen (`./gradlew :composeApp:renderGallery`) and read the image, before the emulator.
