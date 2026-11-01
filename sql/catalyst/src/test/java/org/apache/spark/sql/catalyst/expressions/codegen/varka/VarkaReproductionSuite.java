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
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
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
        // A decimal column beside an int one widened to its lane.
        new IntArith(IntOp.ADD, Overflow.WRAP, new DecimalColumn(2, LaneType.LONG, 9),
            new WidenLane(new ColumnRef(0), LaneType.LONG)),
        new CondValue(new NullSafeEqual(new ColumnRef(0), new LiteralSlot(0)), LaneType.INT),
        new BitCount(new BitOp(BitOpKind.XOR, new ColumnRef(0), new LiteralSlot(0))),
        // Over comparisons: the parser reads no boolean-column leaf yet.
        new BoolOutput(new Or(new Not(new IsNotNull(new ColumnRef(0))),
            new Compare(CompareOp.LT, new ColumnRef(1), new LiteralSlot(0)))));
  }

  /**
   * The node types the parser has no arm for yet, so no round trip reaches them: the boolean
   * value nodes, whose parse arms the owner is turning into a row of their own. Listed by name so
   * that the completeness check below fails on any other node type left without an entry, and on
   * one of these once a tree above reaches it.
   */
  private static final Set<Class<?>> NOT_PARSED_YET = Set.of(BoolColumn.class, CondConst.class,
      BoolCompare.class, CondIf.class, BoolNullSafeEq.class);

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

  @Test
  @DisplayName("the canonical IR reads back every node type, at both lanes")
  void everyNodeTypeRoundTrips() {
    assertRoundTrips(everyNodeType());
    theRest().forEach(VarkaReproductionSuite::assertRoundTrips);
    // Complete over the sealed interface, so a new node type fails here until a tree above
    // reaches it or it is listed as not parsed yet.
    Set<Class<?>> all = new HashSet<>();
    nodeTypes(VarkaVectorIR.class, all);
    Set<Class<?>> got = new HashSet<>();
    reached(everyNodeType(), got);
    theRest().forEach(t -> reached(t, got));
    Set<Class<?>> missing = new HashSet<>(all);
    missing.removeAll(got);
    missing.removeAll(NOT_PARSED_YET);
    assertTrue(missing.isEmpty(), () -> "node types no round trip reaches: " + missing);
    Set<Class<?>> stale = new HashSet<>(NOT_PARSED_YET);
    stale.retainAll(got);
    assertTrue(stale.isEmpty(), () -> "node types listed as not parsed yet but reached: " + stale);
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
