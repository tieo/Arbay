#!/usr/bin/env python3
"""Turn the model in app.yaml into one browsable page.

The model is data in the repository; this only renders it. Nothing is written by hand into the
output, so the page cannot drift from the file that is reviewed and committed.

    python3 docs/model/build.py docs/model/app.yaml site/index.html
"""
import html
import sys
from pathlib import Path

import yaml

STATUS_LABEL = {"MISSING": "not built", "BROKEN": "broken", "OK": ""}


def chip(status):
    if not status or status == "OK":
        return ""
    cls = "missing" if status == "MISSING" else "broken"
    return f'<span class="chip {cls}">{STATUS_LABEL.get(status, status)}</span>'


def esc(x):
    return html.escape(str(x)) if x is not None else ""


def render_list(items, key="what"):
    out = []
    for it in items or []:
        if isinstance(it, str):
            out.append(f"<li>{esc(it)}</li>")
            continue
        text = esc(it.get(key) or it.get("id") or "")
        bits = []
        if it.get("tier"):
            bits.append(f'<span class="tier">tier {esc(it["tier"])}</span>')
        if it.get("goes_to"):
            bits.append(f'<a class="link" href="#{esc(it["goes_to"])}">→ {esc(it["goes_to"])}</a>')
        if it.get("opens"):
            bits.append(f'<a class="link" href="#{esc(it["opens"])}">→ {esc(it["opens"])}</a>')
        if it.get("persists_into"):
            bits.append(f'<span class="meta">kept in {esc(it["persists_into"])}</span>')
        note = f'<div class="note">{esc(it["note"])}</div>' if it.get("note") else ""
        detail = f'<div class="note">{esc(it["detail"])}</div>' if it.get("detail") else ""
        gap = f'<div class="note gap">{esc(it["gap"])}</div>' if it.get("gap") else ""
        when = f'<span class="meta">{esc(it["when"])}</span>' if it.get("when") else ""
        shows = f'<div class="note">{esc(it["shows"])}</div>' if it.get("shows") else ""
        out.append(
            f'<li>{text} {chip(it.get("status"))} {when} {" ".join(bits)}{note}{detail}{gap}{shows}</li>'
        )
    return "\n".join(out)


def section(title, items, key="what"):
    if not items:
        return ""
    return f'<h3>{esc(title)}</h3><ul>{render_list(items, key)}</ul>'


def render(model):
    views = model.get("views", [])
    nav = "\n".join(
        f'<a href="#{esc(v["id"])}">{esc(v["name"])} {chip(v.get("status"))}</a>' for v in views
    )
    blocks = []
    for v in views:
        parts = [f'<h2 id="{esc(v["id"])}">{esc(v["name"])} {chip(v.get("status"))}</h2>']
        parts.append(f'<p class="purpose">{esc(v.get("purpose", ""))}</p>')
        if v.get("note"):
            parts.append(f'<p class="note">{esc(v["note"])}</p>')
        if v.get("reached_from"):
            froms = v["reached_from"]
            if froms and isinstance(froms[0], dict):
                txt = ", ".join(f'{esc(f["from"])} ({esc(f.get("by",""))})' for f in froms)
            else:
                txt = ", ".join(esc(f) for f in froms)
            parts.append(f'<p class="meta">reached from {txt}</p>')
        parts.append(section("Shows", v.get("shows")))
        parts.append(section("Can do", v.get("actions")))
        parts.append(section("States", v.get("states"), key="id"))
        parts.append(section("Gaps", v.get("gaps")))
        parts.append(section("Deliberately not here", v.get("not_this_view")))
        parts.append(section("Open questions", v.get("open_questions")))
        blocks.append(f'<section class="view">{"".join(p for p in parts if p)}</section>')

    across = section("Across the app", model.get("across_the_app"))
    meta = model.get("meta", {})
    counts = {
        "views": len(views),
        "missing": sum(1 for v in views if v.get("status") == "MISSING"),
    }
    return f"""<!doctype html>
<html lang="en"><head><meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>{esc(meta.get('name','Model'))} — the model</title>
<style>
 :root {{ --bg:#fbfbf9; --fg:#1a1a17; --muted:#6b6b63; --line:#e3e3dd; --accent:#2f6f43;
          --missing:#8a6d1f; --broken:#9c3428; }}
 @media (prefers-color-scheme: dark) {{ :root {{ --bg:#14140f; --fg:#eceae1; --muted:#98978c;
          --line:#2c2c25; --accent:#7fc79a; --missing:#d9b95c; --broken:#e08a7d; }} }}
 * {{ box-sizing:border-box }}
 body {{ margin:0; background:var(--bg); color:var(--fg);
   font:16px/1.55 ui-sans-serif,system-ui,-apple-system,"Segoe UI",sans-serif; }}
 .wrap {{ max-width:52rem; margin:0 auto; padding:2rem 1.25rem 5rem }}
 h1 {{ font-size:1.5rem; margin:0 0 .25rem }}
 .lede {{ color:var(--muted); margin:0 0 1.5rem }}
 nav {{ display:flex; flex-wrap:wrap; gap:.5rem; margin-bottom:2.5rem }}
 nav a {{ color:var(--fg); text-decoration:none; border:1px solid var(--line);
   border-radius:999px; padding:.3rem .7rem; font-size:.85rem }}
 nav a:hover {{ border-color:var(--accent) }}
 section.view {{ border-top:1px solid var(--line); padding-top:1.5rem; margin-top:2rem }}
 h2 {{ font-size:1.15rem; margin:0 0 .35rem }}
 h3 {{ font-size:.8rem; text-transform:uppercase; letter-spacing:.06em;
   color:var(--muted); margin:1.25rem 0 .4rem; font-weight:600 }}
 .purpose {{ margin:.25rem 0 .5rem }}
 ul {{ margin:0; padding-left:1.1rem }}
 li {{ margin:.3rem 0 }}
 .meta {{ color:var(--muted); font-size:.85rem }}
 .note {{ color:var(--muted); font-size:.88rem; margin:.15rem 0 .35rem }}
 .note.gap {{ color:var(--broken) }}
 .tier {{ color:var(--muted); font-size:.75rem; border:1px solid var(--line);
   border-radius:4px; padding:0 .3rem }}
 .link {{ color:var(--accent); text-decoration:none; font-size:.85rem }}
 .chip {{ font-size:.72rem; border-radius:999px; padding:.1rem .5rem; vertical-align:middle }}
 .chip.missing {{ color:var(--missing); border:1px solid var(--missing) }}
 .chip.broken {{ color:var(--broken); border:1px solid var(--broken) }}
 footer {{ margin-top:3rem; color:var(--muted); font-size:.8rem }}
</style></head><body><div class="wrap">
<h1>{esc(meta.get('name',''))}</h1>
<p class="lede">{esc(meta.get('one_line',''))}</p>
<p class="meta">{counts['views']} views, {counts['missing']} of them not built yet ·
 {esc(meta.get('markets',''))} markets</p>
<nav>{nav}</nav>
{"".join(blocks)}
{f'<section class="view">{across}</section>' if across else ''}
<footer>Generated from <code>docs/model/app.yaml</code>. Edit the file, not this page.</footer>
</div></body></html>
"""


def main():
    src = Path(sys.argv[1] if len(sys.argv) > 1 else "docs/model/app.yaml")
    dst = Path(sys.argv[2] if len(sys.argv) > 2 else "site/index.html")
    model = yaml.safe_load(src.read_text(encoding="utf-8"))
    dst.parent.mkdir(parents=True, exist_ok=True)
    dst.write_text(render(model), encoding="utf-8")
    print(f"{dst} written from {src}")


if __name__ == "__main__":
    main()
