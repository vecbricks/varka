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
# Build hsdis - the disassembler plugin HotSpot's -XX:+PrintAssembly needs -
# without building a JDK. The assembly suite (task 31) and dev/varka_emit.sh
# --asm cancel or print hex without it; SKILLS.md's recipe went through a full
# fastdebug build, which this replaces. Needs gcc and libcapstone-dev
# (apt install libcapstone-dev); the JDK's hsdis source is one C file plus a
# header, fetched by a sparse clone when no --src tree is given.
#
#   dev/varka_hsdis_build.sh                       # clone src/utils/hsdis, build to ~/hsdis
#   dev/varka_hsdis_build.sh --src ~/jdk --out ~/hsdis
#
# Prints the export to put in your shell; the gate and the emit tool also look
# in ~/hsdis on their own.
set -euo pipefail
# Usage text is found rather than numbered: a hard-coded range silently truncates as the
# comment above it grows, which had already happened to four of these scripts. Ends at the
# first line that is not a comment.
usage() { sed -n '17,/^[^#]/p' "$0" | sed '$d'; exit "${1:-2}"; }

src=""; out="$HOME/hsdis"; tag="${VARKA_JDK_TAG:-jdk-25-ga}"
while [ "$#" -gt 0 ]; do
  case "$1" in
    -h|--help) usage 0 ;;
    --src) src="$2"; shift 2 ;;
    --out) out="$2"; shift 2 ;;
    *) usage ;;
  esac
done
command -v gcc > /dev/null || { echo "gcc not found" >&2; exit 1; }
[ -f /usr/include/capstone/capstone.h ] || { echo "libcapstone-dev not installed" >&2; exit 1; }

if [ -z "$src" ]; then
  src="$(mktemp -d)/jdk"
  echo "fetching src/utils/hsdis from openjdk/jdk at $tag (sparse, shallow)"
  git clone -q --depth 1 --filter=blob:none --sparse --branch "$tag" \
    https://github.com/openjdk/jdk.git "$src"
  git -C "$src" sparse-checkout set src/utils/hsdis > /dev/null
fi
hsdis="$src/src/utils/hsdis"
[ -f "$hsdis/capstone/hsdis-capstone.c" ] || { echo "no $hsdis/capstone/hsdis-capstone.c" >&2; exit 1; }

case "$(uname -m)" in
  x86_64) arch=amd64; cs_arch=CS_ARCH_X86; cs_mode=CS_MODE_64 ;;
  aarch64) arch=aarch64; cs_arch=CS_ARCH_ARM64; cs_mode=CS_MODE_ARM ;;
  *) echo "unsupported architecture $(uname -m)" >&2; exit 1 ;;
esac
# hsdis.h includes jni.h, so the running JDK's headers are needed too.
java_home="${JAVA_HOME:-$(dirname "$(dirname "$(readlink -f "$(command -v java)")")")}"
[ -f "$java_home/include/jni.h" ] || { echo "no jni.h under $java_home/include" >&2; exit 1; }
mkdir -p "$out"
# The flags make/Hsdis.gmk uses for the capstone backend, without the JDK build around them.
gcc -shared -fPIC -O2 -I"$hsdis" -I/usr/include/capstone \
  -I"$java_home/include" -I"$java_home/include/linux" \
  -DCAPSTONE_ARCH="$cs_arch" -DCAPSTONE_MODE="$cs_mode" \
  -o "$out/hsdis-$arch.so" "$hsdis/capstone/hsdis-capstone.c" -lcapstone
ln -sf "hsdis-$arch.so" "$out/libhsdis.so"
echo "built $out/hsdis-$arch.so"
if LD_LIBRARY_PATH="$out" java -XX:+UnlockDiagnosticVMOptions -XX:+PrintAssembly -version 2>&1 \
    | grep -q "Loading hsdis library failed"; then
  echo "HotSpot did not load it (see the message above)" >&2; exit 1
fi
echo "HotSpot loads it. Put this in your shell:"
echo "  export VARKA_HSDIS_DIR=$out"
