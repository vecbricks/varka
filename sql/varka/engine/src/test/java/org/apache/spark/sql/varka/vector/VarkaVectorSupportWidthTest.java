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

package org.apache.spark.sql.varka.vector;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.io.InputStream;
import java.lang.classfile.ClassFile;
import java.lang.classfile.MethodModel;
import java.lang.classfile.attribute.CodeAttribute;
import java.lang.foreign.Arena;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * The width-specialised whole-group validity helpers (task 46), against the general forms they
 * specialise and against the size that decides whether they inline.
 *
 * <p>They exist because the general pair cannot inline inside a fused loop - 153 and 212
 * bytecode bytes around a four-arm switch on the lane count, refused by C2 for node count
 * (task 32, {@code SKILLS.md}) - and a refused call costs 1.87 to 3.24 ns per lane group
 * whatever the vector width. The emitter picks one of these by name because it knows the width
 * when it writes the bytes. Two things therefore have to hold, and neither is visible in a
 * benchmark: each specialised helper must answer exactly what the general one answers at that
 * width, and each must stay small.
 */
public class VarkaVectorSupportWidthTest {

  /** The lane counts {@link VarkaVectorSupport} has a specialised pair for. */
  private static final int[] WIDTHS = {2, 4, 8, 16};

  /**
   * The bit patterns each width is driven with: all-set and all-clear, one lane at a time, and
   * a few mixed words - including words whose bits above the group are set, which is what the
   * writer's lane mask exists to drop and the reader is allowed to leave in place.
   */
  private static long[] patterns(int lanes) {
    long[] fixed = {0L, -1L, 1L, 1L << (lanes - 1), 0x5555555555555555L, 0xAAAAAAAAAAAAAAAAL};
    long[] all = new long[fixed.length + lanes];
    System.arraycopy(fixed, 0, all, 0, fixed.length);
    for (int lane = 0; lane < lanes; lane++) {
      all[fixed.length + lane] = 1L << lane;
    }
    return all;
  }

  private static MethodHandle reader(int lanes) throws Exception {
    return MethodHandles.lookup().findStatic(VarkaVectorSupport.class,
        "validityBitsAt" + lanes,
        MethodType.methodType(long.class, MemorySegment.class, long.class));
  }

  private static MethodHandle writer(int lanes) throws Exception {
    return MethodHandles.lookup().findStatic(VarkaVectorSupport.class,
        "orValidityBitsAt" + lanes,
        MethodType.methodType(void.class, MemorySegment.class, long.class, long.class));
  }

  /**
   * The reader, over every whole group of a 128-row bitmap holding every byte value in turn.
   * A group's first row is a multiple of the lane count, which is what lets the 8- and 16-lane
   * forms drop the shift; the rows are walked that way here for the same reason.
   */
  @Test
  public void specialisedReadersAnswerWhatTheGeneralOneAnswers() throws Throwable {
    try (Arena arena = Arena.ofConfined()) {
      int rows = 128;
      MemorySegment bitmap = arena.allocate(rows / 8, 8);
      for (int b = 0; b < rows / 8; b++) {
        bitmap.set(ValueLayout.JAVA_BYTE, b, (byte) (b * 17 + 3));
      }
      for (int lanes : WIDTHS) {
        MethodHandle specialised = reader(lanes);
        for (long row = 0; row + lanes <= rows; row += lanes) {
          long expected = VarkaVectorSupport.validityBitsAt(bitmap, row, lanes);
          long actual = (long) specialised.invokeExact(bitmap, row);
          assertEquals(expected, actual,
              "validityBitsAt" + lanes + " at row " + row);
        }
      }
    }
  }

  /**
   * The writer, group by group and pattern by pattern, against the general form run on an
   * identical bitmap. Both buffers carry a byte past the bitmap, pre-set, so a write that runs
   * off the end is a failure rather than a silent corruption of whatever sits there.
   */
  @Test
  public void specialisedWritersWriteWhatTheGeneralOneWrites() throws Throwable {
    try (Arena arena = Arena.ofConfined()) {
      int rows = 128;
      int bytes = rows / 8;
      for (int lanes : WIDTHS) {
        MethodHandle specialised = writer(lanes);
        for (long pattern : patterns(lanes)) {
          MemorySegment mine = arena.allocate(bytes + 1L, 8);
          MemorySegment theirs = arena.allocate(bytes + 1L, 8);
          mine.fill((byte) 0);
          theirs.fill((byte) 0);
          mine.set(ValueLayout.JAVA_BYTE, bytes, (byte) 0x5A);
          theirs.set(ValueLayout.JAVA_BYTE, bytes, (byte) 0x5A);
          for (long row = 0; row + lanes <= rows; row += lanes) {
            specialised.invokeExact(mine.asSlice(0L, bytes), row, pattern);
            VarkaVectorSupport.orValidityBitsAt(theirs.asSlice(0L, bytes), row, pattern, lanes);
          }
          for (int b = 0; b < bytes; b++) {
            assertEquals(theirs.get(ValueLayout.JAVA_BYTE, b), mine.get(ValueLayout.JAVA_BYTE, b),
                "orValidityBitsAt" + lanes + ", pattern " + Long.toHexString(pattern)
                    + ", byte " + b);
          }
          assertEquals((byte) 0x5A, mine.get(ValueLayout.JAVA_BYTE, bytes),
              "orValidityBitsAt" + lanes + " wrote past the bitmap");
        }
      }
    }
  }

  /**
   * ORing into a bitmap that already holds bits must add and never clear, and must leave the
   * neighbouring group's bits alone - the case a narrow width can get wrong, because two 4-lane
   * groups share one byte and a missing lane mask would let one group's junk bits reach the
   * other's nibble.
   */
  @Test
  public void specialisedWritersOnlyAddBitsInsideTheirOwnGroup() throws Throwable {
    try (Arena arena = Arena.ofConfined()) {
      for (int lanes : WIDTHS) {
        MethodHandle specialised = writer(lanes);
        MemorySegment bitmap = arena.allocate(16, 8);
        bitmap.fill((byte) 0x0F);
        // Every bit set in the word, including the ones above this group.
        specialised.invokeExact(bitmap, (long) lanes, -1L);
        for (int bit = 0; bit < 128; bit++) {
          boolean inGroup = bit >= lanes && bit < 2 * lanes;
          boolean before = (0x0F & (1 << (bit % 8))) != 0;
          int b = bitmap.get(ValueLayout.JAVA_BYTE, bit / 8L) & 0xFF;
          assertEquals(before || inGroup, (b & (1 << (bit % 8))) != 0,
              "orValidityBitsAt" + lanes + " changed bit " + bit);
        }
      }
    }
  }

  /**
   * The size gate, which is the premise of the whole task: a helper this big cannot inline, and
   * once it does not inline the specialisation buys nothing.
   *
   * <p>Two thresholds, because C2 uses two. A call site it judges hot may inline a callee up to
   * {@code FreqInlineSize} (325 by default) and any site may inline one up to
   * {@code MaxInlineSize} (35), so a helper under the smaller bound inlines whether or not the
   * loop has been profiled yet. The byte-aligned widths clear that bound; the 2- and 4-lane
   * forms carry a lane mask and a shift the aligned ones do not need and land above it, which
   * is recorded rather than papered over - the binding constraint on the general helpers was
   * never their size but the node count of their four-arm switch, and these have a quarter of
   * it. What proves the outcome is {@code -XX:+PrintInlining} over a fused loop
   * (`PLAN_TASK_46.md` 6); what this test prevents is the drift that would make that run
   * pointless.
   */
  @Test
  public void specialisedHelpersStaySmallEnoughToInline() throws Exception {
    long maxInlineSize = vmOption("MaxInlineSize", 35);
    long freqInlineSize = vmOption("FreqInlineSize", 325);
    for (int lanes : WIDTHS) {
      int read = codeSize("validityBitsAt" + lanes);
      int write = codeSize("orValidityBitsAt" + lanes);
      assertTrue(read <= maxInlineSize,
          "validityBitsAt" + lanes + " is " + read + " bytes, over MaxInlineSize "
              + maxInlineSize);
      assertTrue(write <= freqInlineSize,
          "orValidityBitsAt" + lanes + " is " + write + " bytes, over FreqInlineSize "
              + freqInlineSize);
      if (lanes >= 8) {
        assertTrue(write <= maxInlineSize,
            "orValidityBitsAt" + lanes + " is byte-aligned and needs neither mask nor shift, "
                + "so it must fit MaxInlineSize " + maxInlineSize + "; it is " + write);
      }
      // A quarter of the general form is the resolved switch: four arms become one.
      assertTrue(write * 3 <= codeSize("orValidityBitsAt"),
          "orValidityBitsAt" + lanes + " (" + write + " bytes) is not materially smaller than "
              + "the general form (" + codeSize("orValidityBitsAt") + ")");
    }
  }

  /** Every specialised name the emitter can emit resolves; a typo here is a LinkageError there. */
  @Test
  public void everySpecialisedNameResolves() throws Exception {
    for (int lanes : WIDTHS) {
      assertTrue(reader(lanes) != null && writer(lanes) != null, "width " + lanes);
    }
  }

  private static int codeSize(String methodName) throws Exception {
    String resource = VarkaVectorSupport.class.getName().replace('.', '/') + ".class";
    byte[] bytes;
    try (InputStream in = VarkaVectorSupport.class.getClassLoader().getResourceAsStream(resource)) {
      bytes = in.readAllBytes();
    }
    List<MethodModel> methods = ClassFile.of().parse(bytes).methods();
    for (MethodModel method : methods) {
      if (method.methodName().equalsString(methodName)) {
        return method.code().map(code -> ((CodeAttribute) code).codeLength()).orElse(0);
      }
    }
    return fail("no method " + methodName);
  }

  /** The JVM's own value for a flag, so the gate tracks the JVM rather than a copied default. */
  private static long vmOption(String name, long fallback) {
    try {
      Object bean = java.lang.management.ManagementFactory.getPlatformMXBean(
          Class.forName("com.sun.management.HotSpotDiagnosticMXBean")
              .asSubclass(java.lang.management.PlatformManagedObject.class));
      Object option = bean.getClass().getMethod("getVMOption", String.class).invoke(bean, name);
      return Long.parseLong((String) option.getClass().getMethod("getValue").invoke(option));
    } catch (ReflectiveOperationException | RuntimeException e) {
      return fallback;
    }
  }
}
