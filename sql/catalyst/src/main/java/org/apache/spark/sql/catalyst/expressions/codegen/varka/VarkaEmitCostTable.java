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
  static final double BYTES_MARGIN = 0.157;

  /** The same for the call-site budget. */
  static final double SITES_MARGIN = 0.206;

  /** Each feature's price, by feature. */
  static final Map<String, double[]> PRICES = Map.ofEntries(
      Map.entry("AddDays",
          new double[] {10.559, 12.59, 11.177, 12.982, 1.407, 1.323, 1.426, 1.324}),
      Map.entry("AddMonths",
          new double[] {626.339, 706.814, 629.894, 712.882, 80.691, 83.426, 80.765, 83.418}),
      Map.entry("And/INT",
          new double[] {-6.912, 12.898, -6.03, 13.126, -1.895, -0.754, -1.831, -0.771}),
      Map.entry("And/LONG",
          new double[] {9.268, 15.093, 9.636, 15.32, -0.103, -1.236, -0.04, -1.214}),
      Map.entry("BitCount/INT",
          new double[] {23.57, 22.413, 23.308, 22.47, 3.983, 3.837, 3.991, 3.842}),
      Map.entry("BitCount/LONG",
          new double[] {7.259, 4.462, 7.319, 4.505, 1.033, 1.005, 1.012, 1.004}),
      Map.entry("BitOp/AND/INT",
          new double[] {14.537, 2.352, 15.308, 2.242, 1.443, 1.253, 1.535, 1.269}),
      Map.entry("BitOp/AND/LONG",
          new double[] {13.173, 18.444, 13.436, 18.502, 1.301, 1.026, 1.351, 1.02}),
      Map.entry("BitOp/OR/INT",
          new double[] {9.855, 13.846, 9.817, 14.581, 1.215, 1.95, 1.268, 1.956}),
      Map.entry("BitOp/OR/LONG",
          new double[] {10.051, 12.216, 9.957, 12.313, 1.057, 0.926, 1.052, 0.927}),
      Map.entry("BitOp/SHL/INT",
          new double[] {7.133, 6.663, 7.191, 6.771, 0.698, 1.007, 0.653, 1.003}),
      Map.entry("BitOp/SHL/LONG",
          new double[] {11.218, 8.74, 11.431, 8.691, 1.088, 1.137, 1.075, 1.135}),
      Map.entry("BitOp/SHR/INT",
          new double[] {9.443, 16.585, 9.372, 17.179, 1.098, 1.599, 1.055, 1.593}),
      Map.entry("BitOp/SHR/LONG",
          new double[] {11.015, 11.71, 11.004, 11.704, 1.188, 0.753, 1.189, 0.758}),
      Map.entry("BitOp/USHR/INT",
          new double[] {8.208, 0.263, 8.356, 0.202, 0.925, 1.253, 0.947, 1.253}),
      Map.entry("BitOp/USHR/LONG",
          new double[] {10.917, 6.816, 11.101, 6.814, 1.135, 1.023, 1.108, 1.02}),
      Map.entry("BitOp/XOR/INT",
          new double[] {11.246, 3.967, 11.241, 3.709, 1.246, 1.481, 1.239, 1.475}),
      Map.entry("BitOp/XOR/LONG",
          new double[] {10.704, 12.711, 10.828, 12.648, 1.261, 1.063, 1.232, 1.062}),
      Map.entry("BoundedDivide",
          new double[] {14.934, 17.058, 15.093, 17.304, 2.043, 2.304, 2.064, 2.314}),
      Map.entry("ColumnRef/INT",
          new double[] {18.424, 90.399, 20.37, 94.018, 0.778, 0.943, 0.735, 0.943}),
      Map.entry("ColumnRef/LONG",
          new double[] {20.908, 104.087, 23.101, 106.272, 0.836, 0.588, 0.811, 0.586}),
      Map.entry("Compare/EQ/INT",
          new double[] {25.835, 39.103, 26.308, 38.72, 2.221, 1.858, 2.241, 1.902}),
      Map.entry("Compare/EQ/LONG",
          new double[] {15.163, 49.416, 15.883, 49.476, 1.263, 2.229, 1.278, 2.232}),
      Map.entry("Compare/GE/INT",
          new double[] {23.911, 46.504, 24.556, 46.434, 1.978, 2.511, 2.176, 2.596}),
      Map.entry("Compare/GE/LONG",
          new double[] {15.359, 51.455, 15.539, 51.46, 1.262, 2.701, 1.26, 2.716}),
      Map.entry("Compare/GT/INT",
          new double[] {20.164, 53.537, 20.911, 54.29, 1.41, 2.423, 1.537, 2.483}),
      Map.entry("Compare/GT/LONG",
          new double[] {15, 47.267, 15.913, 47.235, 1.206, 2.728, 1.23, 2.718}),
      Map.entry("Compare/LE/INT",
          new double[] {17.958, 51.451, 18.108, 51.733, 1.229, 2.628, 1.265, 2.672}),
      Map.entry("Compare/LE/LONG",
          new double[] {12.741, 52.579, 13.284, 52.636, 1.173, 2.354, 1.232, 2.357}),
      Map.entry("Compare/LT/INT",
          new double[] {24.174, 46.04, 25.072, 46.488, 1.995, 2.22, 2.162, 2.298}),
      Map.entry("Compare/LT/LONG",
          new double[] {15.265, 49.311, 16.049, 49.361, 1.217, 2.493, 1.26, 2.495}),
      Map.entry("ConstDivide/INT/neg",
          new double[] {108.131, 106.92, 108.36, 106.894, 11.989, 11.914, 11.998, 11.914}),
      Map.entry("ConstDivide/INT/pos",
          new double[] {106.396, 98.773, 106.685, 98.606, 11.168, 10.553, 11.188, 10.553}),
      Map.entry("ConstDivide/LONG/neg",
          new double[] {31.564, 22.495, 31.291, 22.387, 2.907, 3.176, 2.877, 3.169}),
      Map.entry("ConstDivide/LONG/pos",
          new double[] {32.179, 21.926, 32.584, 22.027, 2.989, 3.014, 3.053, 3.02}),
      Map.entry("DateDiff",
          new double[] {7.01, 7.22, 7.026, 7.306, 1.151, 1.307, 1.125, 1.297}),
      Map.entry("DayOfMonth",
          new double[] {22.92, 38.829, 22.605, 39.204, 3.916, 5.079, 3.96, 5.065}),
      Map.entry("DayOfWeek",
          new double[] {116.857, 115.946, 116.947, 116.166, 16.203, 15.735, 16.209, 15.741}),
      Map.entry("DayOfWeekIso",
          new double[] {114.729, 116.524, 114.582, 116.59, 15.936, 15.826, 15.897, 15.822}),
      Map.entry("DayOfYear",
          new double[] {99.319, 117.827, 99.457, 120.568, 13.307, 12.493, 13.318, 12.492}),
      Map.entry("Greatest/INT",
          new double[] {6.288, 90.515, 6.391, 91.417, 0.907, 3.492, 0.927, 3.495}),
      Map.entry("Greatest/LONG",
          new double[] {4.986, 79.146, 5.248, 79.183, 0.95, 3.325, 1.022, 3.335}),
      Map.entry("GuardedDay",
          new double[] {44.224, 73.24, 49.173, 78.571, 2.01, 1.948, 2.006, 1.95}),
      Map.entry("GuardedRange/INT",
          new double[] {45.466, 76.164, 50.626, 81.627, 2.145, 2.307, 2.135, 2.304}),
      Map.entry("GuardedRange/LONG",
          new double[] {42.167, 60.639, 47.342, 65.735, 2.021, 2.151, 2.016, 2.156}),
      Map.entry("IfElse/INT",
          new double[] {1.217, 71.286, 0.956, 72.879, 0.489, 0.077, 0.429, 0.015}),
      Map.entry("IfElse/LONG",
          new double[] {7.874, 52.876, 7.913, 52.889, 1.05, 0.259, 1.123, 0.257}),
      Map.entry("InRanges",
          new double[] {78.217, 98.946, 79.073, 98.863, 2.571, 4.17, 2.639, 4.215}),
      Map.entry("IntArith/ADD/FAIL/INT",
          new double[] {68.785, 100.371, 75.115, 106.141, 5.296, 5.602, 5.539, 5.618}),
      Map.entry("IntArith/ADD/FAIL/LONG",
          new double[] {61.106, 93.157, 66.366, 98.256, 4.984, 5.04, 5.059, 5.05}),
      Map.entry("IntArith/ADD/NULL/INT",
          new double[] {0, 110.749, 0, 112.887, 0, 5.553, 0, 5.553}),
      Map.entry("IntArith/ADD/NULL/LONG",
          new double[] {0, 92.84, 0, 92.936, 0, 5.061, 0, 5.068}),
      Map.entry("IntArith/ADD/WRAP/INT",
          new double[] {5.45, -3.826, 5.356, -3.952, 1.006, 1.129, 1.091, 1.136}),
      Map.entry("IntArith/ADD/WRAP/LONG",
          new double[] {5.013, 10.546, 4.965, 10.468, 1.072, 1.103, 1.086, 1.103}),
      Map.entry("IntArith/MUL/WRAP/INT",
          new double[] {6.127, 11.598, 6.653, 12.089, 1.368, 1.757, 1.445, 1.763}),
      Map.entry("IntArith/MUL/WRAP/LONG",
          new double[] {6.242, 7.717, 6.361, 7.707, 1.085, 1.262, 1.107, 1.264}),
      Map.entry("IntArith/SUB/FAIL/INT",
          new double[] {62.528, 98.087, 67.224, 103.321, 5.296, 5.199, 5.255, 5.182}),
      Map.entry("IntArith/SUB/FAIL/LONG",
          new double[] {62.261, 87.972, 67.289, 92.986, 4.993, 5.44, 5.019, 5.439}),
      Map.entry("IntArith/SUB/NULL/INT",
          new double[] {0, 102.727, 0, 104.322, 0, 5.203, 0, 5.217}),
      Map.entry("IntArith/SUB/NULL/LONG",
          new double[] {0, 92.357, 0, 92.36, 0, 5.487, 0, 5.488}),
      Map.entry("IntArith/SUB/WRAP/INT",
          new double[] {5.224, 7.986, 5.303, 8.069, 1.24, 1.207, 1.322, 1.224}),
      Map.entry("IntArith/SUB/WRAP/LONG",
          new double[] {5.405, 7.652, 5.705, 7.658, 1.133, 1.014, 1.18, 1.023}),
      Map.entry("IntNeg/FAIL/INT",
          new double[] {33.435, 57.587, 38.426, 62.681, 2.09, 1.65, 2.093, 1.647}),
      Map.entry("IntNeg/FAIL/LONG",
          new double[] {30.335, 49.272, 35.711, 54.33, 2.023, 1.836, 2.057, 1.841}),
      Map.entry("IntNeg/WRAP/INT",
          new double[] {4.678, 5.108, 4.445, 5.029, 0.929, 0.911, 0.897, 0.906}),
      Map.entry("IntNeg/WRAP/LONG",
          new double[] {4.323, 3.267, 4.454, 3.3, 0.866, 1.147, 0.843, 1.148}),
      Map.entry("IsNotNull/INT",
          new double[] {-21.785, 27.317, -31.747, 24.62, -2.335, 0.6, -2.401, 0.553}),
      Map.entry("IsNotNull/LONG",
          new double[] {5.095, 27.873, 1.026, 26.73, -0.054, 1.128, -0.206, 1.079}),
      Map.entry("IsNotNull/cond/INT",
          new double[] {-54.342, 10.169, -56.852, 10.125, -6.004, -0.144, -6.16, -0.096}),
      Map.entry("IsNotNull/cond/LONG",
          new double[] {-18.038, 13.954, -20.943, 13.795, -2.178, -0.255, -2.355, -0.274}),
      Map.entry("LastDay",
          new double[] {220.573, 259.029, 220.663, 261.953, 31.084, 33.232, 31.081, 33.221}),
      Map.entry("Least/INT",
          new double[] {7.224, 88.532, 7.55, 89.148, 1.187, 3.123, 1.275, 3.134}),
      Map.entry("Least/LONG",
          new double[] {5.764, 80.058, 6.107, 80.13, 1.058, 3.265, 1.134, 3.271}),
      Map.entry("LiteralSlot/INT",
          new double[] {8.554, 8.708, 7.27, 8.044, 0.998, 1.383, 1.049, 1.402}),
      Map.entry("LiteralSlot/LONG",
          new double[] {13.714, 5.3, 13.197, 5.236, 1.317, 2.027, 1.46, 2.073}),
      Map.entry("MakeDate/ansi",
          new double[] {465.807, 532.388, 471.061, 538.791, 52.97, 53.252, 52.964, 53.264}),
      Map.entry("MakeDate/null",
          new double[] {0, 539.923, 0, 546.285, 0, 54.224, 0, 54.241}),
      Map.entry("Month",
          new double[] {19.065, 40.736, 18.72, 41.164, 2.524, 4.469, 2.597, 4.471}),
      Map.entry("NarrowLane",
          new double[] {32.032, 31.331, 29.281, 29.025, 1.101, 2.028, 1.087, 2.023}),
      Map.entry("NextDay",
          new double[] {103.507, 103.321, 103.775, 103.324, 14.957, 15.365, 15.001, 15.373}),
      Map.entry("Not/INT",
          new double[] {14.166, 1.532, 14.272, 1.889, 0.972, 0.097, 1.005, 0.081}),
      Map.entry("Not/LONG",
          new double[] {8.346, 9.93, 8.463, 9.886, 0.117, 0.133, 0.02, 0.109}),
      Map.entry("Or/INT",
          new double[] {11.868, 13.111, 11.674, 13.808, 0.902, -0.629, 0.84, -0.61}),
      Map.entry("Or/LONG",
          new double[] {9.835, 8.734, 10.288, 9.058, -0.015, -1.303, 0.07, -1.245}),
      Map.entry("Quarter",
          new double[] {39.448, 55.859, 38.811, 56.232, 5.872, 7.408, 5.913, 7.399}),
      Map.entry("SubDays",
          new double[] {11.695, 14.665, 12.408, 15.165, 1.552, 1.736, 1.586, 1.743}),
      Map.entry("ThursdayOf",
          new double[] {117.659, 123.89, 117.412, 124.299, 16.536, 16.838, 16.501, 16.835}),
      Map.entry("TruncDate/MONTH",
          new double[] {24.072, 28.436, 23.664, 29.171, 3.951, 5.038, 4.001, 5.027}),
      Map.entry("TruncDate/QUARTER",
          new double[] {214.984, 244.27, 214.009, 246.487, 29.798, 31.39, 29.824, 31.399}),
      Map.entry("TruncDate/YEAR",
          new double[] {107.088, 125.565, 107.418, 128.419, 14.695, 15.485, 14.795, 15.498}),
      Map.entry("TruncDateDynamic",
          new double[] {417.162, 463.189, 416.394, 465.285, 57.896, 61.302, 57.985, 61.286}),
      Map.entry("WeekDay",
          new double[] {110.085, 110.195, 110.212, 110.281, 14.815, 14.885, 14.832, 14.893}),
      Map.entry("WeekOfYear",
          new double[] {126.621, 114.125, 127.039, 116.872, 17.713, 12.926, 17.711, 12.928}),
      Map.entry("WidenLane",
          new double[] {33.759, -10.124, 74.974, 28.792, 1.199, 1.765, 1.268, 1.791}),
      Map.entry("Year",
          new double[] {29.421, 48.746, 29.447, 51.809, 4.14, 3.705, 4.107, 3.701}),
      Map.entry("fixed/INT",
          new double[] {83.264, 48.829, 95.899, 60.666, 0.064, -0.762, 0.135, -0.766}),
      Map.entry("fixed/LONG",
          new double[] {86.514, 51.555, 98.282, 63.981, -0.152, -0.669, -0.059, -0.681}),
      Map.entry("out/INT",
          new double[] {24.288, 21.634, 26.524, 23.942, 1.173, 1.568, 1.131, 1.559}),
      Map.entry("out/LONG",
          new double[] {23.27, 30.181, 25.299, 32.181, 1.174, 0.958, 1.055, 0.945}),
      Map.entry("out/cond/INT",
          new double[] {18.718, 28.51, 18.73, 27.584, -0.369, -1.931, -0.294, -1.7}),
      Map.entry("out/cond/LONG",
          new double[] {18.154, 26.22, 16.171, 25.754, -0.455, -1.741, -0.217, -1.138}),
      Map.entry("prefix",
          new double[] {259.26, 274.252, 259.506, 272.855, 27.963, 31.065, 28.005, 31.073}),
      Map.entry("prefix/load",
          new double[] {40.111, -4.428, 44.145, -3.128, 0.021, -2.657, 0.027, -2.66}),
      Map.entry("prefix/month",
          new double[] {17.19, -7.038, 17.49, -5.528, 2.216, -2.116, 2.127, -2.109}));
}
