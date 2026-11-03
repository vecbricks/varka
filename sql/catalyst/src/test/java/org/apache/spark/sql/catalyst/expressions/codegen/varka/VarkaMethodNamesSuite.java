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

import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMethodNames.DISPATCH;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMethodNames.DRIVERS;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMethodNames.GROUP_METHOD_KINDS;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMethodNames.driver;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMethodNames.epilogue;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMethodNames.groupMethod;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMethodNames.groupOf;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMethodNames.isEpilogue;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMethodNames.isLoop;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMethodNames.isStage;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMethodNames.loop;
import static org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMethodNames.stage;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.IntStream;
import java.util.stream.Stream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * {@code VarkaMethodNames} builds exactly the names the emitted classes have always had - the
 * names other suites write literally when they pin a class's layout - and reads every kind back,
 * including the methods that are not a group's.
 *
 * <p>The first Varka suite written in JUnit 5 (VARKA-312), ported from ScalaTest with its test
 * names unchanged: each {@code @DisplayName} is the sentence the Scala test was called by.
 */
@VarkaSuite
public final class VarkaMethodNamesSuite {

  private static List<String> filter(List<String> names, Predicate<String> keep) {
    return names.stream().filter(keep).toList();
  }

  @Test
  @DisplayName("the builders give the names the emitted classes have")
  void builders() {
    assertEquals("run", DISPATCH);
    assertTrue(driver(true).equals("runDense") && driver(false).equals("runMasked"));
    assertTrue(stage(true, 0).equals("stageDense0") && stage(false, 3).equals("stageMasked3"));
    assertTrue(loop(true, 0).equals("loopDense0") && loop(false, 12).equals("loopMasked12"));
    assertTrue(epilogue(true).equals("epilogueDense")
        && epilogue(false).equals("epilogueMasked"));
    assertTrue(epilogue(true, 0).equals("epilogueDense0")
        && epilogue(false, 12).equals("epilogueMasked12"));
    assertEquals(List.of("runDense", "runMasked"), DRIVERS);
    assertEquals(List.of("loopDense", "loopMasked", "epilogueDense", "epilogueMasked"),
        GROUP_METHOD_KINDS);
  }

  @Test
  @DisplayName("the readers tell every kind of method apart, on both sides")
  void readers() {
    List<String> names = List.of("<init>", "run", "runDense", "runMasked", "stageDense0",
        "stageMasked1", "loopDense0", "loopMasked7", "epilogueDense", "epilogueMasked",
        "epilogueDense0", "epilogueMasked7");
    assertEquals(List.of("runDense", "runMasked"), filter(names, VarkaMethodNames::isDriver));
    assertEquals(List.of("run", "runDense", "runMasked"),
        filter(names, VarkaMethodNames::isDriverOrDispatch));
    assertEquals(List.of("stageDense0", "stageMasked1"),
        filter(names, VarkaMethodNames::isStage));
    assertEquals(List.of("stageDense0"), filter(names, m -> isStage(m, true)));
    assertEquals(List.of("stageMasked1"), filter(names, m -> isStage(m, false)));
    assertEquals(List.of("loopDense0", "loopMasked7"), filter(names, VarkaMethodNames::isLoop));
    assertEquals(List.of("loopDense0"), filter(names, m -> isLoop(m, true)));
    assertEquals(List.of("loopMasked7"), filter(names, m -> isLoop(m, false)));
    assertEquals(List.of("epilogueDense", "epilogueMasked", "epilogueDense0", "epilogueMasked7"),
        filter(names, VarkaMethodNames::isEpilogue));
    assertEquals(List.of("epilogueDense", "epilogueDense0"),
        filter(names, m -> isEpilogue(m, true)));
    assertEquals(List.of("epilogueMasked", "epilogueMasked7"),
        filter(names, m -> isEpilogue(m, false)));
    assertEquals(List.of("loopDense0", "loopMasked7", "epilogueDense", "epilogueMasked",
        "epilogueDense0", "epilogueMasked7"), filter(names, VarkaMethodNames::isGroupMethod));
  }

  @Test
  @DisplayName("a group's methods by column are the cost table's four kinds")
  void groupMethodsByColumn() {
    assertEquals(List.of(loop(true, 5), loop(false, 5), epilogue(true, 5), epilogue(false, 5)),
        IntStream.range(0, GROUP_METHOD_KINDS.size()).mapToObj(k -> groupMethod(k, 5)).toList());
  }

  @Test
  @DisplayName("a group method's group is read off its name, and every other method has none")
  void groupOfName() {
    assertTrue(groupOf("loopMasked3") == 3 && groupOf("epilogueDense12") == 12);
    for (String m : List.of("<init>", "run", "runDense", "stageDense0", "epilogueDense",
        "epilogueMasked")) {
      assertEquals(-1, groupOf(m), m);
    }
    for (boolean dense : new boolean[] {true, false}) {
      for (int g : new int[] {0, 1, 99}) {
        assertTrue(groupOf(loop(dense, g)) == g && groupOf(epilogue(dense, g)) == g);
      }
    }
  }

  @Test
  @DisplayName("the scripts outside Java that mirror the names still name what the builders "
      + "produce")
  void mirrors() throws IOException {
    // Python and shell cannot call this class, so each mirror is held to the names it relies on:
    // a rename here fails this test until the mirror follows it (see VarkaMethodNames's doc).
    var figure21 = new ArrayList<>(GROUP_METHOD_KINDS);
    figure21.addAll(DRIVERS);
    List<String> figure27 = Stream.concat(
        Stream.of(GROUP_METHOD_KINDS.get(0), GROUP_METHOD_KINDS.get(2)), DRIVERS.stream())
        .toList();
    Map<String, List<String>> mirrors = Map.of(
        "dev/varka_deopt_cycle.py", List.of(GROUP_METHOD_KINDS.get(0)),
        "dev/varka_emit.sh", List.of(loop(true, 0)),
        "dev/varka_inlining_cliff.py", List.of(loop(true, 0)),
        "sql/varka/plans/figures/fig21.py", figure21,
        "sql/varka/plans/figures/fig27.py", figure27);
    String home = System.getProperty("spark.test.home", System.getenv("SPARK_HOME"));
    assertTrue(home != null, "spark.test.home or SPARK_HOME is not set.");
    for (var mirror : mirrors.entrySet()) {
      String text = Files.readString(Path.of(home, mirror.getKey()), StandardCharsets.UTF_8);
      for (String name : mirror.getValue()) {
        assertTrue(text.contains(name), mirror.getKey() + " no longer names " + name);
      }
    }
  }

  /** Every method a side's builders name, over a few stage and group indices. */
  private static List<String> side(boolean dense) {
    return List.of(driver(dense), stage(dense, 0), stage(dense, 3), loop(dense, 0),
        loop(dense, 12), epilogue(dense), epilogue(dense, 0), epilogue(dense, 12));
  }

  @Test
  @DisplayName("the tier-split job's method patterns name one side's methods, the dispatch or "
      + "every method, and nothing of Spark's")
  void tierSplitPatterns() throws IOException {
    // HotSpot's patterns are slashed class globs and method globs; one that matches nothing passes
    // silently, so each is held to the names the builders produce (VARKA-557).
    String kernels = VarkaShapeCacheImpl.CLASS_NAME_PREFIX.replace('.', '/') + "*";
    List<String> dense = side(true);
    List<String> masked = side(false);
    var every = new ArrayList<String>(dense);
    every.addAll(masked);
    every.add(DISPATCH);
    // A method glob after the class glob: written out in a directive file, after {KERNELS} in
    // the script, whose KERNELS constant must then be the class glob.
    Pattern use = Pattern.compile("(?:" + Pattern.quote(kernels) + "|\\{KERNELS\\})\\.([\\w*]+)");
    String home = System.getProperty("spark.test.home", System.getenv("SPARK_HOME"));
    assertTrue(home != null, "spark.test.home or SPARK_HOME is not set.");
    Path script = Path.of(home, "dev", "varka_tier_split.py");
    var files = new ArrayList<Path>(List.of(script));
    try (Stream<Path> json = Files.list(Path.of(home, "sql", "varka", "ci", "tier-split"))) {
      files.addAll(json.filter(f -> f.toString().endsWith(".json")).sorted().toList());
    }
    String scriptText = Files.readString(script, StandardCharsets.UTF_8);
    assertTrue(scriptText.contains("KERNELS = \"" + kernels + "\""),
        "dev/varka_tier_split.py's KERNELS is not " + kernels);
    var globs = new HashSet<String>();
    for (Path f : files) {
      String text = Files.readString(f, StandardCharsets.UTF_8);
      // No other spelling of the kernel classes: a stale package or prefix would match nothing.
      assertEquals(text.split("/VarkaFusedProjection_", -1).length - 1,
          text.split(Pattern.quote(kernels), -1).length - 1, f + " names the classes otherwise");
      Matcher m = use.matcher(text);
      while (m.find()) {
        globs.add(m.group(1));
      }
    }
    assertEquals(Set.of("*", "*Dense*", "*Masked*", "run"), globs);
    for (String glob : globs) {
      Pattern p = Pattern.compile(glob.replace("*", ".*"));
      List<String> named = filter(every, n -> p.matcher(n).matches());
      assertTrue(named.equals(every) || named.equals(dense) || named.equals(masked)
          || named.equals(List.of(DISPATCH)), glob + " names " + named);
    }
  }
}
