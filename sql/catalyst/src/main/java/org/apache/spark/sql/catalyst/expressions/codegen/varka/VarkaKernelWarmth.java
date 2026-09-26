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

import java.util.concurrent.atomic.AtomicReference;

/**
 * Whether one cached shape's kernel is ready to serve batches, shared by every task that runs the
 * shape. A newly emitted class runs interpreted until HotSpot compiles it, and interpreted Vector
 * API code is a library call and an allocation per operation, so the evaluator keeps a cold
 * shape's batches on Spark's row path while {@link VarkaKernelWarmup} gets the kernel compiled in
 * the background, and moves them to the kernel once this says it is ready.
 *
 * <p>The states, in the only order they move:
 * <ul>
 *   <li>{@code COLD} - emitted, and no task has asked for a warm-up yet;</li>
 *   <li>{@code WARMING} - one task claimed the shape and queued a warm-up on a copy of its batch;
 *   every other task of the shape takes the row path meanwhile;</li>
 *   <li>{@code COMPILED} - the warm-up saw the kernel run without allocating, which it does only
 *   once C2's code, with the Vector API intrinsics, is in place;</li>
 *   <li>{@code RELEASED} - the warm-up stopped without that verdict: it ran out of time, the shape
 *   left the cache, or the kernel failed. Tasks run the kernel from here on, and it compiles from
 *   the profile the warm-up's calls began and their batches continue.</li>
 * </ul>
 * The one step back is {@code WARMING} to {@code COLD}, taken when the claiming task could not
 * queue a warm-up for its batch - the batch was declined before the kernel would have run, or
 * the queue was full - so that a later batch can claim the shape instead.
 */
public final class VarkaKernelWarmth {

  /** Where the shape's kernel is on its way to serving batches; see the class doc. */
  public enum State { COLD, WARMING, COMPILED, RELEASED }

  private final AtomicReference<State> state = new AtomicReference<>(State.COLD);

  public State state() {
    return state.get();
  }

  /** Whether tasks should run the kernel: it is compiled, or nothing is warming it any more. */
  public boolean ready() {
    State s = state.get();
    return s == State.COMPILED || s == State.RELEASED;
  }

  /** Claims the shape for a warm-up; true for exactly one caller while the shape is cold. */
  public boolean tryClaim() {
    return state.compareAndSet(State.COLD, State.WARMING);
  }

  /** Hands a claim back, so a later batch can take it; see the class doc. */
  public void unclaim() {
    state.compareAndSet(State.WARMING, State.COLD);
  }

  /** Records the warm-up's verdict that the kernel is compiled; false if it was released first. */
  boolean markCompiled() {
    return state.compareAndSet(State.WARMING, State.COMPILED);
  }

  /**
   * Stops waiting for a compile: tasks run the kernel from now on. A no-op once compiled, and safe
   * from any thread - the cache calls it when the shape leaves, and a running warm-up reads it as
   * its signal to stop.
   */
  public void release() {
    state.getAndUpdate(s -> s == State.COMPILED ? s : State.RELEASED);
  }
}
