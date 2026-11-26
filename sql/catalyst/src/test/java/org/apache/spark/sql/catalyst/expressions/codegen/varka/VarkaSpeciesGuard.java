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
import java.lang.classfile.ClassFile;
import java.lang.classfile.constantpool.FieldRefEntry;
import java.lang.classfile.constantpool.PoolEntry;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jdk.incubator.vector.VectorSpecies;

/**
 * Fails an in-process test that would put a second vector species of one lane type into the
 * shared test JVM (VARKA-246, {@code m9/SCOPE.md} item 69).
 *
 * <p>Two species of one lane type in a JVM make the Vector API's shared templates inline
 * bimorphically, and C2 then keeps a heap box per loop iteration in some shapes: a probe measured
 * the same loops 2.7x to 12.8x slower once a second species had been touched
 * ({@code sql/varka/skills/vector-api-and-width.md}). The cost lands on every kernel compiled
 * after the second species ran, and on any test whose verdict is a JIT outcome, which then fails
 * by the suites' order. Answers stay right, so nothing else notices.
 *
 * <p>A kernel emitted at a lanes override names
 * {@code jdk/incubator/vector/<Lane>Vector.SPECIES_<bits>} ({@code Lane.speciesField}); one
 * emitted for the JVM's own width names {@code SPECIES_PREFERRED}, or the {@code SPECIES_<bits>}
 * that is the preferred species. So the bytes say which size a class uses, per vector class.
 *
 * <p>{@link #check} throws where a test is about to define a class that would add a second size
 * to a vector class beside the ones the JVM has already used (the {@link Registry}); the check is
 * against the species already used, not against the preferred one, because a matrix
 * configuration that pins the lane count ({@code lanesOverride=4}) runs every suite at one width
 * that is not the preferred: one species per lane type is what keeps the templates monomorphic,
 * however it is spelled. The registry is first-come: the class that adds the second size is the
 * one reported, with the size already established, so read the message for both. A class that
 * names two sizes of one vector class by design (a configuration that stores an int species at
 * the long lane's lane count) is reported as such; none of the four lane-related matrix
 * configurations does.
 *
 * <p>A test that needs the second width runs it in a JVM of its own (a forked probe, as the
 * assembly and cliff suites do) or in the gate's {@code -XX:MaxVectorSize=16} JVM, where the
 * override is the preferred width and so no second species. The class is Java because the
 * {@code java.lang.classfile} API's types are cyclic in a way scalac rejects.
 * {@code -Dvarka.speciesGuard=report} records the violations to the file
 * {@code -Dvarka.speciesGuard.report} names (suite, class, species) and lets the test run, which
 * is how the census was made; {@code off} disables the guard.
 */
public final class VarkaSpeciesGuard {

  /** Set in a suite's own JVM ({@code VarkaOwnJvm}), where any species may be used. */
  public static final String OWN_JVM_PROPERTY = "varka.ownJvm";

  private static final Pattern SPECIES = Pattern.compile("SPECIES_(\\d+)");
  private static final String VECTOR_PREFIX = "jdk/incubator/vector/";

  /** The bit size of {@code owner.SPECIES_PREFERRED} on this JVM, read once per class. */
  private static final ConcurrentHashMap<String, Integer> PREFERRED_BITS =
      new ConcurrentHashMap<>();

  private VarkaSpeciesGuard() {
  }

  /** A suite run in a JVM of its own ({@code VarkaOwnJvm}) may use any species. */
  private static boolean ownJvm() {
    return "true".equals(System.getProperty(OWN_JVM_PROPERTY));
  }

  private static String mode() {
    return System.getProperty("varka.speciesGuard", "fail");
  }

  private static int preferred(String owner) {
    return PREFERRED_BITS.computeIfAbsent(owner, o -> {
      try {
        Object species = Class.forName(o.replace('/', '.')).getField("SPECIES_PREFERRED")
            .get(null);
        return ((VectorSpecies<?>) species).vectorBitSize();
      } catch (ReflectiveOperationException e) {
        throw new IllegalStateException(e);
      }
    });
  }

  /**
   * The lanes of {@code IntVector.SPECIES_PREFERRED} on this JVM, the widest int vector its C2
   * keeps in registers: a kernel emitted wider runs correctly, every vector a heap object.
   */
  public static int preferredIntLanes() {
    return preferred(VECTOR_PREFIX + "IntVector") / Integer.SIZE;
  }

  /**
   * The int lanes every kernel of this JVM is emitted at, and so the width its species is
   * established at: the matrix's lanes override where a configuration sets one, else {@link
   * #preferredIntLanes}. A test that loads a class it emitted at another width puts a second
   * species beside the established one, which {@link #check} refuses. An override with no named
   * species (such as 32 int lanes) falls back to the preferred width, which this does not
   * model; no matrix configuration sets one.
   */
  public static int establishedIntLanes() {
    int override = VarkaMatrixRuns.BASE.lanesOverride();
    return override != 0 ? override : preferredIntLanes();
  }

  /** The species constants {@code bytes} names that are not their vector class's preferred. */
  public static List<String> secondSpecies(byte[] bytes) {
    var found = new ArrayList<String>();
    for (var e : sizesUsed(bytes).entrySet()) {
      int preferred = preferred(e.getKey());
      for (int bits : e.getValue()) {
        if (bits != preferred) {
          found.add(e.getKey().substring(VECTOR_PREFIX.length()) + ".SPECIES_" + bits
              + " (preferred " + preferred + ")");
        }
      }
    }
    return found;
  }

  /** The suite that is running: the first frame of the stack that is a {@code *Suite}. */
  private static String suiteName() {
    for (StackTraceElement frame : Thread.currentThread().getStackTrace()) {
      String c = frame.getClassName();
      if (c.endsWith("Suite") || c.contains("Suite$")) {
        return c;
      }
    }
    return "unknown";
  }

  /** The species sizes, per vector class, a class's bytes use: the preferred one counts. */
  private static Map<String, List<Integer>> sizesUsed(byte[] bytes) {
    var used = new LinkedHashMap<String, List<Integer>>();
    for (PoolEntry entry : ClassFile.of().parse(bytes).constantPool()) {
      if (entry instanceof FieldRefEntry field
          && field.owner().asInternalName().startsWith(VECTOR_PREFIX)) {
        String owner = field.owner().asInternalName();
        String name = field.name().stringValue();
        Matcher m = SPECIES.matcher(name);
        int bits;
        if (name.equals("SPECIES_PREFERRED")) {
          bits = preferred(owner);
        } else if (m.matches()) {
          bits = Integer.parseInt(m.group(1));
        } else {
          continue;
        }
        var sizes = used.computeIfAbsent(owner, o -> new ArrayList<>());
        if (!sizes.contains(bits)) {
          sizes.add(bits);
        }
      }
    }
    return used;
  }

  /**
   * The species sizes the classes defined in one JVM have used, per vector class. A JVM that runs
   * every kernel at one width - the default, or a matrix configuration that pins the lane count
   * for every suite - has one size per class however it spells it, and a class that would add a
   * second size is the violation. {@link #SHARED} is the test JVM's.
   */
  public static final class Registry {
    private final Map<String, Integer> seen = new LinkedHashMap<>();

    /**
     * The sizes {@code bytes} would add beside ones already seen, described, or none if it adds
     * none; a class that conflicts registers nothing, so later kernels of the established width
     * still pass.
     */
    public synchronized List<String> admit(byte[] bytes) {
      var conflicts = new ArrayList<String>();
      var fresh = new LinkedHashMap<String, Integer>();
      for (var e : sizesUsed(bytes).entrySet()) {
        String name = e.getKey().substring(VECTOR_PREFIX.length());
        List<Integer> sizes = e.getValue();
        if (sizes.size() > 1) {
          conflicts.add(name + " at " + sizes + " bits in one class");
          continue;
        }
        Integer established = seen.get(e.getKey());
        if (established != null && !established.equals(sizes.get(0))) {
          conflicts.add(name + ".SPECIES_" + sizes.get(0) + " beside the established "
              + established);
        } else {
          fresh.put(e.getKey(), sizes.get(0));
        }
      }
      if (conflicts.isEmpty()) {
        seen.putAll(fresh);
      }
      return conflicts;
    }
  }

  /** The test JVM's registry. */
  static final Registry SHARED = new Registry();

  /** The vector classes a kernel is driven at, each with its element's bits. */
  private static final Map<String, Integer> DRIVE_ELEMENT_BITS = Map.of(
      VECTOR_PREFIX + "IntVector", Integer.SIZE, VECTOR_PREFIX + "LongVector", Long.SIZE,
      VECTOR_PREFIX + "FloatVector", Float.SIZE, VECTOR_PREFIX + "DoubleVector", Double.SIZE);

  /**
   * The species {@code bytes} names of the drive lanes' vector classes, described, when they do
   * not all have one lane count; empty when they do. Such a class is a kernel of two lanes of
   * different widths (a long kernel holding int values in the int species of its own width, an
   * int kernel widening into the long lane), which at the defaults names each species at the
   * preferred width and so passes {@link Registry#admit}. A lane configuration emits every
   * species of a kernel at lane counts proportional to its drive's, and establishes each drive
   * lane's species at the configuration's lane count, so a class whose species differ in lane
   * count adds a second species there ({@code lanesOverride=4}, {@code lanesOverride=16}), as
   * its narrowing store does under {@code narrowHalfSpecies=true}. The byte and short classes
   * are left out: no kernel is driven at them, and every narrow load of a JVM takes its species
   * by one rule from the int lane count, so they agree with each other under any configuration.
   */
  static List<String> mixedLaneCounts(byte[] bytes) {
    var described = new ArrayList<String>();
    var counts = new TreeSet<Integer>();
    for (var e : sizesUsed(bytes).entrySet()) {
      Integer element = DRIVE_ELEMENT_BITS.get(e.getKey());
      if (element == null) {
        continue;
      }
      for (int bits : e.getValue()) {
        counts.add(bits / element);
        described.add(e.getKey().substring(VECTOR_PREFIX.length()) + " at " + bits / element
            + " lanes");
      }
    }
    return counts.size() > 1 ? described : List.of();
  }

  /** What {@link #check} throws for a class of {@link #mixedLaneCounts}: the fix, named. */
  static String mixedLanesMessage(String className, List<String> described) {
    return className + " holds lanes of two widths (" + String.join(", ", described) + "), so a "
        + "lane configuration of the option matrix (lanesOverride=4, lanesOverride=16, "
        + "narrowHalfSpecies=true) puts a second vector species into the shared test JVM, which "
        + "refuses it there (VARKA-246, VARKA-499). Run the suite in a JVM of its own: "
        + "@VarkaSuite(ownJvm = true) on a JUnit class, VarkaOwnJvm mixed in last on a ScalaTest "
        + "one.";
  }

  /**
   * Throws if {@code bytes} would put a second species into this JVM; in {@code report} mode
   * appends the classes that name a non-preferred species to the report file instead.
   */
  public static void check(String className, byte[] bytes) {
    check(className, bytes, ownJvm() ? "off" : mode(), SHARED,
        lanesTrapApplies(VarkaMatrixRuns.CONFIG));
  }

  /**
   * Whether the lane-count rule of {@link #mixedLaneCounts} holds under the option matrix's
   * configuration {@code config}. It does not under one that changes the division's form: there
   * an int division runs through double lanes ({@code division=DOUBLE_RECIP},
   * {@code division=DOUBLE_DIV}), so every such kernel holds int lanes and half as many double
   * lanes, in every emitter suite. The rule guards against a lane configuration, and the matrix
   * changes one option a JVM, so a kernel of a division configuration never meets one; the same
   * suites run the rule under the defaults.
   */
  static boolean lanesTrapApplies(String config) {
    for (String pair : config.split(",")) {
      if (pair.trim().startsWith("division=")) {
        return false;
      }
    }
    return true;
  }

  /**
   * {@link #check} in {@code mode} against {@code registry}: the seam a test drives with a
   * registry of its own, so that it defines no class and leaves the JVM's registry as it was.
   * In {@code fail} mode a class of {@link #mixedLaneCounts} throws even where its species agree
   * with the registry's, since only a lane configuration would show it, hours later; in
   * {@code report} mode it is a line of its own, {@code lanes} in the third field.
   */
  static void check(String className, byte[] bytes, String mode, Registry registry) {
    check(className, bytes, mode, registry, true);
  }

  /** {@link #check} with the lane-count rule on or off; see {@link #lanesTrapApplies}. */
  static void check(String className, byte[] bytes, String mode, Registry registry,
      boolean lanesTrap) {
    if (mode.equals("off")) {
      return;
    }
    List<String> mixed = lanesTrap ? mixedLaneCounts(bytes) : List.of();
    if (mode.equals("report")) {
      List<String> second = secondSpecies(bytes);
      if (!second.isEmpty()) {
        report(className + "\t" + String.join(", ", second));
      }
      if (!mixed.isEmpty()) {
        report(className + "\tlanes\t" + String.join(", ", mixed));
      }
      return;
    }
    if (!mixed.isEmpty()) {
      throw new IllegalStateException(mixedLanesMessage(className, mixed));
    }
    List<String> conflicts = registry.admit(bytes);
    if (!conflicts.isEmpty()) {
      throw new IllegalStateException(className + " would put a second vector species into this "
          + "JVM: " + String.join(", ", conflicts) + ". Run it in a forked JVM, or where "
          + "-XX:MaxVectorSize makes this width the preferred one (VARKA-246); the shared test "
          + "JVM keeps one species per lane type.");
    }
  }

  /** Appends a line, headed by the running suite, to the report file, when one is named. */
  private static void report(String line) {
    String file = System.getProperty("varka.speciesGuard.report");
    if (file == null) {
      return;
    }
    try {
      Files.write(Paths.get(file), (suiteName() + "\t" + line + "\n")
          .getBytes(StandardCharsets.UTF_8), StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    } catch (IOException e) {
      throw new IllegalStateException(e);
    }
  }
}
