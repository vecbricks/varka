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

import java.util.Map;

/**
 * The prices {@link VarkaEmitCost} predicts with: for each feature, what it adds to each
 * of a group's four methods in bytes and then in Vector API call sites, in
 * {@link VarkaMethodNames#GROUP_METHOD_KINDS} order, fitted by least squares over emitted
 * groups; and the margins the planned grouping keeps under the budgets, the largest
 * under-prediction the fit makes on its own groups in the band where each budget binds
 * ({@code VARKA-236.md} 3.3).
 *
 * <p>Generated, not written: {@code VarkaEmitCostSuite} derives it from emitted classes at
 * the default options and sixteen int lanes, fails while this file differs from what it
 * derives, and rewrites it under {@code VARKA_COST_REGEN=true build/sbt
 * 'catalyst/testOnly *VarkaEmitCostSuite'}. How it is derived is
 * {@code VarkaEmitCostFit}'s doc.
 */
final class VarkaEmitCostTable {

  private VarkaEmitCostTable() {}

  /**
   * The share of the byte budget a planned group is closed under, so that a method the
   * prices under-predict still measures within it.
   */
  static final double BYTES_MARGIN = 0.209;

  /** The same for the call-site budget. */
  static final double SITES_MARGIN = 0.182;

  /** Each feature's price, by feature. */
  static final Map<String, double[]> PRICES = Map.ofEntries(
      Map.entry("AddDays",
          new double[] {11.418, 13.897, 12.162, 14.67, 1.479, 1.567, 1.523, 1.582}),
      Map.entry("AddMonths",
          new double[] {611.612, 677.724, 614.914, 683.451, 79.853, 82.163, 80.212, 82.225}),
      Map.entry("And/INT",
          new double[] {5.063, 8.425, 4.106, 8.261, -0.464, -0.482, -0.66, -0.53}),
      Map.entry("And/LONG",
          new double[] {9.045, 16.948, 10.25, 17.116, -0.245, -0.922, -0.105, -0.891}),
      Map.entry("BitCount/INT",
          new double[] {22.406, -13.741, 22.79, -14.991, 3.918, 3.759, 4.014, 3.785}),
      Map.entry("BitCount/LONG",
          new double[] {4.75, -65.565, 5.813, -65.051, 1.082, 1.907, 1.233, 1.958}),
      Map.entry("BitOp/AND/INT",
          new double[] {9.669, -95.797, 9.939, -98.837, 0.92, 0.228, 1.134, 0.275}),
      Map.entry("BitOp/AND/LONG",
          new double[] {9.816, -155.186, 10.503, -154.81, 0.899, 2.141, 1.226, 2.218}),
      Map.entry("BitOp/OR/INT",
          new double[] {9.669, -95.797, 9.939, -98.837, 0.92, 0.228, 1.134, 0.275}),
      Map.entry("BitOp/OR/LONG",
          new double[] {9.816, -155.186, 10.503, -154.81, 0.899, 2.141, 1.226, 2.218}),
      Map.entry("BitOp/SHL/INT",
          new double[] {9.669, -95.797, 9.939, -98.837, 0.92, 0.228, 1.134, 0.275}),
      Map.entry("BitOp/SHL/LONG",
          new double[] {9.816, -155.186, 10.503, -154.81, 0.899, 2.141, 1.226, 2.218}),
      Map.entry("BitOp/SHR/INT",
          new double[] {9.669, -95.797, 9.939, -98.837, 0.92, 0.228, 1.134, 0.275}),
      Map.entry("BitOp/SHR/LONG",
          new double[] {9.816, -155.186, 10.503, -154.81, 0.899, 2.141, 1.226, 2.218}),
      Map.entry("BitOp/USHR/INT",
          new double[] {9.669, -95.797, 9.939, -98.837, 0.92, 0.228, 1.134, 0.275}),
      Map.entry("BitOp/USHR/LONG",
          new double[] {9.816, -155.186, 10.503, -154.81, 0.899, 2.141, 1.226, 2.218}),
      Map.entry("BitOp/XOR/INT",
          new double[] {9.669, -95.797, 9.939, -98.837, 0.92, 0.228, 1.134, 0.275}),
      Map.entry("BitOp/XOR/LONG",
          new double[] {9.816, -155.186, 10.503, -154.81, 0.899, 2.141, 1.226, 2.218}),
      Map.entry("BoundedDivide",
          new double[] {13.12, 16.032, 13.191, 16.125, 1.968, 2.23, 1.991, 2.229}),
      Map.entry("ColumnRef/INT",
          new double[] {19.096, 91.931, 21.13, 95.392, 0.804, 0.99, 0.755, 0.981}),
      Map.entry("ColumnRef/LONG",
          new double[] {21.147, 105.695, 23.359, 107.882, 0.985, 0.637, 0.92, 0.633}),
      Map.entry("Compare/EQ/INT",
          new double[] {21.112, 52.217, 24.304, 53.547, 1.727, 1.958, 2.225, 2.078}),
      Map.entry("Compare/EQ/LONG",
          new double[] {16.25, 47.862, 16.798, 47.804, 1.525, 2.794, 1.436, 2.751}),
      Map.entry("Compare/GE/INT",
          new double[] {18.686, 49.269, 21.435, 50.482, 1.615, 1.598, 2.104, 1.754}),
      Map.entry("Compare/GE/LONG",
          new double[] {16.606, 46.153, 16.968, 46.306, 1.361, 2.585, 1.395, 2.607}),
      Map.entry("Compare/GT/INT",
          new double[] {21.244, 38.78, 23.61, 39.01, 1.775, 0.981, 2.106, 1.078}),
      Map.entry("Compare/GT/LONG",
          new double[] {16.567, 50.637, 16.819, 50.718, 1.527, 2.799, 1.43, 2.786}),
      Map.entry("Compare/LE/INT",
          new double[] {24.767, 40.074, 27.582, 40.611, 1.777, 1.513, 2.112, 1.612}),
      Map.entry("Compare/LE/LONG",
          new double[] {17.789, 47.609, 18.696, 47.794, 1.531, 2.295, 1.538, 2.316}),
      Map.entry("Compare/LT/INT",
          new double[] {23.712, 38.85, 27.223, 39.175, 1.619, 1.433, 2.074, 1.587}),
      Map.entry("Compare/LT/LONG",
          new double[] {18.01, 44.26, 18.748, 44.44, 1.469, 2.662, 1.477, 2.684}),
      Map.entry("ConstDivide/INT/neg",
          new double[] {106.148, 108.963, 106.564, 108.945, 11.733, 12.135, 11.838, 12.15}),
      Map.entry("ConstDivide/INT/pos",
          new double[] {104.785, 103.217, 104.984, 103.143, 11.015, 10.907, 11.06, 10.913}),
      Map.entry("ConstDivide/LONG/neg",
          new double[] {32.292, 26.64, 32.18, 26.635, 2.89, 2.919, 2.834, 2.904}),
      Map.entry("ConstDivide/LONG/pos",
          new double[] {31.943, 19.915, 32.31, 20.033, 2.952, 2.826, 2.993, 2.832}),
      Map.entry("DateDiff",
          new double[] {4.992, 4.234, 5.033, 4.458, 1.174, 1.196, 1.193, 1.199}),
      Map.entry("DayOfMonth",
          new double[] {20.309, 38.756, 20.414, 39.214, 3.699, 5.126, 3.82, 5.12}),
      Map.entry("DayOfWeek",
          new double[] {114.436, 117.329, 114.373, 117.352, 15.968, 16.023, 15.991, 16.024}),
      Map.entry("DayOfWeekIso",
          new double[] {116.764, 113.186, 117.124, 113.089, 16.009, 15.586, 15.987, 15.586}),
      Map.entry("DayOfYear",
          new double[] {92.025, 114.455, 91.361, 117.167, 12.664, 12.716, 12.633, 12.722}),
      Map.entry("Greatest/INT",
          new double[] {6.667, 69.602, 7.015, 70.11, 1.111, 3.412, 1.153, 3.412}),
      Map.entry("Greatest/LONG",
          new double[] {6.271, 61.514, 6.735, 61.541, 1.12, 3.234, 1.184, 3.24}),
      Map.entry("GuardedDay",
          new double[] {43.309, 67.231, 48.518, 72.65, 2.06, 2.192, 2.092, 2.194}),
      Map.entry("GuardedRange/INT",
          new double[] {45.353, 69.661, 50.689, 75.102, 2.012, 2.423, 2.017, 2.42}),
      Map.entry("GuardedRange/LONG",
          new double[] {41.734, 51.667, 47.134, 56.72, 2.033, 2.189, 2.055, 2.192}),
      Map.entry("IfElse/INT",
          new double[] {3.824, 66.537, 2.196, 68.058, 0.765, 0.774, 0.486, 0.67}),
      Map.entry("IfElse/LONG",
          new double[] {5.124, 43.976, 5.073, 43.966, 0.778, -0.015, 0.885, 0.002}),
      Map.entry("InRanges",
          new double[] {92.014, 100.085, 96.358, 101.061, 3.807, 4.666, 4.318, 4.811}),
      Map.entry("IntArith/ADD/FAIL/INT",
          new double[] {69.452, 83.09, 75.255, 88.237, 5.171, 4.774, 5.395, 4.831}),
      Map.entry("IntArith/ADD/FAIL/LONG",
          new double[] {62.188, 80.975, 67.631, 86.058, 5.052, 5.25, 5.069, 5.25}),
      Map.entry("IntArith/ADD/NULL/INT",
          new double[] {0, 106.779, 0, 108.976, 0, 5.236, 0, 5.268}),
      Map.entry("IntArith/ADD/NULL/LONG",
          new double[] {0, 90.145, 0, 90.255, 0, 5.248, 0, 5.26}),
      Map.entry("IntArith/ADD/WRAP/INT",
          new double[] {8.603, -12.768, 8.964, -13.989, 1.419, 0.56, 1.44, 0.547}),
      Map.entry("IntArith/ADD/WRAP/LONG",
          new double[] {6.179, 10.608, 6.183, 10.589, 1.069, 0.983, 1.074, 0.981}),
      Map.entry("IntArith/MUL/WRAP/INT",
          new double[] {7.72, 10.818, 7.983, 11.275, 1.494, 1.778, 1.536, 1.788}),
      Map.entry("IntArith/MUL/WRAP/LONG",
          new double[] {5.117, 8.32, 5.483, 8.322, 1.021, 1.174, 1.098, 1.177}),
      Map.entry("IntArith/SUB/FAIL/INT",
          new double[] {70.209, 81.388, 76.48, 86.472, 5.349, 4.627, 5.564, 4.652}),
      Map.entry("IntArith/SUB/FAIL/LONG",
          new double[] {60.653, 81.167, 66.686, 86.298, 5.119, 5.24, 5.295, 5.259}),
      Map.entry("IntArith/SUB/NULL/INT",
          new double[] {0, 104.033, 0, 105.938, 0, 5.589, 0, 5.588}),
      Map.entry("IntArith/SUB/NULL/LONG",
          new double[] {0, 88.287, 0, 88.468, 0, 5.405, 0, 5.424}),
      Map.entry("IntArith/SUB/WRAP/INT",
          new double[] {4.521, -5.597, 3.241, -5.883, 0.973, 0.622, 0.967, 0.636}),
      Map.entry("IntArith/SUB/WRAP/LONG",
          new double[] {5.89, 12.199, 5.853, 12.244, 1.034, 0.871, 1.109, 0.873}),
      Map.entry("IntNeg/FAIL/INT",
          new double[] {31.831, 52.95, 36.733, 58.308, 1.82, 1.901, 1.812, 1.901}),
      Map.entry("IntNeg/FAIL/LONG",
          new double[] {29.646, 37.994, 34.077, 42.986, 1.99, 2.102, 1.867, 2.095}),
      Map.entry("IntNeg/WRAP/INT",
          new double[] {2.142, -0.695, 2.091, -1.018, 0.871, 1.031, 0.887, 1.038}),
      Map.entry("IntNeg/WRAP/LONG",
          new double[] {7.129, 3.03, 7.68, 3.128, 1.092, 0.975, 1.066, 0.982}),
      Map.entry("IsNotNull/INT",
          new double[] {5.787, 17.012, 1.279, 15.145, 0.156, 0.45, 0.353, 0.45}),
      Map.entry("IsNotNull/LONG",
          new double[] {7.218, 13.936, 2.867, 12.905, 0.137, 1.22, -0.039, 1.18}),
      Map.entry("LastDay",
          new double[] {218.554, 264.554, 219.788, 268.212, 31.003, 33.412, 31.125, 33.41}),
      Map.entry("Least/INT",
          new double[] {6.366, 72.909, 6.493, 73.562, 1.141, 3.573, 1.124, 3.567}),
      Map.entry("Least/LONG",
          new double[] {5.295, 61.627, 5.09, 61.661, 1.049, 3.172, 1.043, 3.172}),
      Map.entry("LiteralSlot/INT",
          new double[] {7.636, 9.964, 6.448, 9.083, 0.99, 1.354, 1.062, 1.377}),
      Map.entry("LiteralSlot/LONG",
          new double[] {14.113, 7.25, 13.506, 7.255, 1.318, 2.014, 1.465, 2.069}),
      Map.entry("MakeDate/ansi",
          new double[] {475.499, 525.63, 481.274, 531.745, 53.614, 53.548, 53.499, 53.545}),
      Map.entry("MakeDate/null",
          new double[] {0, 527.877, 0, 533.862, 0, 54.312, 0, 54.327}),
      Map.entry("Month",
          new double[] {19.671, 37.412, 19.782, 37.665, 2.756, 4.325, 2.898, 4.322}),
      Map.entry("NarrowLane",
          new double[] {36.455, 34.212, 30.591, 31.465, 1.436, 2.657, 1.442, 2.644}),
      Map.entry("NextDay",
          new double[] {106.139, 104.671, 106.368, 104.766, 15.169, 15.339, 15.177, 15.338}),
      Map.entry("Not/INT",
          new double[] {8.039, 4.416, 8.838, 5.177, -0.021, 0.486, 0.015, 0.538}),
      Map.entry("Not/LONG",
          new double[] {8.118, -5.051, 8.288, -5.035, 0.122, -0.154, -0.039, -0.19}),
      Map.entry("Or/INT",
          new double[] {-2.814, 8.335, -4.156, 7.654, -0.759, -0.145, -0.781, -0.173}),
      Map.entry("Or/LONG",
          new double[] {5.992, 19.214, 6.922, 19.359, -0.383, -1.242, -0.131, -1.213}),
      Map.entry("Quarter",
          new double[] {35.071, 58.602, 34.909, 59.216, 5.648, 7.147, 5.737, 7.136}),
      Map.entry("SubDays",
          new double[] {10.46, 17.174, 11.128, 18.058, 1.459, 1.905, 1.464, 1.909}),
      Map.entry("ThursdayOf",
          new double[] {121.169, 118.84, 121.643, 119.072, 16.969, 16.641, 17.047, 16.655}),
      Map.entry("TruncDate/MONTH",
          new double[] {16.5, 50.079, 15.798, 51.212, 3.516, 5.658, 3.521, 5.661}),
      Map.entry("TruncDate/QUARTER",
          new double[] {212.001, 259.536, 211.584, 262.281, 29.806, 31.613, 29.905, 31.647}),
      Map.entry("TruncDate/YEAR",
          new double[] {112.246, 126.928, 112.052, 129.551, 14.94, 14.638, 14.917, 14.641}),
      Map.entry("TruncDateDynamic",
          new double[] {414.158, 459.383, 413.775, 461.271, 57.917, 61.743, 58.025, 61.744}),
      Map.entry("WeekDay",
          new double[] {110.337, 111.394, 110.006, 111.476, 15.025, 14.887, 14.965, 14.878}),
      Map.entry("WeekOfYear",
          new double[] {112.261, 111.98, 110.993, 114.929, 16.533, 12.661, 16.432, 12.665}),
      Map.entry("Year",
          new double[] {22.836, 45.554, 22.146, 48.659, 3.689, 3.486, 3.659, 3.493}),
      Map.entry("fixed/INT",
          new double[] {81.167, 51.41, 93.591, 63.539, -0.119, -0.842, -0.029, -0.835}),
      Map.entry("fixed/LONG",
          new double[] {87.535, 52.905, 98.928, 65.301, -0.465, -0.804, -0.327, -0.807}),
      Map.entry("out/INT",
          new double[] {24.654, 21.207, 26.733, 23.537, 1.197, 1.544, 1.128, 1.532}),
      Map.entry("out/LONG",
          new double[] {22.782, 33.016, 24.947, 34.967, 1.199, 1.129, 1.087, 1.107}),
      Map.entry("out/cond/INT",
          new double[] {13.001, 16.005, 9.604, 13.907, -0.716, -1.718, -0.965, -1.465}),
      Map.entry("out/cond/LONG",
          new double[] {14.192, 24.714, 12.026, 23.042, -0.637, -1.891, -0.336, -1.306}),
      Map.entry("prefix",
          new double[] {265.798, 274.481, 266.668, 272.885, 28.432, 31.142, 28.462, 31.142}),
      Map.entry("prefix/load",
          new double[] {16.58, -6.51, 19.461, -5.321, -1.052, -3.034, -1.116, -3.049}),
      Map.entry("prefix/month",
          new double[] {16.098, -1.469, 15.536, 0.537, 1.999, -1.852, 1.838, -1.843}));
}
