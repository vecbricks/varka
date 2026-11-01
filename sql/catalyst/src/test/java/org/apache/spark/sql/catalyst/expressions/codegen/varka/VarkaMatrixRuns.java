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

import java.io.FileWriter;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.Set;
import java.util.function.Function;
import java.util.function.LongSupplier;

/**
 * The option configuration matrix (VARKA-248 step 2, {@code m7/VARKA-248.md} 3.2): the Varka
 * suites rerun once per configuration - the defaults with one option changed - and every kernel
 * they emit carries it, so a reference form or a machine's alternative meets every test rather
 * than only the few that set it.
 *
 * <p>A JVM runs under one configuration, named by {@code -Dvarka.matrix.config=<name>=<value>}
 * (several pairs comma separated), and under the defaults without it. Tests start from
 * {@link #BASE} where they would start from {@code VarkaEmitOptions.DEFAULTS}, and the SQL suites'
 * emit hook starts there too, so the configuration reaches every kernel while production code
 * reads no property. {@code dev/varka_matrix.sh} runs the configurations, several JVMs at a time.
 *
 * <p>This class is the matrix's logic, free of any test framework: a test framework's face
 * ({@code VarkaMatrix} for ScalaTest) supplies how a test is cancelled and failed, through
 * {@link Outcomes} (VARKA-312).
 */
public final class VarkaMatrixRuns {

  private VarkaMatrixRuns() {}

  /** The system property naming this JVM's configuration. */
  public static final String PROPERTY = "varka.matrix.config";

  /** The system property naming the file each test's fused-batch count is appended to. */
  public static final String REPORT_PROPERTY = "varka.matrix.report";

  /** This JVM's configuration, {@code ""} for the defaults. */
  public static final String CONFIG = System.getProperty(PROPERTY, "").trim();

  /**
   * The name of the batch-size axis (VARKA-301): {@code arrowBatchSize=<rows>} sets the rows per
   * Arrow cached batch ({@code spark.sql.execution.arrow.maxRecordsPerBatch}; the Arrow cache
   * ignores {@code spark.sql.inMemoryColumnarStorage.batchSize}) in the SQL suites' sessions. It
   * is not an emit option: {@link #parse} passes over it, and the sessions read
   * {@link #ARROW_BATCH_SIZE_ROWS}.
   */
  public static final String ARROW_BATCH_SIZE = "arrowBatchSize";

  /**
   * The batch sizes the axis runs: one row; three, a batch that is all epilogue at any width;
   * and seventeen, just past two lane groups of eight.
   */
  public static final List<Integer> ARROW_BATCH_SIZES = List.of(1, 3, 17);

  /** This JVM's rows per Arrow cached batch, when its configuration names one. */
  public static final OptionalInt ARROW_BATCH_SIZE_ROWS = arrowBatchSizeOf(CONFIG);

  /**
   * The options every test starts from: the defaults with this JVM's configuration applied.
   * Declared after the axis's names, which {@code parse} reads while this class initialises.
   */
  public static final VarkaEmitOptions BASE = parse(CONFIG);

  /**
   * The tag of a test whose subject is the defaults' emitted structure - op counts, a method
   * count, a {@code HugeMethodLimit} crossing, bytes compared byte for byte, the size a shape
   * declines at. Under a configuration it would measure nothing new, so the matrix cancels it
   * there.
   */
  public static final String PINS_DEFAULTS_TAG = "org.apache.spark.sql.varka.PinsDefaults";

  /** The message a {@code PinsDefaults} test is cancelled with under {@code config}. */
  public static String pinsDefaultsMessage(String config) {
    return "pins the defaults' structure; not run under " + config;
  }

  /**
   * The tag of a test whose subject is the C2 code a kernel compiles to - its tiers, its traps,
   * its answers on the way up - which checks the lowerings as the JIT runs them for users, at the
   * defaults; every row is checked so each night. Under a configuration it runs only where the
   * configuration changes what C2 is given beyond the bytes ({@link #C2_OPTIONS}), and is
   * cancelled elsewhere (VARKA-517).
   */
  public static final String C2_CODE_TAG = "org.apache.spark.sql.varka.C2Code";

  /**
   * The options that change what C2 compiles a kernel against, not only its bytes: a second
   * vector species in the JVM (the lane count, the half species) or the instructions C2 may use.
   */
  public static final Set<String> C2_OPTIONS =
      Set.of("lanesOverride", "narrowHalfSpecies", "useAVX");

  /** Whether {@code config} sets an option of {@link #C2_OPTIONS}. */
  public static boolean changesC2(String config) {
    for (String part : config.split("[,_]")) {
      int eq = part.indexOf('=');
      if (eq > 0 && C2_OPTIONS.contains(part.substring(0, eq))) {
        return true;
      }
    }
    return false;
  }

  /** The message a {@code C2Code} test is cancelled with under {@code config}. */
  public static String c2CodeMessage(String config) {
    return "checks the C2 code of the defaults' kernels; " + config + " changes only their bytes";
  }

  private static OptionalInt arrowBatchSizeOf(String config) {
    for (String pair : config.split(",")) {
      String[] kv = pair.trim().split("=", 2);
      if (kv.length == 2 && kv[0].equals(ARROW_BATCH_SIZE)) {
        return OptionalInt.of(Integer.parseInt(kv[1].trim()));
      }
    }
    return OptionalInt.empty();
  }

  /**
   * {@code name=value[,name=value]} applied over the defaults, each name looked up in
   * {@code VarkaEmitOption.TABLE}. A flag takes only {@code true} or {@code false}, and an enum
   * only one of its constants, so a typo is refused rather than silently selecting the default.
   */
  public static VarkaEmitOptions parse(String spec) {
    return parseOver(VarkaEmitOptions.DEFAULTS, spec);
  }

  /** {@link #parse}'s values applied over {@code base} rather than over the defaults. */
  public static VarkaEmitOptions parseOver(VarkaEmitOptions base, String spec) {
    VarkaEmitOptions options = base;
    if (spec.isEmpty()) {
      return options;
    }
    for (String pair : spec.split(",")) {
      String[] kv = pair.trim().split("=", 2);
      if (kv.length != 2) {
        throw new IllegalArgumentException("expected name=value, got '" + pair + "'");
      }
      String name = kv[0].trim();
      String value = kv[1].trim();
      if (kv[0].equals(ARROW_BATCH_SIZE) && Integer.parseInt(value) > 0) {
        continue;
      }
      options = switch (VarkaEmitOption.named(name)) {
        case VarkaEmitOption.Count count -> count.with(options, Integer.parseInt(value));
        case VarkaEmitOption.Flag flag -> switch (value.toLowerCase(Locale.ROOT)) {
          case "true" -> flag.with(options, true);
          case "false" -> flag.with(options, false);
          default -> throw new IllegalArgumentException(
              kv[0] + ": expected true or false, got " + value.toLowerCase(Locale.ROOT));
        };
        case VarkaEmitOption.Choice<?> choice -> choice.withNamed(options, value);
      };
    }
    return options;
  }

  /**
   * The lane counts the matrix emits for besides the machine's own: the widths either side of
   * the laptop's eight. {@code lanesOverride} has no audit values - the bytes oracle emits every
   * shape at two widths already - so its matrix values are named here.
   */
  private static final List<Integer> LANE_COUNTS = List.of(4, 16);

  /**
   * Every configuration, in table order: each non-default arm of each option the fuzzer draws
   * ({@code Reason.drawn}: not a fault injector or a miscompiled form) - a flag's other value,
   * an enum's other constants, a count's audit values - and
   * the lane counts above. Derived from the table, so a new option joins with its entry. Then
   * the batch-size axis, which changes the sessions' cached batches rather than an option.
   *
   * <p>A flag whose {@code VarkaEmitOption.Subject} is not the defaults runs its other value over
   * that subject, {@code denseValidityOnce=false,validityByBitmap=false,validityOrFirst=false}:
   * at the defaults the value emits the defaults' bytes, and the configuration would test nothing
   * (VARKA-247).
   *
   * <p>An arm runs only if it emits something the defaults and the option's other arms do not, by
   * {@link #emissionKey}: see {@link #distinctArms}.
   */
  public static List<String> configurations() {
    VarkaEmitOptions defaults = VarkaEmitOptions.DEFAULTS;
    var out = new ArrayList<String>();
    for (VarkaEmitOption option : VarkaEmitOption.TABLE) {
      if (!option.reason().drawn()) {
        continue;
      }
      List<String> arms = distinctArms(option.arms(), defaults).stream()
          .map(VarkaEmitOption.Arm::name)
          .toList();
      if (option.name().equals("lanesOverride")) {
        LANE_COUNTS.forEach(n -> out.add("lanesOverride=" + n));
      } else if (option instanceof VarkaEmitOption.Flag f
          && !f.subject().equals(VarkaEmitOption.Subject.DEFAULTS)) {
        arms.forEach(arm -> out.add(f.subject().label() + "," + arm));
      } else {
        out.addAll(arms);
      }
    }
    ARROW_BATCH_SIZES.forEach(rows -> out.add(ARROW_BATCH_SIZE + "=" + rows));
    return List.copyOf(out);
  }

  /** The {@code -XX:UseAVX} level that stands for every level whose converts fall back. */
  static final int FALLBACK_USE_AVX = 2;

  /**
   * {@code options} as the emitter can tell them apart. {@code useAVX} reaches the emitted bytes
   * only through {@link VarkaEmitOptions#convertsFallBack}, which asks whether the level is below
   * AVX-512, so every level below it emits as {@link #FALLBACK_USE_AVX} does and every other
   * level as the unstated one, the defaults'. {@code VarkaEmittedBytesSuite} holds each arm the
   * key moves to its key's bytes over the oracle's shapes, so a new reader of the level that
   * tells two of them apart fails there.
   */
  static VarkaEmitOptions emissionKey(VarkaEmitOptions options) {
    return options.withUseAVX(
        options.convertsFallBack() ? FALLBACK_USE_AVX : VarkaEmitOptions.USE_AVX_UNKNOWN);
  }

  /**
   * The arms of one option that emit something new: an arm whose {@link #emissionKey} is the
   * defaults' is left out, and of arms that share a key one runs, the one whose options are the
   * key itself where there is one, else the first. So {@code useAVX=0}, {@code 1} and {@code 2}
   * run once, as {@code useAVX=2}, and {@code useAVX=3} not at all.
   */
  static List<VarkaEmitOption.Arm> distinctArms(List<VarkaEmitOption.Arm> arms,
      VarkaEmitOptions defaults) {
    VarkaEmitOptions defaultsKey = emissionKey(defaults);
    var byKey = new LinkedHashMap<VarkaEmitOptions, VarkaEmitOption.Arm>();
    for (VarkaEmitOption.Arm arm : arms) {
      VarkaEmitOptions options = arm.apply().apply(defaults);
      VarkaEmitOptions key = emissionKey(options);
      if (options.equals(defaults) || key.equals(defaultsKey)) {
        continue;
      }
      if (!byKey.containsKey(key) || options.equals(key)) {
        byKey.put(key, arm);
      }
    }
    return List.copyOf(byKey.values());
  }

  // ---------------------------------------------------------------------------------------------
  // The skip list
  // ---------------------------------------------------------------------------------------------

  /** What a configuration is expected to do to a test: fail it, or leave it fusing nothing. */
  public enum Kind {
    FAILS("fails"),
    DECLINES("declines");

    /** The word the skip list uses. */
    public final String word;

    Kind(String word) {
      this.word = word;
    }

    @Override
    public String toString() {
      return word;
    }

    /** The kind the skip list's word names; {@link NoSuchElementException} for any other. */
    public static Kind named(String word) {
      for (Kind k : values()) {
        if (k.word.equals(word)) {
          return k;
        }
      }
      throw new NoSuchElementException("no skip kind named '" + word + "'");
    }
  }

  /** One line of the skip list: a test a configuration is expected to break, and why. */
  public record Skip(String config, String suite, String test, Kind kind, String reason) {}

  /** The committed skip list, relative to {@code spark.test.home}. */
  public static final String SKIPS_PATH = "sql/varka/matrix/skips.tsv";

  /**
   * {@code sql/varka/matrix/skips.tsv}: tab separated configuration, suite (simple name), test
   * name, kind and reason; {@code #} starts a comment line.
   */
  public static List<Skip> parseSkips(String text) {
    var out = new ArrayList<Skip>();
    for (String raw : text.split("\n")) {
      String line = raw.endsWith("\r") ? raw.substring(0, raw.length() - 1) : raw;
      if (line.isBlank() || line.startsWith("#")) {
        continue;
      }
      String[] f = line.split("\t");
      if (f.length != 5) {
        throw new IllegalArgumentException(SKIPS_PATH + ": malformed line '" + line + "'");
      }
      out.add(new Skip(f[0], f[1], f[2], Kind.named(f[3]), f[4]));
    }
    return List.copyOf(out);
  }

  // The committed list once read; null until a read succeeds. A read that fails throws its own
  // exception to that caller and leaves the next to read again, as a Scala lazy val does.
  private static volatile List<Skip> skips;

  /** The committed skip list, read once. */
  public static List<Skip> skips() {
    List<Skip> list = skips;
    if (list == null) {
      synchronized (VarkaMatrixRuns.class) {
        list = skips;
        if (list == null) {
          Path file = Path.of(System.getProperty("spark.test.home", "."), SKIPS_PATH);
          try {
            list = Files.exists(file)
                ? parseSkips(Files.readString(file, StandardCharsets.UTF_8))
                : List.of();
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
          skips = list;
        }
      }
    }
    return list;
  }

  /** The {@code fails} entry for this test under {@code config}, if the skip list has one. */
  public static Optional<Skip> expectedFailure(List<Skip> entries, String config, String suite,
      String test) {
    return entries.stream()
        .filter(s -> s.kind() == Kind.FAILS && s.config().equals(config)
            && s.suite().equals(suite) && s.test().equals(test))
        .findFirst();
  }

  // ---------------------------------------------------------------------------------------------
  // Running one test
  // ---------------------------------------------------------------------------------------------

  // Batches the Varka kernels have processed in this JVM so far, or -1 where nothing counts them.
  private static volatile LongSupplier fusedBatches = () -> -1L;

  /**
   * Batches the Varka kernels have processed in this JVM so far, or -1 where nothing counts
   * them. The SQL suites' sessions set it; the emitter suites run their kernels directly and a
   * decline there fails the test, so they need no count.
   */
  public static LongSupplier fusedBatches() {
    return fusedBatches;
  }

  public static void setFusedBatches(LongSupplier counter) {
    fusedBatches = counter;
  }

  /** A test body, which may throw anything a test may. */
  @FunctionalInterface
  public interface Body<T> {
    T run() throws Throwable;
  }

  /**
   * How a test framework cancels and fails a test, and how it tells a cancel: the body's own
   * cancel passes through {@link #around} untouched, as a cancel and not a failure. The two
   * factories return a {@code Throwable}, not a {@code RuntimeException}, because JUnit's failure,
   * opentest4j's {@code AssertionFailedError}, is an {@code Error}.
   */
  public record Outcomes(
      Function<String, Throwable> cancel,
      Function<String, Throwable> fail,
      Class<? extends Throwable> cancelled) {}

  /**
   * Runs one test under the matrix. A test the skip list expects to fail under this JVM's
   * configuration is cancelled with the entry's reason when it fails, and fails as a stale entry
   * when it passes. Every test's fused-batch count goes to the report file when one is named.
   */
  public static <T> T around(String suite, String test, Outcomes outcomes, Body<T> body)
      throws Throwable {
    return around(skips(), CONFIG, Optional.ofNullable(System.getProperty(REPORT_PROPERTY)),
        suite, test, outcomes, body);
  }

  /** {@link #around} with its skip list, configuration and report named: the seam its tests use. */
  public static <T> T around(List<Skip> entries, String config, Optional<String> report,
      String suite, String test, Outcomes outcomes, Body<T> body) throws Throwable {
    Optional<Skip> expected = expectedFailure(entries, config, suite, test);
    // A test nothing of which this configuration changes was run by the defaults' run already
    // (VarkaTestReach): it passes here unrun, with no fused count for the declining check.
    if (VarkaTestReach.skips(config, suite, test, expected.isPresent())) {
      return null;
    }
    VarkaTestReach.begin();
    long before = fusedBatches.getAsLong();
    boolean cancelled = false;
    try {
      if (expected.isEmpty()) {
        return body.run();
      }
      Skip skip = expected.get();
      Throwable failure = null;
      try {
        body.run();
      } catch (Throwable t) {
        if (outcomes.cancelled().isInstance(t)) {
          throw t;
        }
        failure = t;
      }
      if (failure != null) {
        throw outcomes.cancel().apply("expected to fail under " + skip.config() + ": "
            + skip.reason() + " (" + failure.getClass().getSimpleName() + ")");
      }
      throw outcomes.fail().apply("stale entry in " + SKIPS_PATH + ": '" + test + "' of " + suite
          + " passes under " + skip.config() + "; remove the line");
    } catch (Throwable t) {
      cancelled = outcomes.cancelled().isInstance(t);
      throw t;
    } finally {
      VarkaTestReach.end(suite, test, cancelled);
      if (report.isPresent()) {
        long after = fusedBatches.getAsLong();
        long fused = before < 0 || after < 0 ? -1L : after - before;
        synchronized (VarkaMatrixRuns.class) {
          try (var writer = new FileWriter(report.get(), StandardCharsets.UTF_8, true)) {
            writer.write(suite + "\t" + test + "\t" + fused + "\n");
          } catch (IOException e) {
            throw new UncheckedIOException(e);
          }
        }
      }
    }
  }
}
