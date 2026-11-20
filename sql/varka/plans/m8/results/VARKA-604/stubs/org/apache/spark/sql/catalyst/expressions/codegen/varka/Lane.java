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
// A stub for row 604's admission probe (../../VARKA-604-admission.txt); not Varka code.
package org.apache.spark.sql.catalyst.expressions.codegen.varka;
enum Lane {
  BYTE(8), SHORT(16);
  final int bits;
  Lane(int b) { bits = b; }
  boolean hasSpecies(int lanes) {
    int w = lanes * bits;
    return w >= 64 && w <= 512 && Integer.bitCount(w) == 1;
  }
  static int widenLanes(int intLanes, Lane narrow) {
    if (intLanes <= 0) return 0;
    for (int l = intLanes; l <= 64; l *= 2) if (narrow.hasSpecies(l)) return l;
    return 0;
  }
}
