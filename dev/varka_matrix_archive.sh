#!/usr/bin/env bash
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
# Archives a build of the test classes for the fanned-out option matrix (VARKA-507), or unpacks
# one into a build directory:
#
#   dev/varka_matrix_archive.sh pack BUILD_DIR TAR      # BUILD_DIR as dev/varka_matrix.sh left it
#   dev/varka_matrix_archive.sh unpack TAR BUILD_DIR
#
# The archive holds the entries of the test classpath - the build's class directories and the
# dependencies' jars alike, but for the jars no Varka suite loads (the list in `pack`) - at its
# absolute path, which is the same on every GitHub runner, and
# the build directory's classpath and jvm.opts under the names `varka-classpath` and
# `varka-jvm.opts` at the archive's root, and its reach.tsv as `varka-reach.tsv` where it has one:
# the tests each configuration moves, which a configuration's bin reads (VarkaTestReach); and its
# harness-store/ as `varka-harness-store/` where that holds a file: the plans and Spark's answers
# the defaults' run wrote, which a configuration's bin reads (VarkaHarnessStore). Unpacking
# restores the entries where they were and
# writes those files into the build directory a bin reads with --skip-build --build-dir, so
# the pull request's Build, which builds into target/varka-scoped, and the matrix's own build,
# into target/varka-matrix, give the bins the same thing.
set -euo pipefail
cd "$(dirname "$0")/.."
case "${1:-}" in
  pack)
    build=${2:?usage: pack BUILD_DIR TAR} tar=${3:?usage: pack BUILD_DIR TAR}
    [ -s "$build/classpath" ] && [ -s "$build/jvm.opts" ] || {
      echo "no classpath or jvm.opts in $build" >&2; exit 1; }
    work=$(mktemp -d)
    trap 'rm -rf "$work"' EXIT
    cp "$build/jvm.opts" "$work/varka-jvm.opts"
    named=(varka-classpath varka-jvm.opts)
    if [ -s "$build/reach.tsv" ]; then
      cp "$build/reach.tsv" "$work/varka-reach.tsv"
      named+=(varka-reach.tsv)
    fi
    if compgen -G "$build/harness-store/harness-*.bin" > /dev/null; then
      mkdir "$work/varka-harness-store"
      cp "$build"/harness-store/harness-*.bin "$work/varka-harness-store/"
      named+=(varka-harness-store)
    fi
    # The jars no Varka suite loads, left out of the archive and of its classpath: sql's test
    # classpath carries them for Spark's own cloud, JDBC, UI and state-store tests, and they were
    # 849 of the archive's 1,440 MB, the AWS SDK bundle alone 686, which every bin downloaded
    # (VARKA-514, m8/results/VARKA-514-archive.txt). A suite that needs one fails in the matrix
    # with the class it could not find.
    unused='/software/amazon/awssdk/|/org/seleniumhq/|/org/rocksdb/|/databricks-jdbc/'
    unused+='|/oracle/database/jdbc/|/net/snowflake/|/com/ibm/db2/|/mssql-jdbc/|/mariadb/'
    unused+='|/org/postgresql/'
    tr ':' '\n' < "$build/classpath" | grep -v '^$' | grep -vE "$unused" \
      | while read -r e; do if [ -e "$e" ]; then echo "$e"; fi; done > "$work/entries"
    tr ':' '\n' < "$build/classpath" | grep -v '^$' | grep -vE "$unused" | paste -sd: \
      > "$work/varka-classpath"
    mkdir -p "$(dirname "$tar")"
    tar -cPf "$tar" -T "$work/entries" -C "$work" "${named[@]}"
    ls -l "$tar"
    # What the bins download, by kind, for the run's log.
    tar -tvPf "$tar" | awk '{ k = "other" }
      $6 ~ /\.jar$/ { k = ($6 ~ /coursier/) ? "dependency jars" : "jars built" }
      $6 ~ /test-classes/ { k = "test classes" }
      { sz[k] += $3; n[k]++ }
      END { for (k in sz) printf "%9.1f MB %7d files  %s\n", sz[k] / 1e6, n[k], k }' | sort -rn
    ;;
  unpack)
    tar=${2:?usage: unpack TAR BUILD_DIR} build=${3:?usage: unpack TAR BUILD_DIR}
    mkdir -p "$build"
    tar -xPf "$tar" -C "$build" --transform 's#^varka-classpath$#classpath#' \
      --transform 's#^varka-jvm\.opts$#jvm.opts#' --transform 's#^varka-reach\.tsv$#reach.tsv#' \
      --transform 's#^varka-harness-store#harness-store#'
    [ -s "$build/classpath" ] && [ -s "$build/jvm.opts" ] || {
      echo "$tar holds no classpath or jvm.opts" >&2; exit 1; }
    ;;
  *)
    echo "usage: dev/varka_matrix_archive.sh pack BUILD_DIR TAR | unpack TAR BUILD_DIR" >&2
    exit 2
    ;;
esac
