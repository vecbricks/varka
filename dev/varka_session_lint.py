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
"""
Flags a Varka test suite that checks expressions' answers row by row through a SparkSession,
where the expression harness checks them in a fraction of the time.

A query through a local session pays the analyzer, the optimizer, task serialization and a
task run on every check, and a suite that compares many expressions' rows that way spends
nearly all of its time there: the three suites that did, moved below the session, went from
472.2 s to 135.8 s on the laptop (sql/varka/plans/m8/results/VARKA-509-step4.txt). The rule
since is answers in the harness (VarkaExpressionHarness, VarkaDifferentialHarnessSuite,
VarkaHarnessCases) and, through a session, one test a path: a plan rule, a configuration
reaching the emitter, the cache, the fallback's accounting, an error as the user sees it.

The check counts a suite's answer checks. An answer check is a line outside a helper's body
that compares a session's rows: `checkAnswer`, `QueryTest.sameRows`, the shared traits' answer
comparisons, a `collect()` beside an assertion, or a call of a helper of the suite (or of the
shared test traits) whose body makes one, however deep. A check inside a loop counts once for
each element of the loop's collection where the source sizes it (`Seq(a, b, c)`, `0 until 4`,
a `val` of the file bound to such a literal or a block ending in one), and UNSIZED times where
it does not (the coverage table's rows read from a file, a list another class builds). A suite
that runs a session (it extends QueryTest, or calls a session's `.sql(`) is flagged when its
answer checks pass LIMIT, or when it registers tests in a loop over an unsized collection, a
test a case, which is the shape of a table checked row by row. A harness suite has no session
and is never flagged, however many dynamic tests it makes.

A test whose session checks are its point - one row a family through a path, a hundred tasks
of one shape through the cache - is headed by a marker comment with its reason, and its checks
are not counted; a marker above the class covers the whole suite. A marker without a reason
is a finding:

  // varka-session-lint: allow <why these answers are checked through a session>

  dev/varka_session_lint.py [--cached | --working-tree | --range A..B]  # the changed suites
  dev/varka_session_lint.py --all                 # every Varka suite of the tree
  dev/varka_session_lint.py FILE...               # these files; REV:PATH reads one from git
  dev/varka_session_lint.py --table [--all | FILE...]    # each suite's counts, flagged or not
  dev/varka_session_lint.py --explain [--all | FILE...]  # each answer check and its weight
  dev/varka_session_lint.py --selftest

Exit status is the number of findings.
"""

import os
import re
import subprocess
import sys
import tempfile

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))

# The answer checks a suite may make through a session before it is pointed at the harness:
# on the tree this check came with, the session suites left after the moves below the session
# make at most 35, and the time-arithmetic suite made 52 before its move
# (sql/varka/plans/m8/results/VARKA-529-session-lint.txt).
LIMIT = 40
# What a check in a loop over a collection the source does not size - a table read from a file,
# a list built in a block - counts for each such loop around it.
UNSIZED = 10
# A suite of the repository's naming, in a test tree; only these are checked.
SUITE = re.compile(r"(^|/)src/test/(scala|java)/.*/Varka\w*Suite\.(scala|java)$")
# The other Varka test sources of a tree, whose helpers the suites call.
SHARED = re.compile(r"(^|/)src/test/(scala|java)/.*/Varka\w*\.(scala|java)$")
MARKER = re.compile(r"//\s*varka-session-lint:\s*allow\b(.*)$")
# A file that runs a session.
SESSION = re.compile(
    r"\bextends\s+QueryTest\b|\bSharedSparkSession\b|\bVarkaSharedSessions\b"
    r"|\b(spark|varkaSpark|disabledSpark|session|\w+Session)\.sql\("
)
# A line that compares a session's rows by itself.
DIRECT = re.compile(
    r"\bcheckAnswer\s*\(|\bsameRows\s*\(|\bcheckSparkAnswer\w*\s*\(|\bcheckSparkRaisesTheSame\b"
)
COLLECT = re.compile(r"\.collect(AsList)?\(\)")
ASSERTS = re.compile(r"===|\bassert\w*\s*\(|\bassertEquals\s*\(|\bshouldBe\b")
# A Scala `def`, and in Java a method declaration: a type, a name and an open parameter list.
DEF = re.compile(
    r"^\s*(?:(?:private|protected|override|final|implicit|lazy)(?:\[\w+\])?\s+)*def\s+(\w+)"
)
JAVA_DEF = re.compile(
    r"^\s*(?:(?:public|private|protected|static|final|synchronized|default)\s+)*"
    r"(?:<[^>]*>\s+)?([\w.]+(?:<[^()]*>)?(?:\[\])*)\s+(\w+)\s*\([^;=]*$"
)
JAVA_KEYWORDS = {"if", "for", "while", "switch", "catch", "return", "new", "else", "throw"}
LITERAL = re.compile(
    r"^(?:Seq|List|Array|Set|Map|Vector|IndexedSeq|Iterator|List\.of|Set\.of|Map\.of|Arrays\.asList"
    r"|Stream\.of)\s*(?:\[[^\]]*\])?\s*\((.*)\)$",
    re.S,
)
RANGE = re.compile(r"^\(?\s*(-?\d+)\s+(until|to)\s+(-?\d+)\s*\)?$")


def git(*args, check=True):
    out = subprocess.run(["git", *args], cwd=ROOT, capture_output=True, text=True)
    if check and out.returncode != 0:
        raise RuntimeError(f"git {' '.join(args)}: {out.stderr.strip()}")
    return out.stdout


def read(spec):
    """A file of the tree, or REV:PATH from git."""
    if ":" in spec and not os.path.exists(os.path.join(ROOT, spec)):
        return git("show", spec)
    with open(os.path.join(ROOT, spec), encoding="utf-8", errors="replace") as f:
        return f.read()


def gap(text, i, j):
    """What stands for text[i:j] when it spans lines: its line breaks, and on the last line
    spaces up to j, so the code after it keeps its column."""
    breaks = text.count("\n", i, j)
    if not breaks:
        return ""
    return "\n" * breaks + " " * (j - text.rfind("\n", i, j) - 1)


def code_lines(text):
    """The text with comments removed and string contents emptied, line for line."""
    i, n = 0, len(text)
    buf = []
    while i < n:
        c = text[i]
        if text.startswith('"""', i):
            j = text.find('"""', i + 3)
            j = n if j < 0 else j + 3
            buf.append('""' + gap(text, i, j))
            i = j
        elif c == '"':
            j = i + 1
            while j < n and text[j] != '"' and text[j] != "\n":
                j += 2 if text[j] == "\\" else 1
            buf.append('""')
            i = j + 1
        elif text.startswith("//", i):
            j = text.find("\n", i)
            i = n if j < 0 else j
        elif text.startswith("/*", i):
            j = text.find("*/", i + 2)
            j = n if j < 0 else j + 2
            buf.append(gap(text, i, j))
            i = j
        elif c == "'" and i + 2 < n and text[i + 2] == "'":
            # A character literal, which may be a quote.
            buf.append("' '")
            i += 3
        else:
            buf.append(c)
            i += 1
    return "".join(buf).split("\n")


def indent(line):
    return len(line) - len(line.lstrip(" "))


def block_end(lines, i):
    """The index past the block a header at line i opens: the deeper lines that follow it."""
    base = indent(lines[i])
    j = i + 1
    while j < len(lines):
        s = lines[j]
        if s.strip() and indent(s) <= base:
            # A closing brace at the header's own indentation still belongs to it.
            if s.strip().startswith(("}", ")")):
                j += 1
            break
        j += 1
    return j


def balanced(text, start):
    """The index past the bracket group opening at text[start]."""
    depth = 0
    for k in range(start, len(text)):
        if text[k] in "([{":
            depth += 1
        elif text[k] in ")]}":
            depth -= 1
            if depth == 0:
                return k + 1
    return len(text)


def split_top(text, sep=","):
    parts, depth, cur = [], 0, []
    for c in text:
        if c in "([{":
            depth += 1
        elif c in ")]}":
            depth -= 1
        if c == sep and depth == 0:
            parts.append("".join(cur))
            cur = []
        else:
            cur.append(c)
    parts.append("".join(cur))
    return [p for p in parts if p.strip()]


def joined(lines, i):
    """Line i joined with the lines that close the brackets it opens."""
    text, j = lines[i], i
    while text.count("(") > text.count(")") and j + 1 < len(lines) and j - i < 40:
        j += 1
        text += " " + lines[j].strip()
    return text


class Suite:
    """One suite's answer checks: where they are, what each weighs, and what is marked."""

    def __init__(self, name, text, shared_helpers=frozenset()):
        self.name = name
        self.text = text
        self.lines = code_lines(text)
        self.session = bool(SESSION.search("\n".join(self.lines)))
        self.bindings = self._bindings()
        self.helpers = self._helpers(shared_helpers)
        self.markers = self._markers()
        self.sites = self._sites()
        counted = [x for x in self.sites if not x.marked]
        self.checks = sum(x.weight for x in counted)
        self.generated = sum(1 for x in counted if x.generated)

    def _bindings(self):
        """Each `val NAME = <expression>` of the file, for the size of a loop over NAME. A block
        stands for its last statement."""
        out = {}
        for i, line in enumerate(self.lines):
            m = re.match(
                r"^\s*(?:(?:private|protected|lazy|final|static)\s+)*(?:val|var|def|[\w<>]+)\s+"
                r"(\w+)\s*(?::.+?)?\s=(?!>)\s*(.*)$",
                line,
            )
            if not m:
                continue
            rhs = m.group(2).strip()
            if rhs == "{":
                end = block_end(self.lines, i)
                inner = indent(line) + 2
                last = None
                for j in range(i + 1, end - 1):
                    if self.lines[j].strip() and indent(self.lines[j]) == inner:
                        last = j
                text = joined(self.lines, last).strip() if last is not None else ""
            elif not rhs and i + 1 < len(self.lines):
                text = joined(self.lines, i + 1).strip()
            else:
                text = joined(self.lines, i)[m.start(2) :].strip()
            out.setdefault(m.group(1), []).append(text.rstrip(";").strip())
        return out

    def _defs(self):
        java = self.name.endswith(".java")
        for i, line in enumerate(self.lines):
            m = DEF.match(line)
            if m:
                yield m.group(1), i, block_end(self.lines, i)
                continue
            m = JAVA_DEF.match(line) if java else None
            if not m or m.group(1) in JAVA_KEYWORDS or m.group(2) in JAVA_KEYWORDS:
                continue
            # A test method is where checks are counted, not a helper that makes them.
            if i > 0 and re.match(
                r"^\s*@(Test|ParameterizedTest|TestFactory)\b", self.lines[i - 1]
            ):
                continue
            if re.search(r"\)\s*(throws [\w., ]+)?\s*\{\s*$", joined(self.lines, i)):
                yield m.group(2), i, block_end(self.lines, i)

    def _helpers(self, shared):
        """The methods whose bodies compare rows, directly or through another such method."""
        defs = list(self._defs())
        helpers = set(shared)
        changed = True
        while changed:
            changed = False
            for name, start, end in defs:
                if name in helpers:
                    continue
                if any(
                    self._compares(line, helpers, own=name) is not None
                    for line in self.lines[start:end]
                ):
                    helpers.add(name)
                    changed = True
        self.def_spans = [(s, e) for name, s, e in defs if name in helpers]
        return helpers

    def _compares(self, line, helpers, own=None):
        """The column of the first row comparison on the line, or None."""
        cols = []
        m = DIRECT.search(line)
        if m:
            cols.append(m.start())
        m = COLLECT.search(line)
        if m and ASSERTS.search(line):
            cols.append(m.start())
        for m in re.finditer(r"\b(\w+)\s*[\[(]", line):
            if m.group(1) in helpers and m.group(1) != own:
                cols.append(m.start())
                break
        return min(cols) if cols else None

    def _markers(self):
        """Each marker's reason and the lines it covers: the block it heads, or the file."""
        out = []
        raw = self.text.splitlines()
        for i, text in enumerate(raw):
            m = MARKER.search(text)
            if not m:
                continue
            t = i
            if not self.lines[i].strip():
                t = i + 1
                while t < len(self.lines) and not self.lines[t].strip():
                    t += 1
            if t >= len(self.lines) or re.search(r"\b(class|object|trait)\s+\w", self.lines[t]):
                span = (0, len(self.lines))
            else:
                span = (t, block_end(self.lines, t))
            out.append(Marker(i + 1, m.group(1).strip(), span))
        return out

    def size(self, expr, depth=0):
        """How many elements a loop over expr runs, or None where the source cannot say."""
        expr = expr.strip()
        expr = re.sub(
            r"(\.(zipWithIndex|toSeq|toList|sorted|reverse|distinct|keys|values"
            r"|keySet|asScala|stream\(\)))+$",
            "",
            expr,
        )
        parts = split_top(expr, "+") if "++" in expr else []
        if len(parts) > 1:
            sizes = [self.size(x.strip().lstrip("+"), depth) for x in parts]
            return None if None in sizes else sum(sizes)
        m = re.match(r"^(.*)\.take\((\d+)\)$", expr)
        if m:
            return int(m.group(2))
        m = re.match(r"^(.*)\.slice\((\d+),\s*(\d+)\)$", expr)
        if m:
            return max(0, int(m.group(3)) - int(m.group(2)))
        m = LITERAL.match(expr)
        if m:
            return len(split_top(m.group(1)))
        m = RANGE.match(expr)
        if m:
            lo, kind, hi = int(m.group(1)), m.group(2), int(m.group(3))
            return max(0, hi - lo + (1 if kind == "to" else 0))
        m = re.fullmatch(r"(?:\w+\.)?(\w+)", expr)
        if m and depth < 4:
            # A name, or a member of the file's own companion: the first of its bindings that
            # the source sizes.
            for text in self.bindings.get(m.group(1), []):
                if text != expr:
                    n = self.size(text, depth + 1)
                    if n is not None:
                        return n
        return None

    def _loops(self, i):
        """The loops whose header starts at line i: (size or None, body start, body end), the
        body's ends as (line, column) pairs."""
        line = self.lines[i]
        text = joined(self.lines, i)
        end = block_end(self.lines, i)
        out = []
        m = re.search(r"\bfor\s*\(", line)
        if m:
            close = balanced(text, m.end() - 1)
            inner = text[m.end() : close - 1]
            sizes = []
            if "<-" in inner:
                for gen in inner.split(";"):
                    if "<-" in gen:
                        sizes.append(self.size(re.split(r"\bif\b", gen.split("<-", 1)[1])[0]))
            elif ";" in inner:
                bound = re.search(r"<=?\s*(\d+)\s*;", inner)
                sizes.append(int(bound.group(1)) if bound else None)
            elif ":" in inner:
                sizes.append(self.size(inner.split(":", 1)[1]))
            body = (i, close) if close <= len(line) else (i + 1, 0)
            for size in sizes:
                out.append((size, body, (end, 0)))
        for m in re.finditer(r"\.(foreach|forEach|map|flatMap)\s*([({])", line):
            recv = self._receiver(line, m.start())
            if recv is None:
                continue
            open_at = m.end() - 1
            close = balanced(line, open_at)
            if m.group(2) == "(" and close <= len(line) and line[close - 1] == ")":
                out.append((self.size(recv), (i, open_at), (i, close)))
            else:
                out.append((self.size(recv), (i, open_at), (end, 0)))
        return out

    def _receiver(self, line, end):
        """The expression a `.foreach` at end is called on."""
        k = end
        while k > 0 and line[k - 1] in ")]":
            depth = 0
            for j in range(k - 1, -1, -1):
                if line[j] in ")]":
                    depth += 1
                elif line[j] in "([":
                    depth -= 1
                    if depth == 0:
                        k = j
                        break
            else:
                return None
            while k > 0 and (line[k - 1].isalnum() or line[k - 1] in "_."):
                k -= 1
        while k > 0 and (line[k - 1].isalnum() or line[k - 1] in "_."):
            k -= 1
        recv = line[k:end].strip()
        if not recv or recv.startswith("_"):
            return None
        return recv

    def _sites(self):
        loops = [loop for i in range(len(self.lines)) for loop in self._loops(i)]
        tests = [
            (i, block_end(self.lines, i))
            for i, line in enumerate(self.lines)
            if re.match(r"^\s*test\s*\(", line)
        ]
        out = []
        for i, line in enumerate(self.lines):
            if any(s <= i < e for s, e in self.def_spans):
                continue
            col = self._compares(line, self.helpers)
            if col is None:
                continue
            weight, generated = 1, False
            for size, start, stop in loops:
                if start <= (i, col) < stop:
                    weight *= UNSIZED if size is None else max(size, 1)
                    # A test registered inside the loop: the suite makes a test a case.
                    generated |= size is None and any(
                        start[0] <= t < i < e <= stop[0] + 1 for t, e in tests
                    )
            marked = any(s <= i < e for mk in self.markers for s, e in [mk.span])
            out.append(Site(i + 1, weight, generated, marked))
        return out

    def flagged(self):
        return self.session and (self.checks > LIMIT or self.generated > 0)


class Marker:
    def __init__(self, line, reason, span):
        self.line, self.reason, self.span = line, reason, span


class Site:
    def __init__(self, line, weight, generated, marked):
        self.line, self.weight, self.generated, self.marked = line, weight, generated, marked


def shared_helpers(paths):
    """The answer helpers the shared test traits define, for the suites that call them."""
    helpers = set()
    for p in paths:
        if SUITE.search(p):
            continue
        try:
            helpers |= Suite(p, read(p)).helpers
        except (OSError, RuntimeError):
            pass
    return frozenset(helpers)


def tree_files():
    return [p for p in git("ls-files", "sql").splitlines() if SHARED.search(p)]


def changed(argv):
    if argv and argv[0] == "--range":
        names = git("diff", "--name-only", "--diff-filter=ACMR", argv[1])
    elif argv and argv[0] == "--working-tree":
        names = git("diff", "--name-only", "--diff-filter=ACMR", "HEAD")
        names += git("ls-files", "--others", "--exclude-standard")
    else:
        names = git("diff", "--cached", "--name-only", "--diff-filter=ACMR")
    return [p for p in names.splitlines() if SUITE.search(p)]


def findings(s):
    out = [
        f"{s.name}:{mk.line}: session-lint: a varka-session-lint marker with no reason; say "
        "why these answers are checked through a session"
        for mk in s.markers
        if not mk.reason
    ]
    if s.flagged():
        where = (
            f"{s.generated} of them in tests generated from a collection the source does not size"
            if s.generated
            else f"more than {LIMIT}"
        )
        out.append(
            f"{s.name}: session-lint: {s.checks} answer checks through a session "
            f"({where}); check expressions' answers in the harness (VarkaHarnessCases, "
            "VarkaDifferentialHarnessSuite; sql/varka/ADDING_AN_EXPRESSION.md) and keep one "
            "session test a path, or head a path test that needs them with "
            "`// varka-session-lint: allow <reason>`"
        )
    return out


def run(argv):
    mode = argv[0] if argv[:1] in (["--table"], ["--explain"]) else None
    if mode:
        argv = argv[1:]
    if argv[:1] == ["--all"]:
        paths = [p for p in tree_files() if SUITE.search(p)]
    elif argv and argv[0] not in ("--cached", "--working-tree", "--range"):
        paths = list(argv)
    else:
        paths = changed(argv)
    helpers = shared_helpers(tree_files())
    found = []
    for p in paths:
        if not os.path.exists(os.path.join(ROOT, p)) and ":" not in p:
            continue
        s = Suite(p, read(p), helpers)
        if mode == "--table":
            state = "flagged" if s.flagged() else ("session" if s.session else "no session")
            marked = sum(1 for x in s.sites if x.marked)
            print(f"{s.checks:6d} {s.generated:4d} {marked:4d}  {state:10s} {p}")
        elif mode == "--explain":
            for x in s.sites:
                what = "generated" if x.generated else f"x{x.weight}"
                print(f"{p}:{x.line}: {what}{' (marked)' if x.marked else ''}")
        found += findings(s)
    if not mode:
        for f in found:
            print(f)
    return min(len(found), 100)


def selftest():
    """Planted suites: what must be flagged and what must not."""
    fails = 0
    head = "class X extends QueryTest with VarkaSharedSessions {\n"
    helper = (
        "  private def checkBoth(select: String): Unit = {\n"
        '    val q = s"SELECT $select FROM t"\n'
        "    checkAnswer(varkaSpark.sql(q), spark.sql(q))\n"
        "  }\n"
    )
    cases = [
        (
            "a session suite of answer checks over a generated list",
            head + helper + "  rows.foreach { r =>\n    test(r) {\n      checkBoth(r)\n"
            "    }\n  }\n}\n",
            True,
        ),
        (
            "a session suite looping its helper over more expressions than the limit",
            head
            + helper
            + '  test("all") {\n    for (a <- Seq('
            + ", ".join(f'"{k}"' for k in range(9))
            + '); b <- Seq("x", "y", "z", "w", "v")) {\n      checkBoth(s"$a + $b")\n    }\n'
            "  }\n}\n",
            True,
        ),
        (
            "a session suite of one path test",
            head + helper + '  test("the rule fuses") {\n    checkBoth("a + 1")\n'
            '    assertFused(varkaSpark.sql("SELECT a + 1 FROM t").queryExecution'
            ".executedPlan)\n  }\n}\n",
            False,
        ),
        (
            "a harness suite of a thousand dynamic tests",
            "class VarkaXHarnessSuite {\n  @TestFactory\n  Stream<DynamicTest> rows() {\n"
            "    return ROWS.stream().map(r -> dynamicTest(r, () -> harness.check(r)));\n"
            "  }\n}\n",
            False,
        ),
        (
            "a generated session suite with a marker and its reason",
            "// varka-session-lint: allow one row per family through the cache path\n"
            + head
            + helper
            + "  rows.foreach { r =>\n    test(r) { checkBoth(r) }\n  }\n}\n",
            False,
        ),
        (
            "a marker without a reason",
            "// varka-session-lint: allow\n" + head + helper + "}\n",
            True,
        ),
        (
            "a hundred checks of one shape in a test of their own",
            head + helper + '  test("one shape") {\n    (0 until 100).foreach { i =>\n'
            '      checkBoth(s"a + $i")\n    }\n  }\n}\n',
            True,
        ),
        (
            "the same test headed by a marker",
            head + helper + "  // varka-session-lint: allow a hundred tasks of one shape\n"
            '  test("one shape") {\n    (0 until 100).foreach { i =>\n'
            '      checkBoth(s"a + $i")\n    }\n  }\n}\n',
            False,
        ),
        (
            "a range mapped into one expression, which is one check",
            head + helper + '  test("wide") {\n'
            '    checkBoth((0 until 300).map(j => s"a$j").mkString("+"))\n  }\n}\n',
            False,
        ),
        (
            "a loop over an unsized list inside one test",
            head + helper + '  test("sections") {\n    for (s <- sections) {\n'
            "      checkBoth(s)\n    }\n  }\n}\n",
            False,
        ),
        (
            "a loop over a block that ends in a literal, sized by it",
            head
            + helper
            + "  private val fixtures = {\n    val a = 1\n    Seq("
            + ", ".join(f'"f{k}"' for k in range(50))
            + ")\n  }\n"
            + '  test("each fixture") {\n    fixtures.foreach { f =>\n      checkBoth(f)\n'
            "    }\n  }\n}\n",
            True,
        ),
    ]
    java = (
        "class VarkaJavaSessionSuite {\n  private void check(String q) {\n"
        "    assertEquals(spark.sql(q).collectAsList(), other.sql(q).collectAsList());\n  }\n"
        "  @Test\n  public void all() {\n"
        '    for (String a : List.of("1", "2", "3", "4", "5", "6", "7")) {\n'
        '      for (String b : List.of("x", "y", "z", "w", "v", "u", "t")) {\n'
        "        check(a + b);\n      }\n    }\n  }\n}\n"
    )
    cases.append(("a Java session suite over more expressions than the limit", java, True))
    for name, text, expect in cases:
        ext = "java" if name.startswith("a Java") else "scala"
        got = bool(findings(Suite(f"VarkaPlantedSuite.{ext}", text)))
        if got != expect:
            print(f"varka_session_lint selftest: {name}: expected {expect}, got {got}")
            fails += 1
    # The changed-file mode, in a repository of its own: a staged suite is read, and a file
    # that is not a suite is not.
    for key in [k for k in os.environ if k.startswith("GIT_")]:
        del os.environ[key]
    global ROOT
    saved = ROOT
    with tempfile.TemporaryDirectory() as tmp:
        try:
            ROOT = tmp
            git("init", "-q", ".")
            path = "sql/core/src/test/scala/x/VarkaPlantedSuite.scala"
            os.makedirs(os.path.join(tmp, os.path.dirname(path)))
            with open(os.path.join(tmp, path), "w") as f:
                f.write(cases[0][1])
            with open(os.path.join(tmp, "sql/core/src/test/scala/x/Other.scala"), "w") as f:
                f.write(cases[0][1])
            git("add", "-A")
            got = changed(["--cached"])
            if got != [path]:
                print(f"varka_session_lint selftest: the staged suites: expected [{path}], {got}")
                fails += 1
        finally:
            ROOT = saved
    if fails == 0:
        print(f"varka_session_lint selftest: ok ({len(cases) + 1} cases)")
    return fails


if __name__ == "__main__":
    if sys.argv[1:2] in (["-h"], ["--help"]):
        print(__doc__)
        sys.exit(0)
    if sys.argv[1:2] == ["--selftest"]:
        sys.exit(selftest())
    sys.exit(run(sys.argv[1:]))
