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

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTimeoutPreemptively;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.RecordComponent;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.*;
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR.WidenDecimal;
import org.apache.spark.sql.internal.SQLConf;

/**
 * The reproduction file's pieces below the query (VARKA-315): the canonical-IR parser a replay
 * rebuilds the kernel with, the file's text form, the writer's bounds, and the replay's refusals.
 * The planted failures through Spark's own write sites, and their replays, are
 * {@code VarkaReproductionEndToEndSuite}'s.
 */
@VarkaSuite
public final class VarkaReproductionSuite {

  @TempDir
  Path dir;

  @AfterEach
  void forgetWrites() {
    VarkaReproduction.resetForTesting();
    SQLConf.get().unsetConf(SQLConf.VARKA_REPRODUCTION_DIRECTORY().key());
    SQLConf.get().unsetConf(SQLConf.VARKA_REPRODUCTION_ENABLED().key());
    SQLConf.get().unsetConf(SQLConf.VARKA_REPRODUCTION_MAX_BATCH_BYTES().key());
  }

  /** One tree with every node type, as {@code VarkaShapeCacheSuite}'s pinned hash has it. */
  private static VarkaVectorIR everyNodeType() {
    var c = new ColumnRef(0);
    var l = new LiteralSlot(0);
    Cond cond = new And(
        new Or(new Compare(CompareOp.LT, c, l), new Not(new Compare(CompareOp.EQ, c, l))),
        new And(new Compare(CompareOp.GE, c, l), new IsNotNull(c)));
    VarkaVectorIR chrono = new Greatest(
        new Least(new Greatest(new Year(c), new Month(c)),
            new Greatest(new DayOfMonth(c), new Least(new Quarter(c),
                new Least(new LastDay(c), new TruncDate(c, TruncLevel.YEAR))))),
        new Greatest(new DayOfYear(c), new WeekOfYear(new ThursdayOf(c))));
    return new IfElse(cond,
        new Greatest(new AddDays(c, l), new SubDays(c, l)),
        new Least(new DateDiff(chrono, new DayOfWeek(c)),
            new Least(new Least(new WeekDay(c), new DayOfWeekIso(c)),
                new Least(new NextDay(c, l),
                    new Least(new AddMonths(c, l),
                        new Least(new TruncDateDynamic(c, c),
                            new Least(new MakeDate(c, l, l, false),
                                new MakeDate(new GuardedDay(c), new IntNeg(Overflow.FAIL, l),
                                    new IntArith(IntOp.MUL, Overflow.NULL,
                                        new ConstDivide(c, -7), BoundedDivide.of(c, 7, 100)),
                                    true))))))));
  }

  /** The nodes the tree above does not reach, at the lanes that carry them. */
  private static List<VarkaVectorIR> theRest() {
    var x = new ColumnRef(1, LaneType.LONG);
    return List.of(
        new IfElse(new InRanges(new ColumnRef(0), List.of(-9, -3, 0, 5, 7, 40)),
            new ColumnRef(0), new LiteralSlot(1)),
        new NarrowLane(new ConstDivide(new GuardedRange(
            new IntArith(IntOp.ADD, Overflow.WRAP, x, new LiteralSlot(0, LaneType.LONG)),
            -1000L, 86_400_000_000_000L), 1000L, ConstDivide.EXACT_DIVIDEND_BOUND)),
        // The guarded product of two long columns (VARKA-611).
        new GuardedProduct(x, new ColumnRef(0, LaneType.LONG)),
        // A decimal column beside an int one widened to its lane.
        new IntArith(IntOp.ADD, Overflow.WRAP, new DecimalColumn(2, LaneType.LONG, 9),
            new WidenLane(new ColumnRef(0), LaneType.LONG)),
        // The two words of a 128-bit decimal column (VARKA-359).
        new IntArith(IntOp.ADD, Overflow.WRAP,
            new VarkaVectorIR.DecimalWord(2, VarkaVectorIR.Word.LOW, 38),
            new VarkaVectorIR.DecimalWord(2, VarkaVectorIR.Word.HIGH, 38)),
        // A decimal output over a long column's arithmetic.
        new WidenDecimal(new IntArith(IntOp.ADD, Overflow.WRAP, x,
            new LiteralSlot(0, LaneType.LONG))),
        new CondValue(new NullSafeEqual(new ColumnRef(0), new LiteralSlot(0)), LaneType.INT),
        // The missing ELSE of a CASE WHEN, at the long lane, whose lane the leaf names.
        new IfElse(new Compare(CompareOp.LT, x, new LiteralSlot(0, LaneType.LONG)), x,
            new VarkaVectorIR.NullValue(LaneType.LONG)),
        new BitCount(new BitOp(BitOpKind.XOR, new ColumnRef(0), new LiteralSlot(0))),
        // An int column converted to the float lane, at the int lane's count.
        new ConvertLane(new ColumnRef(0), LaneType.FLOAT),
        // A double column's bits at the long lane, an fdlibm port's word access (VARKA-345).
        new ReinterpretLane(new ColumnRef(0, LaneType.DOUBLE), LaneType.LONG),
        // A floating lane's square root over its IEEE division, whose zero divisor nulls a lane.
        new MathUnary(MathFn.SQRT, new IntArith(IntOp.DIV, Overflow.NULL,
            new ColumnRef(0, LaneType.DOUBLE), new LiteralSlot(0, LaneType.DOUBLE))),
        // The single-rounding multiply-add over double columns and a literal (VARKA-342).
        new VarkaVectorIR.Fma(new ColumnRef(0, LaneType.DOUBLE), new ColumnRef(1, LaneType.DOUBLE),
            new LiteralSlot(0, LaneType.DOUBLE)),
        // The rint construction's node over a double column (VARKA-344).
        new VarkaVectorIR.RoundToIntegral(VarkaVectorIR.RoundMode.RINT,
            new ColumnRef(0, LaneType.DOUBLE)),
        // The canonicalisation over a float sum (VARKA-337).
        VarkaVectorIR.CanonicalFloat.of(new IntArith(IntOp.ADD, Overflow.WRAP,
            new ColumnRef(0, LaneType.FLOAT), new LiteralSlot(0, LaneType.FLOAT))),
        // Murmur3 over a double's canonical bits, its two halves (VARKA-337).
        VarkaEmitterHashSuite.step(false, new LiteralSlot(0), new ColumnRef(0, LaneType.DOUBLE),
            new LiteralSlot(0, LaneType.LONG)),
        // XXH64 over a double's canonical bits, the step and the reinterpretation built by name.
        new VarkaVectorIR.HashStep(VarkaVectorIR.HashMix.XXH64_LONG,
            new LiteralSlot(0, LaneType.LONG), new VarkaVectorIR.CanonicalBits(
                VarkaVectorIR.CanonicalFloat.of(new ColumnRef(0, LaneType.DOUBLE)))),
        // A double comparison crossed to the long lane of the choice's values (VARKA-343).
        new IfElse(new CrossLane(new Compare(CompareOp.GT, new ColumnRef(2, LaneType.DOUBLE),
            new LiteralSlot(1, LaneType.DOUBLE)), LaneType.LONG), x,
            new LiteralSlot(0, LaneType.LONG)),
        // Over comparisons.
        new BoolOutput(new Or(new Not(new IsNotNull(new ColumnRef(0))),
            new Compare(CompareOp.LT, new ColumnRef(1), new LiteralSlot(0)))),
        // Over boolean columns and constants, at the int lane.
        new BoolOutput(new CondIf(new BoolColumn(0), new BoolCompare(CompareOp.LE,
            new BoolColumn(1), new CondConst(Truth.NULL)),
            new BoolNullSafeEq(new BoolColumn(0), new CondConst(Truth.TRUE)))),
        // A boolean column beside a long comparison, under a long choice.
        new IfElse(new BoolNullSafeEq(new BoolColumn(0, LaneType.LONG),
            new Compare(CompareOp.LT, x, new LiteralSlot(0, LaneType.LONG))),
            new CondValue(new CondConst(Truth.FALSE, LaneType.LONG), LaneType.LONG), x));
  }

  /** The records under {@code type}, through its sealed subinterfaces. */
  private static void nodeTypes(Class<?> type, Set<Class<?>> out) {
    if (type.isSealed()) {
      Arrays.stream(type.getPermittedSubclasses()).forEach(t -> nodeTypes(t, out));
    } else {
      out.add(type);
    }
  }

  /** The node types of {@code node}'s tree. */
  private static void reached(VarkaVectorIR node, Set<Class<?>> out) {
    out.add(node.getClass());
    for (VarkaVectorIR child : VarkaVectorIR.childrenOf(node)) {
      reached(child, out);
    }
  }

  private static void assertRoundTrips(VarkaVectorIR tree) {
    String text = VarkaVectorIR.canonical(tree);
    VarkaVectorIR parsed = VarkaCanonicalIr.parse(text);
    assertEquals(text, VarkaVectorIR.canonical(parsed));
    assertEquals(normalised(tree), parsed, () -> "the tree read back differs: " + text);
  }

  /**
   * {@code tree} as the parser builds it from its rendering: a {@code ConstDivide} off the int
   * lane carries {@code EXACT_DIVIDEND_BOUND}, the one field the rendering leaves out and the
   * parser fills by rule. Everything else is kept, so record equality covers the fields the
   * rendering carries and the ones derived from them.
   */
  private static VarkaVectorIR normalised(VarkaVectorIR tree) {
    VarkaVectorIR[] children = VarkaVectorIR.childrenOf(tree);
    VarkaVectorIR node = children.length == 0 ? tree : VarkaVectorIR.withChildren(tree,
        Arrays.stream(children).map(VarkaReproductionSuite::normalised)
            .toArray(VarkaVectorIR[]::new));
    if (node instanceof ConstDivide d && d.child().laneType() != LaneType.INT) {
      return new ConstDivide(d.child(), d.divisor(), ConstDivide.EXACT_DIVIDEND_BOUND);
    }
    return node;
  }

  /** {@code node} and every node under it. */
  private static void subtrees(VarkaVectorIR node, List<VarkaVectorIR> out) {
    out.add(node);
    for (VarkaVectorIR child : VarkaVectorIR.childrenOf(node)) {
      subtrees(child, out);
    }
  }

  /**
   * {@code node} rebuilt through its canonical constructor with one enum or boolean component
   * changed to each other value it can hold, the rest kept; a value the constructor refuses with
   * an {@code IllegalArgumentException} is skipped. A trailing {@code int hash} component keeps
   * its value, since every compact constructor recomputes it.
   */
  private static List<VarkaVectorIR> variants(VarkaVectorIR node)
      throws ReflectiveOperationException {
    RecordComponent[] cs = node.getClass().getRecordComponents();
    var types = new Class<?>[cs.length];
    var values = new Object[cs.length];
    for (int i = 0; i < cs.length; i++) {
      types[i] = cs[i].getType();
      cs[i].getAccessor().setAccessible(true);
      values[i] = cs[i].getAccessor().invoke(node);
    }
    Constructor<?> canonical = node.getClass().getDeclaredConstructor(types);
    canonical.setAccessible(true);
    var out = new ArrayList<VarkaVectorIR>();
    for (int i = 0; i < cs.length; i++) {
      Object[] choices = types[i].isEnum() ? types[i].getEnumConstants()
          : types[i] == boolean.class ? new Object[] {false, true} : new Object[0];
      for (Object choice : choices) {
        if (choice.equals(values[i])) {
          continue;
        }
        Object[] changed = values.clone();
        changed[i] = choice;
        try {
          out.add((VarkaVectorIR) canonical.newInstance(changed));
        } catch (InvocationTargetException e) {
          if (!(e.getCause() instanceof IllegalArgumentException)) {
            throw e;
          }
        }
      }
    }
    return out;
  }

  private static String hash(List<VarkaVectorIR> roots, int inputs, int literals) {
    return VarkaShapeCacheImpl.shapeHash(
        new VarkaShapeKey(roots, inputs, literals, VarkaEmitOptions.DEFAULTS));
  }

  private static List<VarkaVectorIR> reparsed(List<VarkaVectorIR> roots) {
    var out = new ArrayList<VarkaVectorIR>();
    roots.forEach(r -> out.add(VarkaCanonicalIr.parse(VarkaVectorIR.canonical(r))));
    return out;
  }

  private static byte[] emit(List<VarkaVectorIR> roots, int inputs, int literals) {
    return VarkaLoopEmitter.emit("org.apache.spark.sql.varka.execution.VarkaReproductionProbe",
        roots, inputs, literals, "Probe.java", "shape probe", VarkaEmitOptions.DEFAULTS);
  }

  /** A chain whose every level uses the one below twice, so its tree text doubles a level. */
  private static VarkaVectorIR sharedChain(int depth) {
    VarkaVectorIR node = new ColumnRef(0);
    for (int i = 0; i < depth; i++) {
      node = new IntArith(IntOp.ADD, Overflow.WRAP, node, node);
    }
    return node;
  }

  @Test
  @DisplayName("the tree text's length is counted node by node, as the rendering is (VARKA-630)")
  void canonicalLengthIsTheRenderingsLength() {
    List<VarkaVectorIR> trees = new ArrayList<>(theRest());
    trees.add(everyNodeType());
    trees.add(sharedChain(12));
    for (VarkaVectorIR tree : trees) {
      assertEquals(VarkaVectorIR.canonical(tree).length(), VarkaVectorIR.canonicalLength(tree),
          () -> VarkaVectorIR.canonical(tree));
    }
  }

  @Test
  @DisplayName("a shape under the tree limit keeps its tree text, so its committed hash stays")
  void shortShapeKeepsItsTreeText() {
    List<VarkaVectorIR> trees = new ArrayList<>(theRest());
    trees.add(everyNodeType());
    trees.add(sharedChain(16));
    String expected = trees.stream().map(t -> VarkaVectorIR.canonical(t) + "\n")
        .collect(java.util.stream.Collectors.joining());
    assertEquals(expected, VarkaVectorIR.shapeText(trees));
  }

  @Test
  @DisplayName("the DAG text keeps every field of a node, an InRanges node's bounds included")
  void dagTextKeepsWhatTheTreeTextKeeps() {
    VarkaVectorIR a = new InRanges(new ColumnRef(0), List.of(1, 5));
    VarkaVectorIR b = new InRanges(new ColumnRef(0), List.of(7, 9));
    assertFalse(VarkaVectorIR.canonical(a).equals(VarkaVectorIR.canonical(b)));
    assertFalse(VarkaVectorIR.canonicalDag(List.of(a)).equals(
        VarkaVectorIR.canonicalDag(List.of(b))));
    // Two outputs that differ only in the bounds stay two nodes, over one column: the marker, three
    // nodes and the roots.
    String both = VarkaVectorIR.canonicalDag(List.of(a, b));
    assertEquals(5, both.split("\n").length, both);
    // The corpus's DAG text is stable across a fresh copy, and a different tree differs.
    List<VarkaVectorIR> trees = new ArrayList<>(theRest());
    trees.add(everyNodeType());
    for (VarkaVectorIR tree : trees) {
      String dag = VarkaVectorIR.canonicalDag(List.of(tree));
      assertTrue(dag.startsWith("dag\n") && dag.endsWith("\n"), dag);
      assertEquals(dag, VarkaVectorIR.canonicalDag(List.of(VarkaCanonicalIr.parse(
          VarkaVectorIR.canonical(tree)))));
    }
  }

  @Test
  @DisplayName("two equal shape keys over a deep shared chain compare in linear time (VARKA-630)")
  void deepSharedKeysCompareInLinearTime() {
    // Built twice, so the two share no object: a record's equals would compare the shared node
    // once per path, 2^64 here.
    var one = new VarkaShapeKey(List.of(sharedChain(64)), 1, 0);
    var two = new VarkaShapeKey(List.of(sharedChain(64)), 1, 0);
    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertEquals(one, two));
    assertEquals(one.hashCode(), two.hashCode());
    // A chain over another column differs at its foot, and its root hash with it, so this
    // only checks that a differing key is refused, not the walk past the hash.
    VarkaVectorIR other = new ColumnRef(1);
    for (int i = 0; i < 64; i++) {
      other = new IntArith(IntOp.ADD, Overflow.WRAP, other, other);
    }
    var different = new VarkaShapeKey(List.of(other), 1, 0);
    assertTimeoutPreemptively(Duration.ofSeconds(5), () -> assertNotEquals(one, different));
  }

  @Test
  @DisplayName("sameStructure agrees with equals on every node type and between them")
  void sameStructureAgreesWithEquals() {
    List<VarkaVectorIR> first = new ArrayList<>(theRest());
    first.add(everyNodeType());
    first.add(sharedChain(12));
    List<VarkaVectorIR> second = new ArrayList<>(theRest());
    second.add(everyNodeType());
    second.add(sharedChain(12));
    for (int i = 0; i < first.size(); i++) {
      for (int j = 0; j < second.size(); j++) {
        int a = i;
        int b = j;
        assertEquals(first.get(i).equals(second.get(j)),
            VarkaVectorIR.sameStructure(first.get(i), second.get(j)),
            () -> "trees " + a + " and " + b);
      }
      assertTrue(VarkaVectorIR.sameStructure(first.get(i), second.get(i)), "tree " + i);
    }
  }

  @Test
  @DisplayName("a deep shared chain has a DAG text, one line a node, where its tree has none")
  void deepSharedChainRendersAsADag() {
    VarkaVectorIR chain = sharedChain(64);
    assertTrue(VarkaVectorIR.canonicalLength(chain) > VarkaVectorIR.TREE_TEXT_LIMIT);
    String text = VarkaVectorIR.shapeText(List.of(chain));
    assertTrue(text.startsWith("dag\n"), text);
    // 64 adds and the column, then the roots line.
    assertEquals(1 + 65 + 1, text.split("\n").length, text);
    assertTrue(text.endsWith("roots 64\n"), text);
    // Two equal chains built as distinct objects, as the lane lifts rebuild a shared node, give
    // one text, and two different chains give two.
    assertEquals(text, VarkaVectorIR.shapeText(List.of(sharedChain(64))));
    assertFalse(text.equals(VarkaVectorIR.shapeText(List.of(sharedChain(63)))));
    // A message carries the start of it and the tree's size, not the tree.
    String brief = VarkaVectorIR.canonicalBrief(chain);
    assertTrue(brief.length() < 400 && brief.startsWith("dag\n"), brief);
    assertEquals(VarkaVectorIR.canonicalDag(List.of(chain)), VarkaVectorIR.canonicalExact(chain));
  }

  @Test
  @DisplayName("the canonical IR reads back every node type, at both lanes")
  void everyNodeTypeRoundTrips() {
    assertRoundTrips(everyNodeType());
    theRest().forEach(VarkaReproductionSuite::assertRoundTrips);
    // Complete over the sealed interface, so a new node type fails here until a tree above
    // reaches it.
    Set<Class<?>> all = new HashSet<>();
    nodeTypes(VarkaVectorIR.class, all);
    Set<Class<?>> got = new HashSet<>();
    reached(everyNodeType(), got);
    theRest().forEach(t -> reached(t, got));
    Set<Class<?>> missing = new HashSet<>(all);
    missing.removeAll(got);
    assertTrue(missing.isEmpty(), () -> "node types no round trip reaches: " + missing);
  }

  @Test
  @DisplayName("every record reads back in every enum and boolean form its constructor admits")
  void everyFormRoundTrips() throws ReflectiveOperationException {
    var trees = new ArrayList<VarkaVectorIR>();
    trees.add(everyNodeType());
    trees.addAll(theRest());
    var nodes = new ArrayList<VarkaVectorIR>();
    trees.forEach(t -> subtrees(t, nodes));
    int built = 0;
    for (VarkaVectorIR node : nodes) {
      for (VarkaVectorIR variant : variants(node)) {
        assertRoundTrips(variant);
        built++;
      }
    }
    // A sweep whose reflection found nothing to rebuild would pass without checking a form.
    assertTrue(built > 0, "the sweep built no variant of any node");
  }

  @Test
  @DisplayName("every comparison reads back, the four unsigned ones over the long lane among "
      + "them and the three IEEE ones over the double lane")
  void everyCompareOpRoundTrips() {
    var a = new ColumnRef(0, LaneType.LONG);
    var b = new LiteralSlot(0, LaneType.LONG);
    var x = new ColumnRef(0, LaneType.DOUBLE);
    var y = new LiteralSlot(0, LaneType.DOUBLE);
    for (CompareOp op : CompareOp.values()) {
      // A condition value is integral, so a double compare reads back under a pick.
      assertRoundTrips(op.isIeee() ? new IfElse(new Compare(op, x, y), x, y)
          : new CondValue(new Compare(op, a, b), LaneType.LONG));
    }
  }

  @Test
  @DisplayName("every tree the IR grammar draws reads back to its shape hash, and the long "
      + "lane's to the same bytes")
  void drawnTreesRoundTrip() {
    for (int k = 0; k < 400; k++) {
      var drawn = VarkaIrGrammar.drawShape(VarkaIrGrammar.shapeRandom(VarkaIrGrammar.FUZZ_SEED, k));
      assertEquals(hash(drawn.roots(), drawn.numInputs(), drawn.numLiterals()),
          hash(reparsed(drawn.roots()), drawn.numInputs(), drawn.numLiterals()), "shape " + k);
    }
    for (int k = 0; k < 400; k++) {
      Random rnd = VarkaIrGrammar.shapeRandom(VarkaIrGrammar.LONG_FUZZ_SEED, k);
      var drawn = VarkaIrGrammar.drawLongShape(rnd);
      var parsed = reparsed(drawn.roots());
      assertEquals(hash(drawn.roots(), drawn.numInputs(), drawn.numLiterals()),
          hash(parsed, drawn.numInputs(), drawn.numLiterals()), "long shape " + k);
      // A long-lane division's dividend bound is not in the rendering; the parser's choice must
      // change no byte, as ConstDivide says the bound does not.
      if (k < 60) {
        assertArrayEquals(emit(drawn.roots(), drawn.numInputs(), drawn.numLiterals()),
            emit(parsed, drawn.numInputs(), drawn.numLiterals()), "long shape " + k);
      }
    }
    for (int k = 0; k < 400; k++) {
      var drawn = VarkaIrGrammar.drawNarrowShape(
          VarkaIrGrammar.shapeRandom(VarkaIrGrammar.NARROW_FUZZ_SEED, k));
      assertEquals(hash(drawn.roots(), drawn.numInputs(), drawn.numLiterals()),
          hash(reparsed(drawn.roots()), drawn.numInputs(), drawn.numLiterals()),
          "narrow shape " + k);
    }
    var wide = VarkaIrGrammar.drawWideShape(new Random(315));
    assertEquals(hash(wide.roots(), wide.numInputs(), wide.numLiterals()),
        hash(reparsed(wide.roots()), wide.numInputs(), wide.numLiterals()));
  }

  @Test
  @DisplayName("a rendering that is not the canonical IR is refused rather than read loosely")
  void malformedIrIsRefused() {
    assertThrows(IllegalArgumentException.class, () -> VarkaCanonicalIr.parse("(addDays col:0)"));
    assertThrows(IllegalArgumentException.class, () -> VarkaCanonicalIr.parse("(noSuch col:0)"));
    assertThrows(IllegalArgumentException.class,
        () -> VarkaCanonicalIr.parse("(addDays col:0 lit:0) col:1"));
    // Two spaces read as one token boundary, and the re-rendering catches the difference.
    assertThrows(IllegalArgumentException.class,
        () -> VarkaCanonicalIr.parse("(addDays  col:0 lit:0)"));
    // A decimal leaf names its lane and precision, on the long lane and from 1 to 18.
    assertThrows(IllegalArgumentException.class, () -> VarkaCanonicalIr.parse("dec:0:long"));
    assertThrows(IllegalArgumentException.class, () -> VarkaCanonicalIr.parse("dec:0:int:p9"));
    assertThrows(IllegalArgumentException.class,
        () -> VarkaCanonicalIr.parse("dec:0:long:p19"));
    assertThrows(IllegalArgumentException.class, () -> VarkaCanonicalIr.parse("dec:0:long:9"));
    // A decimal word names its word and a precision from 19 to 38.
    assertThrows(IllegalArgumentException.class, () -> VarkaCanonicalIr.parse("decw:0:p38"));
    assertThrows(IllegalArgumentException.class, () -> VarkaCanonicalIr.parse("decw:0:mid:p38"));
    assertThrows(IllegalArgumentException.class, () -> VarkaCanonicalIr.parse("decw:0:lo:p18"));
    // A boolean head over values, an unknown truth, and a boolean leaf or constant with a part
    // too many.
    assertThrows(IllegalArgumentException.class,
        () -> VarkaCanonicalIr.parse("(boolCmp:LT col:0 lit:0)"));
    assertThrows(IllegalArgumentException.class, () -> VarkaCanonicalIr.parse("(condConst:MAYBE)"));
    assertThrows(IllegalArgumentException.class, () -> VarkaCanonicalIr.parse("bool:0:long:p9"));
    assertThrows(IllegalArgumentException.class,
        () -> VarkaCanonicalIr.parse("(condConst:TRUE:long:x)"));
  }

  @Test
  @DisplayName("the auto chain cap renders as no option, and an explicit 16 renders and reads back "
      + "(VARKA-629)")
  void autoChainCapRendersAsNothing() {
    assertFalse(
        VarkaErrorContext.renderOptions(VarkaEmitOptions.DEFAULTS).contains("axChainDepth"));
    var sixteen = VarkaEmitOptions.DEFAULTS.withMaxChainDepth(16);
    String text = VarkaErrorContext.renderOptions(sixteen);
    assertTrue(text.contains("maxChainDepth=16"), text);
    assertEquals(sixteen, VarkaMatrixRuns.parse(text));
    assertNotEquals(VarkaEmitOptions.DEFAULTS, sixteen);
  }

  @Test
  @DisplayName("a DAG rendering is read back, on one line or several, and hashes as it did "
      + "(VARKA-630)")
  void dagFormReadsBack() {
    // Over the tree limit, so the exact text is the DAG form; and one the tree form still holds.
    List<VarkaVectorIR> roots = List.of(sharedChain(40), sharedChain(5), new ColumnRef(1));
    var parsed = new ArrayList<VarkaVectorIR>();
    for (VarkaVectorIR root : roots) {
      String line = VarkaVectorIR.canonicalOneLine(root);
      assertTrue(line.indexOf('\n') < 0, line);
      VarkaVectorIR read = VarkaCanonicalIr.parse(line);
      assertTrue(VarkaVectorIR.sameStructure(root, read), line);
      parsed.add(read);
    }
    assertTrue(VarkaVectorIR.canonicalOneLine(roots.get(0)).startsWith("dag;"));
    // The one-line form joins the lines by ';', so no node's rendering may contain one.
    assertFalse(VarkaVectorIR.canonical(everyNodeType()).contains(";"));
    assertEquals(hash(roots, 2, 0), hash(parsed, 2, 0));
    // The several-line form, as canonicalDag writes it.
    String lines = VarkaVectorIR.canonicalDag(List.of(roots.get(0)));
    assertTrue(VarkaVectorIR.sameStructure(roots.get(0), VarkaCanonicalIr.parse(lines)));
  }

  @Test
  @DisplayName("a DAG line that names a node not yet read, or breaks the numbering, is refused")
  void malformedDagIsRefused() {
    refuses("before it is read", "dag;0=col:0;1=(int:ADD:WRAP 0 2);roots 1");
    refuses("not the next id", "dag;1=col:0;roots 1");
    refuses("one root", "dag;0=col:0;roots 0 0");
    refuses("one root", "dag;0=col:0");
    refuses("not both", "dag;0=col:0\nroots 0");
  }

  private static void refuses(String why, String text) {
    var e = assertThrows(IllegalArgumentException.class, () -> VarkaCanonicalIr.parse(text));
    assertTrue(e.getMessage().contains(why), e.getMessage());
  }

  @Test
  @DisplayName("a reproduction file with a DAG output renders and reads back (VARKA-630)")
  void fileWithDagOutputRoundTrips() {
    String output = VarkaVectorIR.canonicalOneLine(sharedChain(40));
    var written = new VarkaReproduction(new VarkaFailureSignature("IndexOutOfBoundsException",
        "Index # out of bounds for length #"), VarkaFallbackEvent.KERNEL_FAILURE,
        "Varka context:\n  kind: kernel-failure", "2026-10-14T12:00:00Z", "25", false,
        "0123456789abcdef", 1, 0, false, "", List.of(output), List.of(), List.of(), null,
        new byte[] {(byte) 0xCA, (byte) 0xFE, 0, 1});
    VarkaReproduction read = VarkaReproduction.parse(written.render());
    assertEquals(List.of(output), read.outputs());
    // The replay rebuilds the key from the DAG line and finds the file's shape hash.
    var key = new VarkaShapeKey(List.of(sharedChain(40)), 1, 0, VarkaEmitOptions.DEFAULTS);
    var file = new VarkaReproduction(written.signature(), written.stage(), written.context(),
        written.writtenAt(), written.java(), false, VarkaShapeCacheImpl.shapeHash(key), 1, 0,
        false, "", List.of(output), List.of(), List.of(), null, written.classBytes());
    assertTrue(VarkaReplay.of(file).notes().get(0).startsWith("rebuilt shape "),
        () -> VarkaReplay.of(file).notes().toString());
    assertTrue(VarkaVectorIR.sameStructure(sharedChain(40),
        VarkaCanonicalIr.parse(read.outputs().get(0))));
  }

  private static VarkaReproduction sample(VarkaReproduction.KernelCall call) {
    return new VarkaReproduction(new VarkaFailureSignature("IndexOutOfBoundsException",
        "Index # out of bounds for length #"), VarkaFallbackEvent.KERNEL_FAILURE,
        "Varka context:\n  kind: kernel-failure\n  cause: java.lang.Error: two\nlines",
        "2026-10-14T12:00:00Z", "25", false, "0123456789abcdef", 2, 1, false,
        "misdescribeDataBytes=-4", List.of("(addDays col:0 lit:0)", "(dateDiff col:0 col:1)"),
        List.of(3), List.of(), call, new byte[] {(byte) 0xCA, (byte) 0xFE, 0, 1});
  }

  @Test
  @DisplayName("a reproduction file reads back as it was written, the batch's bytes included")
  void fileRoundTrips() {
    var call = new VarkaReproduction.KernelCall(3, 0, List.of(4, 0), List.of(
        new VarkaReproduction.Input(4, 64, 64, 1, false, new byte[12], new byte[] {5, 0, 0, 0,
            0, 0, 0, 0}),
        new VarkaReproduction.Input(4, 16, 0, 0, true, new byte[] {1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            11, 12}, null)), null, false);
    VarkaReproduction written = sample(call);
    VarkaReproduction read = VarkaReproduction.parse(written.render());
    assertEquals(written.signature(), read.signature());
    assertEquals(written.context(), read.context());
    assertEquals(written.outputs(), read.outputs());
    assertEquals(written.options(), read.options());
    assertEquals(written.intLiterals(), read.intLiterals());
    assertEquals(written.longLiterals(), read.longLiterals());
    assertArrayEquals(written.classBytes(), read.classBytes());
    assertEquals(call.rows(), read.call().rows());
    assertEquals(call.outputWidths(), read.call().outputWidths());
    for (int i = 0; i < 2; i++) {
      var a = call.inputs().get(i);
      var b = read.call().inputs().get(i);
      assertEquals(List.of(a.width(), a.dataCapacity(), a.validityCapacity(), a.nullCount(),
          a.derived()), List.of(b.width(), b.dataCapacity(), b.validityCapacity(), b.nullCount(),
          b.derived()));
      assertArrayEquals(a.data(), b.data());
      assertArrayEquals(a.validity(), b.validity());
    }
    var omitted = new VarkaReproduction.KernelCall(3, 0, List.of(4), List.of(
        new VarkaReproduction.Input(4, 64, 64, 1, false, null, null)), "over the limit of 0",
        true);
    var back = VarkaReproduction.parse(sample(omitted).render()).call();
    assertEquals("over the limit of 0", back.omitted());
    assertTrue(back.selectionBitmaps());
    assertNull(back.inputs().get(0).data());
    assertThrows(IllegalArgumentException.class, () -> VarkaReproduction.parse("not a file"));
  }

  /** A record for a kernel of {@code addDays}, as an evaluator's catch would build it. */
  private static VarkaErrorContext context(String kind) {
    return new VarkaErrorContext(kind, "VarkaFusedProjection_x.java [(addDays col:0 lit:0)]",
        null, "", null, null);
  }

  private static VarkaShapeKey addDays() {
    return new VarkaShapeKey(List.of(new AddDays(new ColumnRef(0), new LiteralSlot(0))), 1, 1,
        VarkaEmitOptions.DEFAULTS);
  }

  private Optional<Path> write(Throwable failure) {
    return VarkaReproduction.write(() -> context(VarkaFallbackEvent.EMISSION_FAILURE), null,
        failure, addDays(), List.of(3), List.of(), null, null);
  }

  @Test
  @DisplayName("the writer writes one owner-only file per shape and kind, nothing when disabled, "
      + "and never throws")
  void writerBounds() throws IOException {
    Path target = dir.resolve("repro");
    SQLConf.get().setConfString(SQLConf.VARKA_REPRODUCTION_DIRECTORY().key(), target.toString());
    SQLConf.get().setConfString(SQLConf.VARKA_REPRODUCTION_ENABLED().key(), "false");
    assertTrue(write(new IllegalStateException("one")).isEmpty());
    assertFalse(Files.exists(target));
    SQLConf.get().setConfString(SQLConf.VARKA_REPRODUCTION_ENABLED().key(), "true");
    Path file = write(new IllegalStateException("one")).orElseThrow();
    assertTrue(write(new IllegalStateException("two")).isEmpty(), "the same shape and kind");
    assertTrue(write(new ArithmeticException("three")).isPresent(), "another kind");
    try (var files = Files.list(target)) {
      assertEquals(2, files.count());
    }
    if (Files.getFileStore(target).supportsFileAttributeView("posix")) {
      assertEquals("rw-------",
          PosixFilePermissions.toString(Files.getPosixFilePermissions(file)));
      assertEquals("rwx------",
          PosixFilePermissions.toString(Files.getPosixFilePermissions(target)));
    }
    var read = VarkaReproduction.parse(Files.readString(file));
    assertEquals("IllegalStateException", read.signature().kind());
    assertEquals(VarkaFallbackEvent.EMISSION_FAILURE, read.stage());
    assertEquals(List.of("(addDays col:0 lit:0)"), read.outputs());
    assertNull(read.call());

    // A file where the directory should be: the write fails, quietly, is not tried again for
    // the same shape and kind, and does not count against the cap.
    VarkaReproduction.resetForTesting();
    Path blocker = dir.resolve("blocker");
    Files.writeString(blocker, "x");
    SQLConf.get().setConfString(SQLConf.VARKA_REPRODUCTION_DIRECTORY().key(), blocker.toString());
    assertTrue(write(new IllegalStateException("one")).isEmpty());
    SQLConf.get().setConfString(SQLConf.VARKA_REPRODUCTION_DIRECTORY().key(), target.toString());
    assertTrue(write(new IllegalStateException("one")).isEmpty(), "tried once only");
    assertTrue(write(new UnsupportedOperationException("u")).isPresent());
  }

  @Test
  @DisplayName("a JVM writes at most its cap of files")
  void writerCap() {
    SQLConf.get().setConfString(SQLConf.VARKA_REPRODUCTION_DIRECTORY().key(),
        dir.resolve("cap").toString());
    int written = 0;
    for (int k = 0; k < VarkaReproduction.MAX_FILES_PER_JVM + 5; k++) {
      var key = new VarkaShapeKey(List.of(new AddDays(new ColumnRef(0), new LiteralSlot(0))), 1,
          1, VarkaEmitOptions.DEFAULTS.withGroupBudget(1 + k));
      if (VarkaReproduction.write(() -> context(VarkaFallbackEvent.EMISSION_FAILURE), null,
          new IllegalStateException("x"), key, List.of(3), List.of(), null, null).isPresent()) {
        written++;
      }
    }
    assertEquals(VarkaReproduction.MAX_FILES_PER_JVM, written);
  }

  @Test
  @DisplayName("a directory that already holds its cap of files gets no more, from any JVM")
  void directoryCap() throws IOException {
    Path full = dir.resolve("full");
    Files.createDirectories(full);
    for (int k = 0; k < VarkaReproduction.MAX_FILES_PER_DIRECTORY; k++) {
      Files.writeString(full.resolve("varka-repro-other-" + k + ".txt"), "x");
    }
    SQLConf.get().setConfString(SQLConf.VARKA_REPRODUCTION_DIRECTORY().key(), full.toString());
    assertTrue(write(new IllegalStateException("one")).isEmpty());
  }

  @Test
  @DisplayName("a replay of a file whose IR does not hash to its shape hash is refused")
  void replayRefusesAnotherKernel() {
    VarkaReproduction file = sample(null);
    var e = assertThrows(IllegalArgumentException.class, () -> VarkaReplay.of(file));
    assertTrue(e.getMessage().contains("not to its shape hash"), e.getMessage());
  }

  @Test
  @DisplayName("a replay of a narrow kernel calls its int entry, as the runner does")
  void narrowKernelReplays() {
    var options = VarkaEmitOptions.DEFAULTS.toBuilder().narrowLanes(true).build();
    var outputs = List.<VarkaVectorIR>of(new ColumnRef(0, LaneType.BYTE));
    var key = new VarkaShapeKey(outputs, 1, 0, options);
    String hash = VarkaShapeCacheImpl.shapeHash(key);
    var call = new VarkaReproduction.KernelCall(4, 0, List.of(1), List.of(
        new VarkaReproduction.Input(1, 4, 8, 0, false, new byte[] {-1, 0, 1, 127}, null)),
        null, false);
    var file = new VarkaReproduction(new VarkaFailureSignature("NoSuchMethodError", "x"),
        VarkaFallbackEvent.KERNEL_FAILURE, "", "", "", false, hash, 1, 0, false,
        VarkaErrorContext.renderOptions(options),
        List.of(VarkaVectorIR.canonical(outputs.get(0))), List.of(), List.of(), call, null);
    var replay = VarkaReplay.of(file);
    // A BYTE kernel implements the int lane's seven-argument entry only, so the replay picks
    // the entry by the lane being LONG, as the runner does, and a long entry would throw.
    assertTrue(replay.actual().isEmpty(), String.join("\n", replay.report()));
    assertTrue(replay.notes().contains("the kernel returned"),
        String.join("\n", replay.report()));
  }

  @Test
  @DisplayName("a replayed boolean output records its width as the bit marker and writes whole "
      + "words of data, as the runner allocates them")
  void bitOutputReplays() {
    var outputs = List.<VarkaVectorIR>of(new BoolOutput(
        new Compare(CompareOp.LT, new ColumnRef(0), new ColumnRef(1))));
    var key = new VarkaShapeKey(outputs, 2, 0, VarkaEmitOptions.DEFAULTS);
    String hash = VarkaShapeCacheImpl.shapeHash(key);
    // 100 rows: two words of data, the second partial, which the kernel may store whole.
    int rows = 100;
    var random = new Random(27);
    byte[] a = new byte[rows * 4];
    byte[] b = new byte[rows * 4];
    random.nextBytes(a);
    random.nextBytes(b);
    var call = new VarkaReproduction.KernelCall(rows, 0,
        List.of(VarkaReproduction.KernelCall.BIT_OUTPUT), List.of(
            new VarkaReproduction.Input(4, rows * 4, 0, 0, false, a, null),
            new VarkaReproduction.Input(4, rows * 4, 0, 0, false, b, null)), null, false);
    var written = new VarkaReproduction(new VarkaFailureSignature("IndexOutOfBoundsException",
        "x"), VarkaFallbackEvent.KERNEL_FAILURE, "", "", "", false, hash, 2, 0, false,
        VarkaErrorContext.renderOptions(VarkaEmitOptions.DEFAULTS),
        List.of(VarkaVectorIR.canonical(outputs.get(0))), List.of(), List.of(), call, null);
    // The marker reads back from the file's text, and is not the selection bitmap's 0.
    VarkaReproduction file = VarkaReproduction.parse(written.render());
    assertEquals(List.of(-1), file.call().outputWidths());
    assertEquals(16L, VarkaReproduction.KernelCall.outputDataBytes(-1, rows));
    // rows * -1 bytes before the marker: the replay could not allocate the output at all.
    var replay = VarkaReplay.of(file);
    assertTrue(replay.actual().isEmpty(), String.join("\n", replay.report()));
    assertTrue(replay.notes().contains("the kernel returned"),
        String.join("\n", replay.report()));
  }

  /**
   * A kernel-failure file of {@code outputs} at the default options, carrying the class the shape
   * cache would define for it, as the evaluator's catch writes one.
   */
  private static VarkaReproduction kernelFile(List<VarkaVectorIR> outputs, int inputs,
      int literals, List<Integer> ints, List<Long> longs, VarkaReproduction.KernelCall call) {
    String hash = hash(outputs, inputs, literals);
    byte[] bytes = VarkaLoopEmitter.emit(VarkaShapeCacheImpl.classNameFor(hash), outputs, inputs,
        literals, VarkaShapeCacheImpl.sourceFileFor(hash), "shape " + hash,
        VarkaEmitOptions.DEFAULTS);
    return new VarkaReproduction(new VarkaFailureSignature("IndexOutOfBoundsException", "x"),
        VarkaFallbackEvent.KERNEL_FAILURE, "", "", "", false, hash, inputs, literals, false,
        VarkaErrorContext.renderOptions(VarkaEmitOptions.DEFAULTS),
        outputs.stream().map(VarkaVectorIR::canonical).toList(), ints, longs, call, bytes);
  }

  /** {@code file} with one byte of its class changed. */
  private static VarkaReproduction withClassByteChanged(VarkaReproduction file, int at) {
    byte[] bytes = file.classBytes().clone();
    bytes[at]++;
    return new VarkaReproduction(file.signature(), file.stage(), file.context(),
        file.writtenAt(), file.java(), file.sanitizer(), file.shapeHash(), file.numInputs(),
        file.numLiterals(), file.warmed(), file.options(),
        file.outputs(), file.intLiterals(), file.longLiterals(), file.call(), bytes);
  }

  private static void assertReturnsSameClass(VarkaReproduction file) {
    var replay = VarkaReplay.of(file);
    assertTrue(replay.actual().isEmpty(), String.join("\n", replay.report()));
    assertTrue(replay.notes().contains("the kernel returned"),
        String.join("\n", replay.report()));
    assertEquals(Optional.of(true), replay.sameClass(), String.join("\n", replay.report()));
  }

  @Test
  @DisplayName("a boolean kernel's file replays to the class it carries, byte for byte")
  void booleanKernelReplays() {
    var outputs = List.<VarkaVectorIR>of(new BoolOutput(new CondIf(new BoolColumn(0),
        new Compare(CompareOp.LT, new ColumnRef(2), new LiteralSlot(0)), new BoolColumn(1))));
    int rows = 100;
    var random = new Random(502);
    // Two boolean columns as Arrow keeps them, a bitmap of (rows + 7) / 8 bytes, and an int one.
    byte[] b0 = new byte[(rows + 7) / 8];
    byte[] b1 = new byte[(rows + 7) / 8];
    byte[] x = new byte[rows * 4];
    random.nextBytes(b0);
    random.nextBytes(b1);
    random.nextBytes(x);
    var call = new VarkaReproduction.KernelCall(rows, 0,
        List.of(VarkaReproduction.KernelCall.BIT_OUTPUT), List.of(
            new VarkaReproduction.Input(1, 16, 0, 0, false, b0, null),
            new VarkaReproduction.Input(1, 16, 0, 0, false, b1, null),
            new VarkaReproduction.Input(4, rows * 4, 0, 0, false, x, null)), null, false);
    var written = kernelFile(outputs, 3, 1, List.of(0), List.of(), call);
    VarkaReproduction file = VarkaReproduction.parse(written.render());
    assertReturnsSameClass(file);
    var changed = VarkaReplay.of(withClassByteChanged(file, 100));
    assertEquals(Optional.of(false), changed.sameClass(), String.join("\n", changed.report()));
  }

  @Test
  @DisplayName("a decimal kernel's file replays to the class it carries, byte for byte")
  void decimalKernelReplays() {
    var dec = new DecimalColumn(0, LaneType.LONG, 9);
    var outputs = List.<VarkaVectorIR>of(dec,
        new IntArith(IntOp.ADD, Overflow.WRAP, dec, new LiteralSlot(0, LaneType.LONG)));
    int rows = 64;
    // Sixteen-byte slots, as a decimal vector keeps them: the unscaled long in the low eight
    // bytes, little-endian, and its sign extension in the high eight.
    byte[] data = new byte[rows * 16];
    for (int r = 0; r < rows; r++) {
      long v = (r - 32) * 1001L;
      for (int b = 0; b < 8; b++) {
        data[16 * r + b] = (byte) (v >>> (8 * b));
        data[16 * r + 8 + b] = (byte) (v < 0 ? -1 : 0);
      }
    }
    var call = new VarkaReproduction.KernelCall(rows, 0, List.of(8, 8), List.of(
        new VarkaReproduction.Input(16, rows * 16, 0, 0, false, data, null)), null, false);
    var written = kernelFile(outputs, 1, 1, List.of(), List.of(5L), call);
    assertReturnsSameClass(VarkaReproduction.parse(written.render()));
  }

  @Test
  @DisplayName("a class without its stack maps replays to the VerifyError at both stages that "
      + "meet it, and a clean class to no failure")
  void verifyFailureReplays() {
    var options = VarkaEmitOptions.DEFAULTS.withDropStackMaps(true);
    var key = new VarkaShapeKey(addDays().outputs(), 1, 1, options);
    String hash = VarkaShapeCacheImpl.shapeHash(key);
    byte[] bytes = VarkaLoopEmitter.emit(VarkaShapeCacheImpl.classNameFor(hash), key.outputs(),
        1, 1, VarkaShapeCacheImpl.sourceFileFor(hash), "shape " + hash, options);
    // What the admission's verification throws under test.
    VerifyError atPlanning = firstVerifyError(bytes);
    for (String stage : List.of(VarkaErrorContext.PLANNING, VarkaFallbackEvent.EMISSION_FAILURE)) {
      var file = new VarkaReproduction(VarkaFailureSignature.of(atPlanning, ""), stage, "", "",
          "", false, hash, 1, 1, false, VarkaErrorContext.renderOptions(options),
          List.of("(addDays col:0 lit:0)"), List.of(3), List.of(), null, null);
      var replay = VarkaReplay.of(file);
      assertTrue(replay.actual().isPresent(), stage);
      assertEquals("generated class: VerifyError", replay.actual().get().kind(), stage);
      if (stage.equals(VarkaErrorContext.PLANNING)) {
        assertTrue(replay.reproduced(), String.join("\n", replay.report()));
      }
    }
    String clean = VarkaShapeCacheImpl.shapeHash(addDays());
    var file = new VarkaReproduction(VarkaFailureSignature.of(atPlanning, ""),
        VarkaFallbackEvent.EMISSION_FAILURE, "", "", "", false, clean, 1, 1, false, "",
        List.of("(addDays col:0 lit:0)"), List.of(3), List.of(), null, null);
    var replay = VarkaReplay.of(file);
    assertFalse(replay.reproduced());
    assertTrue(replay.actual().isEmpty(), String.join("\n", replay.report()));
  }

  /** The first error of the JDK's verifier over raw bytes, as the admission runs it. */
  private static VerifyError firstVerifyError(byte[] bytes) {
    List<VerifyError> errors = java.lang.classfile.ClassFile.of().verify(bytes);
    assertFalse(errors.isEmpty(), "the class verified");
    return errors.get(0);
  }

  @Test
  @DisplayName("every option's label reads back as the option, so a file names its options "
      + "exactly")
  void optionLabelsReadBack() {
    for (VarkaEmitOption option : VarkaEmitOption.TABLE) {
      for (VarkaEmitOption.Arm arm : option.arms()) {
        VarkaEmitOptions options = arm.apply().apply(VarkaEmitOptions.DEFAULTS);
        assertEquals(options, VarkaMatrixRuns.parse(VarkaErrorContext.renderOptions(options)),
            option.name() + " " + arm.name());
      }
    }
    var faults = VarkaEmitOptions.DEFAULTS.withMisdescribeDataBytes(-4).withDropStackMaps(true);
    assertEquals(faults, VarkaMatrixRuns.parse(VarkaErrorContext.renderOptions(faults)));
    assertTrue(Arrays.asList(VarkaErrorContext.renderOptions(faults).split(","))
        .contains("misdescribeDataBytes=-4"));
  }
}
