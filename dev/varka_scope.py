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
Classify a change by the files it touches, for the build's precondition (VARKA-160).

Reads repository-relative paths, one per line, on stdin and prints one word:
``spark`` (a file outside Varka's own changed, so Spark's module matrix runs), ``scoped``
(every file is Varka's and at least one is code: the Varka suites run, the matrix does not),
``docs`` (only the Varka documents changed) or ``none``. The rule lives in
``sparktestsupport.modules.varka_change_scope`` with its doctests; this is its command line.

    git diff --name-only BASE HEAD | dev/varka_scope.py
"""

import os
import sys

sys.path.insert(0, os.path.join(os.path.dirname(os.path.abspath(__file__))))
from sparktestsupport.modules import varka_change_scope

if __name__ == "__main__":
    print(varka_change_scope(sys.stdin.read().split()))
