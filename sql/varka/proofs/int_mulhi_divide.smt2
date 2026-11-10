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

; The int lane's multiply-high division (VARKA-149) is exact: for each divisor d below and
; every int n, what the lanes of VarkaDivisionLowering.emitMulHiDivide compute is Java's
; n / d. Per lane, with (Mu, shift) = VarkaDivisionLowering.signedMagic(|d|):
;
;   long p = (long) n * Mu;        I2L, then LongVector.mul by the multiplier
;   int q = (int) (p >> shift);    ASHR by the shift, then L2I
;   q = q + (n >>> 31);            the dividend's sign bit, LSHR 31, added
;   if (d < 0) q = q * -1;         IntVector.mul(-1) for a negative divisor
;
; Each divisor has three checks: the form is n / d for every int n (unsat: no n where it
; is not), and it is not with the multiplier lowered by one, or with the shift raised by
; one (sat: a counterexample exists), which shows the statement can tell the derived
; constants from their neighbours.
;
; Rendered by VarkaProofFiles. VarkaProofFilesSuite fails when this file differs from its
; rendering, and VARKA_PROOFS_REGEN=true build/sbt 'catalyst/testOnly *VarkaProofFilesSuite'
; rewrites it. Run by dev/varka_prove.sh, which inserts java.smt2 after the set-logic line.

(set-logic QF_NIA)

(define-fun mulhi.divide ((n Int) (mu Int) (shift Int) (negate Bool)) Int
  (let ((q (jint.add (l2i (jlong.shr (jlong.mul (i2l n) mu) shift)) (jint.ushr n 31))))
    (ite negate (jint.mul q (- 1)) q)))

(declare-const n Int)
(assert (jint.in n))

(echo "the dividend's domain admits Integer.MIN_VALUE: expect sat")
(push 1)
(assert (= n jint.MIN))
(check-sat)
(pop 1)

(echo "the dividend's domain admits Integer.MAX_VALUE: expect sat")
(push 1)
(assert (= n jint.MAX))
(check-sat)
(pop 1)

; d = 12: signedMagic(12) = (715827883, 33)

(echo "d = 12: the form is n / d for every int n: expect unsat")
(push 1)
(assert (not (= (mulhi.divide n 715827883 33 false) (jint.div n 12))))
(check-sat)
(pop 1)

(echo "d = 12: with the multiplier lowered by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 715827882 33 false) (jint.div n 12))))
(check-sat)
(pop 1)

(echo "d = 12: with the shift raised by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 715827883 34 false) (jint.div n 12))))
(check-sat)
(pop 1)

; d = 2: signedMagic(2) = (2147483649, 32)

(echo "d = 2: the form is n / d for every int n: expect unsat")
(push 1)
(assert (not (= (mulhi.divide n 2147483649 32 false) (jint.div n 2))))
(check-sat)
(pop 1)

(echo "d = 2: with the multiplier lowered by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 2147483648 32 false) (jint.div n 2))))
(check-sat)
(pop 1)

(echo "d = 2: with the shift raised by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 2147483649 33 false) (jint.div n 2))))
(check-sat)
(pop 1)

; d = 3: signedMagic(3) = (1431655766, 32)

(echo "d = 3: the form is n / d for every int n: expect unsat")
(push 1)
(assert (not (= (mulhi.divide n 1431655766 32 false) (jint.div n 3))))
(check-sat)
(pop 1)

(echo "d = 3: with the multiplier lowered by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 1431655765 32 false) (jint.div n 3))))
(check-sat)
(pop 1)

(echo "d = 3: with the shift raised by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 1431655766 33 false) (jint.div n 3))))
(check-sat)
(pop 1)

; d = 7: signedMagic(7) = (2454267027, 34)

(echo "d = 7: the form is n / d for every int n: expect unsat")
(push 1)
(assert (not (= (mulhi.divide n 2454267027 34 false) (jint.div n 7))))
(check-sat)
(pop 1)

(echo "d = 7: with the multiplier lowered by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 2454267026 34 false) (jint.div n 7))))
(check-sat)
(pop 1)

(echo "d = 7: with the shift raised by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 2454267027 35 false) (jint.div n 7))))
(check-sat)
(pop 1)

; d = 100: signedMagic(100) = (1374389535, 37)

(echo "d = 100: the form is n / d for every int n: expect unsat")
(push 1)
(assert (not (= (mulhi.divide n 1374389535 37 false) (jint.div n 100))))
(check-sat)
(pop 1)

(echo "d = 100: with the multiplier lowered by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 1374389534 37 false) (jint.div n 100))))
(check-sat)
(pop 1)

(echo "d = 100: with the shift raised by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 1374389535 38 false) (jint.div n 100))))
(check-sat)
(pop 1)

; d = -3: signedMagic(3) = (1431655766, 32)

(echo "d = -3: the form is n / d for every int n: expect unsat")
(push 1)
(assert (not (= (mulhi.divide n 1431655766 32 true) (jint.div n (- 3)))))
(check-sat)
(pop 1)

(echo "d = -3: with the multiplier lowered by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 1431655765 32 true) (jint.div n (- 3)))))
(check-sat)
(pop 1)

(echo "d = -3: with the shift raised by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 1431655766 33 true) (jint.div n (- 3)))))
(check-sat)
(pop 1)

; d = -12: signedMagic(12) = (715827883, 33)

(echo "d = -12: the form is n / d for every int n: expect unsat")
(push 1)
(assert (not (= (mulhi.divide n 715827883 33 true) (jint.div n (- 12)))))
(check-sat)
(pop 1)

(echo "d = -12: with the multiplier lowered by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 715827882 33 true) (jint.div n (- 12)))))
(check-sat)
(pop 1)

(echo "d = -12: with the shift raised by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 715827883 34 true) (jint.div n (- 12)))))
(check-sat)
(pop 1)

; d = 60: signedMagic(60) = (2290649225, 37)

(echo "d = 60: the form is n / d for every int n: expect unsat")
(push 1)
(assert (not (= (mulhi.divide n 2290649225 37 false) (jint.div n 60))))
(check-sat)
(pop 1)

(echo "d = 60: with the multiplier lowered by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 2290649224 37 false) (jint.div n 60))))
(check-sat)
(pop 1)

(echo "d = 60: with the shift raised by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 2290649225 38 false) (jint.div n 60))))
(check-sat)
(pop 1)

; d = 3600: signedMagic(3600) = (2443359173, 43)

(echo "d = 3600: the form is n / d for every int n: expect unsat")
(push 1)
(assert (not (= (mulhi.divide n 2443359173 43 false) (jint.div n 3600))))
(check-sat)
(pop 1)

(echo "d = 3600: with the multiplier lowered by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 2443359172 43 false) (jint.div n 3600))))
(check-sat)
(pop 1)

(echo "d = 3600: with the shift raised by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 2443359173 44 false) (jint.div n 3600))))
(check-sat)
(pop 1)

; d = 196611: signedMagic(196611) = (2863267841, 49)

(echo "d = 196611: the form is n / d for every int n: expect unsat")
(push 1)
(assert (not (= (mulhi.divide n 2863267841 49 false) (jint.div n 196611))))
(check-sat)
(pop 1)

(echo "d = 196611: with the multiplier lowered by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 2863267840 49 false) (jint.div n 196611))))
(check-sat)
(pop 1)

(echo "d = 196611: with the shift raised by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 2863267841 50 false) (jint.div n 196611))))
(check-sat)
(pop 1)

; d = -2147483648: signedMagic(2147483648) = (2147483649, 62)

(echo "d = -2147483648: the form is n / d for every int n: expect unsat")
(push 1)
(assert (not (= (mulhi.divide n 2147483649 62 true) (jint.div n (- 2147483648)))))
(check-sat)
(pop 1)

(echo "d = -2147483648: with the multiplier lowered by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 2147483648 62 true) (jint.div n (- 2147483648)))))
(check-sat)
(pop 1)

(echo "d = -2147483648: with the shift raised by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 2147483649 63 true) (jint.div n (- 2147483648)))))
(check-sat)
(pop 1)

; d = 10: signedMagic(10) = (1717986919, 34)

(echo "d = 10: the form is n / d for every int n: expect unsat")
(push 1)
(assert (not (= (mulhi.divide n 1717986919 34 false) (jint.div n 10))))
(check-sat)
(pop 1)

(echo "d = 10: with the multiplier lowered by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 1717986918 34 false) (jint.div n 10))))
(check-sat)
(pop 1)

(echo "d = 10: with the shift raised by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 1717986919 35 false) (jint.div n 10))))
(check-sat)
(pop 1)

; d = 1000: signedMagic(1000) = (274877907, 38)

(echo "d = 1000: the form is n / d for every int n: expect unsat")
(push 1)
(assert (not (= (mulhi.divide n 274877907 38 false) (jint.div n 1000))))
(check-sat)
(pop 1)

(echo "d = 1000: with the multiplier lowered by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 274877906 38 false) (jint.div n 1000))))
(check-sat)
(pop 1)

(echo "d = 1000: with the shift raised by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 274877907 39 false) (jint.div n 1000))))
(check-sat)
(pop 1)

; d = 10000: signedMagic(10000) = (1759218605, 44)

(echo "d = 10000: the form is n / d for every int n: expect unsat")
(push 1)
(assert (not (= (mulhi.divide n 1759218605 44 false) (jint.div n 10000))))
(check-sat)
(pop 1)

(echo "d = 10000: with the multiplier lowered by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 1759218604 44 false) (jint.div n 10000))))
(check-sat)
(pop 1)

(echo "d = 10000: with the shift raised by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 1759218605 45 false) (jint.div n 10000))))
(check-sat)
(pop 1)

; d = 100000: signedMagic(100000) = (351843721, 45)

(echo "d = 100000: the form is n / d for every int n: expect unsat")
(push 1)
(assert (not (= (mulhi.divide n 351843721 45 false) (jint.div n 100000))))
(check-sat)
(pop 1)

(echo "d = 100000: with the multiplier lowered by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 351843720 45 false) (jint.div n 100000))))
(check-sat)
(pop 1)

(echo "d = 100000: with the shift raised by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 351843721 46 false) (jint.div n 100000))))
(check-sat)
(pop 1)

; d = 1000000: signedMagic(1000000) = (1125899907, 50)

(echo "d = 1000000: the form is n / d for every int n: expect unsat")
(push 1)
(assert (not (= (mulhi.divide n 1125899907 50 false) (jint.div n 1000000))))
(check-sat)
(pop 1)

(echo "d = 1000000: with the multiplier lowered by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 1125899906 50 false) (jint.div n 1000000))))
(check-sat)
(pop 1)

(echo "d = 1000000: with the shift raised by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 1125899907 51 false) (jint.div n 1000000))))
(check-sat)
(pop 1)

; d = 10000000: signedMagic(10000000) = (1801439851, 54)

(echo "d = 10000000: the form is n / d for every int n: expect unsat")
(push 1)
(assert (not (= (mulhi.divide n 1801439851 54 false) (jint.div n 10000000))))
(check-sat)
(pop 1)

(echo "d = 10000000: with the multiplier lowered by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 1801439850 54 false) (jint.div n 10000000))))
(check-sat)
(pop 1)

(echo "d = 10000000: with the shift raised by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 1801439851 55 false) (jint.div n 10000000))))
(check-sat)
(pop 1)

; d = 100000000: signedMagic(100000000) = (1441151881, 57)

(echo "d = 100000000: the form is n / d for every int n: expect unsat")
(push 1)
(assert (not (= (mulhi.divide n 1441151881 57 false) (jint.div n 100000000))))
(check-sat)
(pop 1)

(echo "d = 100000000: with the multiplier lowered by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 1441151880 57 false) (jint.div n 100000000))))
(check-sat)
(pop 1)

(echo "d = 100000000: with the shift raised by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 1441151881 58 false) (jint.div n 100000000))))
(check-sat)
(pop 1)

; d = 1000000000: signedMagic(1000000000) = (1152921505, 60)

(echo "d = 1000000000: the form is n / d for every int n: expect unsat")
(push 1)
(assert (not (= (mulhi.divide n 1152921505 60 false) (jint.div n 1000000000))))
(check-sat)
(pop 1)

(echo "d = 1000000000: with the multiplier lowered by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 1152921504 60 false) (jint.div n 1000000000))))
(check-sat)
(pop 1)

(echo "d = 1000000000: with the shift raised by one it is not: expect sat")
(push 1)
(assert (not (= (mulhi.divide n 1152921505 61 false) (jint.div n 1000000000))))
(check-sat)
(pop 1)
