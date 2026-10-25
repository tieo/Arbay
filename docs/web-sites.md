# Teaching the web market a new site

The web market (`WebCrawler`) asks six search engines for a search's words and reads every shop
page they list. A page is read by the structured data shops publish for search engines
(`ProductPages`: JSON-LD, microdata, OpenGraph), or by the site's recipe (`SiteRecipes`). Before a site gets
here, two generic readers try it: the JSON a JavaScript shop hands its page (`__NEXT_DATA__`,
`application/json` scripts), and the box a list page repeats with a link and a price in each; a
single product page is also guessed from its one heading and its price element. A page that sells
something and yields no offer, or only a guess, is a **gap**: the server keeps the page as it fetched it
and reports the site on `GET /api/web/gaps`. Each report is handed to the Paseo agent
"Arbay web sites", which works through this document for every site in it.

The server's API answers at `127.0.0.1:8091` on the machine it runs on; the project memory says how
to reach that machine.

## Endpoints

| Call | What it does |
|---|---|
| `GET /api/web/gaps?after=<seq>` | The reported sites after a number |
| `GET /api/web/gaps/<domain>/page` | The page as Arbay fetched it when it reported the site |
| `GET /api/web/recipes` | Every recipe |
| `POST /api/web/try` `{"url": ..., "recipe": {...}, "kept": true}` | Reads a page by a recipe (or the stored one, or structured data only) and answers the offers found, what read them and whether the page sells something. `kept` reads the kept page instead of fetching it again. |
| `PUT /api/web/recipes` (a recipe) | Keeps a recipe and reads the site again for every search that met it; answers how many offers each of those searches gained. A search still open on a screen shows them at once. |
| `POST /api/web/gaps/<domain>/given-up` | Says a site cannot be taught, so the searches waiting on it stop waiting |
| `DELETE /api/web/recipes/<domain>` | Drops a site's recipes |

## A recipe

```json
{
  "domain": "example.de",
  "urlPattern": "/suche|/search",
  "item": "div.product-tile",
  "title": "a.product-name",
  "price": "span.price@content",
  "currency": "EUR",
  "link": "a.product-name@href",
  "image": "img@src",
  "condition": "span.condition",
  "soldOut": ".not-available",
  "render": false,
  "searchUrl": "https://www.example.de/search?q={q}",
  "note": "why it reads the page this way"
}
```

Every field holding a selector takes a CSS selector, optionally followed by `@attribute` to read
that attribute instead of the element's text. `item` is the box of one offer on a page listing
several; leave it out where the page is one product. `urlPattern` (a regex on the address) limits
a recipe to some of the site's pages, so a site can have one recipe for its product pages and one
for its search results. `render: true` reads the page in a browser, for a site whose offers only
appear once its scripts ran. `searchUrl` is the site's own search with `{q}` for the words: from
then on the site is searched directly on every web search, so set it wherever the site has a
search.

Where no element holds one whole offer (pearl's lists split each product across rows, and state
it whole only in a script), `pattern` replaces `item`, `title`, `price`, `link` and `image`: a
regex over the page's source with the named groups `title` and `price`, and `link` and `image`
where it has them. Backslash escapes and HTML entities in a group are read.

```json
{ "domain": "pearl.de", "pattern": "new Product\\('[^']*','(?<title>(?:[^'\\\\]|\\\\.)*)',(?<price>[\\d.]+),'(?<link>[^']*)'" }
```

A site that hires things out (a Mietpark, a Verleih) gets `"rentPer": "DAY"` with `price` reading
its day rate, so rentals compare with each other, and its other rates in `rates`, each a selector
by the time it pays for (`HALF_DAY`, `DAY`, `WEEKEND`, `WEEK`, `MONTH`). Its offers are rentals:
shown as "45,00 €/day", and left out of every search whose words do not ask to rent.

```json
{ "domain": "toom.de", "title": "h1", "price": "td.day", "rentPer": "DAY", "rates": {"WEEKEND": "td.weekend", "WEEK": "td.week"} }
```

A page passing itself off as another shop (its `og:site_name` a letter or two off its own address,
as medymarkt.live serving MediaMarkt's pages) is never read and never reported.

## For each reported site

1. Fetch the kept page and read it. Decide what kind of page it is: one product, a list of
   products, or a page that sells nothing (a review, a manual, a forum with a price in it).
2. A page that sells nothing is a false report. Fix `ProductPages.sellsSomething` so pages like it
   are not reported, with a test in `WebMarketTest` built from the page, and deploy.
3. Otherwise find the elements carrying each offer's title and price, and the link and image where
   the page lists several. Prefer attributes meant for machines (`content`, `data-price`) over
   visible text. Find the site's own search (its search form's action) for `searchUrl`, and write a
   second recipe with a `urlPattern` for its results page where that page is built differently.
4. Try the recipe with `POST /api/web/try` on the kept page and on the live page, and on the
   site's search results for the words of the report. Every offer must carry the title and price
   the page shows; check them against the page, not against what looks plausible.
5. Keep it with `PUT /api/web/recipes`, then try the page once more without a recipe in the
   request, which reads it the way a search will.
6. Where no recipe can read the site (the offers come from a JSON API the page calls, a price
   needs a variant picked first, the site answers Arbay's fetch with a block page), change the
   code instead: `WebCrawler`, `ProductPages` or `SiteRecipes`, with a test, committed and deployed
   as every Arbay change is.
7. A site that blocks every way in, or turns out to sell nothing, is given up on with
   `POST /api/web/gaps/<domain>/given-up`, so the searches waiting on it finish, and said so in the
   report.

Never send anything to a seller and never buy anything. Report to the user as a short table: each
site, what kind of page it was, what was done (recipe kept, code changed, false report fixed,
blocked) and how many offers the live page gives now.
