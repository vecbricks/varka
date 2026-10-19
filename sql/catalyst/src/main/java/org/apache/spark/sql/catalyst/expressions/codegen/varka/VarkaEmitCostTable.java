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
          new double[] {11.411, 13.852, 12.156, 14.624, 1.479, 1.567, 1.524, 1.582}),
      Map.entry("AddMonths",
          new double[] {611.592, 677.673, 614.896, 683.398, 79.853, 82.163, 80.214, 82.225}),
      Map.entry("And/INT",
          new double[] {5.055, 8.384, 4.1, 8.218, -0.464, -0.482, -0.659, -0.53}),
      Map.entry("And/LONG",
          new double[] {9.033, 16.911, 10.24, 17.08, -0.245, -0.921, -0.104, -0.891}),
      Map.entry("BoundedDivide",
          new double[] {13.124, 16.042, 13.195, 16.135, 1.968, 2.23, 1.991, 2.229}),
      Map.entry("ColumnRef/INT",
          new double[] {19.116, 92.042, 21.148, 95.506, 0.803, 0.991, 0.754, 0.982}),
      Map.entry("ColumnRef/LONG",
          new double[] {21.169, 105.769, 23.378, 107.957, 0.985, 0.637, 0.919, 0.633}),
      Map.entry("Compare/EQ/INT",
          new double[] {21.118, 52.277, 24.309, 53.608, 1.727, 1.958, 2.224, 2.078}),
      Map.entry("Compare/EQ/LONG",
          new double[] {16.258, 47.914, 16.805, 47.856, 1.525, 2.794, 1.436, 2.75}),
      Map.entry("Compare/GE/INT",
          new double[] {18.689, 49.324, 21.437, 50.539, 1.615, 1.599, 2.104, 1.754}),
      Map.entry("Compare/GE/LONG",
          new double[] {16.623, 46.207, 16.982, 46.361, 1.361, 2.585, 1.394, 2.606}),
      Map.entry("Compare/GT/INT",
          new double[] {21.245, 38.849, 23.61, 39.081, 1.775, 0.981, 2.106, 1.078}),
      Map.entry("Compare/GT/LONG",
          new double[] {16.582, 50.701, 16.832, 50.782, 1.526, 2.799, 1.429, 2.785}),
      Map.entry("Compare/LE/INT",
          new double[] {24.771, 40.15, 27.586, 40.689, 1.777, 1.513, 2.111, 1.612}),
      Map.entry("Compare/LE/LONG",
          new double[] {17.8, 47.654, 18.705, 47.84, 1.531, 2.295, 1.537, 2.315}),
      Map.entry("Compare/LT/INT",
          new double[] {23.715, 38.913, 27.225, 39.239, 1.619, 1.433, 2.074, 1.587}),
      Map.entry("Compare/LT/LONG",
          new double[] {18.023, 44.324, 18.759, 44.504, 1.469, 2.661, 1.476, 2.684}),
      Map.entry("ConstDivide/INT/neg",
          new double[] {106.142, 108.921, 106.559, 108.902, 11.733, 12.135, 11.838, 12.15}),
      Map.entry("ConstDivide/INT/pos",
          new double[] {104.78, 103.183, 104.98, 103.107, 11.015, 10.907, 11.06, 10.913}),
      Map.entry("ConstDivide/LONG/neg",
          new double[] {32.276, 26.61, 32.167, 26.605, 2.891, 2.92, 2.835, 2.904}),
      Map.entry("ConstDivide/LONG/pos",
          new double[] {31.937, 19.882, 32.305, 20.001, 2.952, 2.827, 2.993, 2.833}),
      Map.entry("DateDiff",
          new double[] {4.988, 4.209, 5.029, 4.432, 1.174, 1.196, 1.193, 1.199}),
      Map.entry("DayOfMonth",
          new double[] {20.29, 38.715, 20.397, 39.172, 3.7, 5.126, 3.822, 5.12}),
      Map.entry("DayOfWeek",
          new double[] {114.43, 117.294, 114.368, 117.316, 15.968, 16.023, 15.992, 16.024}),
      Map.entry("DayOfWeekIso",
          new double[] {116.761, 113.149, 117.121, 113.05, 16.01, 15.586, 15.988, 15.586}),
      Map.entry("DayOfYear",
          new double[] {92.021, 114.413, 91.357, 117.124, 12.664, 12.716, 12.633, 12.722}),
      Map.entry("Greatest/INT",
          new double[] {6.662, 69.577, 7.01, 70.085, 1.111, 3.412, 1.153, 3.412}),
      Map.entry("Greatest/LONG",
          new double[] {6.265, 61.504, 6.73, 61.53, 1.12, 3.234, 1.185, 3.24}),
      Map.entry("GuardedDay",
          new double[] {43.305, 67.196, 48.515, 72.614, 2.06, 2.192, 2.093, 2.194}),
      Map.entry("GuardedRange/INT",
          new double[] {45.348, 69.625, 50.685, 75.065, 2.012, 2.422, 2.017, 2.42}),
      Map.entry("GuardedRange/LONG",
          new double[] {41.727, 51.623, 47.129, 56.676, 2.033, 2.189, 2.055, 2.192}),
      Map.entry("IfElse/INT",
          new double[] {3.807, 66.438, 2.181, 67.957, 0.765, 0.774, 0.487, 0.67}),
      Map.entry("IfElse/LONG",
          new double[] {5.102, 43.912, 5.055, 43.902, 0.779, -0.014, 0.887, 0.002}),
      Map.entry("InRanges",
          new double[] {92.035, 100.248, 96.377, 101.227, 3.807, 4.666, 4.317, 4.811}),
      Map.entry("IntArith/ADD/FAIL/INT",
          new double[] {69.451, 83.042, 75.255, 88.187, 5.171, 4.774, 5.395, 4.831}),
      Map.entry("IntArith/ADD/FAIL/LONG",
          new double[] {62.175, 80.947, 67.62, 86.031, 5.052, 5.25, 5.07, 5.251}),
      Map.entry("IntArith/ADD/NULL/INT",
          new double[] {0, 106.72, 0, 108.916, 0, 5.236, 0, 5.267}),
      Map.entry("IntArith/ADD/NULL/LONG",
          new double[] {0, 90.12, 0, 90.23, 0, 5.249, 0, 5.261}),
      Map.entry("IntArith/ADD/WRAP/INT",
          new double[] {8.593, -12.817, 8.954, -14.039, 1.42, 0.56, 1.441, 0.547}),
      Map.entry("IntArith/ADD/WRAP/LONG",
          new double[] {6.17, 10.573, 6.176, 10.554, 1.069, 0.983, 1.074, 0.982}),
      Map.entry("IntArith/MUL/WRAP/INT",
          new double[] {7.712, 10.762, 7.976, 11.218, 1.494, 1.778, 1.536, 1.788}),
      Map.entry("IntArith/MUL/WRAP/LONG",
          new double[] {5.111, 8.3, 5.479, 8.302, 1.021, 1.174, 1.099, 1.177}),
      Map.entry("IntArith/SUB/FAIL/INT",
          new double[] {70.193, 81.348, 76.465, 86.431, 5.35, 4.627, 5.565, 4.652}),
      Map.entry("IntArith/SUB/FAIL/LONG",
          new double[] {60.65, 81.142, 66.683, 86.273, 5.119, 5.24, 5.295, 5.259}),
      Map.entry("IntArith/SUB/NULL/INT",
          new double[] {0, 103.983, 0, 105.886, 0, 5.589, 0, 5.588}),
      Map.entry("IntArith/SUB/NULL/LONG",
          new double[] {0, 88.259, 0, 88.44, 0, 5.405, 0, 5.424}),
      Map.entry("IntArith/SUB/WRAP/INT",
          new double[] {4.508, -5.646, 3.229, -5.933, 0.973, 0.622, 0.967, 0.636}),
      Map.entry("IntArith/SUB/WRAP/LONG",
          new double[] {5.885, 12.161, 5.85, 12.207, 1.034, 0.872, 1.11, 0.873}),
      Map.entry("IntNeg/FAIL/INT",
          new double[] {31.827, 52.91, 36.73, 58.267, 1.82, 1.901, 1.812, 1.901}),
      Map.entry("IntNeg/FAIL/LONG",
          new double[] {29.639, 37.949, 34.071, 42.942, 1.99, 2.102, 1.868, 2.095}),
      Map.entry("IntNeg/WRAP/INT",
          new double[] {2.136, -0.733, 2.086, -1.057, 0.872, 1.031, 0.888, 1.038}),
      Map.entry("IntNeg/WRAP/LONG",
          new double[] {7.121, 2.988, 7.673, 3.086, 1.092, 0.975, 1.066, 0.982}),
      Map.entry("IsNotNull/INT",
          new double[] {5.79, 17.082, 1.282, 15.217, 0.156, 0.45, 0.353, 0.45}),
      Map.entry("IsNotNull/LONG",
          new double[] {7.233, 14.007, 2.881, 12.976, 0.137, 1.219, -0.04, 1.179}),
      Map.entry("LastDay",
          new double[] {218.539, 264.53, 219.774, 268.186, 31.003, 33.412, 31.126, 33.41}),
      Map.entry("Least/INT",
          new double[] {6.361, 72.888, 6.489, 73.54, 1.141, 3.573, 1.124, 3.567}),
      Map.entry("Least/LONG",
          new double[] {5.287, 61.608, 5.083, 61.642, 1.05, 3.173, 1.043, 3.172}),
      Map.entry("LiteralSlot/INT",
          new double[] {7.634, 9.949, 6.447, 9.068, 0.99, 1.354, 1.062, 1.377}),
      Map.entry("LiteralSlot/LONG",
          new double[] {14.1, 7.15, 13.495, 7.155, 1.319, 2.014, 1.466, 2.07}),
      Map.entry("MakeDate/ansi",
          new double[] {475.516, 525.689, 481.29, 531.806, 53.613, 53.548, 53.498, 53.545}),
      Map.entry("MakeDate/null",
          new double[] {0, 527.934, 0, 533.921, 0, 54.313, 0, 54.327}),
      Map.entry("Month",
          new double[] {19.654, 37.376, 19.767, 37.628, 2.757, 4.325, 2.9, 4.322}),
      Map.entry("NarrowLane",
          new double[] {36.439, 34.147, 30.579, 31.401, 1.436, 2.657, 1.443, 2.644}),
      Map.entry("NextDay",
          new double[] {106.131, 104.625, 106.361, 104.72, 15.17, 15.339, 15.178, 15.338}),
      Map.entry("Not/INT",
          new double[] {8.037, 4.432, 8.837, 5.194, -0.021, 0.486, 0.016, 0.538}),
      Map.entry("Not/LONG",
          new double[] {8.117, -5.048, 8.287, -5.032, 0.122, -0.154, -0.039, -0.19}),
      Map.entry("Or/INT",
          new double[] {-2.813, 8.296, -4.155, 7.614, -0.759, -0.145, -0.782, -0.173}),
      Map.entry("Or/LONG",
          new double[] {5.981, 19.166, 6.912, 19.311, -0.383, -1.242, -0.131, -1.213}),
      Map.entry("Quarter",
          new double[] {35.058, 58.572, 34.897, 59.186, 5.648, 7.147, 5.738, 7.136}),
      Map.entry("SubDays",
          new double[] {10.457, 17.134, 11.125, 18.017, 1.459, 1.905, 1.464, 1.909}),
      Map.entry("ThursdayOf",
          new double[] {121.163, 118.797, 121.638, 119.028, 16.97, 16.641, 17.048, 16.655}),
      Map.entry("TruncDate/MONTH",
          new double[] {16.488, 50.039, 15.787, 51.171, 3.517, 5.658, 3.522, 5.661}),
      Map.entry("TruncDate/QUARTER",
          new double[] {211.991, 259.516, 211.576, 262.261, 29.806, 31.613, 29.906, 31.647}),
      Map.entry("TruncDate/YEAR",
          new double[] {112.236, 126.905, 112.043, 129.527, 14.94, 14.638, 14.918, 14.641}),
      Map.entry("TruncDateDynamic",
          new double[] {414.13, 459.243, 413.749, 461.128, 57.918, 61.743, 58.027, 61.744}),
      Map.entry("WeekDay",
          new double[] {110.332, 111.356, 110.001, 111.437, 15.025, 14.887, 14.965, 14.878}),
      Map.entry("WeekOfYear",
          new double[] {112.269, 111.989, 111, 114.938, 16.533, 12.661, 16.431, 12.665}),
      Map.entry("WidenLane",
          new double[] {32.514, -16.115, 73.886, 23.036, 1.462, 1.685, 1.487, 1.718}),
      Map.entry("Year",
          new double[] {22.835, 45.519, 22.146, 48.623, 3.689, 3.486, 3.659, 3.493}),
      Map.entry("fixed/INT",
          new double[] {81.159, 51.364, 93.583, 63.492, -0.119, -0.842, -0.028, -0.835}),
      Map.entry("fixed/LONG",
          new double[] {87.545, 52.974, 98.936, 65.37, -0.466, -0.805, -0.328, -0.808}),
      Map.entry("out/INT",
          new double[] {24.663, 21.298, 26.741, 23.631, 1.196, 1.544, 1.127, 1.532}),
      Map.entry("out/LONG",
          new double[] {22.793, 33.114, 24.956, 35.065, 1.198, 1.128, 1.086, 1.106}),
      Map.entry("out/cond/INT",
          new double[] {12.991, 15.925, 9.595, 13.826, -0.715, -1.719, -0.964, -1.465}),
      Map.entry("out/cond/LONG",
          new double[] {14.159, 24.611, 12, 22.939, -0.636, -1.89, -0.334, -1.305}),
      Map.entry("prefix",
          new double[] {265.794, 274.476, 266.664, 272.88, 28.432, 31.142, 28.463, 31.142}),
      Map.entry("prefix/load",
          new double[] {16.583, -6.52, 19.463, -5.331, -1.052, -3.034, -1.116, -3.049}),
      Map.entry("prefix/month",
          new double[] {16.113, -1.474, 15.549, 0.531, 1.998, -1.852, 1.837, -1.843}));
}
