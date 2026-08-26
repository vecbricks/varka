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

import java.lang.classfile.AttributeMapper;
import java.lang.classfile.AttributedElement;
import java.lang.classfile.BufWriter;
import java.lang.classfile.ClassFile;
import java.lang.classfile.ClassModel;
import java.lang.classfile.ClassReader;
import java.lang.classfile.CustomAttribute;
import java.lang.classfile.constantpool.Utf8Entry;
import java.util.Objects;
import java.util.Optional;

/**
 * The {@code VarkaDebugInfo} class attribute (milestone 2, task 13): the emitted fused-kernel
 * class carries, baked into its own bytes, the vector IR it was emitted from and the plan
 * fragment that produced that IR. A generated class dumped from a heap, a Metaspace profile or
 * a {@code -XX:+DumpLoadedClassList}-style capture is thereby self-describing - no live session
 * or log correlation is needed to answer "which projection is this loop?".
 *
 * <p>The attribute is a standard class-file custom attribute: one the JVM does not recognize
 * and therefore ignores entirely ("Attributes" in the JVMS - unrecognized attributes must be
 * silently skipped), so it costs nothing at class load or JIT time and cannot affect execution.
 * Its payload is two constant-pool UTF-8 references - {@code u2 ir_index; u2 plan_index} - which
 * is also why the {@linkplain AttributeMapper#stability() stability} is {@code CP_REFS}: the
 * payload survives any transform that adjusts constant-pool indices, and nothing else in it can
 * go stale.
 *
 * <p>Reading it back needs the mapper registered, since an unregistered custom attribute parses
 * as an opaque {@code UnknownAttribute}: {@link #read} does the registration and lookup in one
 * step over raw class bytes. Scala callers cannot use it - or this type at all, whose supertype
 * belongs to the Class-File API's sealed hierarchy that scalac cannot typecheck - and go
 * through {@link VarkaDebugInfoReader}'s plain-string signatures instead.
 *
 * <p>Milestone 3's cross-task byte cache must reconcile with this attribute (and with the
 * emitted {@code SourceFile} name): cached bytes would replay another query's telemetry
 * verbatim. That reconciliation is part of the cache's design, recorded in
 * {@code PLAN_MILESTONE_3.md}, not something this class anticipates.
 */
public final class VarkaDebugInfo extends CustomAttribute<VarkaDebugInfo> {

  /** The attribute name as it appears in the class file. */
  public static final String NAME = "VarkaDebugInfo";

  /** The mapper that serializes and parses the attribute; see the payload note above. */
  public static final AttributeMapper<VarkaDebugInfo> MAPPER = new Mapper();

  private final String ir;
  private final String planFragment;

  public VarkaDebugInfo(String ir, String planFragment) {
    super(MAPPER);
    this.ir = Objects.requireNonNull(ir, "ir");
    this.planFragment = Objects.requireNonNull(planFragment, "planFragment");
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
   * The diagnostics entry point: parses raw class bytes with {@link #MAPPER} registered and
   * returns the class's {@code VarkaDebugInfo}, or empty when the class does not carry one
   * (any class this emitter did not produce).
   */
  public static Optional<VarkaDebugInfo> read(byte[] classBytes) {
    ClassModel model = ClassFile.of(ClassFile.AttributeMapperOption.of(
        name -> name.equalsString(NAME) ? MAPPER : null)).parse(classBytes);
    return model.findAttribute(MAPPER);
  }

  @Override
  public String toString() {
    return NAME + "[ir=" + ir + ", planFragment=" + planFragment + "]";
  }

  private static final class Mapper implements AttributeMapper<VarkaDebugInfo> {

    @Override
    public String name() {
      return NAME;
    }

    @Override
    public VarkaDebugInfo readAttribute(AttributedElement enclosing, ClassReader cf, int pos) {
      String ir = cf.readEntry(pos, Utf8Entry.class).stringValue();
      String planFragment = cf.readEntry(pos + 2, Utf8Entry.class).stringValue();
      return new VarkaDebugInfo(ir, planFragment);
    }

    @Override
    public void writeAttribute(BufWriter buf, VarkaDebugInfo attr) {
      // The mapper writes the whole attribute structure, the six-byte header included (the
      // built-in mappers do the same); readAttribute's pos, in contrast, is the payload start.
      buf.writeIndex(buf.constantPool().utf8Entry(NAME));
      buf.writeInt(4); // attribute_length: the two u2 constant-pool indices below
      buf.writeIndex(buf.constantPool().utf8Entry(attr.ir));
      buf.writeIndex(buf.constantPool().utf8Entry(attr.planFragment));
    }

    @Override
    public AttributeStability stability() {
      return AttributeStability.CP_REFS;
    }
  }
}
