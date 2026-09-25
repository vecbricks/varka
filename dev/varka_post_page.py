#!/usr/bin/env python3
#
# Licensed to the Apache Software Foundation (ASF) under one or more
# contributor license agreements.  See the NOTICE file distributed with
# this work for additional information regarding copyright ownership.
# The ASF licenses this file to You under the Apache License, Version 2.0
# (the "License"); you may not use this file except in compliance with
# the License.  You may obtain a copy of the License at
#
#    http://www.apache.org/licenses/LICENSE-2.0
#
# Unless required by applicable law or agreed to in writing, software
# distributed under the License is distributed on an "AS IS" BASIS,
# WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
# See the License for the specific language governing permissions and
# limitations under the License.
#
# Render a post's Markdown and its figures into the page that gets published.

"""Render a Varka post from its Markdown source into a standalone web page.

    dev/varka_post_page.py sql/varka/plans/POST_MILESTONE_5.md --out target/post

The post is written as an ordinary Markdown file under `sql/varka/plans/`, and its figures
are the SVGs that `sql/varka/plans/figures/*.py` generate. This script is what turns the two
into the page that gets published: it converts the Markdown, inlines each referenced SVG so
the page carries its own drawings and fonts, wraps a `*Figure N.*` paragraph as that figure's
caption, and writes one `index.html` that depends on nothing but a Google Fonts stylesheet.

Keeping the page a build product rather than a hand-edited file is the point: the prose has
one home, the figures have one home, and neither can drift from what is published.

The page carries Open Graph tags so a shared link renders a card. `--og-image URL` sets the
card's picture, which has to be a raster image - social sites do not render SVG - so render
one figure first, for example:

    chromium --headless=new --window-size=900,600 \\
      --screenshot=card.png figures/svg/fig2-localtime-per-row.svg

Requires the `markdown` package (`pip install markdown`).
"""

import argparse
import os
import re
import shutil
import sys

STYLE = """
:root{--bg:#fbfaf7;--fg:#1f2328;--muted:#5d6672;--accent:#6741d9;--rule:#e3ddd2;
--code:#f1eee8;--fig:#ffffff}
@media (prefers-color-scheme:dark){:root:not([data-theme="light"]){--bg:#15171b;--fg:#e6e3dc;
--muted:#a1a7b0;--accent:#b197fc;--rule:#2b2f36;--code:#1f2329;--fig:#ffffff}}
:root[data-theme="dark"]{--bg:#15171b;--fg:#e6e3dc;--muted:#a1a7b0;--accent:#b197fc;
--rule:#2b2f36;--code:#1f2329;--fig:#ffffff}
*{box-sizing:border-box}
body{margin:0;background:var(--bg);color:var(--fg);font-family:Literata,Georgia,serif;
font-size:18px;line-height:1.6}
main{max-width:42rem;margin:0 auto;padding:2.5rem 16px 5rem}
h1{font-family:"Source Sans 3",system-ui,sans-serif;font-size:2.1rem;line-height:1.15;
text-wrap:balance;margin:0 0 1rem;letter-spacing:-.01em}
h2{font-family:"Source Sans 3",system-ui,sans-serif;font-size:1.45rem;margin:2.6rem 0 .8rem;
text-wrap:balance}
p{margin:0 0 1.1rem}
hr{border:0;border-top:1px solid var(--rule);margin:2rem 0}
code{font-family:"JetBrains Mono",ui-monospace,monospace;font-size:.86em;background:var(--code);
padding:.08em .35em;border-radius:4px}
pre{background:var(--code);padding:1rem;border-radius:8px;overflow-x:auto;font-size:.85rem;
line-height:1.5}
pre code{background:none;padding:0}
table{border-collapse:collapse;width:100%;font-size:.92rem;margin:0 0 1.2rem}
th,td{border-bottom:1px solid var(--rule);padding:.4rem .5rem;text-align:left}
td,th{font-variant-numeric:tabular-nums}
/* Alignment is the table's own: a column marked `--:` in the Markdown is right-aligned by
   the converter, so numbers line up and text columns stay readable. */
blockquote{margin:1.6rem 0;padding:.9rem 1.2rem;background:var(--code);border:1px solid var(--rule);
border-radius:8px}
blockquote p:last-child,blockquote ol:last-child{margin-bottom:0}
blockquote ol{margin:.2rem 0 0;padding-left:1.3rem}
.fig{margin:1.6rem 0 .6rem;background:var(--fig);border-radius:10px;padding:.6rem;
border:1px solid var(--rule);overflow-x:auto;-webkit-overflow-scrolling:touch}
/* A drawing authored at 900px shrinks to a third of that in a phone-width column, which
   puts its smallest labels under 5px. Below the floor the figure scrolls sideways inside
   its own box instead, and above 60rem it is allowed out past the text column. */
.fig svg{width:100%;min-width:600px;height:auto;display:block}
@media (min-width:60rem){.fig{width:52rem;margin-left:calc(50% - 26rem)}}
.cap{color:var(--muted);font-size:.92rem;line-height:1.5;margin-bottom:1.6rem}
a{color:var(--accent)}
"""

FONTS = (
    "https://fonts.googleapis.com/css2?family=Literata:ital,opsz,wght@0,7..72,400;"
    "0,7..72,600;1,7..72,400&family=Source+Sans+3:wght@600;700&"
    "family=JetBrains+Mono:wght@400&display=swap"
)


def inline_figures(html, source_dir):
    """Replace each `<p><img src=...></p>` with the referenced SVG's own markup."""

    def one(match):
        alt, src = match.group(1), match.group(2)
        path = os.path.join(source_dir, src)
        if not src.endswith(".svg"):
            return match.group(0)
        if not os.path.exists(path):
            # Loudly: a figure whose SVG is absent used to pass through as an <img> pointing
            # at nothing, so the page shipped with a hole in it and the build still exited 0.
            raise SystemExit(
                "missing figure %s - run the scripts in %s/figures" % (src, source_dir)
            )
        with open(path) as handle:
            svg = handle.read()
        # The drawings are authored at a fixed pixel size; the page scales them by width, so
        # the intrinsic size has to go or it wins over the stylesheet.
        svg = re.sub(r'<svg ([^>]*?)width="\d+" height="\d+"', r"<svg \1", svg, count=1)
        svg = svg.replace("<svg ", '<svg role="img" aria-label="%s" ' % alt, 1)
        return '<figure class="fig">%s</figure>' % svg

    return re.sub(r'<p><img alt="([^"]*)" src="([^"]+)" ?/?></p>', one, html)


def summarise(html, title):
    """The link card's blurb: the post's opening prose, cut at a sentence end if there is one
    in reach and at a word boundary otherwise - never mid-word, which is what a card shows."""
    # Tags become a space, then the spaces that landed before punctuation are taken back:
    # an inline <code> otherwise leaves "hour , minute , second" in the card's own blurb.
    plain = " ".join(re.sub(r"<[^>]+>", " ", html).split())
    plain = re.sub(r"\s+([,.;:!?)])", r"\1", plain)
    plain = re.sub(r"([(])\s+", r"\1", plain)
    if plain.startswith(title):
        plain = plain[len(title) :].strip()
    if len(plain) <= 220:
        return plain.replace('"', "&quot;")
    cut = plain[:220]
    stop = cut.rfind(". ")
    if stop > 80:
        return cut[: stop + 1].replace('"', "&quot;")
    space = cut.rfind(" ")
    if space > 80:
        cut = cut[:space]
    return (cut.rstrip(" ,;:-") + "...").replace('"', "&quot;")


def render(source, out_dir, og_image=""):
    import markdown

    source_dir = os.path.dirname(os.path.abspath(source))
    with open(source) as handle:
        text = handle.read()
    head, rest = text.split("\n", 1)
    title = head.lstrip("# ").strip().replace("`", "")
    # Whatever italic block opens the file under the title is the post's note to its own
    # editors - when it was drafted, what it still owes - and not something a reader of the
    # published page needs. Keyed on that shape rather than on one post's words.
    # Anchored at the top of `rest`: with re.S an unanchored pattern can start at the
    # first emphasised line anywhere in the document and swallow everything up to the next
    # asterisk, which would silently delete most of a post that opens without a note.
    body = head + "\n" + re.sub(r"\A\s*\*[^*].*?\*[ \t]*$", "", rest, count=1, flags=re.S | re.M)
    html = markdown.markdown(body, extensions=["fenced_code", "tables"])
    # The blurb is taken before the figures go in: an inlined SVG carries a <style> block
    # whose base64 font would otherwise be stripped into the card's text.
    summary = summarise(html, title)
    html = inline_figures(html, source_dir)
    # The substitution above matches one shape of Markdown's <img> output. Anything still
    # pointing at a figure got past it - a quoted alt text, an attribute order this pattern
    # does not know - and would ship as a broken image, so it fails here instead.
    left = re.search(r'<img[^>]+src="[^"]*figures/[^"]*"', html)
    if left:
        raise SystemExit("a figure was not inlined: " + left.group(0))
    html = html.replace("<p><em>Figure", '<p class="cap"><em>Figure')
    # A summary_large_image card with no image renders as a bare link, so the card kind
    # follows what is actually there, and the canonical URL goes out with it.
    og = ""
    card = "summary"
    if og_image:
        og = '<meta property="og:image" content="%s">' % og_image
        card = "summary_large_image"
        page_url = og_image.rsplit("/", 1)[0] + "/"
        og += '<meta property="og:url" content="%s">' % page_url
        og += '<link rel="canonical" href="%s">' % page_url
    page = (
        '<!DOCTYPE html><html lang="en"><head><meta charset="utf-8">'
        '<meta name="viewport" content="width=device-width,initial-scale=1">'
        "<title>%s</title>"
        '<meta name="description" content="%s">'
        '<meta property="og:type" content="article">'
        '<meta property="og:title" content="%s">'
        '<meta property="og:description" content="%s">%s'
        '<meta name="twitter:card" content="%s">'
        '<link rel="preconnect" href="https://fonts.googleapis.com">'
        '<link rel="stylesheet" href="%s">'
        "<style>%s</style></head><body><main>%s</main></body></html>"
        % (title, summary, title, summary, og, card, FONTS, STYLE, html)
    )
    os.makedirs(out_dir, exist_ok=True)
    out = os.path.join(out_dir, "index.html")
    with open(out, "w") as handle:
        handle.write(page)
    # The SVGs travel too, so the page can be opened from the output directory alone even
    # though this build inlines them.
    figures = os.path.join(source_dir, "figures", "svg")
    if os.path.isdir(figures):
        shutil.copytree(figures, os.path.join(out_dir, "figures"), dirs_exist_ok=True)
    return out, page


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("source", help="the post's Markdown file")
    parser.add_argument("--out", default="target/post", help="output directory")
    parser.add_argument("--og-image", default="", help="absolute URL of the link-card image")
    args = parser.parse_args()
    try:
        out, page = render(args.source, args.out, args.og_image)
    except ImportError:
        sys.exit("this needs the markdown package: pip install markdown")
    print("%s  %d bytes  %d figures" % (out, len(page), page.count('<figure class="fig">')))


if __name__ == "__main__":
    main()
