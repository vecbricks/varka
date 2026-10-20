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
  static final double BYTES_MARGIN = 0.205;

  /** The same for the call-site budget. */
  static final double SITES_MARGIN = 0.176;

  /** Each feature's price, by feature. */
  static final Map<String, double[]> PRICES = Map.ofEntries(
      Map.entry("AddDays",
          new double[] {11.67, 13.768, 12.339, 14.521, 1.532, 1.567, 1.571, 1.581}),
      Map.entry("AddMonths",
          new double[] {609.39, 681.577, 612.448, 687.034, 79.549, 82.735, 79.91, 82.798}),
      Map.entry("And/INT",
          new double[] {-1.445, 4.408, -2.625, 4.136, -0.967, -0.985, -1.176, -1.039}),
      Map.entry("And/LONG",
          new double[] {9.986, 15.24, 11.596, 15.48, -0.057, -1.245, 0.121, -1.195}),
      Map.entry("BoundedDivide",
          new double[] {12.81, 17.526, 12.895, 17.652, 1.963, 2.372, 1.989, 2.372}),
      Map.entry("ColumnRef/INT",
          new double[] {18.514, 92.88, 20.608, 96.403, 0.717, 1.058, 0.678, 1.051}),
      Map.entry("ColumnRef/LONG",
          new double[] {20.719, 106.165, 22.804, 108.315, 0.961, 0.609, 0.888, 0.605}),
      Map.entry("Compare/EQ/INT",
          new double[] {18.743, 52.889, 20.758, 53.919, 1.454, 2.124, 1.774, 2.215}),
      Map.entry("Compare/EQ/LONG",
          new double[] {16.306, 51.914, 16.984, 51.852, 1.382, 2.729, 1.347, 2.707}),
      Map.entry("Compare/GE/INT",
          new double[] {21.677, 50.794, 23.234, 51.372, 1.955, 1.663, 2.279, 1.775}),
      Map.entry("Compare/GE/LONG",
          new double[] {15.001, 51.239, 14.288, 51.132, 1.226, 2.662, 1.15, 2.661}),
      Map.entry("Compare/GT/INT",
          new double[] {25.509, 44.097, 26.936, 44.192, 2.361, 1.418, 2.584, 1.485}),
      Map.entry("Compare/GT/LONG",
          new double[] {15.465, 51.629, 15.507, 51.614, 1.271, 2.748, 1.175, 2.741}),
      Map.entry("Compare/LE/INT",
          new double[] {21.876, 43.34, 23.359, 43.642, 1.644, 1.684, 1.845, 1.759}),
      Map.entry("Compare/LE/LONG",
          new double[] {13.612, 51.526, 14.002, 51.575, 1.136, 2.677, 1.16, 2.69}),
      Map.entry("Compare/LT/INT",
          new double[] {20.928, 45.728, 22.886, 45.983, 1.515, 2.027, 1.818, 2.152}),
      Map.entry("Compare/LT/LONG",
          new double[] {16.919, 54.095, 17.269, 54.2, 1.209, 2.912, 1.249, 2.927}),
      Map.entry("ConstDivide/INT/neg",
          new double[] {107.732, 109.841, 108.376, 109.884, 11.813, 12.126, 11.941, 12.138}),
      Map.entry("ConstDivide/INT/pos",
          new double[] {103.586, 103.791, 103.668, 103.68, 10.908, 10.996, 10.949, 11.003}),
      Map.entry("ConstDivide/LONG/neg",
          new double[] {32.784, 30.207, 32.949, 30.184, 2.95, 2.853, 2.907, 2.838}),
      Map.entry("ConstDivide/LONG/pos",
          new double[] {31.209, 21.387, 31.336, 21.53, 2.917, 2.877, 2.915, 2.878}),
      Map.entry("DateDiff",
          new double[] {3.643, 2.869, 3.493, 2.871, 1.017, 1.264, 1.016, 1.264}),
      Map.entry("DayOfMonth",
          new double[] {20.802, 38.249, 20.859, 38.592, 3.703, 5.201, 3.821, 5.191}),
      Map.entry("DayOfWeek",
          new double[] {112.11, 116.174, 112.202, 116.199, 15.679, 15.861, 15.692, 15.862}),
      Map.entry("DayOfWeekIso",
          new double[] {114.752, 112.517, 114.921, 112.315, 15.861, 15.702, 15.85, 15.702}),
      Map.entry("DayOfYear",
          new double[] {92.289, 111.797, 91.794, 114.322, 12.661, 12.544, 12.617, 12.547}),
      Map.entry("Greatest/INT",
          new double[] {8.707, 68.977, 9.183, 69.519, 1.225, 3.36, 1.265, 3.36}),
      Map.entry("Greatest/LONG",
          new double[] {6.5, 61.313, 6.665, 61.356, 1.163, 3.058, 1.197, 3.061}),
      Map.entry("GuardedDay",
          new double[] {43.498, 63.48, 48.91, 68.697, 2.023, 1.905, 2.059, 1.907}),
      Map.entry("GuardedRange/INT",
          new double[] {45.226, 70.421, 50.299, 75.84, 2.076, 2.512, 2.075, 2.509}),
      Map.entry("GuardedRange/LONG",
          new double[] {41.443, 51.034, 46.682, 56.081, 2.034, 2.194, 2.047, 2.196}),
      Map.entry("IfElse/INT",
          new double[] {4.668, 62.83, 4.041, 64.572, 0.808, 0.474, 0.67, 0.399}),
      Map.entry("IfElse/LONG",
          new double[] {6.81, 40.388, 7.304, 40.466, 1.009, -0.046, 1.138, -0.036}),
      Map.entry("InRanges",
          new double[] {86.945, 106.274, 90.275, 107.088, 3.25, 5.137, 3.682, 5.276}),
      Map.entry("IntArith/ADD/FAIL/INT",
          new double[] {70.059, 85.809, 76.017, 90.988, 5.262, 5.091, 5.512, 5.149}),
      Map.entry("IntArith/ADD/FAIL/LONG",
          new double[] {62.654, 80.866, 67.773, 85.855, 5.071, 5.256, 5.06, 5.251}),
      Map.entry("IntArith/ADD/NULL/INT",
          new double[] {0, 105.526, 0, 107.704, 0, 5.128, 0, 5.16}),
      Map.entry("IntArith/ADD/NULL/LONG",
          new double[] {0, 90.911, 0, 90.97, 0, 5.137, 0, 5.147}),
      Map.entry("IntArith/ADD/WRAP/INT",
          new double[] {9.25, -10.626, 9.756, -11.766, 1.416, 0.678, 1.429, 0.668}),
      Map.entry("IntArith/ADD/WRAP/LONG",
          new double[] {6.151, 11.164, 6.241, 11.176, 1.112, 0.919, 1.115, 0.916}),
      Map.entry("IntArith/MUL/WRAP/INT",
          new double[] {8.129, 7.559, 8.441, 7.902, 1.575, 1.367, 1.608, 1.377}),
      Map.entry("IntArith/MUL/WRAP/LONG",
          new double[] {5.03, 10.345, 5.537, 10.375, 1.034, 1.348, 1.119, 1.351}),
      Map.entry("IntArith/SUB/FAIL/INT",
          new double[] {70.962, 84.714, 77.333, 90.148, 5.455, 4.934, 5.666, 4.96}),
      Map.entry("IntArith/SUB/FAIL/LONG",
          new double[] {61.378, 79.266, 67.282, 84.269, 5.11, 5.31, 5.302, 5.325}),
      Map.entry("IntArith/SUB/NULL/INT",
          new double[] {0, 101.695, 0, 103.357, 0, 5.479, 0, 5.478}),
      Map.entry("IntArith/SUB/NULL/LONG",
          new double[] {0, 89.342, 0, 89.457, 0, 5.406, 0, 5.426}),
      Map.entry("IntArith/SUB/WRAP/INT",
          new double[] {5.167, -5.048, 3.948, -5.178, 1.023, 0.464, 1.014, 0.489}),
      Map.entry("IntArith/SUB/WRAP/LONG",
          new double[] {6.478, 10.576, 6.434, 10.611, 1.076, 0.982, 1.15, 0.986}),
      Map.entry("IntNeg/FAIL/INT",
          new double[] {32.009, 53.09, 36.763, 58.375, 1.928, 1.863, 1.924, 1.861}),
      Map.entry("IntNeg/FAIL/LONG",
          new double[] {30.476, 37.467, 35.144, 42.493, 1.966, 2.212, 1.883, 2.204}),
      Map.entry("IntNeg/WRAP/INT",
          new double[] {2.232, 0.596, 2.194, 0.377, 0.857, 1.095, 0.861, 1.098}),
      Map.entry("IntNeg/WRAP/LONG",
          new double[] {5.975, 2.469, 6.478, 2.593, 0.934, 0.873, 0.945, 0.889}),
      Map.entry("IsNotNull/INT",
          new double[] {-8.778, 18.093, -15.713, 15.103, -1.278, 0.447, -1.211, 0.415}),
      Map.entry("IsNotNull/LONG",
          new double[] {7.856, 24.147, 3.079, 23.034, -0.099, 1.521, -0.323, 1.468}),
      Map.entry("IsNotNull/cond/INT",
          new double[] {-42.483, 27.952, -46.552, 28.555, -4.922, 0.797, -5.272, 0.725}),
      Map.entry("IsNotNull/cond/LONG",
          new double[] {-14.755, 15.73, -17.426, 15.742, -1.588, 0.413, -1.65, 0.428}),
      Map.entry("LastDay",
          new double[] {218.258, 264.785, 219.064, 268.432, 31.089, 33.391, 31.198, 33.382}),
      Map.entry("Least/INT",
          new double[] {6.913, 75.159, 6.913, 75.978, 1.24, 3.699, 1.223, 3.694}),
      Map.entry("Least/LONG",
          new double[] {5.195, 60.05, 5.093, 60.05, 1.012, 3.123, 1.014, 3.124}),
      Map.entry("LiteralSlot/INT",
          new double[] {7.656, 9.355, 6.525, 8.521, 0.95, 1.291, 1.028, 1.315}),
      Map.entry("LiteralSlot/LONG",
          new double[] {13.968, 6.328, 13.225, 6.3, 1.313, 1.994, 1.453, 2.049}),
      Map.entry("MakeDate/ansi",
          new double[] {475.484, 529.071, 480.986, 535.548, 53.725, 53.607, 53.602, 53.61}),
      Map.entry("MakeDate/null",
          new double[] {0, 534.038, 0, 540.467, 0, 54.57, 0, 54.592}),
      Map.entry("Month",
          new double[] {20.1, 35.391, 20.101, 35.551, 2.797, 4.188, 2.938, 4.182}),
      Map.entry("NarrowLane",
          new double[] {36.556, 34.663, 30.949, 31.946, 1.405, 3.108, 1.411, 3.098}),
      Map.entry("NextDay",
          new double[] {105.134, 102.819, 105.291, 102.837, 15.082, 15.274, 15.077, 15.273}),
      Map.entry("Not/INT",
          new double[] {12.224, 2.531, 12.951, 3.367, 0.601, 0.317, 0.718, 0.397}),
      Map.entry("Not/LONG",
          new double[] {7.673, -5.703, 7.502, -5.769, 0.079, -0.113, -0.086, -0.145}),
      Map.entry("Or/INT",
          new double[] {-3.928, 9.646, -4.404, 9.244, -1.233, 0.112, -1.26, 0.095}),
      Map.entry("Or/LONG",
          new double[] {6.983, 8.241, 7.69, 8.354, -0.124, -1.489, 0.067, -1.453}),
      Map.entry("Quarter",
          new double[] {35.308, 59.29, 35.064, 59.963, 5.694, 7.271, 5.796, 7.259}),
      Map.entry("SubDays",
          new double[] {11.118, 16.906, 11.798, 17.768, 1.553, 1.922, 1.56, 1.924}),
      Map.entry("ThursdayOf",
          new double[] {121.75, 118.817, 122.061, 118.961, 17.058, 16.765, 17.145, 16.779}),
      Map.entry("TruncDate/MONTH",
          new double[] {15.859, 44.382, 14.901, 45.407, 3.631, 5.453, 3.634, 5.453}),
      Map.entry("TruncDate/QUARTER",
          new double[] {210.339, 256.959, 209.542, 259.356, 29.627, 31.542, 29.729, 31.568}),
      Map.entry("TruncDate/YEAR",
          new double[] {111.527, 128.697, 111.434, 131.486, 14.856, 14.863, 14.835, 14.861}),
      Map.entry("TruncDateDynamic",
          new double[] {414.336, 469.106, 413.799, 471.151, 57.903, 62.956, 57.999, 62.943}),
      Map.entry("WeekDay",
          new double[] {110.966, 110.151, 110.701, 110.13, 15.052, 14.75, 15.004, 14.742}),
      Map.entry("WeekOfYear",
          new double[] {112.855, 109.937, 111.919, 112.751, 16.609, 12.399, 16.509, 12.397}),
      Map.entry("WidenLane",
          new double[] {32.316, -15.841, 73.237, 23.154, 1.477, 1.599, 1.467, 1.629}),
      Map.entry("Year",
          new double[] {21.884, 45.051, 21.357, 47.934, 3.574, 3.516, 3.554, 3.519}),
      Map.entry("fixed/INT",
          new double[] {82.615, 50.634, 95.008, 62.665, 0.037, -0.861, 0.119, -0.855}),
      Map.entry("fixed/LONG",
          new double[] {88.217, 52.063, 99.995, 64.542, -0.393, -0.748, -0.222, -0.752}),
      Map.entry("out/INT",
          new double[] {24.184, 21.777, 26.292, 24.177, 1.159, 1.492, 1.089, 1.48}),
      Map.entry("out/LONG",
          new double[] {22.921, 32.914, 25.086, 34.878, 1.198, 1.089, 1.075, 1.071}),
      Map.entry("out/cond/INT",
          new double[] {17.103, 12.334, 15.442, 11.239, -0.354, -2.128, -0.465, -1.844}),
      Map.entry("out/cond/LONG",
          new double[] {15.322, 20.26, 13.948, 20.04, -0.445, -2.063, -0.154, -1.414}),
      Map.entry("prefix",
          new double[] {265.47, 276.904, 266.089, 275.565, 28.429, 31.28, 28.445, 31.283}),
      Map.entry("prefix/load",
          new double[] {19.631, -5.57, 22.589, -4.268, -0.675, -2.939, -0.748, -2.95}),
      Map.entry("prefix/month",
          new double[] {16.684, -4.752, 16.507, -2.981, 2.031, -2.068, 1.885, -2.059}));
}
