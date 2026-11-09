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

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Random;
import java.util.Set;
import java.util.StringJoiner;
import java.util.TreeMap;
import java.util.function.Function;
import java.util.function.IntPredicate;
import java.util.function.IntToLongFunction;
import java.util.stream.Collectors;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaFuzzTypes.ValueSource;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaIrGrammar.DrawnLong;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.ColumnRef;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Compare;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.CompareOp;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Cond;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntArith;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.IntNeg;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.LaneType;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.Overflow;

/**
 * The IR fuzzer's helpers for a sequence of one integral lane, which {@code VarkaIrFuzzSuite}
 * runs: the draw of a case around a {@link LongShapes}-drawn shape (its literals, batch length,
 * null patterns, masking, options and cells), shared by the long lane's sequences and the narrow
 * ones (VARKA-352 2.2), the count of the narrow draws per {@code narrowLanes} arm, and the reach
 * of the generators, asked of the IR's own constructors.
 *
 * <p>The draw's order is the long sequence's, coin for coin: a recorded failure
 * ({@code sql/varka/fuzz/known_failures.tsv}) names a seed and an iteration, and the pair must
 * name one case for as long as the entry is listed.
 *
 * @see VarkaIrGrammar.LongShapes
 */
public final class VarkaLaneFuzz {

  private VarkaLaneFuzz() {
  }

  /** The batch lengths a draw picks from: around the lane counts and the masked tail. */
  public static final List<Integer> LENGTHS =
      List.of(1, 3, 7, 15, 16, 17, 33, 64, 65, 100, 257, 1000);

  /** The null patterns a column is drawn with, by the names a failure's label prints. */
  public static final List<String> PATTERN_NAMES =
      List.of("null-free", "every-5th", "alternating", "all-null", "random");

  /**
   * Null pattern {@code which} over {@code length} rows: whether row {@code i} is null. The
   * random pattern draws its rows from {@code rnd} now, so the predicate draws nothing later.
   */
  public static IntPredicate pattern(Random rnd, int which, int length) {
    return switch (which) {
      case 0 -> i -> false;
      case 1 -> i -> i % 5 == 0;
      case 2 -> i -> i % 2 == 1;
      case 3 -> i -> true;
      default -> {
        boolean[] bits = new boolean[length];
        for (int i = 0; i < length; i++) {
          bits[i] = rnd.nextInt(3) == 0;
        }
        yield i -> bits[i];
      }
    };
  }

  /**
   * What a lane's cells and literals are drawn from: the lane, the bound of a literal, the
   * type's value source, the bound of column {@code k}'s cells (which the grammar drew the
   * shape's checked modes against), and the edge share, one cell in {@code edgeOneIn}, or none
   * at 0.
   */
  public record LaneSource(LaneType lane, long literalBound, ValueSource values,
      IntToLongFunction columnBound, int edgeOneIn) {

    /** The long lane's sequences: every column within {@code LONG_COLUMN_BOUND}. */
    public static LaneSource longLane(int edgeOneIn) {
      return new LaneSource(LaneType.LONG, VarkaIrGrammar.LONG_LITERAL_BOUND,
          VarkaFuzzTypes.values(VarkaTypeId.LONG), k -> VarkaIrGrammar.LONG_COLUMN_BOUND,
          edgeOneIn);
    }

    /**
     * A narrow sequence, {@code fuzz_byte} or {@code fuzz_short}: the type's value source and
     * the bounds {@link VarkaIrGrammar#narrowColumnBound} gives each column.
     */
    public static LaneSource narrow(LaneType lane, int edgeOneIn) {
      VarkaTypeId id = switch (lane) {
        case BYTE -> VarkaTypeId.BYTE;
        case SHORT -> VarkaTypeId.SHORT;
        case INT, LONG, FLOAT, DOUBLE ->
            throw new IllegalArgumentException("not a narrow lane: " + lane);
      };
      return new LaneSource(lane, VarkaIrGrammar.narrowLiteralBound(lane),
          VarkaFuzzTypes.values(id), k -> VarkaIrGrammar.narrowColumnBound(lane, k), edgeOneIn);
    }
  }

  /**
   * Case {@code iteration} of the sequence of {@code seed}: {@code drawn}, the shape the grammar
   * drew from {@code rnd}, then from {@code rnd} in this order the literals, the length, each
   * column's pattern, the masking coin, the options ({@code options}, the suite's draw of them)
   * and the cells; the edge coins come from {@code values}, the case's own stream. Forcing the
   * masked path is never drawn at length 1, where one null over a full bitmap would be an
   * all-null column. The label names everything a replay needs; the int and long sequences'
   * labels name the edge share only when there is one, and a narrow one's always does.
   */
  public static VarkaFuzzCase drawCase(long seed, int iteration, Random rnd, DrawnLong drawn,
      LaneSource source, Random values, Function<Random, VarkaEmitOptions> options) {
    int numInputs = drawn.numInputs();
    long[] lits = new long[drawn.numLiterals()];
    for (int k = 0; k < lits.length; k++) {
      lits[k] = VarkaIrGrammar.within(rnd, source.literalBound());
    }
    int length = LENGTHS.get(rnd.nextInt(LENGTHS.size()));
    int[] patternIds = new int[numInputs];
    for (int k = 0; k < numInputs; k++) {
      patternIds[k] = rnd.nextInt(PATTERN_NAMES.size());
    }
    boolean[][] nulls = new boolean[numInputs][length];
    for (int k = 0; k < numInputs; k++) {
      IntPredicate p = pattern(rnd, patternIds[k], length);
      for (int i = 0; i < length; i++) {
        nulls[k][i] = p.test(i);
      }
    }
    boolean forceMasked = length > 1 && rnd.nextInt(4) == 0;
    VarkaEmitOptions drawnOptions = options.apply(rnd);
    long[][] data = new long[numInputs][length];
    for (int k = 0; k < numInputs; k++) {
      long bound = source.columnBound().applyAsLong(k);
      for (int i = 0; i < length; i++) {
        data[k][i] = source.values().draw(rnd, values, bound, source.edgeOneIn());
      }
    }
    var patterns = new StringJoiner(",");
    for (int id : patternIds) {
      patterns.add(PATTERN_NAMES.get(id));
    }
    var literals = new StringJoiner(",");
    for (long l : lits) {
      literals.add(Long.toString(l));
    }
    String label = "lane=" + source.lane().name().toLowerCase(Locale.ROOT) + " seed=" + seed
        + " iteration=" + iteration + " roots="
        + drawn.roots().stream().map(VarkaVectorIR::canonical)
            .collect(Collectors.joining(", ", "[", "]"))
        + " options=" + (drawnOptions.isDefault() ? "(defaults)" : drawnOptions.canonical())
        + " length=" + length + " patterns=" + patterns + " literals=" + literals
        + " forceMasked=" + forceMasked
        + (source.edgeOneIn() > 0 || source.lane().isNarrow()
            ? " edges=" + source.edgeOneIn() : "");
    return new VarkaFuzzCase(source.lane(), drawn.roots(), numInputs, lits, length, nulls, data,
        forceMasked, drawnOptions, -1, -1, label);
  }

  /**
   * A narrow sequence's draws and answers per {@code narrowLanes} arm: the native arm computes
   * in the narrow species, the widened one in the int lane (VARKA-352 3 item 6), and the options
   * draw's coin picks between them. One tally per run of a sequence.
   */
  public static final class ArmTally {
    private final TreeMap<Boolean, int[]> counts = new TreeMap<>();

    private int[] of(boolean nativeArm) {
      return counts.computeIfAbsent(nativeArm, a -> new int[2]);
    }

    /** A draw that reached the plan at {@code narrowLanes=nativeArm}. */
    public void drawn(boolean nativeArm) {
      of(nativeArm)[0]++;
    }

    /** A draw at that arm that was compiled and compared with the reference. */
    public void answered(boolean nativeArm) {
      of(nativeArm)[1]++;
    }

    /** Each arm's answered and drawn counts, for the run's log and an assertion's message. */
    public String summary() {
      return counts.entrySet().stream()
          .map(e -> "narrowLanes=" + e.getKey() + " " + e.getValue()[1] + "/" + e.getValue()[0])
          .collect(Collectors.joining(", "));
    }

    /**
     * The arms of both that drew at least {@code minDraws} cases and answered none, and, once
     * the run drew {@code minDraws}, an arm never drawn. An arm of fewer draws is not asked for:
     * under {@code -Dvarka.fuzz.narrowIterations} or the matrix's configurations a coin may give
     * it a handful, all refused by design.
     */
    public List<Boolean> unanswered(int minDraws) {
      var arms = new ArrayList<Boolean>();
      int total = counts.values().stream().mapToInt(c -> c[0]).sum();
      for (boolean arm : new boolean[] {false, true}) {
        int[] c = counts.get(arm);
        if (c == null ? total >= minDraws : c[0] >= minDraws && c[1] == 0) {
          arms.add(arm);
        }
      }
      return arms;
    }
  }

  // -------------------------------------------------------------------------------------------
  // The reach of a generator, asked of the IR itself
  // -------------------------------------------------------------------------------------------

  /** The record node types of the sealed IR. */
  public static Set<Class<?>> recordNodeTypes() {
    var types = new LinkedHashSet<Class<?>>();
    addRecordTypes(VarkaVectorIR.class, types);
    return types;
  }

  private static void addRecordTypes(Class<?> c, Set<Class<?>> into) {
    Class<?>[] subs = c.getPermittedSubclasses();
    if (subs == null || subs.length == 0) {
      if (c.isRecord()) {
        into.add(c);
      }
      return;
    }
    for (Class<?> s : subs) {
      addRecordTypes(s, into);
    }
  }

  /** Every node type reachable from {@code node}, by simple name, added to {@code seen}. */
  public static void collectNodeTypes(Object node, Set<String> seen) {
    seen.add(node.getClass().getSimpleName());
    for (RecordComponent rc : node.getClass().getRecordComponents()) {
      Object v = invoke(rc, node);
      if (v instanceof VarkaVectorIR child) {
        collectNodeTypes(child, seen);
      }
    }
  }

  private static Object invoke(RecordComponent rc, Object node) {
    try {
      return rc.getAccessor().invoke(node);
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException("cannot read " + rc, e);
    }
  }

  /**
   * Whether a node type admits leaves of {@code lane}, asked of the type itself: its canonical
   * constructor is called once with every child a column of the lane (a condition child a
   * comparison of two), and a type that refuses such a child where it is built
   * ({@code IllegalArgumentException}) does not. An enum component takes its first constant, so
   * a conversion's target is {@code BYTE}. Read off the constructors rather than written as a
   * list, so a node type added to the IR is classified by what it does.
   */
  public static boolean admitsLane(Class<?> cls, LaneType lane) {
    var col = new ColumnRef(0, lane);
    var cond = new Compare(CompareOp.LT, col, col);
    RecordComponent[] components = cls.getRecordComponents();
    Object[] args = new Object[components.length];
    Class<?>[] types = new Class<?>[components.length];
    for (int k = 0; k < components.length; k++) {
      Class<?> t = components[k].getType();
      types[k] = t;
      if (t == Cond.class) {
        args[k] = cond;
      } else if (VarkaVectorIR.class.isAssignableFrom(t)) {
        args[k] = col;
      } else if (t == long.class) {
        args[k] = 1L;
      } else if (t == int.class) {
        args[k] = 1;
      } else if (t == boolean.class) {
        args[k] = false;
      } else if (t.isEnum()) {
        args[k] = t.getEnumConstants()[0];
      } else if (t == List.class) {
        args[k] = List.of(1, 2);
      } else {
        throw new IllegalStateException(cls.getSimpleName() + "." + components[k].getName()
            + " has a component type this probe cannot build: " + t.getName());
      }
    }
    try {
      cls.getDeclaredConstructor(types).newInstance(args);
      return true;
    } catch (InvocationTargetException e) {
      if (e.getCause() instanceof IllegalArgumentException) {
        return false;
      }
      throw new IllegalStateException(cls.getSimpleName() + " failed its probe", e.getCause());
    } catch (ReflectiveOperationException e) {
      throw new IllegalStateException(cls.getSimpleName() + " cannot be probed", e);
    }
  }

  /**
   * What the first {@code shapes} draws of a narrow sequence hold: the node types, the forms of
   * the arithmetic ({@code "ADD FAIL"}, {@code "abs WRAP"}), the checked modes found over a
   * subtree that reads a column ({@code "FAIL over a column"}), and every root that holds a
   * lane other than the sequence's, which a narrow tree must not.
   */
  public record NarrowReach(Set<String> nodeTypes, Set<String> forms, List<String> foreignRoots) {
  }

  public static NarrowReach narrowReach(LaneType lane, long seed, int shapes) {
    var nodeTypes = new HashSet<String>();
    var forms = new HashSet<String>();
    var foreign = new ArrayList<String>();
    for (int k = 0; k < shapes; k++) {
      DrawnLong drawn = VarkaIrGrammar.drawNarrowLaneShape(VarkaIrGrammar.shapeRandom(seed, k),
          lane);
      for (VarkaVectorIR root : drawn.roots()) {
        if (!VarkaVectorIR.lanesIn(root).equals(Set.of(lane))) {
          foreign.add("draw " + k + ": " + VarkaVectorIR.canonical(root));
        }
        collectNodeTypes(root, nodeTypes);
        addForms(root, forms);
      }
    }
    return new NarrowReach(nodeTypes, forms, foreign);
  }

  private static void addForms(VarkaVectorIR n, Set<String> forms) {
    Overflow mode = null;
    if (n instanceof IntArith a) {
      forms.add(a.op() + " " + a.mode());
      mode = a.mode();
    } else if (n instanceof IntNeg g) {
      forms.add((g.abs() ? "abs" : "negate") + " " + g.mode());
      mode = g.mode();
    }
    if (mode != null && mode != Overflow.WRAP && readsColumn(n)) {
      forms.add(mode + " over a column");
    }
    for (VarkaVectorIR child : VarkaVectorIR.childrenOf(n)) {
      addForms(child, forms);
    }
  }

  private static boolean readsColumn(VarkaVectorIR n) {
    if (n instanceof ColumnRef) {
      return true;
    }
    for (VarkaVectorIR child : VarkaVectorIR.childrenOf(n)) {
      if (readsColumn(child)) {
        return true;
      }
    }
    return false;
  }
}
