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
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFuzzTypes.FuzzType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFuzzTypes.IrLeaf;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFuzzTypes.NotApplicable;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFuzzTypes.Owed;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFuzzTypes.Present;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFuzzTypes.Site;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFuzzTypes.SparkColumns;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFuzzTypes.ValueSource;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTypes.Admission;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTypes.Info;

/**
 * The fuzz descriptor ({@link VarkaFuzzTypes}) held to the registry and to the grammar it names,
 * and written for the tools: {@code sql/varka/fuzz/types.tsv}, which {@code dev/varka_new_lane.py}
 * reads, is the table as it is. A site says {@code Present} only if the thing it names draws what
 * it claims, which is tested here and not declared.
 */
@VarkaSuite
public final class VarkaFuzzTypesSuite {

  private static final String REGEN =
      "VARKA_FUZZ_TYPES_REGEN=true build/sbt 'catalyst/testOnly *VarkaFuzzTypesSuite'";

  private static final String HEADER = "id\tlane\tir\tir_seed\tspark\tspark_columns\tvalues";

  private static Path file() {
    String home = System.getProperty("spark.test.home", System.getenv("SPARK_HOME"));
    assertTrue(home != null, "spark.test.home or SPARK_HOME is not set.");
    return Path.of(home, "sql", "varka", "fuzz", "types.tsv");
  }

  /** A site as the table spells it: {@code present}, {@code owed:<row>} or {@code n/a}. */
  private static String cell(Site<?> site) {
    return switch (site) {
      case Present<?> _ -> "present";
      case Owed<?> o -> "owed:" + o.row();
      case NotApplicable<?> _ -> "n/a";
    };
  }

  /** The table's lines, one per registry id in the registry's order. */
  static List<String> lines() {
    var out = new ArrayList<String>();
    out.add("# Written by VarkaFuzzTypesSuite from VarkaFuzzTypes (VARKA-352); regenerate with "
        + REGEN);
    out.add(HEADER);
    for (VarkaTypeId id : VarkaTypeId.values()) {
      FuzzType t = VarkaFuzzTypes.of(id);
      String lane = VarkaTypes.info(id).lane().map(Enum::name).orElse("-");
      String seed = t.ir() instanceof Present<IrLeaf> p ? Long.toString(p.value().seed()) : "-";
      String columns = t.spark() instanceof Present<SparkColumns> p
          ? p.value().columns().stream().map(VarkaFuzzTypes.Column::name)
              .collect(Collectors.joining(","))
          : "-";
      out.add(String.join("\t", id.name(), lane, cell(t.ir()), seed, cell(t.spark()), columns,
          cell(t.values())));
    }
    return out;
  }

  @Test
  @DisplayName("sql/varka/fuzz/types.tsv is the descriptor as it is")
  void committedFile() throws IOException {
    List<String> fresh = lines();
    if ("true".equals(System.getenv("VARKA_FUZZ_TYPES_REGEN"))) {
      Files.write(file(), fresh, StandardCharsets.UTF_8);
      return;
    }
    List<String> committed = Files.exists(file())
        ? Files.readAllLines(file(), StandardCharsets.UTF_8) : List.of();
    if (!committed.equals(fresh)) {
      fail("sql/varka/fuzz/types.tsv is not the descriptor; regenerate it with\n  " + REGEN
          + "\nThe descriptor today:\n  " + String.join("\n  ", fresh));
    }
  }

  @Test
  @DisplayName("every registry type has an entry under its own id, and no site names a bad row")
  void everyTypeHasAnEntry() {
    var row = Pattern.compile("\\d+[a-z]?");
    for (VarkaTypeId id : VarkaTypeId.values()) {
      FuzzType t = VarkaFuzzTypes.of(id);
      assertEquals(id, t.id());
      for (Site<?> site : List.of(t.ir(), t.spark(), t.values())) {
        if (site instanceof Owed<?> o) {
          assertTrue(row.matcher(o.row()).matches(), id + " is owed to \"" + o.row() + "\"");
        }
      }
    }
  }

  @Test
  @DisplayName("a declined type is never drawn, and a type the registry reads is not left out")
  void declinedIsNotApplicable() {
    for (Info i : VarkaTypes.all()) {
      FuzzType t = VarkaFuzzTypes.of(i.id());
      boolean declined = i.admission() instanceof Admission.Declined;
      for (Site<?> site : List.of(t.ir(), t.spark(), t.values())) {
        assertEquals(declined, site instanceof NotApplicable<?>, i.id().name());
      }
    }
  }

  @Test
  @DisplayName("a Present IR site is on its type's lane, and its sequence draws a column of it")
  void presentIrSitesDrawTheirLeaf() {
    int present = 0;
    for (VarkaTypeId id : VarkaTypeId.values()) {
      if (!(VarkaFuzzTypes.of(id).ir() instanceof Present<IrLeaf> p)) {
        continue;
      }
      present++;
      IrLeaf leaf = p.value();
      assertEquals(VarkaTypes.info(id).lane().orElseThrow(), leaf.lane(), id.name());
      // "col:3" for the int lane, "col:3:long" for the long one: the leaf's own spelling, with
      // its ordinal read as any digits, and not followed by more of a longer tag.
      String spelled = VarkaVectorIR.canonical(leaf.leaf().apply(0));
      var column = Pattern.compile("col:\\d+" + Pattern.quote(spelled.substring("col:0".length()))
          + "(?![\\w:])");
      boolean drawn = false;
      for (int k = 0; k < 100 && !drawn; k++) {
        Random rnd = VarkaIrGrammar.shapeRandom(leaf.seed(), k);
        drawn = leaf.roots().apply(rnd).stream()
            .anyMatch(r -> column.matcher(VarkaVectorIR.canonical(r)).find());
      }
      assertTrue(drawn, id + ": 100 shapes of seed " + leaf.seed() + " hold no " + spelled);
    }
    assertTrue(present > 0);
  }

  @Test
  @DisplayName("a value source reaches each of its edges, inside the bound it is given")
  void edgesAreReached() {
    long bound = 1L << 61;
    for (VarkaTypeId id : VarkaTypeId.values()) {
      if (!(VarkaFuzzTypes.of(id).values() instanceof Present<ValueSource> p)) {
        continue;
      }
      ValueSource source = p.value();
      Random main = new Random(7);
      Random own = new Random(11);
      var seen = new HashSet<Long>();
      for (int k = 0; k < 4000; k++) {
        long v = source.draw(main, own, bound, 4);
        if (source.numeric()) {
          assertTrue(Math.abs(v) <= bound, id + " drew " + v + " past the bound");
        }
        seen.add(v);
      }
      for (long edge : source.edges()) {
        long expected = source.numeric() ? Math.max(-bound, Math.min(bound, edge)) : edge;
        assertTrue(seen.contains(expected), id + " never drew its edge " + edge);
      }
    }
  }

  @Test
  @DisplayName("without edges a draw is the grammar's own, and with them the main stream is too")
  void theUniformArmIsTheGrammarsDraw() {
    ValueSource source = VarkaFuzzTypes.values(VarkaTypeId.LONG);
    long bound = VarkaIrGrammar.LONG_COLUMN_BOUND;
    for (int edgeOneIn : new int[] {ValueSource.NO_EDGES, 4}) {
      Random viaSource = new Random(3);
      Random plain = new Random(3);
      Random own = new Random(5);
      Random ownUntouched = new Random(5);
      var uniform = new ArrayList<Long>();
      var drawn = new ArrayList<Long>();
      for (int k = 0; k < 500; k++) {
        uniform.add(VarkaIrGrammar.within(plain, bound));
        drawn.add(source.draw(viaSource, own, bound, edgeOneIn));
      }
      assertEquals(plain.nextLong(), viaSource.nextLong(), "the main stream moved apart");
      if (edgeOneIn == ValueSource.NO_EDGES) {
        assertEquals(uniform, drawn);
        assertEquals(ownUntouched.nextLong(), own.nextLong(), "the second stream was drawn");
      } else {
        // About a quarter of the cells are edges, and an edge equals the uniform cell by chance
        // alone, so the cells that differ are some but far from all.
        long differing = IntStream.range(0, uniform.size())
            .filter(k -> !uniform.get(k).equals(drawn.get(k))).count();
        assertTrue(differing > 0 && differing < uniform.size() / 2, differing + " cells differ");
      }
    }
  }
}
