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

"""A small hand-drawn SVG renderer for the milestone posts' figures, in the manner of rough.js
and Excalidraw: wobbly double strokes, hachure fills, a handwriting font. Each figure is a
short script beside this file that builds a `Rough` and calls `finish`, which embeds a subset
of the font so the SVG renders the same everywhere. Deterministic per seed, so a figure
re-renders identically until its script changes.

Run any `fig*.py` from this directory; the SVG lands in `svg/`. The font (Patrick Hand, OFL)
is fetched into `fonts/` on first use if it is not there.

Needs `fonttools` and `brotli`: the glyphs each figure uses are subset out of the font and
embedded as woff2, so the drawing carries its own lettering and renders the same anywhere.
"""

import base64
import math
import os
import random
import subprocess
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
FONT_NAME = "Patrick Hand"
FONT_FILE = os.path.join(HERE, "fonts", "PatrickHand-Regular.ttf")
FONT_URL = "https://github.com/google/fonts/raw/main/ofl/patrickhand/PatrickHand-Regular.ttf"

STROKE = "#1e1e1e"
NOTE = "#6741d9"
# The fills are drawn as thin diagonal strokes rather than solid areas, so a pale colour
# reads as almost nothing on a page - these sit a few steps up the scale, bright enough to
# carry the hatching while leaving near-black label text legible on top of it.
PALETTE = dict(
    blue="#4dabf7",
    green="#51cf66",
    yellow="#ffd43b",
    red="#ff8787",
    violet="#9775fa",
    orange="#ffa94d",
    grey="#ced4da",
    white="#ffffff",
)


class Rough:
    def __init__(self, width, height, seed=1, roughness=1.0, bg="#ffffff"):
        self.w, self.h = width, height
        self.rnd = random.Random(seed)
        self.r = roughness
        self.bg = bg
        self.parts = []
        self.font_file = None

    # -- strokes ---------------------------------------------------------------------------
    def _o(self, k=1.0):
        return (self.rnd.random() * 2 - 1) * self.r * 2.0 * k

    def _line_path(self, x1, y1, x2, y2, k=1.0):
        """One wobbly pass: a cubic with jittered ends and control points at 50% and 75%."""
        dx, dy = x2 - x1, y2 - y1
        f = min(1.0, math.hypot(dx, dy) / 200.0) + 0.3
        pts = [
            (x1, y1),
            (x1 + dx * 0.5, y1 + dy * 0.5),
            (x1 + dx * 0.75, y1 + dy * 0.75),
            (x2, y2),
        ]
        j = [(px + self._o(k * f), py + self._o(k * f)) for px, py in pts]
        return "M%.1f %.1f C%.1f %.1f, %.1f %.1f, %.1f %.1f" % (j[0] + j[1] + j[2] + j[3])

    def _stroke(self, d, color=STROKE, width=1.6, dash=None):
        extra = ' stroke-dasharray="%s"' % dash if dash else ""
        self.parts.append(
            '<path d="%s" fill="none" stroke="%s" stroke-width="%.1f" '
            'stroke-linecap="round" stroke-linejoin="round"%s/>' % (d, color, width, extra)
        )

    def line(self, x1, y1, x2, y2, color=STROKE, width=1.6, dash=None):
        self._stroke(self._line_path(x1, y1, x2, y2, 1.0), color, width, dash)
        self._stroke(self._line_path(x1, y1, x2, y2, 0.5), color, width, dash)

    def arrow(self, x1, y1, x2, y2, color=STROKE, width=1.6, head=12, dash=None):
        self.line(x1, y1, x2, y2, color, width, dash)
        a = math.atan2(y2 - y1, x2 - x1)
        for s in (1, -1):
            hx = x2 - head * math.cos(a + s * 0.5)
            hy = y2 - head * math.sin(a + s * 0.5)
            self.line(x2, y2, hx, hy, color, width)

    def curve(self, pts, color=STROKE, width=1.6, arrow=False, monotone=False):
        """A smooth freehand curve through `pts`, drawn twice.

        By default a Catmull-Rom spline, which suits shapes. For data, `monotone=True` draws a
        monotone cubic (Fritsch-Carlson) through points ordered by x: it never rises above or
        dips below its neighbouring points between them, so a chart's line cannot suggest a
        value the data does not hold."""
        for k in (1.0, 0.5):
            p = [(x + self._o(k), y + self._o(k)) for x, y in pts]
            d = "M%.1f %.1f" % p[0]
            if monotone:
                for c1, c2, end in self._monotone(p):
                    d += " C%.1f %.1f, %.1f %.1f, %.1f %.1f" % (c1 + c2 + end)
            else:
                p = [p[0]] + p + [p[-1]]
                for i in range(1, len(p) - 2):
                    p0, p1, p2, p3 = p[i - 1], p[i], p[i + 1], p[i + 2]
                    c1 = (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6)
                    c2 = (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
                    d += " C%.1f %.1f, %.1f %.1f, %.1f %.1f" % (c1 + c2 + p2)
            self._stroke(d, color, width)
        if arrow:
            (x1, y1), (x2, y2) = pts[-2], pts[-1]
            a = math.atan2(y2 - y1, x2 - x1)
            for s in (1, -1):
                self.line(
                    x2,
                    y2,
                    x2 - 12 * math.cos(a + s * 0.5),
                    y2 - 12 * math.sin(a + s * 0.5),
                    color,
                    width,
                )

    @staticmethod
    def _monotone(p):
        """Bezier segments of the monotone cubic Hermite spline through `p` (x increasing)."""
        n = len(p)
        dx = [p[i + 1][0] - p[i][0] for i in range(n - 1)]
        m = [(p[i + 1][1] - p[i][1]) / dx[i] for i in range(n - 1)]
        t = (
            [m[0]]
            + [0.0 if m[i - 1] * m[i] <= 0 else (m[i - 1] + m[i]) / 2 for i in range(1, n - 1)]
            + [m[-1]]
        )
        for i in range(n - 1):
            if m[i] == 0:
                t[i] = t[i + 1] = 0.0
                continue
            a, b = t[i] / m[i], t[i + 1] / m[i]
            if a * a + b * b > 9:
                tau = 3 / math.sqrt(a * a + b * b)
                t[i], t[i + 1] = tau * a * m[i], tau * b * m[i]
        for i in range(n - 1):
            h = dx[i] / 3
            yield (
                (p[i][0] + h, p[i][1] + t[i] * h),
                (p[i + 1][0] - h, p[i + 1][1] - t[i + 1] * h),
                p[i + 1],
            )

    # -- fills -----------------------------------------------------------------------------
    def _hachure(self, poly, fill, gap=4.2, angle=-41.0, width=1.5):
        """Parallel lines at `angle`, clipped to the convex polygon `poly`."""
        a = math.radians(angle)
        ux, uy = math.cos(a), math.sin(a)
        nx, ny = -uy, ux
        ds = [px * nx + py * ny for px, py in poly]
        c = min(ds) + gap * self.rnd.random()
        hi = max(ds)
        n = len(poly)
        while c < hi:
            hits = []
            for i in range(n):
                (ax, ay), (bx, by) = poly[i], poly[(i + 1) % n]
                da, db = ax * nx + ay * ny - c, bx * nx + by * ny - c
                if (da <= 0 < db) or (db <= 0 < da):
                    t = da / (da - db)
                    px, py = ax + (bx - ax) * t, ay + (by - ay) * t
                    hits.append((px * ux + py * uy, px, py))
            if len(hits) >= 2:
                hits.sort()
                (_, x1, y1), (_, x2, y2) = hits[0], hits[-1]
                self._stroke(self._line_path(x1, y1, x2, y2, 0.6), fill, width)
            c += gap + self._o(0.3)

    # -- shapes ----------------------------------------------------------------------------
    def rect(
        self,
        x,
        y,
        w,
        h,
        fill=None,
        color=STROKE,
        width=1.6,
        label=None,
        size=20,
        label_color=STROKE,
        solid=False,
    ):
        poly = [(x, y), (x + w, y), (x + w, y + h), (x, y + h)]
        if fill:
            col = PALETTE.get(fill, fill)
            if solid:
                pts = " ".join(
                    "%.1f,%.1f" % (px + self._o(0.5), py + self._o(0.5)) for px, py in poly
                )
                self.parts.append('<polygon points="%s" fill="%s" stroke="none"/>' % (pts, col))
            else:
                self._hachure(poly, col)
        for i in range(4):
            (x1, y1), (x2, y2) = poly[i], poly[(i + 1) % 4]
            self.line(x1, y1, x2, y2, color, width)
        if label:
            self.text(x + w / 2, y + h / 2, label, size=size, anchor="middle", color=label_color)

    def ellipse(self, cx, cy, rx, ry, fill=None, color=STROKE, width=1.6, label=None, size=20):
        if fill:
            poly = [
                (cx + rx * math.cos(t), cy + ry * math.sin(t))
                for t in [i * 2 * math.pi / 40 for i in range(40)]
            ]
            self._hachure(poly, PALETTE.get(fill, fill))
        for k in (1.0, 0.6):
            n = 24
            start = self.rnd.random() * 2 * math.pi
            pts = [
                (
                    cx + (rx + self._o(k)) * math.cos(start + i * 2 * math.pi / n),
                    cy + (ry + self._o(k)) * math.sin(start + i * 2 * math.pi / n),
                )
                for i in range(n + 3)
            ]
            # From pts[1], as `curve` does: the loop's first cubic ends at pts[2] and its
            # control points belong to the pts[1] -> pts[2] segment, so opening at pts[0]
            # skips a point and draws the first arc as a chord.
            d = "M%.1f %.1f" % pts[1]
            for i in range(1, len(pts) - 2):
                p0, p1, p2, p3 = pts[i - 1], pts[i], pts[i + 1], pts[i + 2]
                c1 = (p1[0] + (p2[0] - p0[0]) / 6, p1[1] + (p2[1] - p0[1]) / 6)
                c2 = (p2[0] - (p3[0] - p1[0]) / 6, p2[1] - (p3[1] - p1[1]) / 6)
                d += " C%.1f %.1f, %.1f %.1f, %.1f %.1f" % (c1 + c2 + p2)
            self._stroke(d, color, width)
        if label:
            self.text(cx, cy, label, size=size, anchor="middle")

    def text(self, x, y, s, size=20, anchor="start", color=STROKE, rotate=0):
        lines = s.split("\n")
        lh = size * 1.25
        y0 = y - lh * (len(lines) - 1) / 2
        rot = ' transform="rotate(%.1f %.1f %.1f)"' % (rotate, x, y) if rotate else ""
        for i, ln in enumerate(lines):
            ln = ln.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
            # Leading spaces carry indentation in code samples; SVG collapses them otherwise.
            lead = len(ln) - len(ln.lstrip(" "))
            ln = "&#160;" * lead + ln[lead:]
            # A halo, painted under the glyphs: the fills are hachure lines and a label
            # sitting on one competes with them stroke for stroke. Drawing the text once in
            # the figure's own background colour, thickened, and again in its own colour on
            # top clears just enough room around each glyph to read it, without a plate that
            # would box the label in.
            self.parts.append(
                '<text x="%.1f" y="%.1f" font-family="%s" font-size="%d" text-anchor="%s" '
                'fill="%s" stroke="%s" stroke-width="%.1f" stroke-linejoin="round" '
                'paint-order="stroke fill" dominant-baseline="middle"%s>%s</text>'
                % (
                    x,
                    y0 + i * lh,
                    FONT_NAME,
                    size,
                    anchor,
                    color,
                    self.bg,
                    max(3.0, size * 0.28),
                    rot,
                    ln,
                )
            )

    def note(self, x, y, s, size=18, color=NOTE):
        """A handwritten aside with a squiggle under its last line."""
        self.text(x, y, s, size=size, color=color)
        w = len(s.split("\n")[-1]) * size * 0.45
        yy = y + size * 0.75 * (s.count("\n") + 1)
        d = "M%.1f %.1f" % (x, yy)
        px = x
        while px < x + w:
            d += " q4 -3 8 0 t8 0"
            px += 16
        self._stroke(d, color, 1.2)

    # -- output ----------------------------------------------------------------------------
    def svg(self):
        style = ""
        if self.font_file:
            data = base64.b64encode(open(self.font_file, "rb").read()).decode()
            style = (
                '<style>@font-face{font-family:"%s";src:url(data:font/woff2;base64,%s) '
                'format("woff2");}</style>' % (FONT_NAME, data)
            )
        return (
            '<svg xmlns="http://www.w3.org/2000/svg" width="%d" height="%d" '
            'viewBox="0 0 %d %d">%s<rect width="100%%" height="100%%" fill="%s"/>%s</svg>'
            % (self.w, self.h, self.w, self.h, style, self.bg, "".join(self.parts))
        )


LICENCE = """<!--
  Licensed to the Apache Software Foundation (ASF) under one or more
  contributor license agreements.  See the NOTICE file distributed with
  this work for additional information regarding copyright ownership.
  The ASF licenses this file to You under the Apache License, Version 2.0
  (the "License"); you may not use this file except in compliance with
  the License.  You may obtain a copy of the License at

     http://www.apache.org/licenses/LICENSE-2.0

  Unless required by applicable law or agreed to in writing, software
  distributed under the License is distributed on an "AS IS" BASIS,
  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
  See the License for the specific language governing permissions and
  limitations under the License.

  Generated by sql/varka/plans/figures/%s - edit that script, not this file.

  The lettering is a subset of %s, Copyright (c) 2010-2012 Patrick
  Wagesreiter, licensed under the SIL Open Font License 1.1, reproduced in
  this directory as OFL.txt and at https://openfontlicense.org.
-->
"""


def finish(r, name):
    """Subset the font to the glyphs the figure uses, embed it, and write `out/<name>.svg`."""
    if not os.path.exists(FONT_FILE):
        os.makedirs(os.path.dirname(FONT_FILE), exist_ok=True)
        urllib.request.urlretrieve(FONT_URL, FONT_FILE)
    out_dir = os.path.join(HERE, "svg")
    os.makedirs(out_dir, exist_ok=True)
    chars = sorted(set(c for c in "".join(r.parts) if c.isprintable()))
    sub = os.path.join(out_dir, name + ".woff2")
    subprocess.run(
        [
            sys.executable,
            "-m",
            "fontTools.subset",
            FONT_FILE,
            "--text=" + "".join(chars),
            "--flavor=woff2",
            "--output-file=" + sub,
        ],
        check=True,
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    r.font_file = sub
    path = os.path.join(out_dir, name + ".svg")
    with open(path, "w") as f:
        # The drawing carries its own licence and its own provenance: it is a build product
        # that gets published on its own, and a reader who saves one should be able to tell
        # which script drew it and what the lettering inside it is.
        f.write(LICENCE % (os.path.basename(sys.argv[0]), FONT_NAME))
        f.write(r.svg())
    os.remove(sub)
    print(path, os.path.getsize(path), "bytes")
