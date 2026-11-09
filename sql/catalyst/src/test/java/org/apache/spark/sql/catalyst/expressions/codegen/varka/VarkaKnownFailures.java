/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *    http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.spark.sql.catalyst.expressions.codegen.varka;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;

/**
 * The fuzz failures that are known (VARKA-277), {@code sql/varka/fuzz/known_failures.tsv}: tab
 * separated lane ({@code int}, {@code long}, {@code byte} or {@code short} for the IR fuzzer, or
 * {@code projection}, {@code predicate} or {@code wide} for the composition fuzzer), seed,
 * iteration, the signature's kind, its text, and the reason, which names the plan row or ticket
 * that owns the bug; {@code #} starts a comment line. A fuzzer's failure with a listed signature is
 * reported as known and does not fail the run, and a listed entry that its (lane, seed, iteration)
 * no longer reproduces is stale and fails, so the list can only shrink once a bug is fixed - as
 * {@code matrix/skips.tsv} does for the option matrix.
 */
public final class VarkaKnownFailures {

  private VarkaKnownFailures() {
  }

  /**
   * The fuzzers a lane names: the IR fuzzer's int and long lanes and its narrow sequences
   * (VARKA-352), and the composition fuzzer's four.
   */
  public static final Set<String> LANES =
      Set.of("int", "long", "byte", "short", "projection", "predicate", "wide", "forced");

  /** The committed list, relative to {@code spark.test.home}. */
  public static final String PATH = "sql/varka/fuzz/known_failures.tsv";

  public record Entry(
      String lane, long seed, int iteration, VarkaFailureSignature signature, String reason) { }

  public static List<Entry> parse(String text) {
    return text.lines()
        .map(String::stripTrailing)
        .filter(l -> !l.isBlank() && !l.startsWith("#"))
        .map(VarkaKnownFailures::entry)
        .toList();
  }

  private static Entry entry(String line) {
    String[] f = line.split("\t", -1);
    if (f.length != 6 || !LANES.contains(f[0]) || f[5].isEmpty()) {
      throw new IllegalArgumentException(PATH
          + ": expected lane<TAB>seed<TAB>iteration<TAB>kind<TAB>text<TAB>reason, got '"
          + line + "'");
    }
    return new Entry(f[0], Long.parseLong(f[1]), Integer.parseInt(f[2]),
        new VarkaFailureSignature(f[3], f[4]), f[5]);
  }

  // The committed list once read; null until a read succeeds. A read that fails throws its own
  // exception to that caller and leaves the next to read again, as a Scala lazy val does - a
  // holder class's static initializer would turn a malformed row into an
  // ExceptionInInitializerError, which aborts the suite and hides the row's message from later
  // callers behind NoClassDefFoundError.
  private static volatile List<Entry> committed;

  /** The committed list, read once. */
  public static List<Entry> entries() {
    List<Entry> list = committed;
    if (list == null) {
      synchronized (VarkaKnownFailures.class) {
        list = committed;
        if (list == null) {
          list = read();
          committed = list;
        }
      }
    }
    return list;
  }

  private static List<Entry> read() {
    Path file = Path.of(System.getProperty("spark.test.home", "."), PATH);
    if (!Files.exists(file)) {
      return List.of();
    }
    try {
      return parse(Files.readString(file, StandardCharsets.UTF_8));
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }

  public static boolean isKnown(List<Entry> list, VarkaFailureSignature signature) {
    return list.stream().anyMatch(e -> e.signature().equals(signature));
  }

  /** The entries whose case, replayed, no longer fails with the signature they list. */
  public static List<Entry> stale(List<Entry> list,
      Function<Entry, Optional<VarkaFailureSignature>> replay) {
    return list.stream()
        .filter(e -> replay.apply(e).filter(e.signature()::equals).isEmpty())
        .toList();
  }
}
