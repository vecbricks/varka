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
"""Which pinned digest of emitted_bytes.json moved against a base? (VARKA-352 2.2.)

  dev/varka_bytes_moved.py origin/master                    # the file as it is on disk
  dev/varka_bytes_moved.py origin/master --head HEAD        # the file as committed at HEAD
  dev/varka_bytes_moved.py origin/master --ignore option_arms

`sql/varka/emitted_bytes.json` pins what the emitter writes for the coverage rows and for the
seeded fuzz sequences. A change to the draws of a committed sequence, or to the emitter, moves
digests in it; a new type's sequence adds keys and moves none. This prints each key that has a
digest in both files and a different one, or that the base had and the file lost, as
`<path>  <base>  <now>`, where a path is the keys joined by dots and a fuzz block is
`blocks[<index>]`. A key only the file has is a new pin and is not printed.

The exit status is 1 when anything was printed, so the script is a check: the list must be empty
for every existing key, but `option_arms`, a digest over every shape that moves with any new
coverage row (pass `--ignore option_arms`).
"""

import argparse
import json
import os
import subprocess
import sys

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
FILE = "sql/varka/emitted_bytes.json"


def committed(rev):
    out = subprocess.run(["git", "show", f"{rev}:{FILE}"], cwd=ROOT, capture_output=True, text=True)
    if out.returncode != 0:
        sys.exit(f"{rev}:{FILE}: {out.stderr.strip()}")
    return json.loads(out.stdout)


def moved(path, base, now):
    """Yields (path, base value, now value) for each leaf `base` has that `now` lacks or changed."""
    if isinstance(base, dict):
        for key, value in base.items():
            sub = f"{path}.{key}" if path else key
            if isinstance(now, dict) and key in now:
                yield from moved(sub, value, now[key])
            else:
                yield sub, value, None
    elif isinstance(base, list):
        now = now if isinstance(now, list) else []
        for i, value in enumerate(base):
            if i < len(now):
                yield from moved(f"{path}[{i}]", value, now[i])
            else:
                yield f"{path}[{i}]", value, None
    elif base != now:
        yield path, base, now


def main():
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("base", help="the revision whose emitted_bytes.json is the reference")
    ap.add_argument("--head", help="a revision to read the file from, instead of the disk")
    ap.add_argument(
        "--ignore", action="append", default=[], metavar="PREFIX", help="a path prefix to skip"
    )
    args = ap.parse_args()
    args.ignore += ["generated_by", "description"]  # prose, not digests
    if args.head:
        now = committed(args.head)
    else:
        with open(os.path.join(ROOT, FILE), encoding="utf-8") as f:
            now = json.load(f)
    rows = [
        (p, b, n)
        for p, b, n in moved("", committed(args.base), now)
        if not any(p == i or p.startswith((i + ".", i + "[")) for i in args.ignore)
    ]
    for path, before, after in rows:
        print(f"{path}\t{before}\t{after if after is not None else '(gone)'}")
    sys.exit(1 if rows else 0)


if __name__ == "__main__":
    main()
