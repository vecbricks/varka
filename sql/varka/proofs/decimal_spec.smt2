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

; The specification of Spark's DECIMAL arithmetic over exact integers (VARKA-506 2.5), the side
; of a decimal refinement that is not the kernel. Written by hand from Spark's Decimal and its
; result-type rules (spec/STANDARD.md 4.1 and 4.2, spec/DELTAS.md 1.2) and never from Varka's IR,
; so a proof that a kernel meets it does not rest on the code it checks.
;
; A DECIMAL(p, s) value is u / 10^s for an unscaled integer u with |u| <= 10^p - 1, 1 <= p <= 38.
; Each definition takes the precisions and scales as integers and is applied to the shape's own
; types, which are constants, so over the unscaled values it reads as linear arithmetic, apart
; from the product of two operands. A result type is not defined here: it is Spark's analyzer's,
; read from the shape's plan, and STANDARD.md 4.1 states its rule.
;
; decimal_spec_check.smt2, rendered by VarkaProofFiles, holds every definition to Spark's own
; Decimal over boundary operands. This file is a prelude: dev/varka_prove.sh inserts it after
; java.smt2 into a proof whose header asks for it with "; prelude: decimal_spec.smt2" before its
; set-logic line, and never runs it on its own.

; 10^k for 0 <= k <= 38; 0 outside, where no definition below is applied.
(define-fun spec.dec.pow10 ((k Int)) Int
  (ite (= k 0) 1
  (ite (= k 1) 10
  (ite (= k 2) 100
  (ite (= k 3) 1000
  (ite (= k 4) 10000
  (ite (= k 5) 100000
  (ite (= k 6) 1000000
  (ite (= k 7) 10000000
  (ite (= k 8) 100000000
  (ite (= k 9) 1000000000
  (ite (= k 10) 10000000000
  (ite (= k 11) 100000000000
  (ite (= k 12) 1000000000000
  (ite (= k 13) 10000000000000
  (ite (= k 14) 100000000000000
  (ite (= k 15) 1000000000000000
  (ite (= k 16) 10000000000000000
  (ite (= k 17) 100000000000000000
  (ite (= k 18) 1000000000000000000
  (ite (= k 19) 10000000000000000000
  (ite (= k 20) 100000000000000000000
  (ite (= k 21) 1000000000000000000000
  (ite (= k 22) 10000000000000000000000
  (ite (= k 23) 100000000000000000000000
  (ite (= k 24) 1000000000000000000000000
  (ite (= k 25) 10000000000000000000000000
  (ite (= k 26) 100000000000000000000000000
  (ite (= k 27) 1000000000000000000000000000
  (ite (= k 28) 10000000000000000000000000000
  (ite (= k 29) 100000000000000000000000000000
  (ite (= k 30) 1000000000000000000000000000000
  (ite (= k 31) 10000000000000000000000000000000
  (ite (= k 32) 100000000000000000000000000000000
  (ite (= k 33) 1000000000000000000000000000000000
  (ite (= k 34) 10000000000000000000000000000000000
  (ite (= k 35) 100000000000000000000000000000000000
  (ite (= k 36) 1000000000000000000000000000000000000
  (ite (= k 37) 10000000000000000000000000000000000000
  (ite (= k 38) 100000000000000000000000000000000000000
  0))))))))))))))))))))))))))))))))))))))))

; u fits DECIMAL(p, s): |u| <= 10^p - 1. Spark's changePrecision(p, s) succeeds on exactly these,
; so a value outside is null in LEGACY and TRY and raises NUMERIC_VALUE_OUT_OF_RANGE under ANSI.
(define-fun spec.dec.fits ((u Int) (p Int)) Bool
  (and (<= (- 1 (spec.dec.pow10 p)) u) (<= u (- (spec.dec.pow10 p) 1))))

; u / 10^s as an unscaled value at scale s + k, k >= 0: exact.
(define-fun spec.dec.rescale ((u Int) (k Int)) Int (* u (spec.dec.pow10 k)))

; The scale of a sum, a difference and a comparison: max(s1, s2).
(define-fun spec.dec.wider ((s1 Int) (s2 Int)) Int (ite (>= s1 s2) s1 s2))

; u1 / 10^s1 + u2 / 10^s2 and its difference, exact, unscaled at max(s1, s2).
(define-fun spec.dec.add ((u1 Int) (s1 Int) (u2 Int) (s2 Int)) Int
  (+ (spec.dec.rescale u1 (- (spec.dec.wider s1 s2) s1))
     (spec.dec.rescale u2 (- (spec.dec.wider s1 s2) s2))))
(define-fun spec.dec.sub ((u1 Int) (s1 Int) (u2 Int) (s2 Int)) Int
  (- (spec.dec.rescale u1 (- (spec.dec.wider s1 s2) s1))
     (spec.dec.rescale u2 (- (spec.dec.wider s1 s2) s2))))

; The product, unscaled at s1 + s2. Spark's Decimal.* rounds the exact product to 39 digits
; toward zero first (MathContext(39, DOWN)), so this is Spark's wherever |u1 * u2| < 10^39, which
; holds for every product of two operands of 19 digits or fewer.
(define-fun spec.dec.mul ((u1 Int) (u2 Int)) Int (* u1 u2))

; Negation and abs, at the operand's own scale; neither leaves its precision.
(define-fun spec.dec.neg ((u Int)) Int (- u))
(define-fun spec.dec.abs ((u Int)) Int (ite (< u 0) (- u) u))

; The comparison of u1 / 10^s1 with u2 / 10^s2, as Decimal.compare gives it: -1, 0 or 1.
(define-fun spec.dec.cmp ((u1 Int) (s1 Int) (u2 Int) (s2 Int)) Int
  (let ((l (spec.dec.rescale u1 (- (spec.dec.wider s1 s2) s1)))
        (r (spec.dec.rescale u2 (- (spec.dec.wider s1 s2) s2))))
    (ite (< l r) (- 1) (ite (= l r) 0 1))))

; u / 10^s at scale s - k, k >= 0, rounded HALF_UP, a tie away from zero: Spark's changePrecision
; to a smaller scale, whose rounding mode is ROUND_HALF_UP unless its caller names another.
(define-fun spec.dec.halfup ((u Int) (k Int)) Int
  (let ((h (* 5 (spec.dec.pow10 (- k 1)))))
    (ite (< u 0)
      (- (div (+ (- u) h) (spec.dec.pow10 k)))
      (div (+ u h) (spec.dec.pow10 k)))))
