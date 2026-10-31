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

import java.lang.classfile.ClassFile;
import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import org.apache.arrow.memory.ArrowBuf;
import org.apache.arrow.memory.BufferAllocator;

import org.apache.spark.sql.catalyst.expressions.codegen.VarkaGeneratedClassLoader;
import org.apache.spark.sql.util.ArrowUtils;

/**
 * Replays a reproduction file (VARKA-315, {@link VarkaReproduction}) to the failure it recorded,
 * outside Spark: what {@code dev/varka_emit.sh --replay <file>} runs.
 *
 * <p>The replay rebuilds the failing kernel from the file rather than running the class bytes the
 * file carries, so that once the emitter is fixed the replay stops reproducing - which is how a fix
 * is checked. In order:
 *
 * <ol>
 *   <li>each output's canonical IR is parsed ({@link VarkaCanonicalIr}) and the shape key rebuilt
 *       with the options the file names; a key whose hash is not the file's is refused, since the
 *       replay would then be of another kernel;</li>
 *   <li>the class is emitted as the shape cache builds it, under the same name, and compared with
 *       the class the file carries, where it carries one;</li>
 *   <li>the failing path is walked as far as the file's stage says: the admission's verification
 *       for {@code planning}, definition and linking for {@code emission-failure}, and a run over
 *       the recorded call for {@code kernel-failure} and {@code memory-violation}, with the
 *       sanitizer's window opened and each buffer registered or guarded as the evaluator and its
 *       runner do, so a mapping is checked against the same bounds;</li>
 *   <li>what that threw is classified with {@link VarkaFailureSignature}, the same classifier the
 *       file's signature came from, and the two compared.</li>
 * </ol>
 *
 * <p>A violation is reported only when the replaying JVM runs with the sanitizer on, as the one
 * that wrote the file did; the script starts it so when the file says {@code sanitizer: on}.
 *
 * @param expected the file's signature
 * @param actual what the replay threw, classified; empty when nothing failed
 * @param notes what the replay did and found on the way, a line each
 */
public record VarkaReplay(VarkaFailureSignature expected, Optional<VarkaFailureSignature> actual,
    List<String> notes) {

  /** Whether the replay failed with the file's signature. */
  public boolean reproduced() {
    return actual.isPresent() && actual.get().equals(expected);
  }

  /** The report {@code VarkaEmitDump --replay} prints. */
  public List<String> report() {
    var out = new ArrayList<String>();
    out.add("== Varka replay ==");
    notes.forEach(n -> out.add("  " + n));
    out.add("recorded: " + expected);
    out.add("replayed: " + actual.map(VarkaFailureSignature::toString).orElse("no failure"));
    out.add(reproduced() ? "REPRODUCED: the same signature"
        : actual.isPresent() && actual.get().kind().equals(expected.kind())
            ? "NOT REPRODUCED: the same kind, another message"
            : "NOT REPRODUCED");
    return out;
  }

  /** Replays {@code file}. */
  public static VarkaReplay of(VarkaReproduction file) {
    var notes = new ArrayList<String>();
    var outputs = new ArrayList<VarkaVectorIR>();
    for (String output : file.outputs()) {
      outputs.add(VarkaCanonicalIr.parse(output));
    }
    VarkaEmitOptions options = VarkaMatrixRuns.parse(file.options());
    var key = new VarkaShapeKey(outputs, file.numInputs(), file.numLiterals(), options,
        file.warmed());
    String hash = VarkaShapeCacheImpl.shapeHash(key);
    if (!hash.equals(file.shapeHash())) {
      throw new IllegalArgumentException("the file's IR and options hash to " + hash
          + ", not to its shape hash " + file.shapeHash() + ": the replay would not be of the "
          + "kernel that failed");
    }
    notes.add("rebuilt shape " + hash + " (" + outputs.size() + " outputs, " + file.numInputs()
        + " inputs, " + file.numLiterals() + " literals"
        + (file.options().isEmpty() ? "" : ", options " + file.options()) + ")");
    String className = VarkaShapeCacheImpl.classNameFor(hash);
    byte[] bytes;
    try {
      bytes = VarkaLoopEmitter.emit(className, outputs, file.numInputs(), file.numLiterals(),
          VarkaShapeCacheImpl.sourceFileFor(hash), "shape " + hash, options);
    } catch (Throwable t) {
      notes.add("the emitter threw");
      return failed(file, notes, t);
    }
    if (file.classBytes() != null) {
      notes.add(Arrays.equals(bytes, file.classBytes())
          ? "the emitter built the failing class byte for byte"
          : "the emitter built a class that differs from the failing one ("
              + bytes.length + " bytes against " + file.classBytes().length + ")");
    }
    if (file.stage().equals(VarkaErrorContext.PLANNING)) {
      List<VerifyError> errors = ClassFile.of().verify(bytes);
      notes.add("verified the class as the admission does: "
          + (errors.isEmpty() ? "it verifies" : errors.size() + " errors"));
      return errors.isEmpty() ? done(file, notes) : failed(file, notes, errors.get(0));
    }
    var loader = new VarkaGeneratedClassLoader(VarkaReplay.class.getClassLoader());
    try {
      VarkaFusedKernel kernel;
      try {
        kernel = load(loader, className, bytes);
        notes.add("defined and linked the class");
      } catch (Throwable t) {
        notes.add("defining or linking the class threw");
        return failed(file, notes, t);
      }
      if (file.call() == null) {
        return done(file, notes);
      }
      try {
        run(kernel, VarkaKernelPlan.of(outputs, options).drive(), file, notes);
      } catch (Throwable t) {
        notes.add("the kernel threw");
        return failed(file, notes, t);
      }
      return done(file, notes);
    } finally {
      loader.release();
    }
  }

  private static VarkaFusedKernel load(VarkaGeneratedClassLoader loader, String className,
      byte[] bytes) throws Throwable {
    Class<?> klass = loader.defineGeneratedClass(className, bytes);
    try {
      return (VarkaFusedKernel) klass.getConstructor().newInstance();
    } catch (InvocationTargetException e) {
      throw e.getCause();
    }
  }

  private static VarkaReplay failed(VarkaReproduction file, List<String> notes, Throwable t) {
    return new VarkaReplay(file.signature(), Optional.of(VarkaFailureSignature.of(t, "")), notes);
  }

  private static VarkaReplay done(VarkaReproduction file, List<String> notes) {
    return new VarkaReplay(file.signature(), Optional.empty(), notes);
  }

  // ---- the run --------------------------------------------------------------------------------

  /**
   * Runs {@code kernel} over the file's call, its buffers laid out as the runner lays out a
   * batch's: an input registered at the capacity its buffer had, a derived input, the scratch and
   * the outputs guarded at their nominal sizes with a canary past them, and the canaries checked
   * after the kernel returns. Every buffer is filled before the sanitizer's window opens, so the
   * replay's own writes are not checked. A declined batch is not a failure, and is noted.
   */
  private static void run(VarkaFusedKernel kernel, VarkaVectorIR.LaneType lane,
      VarkaReproduction file, List<String> notes) {
    VarkaReproduction.KernelCall call = file.call();
    int len = call.rows();
    int n = call.inputs().size();
    int outputs = call.outputWidths().size();
    if (call.omitted() != null) {
      notes.add("the file carries no batch (" + call.omitted() + "); running a synthetic batch "
          + "of " + len + " rows with the recorded null counts");
    } else {
      notes.add("running the recorded batch of " + len + " rows");
    }
    long words = ((len + 63L) / 64) * 8;
    int rows = VarkaMemorySanitizer.ENABLED ? len + VarkaMemorySanitizer.CANARY_ROWS : len;
    long scratchBytes = (long) call.scratchBytesPerRow() * len;
    BufferAllocator allocator = ArrowUtils.rootAllocator().newChildAllocator("varka-replay", 0,
        Long.MAX_VALUE);
    var owned = new ArrayList<ArrowBuf>();
    try {
      var data = new ArrowBuf[n];
      var validity = new ArrowBuf[n];
      for (int i = 0; i < n; i++) {
        VarkaReproduction.Input input = call.inputs().get(i);
        long dataBytes = (long) len * input.width();
        data[i] = buffer(allocator, owned, Math.max(input.dataCapacity(), dataBytes));
        if (input.data() != null) {
          data[i].setBytes(0, input.data());
        }
        if (input.nullCount() != len) {
          validity[i] = buffer(allocator, owned, Math.max(input.validityCapacity(), words));
          fillValidity(validity[i], words, input, len);
        }
      }
      ArrowBuf scratch = scratchBytes > 0 ? buffer(allocator, owned, scratchBytes) : null;
      var dstData = new ArrowBuf[outputs];
      var dstValidity = new ArrowBuf[outputs];
      // A filter's bitmaps are one buffer of whole words per output, as VarkaFilterEvaluator
      // lays them out; a projection's outputs are a vector each.
      ArrowBuf bitmaps = call.selectionBitmaps() ? buffer(allocator, owned, words * outputs) : null;
      for (int o = 0; o < outputs && bitmaps == null; o++) {
        // A bit output's data is a bitmap of whole words (KernelCall.BIT_OUTPUT).
        dstData[o] = buffer(allocator, owned,
            VarkaReproduction.KernelCall.outputDataBytes(call.outputWidths().get(o), rows));
        dstValidity[o] = buffer(allocator, owned, ((rows + 63L) / 64) * 8);
      }
      var srcData = new long[n];
      var srcValidity = new long[n];
      var nullCounts = new int[n];
      var dst = new long[outputs];
      var dstValid = new long[outputs];
      VarkaMemorySanitizer.begin();
      try {
        for (int i = 0; i < n; i++) {
          VarkaReproduction.Input input = call.inputs().get(i);
          srcData[i] = data[i].memoryAddress();
          srcValidity[i] = validity[i] == null ? 0L : validity[i].memoryAddress();
          nullCounts[i] = input.nullCount();
          if (input.derived()) {
            VarkaMemorySanitizer.guard("derived data", i, data[i],
                Math.max((long) len * input.width(), 8L));
            if (validity[i] != null) {
              VarkaMemorySanitizer.guard("derived validity", i, validity[i], words);
            }
          } else {
            VarkaMemorySanitizer.register("input data", i, data[i].memoryAddress(),
                input.dataCapacity());
            if (validity[i] != null) {
              VarkaMemorySanitizer.register("input validity", i, validity[i].memoryAddress(),
                  input.validityCapacity());
            }
          }
        }
        if (scratch != null) {
          VarkaMemorySanitizer.guard("kernel scratch", 0, scratch, scratchBytes);
        }
        if (bitmaps != null) {
          VarkaMemorySanitizer.guard("selection bitmaps", 0, bitmaps, words * outputs);
          for (int o = 0; o < outputs; o++) {
            dstValid[o] = bitmaps.memoryAddress() + o * words;
          }
        }
        for (int o = 0; o < outputs && bitmaps == null; o++) {
          dst[o] = dstData[o].memoryAddress();
          dstValid[o] = dstValidity[o].memoryAddress();
          VarkaMemorySanitizer.guard("output data", o, dstData[o],
              VarkaReproduction.KernelCall.outputDataBytes(call.outputWidths().get(o), len));
          VarkaMemorySanitizer.guard("output validity", o, dstValidity[o], words);
        }
        int[] ints = file.intLiterals().stream().mapToInt(Integer::intValue).toArray();
        long[] longs = file.longLiterals().stream().mapToLong(Long::longValue).toArray();
        long scratchAddress = scratch == null ? 0L : scratch.memoryAddress();
        // One overload per lane, picked as the runner picks it: the long entry at a 64-bit lane
        // (LONG, and DOUBLE's bits), the int entry (which the narrow lanes and FLOAT share) at
        // every other.
        int status = lane.bits() == Long.SIZE
            ? kernel.run(srcData, srcValidity, nullCounts, dst, dstValid, ints, longs, len,
                scratchAddress)
            : kernel.run(srcData, srcValidity, nullCounts, dst, dstValid, ints, len,
                scratchAddress);
        VarkaMemorySanitizer.verifyCanaries();
        notes.add(status == 0 ? "the kernel returned"
            : "the kernel declined the batch, status " + status);
      } finally {
        VarkaMemorySanitizer.end();
      }
    } finally {
      owned.forEach(ArrowBuf::close);
      allocator.close();
    }
  }

  /**
   * A zeroed buffer of at least {@code bytes}, with room past them for the sanitizer's canary,
   * which a guard places only where the capacity allows.
   */
  private static ArrowBuf buffer(BufferAllocator allocator, List<ArrowBuf> owned, long bytes) {
    ArrowBuf buf = allocator.buffer(Math.max(bytes, 8L) + VarkaMemorySanitizer.CANARY_BYTES);
    owned.add(buf);
    buf.setZero(0, buf.capacity());
    return buf;
  }

  /**
   * The input's validity words: the recorded ones, or, without them, every row valid but
   * {@code nullCount}, spread over the batch as {@code VarkaEmitDump}'s {@code --nulls} spreads
   * them.
   */
  private static void fillValidity(ArrowBuf target, long words, VarkaReproduction.Input input,
      int len) {
    for (long b = 0; b < words; b++) {
      target.setByte(b, 0xFF);
    }
    if (input.validity() != null) {
      target.setBytes(0, input.validity());
    } else if (input.nullCount() > 0) {
      int stride = Math.max(1, len / input.nullCount());
      int cleared = 0;
      for (int r = 0; r < len && cleared < input.nullCount(); r += stride) {
        target.setByte(r / 8, target.getByte(r / 8) & ~(1 << (r % 8)));
        cleared++;
      }
    }
  }
}
