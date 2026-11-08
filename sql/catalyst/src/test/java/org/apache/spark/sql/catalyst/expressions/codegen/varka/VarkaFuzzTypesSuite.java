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
import static org.junit.jupiter.api.Assertions.fail;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFuzzTypes.FuzzType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFuzzTypes.IrLeaf;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFuzzTypes.Kind;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFuzzTypes.NotApplicable;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFuzzTypes.Owed;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFuzzTypes.Present;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFuzzTypes.Site;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFuzzTypes.SparkColumns;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFuzzTypes.ValueSource;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTypes.Admission;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaTypes.Info;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;

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

  private static final String HEADER =
      "id\tlane\tir\tir_seed\tir_form\tir_cells\tspark\tspark_columns\tvalues";

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
      IrLeaf leaf = t.ir() instanceof Present<IrLeaf> p ? p.value() : null;
      String seed = leaf == null ? "-" : Long.toString(leaf.seed());
      String form = leaf == null ? "-" : leaf.form() + (leaf.typed() ? "" : " (untyped)");
      String cells = leaf == null ? "-" : leaf.cells();
      String columns = t.spark() instanceof Present<SparkColumns> p
          ? p.value().columns().stream().map(VarkaFuzzTypes.Column::name)
              .collect(Collectors.joining(","))
          : "-";
      out.add(String.join("\t", id.name(), lane, cell(t.ir()), seed, form, cells,
          cell(t.spark()), columns, cell(t.values())));
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
  @DisplayName("a Present IR site's sequence draws a node reading a column as its type")
  void presentIrSitesDrawTheirLeaf() {
    int present = 0;
    for (VarkaTypeId id : VarkaTypeId.values()) {
      if (!(VarkaFuzzTypes.of(id).ir() instanceof Present<IrLeaf> p)) {
        continue;
      }
      present++;
      IrLeaf leaf = p.value();
      // A form that any column passes would hold of every sequence and test nothing.
      assertFalse(leaf.readIn(new ColumnRef(0)), id + ": a bare int column is a " + leaf.form());
      boolean drawn = false;
      for (int k = 0; k < 100 && !drawn; k++) {
        Random rnd = VarkaIrGrammar.shapeRandom(leaf.seed(), k);
        drawn = leaf.roots().apply(rnd).stream().anyMatch(leaf::readIn);
      }
      assertTrue(drawn, id + ": 100 shapes of seed " + leaf.seed() + " hold no " + leaf.form());
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
  @DisplayName("a draw takes one value of the main stream, as the grammar's does, edges or not")
  void theUniformArmIsTheGrammarsDraw() {
    long bound = VarkaIrGrammar.LONG_COLUMN_BOUND;
    for (VarkaTypeId id : VarkaTypeId.values()) {
      if (!(VarkaFuzzTypes.of(id).values() instanceof Present<ValueSource> p)) {
        continue;
      }
      ValueSource source = p.value();
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
        assertEquals(plain.nextLong(), viaSource.nextLong(), id + ": the main stream moved");
        if (edgeOneIn == ValueSource.NO_EDGES) {
          assertEquals(ownUntouched.nextLong(), own.nextLong(), id + ": the second stream moved");
          uniformOfItsKind(id, source, drawn, uniform, bound);
        } else if (source.kind() == Kind.INTEGRAL) {
          // About a quarter of the cells are edges, and an edge equals the uniform cell by
          // chance alone, so the cells that differ are some but far from all.
          long differing = IntStream.range(0, uniform.size())
              .filter(k -> !uniform.get(k).equals(drawn.get(k))).count();
          assertTrue(differing > 0 && differing < uniform.size() / 2, differing + " differ");
        }
      }
    }
  }

  /** The uniform cells are the type's: the grammar's integers, finite floats, 0 and 1. */
  private static void uniformOfItsKind(VarkaTypeId id, ValueSource source, List<Long> drawn,
      List<Long> within, long bound) {
    switch (source.kind()) {
      case INTEGRAL -> assertEquals(within, drawn, id.name());
      case FLOAT -> drawn.forEach(v -> {
        float x = Float.intBitsToFloat((int) (long) v);
        assertTrue(v >>> 32 == 0 && Float.isFinite(x) && Math.abs(x) <= bound, id + " " + x);
      });
      case DOUBLE -> drawn.forEach(v -> {
        double x = Double.longBitsToDouble(v);
        assertTrue(Double.isFinite(x) && Math.abs(x) <= bound, id + " " + x);
      });
      case BOOLEAN -> assertEquals(Set.of(0L, 1L), new HashSet<>(drawn), id.name());
    }
  }

  @Test
  @DisplayName("TIME's edges are TIME(6) values: whole microseconds of one day")
  void timeEdgesAreMicroseconds() {
    for (long edge : VarkaFuzzTypes.values(VarkaTypeId.TIME).edges()) {
      assertTrue(edge % 1000 == 0 && edge >= 0 && edge < 86_400_000_000_000L, "edge " + edge);
    }
  }
}
