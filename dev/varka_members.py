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
"""The member map of Java and Scala files: every member of each top-level type, with its
line range, its doc comment's first sentence, and the other listed members it calls.

    dev/varka_members.py FILE...               # a table, one member per line
    dev/varka_members.py FILE... --callees     # and what each one calls
    dev/varka_members.py FILE... --json        # the same, for a script

What it is for: the inventory of a refactor or a port. A plan that moves code lists the
members it moves, and a list written from memory names members that do not exist; this one is
read from the files. Given several files, a member's callees are looked up across all of them,
so the map shows which calls cross from one file to another. `dev/varka_callgraph.py` groups
the same map into named sets and prints only the calls that cross between them.

How it reads a file: it strips comments and string literals, tracks brace depth, and takes a
declaration that starts at depth one inside a top-level type as a member, so nested types
count once and their insides do not. A member runs from its doc comment and annotations down
to the line before the next member's. Callees are the other members' names appearing as
words in the member's code, which over-reports an overloaded or common name and never
misses a call; it is an inventory, not a compiler.
"""

import argparse
import json
import os
import re
import sys
from dataclasses import asdict, dataclass, field


@dataclass
class Member:
    file: str
    owner: str
    name: str
    kind: str
    first: int  # first line, 1-based, including the doc comment and annotations
    decl: int  # the declaration's own line
    last: int
    doc: str
    callees: list = field(default_factory=list)

    @property
    def qualified(self):
        return f"{self.owner}.{self.name}"


def code_lines(text, scala):
    """`text`'s lines with comments and string contents blanked, lengths kept, so a column in
    the result is a column in the source.

    >>> code_lines('int a = 1; // one {\\nString s = "}"; /* { */ int b;', False)
    ['int a = 1;         ', 'String s = " "; /*   */ int b;']
    """
    out, i, n = [], 0, len(text)
    buf = []
    state = None  # None, "line", "block", '"', "'", '"""'
    while i < n:
        c = text[i]
        nxt = text[i : i + 3]
        if c == "\n":
            out.append("".join(buf))
            buf = []
            if state == "line":
                state = None
            i += 1
            continue
        if state is None:
            if text.startswith("//", i):
                state = "line"
                buf.append("  ")
                i += 2
                continue
            if text.startswith("/*", i):
                state = "block"
                buf.append("/*")
                i += 2
                continue
            if nxt == '"""':
                state = '"""'
                buf.append('"""')
                i += 3
                continue
            if c == '"' or (c == "'" and not scala) or (c == "'" and _scala_char(text, i)):
                state = c
                buf.append(c)
                i += 1
                continue
            buf.append(c)
            i += 1
            continue
        if state == "line":
            buf.append(" ")
            i += 1
            continue
        if state == "block":
            if text.startswith("*/", i):
                state = None
                buf.append("*/")
                i += 2
            else:
                buf.append(" ")
                i += 1
            continue
        if state == '"""':
            if nxt == '"""':
                state = None
                buf.append('"""')
                i += 3
            else:
                buf.append(" ")
                i += 1
            continue
        if c == "\\" and i + 1 < n and text[i + 1] != "\n":
            buf.append("  ")
            i += 2
            continue
        if c == state:
            state = None
            buf.append(c)
        else:
            buf.append(" ")
        i += 1
    out.append("".join(buf))
    return out


def _scala_char(text, i):
    """Whether the quote at `i` opens a Scala character literal ('a', '\\n'), not a symbol."""
    return re.match(r"'(?:\\.|[^'\\\n])'", text[i : i + 4]) is not None


JAVA_DECL = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:(?:public|protected|private|static|final|abstract|sealed|non-sealed|default|"
    r"synchronized|native|transient|volatile|strictfp)\s+)*"
    r"(?:(?P<type>class|interface|enum|record|@interface)\s+(?P<tname>\w+)"
    r"|(?:<[^>]*>\s*)?[\w.$<>\[\], ?]+?\s+(?P<mname>\w+)\s*\("
    r"|[\w.$<>\[\], ?]+?\s+(?P<fname>\w+)\s*(?:=|;|,))"
)
SCALA_DECL = re.compile(
    r"^\s*(?:@\w+(?:\([^)]*\))?\s+)*"
    r"(?:(?:private|protected)(?:\[\w+\])?\s+|(?:override|final|implicit|lazy|abstract|"
    r"sealed|case|inline)\s+)*"
    r"(?P<kw>def|val|var|type|class|object|trait)\s+(?P<name>[\w$]+|`[^`]+`)"
)
JAVA_KEYWORDS = {"return", "new", "throw", "else", "case", "if", "for", "while", "switch", "do"}


def declaration(line, scala):
    """The (kind, name) a code line declares, or None.

    >>> declaration("  private static int emitLanes(VarkaEmitOptions o, Lane lane) {", False)
    ('method', 'emitLanes')
    >>> declaration("  record ArmStep(IfElse node, boolean thenBranch) { }", False)
    ('record', 'ArmStep')
    >>> declaration("  public static final int MAX_INPUTS = 64;", False)
    ('field', 'MAX_INPUTS')
    >>> declaration("    return foo(x);", False) is None
    True
    >>> declaration("  private[codegen] def compileNode(", True)
    ('def', 'compileNode')
    >>> declaration("  private lazy val (columns: Seq[Attribute], rows) = {", True) is None
    True
    """
    if scala:
        m = SCALA_DECL.match(line)
        if not m:
            return None
        return (m.group("kw"), m.group("name").strip("`"))
    m = JAVA_DECL.match(line)
    if not m:
        return None
    if m.group("tname"):
        return (m.group("type"), m.group("tname"))
    stripped = line.strip().split()[0] if line.strip() else ""
    if stripped in JAVA_KEYWORDS:
        return None
    if m.group("mname"):
        return ("method", m.group("mname"))
    return ("field", m.group("fname"))


def members_of(path, text=None):
    """The members of every top-level type in `path` (or in `text`, when given).

    >>> src = '''package p;
    ... /** A facade. */
    ... public final class F {
    ...   /** The limit. More words. */
    ...   static final int MAX = 4;
    ...
    ...   // a note
    ...   @Override
    ...   public String toString() { return helper(); }
    ...
    ...   private static String helper() {
    ...     if (MAX > 3) { return "}"; }
    ...     return "";
    ...   }
    ...
    ...   record Step(int a) { }
    ...
    ...   F(int x) { }
    ...
    ...   static F of(int a,
    ...       int wrapped, String alsoWrapped) {
    ...     return new F(a);
    ...   }
    ... }
    ... '''
    >>> for m in members_of("F.java", src):
    ...     print(m.qualified, m.kind, m.first, m.decl, m.last, repr(m.doc))
    F.MAX field 4 5 5 'The limit.'
    F.toString method 7 9 9 ''
    F.helper method 11 11 14 ''
    F.Step record 16 16 16 ''
    F.F constructor 18 18 18 ''
    F.of method 20 20 23 ''
    """
    if text is None:
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
    scala = path.endswith(".scala")
    raw = text.split("\n")
    code = code_lines(text, scala)
    depth_at, parens_at = [], []
    depth = parens = 0
    for line in code:
        depth_at.append(depth)
        parens_at.append(parens)
        depth += line.count("{") - line.count("}")
        parens += line.count("(") - line.count(")")
    found = []
    owner = None
    for i, line in enumerate(code):
        d = depth_at[i]
        if d == 0:
            decl = declaration(line, scala)
            if decl and decl[0] in ("class", "interface", "enum", "record", "object", "trait"):
                owner = decl[1]
            continue
        # A line inside an open parenthesis continues a declaration, such as a wrapped
        # parameter list, and never starts one.
        if d == 1 and owner is not None and parens_at[i] == 0:
            decl = declaration(line, scala)
            if (
                decl is None
                and not scala
                and re.match(
                    r"^\s*(?:(?:public|protected|private)\s+)?" + re.escape(owner) + r"\s*\(", line
                )
            ):
                decl = ("constructor", owner)
            if decl and not line.lstrip().startswith("}"):
                found.append([owner, decl[1], decl[0], i])
    result = []
    for k, (own, name, kind, decl_idx) in enumerate(found):
        first = _lead_start(raw, code, decl_idx)
        if k + 1 < len(found) and found[k + 1][0] == own:
            end = _lead_start(raw, code, found[k + 1][3]) - 1
        else:
            end = _type_end(code, depth_at, decl_idx)
        while end > decl_idx and not raw[end].strip():
            end -= 1
        if kind == "method" and name == own:
            kind = "constructor"
        result.append(
            Member(
                path,
                own,
                name,
                kind,
                first + 1,
                decl_idx + 1,
                end + 1,
                _doc_summary(raw, first, decl_idx),
            )
        )
    return result


def _lead_start(raw, code, decl_idx):
    """The first line of what belongs to the declaration at `decl_idx`: its doc comment,
    annotations and line comments directly above it."""
    i = decl_idx - 1
    while i >= 0:
        s = raw[i].strip()
        if s.startswith("@") or s.startswith("//"):
            i -= 1
            continue
        if s.endswith("*/"):
            while i >= 0 and "/*" not in raw[i]:
                i -= 1
            i -= 1
            continue
        break
    return i + 1


def _type_end(code, depth_at, decl_idx):
    """The last line before the enclosing type's closing brace."""
    i = decl_idx + 1
    while i < len(code) and not (depth_at[i] == 1 and code[i].strip().startswith("}")):
        if depth_at[i] == 0:
            break
        i += 1
    return i - 1


def _doc_summary(raw, first, decl_idx):
    block = [raw[i] for i in range(first, decl_idx) if not raw[i].strip().startswith("@")]
    text = " ".join(line.strip() for line in block)
    m = re.search(r"/\*\*(.*?)\*/", text)
    if not m:
        return ""
    body = re.sub(r"\s*\*\s*", " ", m.group(1)).strip()
    sentence = re.split(r"(?<=[.?!])\s", body, maxsplit=1)[0]
    return sentence


def with_callees(members, texts, fields=False):
    """Fills each member's callees: the other members' names used as words in its code.

    Plain fields are left out unless `fields` is set, because an instance field is usually
    named like the locals that shadow it (`outputs`, `options`) and would make every method
    appear to call it; constants, being upper case, stay in.
    """
    names = {}
    for m in members:
        if m.kind in ("field", "val", "var") and not fields and not m.name.isupper():
            continue
        if m.kind == "constructor":
            continue
        names.setdefault(m.name, []).append(m)
    for m in members:
        code = texts[m.file]
        body = "\n".join(code[m.decl : m.last])  # after the declaration line itself
        words = set(re.findall(r"[A-Za-z_$][\w$]*", body))
        m.callees = sorted(
            other.qualified for w in words & set(names) for other in names[w] if other is not m
        )
    return members


def load(paths, fields=False):
    members, texts = [], {}
    for path in paths:
        with open(path, encoding="utf-8") as handle:
            text = handle.read()
        texts[path] = code_lines(text, path.endswith(".scala"))
        members.extend(members_of(path, text))
    return with_callees(members, texts, fields)


def main():
    parser = argparse.ArgumentParser(description=__doc__.split("\n\n")[1])
    parser.add_argument("files", nargs="+")
    parser.add_argument("--callees", action="store_true", help="list what each member calls")
    parser.add_argument("--json", action="store_true", help="print JSON")
    parser.add_argument("--fields", action="store_true", help="count plain fields as callees")
    args = parser.parse_args()
    for path in args.files:
        if not os.path.exists(path):
            sys.exit(f"no such file: {path}")
    members = load(args.files, args.fields)
    if args.json:
        print(json.dumps([asdict(m) for m in members], indent=1))
        return
    for m in members:
        size = m.last - m.first + 1
        where = f"{os.path.basename(m.file)}:{m.first}-{m.last}"
        print(f"{m.qualified:44} {m.kind:9} {where:34} {size:>5}  {m.doc[:70]}")
        if args.callees and m.callees:
            print(f"{'':44}   calls {', '.join(m.callees)}")


if __name__ == "__main__":
    main()
