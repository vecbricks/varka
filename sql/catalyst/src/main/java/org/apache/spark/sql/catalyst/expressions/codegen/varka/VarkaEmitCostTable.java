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
  static final double BYTES_MARGIN = 0.155;

  /** The same for the call-site budget. */
  static final double SITES_MARGIN = 0.206;

  /** Each feature's price, by feature. */
  static final Map<String, double[]> PRICES = Map.ofEntries(
      Map.entry("AddDays",
          new double[] {10.559, 11.482, 11.177, 11.874, 1.407, 1.323, 1.426, 1.324}),
      Map.entry("AddMonths",
          new double[] {626.339, 701.199, 629.894, 707.267, 80.691, 83.426, 80.765, 83.418}),
      Map.entry("And/INT",
          new double[] {-6.912, 13.138, -6.03, 13.366, -1.895, -0.754, -1.831, -0.771}),
      Map.entry("And/LONG",
          new double[] {9.446, 14.115, 9.814, 14.341, -0.103, -1.236, -0.04, -1.214}),
      Map.entry("BitCount/INT",
          new double[] {23.57, 22.345, 23.308, 22.402, 3.983, 3.837, 3.991, 3.842}),
      Map.entry("BitCount/LONG",
          new double[] {7.124, 4.345, 7.183, 4.388, 1.033, 1.005, 1.012, 1.004}),
      Map.entry("BitOp/AND/INT",
          new double[] {14.537, 2.425, 15.308, 2.315, 1.443, 1.253, 1.535, 1.269}),
      Map.entry("BitOp/AND/LONG",
          new double[] {13.246, 17.994, 13.51, 18.051, 1.301, 1.026, 1.351, 1.02}),
      Map.entry("BitOp/OR/INT",
          new double[] {9.855, 14.079, 9.817, 14.814, 1.215, 1.95, 1.268, 1.956}),
      Map.entry("BitOp/OR/LONG",
          new double[] {10.118, 12.161, 10.024, 12.259, 1.057, 0.926, 1.052, 0.927}),
      Map.entry("BitOp/SHL/INT",
          new double[] {7.133, 7.173, 7.191, 7.281, 0.698, 1.007, 0.653, 1.003}),
      Map.entry("BitOp/SHL/LONG",
          new double[] {11.259, 8.805, 11.473, 8.757, 1.088, 1.137, 1.075, 1.135}),
      Map.entry("BitOp/SHR/INT",
          new double[] {9.443, 17.038, 9.372, 17.632, 1.098, 1.599, 1.055, 1.593}),
      Map.entry("BitOp/SHR/LONG",
          new double[] {11.065, 11.234, 11.054, 11.228, 1.188, 0.753, 1.189, 0.758}),
      Map.entry("BitOp/USHR/INT",
          new double[] {8.208, 0.568, 8.356, 0.507, 0.925, 1.253, 0.947, 1.253}),
      Map.entry("BitOp/USHR/LONG",
          new double[] {11.002, 6.735, 11.186, 6.733, 1.135, 1.023, 1.108, 1.02}),
      Map.entry("BitOp/XOR/INT",
          new double[] {11.246, 4.008, 11.241, 3.75, 1.246, 1.481, 1.239, 1.475}),
      Map.entry("BitOp/XOR/LONG",
          new double[] {10.79, 12.692, 10.914, 12.629, 1.261, 1.063, 1.232, 1.062}),
      Map.entry("BoundedDivide",
          new double[] {14.934, 17.158, 15.093, 17.404, 2.043, 2.304, 2.064, 2.314}),
      Map.entry("ColumnRef/INT",
          new double[] {18.424, 89.954, 20.37, 93.572, 0.778, 0.943, 0.735, 0.943}),
      Map.entry("ColumnRef/LONG",
          new double[] {20.976, 103.68, 23.17, 105.864, 0.836, 0.588, 0.811, 0.586}),
      Map.entry("Compare/EQ/INT",
          new double[] {25.835, 38.306, 26.308, 37.923, 2.221, 1.858, 2.241, 1.902}),
      Map.entry("Compare/EQ/LONG",
          new double[] {15.086, 50.144, 15.806, 50.205, 1.263, 2.229, 1.278, 2.232}),
      Map.entry("Compare/GE/INT",
          new double[] {23.911, 45.838, 24.556, 45.767, 1.978, 2.511, 2.176, 2.596}),
      Map.entry("Compare/GE/LONG",
          new double[] {15.074, 51.791, 15.254, 51.796, 1.262, 2.701, 1.26, 2.716}),
      Map.entry("Compare/GT/INT",
          new double[] {20.164, 51.732, 20.911, 52.485, 1.41, 2.423, 1.537, 2.483}),
      Map.entry("Compare/GT/LONG",
          new double[] {14.735, 47.558, 15.648, 47.527, 1.206, 2.728, 1.23, 2.718}),
      Map.entry("Compare/LE/INT",
          new double[] {17.958, 50.047, 18.108, 50.329, 1.229, 2.628, 1.265, 2.672}),
      Map.entry("Compare/LE/LONG",
          new double[] {12.572, 53.403, 13.116, 53.46, 1.173, 2.354, 1.232, 2.357}),
      Map.entry("Compare/LT/INT",
          new double[] {24.174, 45.554, 25.072, 46.002, 1.995, 2.22, 2.162, 2.298}),
      Map.entry("Compare/LT/LONG",
          new double[] {15.096, 49.95, 15.88, 50.001, 1.217, 2.493, 1.26, 2.495}),
      Map.entry("ConstDivide/INT/neg",
          new double[] {108.131, 107.139, 108.36, 107.112, 11.989, 11.914, 11.998, 11.914}),
      Map.entry("ConstDivide/INT/pos",
          new double[] {106.396, 98.48, 106.685, 98.313, 11.168, 10.553, 11.188, 10.553}),
      Map.entry("ConstDivide/LONG/neg",
          new double[] {31.611, 22.489, 31.337, 22.382, 2.907, 3.176, 2.877, 3.169}),
      Map.entry("ConstDivide/LONG/pos",
          new double[] {32.192, 21.793, 32.597, 21.894, 2.989, 3.014, 3.053, 3.02}),
      Map.entry("DateDiff",
          new double[] {7.01, 7.443, 7.026, 7.529, 1.151, 1.307, 1.125, 1.297}),
      Map.entry("DayOfMonth",
          new double[] {22.92, 38.42, 22.605, 38.795, 3.916, 5.079, 3.96, 5.065}),
      Map.entry("DayOfWeek",
          new double[] {116.857, 115.968, 116.947, 116.188, 16.203, 15.735, 16.209, 15.741}),
      Map.entry("DayOfWeekIso",
          new double[] {114.729, 116.44, 114.582, 116.506, 15.936, 15.826, 15.897, 15.822}),
      Map.entry("DayOfYear",
          new double[] {99.319, 117.018, 99.457, 119.759, 13.307, 12.493, 13.318, 12.492}),
      Map.entry("Greatest/INT",
          new double[] {6.288, 74.398, 6.391, 75.3, 0.907, 3.492, 0.927, 3.495}),
      Map.entry("Greatest/LONG",
          new double[] {4.978, 63.227, 5.24, 63.264, 0.95, 3.325, 1.022, 3.335}),
      Map.entry("GuardedDay",
          new double[] {44.224, 66.13, 49.173, 71.461, 2.01, 1.948, 2.006, 1.95}),
      Map.entry("GuardedRange/INT",
          new double[] {45.466, 68.854, 50.626, 74.316, 2.145, 2.307, 2.135, 2.304}),
      Map.entry("GuardedRange/LONG",
          new double[] {42.169, 52.998, 47.344, 58.094, 2.021, 2.151, 2.016, 2.156}),
      Map.entry("IfElse/INT",
          new double[] {1.217, 63.318, 0.956, 64.912, 0.489, 0.077, 0.429, 0.015}),
      Map.entry("IfElse/LONG",
          new double[] {7.931, 44.135, 7.97, 44.148, 1.05, 0.259, 1.123, 0.257}),
      Map.entry("InRanges",
          new double[] {78.217, 97.627, 79.073, 97.544, 2.571, 4.17, 2.639, 4.215}),
      Map.entry("IntArith/ADD/FAIL/INT",
          new double[] {68.785, 92.895, 75.115, 98.665, 5.296, 5.602, 5.539, 5.618}),
      Map.entry("IntArith/ADD/FAIL/LONG",
          new double[] {61.17, 84.458, 66.43, 89.557, 4.984, 5.04, 5.059, 5.05}),
      Map.entry("IntArith/ADD/NULL/INT",
          new double[] {0, 109.891, 0, 112.029, 0, 5.553, 0, 5.553}),
      Map.entry("IntArith/ADD/NULL/LONG",
          new double[] {0, 93.194, 0, 93.29, 0, 5.061, 0, 5.068}),
      Map.entry("IntArith/ADD/WRAP/INT",
          new double[] {5.45, -3.823, 5.356, -3.95, 1.006, 1.129, 1.091, 1.136}),
      Map.entry("IntArith/ADD/WRAP/LONG",
          new double[] {4.975, 10.432, 4.927, 10.354, 1.072, 1.103, 1.086, 1.103}),
      Map.entry("IntArith/MUL/WRAP/INT",
          new double[] {6.127, 11.008, 6.653, 11.499, 1.368, 1.757, 1.445, 1.763}),
      Map.entry("IntArith/MUL/WRAP/LONG",
          new double[] {6.277, 7.951, 6.396, 7.94, 1.085, 1.262, 1.107, 1.264}),
      Map.entry("IntArith/SUB/FAIL/INT",
          new double[] {62.528, 89.973, 67.224, 95.207, 5.296, 5.199, 5.255, 5.182}),
      Map.entry("IntArith/SUB/FAIL/LONG",
          new double[] {62.325, 79.383, 67.352, 84.398, 4.993, 5.44, 5.019, 5.439}),
      Map.entry("IntArith/SUB/NULL/INT",
          new double[] {0, 101.264, 0, 102.859, 0, 5.203, 0, 5.217}),
      Map.entry("IntArith/SUB/NULL/LONG",
          new double[] {0, 92.753, 0, 92.756, 0, 5.487, 0, 5.488}),
      Map.entry("IntArith/SUB/WRAP/INT",
          new double[] {5.224, 6.848, 5.303, 6.931, 1.24, 1.207, 1.322, 1.224}),
      Map.entry("IntArith/SUB/WRAP/LONG",
          new double[] {5.375, 7.559, 5.675, 7.566, 1.133, 1.014, 1.18, 1.023}),
      Map.entry("IntNeg/FAIL/INT",
          new double[] {31.435, 48.327, 36.426, 53.421, 2.09, 1.65, 2.093, 1.647}),
      Map.entry("IntNeg/FAIL/LONG",
          new double[] {30.262, 41.702, 35.638, 46.761, 2.023, 1.836, 2.057, 1.841}),
      Map.entry("IntNeg/WRAP/INT",
          new double[] {2.678, 2.912, 2.445, 2.833, 0.929, 0.911, 0.897, 0.906}),
      Map.entry("IntNeg/WRAP/LONG",
          new double[] {4.337, 3.306, 4.468, 3.338, 0.866, 1.147, 0.843, 1.148}),
      Map.entry("IsNotNull/INT",
          new double[] {-21.785, 25.106, -31.747, 22.408, -2.335, 0.6, -2.401, 0.553}),
      Map.entry("IsNotNull/LONG",
          new double[] {4.963, 26.087, 0.893, 24.944, -0.054, 1.128, -0.206, 1.079}),
      Map.entry("IsNotNull/cond/INT",
          new double[] {-54.342, 8.44, -56.852, 8.396, -6.004, -0.144, -6.16, -0.096}),
      Map.entry("IsNotNull/cond/LONG",
          new double[] {-17.911, 13.042, -20.816, 12.884, -2.178, -0.255, -2.355, -0.274}),
      Map.entry("LastDay",
          new double[] {220.573, 258.51, 220.663, 261.434, 31.084, 33.232, 31.081, 33.221}),
      Map.entry("Least/INT",
          new double[] {7.224, 72.387, 7.55, 73.003, 1.187, 3.123, 1.275, 3.134}),
      Map.entry("Least/LONG",
          new double[] {5.765, 64.184, 6.109, 64.256, 1.058, 3.265, 1.134, 3.271}),
      Map.entry("LiteralSlot/INT",
          new double[] {8.554, 10.703, 7.27, 10.039, 0.998, 1.383, 1.049, 1.402}),
      Map.entry("LiteralSlot/LONG",
          new double[] {13.715, 6.529, 13.198, 6.465, 1.317, 2.027, 1.46, 2.073}),
      Map.entry("MakeDate/ansi",
          new double[] {465.807, 524.512, 471.061, 530.915, 52.97, 53.252, 52.964, 53.264}),
      Map.entry("MakeDate/null",
          new double[] {0, 532.112, 0, 538.474, 0, 54.224, 0, 54.241}),
      Map.entry("Month",
          new double[] {19.065, 40.243, 18.72, 40.671, 2.524, 4.469, 2.597, 4.471}),
      Map.entry("NarrowLane",
          new double[] {32.16, 32.403, 29.409, 30.097, 1.101, 2.028, 1.087, 2.023}),
      Map.entry("NextDay",
          new double[] {103.507, 103.271, 103.775, 103.274, 14.957, 15.365, 15.001, 15.373}),
      Map.entry("Not/INT",
          new double[] {14.166, 1.089, 14.272, 1.446, 0.972, 0.097, 1.005, 0.081}),
      Map.entry("Not/LONG",
          new double[] {8.386, 8.324, 8.503, 8.281, 0.117, 0.133, 0.02, 0.109}),
      Map.entry("Or/INT",
          new double[] {11.868, 14.193, 11.674, 14.89, 0.902, -0.629, 0.84, -0.61}),
      Map.entry("Or/LONG",
          new double[] {9.932, 7.131, 10.384, 7.454, -0.015, -1.303, 0.07, -1.245}),
      Map.entry("Quarter",
          new double[] {39.448, 55.553, 38.811, 55.926, 5.872, 7.408, 5.913, 7.399}),
      Map.entry("SubDays",
          new double[] {11.695, 13.404, 12.408, 13.904, 1.552, 1.736, 1.586, 1.743}),
      Map.entry("ThursdayOf",
          new double[] {117.659, 123.674, 117.412, 124.083, 16.536, 16.838, 16.501, 16.835}),
      Map.entry("TruncDate/MONTH",
          new double[] {24.072, 28.222, 23.664, 28.957, 3.951, 5.038, 4.001, 5.027}),
      Map.entry("TruncDate/QUARTER",
          new double[] {214.984, 243.585, 214.009, 245.802, 29.798, 31.39, 29.824, 31.399}),
      Map.entry("TruncDate/YEAR",
          new double[] {107.088, 126.198, 107.418, 129.053, 14.695, 15.485, 14.795, 15.498}),
      Map.entry("TruncDateDynamic",
          new double[] {417.162, 462.686, 416.394, 464.783, 57.896, 61.302, 57.985, 61.286}),
      Map.entry("WeekDay",
          new double[] {110.085, 110.238, 110.212, 110.323, 14.815, 14.885, 14.832, 14.893}),
      Map.entry("WeekOfYear",
          new double[] {126.621, 113.445, 127.039, 116.192, 17.713, 12.926, 17.711, 12.928}),
      Map.entry("WidenLane",
          new double[] {33.814, -9.713, 75.03, 29.203, 1.199, 1.765, 1.268, 1.791}),
      Map.entry("Year",
          new double[] {29.421, 47.969, 29.447, 51.032, 4.14, 3.705, 4.107, 3.701}),
      Map.entry("fixed/INT",
          new double[] {83.264, 48.706, 95.899, 60.543, 0.064, -0.762, 0.135, -0.766}),
      Map.entry("fixed/LONG",
          new double[] {86.482, 51.367, 98.25, 63.794, -0.152, -0.669, -0.059, -0.681}),
      Map.entry("out/INT",
          new double[] {24.288, 21.907, 26.524, 24.215, 1.173, 1.568, 1.131, 1.559}),
      Map.entry("out/LONG",
          new double[] {23.246, 30.402, 25.275, 32.402, 1.174, 0.958, 1.055, 0.945}),
      Map.entry("out/cond/INT",
          new double[] {18.718, 30.484, 18.73, 29.558, -0.369, -1.931, -0.294, -1.7}),
      Map.entry("out/cond/LONG",
          new double[] {18.281, 27.334, 16.298, 26.869, -0.455, -1.741, -0.217, -1.138}),
      Map.entry("prefix",
          new double[] {259.26, 274.722, 259.506, 273.325, 27.963, 31.065, 28.005, 31.073}),
      Map.entry("prefix/load",
          new double[] {40.111, -3.456, 44.145, -2.156, 0.021, -2.657, 0.027, -2.66}),
      Map.entry("prefix/month",
          new double[] {17.19, -7.292, 17.49, -5.782, 2.216, -2.116, 2.127, -2.109}));
}
