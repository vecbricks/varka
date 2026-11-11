; Licensed to the Apache Software Foundation (ASF) under one or more
; contributor license agreements.  See the NOTICE file distributed with
; this work for additional information regarding copyright ownership.
; The ASF licenses this file to You under the Apache License, Version 2.0
; (the "License"); you may not use this file except in compliance with
; the License.  You may obtain a copy of the License at
;
;    http://www.apache.org/licenses/LICENSE-2.0
;
; Unless required by applicable law or agreed to in writing, software
; distributed under the License is distributed on an "AS IS" BASIS,
; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
; See the License for the specific language governing permissions and
; limitations under the License.

; The interval products by an integral literal (VARKA-103 2.2). Spark computes dt * k as
; Math.multiplyExact(micros, k) and ym * k as Math.multiplyExact(months, k), or for a
; bigint k toIntExact(multiplyExact(months, k)), in every mode, and VarkaIntervalCompiler
; emits, per lane,
;
;   GuardedRange(x, lo, hi)        the batch declines where a lane leaves [lo, hi]
;   x * k                          wrapping multiply, by (int) k at the int lane
;
; with [lo, hi] = [ceil(MIN / k), floor(MAX / k)] for k > 0, the two swapped for k < 0,
; clamped to the lane (dayTimeProductBounds, yearMonthProductBounds). For each factor in
; VarkaProofFiles.DT_FACTORS at the long lane and YM_FACTORS at the int lane the solver
; shows that the guard admits x exactly when the true product is in the lane, and that
; under the guard the wrapping product is the true one; and, beside them, that a bound
; moved one further out admits a product past the lane and that the symmetric bound
; +-(MAX / |k|) refuses one inside it.
;
; Rendered by VarkaProofFiles. VarkaProofFilesSuite fails when this file differs from its
; rendering, and VARKA_PROOFS_REGEN=true build/sbt 'catalyst/testOnly *VarkaProofFilesSuite'
; rewrites it. Run by dev/varka_prove.sh, which inserts java.smt2 after the set-logic line.

(set-logic QF_NIA)

(declare-const x Int)
(push 1)
(assert (jlong.in x))

(echo "jlong k = 2: the guard [-4611686018427387904, 4611686018427387903] admits x exactly where x * k is in the lane: expect unsat")
(push 1)
(assert (not (= (and (<= (- 4611686018427387904) x) (<= x 4611686018427387903)) (jlong.in (* x 2)))))
(check-sat)
(pop 1)

(echo "jlong k = 2: under the guard the wrapping product is the true one: expect unsat")
(push 1)
(assert (and (<= (- 4611686018427387904) x) (<= x 4611686018427387903)))
(assert (not (= (jlong.mul x 2) (* x 2))))
(check-sat)
(pop 1)

(echo "jlong k = 2: with the lower bound one lower it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 4611686018427387905) x) (<= x 4611686018427387903)))
(assert (not (jlong.in (* x 2))))
(check-sat)
(pop 1)

(echo "jlong k = 2: with the upper bound one higher it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 4611686018427387904) x) (<= x 4611686018427387904)))
(assert (not (jlong.in (* x 2))))
(check-sat)
(pop 1)

(echo "jlong k = 2: the symmetric bound +-4611686018427387903 refuses a product inside the lane: expect sat")
(push 1)
(assert (not (and (<= (- 4611686018427387903) x) (<= x 4611686018427387903))))
(assert (jlong.in (* x 2)))
(check-sat)
(pop 1)

(echo "jlong k = -2: the guard [-4611686018427387903, 4611686018427387904] admits x exactly where x * k is in the lane: expect unsat")
(push 1)
(assert (not (= (and (<= (- 4611686018427387903) x) (<= x 4611686018427387904)) (jlong.in (* x (- 2))))))
(check-sat)
(pop 1)

(echo "jlong k = -2: under the guard the wrapping product is the true one: expect unsat")
(push 1)
(assert (and (<= (- 4611686018427387903) x) (<= x 4611686018427387904)))
(assert (not (= (jlong.mul x (- 2)) (* x (- 2)))))
(check-sat)
(pop 1)

(echo "jlong k = -2: with the lower bound one lower it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 4611686018427387904) x) (<= x 4611686018427387904)))
(assert (not (jlong.in (* x (- 2)))))
(check-sat)
(pop 1)

(echo "jlong k = -2: with the upper bound one higher it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 4611686018427387903) x) (<= x 4611686018427387905)))
(assert (not (jlong.in (* x (- 2)))))
(check-sat)
(pop 1)

(echo "jlong k = -2: the symmetric bound +-4611686018427387903 refuses a product inside the lane: expect sat")
(push 1)
(assert (not (and (<= (- 4611686018427387903) x) (<= x 4611686018427387903))))
(assert (jlong.in (* x (- 2))))
(check-sat)
(pop 1)

(echo "jlong k = 3: the guard [-3074457345618258602, 3074457345618258602] admits x exactly where x * k is in the lane: expect unsat")
(push 1)
(assert (not (= (and (<= (- 3074457345618258602) x) (<= x 3074457345618258602)) (jlong.in (* x 3)))))
(check-sat)
(pop 1)

(echo "jlong k = 3: under the guard the wrapping product is the true one: expect unsat")
(push 1)
(assert (and (<= (- 3074457345618258602) x) (<= x 3074457345618258602)))
(assert (not (= (jlong.mul x 3) (* x 3))))
(check-sat)
(pop 1)

(echo "jlong k = 3: with the lower bound one lower it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 3074457345618258603) x) (<= x 3074457345618258602)))
(assert (not (jlong.in (* x 3))))
(check-sat)
(pop 1)

(echo "jlong k = 3: with the upper bound one higher it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 3074457345618258602) x) (<= x 3074457345618258603)))
(assert (not (jlong.in (* x 3))))
(check-sat)
(pop 1)

(echo "jlong k = -3: the guard [-3074457345618258602, 3074457345618258602] admits x exactly where x * k is in the lane: expect unsat")
(push 1)
(assert (not (= (and (<= (- 3074457345618258602) x) (<= x 3074457345618258602)) (jlong.in (* x (- 3))))))
(check-sat)
(pop 1)

(echo "jlong k = -3: under the guard the wrapping product is the true one: expect unsat")
(push 1)
(assert (and (<= (- 3074457345618258602) x) (<= x 3074457345618258602)))
(assert (not (= (jlong.mul x (- 3)) (* x (- 3)))))
(check-sat)
(pop 1)

(echo "jlong k = -3: with the lower bound one lower it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 3074457345618258603) x) (<= x 3074457345618258602)))
(assert (not (jlong.in (* x (- 3)))))
(check-sat)
(pop 1)

(echo "jlong k = -3: with the upper bound one higher it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 3074457345618258602) x) (<= x 3074457345618258603)))
(assert (not (jlong.in (* x (- 3)))))
(check-sat)
(pop 1)

(echo "jlong k = 7: the guard [-1317624576693539401, 1317624576693539401] admits x exactly where x * k is in the lane: expect unsat")
(push 1)
(assert (not (= (and (<= (- 1317624576693539401) x) (<= x 1317624576693539401)) (jlong.in (* x 7)))))
(check-sat)
(pop 1)

(echo "jlong k = 7: under the guard the wrapping product is the true one: expect unsat")
(push 1)
(assert (and (<= (- 1317624576693539401) x) (<= x 1317624576693539401)))
(assert (not (= (jlong.mul x 7) (* x 7))))
(check-sat)
(pop 1)

(echo "jlong k = 7: with the lower bound one lower it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 1317624576693539402) x) (<= x 1317624576693539401)))
(assert (not (jlong.in (* x 7))))
(check-sat)
(pop 1)

(echo "jlong k = 7: with the upper bound one higher it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 1317624576693539401) x) (<= x 1317624576693539402)))
(assert (not (jlong.in (* x 7))))
(check-sat)
(pop 1)

(echo "jlong k = 24: the guard [-384307168202282325, 384307168202282325] admits x exactly where x * k is in the lane: expect unsat")
(push 1)
(assert (not (= (and (<= (- 384307168202282325) x) (<= x 384307168202282325)) (jlong.in (* x 24)))))
(check-sat)
(pop 1)

(echo "jlong k = 24: under the guard the wrapping product is the true one: expect unsat")
(push 1)
(assert (and (<= (- 384307168202282325) x) (<= x 384307168202282325)))
(assert (not (= (jlong.mul x 24) (* x 24))))
(check-sat)
(pop 1)

(echo "jlong k = 24: with the lower bound one lower it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 384307168202282326) x) (<= x 384307168202282325)))
(assert (not (jlong.in (* x 24))))
(check-sat)
(pop 1)

(echo "jlong k = 24: with the upper bound one higher it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 384307168202282325) x) (<= x 384307168202282326)))
(assert (not (jlong.in (* x 24))))
(check-sat)
(pop 1)

(echo "jlong k = 1000: the guard [-9223372036854775, 9223372036854775] admits x exactly where x * k is in the lane: expect unsat")
(push 1)
(assert (not (= (and (<= (- 9223372036854775) x) (<= x 9223372036854775)) (jlong.in (* x 1000)))))
(check-sat)
(pop 1)

(echo "jlong k = 1000: under the guard the wrapping product is the true one: expect unsat")
(push 1)
(assert (and (<= (- 9223372036854775) x) (<= x 9223372036854775)))
(assert (not (= (jlong.mul x 1000) (* x 1000))))
(check-sat)
(pop 1)

(echo "jlong k = 1000: with the lower bound one lower it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 9223372036854776) x) (<= x 9223372036854775)))
(assert (not (jlong.in (* x 1000))))
(check-sat)
(pop 1)

(echo "jlong k = 1000: with the upper bound one higher it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 9223372036854775) x) (<= x 9223372036854776)))
(assert (not (jlong.in (* x 1000))))
(check-sat)
(pop 1)

(echo "jlong k = -1: the guard [-9223372036854775807, 9223372036854775807] admits x exactly where x * k is in the lane: expect unsat")
(push 1)
(assert (not (= (and (<= (- 9223372036854775807) x) (<= x 9223372036854775807)) (jlong.in (* x (- 1))))))
(check-sat)
(pop 1)

(echo "jlong k = -1: under the guard the wrapping product is the true one: expect unsat")
(push 1)
(assert (and (<= (- 9223372036854775807) x) (<= x 9223372036854775807)))
(assert (not (= (jlong.mul x (- 1)) (* x (- 1)))))
(check-sat)
(pop 1)

(echo "jlong k = -1: with the lower bound one lower it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 9223372036854775808) x) (<= x 9223372036854775807)))
(assert (not (jlong.in (* x (- 1)))))
(check-sat)
(pop 1)

(echo "jlong k = -2147483648: the guard [-4294967295, 4294967296] admits x exactly where x * k is in the lane: expect unsat")
(push 1)
(assert (not (= (and (<= (- 4294967295) x) (<= x 4294967296)) (jlong.in (* x (- 2147483648))))))
(check-sat)
(pop 1)

(echo "jlong k = -2147483648: under the guard the wrapping product is the true one: expect unsat")
(push 1)
(assert (and (<= (- 4294967295) x) (<= x 4294967296)))
(assert (not (= (jlong.mul x (- 2147483648)) (* x (- 2147483648)))))
(check-sat)
(pop 1)

(echo "jlong k = -2147483648: with the lower bound one lower it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 4294967296) x) (<= x 4294967296)))
(assert (not (jlong.in (* x (- 2147483648)))))
(check-sat)
(pop 1)

(echo "jlong k = -2147483648: with the upper bound one higher it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 4294967295) x) (<= x 4294967297)))
(assert (not (jlong.in (* x (- 2147483648)))))
(check-sat)
(pop 1)

(echo "jlong k = -2147483648: the symmetric bound +-4294967295 refuses a product inside the lane: expect sat")
(push 1)
(assert (not (and (<= (- 4294967295) x) (<= x 4294967295))))
(assert (jlong.in (* x (- 2147483648))))
(check-sat)
(pop 1)

(echo "jlong k = 2147483647: the guard [-4294967298, 4294967298] admits x exactly where x * k is in the lane: expect unsat")
(push 1)
(assert (not (= (and (<= (- 4294967298) x) (<= x 4294967298)) (jlong.in (* x 2147483647)))))
(check-sat)
(pop 1)

(echo "jlong k = 2147483647: under the guard the wrapping product is the true one: expect unsat")
(push 1)
(assert (and (<= (- 4294967298) x) (<= x 4294967298)))
(assert (not (= (jlong.mul x 2147483647) (* x 2147483647))))
(check-sat)
(pop 1)

(echo "jlong k = 2147483647: with the lower bound one lower it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 4294967299) x) (<= x 4294967298)))
(assert (not (jlong.in (* x 2147483647))))
(check-sat)
(pop 1)

(echo "jlong k = 2147483647: with the upper bound one higher it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 4294967298) x) (<= x 4294967299)))
(assert (not (jlong.in (* x 2147483647))))
(check-sat)
(pop 1)

(echo "jlong k = -9223372036854775808: the guard [0, 1] admits x exactly where x * k is in the lane: expect unsat")
(push 1)
(assert (not (= (and (<= 0 x) (<= x 1)) (jlong.in (* x (- 9223372036854775808))))))
(check-sat)
(pop 1)

(echo "jlong k = -9223372036854775808: under the guard the wrapping product is the true one: expect unsat")
(push 1)
(assert (and (<= 0 x) (<= x 1)))
(assert (not (= (jlong.mul x (- 9223372036854775808)) (* x (- 9223372036854775808)))))
(check-sat)
(pop 1)

(echo "jlong k = -9223372036854775808: with the lower bound one lower it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 1) x) (<= x 1)))
(assert (not (jlong.in (* x (- 9223372036854775808)))))
(check-sat)
(pop 1)

(echo "jlong k = -9223372036854775808: with the upper bound one higher it admits an overflow: expect sat")
(push 1)
(assert (and (<= 0 x) (<= x 2)))
(assert (not (jlong.in (* x (- 9223372036854775808)))))
(check-sat)
(pop 1)

(echo "jlong k = 9223372036854775807: the guard [-1, 1] admits x exactly where x * k is in the lane: expect unsat")
(push 1)
(assert (not (= (and (<= (- 1) x) (<= x 1)) (jlong.in (* x 9223372036854775807)))))
(check-sat)
(pop 1)

(echo "jlong k = 9223372036854775807: under the guard the wrapping product is the true one: expect unsat")
(push 1)
(assert (and (<= (- 1) x) (<= x 1)))
(assert (not (= (jlong.mul x 9223372036854775807) (* x 9223372036854775807))))
(check-sat)
(pop 1)

(echo "jlong k = 9223372036854775807: with the lower bound one lower it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 2) x) (<= x 1)))
(assert (not (jlong.in (* x 9223372036854775807))))
(check-sat)
(pop 1)

(echo "jlong k = 9223372036854775807: with the upper bound one higher it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 1) x) (<= x 2)))
(assert (not (jlong.in (* x 9223372036854775807))))
(check-sat)
(pop 1)
(pop 1)

; ym * k at the int lane
(push 1)
(assert (jint.in x))

(echo "jint k = 2: the guard [-1073741824, 1073741823] admits x exactly where x * k is in the lane: expect unsat")
(push 1)
(assert (not (= (and (<= (- 1073741824) x) (<= x 1073741823)) (jint.in (* x 2)))))
(check-sat)
(pop 1)

(echo "jint k = 2: under the guard the wrapping product is the true one: expect unsat")
(push 1)
(assert (and (<= (- 1073741824) x) (<= x 1073741823)))
(assert (not (= (jint.mul x 2) (* x 2))))
(check-sat)
(pop 1)

(echo "jint k = 2: with the lower bound one lower it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 1073741825) x) (<= x 1073741823)))
(assert (not (jint.in (* x 2))))
(check-sat)
(pop 1)

(echo "jint k = 2: with the upper bound one higher it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 1073741824) x) (<= x 1073741824)))
(assert (not (jint.in (* x 2))))
(check-sat)
(pop 1)

(echo "jint k = 2: the symmetric bound +-1073741823 refuses a product inside the lane: expect sat")
(push 1)
(assert (not (and (<= (- 1073741823) x) (<= x 1073741823))))
(assert (jint.in (* x 2)))
(check-sat)
(pop 1)

(echo "jint k = -2: the guard [-1073741823, 1073741824] admits x exactly where x * k is in the lane: expect unsat")
(push 1)
(assert (not (= (and (<= (- 1073741823) x) (<= x 1073741824)) (jint.in (* x (- 2))))))
(check-sat)
(pop 1)

(echo "jint k = -2: under the guard the wrapping product is the true one: expect unsat")
(push 1)
(assert (and (<= (- 1073741823) x) (<= x 1073741824)))
(assert (not (= (jint.mul x (- 2)) (* x (- 2)))))
(check-sat)
(pop 1)

(echo "jint k = -2: with the lower bound one lower it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 1073741824) x) (<= x 1073741824)))
(assert (not (jint.in (* x (- 2)))))
(check-sat)
(pop 1)

(echo "jint k = -2: with the upper bound one higher it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 1073741823) x) (<= x 1073741825)))
(assert (not (jint.in (* x (- 2)))))
(check-sat)
(pop 1)

(echo "jint k = -2: the symmetric bound +-1073741823 refuses a product inside the lane: expect sat")
(push 1)
(assert (not (and (<= (- 1073741823) x) (<= x 1073741823))))
(assert (jint.in (* x (- 2))))
(check-sat)
(pop 1)

(echo "jint k = 3: the guard [-715827882, 715827882] admits x exactly where x * k is in the lane: expect unsat")
(push 1)
(assert (not (= (and (<= (- 715827882) x) (<= x 715827882)) (jint.in (* x 3)))))
(check-sat)
(pop 1)

(echo "jint k = 3: under the guard the wrapping product is the true one: expect unsat")
(push 1)
(assert (and (<= (- 715827882) x) (<= x 715827882)))
(assert (not (= (jint.mul x 3) (* x 3))))
(check-sat)
(pop 1)

(echo "jint k = 3: with the lower bound one lower it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 715827883) x) (<= x 715827882)))
(assert (not (jint.in (* x 3))))
(check-sat)
(pop 1)

(echo "jint k = 3: with the upper bound one higher it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 715827882) x) (<= x 715827883)))
(assert (not (jint.in (* x 3))))
(check-sat)
(pop 1)

(echo "jint k = -1: the guard [-2147483647, 2147483647] admits x exactly where x * k is in the lane: expect unsat")
(push 1)
(assert (not (= (and (<= (- 2147483647) x) (<= x 2147483647)) (jint.in (* x (- 1))))))
(check-sat)
(pop 1)

(echo "jint k = -1: under the guard the wrapping product is the true one: expect unsat")
(push 1)
(assert (and (<= (- 2147483647) x) (<= x 2147483647)))
(assert (not (= (jint.mul x (- 1)) (* x (- 1)))))
(check-sat)
(pop 1)

(echo "jint k = -1: with the lower bound one lower it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 2147483648) x) (<= x 2147483647)))
(assert (not (jint.in (* x (- 1)))))
(check-sat)
(pop 1)

(echo "jint k = -2147483648: the guard [0, 1] admits x exactly where x * k is in the lane: expect unsat")
(push 1)
(assert (not (= (and (<= 0 x) (<= x 1)) (jint.in (* x (- 2147483648))))))
(check-sat)
(pop 1)

(echo "jint k = -2147483648: under the guard the wrapping product is the true one: expect unsat")
(push 1)
(assert (and (<= 0 x) (<= x 1)))
(assert (not (= (jint.mul x (- 2147483648)) (* x (- 2147483648)))))
(check-sat)
(pop 1)

(echo "jint k = -2147483648: with the lower bound one lower it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 1) x) (<= x 1)))
(assert (not (jint.in (* x (- 2147483648)))))
(check-sat)
(pop 1)

(echo "jint k = -2147483648: with the upper bound one higher it admits an overflow: expect sat")
(push 1)
(assert (and (<= 0 x) (<= x 2)))
(assert (not (jint.in (* x (- 2147483648)))))
(check-sat)
(pop 1)

(echo "jint k = -2147483648: the symmetric bound +-0 refuses a product inside the lane: expect sat")
(push 1)
(assert (not (and (<= 0 x) (<= x 0))))
(assert (jint.in (* x (- 2147483648))))
(check-sat)
(pop 1)

(echo "jint k = 2147483647: the guard [-1, 1] admits x exactly where x * k is in the lane: expect unsat")
(push 1)
(assert (not (= (and (<= (- 1) x) (<= x 1)) (jint.in (* x 2147483647)))))
(check-sat)
(pop 1)

(echo "jint k = 2147483647: under the guard the wrapping product is the true one: expect unsat")
(push 1)
(assert (and (<= (- 1) x) (<= x 1)))
(assert (not (= (jint.mul x 2147483647) (* x 2147483647))))
(check-sat)
(pop 1)

(echo "jint k = 2147483647: with the lower bound one lower it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 2) x) (<= x 1)))
(assert (not (jint.in (* x 2147483647))))
(check-sat)
(pop 1)

(echo "jint k = 2147483647: with the upper bound one higher it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 1) x) (<= x 2)))
(assert (not (jint.in (* x 2147483647))))
(check-sat)
(pop 1)

(echo "jint k = 2147483648: the guard [-1, 0] admits x exactly where x * k is in the lane: expect unsat")
(push 1)
(assert (not (= (and (<= (- 1) x) (<= x 0)) (jint.in (* x 2147483648)))))
(check-sat)
(pop 1)

(echo "jint k = 2147483648: under the guard the wrapping product is the true one: expect unsat")
(push 1)
(assert (and (<= (- 1) x) (<= x 0)))
(assert (not (= (jint.mul x (- 2147483648)) (* x 2147483648))))
(check-sat)
(pop 1)

(echo "jint k = 2147483648: with the lower bound one lower it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 2) x) (<= x 0)))
(assert (not (jint.in (* x 2147483648))))
(check-sat)
(pop 1)

(echo "jint k = 2147483648: with the upper bound one higher it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 1) x) (<= x 1)))
(assert (not (jint.in (* x 2147483648))))
(check-sat)
(pop 1)

(echo "jint k = -9223372036854775808: the guard [0, 0] admits x exactly where x * k is in the lane: expect unsat")
(push 1)
(assert (not (= (and (<= 0 x) (<= x 0)) (jint.in (* x (- 9223372036854775808))))))
(check-sat)
(pop 1)

(echo "jint k = -9223372036854775808: under the guard the wrapping product is the true one: expect unsat")
(push 1)
(assert (and (<= 0 x) (<= x 0)))
(assert (not (= (jint.mul x 0) (* x (- 9223372036854775808)))))
(check-sat)
(pop 1)

(echo "jint k = -9223372036854775808: with the lower bound one lower it admits an overflow: expect sat")
(push 1)
(assert (and (<= (- 1) x) (<= x 0)))
(assert (not (jint.in (* x (- 9223372036854775808)))))
(check-sat)
(pop 1)

(echo "jint k = -9223372036854775808: with the upper bound one higher it admits an overflow: expect sat")
(push 1)
(assert (and (<= 0 x) (<= x 1)))
(assert (not (jint.in (* x (- 9223372036854775808)))))
(check-sat)
(pop 1)
(pop 1)
