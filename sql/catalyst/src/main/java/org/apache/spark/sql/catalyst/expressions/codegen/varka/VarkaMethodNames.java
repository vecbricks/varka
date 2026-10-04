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
 * The names of the methods an emitted kernel class has, and how to read one back.
 *
 * <p>A kernel class implements the public {@link #DISPATCH} method, which picks a side - dense,
 * when the batch has no nulls, or masked - and calls that side's driver. The driver calls a loop
 * method per output group, directly or through stages when it is split, and then the epilogue
 * that handles the rows the loop leaves: one epilogue over every output, or one per group.
 *
 * <pre>
 *   run -> runDense  -> [stageDense0 ...] -> loopDense0 ...   -> epilogueDense | epilogueDense0 ...
 *       -> runMasked -> [stageMasked0 ...] -> loopMasked0 ... -> epilogueMasked | ...
 * </pre>
 *
 * <p>The names are the class's layout, and code that measures a built class reads them back to
 * find a group's methods, a driver or a stage. In Java this class is the one place either is
 * spelled. Five files outside Java mirror the names because they cannot call it - the
 * deoptimization-cycle guard {@code dev/varka_deopt_cycle.py}, {@code dev/varka_emit.sh},
 * {@code dev/varka_inlining_cliff.py}, and the figure scripts {@code fig21.py} and
 * {@code fig27.py} under {@code sql/varka/plans/figures} - and {@code VarkaMethodNamesSuite}
 * fails when one of them stops naming what the builders produce, so a rename here reaches them.
 */
public final class VarkaMethodNames {

  private VarkaMethodNames() {}

  /** The public method every kernel implements, which dispatches to a side's driver. */
  public static final String DISPATCH = "run";

  private static final String DRIVER = "run";
  private static final String STAGE = "stage";
  private static final String LOOP = "loop";
  private static final String EPILOGUE = "epilogue";

  /** A kind's name on one side, without a group index: {@code loopDense}, {@code stageMasked}. */
  private static String kind(String prefix, boolean dense) {
    return prefix + (dense ? "Dense" : "Masked");
  }

  /** A side's driver: {@code runDense} or {@code runMasked}. */
  public static String driver(boolean dense) {
    return kind(DRIVER, dense);
  }

  /** A side's stage {@code k} of a split driver: {@code stageDense0}, {@code stageMasked3}. */
  public static String stage(boolean dense, int k) {
    return kind(STAGE, dense) + k;
  }

  /** A side's loop method for {@code group}: {@code loopDense0}, {@code loopMasked12}. */
  public static String loop(boolean dense, int group) {
    return kind(LOOP, dense) + group;
  }

  /** A side's single epilogue over every output: {@code epilogueDense}, {@code epilogueMasked}. */
  public static String epilogue(boolean dense) {
    return kind(EPILOGUE, dense);
  }

  /** A side's epilogue for {@code group}: {@code epilogueDense0}, {@code epilogueMasked12}. */
  public static String epilogue(boolean dense, int group) {
    return kind(EPILOGUE, dense) + group;
  }

  /** Both drivers, dense first. */
  public static final List<String> DRIVERS = List.of(driver(true), driver(false));

  /**
   * The four kinds of group method without their group index - the dense and masked loop, then
   * the dense and masked epilogue - in the column order of the emit cost table.
   */
  public static final List<String> GROUP_METHOD_KINDS =
      List.of(kind(LOOP, true), kind(LOOP, false), kind(EPILOGUE, true), kind(EPILOGUE, false));

  /**
   * The method of {@code group} whose kind is {@code GROUP_METHOD_KINDS.get(kindIndex)}: the
   * cost table reads each group's four methods by their column.
   */
  public static String groupMethod(int kindIndex, int group) {
    return GROUP_METHOD_KINDS.get(kindIndex) + group;
  }

  /** Whether {@code method} is a driver. */
  public static boolean isDriver(String method) {
    return DRIVERS.contains(method);
  }

  /** Whether {@code method} is a driver or the dispatch: the methods above the stages. */
  public static boolean isDriverOrDispatch(String method) {
    return isDriver(method) || DISPATCH.equals(method);
  }

  /** Whether {@code method} is a stage of a split driver, on either side. */
  public static boolean isStage(String method) {
    return method.startsWith(STAGE);
  }

  /** Whether {@code method} is a stage on the given side. */
  public static boolean isStage(String method, boolean dense) {
    return method.startsWith(kind(STAGE, dense));
  }

  /** Whether {@code method} is a loop method, on either side. */
  public static boolean isLoop(String method) {
    return method.startsWith(LOOP);
  }

  /** Whether {@code method} is a loop method on the given side. */
  public static boolean isLoop(String method, boolean dense) {
    return method.startsWith(kind(LOOP, dense));
  }

  /** Whether {@code method} is an epilogue, single or per group, on either side. */
  public static boolean isEpilogue(String method) {
    return method.startsWith(EPILOGUE);
  }

  /** Whether {@code method} is an epilogue on the given side. */
  public static boolean isEpilogue(String method, boolean dense) {
    return method.startsWith(kind(EPILOGUE, dense));
  }

  /** Whether {@code method} is a loop or an epilogue: what a group's methods are. */
  public static boolean isGroupMethod(String method) {
    return isLoop(method) || isEpilogue(method);
  }

  /**
   * The group a loop or epilogue method belongs to, read off its name ({@code loopMasked3},
   * {@code epilogueDense12}), or -1 for a method that is not a group's: the drivers, the stages,
   * the dispatch, the constructor, and the single epilogue over every output.
   */
  public static int groupOf(String method) {
    if (!isGroupMethod(method)) {
      return -1;
    }
    int i = method.length();
    while (i > 0 && Character.isDigit(method.charAt(i - 1))) {
      i--;
    }
    return i == method.length() ? -1 : Integer.parseInt(method.substring(i));
  }
}
