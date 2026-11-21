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

import java.util.List;

/**
 * The structural identity of one emitted fused-kernel class: exactly the {@link VarkaLoopEmitter}
 * inputs the bytes are a function of, nothing else. Two projections with equal keys compile to
 * byte-identical loops (modulo the name and debug strings the cache derives itself), so they may -
 * and with the cache, do - share one loaded class.
 *
 * <p>Equality is structural for free: the IR nodes are records, and the compiler assigns literal
 * slots and column refs dense first-occurrence indices carrying no values ({@code VARKA-10.md}
 * built that property for this key; {@code VarkaExpressionCompilerSuite} pins it). Literal values
 * travel as runtime {@code scalarArgs} and never enter the key - two queries with the same shape
 * and different constants must hit. {@code numLiterals} is a component in its own right because it
 * changes the emitted bytecode independently of the IR (per-slot locals are allocated whether
 * referenced or not, and it gates the broadcast-hoist regime).
 *
 * <p>Deliberately absent, recorded in {@code VARKA-18.md}: the child plan ordinals
 * ({@code ColumnRef} carries the dense kernel input index; the evaluator binds actual columns per
 * task) and the output Spark types (they size the evaluator's output vectors and never reach the
 * emitter). Neither affects the bytes, and leaving them out raises the hit rate. The class name,
 * the {@code SourceFile} and the plan fragment are absent for the opposite reason: the cache
 * derives all three from this key's own hash.
 *
 * <p>{@link VarkaEmitOptions} is present, and that is what it covers: every byte-affecting
 * input of the emitter besides the shape is a key component, so a variant simply gets its own
 * entry and its own class. {@link #VarkaShapeKey(List, int, int)} supplies the defaults, which is
 * every production caller.
 *
 * <p>{@code warmed} is the one component that changes the class's name and not its bytes: a
 * kernel its session warms before it serves batches ({@link VarkaKernelWarmup}) is emitted under
 * a name of its own, because the compiler directive that keeps C1 off warmed kernels matches
 * classes by name ({@link VarkaKernelCompileDirective}). A kernel no warm-up will run must not
 * match it, so the two are different classes even where the bytes agree.
 *
 * <p>A wrong hit returns wrong results and the ghost fallback cannot catch it, so the compact
 * constructor takes an immutable copy of {@code outputs}: a caller holding the list it passed in
 * must not be able to mutate a key that is already sitting in the map (see which ported this
 * record from a Scala case class over an immutable {@code Seq} - where the copy was free).
 */
public record VarkaShapeKey(
    List<VarkaVectorIR> outputs,
    int numInputs,
    int numLiterals,
    VarkaEmitOptions options,
    boolean warmed) {

  public VarkaShapeKey {
    outputs = List.copyOf(outputs);
    if (options == null) {
      throw VarkaErrors.illegalArgument("options must not be null");
    }
  }

  /**
   * Equal when every field is, the outputs by {@link VarkaVectorIR#sameStructure}: a record's own
   * {@code equals} compares two equal DAGs that share no object once per path, which a cache hit
   * does with a freshly compiled key and the cached one (VARKA-630). The hash is the record's, so
   * equal keys still hash alike.
   */
  @Override
  public boolean equals(Object other) {
    return this == other || other instanceof VarkaShapeKey k
        && numInputs == k.numInputs && numLiterals == k.numLiterals && warmed == k.warmed
        && options.equals(k.options) && VarkaVectorIR.sameStructure(outputs, k.outputs);
  }

  @Override
  public int hashCode() {
    return java.util.Objects.hash(outputs, numInputs, numLiterals, options, warmed);
  }

  /** A kernel that is not warmed, emitted with {@code options}. */
  public VarkaShapeKey(List<VarkaVectorIR> outputs, int numInputs, int numLiterals,
      VarkaEmitOptions options) {
    this(outputs, numInputs, numLiterals, options, false);
  }

  /** The production shape: {@link VarkaEmitOptions#DEFAULTS}, not warmed. */
  public VarkaShapeKey(List<VarkaVectorIR> outputs, int numInputs, int numLiterals) {
    this(outputs, numInputs, numLiterals, VarkaEmitOptions.DEFAULTS);
  }
}
