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

import scala.jdk.CollectionConverters._

import org.apache.spark.SparkFunSuite
import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitOption.Reason

/**
 * The options table (`VarkaEmitOption.TABLE`) against the record it describes: one entry per
 * component in declaration order, the defaults it builds, the renderings `canonical()` makes from
 * it, and the reasons it records.
 */
class VarkaEmitOptionSuite extends SparkFunSuite with VarkaTestWatchdog {

  private val table = VarkaEmitOption.TABLE.asScala.toSeq

  test("the table names every option of the record once, in declaration order") {
    val components = classOf[VarkaEmitOptions].getRecordComponents.map(_.getName).toSeq
    assert(table.map(_.name) === components)
  }

  test("the defaults hold every option at its table default") {
    val d = VarkaEmitOptions.DEFAULTS
    for (option <- table) {
      option match {
        case f: VarkaEmitOption.Flag => assert(f.value(d) === f.defaultValue, f.name)
        case c: VarkaEmitOption.Count => assert(c.value(d) === c.defaultValue, c.name)
        case ch: VarkaEmitOption.Choice[_] => assert(ch.value(d) === ch.defaultValue, ch.name)
      }
    }
    assert(d.canonical() === "" && d.isDefault)
  }

  test("each entry reads and sets the record component it is named for, and no other") {
    // The defaults test above reads each option with its own entry's getter, so an entry wired
    // to another component's getter and setter would pass it. Here every entry moves one value
    // off its default and the record's own accessors say which component moved.
    val d = VarkaEmitOptions.DEFAULTS
    val components = classOf[VarkaEmitOptions].getRecordComponents.toSeq
    def values(o: VarkaEmitOptions): Seq[AnyRef] = components.map(_.getAccessor.invoke(o))
    for (option <- table) {
      val (moved, readBack) = option match {
        case f: VarkaEmitOption.Flag =>
          val v = f.`with`(d, !f.defaultValue)
          (v, f.value(v) == !f.defaultValue)
        case c: VarkaEmitOption.Count =>
          // A power of two away from the default: valid for every count, the lanes included.
          val target = if (c.defaultValue == 2) 4 else 2
          val v = c.`with`(d, target)
          (v, c.value(v) == target)
        case ch: VarkaEmitOption.Choice[_] =>
          val index = (ch.constants.indexOf(ch.defaultValue) + 1) % ch.constants.size
          val v = ch.withIndex(d, index)
          (v, ch.value(v) == ch.constants.get(index))
      }
      val changed = components.zip(values(d).zip(values(moved)))
        .collect { case (component, (before, after)) if before != after => component.getName }
      assert(changed === Seq(option.name), option.name)
      assert(readBack, s"${option.name}: the entry's getter does not read what its setter set")
    }
  }

  test("the defaults and their rendering never load the table") {
    // Linking the table's method references costs a fresh JVM about 17 ms, so DEFAULTS is
    // written out rather than built from the table. A class loader of its own shows whether
    // initialising the record and rendering its defaults pulled the table in after all.
    val loader = new VarkaEmitOptionSuite.Isolated(getClass.getClassLoader)
    // Reading DEFAULTS initialises the record.
    val record = loader.loadClass(classOf[VarkaEmitOptions].getName)
    val defaults = record.getField("DEFAULTS").get(null)
    assert(record.getMethod("canonical").invoke(defaults) === "")
    assert(!loader.loaded(classOf[VarkaEmitOption].getName))
  }

  test("canonical renders the positional options, then the tags that show") {
    // The renderings variants have always had: the 26 options that predate the tags by
    // position, then a tag for each later option on the values its entry names - so a flag on
    // by default still shows (`predictGrouping`, `planSize`, `elideUnreadLocals`), and one off
    // its default shows its opposite (`noRangeSets`).
    val positional = "opts(4|400|false|true|true|true|true|true|true|true|true|true|true|0|" +
      "SUBTRACT|MAGIC|MAGIC|-1|false|false|true|true|false|true|false|8000"
    val onByDefault = "|predictGrouping|elideUnreadLocals|planSize"
    val noCse = VarkaEmitOptions.DEFAULTS.withCse(false)
      .withGroupBudget(4).withFusedCeiling(400)
    assert(noCse.canonical() === positional + onByDefault + ")")
    assert(noCse.withRangeSets(false).withCallSiteBudget(7).canonical() ===
      positional + "|noRangeSets|callSites=7" + onByDefault + ")")
  }

  test("the reasons the table records") {
    def reasonOf(name: String): Reason = VarkaEmitOption.named(name).reason
    for (name <- Seq("misdescribeAdd", "misdescribeWordLiveness", "misdescribeDriverBytes",
        "forceResidualAt", "misdescribeRollback", "dropStackMaps", "misdescribeDataBytes")) {
      assert(reasonOf(name) === Reason.FAULT_INJECTOR, name)
    }
    for (name <- Seq("division", "useAVX")) {
      assert(reasonOf(name) === Reason.MACHINE, name)
    }
    for (name <- Seq("checkIntOverflow", "guardDayProducers")) {
      assert(reasonOf(name) === Reason.PRICED_CHECK, name)
    }
    assert(table.filter(_.reason == Reason.KNOB).map(_.name) ===
      Seq("groupBudget", "fusedCeiling", "lanesOverride", "methodByteBudget", "callSiteBudget",
        "heavyGroupOutputs"))
    assert(table.filter(_.reason == Reason.DIAGNOSTIC).map(_.name) === Seq("exactRefusalStatus"))
    assert(table.filter(_.reason == Reason.MISCOMPILED).map(_.name) === Seq("fromLongMasks"))
    assert(Reason.values.filterNot(_.drawn).toSet ===
      Set(Reason.FAULT_INJECTOR, Reason.MISCOMPILED))
    // One option is read by one node kind alone, so the general draws, the steering and the
    // option matrix leave it out whatever its reason (VARKA-157).
    assert(table.filter(o => VarkaEmitOption.readOnlyBy(o).isPresent)
      .map(o => o.name -> VarkaEmitOption.readOnlyBy(o).get) ===
      Seq("decimalStore" -> classOf[VarkaVectorIR.WidenDecimal]))
    assert(reasonOf("decimalStore") === Reason.MACHINE)
    assert(!VarkaEmitOption.drawnInGeneral(VarkaEmitOption.named("decimalStore")))
  }

  test("an option is found by name, and an enum constant by its name or not at all") {
    def withDivision(constant: String): VarkaEmitOptions =
      VarkaEmitOption.named("division") match {
        case choice: VarkaEmitOption.Choice[_] =>
          choice.withNamed(VarkaEmitOptions.DEFAULTS, constant)
        case other => fail(s"division is not a choice: $other")
      }
    assert(withDivision("DOUBLE_DIV").division === VarkaEmitOptions.Division.DOUBLE_DIV)
    val unknown = intercept[IllegalArgumentException](withDivision("FAST"))
    assert(unknown.getMessage.contains("division has no constant FAST"))
    intercept[IllegalArgumentException](VarkaEmitOption.named("noSuchOption"))
  }

  test("every option the fuzzer may draw has values to draw, and no fault injector does") {
    for (option <- table) {
      option match {
        case c: VarkaEmitOption.Count if c.reason != Reason.FAULT_INJECTOR =>
          assert(!c.fuzzDraws.isEmpty, c.name)
        case c: VarkaEmitOption.Count => assert(c.fuzzDraws.isEmpty, c.name)
        case _ =>
      }
    }
  }
}

private object VarkaEmitOptionSuite {

  /** Loads the Varka codegen classes afresh, everything else from `parent`. */
  final class Isolated(parent: ClassLoader) extends ClassLoader(parent) {
    private val prefix = classOf[VarkaEmitOptions].getPackageName + "."

    def loaded(name: String): Boolean = findLoadedClass(name) != null

    override def loadClass(name: String, resolve: Boolean): Class[_] =
      getClassLoadingLock(name).synchronized {
        if (!name.startsWith(prefix)) {
          super.loadClass(name, resolve)
        } else {
          Option(findLoadedClass(name)).getOrElse {
            val in = getParent.getResourceAsStream(name.replace('.', '/') + ".class")
            val bytes = try in.readAllBytes() finally in.close()
            defineClass(name, bytes, 0, bytes.length)
          }
        }
      }
  }
}
