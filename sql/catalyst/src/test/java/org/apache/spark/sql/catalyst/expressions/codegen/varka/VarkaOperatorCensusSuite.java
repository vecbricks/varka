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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Which of the operators the plans rely on C2 lowers to a vector instruction, at each vector
 * species the host runs, and whether a loop of each allocates (the operator census). The
 * answers are committed in {@code sql/varka/operator_census.json}, and
 * {@code skills/vector-api-and-width.md} carries the table.
 *
 * <p>Each catalog entry runs in a JVM of its own ({@link VarkaOperatorCensusProbe}) under
 * {@code -Xbatch} and a {@code PrintInlining} command scoped to the probe's loops, once per
 * distinct width {@code -XX:MaxVectorSize} gives this host. The verdict is read from the inlining
 * decisions of {@code jdk.internal.vm.vector.VectorSupport}'s intrinsic methods in the last C2
 * compile of the loop, never from {@code PrintIntrinsics}, whose refusal lines are not about the
 * compile at hand: an intrinsic C2 applied has an {@code (intrinsic)} decision, a late inline that
 * took a second attempt ends in {@code late inline succeeded}, and one it declined either says
 * {@code failed to inline (intrinsic)} or is inlined as the plain Java method it falls back to.
 * The allocation half is the bytes the thread allocated over a thousand calls at steady state, per
 * row: at most one is no allocation, a box is five or more.
 *
 * <p>A cell is {@code I} (intrinsic, no allocation), {@code I+A} (intrinsic, allocating) or
 * {@code F} (some intrinsic of the loop was declined, so the Vector API's Java implementation
 * runs). The widths are the preferred species' bits, 512 on the laptop's AVX-512, 256 on AVX2 and
 * 128 where forced or native on Arm; the host's datapath may be narrower than its species.
 *
 * <p>The suite defines no vector species in its own JVM, so it needs no child JVM of its own:
 * every species lives in a fork. Regenerate this host's file with
 * {@code VARKA_CENSUS_REGEN=true build/sbt 'catalyst/testOnly *VarkaOperatorCensusSuite'}.
 */
@VarkaSuite
public final class VarkaOperatorCensusSuite {

  /** The widths asked for, in bytes as the JVM names them; a host caps what it cannot run. */
  private static final int[] WIDTHS_BYTES = {16, 32, 64};

  /** At most this many bytes a row is no allocation; a box is five or more. */
  private static final double MAX_BYTES_PER_ROW = 1.0;

  private static final long CHILD_TIMEOUT_SECONDS = 300L;

  private static final String PROBE = VarkaOperatorCensusProbe.class.getName();

  /** One line of C2's inlining tree: indentation, call-site bci, callee class and method, rest. */
  private static final Pattern INLINE_LINE = Pattern.compile(
      "^(\\s*)@ (\\d+)\\s+(\\S+)::(\\S+) \\(\\d+ bytes\\)(.*)$");

  /** A call site C2 re-targeted to another species' class: the second species' footprint. */
  private static final Pattern BIMORPHIC = Pattern.compile(
      "callee changed to\\s+jdk\\.incubator\\.vector\\.");

  private static final String VECTOR_SUPPORT = "jdk.internal.vm.vector.VectorSupport";

  /**
   * What one fork reported.
   *
   * @param bits the preferred species' width
   * @param skipped why the entry is not meaningful at this width, or null
   * @param bytesPerRow the bytes allocated per row at steady state
   * @param intrinsics the {@code VectorSupport} methods the last C2 compile decided on
   * @param failed those among them it declined
   * @param bimorphic whether any inlining decision was changed to another species' class
   */
  enum Verdict {
    INTRINSIC("I"), ALLOCATING("I+A"), FALLBACK("F"), NOT_APPLICABLE("-");

    final String label;

    Verdict(String label) {
      this.label = label;
    }
  }

  record Reading(int bits, String skipped, double bytesPerRow, List<String> intrinsics,
      List<String> failed, boolean bimorphic) {

    Verdict verdict() {
      if (skipped != null) {
        return Verdict.NOT_APPLICABLE;
      }
      if (!failed.isEmpty()) {
        return Verdict.FALLBACK;
      }
      return bytesPerRow > MAX_BYTES_PER_ROW ? Verdict.ALLOCATING : Verdict.INTRINSIC;
    }
  }

  private record Decision(String method, boolean applied) { }

  private record Line(int indent, int bci, String callee, String method, String rest) { }

  /**
   * Reads a fork's output: the markers, and the inlining decisions between them.
   *
   * <p>Every compile of a loop prints its whole tree, C1's included (whose decisions about
   * {@code VectorSupport} say "callee is too large" and never mention an intrinsic), so the trees
   * are told apart by their root call sites, whose bci rises within a tree and falls where the
   * next begins. The last tree that mentions an intrinsic is the loop's C2 code. In it a call
   * site's last decision stands, as C2 retries a late inline.
   */
  static Reading read(List<String> output) {
    int bits = -1;
    String skipped = null;
    double alloc = 0.0;
    boolean done = false;
    boolean bimorphic = false;
    var segments = new ArrayList<List<Line>>();
    segments.add(new ArrayList<>());
    for (String raw : output) {
      // A marker can share a line with a compiler thread's record, so it is found anywhere.
      String begin = after(raw, VarkaOperatorCensusProbe.BEGIN_PREFIX);
      String skip = after(raw, VarkaOperatorCensusProbe.SKIP_PREFIX);
      String measured = after(raw, VarkaOperatorCensusProbe.ALLOC_PREFIX);
      if (begin != null) {
        bits = Integer.parseInt(begin.substring(begin.indexOf("bits=") + 5).strip());
      } else if (skip != null) {
        skipped = skip.strip();
      } else if (measured != null) {
        alloc = Double.parseDouble(measured.substring(measured.lastIndexOf(' ') + 1).strip());
      } else if (after(raw, VarkaOperatorCensusProbe.LOOP_MARKER) != null) {
        segments.add(new ArrayList<>());
      } else if (after(raw, VarkaOperatorCensusProbe.DONE_PREFIX) != null) {
        done = true;
      } else {
        Matcher m = INLINE_LINE.matcher(raw);
        if (m.matches()) {
          segments.get(segments.size() - 1).add(new Line(m.group(1).length(),
              Integer.parseInt(m.group(2)), m.group(3), m.group(4), m.group(5)));
        }
        bimorphic |= BIMORPHIC.matcher(raw).find();
      }
    }
    assertTrue(done && bits > 0, "the census probe did not finish; its output:\n"
        + String.join("\n", output.subList(Math.max(0, output.size() - 40), output.size())));
    var intrinsics = new LinkedHashSet<String>();
    var failed = new LinkedHashSet<String>();
    if (skipped == null) {
      var decisions = new LinkedHashMap<String, Decision>();
      for (int segment = 1; segment < segments.size(); segment++) {
        var tree = lastC2Tree(segments.get(segment));
        assertFalse(tree.isEmpty(), "no C2 compile of loop " + segment + " was printed");
        // A site is its whole chain of callers: two call sites reach the same callee at the same
        // depth and bci through different roots.
        var path = new ArrayList<Line>();
        for (Line l : tree) {
          while (!path.isEmpty() && path.get(path.size() - 1).indent() >= l.indent()) {
            path.remove(path.size() - 1);
          }
          path.add(l);
          // The reboxing helper is no intrinsic; every other VectorSupport method the tree meets
          // is one, and is applied only where its decision says so.
          if (l.callee().equals(VECTOR_SUPPORT) && !l.method().equals("maybeRebox")) {
            boolean applied = l.rest().contains("(intrinsic)") && !l.rest().contains("failed");
            var site = new StringBuilder().append(segment);
            path.forEach(c -> site.append('/').append(c.bci()).append(c.method()));
            decisions.put(site.toString(), new Decision(l.method(), applied));
          }
        }
      }
      decisions.values().forEach(d -> {
        intrinsics.add(d.method());
        if (!d.applied()) {
          failed.add(d.method());
        }
      });
    }
    return new Reading(bits, skipped, alloc, List.copyOf(intrinsics), List.copyOf(failed),
        bimorphic);
  }

  private static String after(String line, String marker) {
    int at = line.indexOf(marker);
    return at < 0 ? null : line.substring(at + marker.length());
  }

  private static List<Line> lastC2Tree(List<Line> lines) {
    if (lines.isEmpty()) {
      return List.of();
    }
    int root = lines.stream().mapToInt(Line::indent).min().getAsInt();
    var trees = new ArrayList<List<Line>>();
    int lastRootBci = Integer.MAX_VALUE;
    for (Line l : lines) {
      if (l.indent() == root) {
        if (l.bci() <= lastRootBci) {
          trees.add(new ArrayList<>());
        }
        lastRootBci = l.bci();
      }
      if (!trees.isEmpty()) {
        trees.get(trees.size() - 1).add(l);
      }
    }
    for (int t = trees.size() - 1; t >= 0; t--) {
      // C1 never lowers an intrinsic and declines the template calls as too large; any other
      // decision about a VectorSupport method is C2's.
      boolean c2 = trees.get(t).stream().anyMatch(
          l -> l.callee().equals(VECTOR_SUPPORT) && !l.method().equals("maybeRebox")
              && !l.rest().contains("callee is too large"));
      if (c2) {
        return trees.get(t);
      }
    }
    return List.of();
  }

  private static String classpath() {
    String dist = System.getenv("SPARK_DIST_CLASSPATH");
    return dist != null && !dist.isEmpty() ? dist : System.getProperty("java.class.path");
  }

  private static String javaBin() {
    return new File(new File(System.getProperty("java.home"), "bin"), "java").getAbsolutePath();
  }

  private static List<String> run(List<String> command) throws IOException, InterruptedException {
    var process = new ProcessBuilder(command).redirectErrorStream(true).start();
    var output = new ArrayList<String>();
    // The output is drained on a thread so that the timeout can fire on a fork that hangs.
    var reader = new Thread(() -> {
      try (var in = new BufferedReader(
          new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
        String line;
        while ((line = in.readLine()) != null) {
          synchronized (output) {
            output.add(line);
          }
        }
      } catch (IOException e) {
        // The process ended under the reader; what was read stands.
      }
    }, "census-fork-output");
    reader.start();
    if (!process.waitFor(CHILD_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
      process.destroyForcibly();
      throw new IllegalStateException("a census fork did not finish within "
          + CHILD_TIMEOUT_SECONDS + " s: " + command);
    }
    reader.join();
    synchronized (output) {
      assertEquals(0, process.exitValue(), () -> "a census fork failed:\n" + String.join("\n",
          output.subList(Math.max(0, output.size() - 40), output.size())));
      return List.copyOf(output);
    }
  }

  /** The vector size in bytes the JVM settles on when asked for {@code requested}. */
  private static int effectiveBytes(int requested) throws IOException, InterruptedException {
    var output = run(List.of(javaBin(), "-XX:MaxVectorSize=" + requested,
        "-XX:+PrintFlagsFinal", "-version"));
    for (String line : output) {
      var m = Pattern.compile("\\s*\\S+\\s+MaxVectorSize\\s+=\\s+(\\d+)").matcher(line);
      if (m.find()) {
        return Integer.parseInt(m.group(1));
      }
    }
    throw new IllegalStateException("no MaxVectorSize in the JVM's flags");
  }

  /** Forks the probe for one entry at one vector size, with any extra JVM flags. */
  static Reading fork(String entry, int bytes, String... flags)
      throws IOException, InterruptedException {
    var command = new ArrayList<String>();
    command.add(javaBin());
    command.add("--add-modules");
    command.add("jdk.incubator.vector");
    command.add("--enable-native-access=ALL-UNNAMED");
    command.add("-XX:+UnlockDiagnosticVMOptions");
    command.add("-Xbatch");
    command.add("-XX:CompileCommand=quiet");
    command.add("-XX:CompileCommand=PrintInlining," + PROBE + "::"
        + VarkaOperatorCensusProbe.LOOP_PREFIX + "*");
    command.add("-XX:CompileCommand=dontinline," + PROBE + "::"
        + VarkaOperatorCensusProbe.LOOP_PREFIX + "*");
    command.add("-XX:MaxVectorSize=" + bytes);
    command.addAll(List.of(flags));
    command.add("-cp");
    command.add(classpath());
    command.add(PROBE);
    command.add(entry);
    return withLibraryStub(VarkaOperatorCensusCatalog.named(entry), read(run(command)));
  }

  /**
   * A math operator whose loop never reaches {@code libraryUnaryOp} or {@code libraryBinaryOp}
   * has no vector stub at this species (the library is looked up per species before the intrinsic
   * is called), so the Java implementation runs: that absence is the refusal, and it is recorded
   * as the method that was not reached. For any other anchor an absence is an unread cell.
   */
  private static Reading withLibraryStub(VarkaOperatorCensusCatalog entry, Reading r) {
    if (r.skipped() != null || r.intrinsics().isEmpty() || !entry.anchor().startsWith("library")
        || r.intrinsics().contains(entry.anchor())) {
      return r;
    }
    var intrinsics = new ArrayList<>(r.intrinsics());
    intrinsics.add(entry.anchor());
    return new Reading(r.bits(), null, r.bytesPerRow(), intrinsics,
        List.of(entry.anchor() + " (no stub)"), r.bimorphic());
  }

  /** The census of this host: per preferred width, per catalog cell. */
  private static final class Census {
    final Map<Integer, Map<String, Reading>> byWidth = new TreeMap<>();
  }

  private static Census census;

  private static synchronized Census census() throws Exception {
    if (census != null) {
      return census;
    }
    var widths = new TreeSet<Integer>();
    for (int requested : WIDTHS_BYTES) {
      widths.add(effectiveBytes(requested));
    }
    int threads = Integer.getInteger("varka.census.parallelism",
        Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors() / 4)));
    var pool = Executors.newFixedThreadPool(threads);
    var result = new Census();
    try {
      for (int bytes : widths) {
        var futures = new LinkedHashMap<String, Future<Reading>>();
        for (var entry : cells()) {
          futures.put(entry.name(), pool.submit(() -> fork(entry.name(), bytes)));
        }
        var readings = new LinkedHashMap<String, Reading>();
        for (var f : futures.entrySet()) {
          try {
            readings.put(f.getKey(), f.getValue().get());
          } catch (ExecutionException e) {
            throw new IllegalStateException(f.getKey() + " at " + bytes * 8 + " bits", e);
          }
        }
        result.byWidth.put(bytes * 8, readings);
      }
    } finally {
      pool.shutdownNow();
    }
    census = result;
    return result;
  }

  /** The catalog without the planted cases, which the self-tests run. */
  private static List<VarkaOperatorCensusCatalog> cells() {
    return VarkaOperatorCensusCatalog.ENTRIES.stream()
        .filter(e -> !e.name().startsWith("planted.")).toList();
  }

  private static int hostBytes() throws IOException, InterruptedException {
    return effectiveBytes(WIDTHS_BYTES[WIDTHS_BYTES.length - 1]);
  }

  @Test
  @DisplayName("a fallback C2 was forced into is read as F")
  void plantedFallback() throws Exception {
    // The binary-op intrinsic is switched off, so the long add runs as the Java implementation
    // on every host; a census that read every operator as intrinsic would call it I.
    var reading = fork("planted.fallback", hostBytes(), "-XX:DisableIntrinsic=_VectorBinaryOp");
    assertEquals(Verdict.FALLBACK, reading.verdict(), () -> "read as " + reading);
    assertTrue(reading.failed().contains("binaryOp"), () -> "the refusal is not named: " + reading);
    var control = fork("planted.fallback", hostBytes());
    assertEquals(Verdict.INTRINSIC, control.verdict(),
        () -> "the same loop, not disabled, read as " + control);
  }

  @Test
  @DisplayName("a vector stored to a field is read as I+A, intrinsic and allocating")
  void plantedBox() throws Exception {
    var reading = fork("planted.box", hostBytes());
    assertEquals(Verdict.ALLOCATING, reading.verdict(), () -> "read as " + reading);
    assertTrue(reading.bytesPerRow() >= 5.0, () -> "a box is five bytes a row or more: " + reading);
  }

  @Test
  @DisplayName("the catalog names each cell once, with a loop and an anchor")
  void catalogIsWellFormed() {
    var seen = new TreeSet<String>();
    for (var e : VarkaOperatorCensusCatalog.ENTRIES) {
      assertTrue(seen.add(e.name()), "two cells are named " + e.name());
      assertFalse(e.loop().isEmpty() || e.anchor().isEmpty(), e.name() + " has no loop or anchor");
    }
    assertTrue(cells().size() >= 80, "the census asks about " + cells().size() + " cells");
  }

  @Test
  @DisplayName("every cell has a verdict, read from the decision its loop is anchored on")
  void everyCellHasAVerdict() throws Exception {
    var unread = new ArrayList<String>();
    var bimorphic = new ArrayList<String>();
    census().byWidth.forEach((bits, readings) -> cells().forEach(e -> {
      var r = readings.get(e.name());
      if (r.skipped() == null && !r.intrinsics().contains(e.anchor())) {
        unread.add(bits + " bits: " + e.name() + " (anchor " + e.anchor() + ", saw "
            + r.intrinsics() + ")");
      }
      if (r.bimorphic() && !e.family().equals("two species")) {
        bimorphic.add(bits + " bits: " + e.name());
      }
    }));
    assertTrue(unread.isEmpty(), "no inlining decision on the anchor for:\n  "
        + String.join("\n  ", unread));
    assertTrue(bimorphic.isEmpty(), "a second species reached a one-species fork:\n  "
        + String.join("\n  ", bimorphic));
  }

  private static String cpuModel() {
    try {
      for (String line : Files.readAllLines(Path.of("/proc/cpuinfo"))) {
        if (line.startsWith("model name")) {
          return line.split(":", 2)[1].strip();
        }
      }
    } catch (IOException e) {
      // No cpuinfo: the host is unnamed.
    }
    return System.getProperty("os.arch");
  }

  private static Path file() {
    String home = System.getProperty("spark.test.home", System.getenv("SPARK_HOME"));
    assertTrue(home != null, "spark.test.home or SPARK_HOME is not set.");
    return Path.of(home, "sql", "varka", "operator_census.json");
  }

  private static Map<String, Object> ordered() {
    return new LinkedHashMap<>();
  }

  private static String render() throws Exception {
    var widths = ordered();
    census().byWidth.forEach((bits, readings) -> {
      var cells = ordered();
      readings.forEach((name, r) -> {
        var cell = ordered();
        cell.put("verdict", r.verdict().label);
        if (r.skipped() == null) {
          cell.put("bytes_per_row", Math.round(r.bytesPerRow() * 100.0) / 100.0);
          cell.put("intrinsics", r.intrinsics());
          if (!r.failed().isEmpty()) {
            cell.put("failed", r.failed());
          }
        } else {
          cell.put("skipped", r.skipped());
        }
        cells.put(name, cell);
      });
      widths.put(String.valueOf(bits), cells);
    });
    var host = ordered();
    host.put("cpu", cpuModel());
    host.put("arch", System.getProperty("os.arch"));
    host.put("jdk", System.getProperty("java.runtime.version"));
    var doc = ordered();
    doc.put("generated_by", "VarkaOperatorCensusSuite; regenerate on the host named below with "
        + "VARKA_CENSUS_REGEN=true build/sbt 'catalyst/testOnly *VarkaOperatorCensusSuite'");
    doc.put("description", "For every operator the plans rely on and every vector species this "
        + "host runs, whether C2 lowers a loop of the operator to a vector instruction (I), "
        + "lowers it and allocates in the loop (I+A) or declines an intrinsic of the loop so "
        + "that the Vector API's Java implementation runs (F), read from the inlining decisions "
        + "of the last C2 compile; '-' is a cell not meaningful at that width. bytes_per_row is "
        + "the allocation over a thousand steady-state calls, and one or less is none. The "
        + "census is a property of the CPU and the JDK, so the host is part of the record.");
    doc.put("host", host);
    doc.put("widths", widths);
    return new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsString(doc) + "\n";
  }

  @Test
  @DisplayName("sql/varka/operator_census.json is this host's census, per width")
  void censusFileIsThisHosts() throws Exception {
    var path = file();
    String rendered = render();
    if ("true".equals(System.getenv("VARKA_CENSUS_REGEN"))) {
      Files.writeString(path, rendered);
      return;
    }
    assertTrue(Files.exists(path), path + " is missing; generate it with\n"
        + "  VARKA_CENSUS_REGEN=true build/sbt 'catalyst/testOnly *VarkaOperatorCensusSuite'");
    var mapper = new ObjectMapper();
    var committed = mapper.readTree(Files.readString(path));
    assumeTrue(committed.get("host").get("cpu").asText().equals(cpuModel()),
        "the committed census is " + committed.get("host").get("cpu").asText() + "'s and this is "
            + cpuModel() + "; a census is compared only where it was taken");
    var now = mapper.readTree(rendered);
    var moved = new ArrayList<String>();
    now.get("widths").fieldNames().forEachRemaining(bits -> {
      now.get("widths").get(bits).fieldNames().forEachRemaining(cell -> {
        var was = committed.get("widths").path(bits).path(cell).path("verdict").asText("(absent)");
        var is = now.get("widths").get(bits).get(cell).get("verdict").asText();
        if (!was.equals(is)) {
          moved.add(bits + " bits: " + cell + ": " + was + " -> " + is);
        }
      });
    });
    assertTrue(moved.isEmpty(), "sql/varka/operator_census.json does not match what this host "
        + "measures; regenerate it and say what moved in the plan:\n  "
        + String.join("\n  ", moved));
  }
}
