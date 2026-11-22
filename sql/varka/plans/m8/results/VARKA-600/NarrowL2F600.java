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
// Row 600 step 1: whether C2 intrinsifies the calls the two new L2F forms make at
// -XX:UseAVX=2, at MaxVectorSize 16 and 32: LongVector.reduceLanes(OR) for WINDOWED's window
// test, LongVector.lane and FloatVector.withLane for SCALAR. Not Varka code; run with
// --add-modules jdk.incubator.vector and -XX:+PrintIntrinsics. Argument: windowed or scalar.
// Each form is checked against (float) v on every row, inside and past the window; the
// windowed form's slow branch is the scalar one here, to keep the model to the calls asked.
// A group stores the whole float species, its upper lanes zero; the next group overwrites them.
// The allocation read in main was added after the intrinsics logs were taken, in a tiered run;
// the three methods the logs compile are unchanged by it.
import jdk.incubator.vector.DoubleVector;
import jdk.incubator.vector.FloatVector;
import jdk.incubator.vector.LongVector;
import jdk.incubator.vector.VectorOperators;
import jdk.incubator.vector.VectorShape;
import jdk.incubator.vector.VectorSpecies;

public class NarrowL2F600 {
  static final double MAGIC = 0x1.8p52;
  static final long MAGIC_BITS = Double.doubleToRawLongBits(MAGIC);
  static final VectorSpecies<Long> LS = LongVector.SPECIES_PREFERRED;
  static final VectorSpecies<Float> FS =
      FloatVector.SPECIES_PREFERRED.withShape(VectorShape.forBitSize(LS.vectorBitSize()));

  /** The window test and the bounded magic; past the window Java's cast per lane. */
  static void windowed(long[] src, float[] dst) {
    int n = LS.length();
    for (int i = 0; i + n <= src.length; i += n) {
      var v = LongVector.fromArray(LS, src, i);
      long w = v.add(1L << 51).lanewise(VectorOperators.LSHR, 52)
          .reduceLanes(VectorOperators.OR);
      FloatVector f;
      if (w == 0) {
        DoubleVector d = v.add(MAGIC_BITS).reinterpretAsDoubles().sub(MAGIC);
        f = (FloatVector) d.convertShape(VectorOperators.D2F, FS, 0);
      } else {
        f = scalarLanes(v);
      }
      f.intoArray(dst, i);
    }
  }

  static FloatVector scalarLanes(LongVector v) {
    var f = FloatVector.zero(FS);
    for (int k = 0; k < LS.length(); k++) {
      f = f.withLane(k, (float) v.lane(k));
    }
    return f;
  }

  static void scalar(long[] src, float[] dst) {
    int n = LS.length();
    for (int i = 0; i + n <= src.length; i += n) {
      scalarLanes(LongVector.fromArray(LS, src, i)).intoArray(dst, i);
    }
  }

  public static void main(String[] args) {
    boolean win = args[0].equals("windowed");
    var src = new long[4096];
    var rnd = new java.util.SplittableRandom(600);
    for (int i = 0; i < src.length; i++) {
      // Most rows inside the window; one row in 64 anywhere in the long range.
      src[i] = i % 64 == 63 ? rnd.nextLong() : rnd.nextLong(-(1L << 51), 1L << 51);
    }
    src[7] = (1L << 53) + (1L << 29) + 1;
    src[8] = Long.MIN_VALUE;
    src[9] = (1L << 51) - 1;
    src[10] = 1L << 51;
    src[11] = -(1L << 51);
    src[12] = -(1L << 51) - 1;
    var dst = new float[src.length + FS.length()];
    long t0 = System.nanoTime();
    for (int r = 0; r < 20_000; r++) {
      if (win) {
        windowed(src, dst);
      } else {
        scalar(src, dst);
      }
    }
    long t1 = System.nanoTime();
    // What a warm call allocates: a vector boxed where the two arms meet shows here.
    var mx = (com.sun.management.ThreadMXBean) java.lang.management.ManagementFactory
        .getThreadMXBean();
    long a0 = mx.getCurrentThreadAllocatedBytes();
    for (int r = 0; r < 1_000; r++) {
      if (win) {
        windowed(src, dst);
      } else {
        scalar(src, dst);
      }
    }
    long a1 = mx.getCurrentThreadAllocatedBytes();
    for (int i = 0; i < src.length; i++) {
      // A long converts to no NaN, so the bits are the whole answer.
      if (Float.floatToRawIntBits(dst[i])
          != Float.floatToRawIntBits((float) src[i])) { // nan-compare: ok
        throw new AssertionError(args[0] + " row " + i + ": " + src[i] + " -> " + dst[i]);
      }
    }
    System.out.printf("%s: %d long lanes, %d float lanes, every row exact, %.2f ns a row, "
        + "%.1f bytes allocated a row%n", args[0], LS.length(), FS.length(),
        (t1 - t0) / (20_000.0 * src.length), (a1 - a0) / (1_000.0 * src.length));
  }
}
