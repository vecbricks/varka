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

package org.apache.spark.sql.catalyst.expressions.codegen.varka

import java.lang.constant.{ClassDesc, ConstantDescs, MethodTypeDesc}
import java.util.Locale

import scala.jdk.CollectionConverters._

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaVectorIR._
import org.apache.spark.sql.varka.vector.VarkaVectorSupport

/**
 * The lane a node's value occupies: that the leaves carry it, that every other node derives it,
 * and that a tree whose lanes do not fit cannot be built.
 *
 * The lane is the width of the vector a node is emitted into, not the Spark type above it - a
 * date, an int and a year-month interval are all the same 32-bit lane. Until the emitter is
 * parameterised on it (task 85, steps 3 and 4) the int lane is the only one it can emit, so a
 * well-formed 64-bit tree is built here and refused there, which is what the last test pins.
 */
class VarkaLaneTypeSuite extends SparkFunSuite {

  private val intCol = new ColumnRef(0)
  private val intLit = new LiteralSlot(0)
  private val longCol = new ColumnRef(0, LaneType.LONG)
  private val longLit = new LiteralSlot(0, LaneType.LONG)

  /** One node of every type in the sealed hierarchy, over int leaves. */
  private val everyIntNode: Seq[VarkaVectorIR] = Seq(
    intCol,
    intLit,
    new GuardedDay(intCol),
    new GuardedRange(intCol, -10, 10),
    new InRanges(intCol, java.util.List.of(Integer.valueOf(-10), Integer.valueOf(-5),
      Integer.valueOf(3), Integer.valueOf(8))),
    // An int by value over a long child: the one node whose own lane is not its child's.
    new NarrowLane(longCol),
    new AddDays(intCol, intLit),
    new SubDays(intCol, intLit),
    new DateDiff(intCol, intCol),
    new IntArith(IntOp.ADD, Overflow.WRAP, intCol, intLit),
    new IntNeg(Overflow.WRAP, intCol),
    new ConstDivide(intCol, 12),
    BoundedDivide.of(intCol, 60, 3600),
    new Compare(CompareOp.LT, intCol, intLit),
    new And(new IsNotNull(intCol), new IsNotNull(intCol)),
    new Or(new IsNotNull(intCol), new IsNotNull(intCol)),
    new Not(new IsNotNull(intCol)),
    new IsNotNull(intCol),
    new IfElse(new IsNotNull(intCol), intCol, intLit),
    new Greatest(intCol, intLit),
    new Least(intCol, intLit),
    new DayOfWeek(intCol),
    new WeekDay(intCol),
    new DayOfWeekIso(intCol),
    new NextDay(intCol, intLit),
    new ThursdayOf(intCol),
    new AddMonths(intCol, intLit),
    new MakeDate(intCol, intLit, intLit, true),
    new Year(intCol),
    new Month(intCol),
    new DayOfMonth(intCol),
    new Quarter(intCol),
    new DayOfYear(intCol),
    new LastDay(intCol),
    new TruncDate(intCol, TruncLevel.YEAR),
    new TruncDateDynamic(intCol, intCol),
    new WeekOfYear(intCol))

  /** The node types whose lane is their operands' rather than INT by construction. */
  private val derivesItsLane: Set[Class[_]] = Set(
    classOf[IntArith], classOf[IntNeg], classOf[Greatest], classOf[Least], classOf[IfElse],
    classOf[Compare], classOf[And], classOf[Or], classOf[Not], classOf[IsNotNull],
    classOf[ConstDivide], classOf[GuardedRange])

  /** Every concrete node type the sealed hierarchy permits, nested interfaces expanded. */
  private def concreteNodeTypes(root: Class[_]): Set[Class[_]] =
    root.getPermittedSubclasses.toSeq.flatMap { c =>
      if (c.isRecord) Seq(c) else concreteNodeTypes(c)
    }.toSet

  test("the lane of an int tree is INT at every node type") {
    // The list above is the specification of what "derived" means per node, so it has to name
    // every type: a node type added without a lane rule would otherwise go unchecked here even
    // though the emitter walks it.
    assert(everyIntNode.map(_.getClass).toSet === concreteNodeTypes(classOf[VarkaVectorIR]))
    everyIntNode.foreach { node =>
      assert(node.laneType() === LaneType.INT, s"${VarkaVectorIR.canonical(node)}")
    }
  }

  test("a leaf carries its lane, and the short form is the int lane") {
    assert(new ColumnRef(3) === new ColumnRef(3, LaneType.INT))
    assert(new LiteralSlot(2) === new LiteralSlot(2, LaneType.INT))
    assert(longCol.lane() === LaneType.LONG)
    assert(longLit.lane() === LaneType.LONG)
    assert(new ColumnRef(3) !== new ColumnRef(3, LaneType.LONG))
  }

  test("a value node over long leaves is on the long lane") {
    // The lane-generic nodes: the ones task 85 ships at 64 bits. Each derives from its
    // operands, so a whole subtree answers LONG without anything below it being asked twice.
    val nodes = Seq(
      new IntArith(IntOp.MUL, Overflow.WRAP, longCol, longLit),
      new IntNeg(Overflow.FAIL, longCol),
      new Greatest(longCol, longLit),
      new Least(longCol, longLit),
      new IfElse(new Compare(CompareOp.GT, longCol, longLit), longCol, longLit),
      new Compare(CompareOp.EQ, longCol, longLit),
      new IsNotNull(longCol),
      new Not(new IsNotNull(longCol)),
      new And(new IsNotNull(longCol), new Compare(CompareOp.LT, longCol, longLit)),
      new Or(new IsNotNull(longCol), new Compare(CompareOp.LT, longCol, longLit)))
    nodes.foreach { node =>
      assert(node.laneType() === LaneType.LONG, s"${VarkaVectorIR.canonical(node)}")
    }
    // Nesting: a long subtree under a long node stays long.
    val nested = new IntArith(IntOp.ADD, Overflow.WRAP,
      new IntArith(IntOp.SUB, Overflow.WRAP, longCol, longLit), longCol)
    assert(nested.laneType() === LaneType.LONG)
  }

  test("a calendar node over a wider child is refused when it is built") {
    // The calendar lowerings decompose a 32-bit epoch day. A 64-bit child would be
    // reinterpreted rather than converted, so the constructor refuses it and names the lane.
    //
    // Every node that takes epoch days appears here, and every operand position of each: the
    // twenty checks are near-identical lines, which is exactly the shape a copy-paste slip
    // survives in, and a node left unchecked would report the int lane over a 64-bit subtree.
    // The completeness assertion below is what keeps the list honest as nodes are added.
    val refusals: Seq[(String, Int, () => Any)] = Seq(
      ("guardedDay", 0, () => new GuardedDay(longCol)),
      ("boundedDivide", 0, () => BoundedDivide.of(longCol, 60, 3600)),
      ("addDays", 0, () => new AddDays(longCol, intLit)),
      ("addDays", 1, () => new AddDays(intCol, longLit)),
      ("subDays", 0, () => new SubDays(longCol, intLit)),
      ("subDays", 1, () => new SubDays(intCol, longLit)),
      ("dateDiff", 0, () => new DateDiff(longCol, intCol)),
      ("dateDiff", 1, () => new DateDiff(intCol, longCol)),
      ("dayOfWeek", 0, () => new DayOfWeek(longCol)),
      ("weekDay", 0, () => new WeekDay(longCol)),
      ("dayOfWeekIso", 0, () => new DayOfWeekIso(longCol)),
      ("nextDay", 0, () => new NextDay(longCol, intLit)),
      ("nextDay", 1, () => new NextDay(intCol, longLit)),
      ("thursdayOf", 0, () => new ThursdayOf(longCol)),
      ("addMonths", 0, () => new AddMonths(longCol, intLit)),
      ("addMonths", 1, () => new AddMonths(intCol, longLit)),
      ("makeDate", 0, () => new MakeDate(longCol, intLit, intLit, true)),
      ("makeDate", 1, () => new MakeDate(intCol, longLit, intLit, false)),
      ("makeDate", 2, () => new MakeDate(intCol, intLit, longLit, true)),
      ("year", 0, () => new Year(longCol)),
      ("month", 0, () => new Month(longCol)),
      ("dayOfMonth", 0, () => new DayOfMonth(longCol)),
      ("quarter", 0, () => new Quarter(longCol)),
      ("dayOfYear", 0, () => new DayOfYear(longCol)),
      ("lastDay", 0, () => new LastDay(longCol)),
      ("truncDate", 0, () => new TruncDate(longCol, TruncLevel.MONTH)),
      ("truncDateDynamic", 0, () => new TruncDateDynamic(longCol, intCol)),
      ("truncDateDynamic", 1, () => new TruncDateDynamic(intCol, longCol)),
      ("weekOfYear", 0, () => new WeekOfYear(longCol)),
      ("inRanges", 0,
        () => new InRanges(longCol, java.util.List.of(Integer.valueOf(1), Integer.valueOf(2)))))
    refusals.foreach { case (what, position, build) =>
      val e = intercept[IllegalArgumentException](build())
      assert(e.getMessage.contains(what), s"$what operand $position: ${e.getMessage}")
      assert(e.getMessage.contains("LONG"), s"$what operand $position: ${e.getMessage}")
    }
    // Every node whose lane is INT by construction rather than derived from its operands takes
    // epoch days, so every one of them has to appear above.
    val covered = refusals.map(_._1.toLowerCase(Locale.ROOT)).toSet
    val calendarNodes = everyIntNode
      .filterNot(n => n.isInstanceOf[ColumnRef] || n.isInstanceOf[LiteralSlot])
      .filterNot(n => derivesItsLane.contains(n.getClass))
      // The narrowing is INT by construction too, but over a long child rather than over epoch
      // days; what it refuses is an int child, which the narrowing test below pins.
      .filterNot(_.isInstanceOf[NarrowLane])
      .map(_.getClass.getSimpleName.toLowerCase(Locale.ROOT))
      .toSet
    assert(calendarNodes -- covered === Set.empty[String],
      "a node that takes epoch days has no refusal case")
  }

  test("a node whose operands disagree on their lane is refused when it is built") {
    // One node is emitted over one species: an add whose operands are different widths, or a
    // blend whose mask and values are, has no lowering. Widening is a conversion node's job.
    val refusals: Seq[(String, () => Any)] = Seq(
      "int arithmetic" -> (() => new IntArith(IntOp.ADD, Overflow.WRAP, intCol, longLit)),
      "int arithmetic" -> (() => new IntArith(IntOp.MUL, Overflow.NULL, longCol, intLit)),
      "a comparison" -> (() => new Compare(CompareOp.LT, longCol, intLit)),
      "greatest" -> (() => new Greatest(longCol, intCol)),
      "least" -> (() => new Least(intCol, longLit)),
      "and" -> (() => new And(new IsNotNull(intCol), new IsNotNull(longCol))),
      "or" -> (() => new Or(new IsNotNull(longCol), new IsNotNull(intCol))),
      "if" -> (() => new IfElse(new IsNotNull(intCol), longCol, longLit)),
      "if" -> (() => new IfElse(new IsNotNull(longCol), longCol, intLit)))
    refusals.foreach { case (what, build) =>
      val e = intercept[IllegalArgumentException](build())
      assert(e.getMessage.contains(what), e.getMessage)
      assert(e.getMessage.contains("mixes lanes"), e.getMessage)
    }
  }

  test("the int lane renders as nothing, so no shape hash committed before it moved") {
    // The rendering drives the emitted class's name and the committed hashes in
    // VarkaShapeCacheSuite, so the int lane has to be invisible in it - the same elision
    // VarkaEmitOptions makes for its defaults. A wider lane renders, and so gets its own name.
    assert(VarkaVectorIR.canonical(intCol) === "col:0")
    assert(VarkaVectorIR.canonical(intLit) === "lit:0")
    assert(VarkaVectorIR.canonical(longCol) === "col:0:long")
    assert(VarkaVectorIR.canonical(longLit) === "lit:0:long")
    val intKey = new VarkaShapeKey(Seq[VarkaVectorIR](intCol).asJava, 1, 0)
    val longKey = new VarkaShapeKey(Seq[VarkaVectorIR](longCol).asJava, 1, 0)
    assert(intKey !== longKey, "the lane is part of a shape's identity")
    assert(VarkaShapeCacheImpl.shapeHash(intKey) !== VarkaShapeCacheImpl.shapeHash(longKey))
  }

  test("every descriptor the lane derives is the one the Vector API declares") {
    // The emitter builds its descriptors from two facts - the vector class and the scalar type -
    // instead of writing one table per lane, so nothing but this test stands between a wrong
    // derivation and a class that fails verification at a lane the oracle cannot reach. The
    // expectations are written out by hand from the Vector API's own signatures, which is the
    // point: a derivation checked against itself would check nothing.
    val v = ClassDesc.of("jdk.incubator.vector.IntVector")
    val vector = ClassDesc.of("jdk.incubator.vector.Vector")
    val mask = ClassDesc.of("jdk.incubator.vector.VectorMask")
    val species = ClassDesc.of("jdk.incubator.vector.VectorSpecies")
    val segment = ClassDesc.of("java.lang.foreign.MemorySegment")
    val order = ClassDesc.of("java.nio.ByteOrder")
    val binary = ClassDesc.of("jdk.incubator.vector.VectorOperators$Binary")
    val comparison = ClassDesc.of("jdk.incubator.vector.VectorOperators$Comparison")
    val int = ConstantDescs.CD_int
    val lane = Lane.INT

    assert(lane.vector === v)
    assert(lane.bits === 32)
    assert(lane.byteStride === 4L, "the int lane is four bytes wide")
    assert(lane.broadcast === MethodTypeDesc.of(v, species, int))
    assert(lane.fromMemorySegmentDense ===
      MethodTypeDesc.of(v, species, segment, ConstantDescs.CD_long, order))
    assert(lane.fromMemorySegmentMasked ===
      MethodTypeDesc.of(v, species, segment, ConstantDescs.CD_long, order, mask))
    assert(lane.intoMemorySegmentDense ===
      MethodTypeDesc.of(ConstantDescs.CD_void, segment, ConstantDescs.CD_long, order))
    assert(lane.intoMemorySegmentMasked ===
      MethodTypeDesc.of(ConstantDescs.CD_void, segment, ConstantDescs.CD_long, order, mask))
    assert(lane.lanewiseVV === MethodTypeDesc.of(v, vector), "the parameter is the erased Vector")
    assert(lane.lanewiseVVWrong === MethodTypeDesc.of(v, v), "the misdescribe hook's wrong shape")
    assert(lane.lanewiseVI === MethodTypeDesc.of(v, int))
    assert(lane.lanewiseVIMasked === MethodTypeDesc.of(v, int, mask))
    assert(lane.lanewiseBinaryV === MethodTypeDesc.of(v, binary, vector))
    assert(lane.lanewiseBinaryI === MethodTypeDesc.of(v, binary, int))
    assert(lane.compareVV === MethodTypeDesc.of(mask, comparison, vector))
    assert(lane.compareVI === MethodTypeDesc.of(mask, comparison, int))
    assert(lane.blend === MethodTypeDesc.of(v, vector, mask))
  }

  test("the species constant a lane names follows its own width") {
    // Sixteen int lanes is 512 bits; the same count at a wider lane would name a wider species,
    // which is why the name is the lane's business and not a shared helper's.
    val lane = Lane.INT
    assert(lane.speciesField(0) === "SPECIES_PREFERRED")
    assert(lane.speciesField(2) === "SPECIES_64")
    assert(lane.speciesField(4) === "SPECIES_128")
    assert(lane.speciesField(8) === "SPECIES_256")
    assert(lane.speciesField(16) === "SPECIES_512")
  }

  test("every node type is emittable at exactly the lanes the table admits") {
    // The reachability claim task 85 owes, as a table rather than as a random walk: for every
    // concrete node type, at every lane, either the IR refuses to build it or the emitter
    // produces a class that verifies. A lane arriving without an arm fails here in
    // milliseconds, which is what the claim is for.
    //
    // It is checked by enumeration rather than by making the fuzz generator lane-parametric.
    // The long lane's subset is twelve node types, which enumeration covers exhaustively where
    // a generator covers it by chance; and a lane-parametric `Shapes` is what task 104 needs
    // for SQL-level shapes, not what this task needs for twelve.
    def instance(cls: Class[_], lane: LaneType): Option[VarkaVectorIR] = {
      val c = new ColumnRef(0, lane)
      val c1 = new ColumnRef(1, lane)
      val l = new LiteralSlot(0, lane)
      try Some(cls.getSimpleName match {
        case "ColumnRef" => c
        case "LiteralSlot" => l
        case "IntArith" => new IntArith(IntOp.ADD, Overflow.WRAP, c, c1)
        case "IntNeg" => new IntNeg(Overflow.WRAP, c)
        case "InRanges" =>
          new InRanges(c, java.util.List.of(Integer.valueOf(-10), Integer.valueOf(10)))
        case "ConstDivide" => new ConstDivide(c, 12, ConstDivide.EXACT_DIVIDEND_BOUND)
        case "BoundedDivide" => BoundedDivide.of(c, 60, 3600)
        case "Greatest" => new Greatest(c, c1)
        case "Least" => new Least(c, c1)
        case "Compare" => new Compare(CompareOp.LT, c, c1)
        case "And" => new And(new IsNotNull(c), new IsNotNull(c1))
        case "Or" => new Or(new IsNotNull(c), new IsNotNull(c1))
        case "Not" => new Not(new IsNotNull(c))
        case "IsNotNull" => new IsNotNull(c)
        case "IfElse" => new IfElse(new IsNotNull(c), c, c1)
        case "GuardedDay" => new GuardedDay(c)
        case "GuardedRange" => new GuardedRange(c, -10, 10)
        // Always over a long child, whatever `lane` says: the node is an int value computed in
        // the long lane, so it is the one type that reaches the emitter from both rows below
        // and is emitted at the long species either way.
        case "NarrowLane" => new NarrowLane(new ColumnRef(0, LaneType.LONG))
        case "AddDays" => new AddDays(c, l)
        case "SubDays" => new SubDays(c, l)
        case "DateDiff" => new DateDiff(c, c1)
        case "DayOfWeek" => new DayOfWeek(c)
        case "WeekDay" => new WeekDay(c)
        case "DayOfWeekIso" => new DayOfWeekIso(c)
        case "NextDay" => new NextDay(c, l)
        case "ThursdayOf" => new ThursdayOf(c)
        case "AddMonths" => new AddMonths(c, l)
        case "MakeDate" => new MakeDate(c, l, l, true)
        case "Year" => new Year(c)
        case "Month" => new Month(c)
        case "DayOfMonth" => new DayOfMonth(c)
        case "Quarter" => new Quarter(c)
        case "DayOfYear" => new DayOfYear(c)
        case "LastDay" => new LastDay(c)
        case "TruncDate" => new TruncDate(c, TruncLevel.YEAR)
        case "TruncDateDynamic" => new TruncDateDynamic(c, c1)
        case "WeekOfYear" => new WeekOfYear(new ThursdayOf(c))
        case other => fail(s"no instance recipe for $other; a node type was added")
      }) catch {
        // The IR's own refusal: a calendar node over a wider child cannot be built at all.
        case _: IllegalArgumentException => None
      }
    }
    val types = concreteNodeTypes(classOf[VarkaVectorIR]).toSeq.sortBy(_.getSimpleName)
    val buildable = for {
      lane <- Seq(LaneType.INT, LaneType.LONG)
      cls <- types
      node <- instance(cls, lane)
    } yield {
      // A `WeekOfYear` needs its `ThursdayOf`; both are int-only, and the guarded day's range
      // check means a bare column root would decline rather than emit, so it is wrapped.
      val roots = Seq[VarkaVectorIR](node)
      val bytes = VarkaLoopEmitter.emit(s"VarkaReach${cls.getSimpleName}$lane",
        roots.asJava, 2, 1, null, null, VarkaEmitOptions.DEFAULTS)
      val problems = VarkaEmitterTestSupport.verify(bytes).asScala
      assert(problems.isEmpty, s"${cls.getSimpleName} at $lane: ${problems.mkString("; ")}")
      (lane, cls.getSimpleName)
    }
    val atLong = buildable.filter(_._1 == LaneType.LONG).map(_._2).toSet
    val atInt = buildable.filter(_._1 == LaneType.INT).map(_._2).toSet
    assert(atInt === types.map(_.getSimpleName).toSet, "every node type is emittable at INT")
    // The subset PLAN_TASK_85.md 3.1 names, and nothing else: a calendar node at the long lane
    // is refused by its constructor, which is why it never reaches the emitter.
    assert(atLong === Set("ColumnRef", "LiteralSlot", "IntArith", "IntNeg", "Greatest", "Least",
      "Compare", "And", "Or", "Not", "IsNotNull", "IfElse", "ConstDivide", "GuardedRange",
      "NarrowLane"),
      "the long lane serves the lane-generic subset, task 88's division, task 102's guard and " +
        "its narrowing root")
  }

  test("a narrowing is an int over a long child, emitted at the long lane, and a root only") {
    // `PLAN_TASK_102.md` 8.3: the TIME extracts compute a 64-bit division and deliver an int,
    // and until task 28 gives the emitter a width conversion the only place a lane changes
    // width is a root's store. So the node answers INT for its value and LONG for the lane it
    // is emitted at, refuses an int child where it is built (there is nothing to narrow), and
    // the emitter refuses it under another node - where its 32-bit value would meet a 64-bit
    // computation - with a reason that names the position, ahead of the lane mix it implies.
    val narrowed = new NarrowLane(longCol)
    assert(narrowed.laneType() === LaneType.INT)
    assert(VarkaVectorIR.emissionLane(narrowed) === LaneType.LONG)
    assert(VarkaVectorIR.emissionLane(longCol) === LaneType.LONG)
    assert(VarkaVectorIR.emissionLane(intCol) === LaneType.INT)
    assert(VarkaVectorIR.canonical(narrowed) === s"(narrow ${VarkaVectorIR.canonical(longCol)})")
    val overInt = intercept[IllegalArgumentException](new NarrowLane(intCol))
    assert(overInt.getMessage.contains("narrowLane takes a LONG child"), overInt.getMessage)
    val interior = new IntArith(IntOp.ADD, Overflow.WRAP, narrowed, intLit)
    val belowRoot = intercept[IllegalArgumentException] {
      VarkaLoopEmitter.emit("VarkaInteriorNarrowKernel", Seq[VarkaVectorIR](interior).asJava,
        1, 1, null, null, VarkaEmitOptions.DEFAULTS)
    }
    assert(belowRoot.getMessage.contains("an output root only"), belowRoot.getMessage)
  }

  test("a baked lane count has both a species constant and a validity helper pair") {
    // The two facts a baked width needs, checked together because they are different
    // questions: `SPECIES_<bits>` is a field the Vector API declares, and `validityBitsAt<n>`
    // is a method VarkaVectorSupport declares. At the int lane the two sets coincide at 2, 4,
    // 8 and 16; at the long lane they do not - one 64-bit lane is `SPECIES_64`, which exists,
    // while `validityBitsAt1` does not. Baking that width produced a class that verified and
    // threw NoSuchMethodError on its first masked batch, so this test is the one that has to
    // fail when the two are confused again.
    val helperCounts = Set(2, 4, 8, 16)
    val supportMethods = classOf[VarkaVectorSupport].getDeclaredMethods.map(_.getName).toSet
    for (n <- helperCounts) {
      assert(supportMethods.contains(s"validityBitsAt$n") &&
        supportMethods.contains(s"orValidityBitsAt$n"),
        s"VarkaVectorSupport is missing the pair this test assumes for $n lanes")
    }
    // Every width the options accept - 0, meaning no override, and the powers of two.
    for (lane <- Seq(Lane.INT, Lane.LONG);
         n <- Seq(0, 1, 2, 4, 8, 16, 32)) {
      val baked = VarkaLoopEmitter.emitLanesForTest(
        VarkaEmitOptions.DEFAULTS.withLanesOverride(n), lane)
      assert(baked == 0 || (lane.hasSpecies(baked) && helperCounts.contains(baked)),
        s"$lane baked $baked lanes for an override of $n, without both a species constant " +
          "and a validity pair")
    }
    // The case that shipped broken, named rather than left to the loop: one 64-bit lane has a
    // species and no helpers, so it must not be baked.
    assert(VarkaLoopEmitter.emitLanesForTest(
      VarkaEmitOptions.DEFAULTS.withLanesOverride(1), Lane.LONG) === 0)
    assert(Lane.LONG.hasSpecies(1), "SPECIES_64 is one long lane")
  }

  test("a long-lane shape emits a class that verifies") {
    // Step 3 gave the emitter a lane descriptor and left it pinned to the int lane; step 4
    // reads the lane from the roots, so this shape is emitted against LongVector. The bytes
    // are put through the Class-File API's own verifier rather than merely produced: a wrong
    // descriptor - the int form of `broadcast`, a species constant the class does not have -
    // is a class that loads and then fails, which a test that only called emit would miss.
    for (lanes <- Seq(2, 8)) {
      val options = VarkaEmitOptions.DEFAULTS.withLanesOverride(lanes)
      val bytes = VarkaLoopEmitter.emit("VarkaLongLaneKernel", Seq[VarkaVectorIR](longCol).asJava,
        1, 0, null, null, options)
      val problems = VarkaEmitterTestSupport.verify(bytes).asScala
      assert(problems.isEmpty, s"$lanes long lanes: ${problems.mkString("; ")}")
    }
  }

  test("a kernel called through the other lane's entry point says so") {
    // One emitted class is one species, so the two `run` forms are not interchangeable: the
    // wide one carries the second scalar array a 64-bit literal needs, and the narrow one does
    // not. Both are interface defaults that throw, so a caller that picks the wrong one gets a
    // sentence naming the lane rather than an AbstractMethodError from the verifier.
    val loader = new VarkaGeneratedClassLoader(getClass.getClassLoader)
    try {
      def kernel(root: VarkaVectorIR, name: String): VarkaFusedKernel = {
        val bytes = VarkaLoopEmitter.emit(name, Seq(root).asJava, 1, 0, null, null,
          VarkaEmitOptions.DEFAULTS)
        loader.defineGeneratedClass(name, bytes)
        loader.loadClass(name).getConstructor().newInstance().asInstanceOf[VarkaFusedKernel]
      }
      val wide = intercept[UnsupportedOperationException] {
        kernel(longCol, "VarkaWrongOverloadLong").run(Array(0L), Array(0L), Array(0),
          Array(0L), Array(0L), Array.empty[Int], 0)
      }
      assert(wide.getMessage.contains("64-bit-lane kernel"), wide.getMessage)
      val narrow = intercept[UnsupportedOperationException] {
        kernel(intCol, "VarkaWrongOverloadInt").run(Array(0L), Array(0L), Array(0),
          Array(0L), Array(0L), Array.empty[Int], Array.empty[Long], 0)
      }
      assert(narrow.getMessage.contains("32-bit-lane kernel"), narrow.getMessage)
    } finally {
      loader.release()
    }
  }

  test("an emission whose roots disagree on their lane is refused") {
    // One emitted class is one species: its loop, its epilogue and its stores are all that
    // width, so two roots on different lanes are two kernels rather than one. The IR's own
    // constructors stop a mixed *tree*; this is the other door, two well-formed trees handed
    // to one emission.
    val e = intercept[IllegalArgumentException] {
      VarkaLoopEmitter.emit("VarkaMixedLaneKernel",
        Seq[VarkaVectorIR](intCol, longCol).asJava, 1, 0, null, null, VarkaEmitOptions.DEFAULTS)
    }
    assert(e.getMessage === "outputs mix lanes: INT and LONG", e.getMessage)
  }
}
