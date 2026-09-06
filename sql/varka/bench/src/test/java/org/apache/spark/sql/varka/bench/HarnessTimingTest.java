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

package org.apache.spark.sql.varka.bench;

import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The iteration policy on a fake clock: warm-up excluded, both minimums honoured, and the
 * executor time booked per iteration.
 */
public class HarnessTimingTest {

  @Test
  public void warmsUpThenMeasuresUntilBothMinimumsHold() {
    AtomicLong now = new AtomicLong();
    AtomicLong executor = new AtomicLong();
    AtomicLong runs = new AtomicLong();
    // Every run takes 100 ms of wall time and books 60 ms of executor time.
    Runnable body = () -> {
      now.addAndGet(100_000_000L);
      executor.addAndGet(60L);
      runs.incrementAndGet();
    };
    Harness.Samples s = Harness.measure(body, now::get, executor::get, () -> { }, 5,
        250_000_000L, 1_000_000_000L);
    // Warm-up: three runs to pass 250 ms. Measured: ten runs to pass 1 s (five is the floor).
    assertEquals(13, runs.get());
    assertEquals(10, s.wallMs().size());
    assertEquals(100.0, s.wallMs().get(0), 1e-9);
    assertEquals(60.0, s.executorMs().get(0), 1e-9);
    Harness.Stats w = Harness.stats(s.wallMs());
    assertEquals(100.0, w.bestMs(), 1e-9);
    assertEquals(0.0, w.stdevMs(), 1e-9);
  }

  @Test
  public void statsAreSparksMinMeanAndSampleStdev() {
    Harness.Stats st = Harness.stats(java.util.List.of(10.0, 12.0, 14.0));
    assertEquals(10.0, st.bestMs(), 1e-9);
    assertEquals(12.0, st.avgMs(), 1e-9);
    assertEquals(2.0, st.stdevMs(), 1e-9);
  }
}
