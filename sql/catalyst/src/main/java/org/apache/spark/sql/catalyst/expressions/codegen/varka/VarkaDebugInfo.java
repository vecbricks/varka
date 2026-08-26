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

import java.util.Objects;
import java.util.Optional;

/**
 * The {@code VarkaDebugInfo} class attribute (milestone 2, task 13): the emitted fused-kernel
 * class carries, baked into its own bytes, the vector IR it was emitted from, the plan
 * fragment that produced that IR, and (task 16) the key that decodes its {@code LineNumberTable}
 * back to IR nodes. A generated class dumped from a heap, a Metaspace profile or a
 * {@code -XX:+DumpLoadedClassList}-style capture is thereby self-describing - no live session
 * or log correlation is needed to answer "which projection is this loop?", nor "which node is
 * the frame at line 7?".
 *
 * <p>The attribute is a standard class-file custom attribute: one the JVM does not recognize
 * and therefore ignores entirely ("Attributes" in the JVMS - unrecognized attributes must be
 * silently skipped), so it costs nothing at class load or JIT time and cannot affect execution.
 * The byte format is normative here: the attribute body is exactly
 * {@code u2 ir_index; u2 plan_index; u2 lines_index}, three constant-pool UTF-8 references -
 * which is also why the mapper stability is {@code CP_REFS} - and the writer emits the
 * six-byte name-and-length header itself while a reader's position starts after it.
 *
 * <p><b>Why this class is a plain holder, with no {@code java.lang.classfile} import.</b>
 * Scala 2.13's typechecker - scalac and scaladoc alike, and the Maven build runs scaladoc via
 * {@code attach-scaladocs} - reports an "illegal cyclic reference" when completing many
 * Class-File API symbols: completing a sealed interface forces its permitted implementation
 * classes, whose completion walks back into the hierarchy (the bug that keeps
 * {@link VarkaLoopEmitter} in Java at all). Its Java parser skips method <i>bodies</i>
 * entirely, but it does complete supertypes and imported symbols - a first cut of this class
 * extending {@code CustomAttribute} broke the build's scaladoc pass, and so did a cut that
 * merely kept the {@code java.lang.classfile} imports with empty bodies. The working regime,
 * pinned by the {@code catalyst/doc} gate: no class-file type in any signature or import in
 * this file; the attribute subclass and its read-side mapper live as fully-qualified local
 * classes inside {@link #read}'s body, and the write side lives in the emitter's private
 * {@code debugElement} beside its only call site, each with a single-purpose mapper - the
 * price of keeping the hierarchy away from scalac.
 *
 * <p>Reading the attribute back needs its mapper registered, since an unregistered custom
 * attribute parses as an opaque {@code UnknownAttribute}: {@link #read} does the registration
 * and lookup in one step over raw class bytes. Scala callers go through
 * {@link VarkaDebugInfoReader}, which adds the {@code SourceFile} accessor and keeps all
 * class-file parsing on the Java side.
 *
 * <p>Milestone 3's cross-task byte cache must reconcile with this attribute (and with the
 * emitted {@code SourceFile} name): cached bytes would replay another query's telemetry
 * verbatim. That reconciliation is part of the cache's design, recorded in
 * {@code PLAN_MILESTONE_3.md}, not something this class anticipates.
 */
public final class VarkaDebugInfo {

  /** The attribute name as it appears in the class file. */
  public static final String NAME = "VarkaDebugInfo";

  private final String ir;
  private final String planFragment;
  private final String lineMap;

  public VarkaDebugInfo(String ir, String planFragment, String lineMap) {
    this.ir = Objects.requireNonNull(ir, "ir");
    this.planFragment = Objects.requireNonNull(planFragment, "planFragment");
    this.lineMap = Objects.requireNonNull(lineMap, "lineMap");
  }

  /** The {@link VarkaVectorIR} roots the class was emitted from, rendered by the emitter. */
  public String ir() {
    return ir;
  }

  /**
   * The Catalyst plan fragment the IR was compiled from, as the caller of the emitter rendered
   * it; empty when the caller recorded none (hand-built IR in tests and benchmarks).
   */
  public String planFragment() {
    return planFragment;
  }

  /**
   * The {@code LineNumberTable}'s decoding key (task 16): one {@code <line>=<node>} entry per
   * distinct IR node, newline separated, so a frame at {@code Varka_Project_Stage3.java:7}
   * resolves to the node the emitter attributed that line to. Empty for a class emitted with
   * no nodes to map.
   */
  public String lineMap() {
    return lineMap;
  }

  /**
   * The diagnostics entry point: parses raw class bytes with the attribute's mapper
   * registered and returns the class's {@code VarkaDebugInfo}, or empty when the class does
   * not carry one (any class the emitter did not produce).
   */
  public static Optional<VarkaDebugInfo> read(byte[] classBytes) {
    final class Parsed extends java.lang.classfile.CustomAttribute<Parsed> {
      final VarkaDebugInfo payload;

      Parsed(java.lang.classfile.AttributeMapper<Parsed> mapper, VarkaDebugInfo payload) {
        super(mapper);
        this.payload = payload;
      }
    }
    final class ReadMapper implements java.lang.classfile.AttributeMapper<Parsed> {
      @Override
      public String name() {
        return NAME;
      }

      @Override
      public Parsed readAttribute(java.lang.classfile.AttributedElement enclosing,
          java.lang.classfile.ClassReader cf, int pos) {
        // pos is the payload start, after the six-byte header the writer emitted.
        return new Parsed(this, new VarkaDebugInfo(
            cf.readEntry(pos, java.lang.classfile.constantpool.Utf8Entry.class).stringValue(),
            cf.readEntry(pos + 2,
                java.lang.classfile.constantpool.Utf8Entry.class).stringValue(),
            cf.readEntry(pos + 4,
                java.lang.classfile.constantpool.Utf8Entry.class).stringValue()));
      }

      @Override
      public void writeAttribute(java.lang.classfile.BufWriter buf, Parsed attr) {
        throw new UnsupportedOperationException(
            "read-side mapper; emission uses the emitter's debugElement");
      }

      @Override
      public AttributeStability stability() {
        return AttributeStability.CP_REFS;
      }
    }
    ReadMapper mapper = new ReadMapper();
    java.lang.classfile.ClassModel model = java.lang.classfile.ClassFile.of(
        java.lang.classfile.ClassFile.AttributeMapperOption.of(
            name -> name.equalsString(NAME) ? mapper : null)).parse(classBytes);
    return model.findAttribute(mapper).map(parsed -> parsed.payload);
  }

  @Override
  public String toString() {
    return NAME + "[ir=" + ir + ", planFragment=" + planFragment + ", lineMap=" + lineMap + "]";
  }
}
