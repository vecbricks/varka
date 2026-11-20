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

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaMathBinding.Operator;

/**
 * The math family's contracted bounds ({@code VARKA-345.md} 2.5): for a function, the library
 * symbol it binds and the full JDK build, how many representable doubles
 * ({@link VarkaMathBound#steps}) its library operator may lie from Spark's answer. In the opt-in
 * mode a function takes the library operator only where its binding has an entry here; a JDK
 * build with none runs the exact form until it is measured.
 *
 * <p>An entry rests on measured lines, row 367's, one TSV a host under
 * {@code sql/varka/plans/m8/results/} named {@code VARKA-367-<host>.tsv} ({@link MeasuredLine}).
 * A measured maximum is a lower bound of the true one, so a later line can exceed a contracted
 * value, and then the contract is wrong: {@link #findings} reports any committed line above its
 * entry's value and any entry that cites no line or a line nobody committed, and
 * {@code VarkaMathContractSuite} fails on a finding. Raising a value is a dated entry with the new
 * line's witness.
 */
public final class VarkaMathContract {

  private VarkaMathContract() {
  }

  /** A measured line an entry rests on: the host's file and the CPU of the line. */
  public record Citation(String host, String cpu) {
    public Citation {
      Objects.requireNonNull(host, "host");
      Objects.requireNonNull(cpu, "cpu");
    }
  }

  /**
   * One contracted bound: the function, the symbol it binds (which names the library and the
   * species), the full JDK build ({@code java.runtime.version}), the contracted steps, SLEEF's
   * stated tier beside a SLEEF symbol, and the measured lines it rests on.
   */
  public record Entry(Operator function, String symbol, String jdkBuild, int steps,
      List<Citation> lines) {
    public Entry {
      Objects.requireNonNull(function, "function");
      Objects.requireNonNull(symbol, "symbol");
      Objects.requireNonNull(jdkBuild, "jdkBuild");
      if (steps < 0) {
        throw VarkaErrors.illegalArgument("a contracted bound is 0 steps or more: " + steps);
      }
      lines = List.copyOf(lines);
    }

    /** SLEEF's stated tier for the symbol, shown beside the bound; empty for SVML. */
    public Optional<String> statedTier() {
      return VarkaMathBinding.statedTier(symbol);
    }
  }

  /** Every contracted bound. Empty until row 367 commits a host's lines and a row enters one. */
  public static final List<Entry> ENTRIES = List.of();

  /** The entry for a function's binding on a JDK build, if one is contracted. */
  public static Optional<Entry> entry(Operator function, String symbol, String jdkBuild) {
    return ENTRIES.stream().filter(e -> e.function() == function && e.symbol().equals(symbol)
        && e.jdkBuild().equals(jdkBuild)).findFirst();
  }

  /**
   * One line of a host's measured TSV, its columns in this order: the function
   * ({@link Operator#symbolName}), the library ({@code SVML} or {@code SLEEF}), the symbol, the
   * species bits, the JDK build, the CPU, the inputs probed, the lanes whose raw bits differed
   * from the row engine's, the largest {@link VarkaMathBound#steps} among them
   * ({@link VarkaMathBound#REFUSED} for a pair no bound admits; 0 where only NaN payloads
   * differed), and the witness of that largest: the input bits ({@code x}, or {@code x,y} for a
   * binary function), the library's bits and the row engine's, each a {@code 0x} hex word, or
   * {@code -} each when no lane differed. The library is the one the symbol names.
   */
  public record MeasuredLine(String host, Operator function, VarkaMathBinding.Library library,
      String symbol, int bits, String jdkBuild, String cpu, long inputs, long differing,
      long largestSteps, String witnessInput, String witnessLibrary, String witnessRowEngine) {

    /** The header every host's file opens with. */
    public static final String HEADER = String.join("\t", "function", "library", "symbol",
        "bits", "jdk", "cpu", "inputs", "differing", "largest_steps", "witness_input",
        "witness_library", "witness_row_engine");

    public MeasuredLine {
      if (library == VarkaMathBinding.Library.NONE || bits < 128 || Integer.bitCount(bits) != 1
          || inputs < 1
          || differing < 0 || differing > inputs || largestSteps < 0) {
        throw VarkaErrors.illegalArgument("not a measured line: " + library + " at " + bits
            + " bits, " + differing + " of " + inputs + " lanes, " + largestSteps + " steps");
      }
      if (!names(symbol, library, function, bits)) {
        throw VarkaErrors.illegalArgument("the symbol " + symbol + " is not " + library + "'s "
            + function.symbolName() + " at " + bits + " bits");
      }
      boolean none = differing == 0;
      if ((none && largestSteps != 0) || !witness(witnessInput, none, function.binary() ? 2 : 1)
          || !witness(witnessLibrary, none, 1) || !witness(witnessRowEngine, none, 1)) {
        throw VarkaErrors.illegalArgument("a measured line's witness is a 0x word per value"
            + " where a lane differed and - where none did: " + witnessInput + " "
            + witnessLibrary + " " + witnessRowEngine);
      }
    }

    /**
     * Whether {@code symbol} is a name {@link VarkaMathBinding} gives {@code function} at
     * {@code bits} in {@code library}: SVML's {@code __jsvml_<op><lanes>_ha_<level>}, the level
     * {@code z0} at 512 bits alone; SLEEF's {@code <op>d2_<tier>advsimd} at 128 bits or
     * {@code <op>dx_<tier>} with {@code sve} or {@code rvv}, the tier {@code u05} for
     * {@code HYPOT}.
     */
    private static boolean names(String symbol, VarkaMathBinding.Library library,
        Operator function, int bits) {
      String op = function.symbolName();
      if (library == VarkaMathBinding.Library.SVML) {
        String level = bits == 512 ? "z0" : "(l9|e9|ex)";
        return symbol.matches("__jsvml_" + op + bits / 64 + "_ha_" + level);
      }
      String tier = function == Operator.HYPOT ? "u05" : "u10";
      return symbol.matches(op + "dx_" + tier + "(sve|rvv)")
          || bits == 128 && symbol.equals(op + "d2_" + tier + "advsimd");
    }

    private static boolean witness(String text, boolean none, int words) {
      if (none) {
        return "-".equals(text);
      }
      String[] split = text.split(",", -1);
      if (split.length != words) {
        return false;
      }
      for (String w : split) {
        if (!w.matches("0x[0-9a-f]{16}")) {
          return false;
        }
      }
      return true;
    }

    /** {@code line} of {@code host}'s file, parsed; a malformed line throws. */
    public static MeasuredLine parse(String host, String line) {
      String[] c = line.split("\t", -1);
      if (c.length != 12) {
        throw VarkaErrors.illegalArgument("a measured line has 12 columns, not " + c.length
            + ": " + line);
      }
      Operator function = null;
      for (Operator op : Operator.values()) {
        if (op.symbolName().equals(c[0])) {
          function = op;
        }
      }
      if (function == null) {
        throw VarkaErrors.illegalArgument("no math operator " + c[0] + ": " + line);
      }
      return new MeasuredLine(host, function, VarkaMathBinding.Library.valueOf(c[1]), c[2],
          Integer.parseInt(c[3]), c[4], c[5], Long.parseLong(c[6]), Long.parseLong(c[7]),
          Long.parseLong(c[8]), c[9], c[10], c[11]);
    }
  }

  /**
   * What is wrong between {@code entries} and the committed {@code lines}: a line above the value
   * of the entry for its function, symbol and JDK build, two entries of one key, an entry citing
   * no line, and a citation no committed line of that key answers. Empty when the contract holds.
   */
  public static List<String> findings(List<Entry> entries, List<MeasuredLine> lines) {
    var found = new ArrayList<String>();
    var keys = new HashSet<String>();
    for (Entry e : entries) {
      List<MeasuredLine> keyed = lines.stream().filter(l -> l.function() == e.function()
          && l.symbol().equals(e.symbol()) && l.jdkBuild().equals(e.jdkBuild())).toList();
      String key = e.function() + " on " + e.symbol() + ", JDK " + e.jdkBuild();
      if (!keys.add(key)) {
        found.add(key + ": entered twice");
      }
      for (MeasuredLine l : keyed) {
        if (l.largestSteps() > e.steps()) {
          found.add(key + ": " + l.host() + " (" + l.cpu() + ") measured " + l.largestSteps()
              + " steps, above the contracted " + e.steps());
        }
      }
      if (e.lines().isEmpty()) {
        found.add(key + ": cites no measured line");
      }
      for (Citation c : e.lines()) {
        if (keyed.stream().noneMatch(l -> l.host().equals(c.host()) && l.cpu().equals(c.cpu()))) {
          found.add(key + ": cites " + c + ", which no committed line answers");
        }
      }
    }
    return List.copyOf(found);
  }
}
