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

; RoundToIntegral(RINT), the 2^(p-1) construction of VarkaRoundingLowering, over the
; floating-point theory. With C = 2^(p-1) (2^52 for a double), a = |x| and the operations
; correctly rounded to nearest, ties to even:
;
;   r   = (a + C) - C
;   out = a < C ? (x is negative ? -r : r) : x
;
; The claim: out = fp.roundToIntegral RNE x for every x, NaN and both infinities and both
; zeros included. The sign is restored by the negation of r, which for r = +0.0 gives
; -0.0, as the lowering's OR of the sign bit does, and the lowering's NaN keeps the
; operand's bits (SMT-LIB has one NaN, so its payload is a test's, not this file's).
;
; The sat checks show the statement tells the real constant and the real sign handling
; from their neighbours: C a binade lower, or no sign restored (rint(-0.3) = -0.0 is the
; witness), is not rint.
;
; At binary64 C is the lowering's own constant, VarkaRoundingLowering.TWO_52, rendered
; from its bits; binary32's 2^23 is the format's, since no lowering runs at that width.
;
; The logic is ALL and each check runs after a (reset), as round_to_odd.smt2 runs its
; own: nothing here uses java.smt2.
;
; Rendered by VarkaProofFiles. VarkaProofFilesSuite fails when this file differs from its
; rendering, and VARKA_PROOFS_REGEN=true build/sbt 'catalyst/testOnly *VarkaProofFilesSuite'
; rewrites it. Run by dev/varka_prove.sh, which inserts java.smt2 after the set-logic line.

(set-logic ALL)

(echo "binary32: the construction is rint for every x: expect unsat")
(reset)
(set-logic ALL)
(declare-const x (_ FloatingPoint 8 24))
(define-fun C () (_ FloatingPoint 8 24) ((_ to_fp 8 24) RNE 8388608.0))
(define-fun a () (_ FloatingPoint 8 24) (fp.abs x))
(define-fun r () (_ FloatingPoint 8 24) (fp.sub RNE (fp.add RNE a C) C))
(define-fun out () (_ FloatingPoint 8 24) (ite (fp.lt a C) (ite (fp.isNegative x) (fp.neg r) r) x))
(assert (not (= out (fp.roundToIntegral RNE x))))
(check-sat)

(echo "binary32: with C a binade lower it is not rint: expect sat")
(reset)
(set-logic ALL)
(declare-const x (_ FloatingPoint 8 24))
(define-fun C () (_ FloatingPoint 8 24) ((_ to_fp 8 24) RNE 4194304.0))
(define-fun a () (_ FloatingPoint 8 24) (fp.abs x))
(define-fun r () (_ FloatingPoint 8 24) (fp.sub RNE (fp.add RNE a C) C))
(define-fun out () (_ FloatingPoint 8 24) (ite (fp.lt a C) (ite (fp.isNegative x) (fp.neg r) r) x))
(assert (not (= out (fp.roundToIntegral RNE x))))
(check-sat)

(echo "binary32: without the sign restored it is not rint: expect sat")
(reset)
(set-logic ALL)
(declare-const x (_ FloatingPoint 8 24))
(define-fun C () (_ FloatingPoint 8 24) ((_ to_fp 8 24) RNE 8388608.0))
(define-fun a () (_ FloatingPoint 8 24) (fp.abs x))
(define-fun r () (_ FloatingPoint 8 24) (fp.sub RNE (fp.add RNE a C) C))
(define-fun out () (_ FloatingPoint 8 24) (ite (fp.lt a C) r x))
(assert (not (= out (fp.roundToIntegral RNE x))))
(check-sat)

(echo "binary64: the construction is rint for every x: expect unsat")
(reset)
(set-logic ALL)
(declare-const x (_ FloatingPoint 11 53))
(define-fun C () (_ FloatingPoint 11 53) ((_ to_fp 11 53) #x4330000000000000))
(define-fun a () (_ FloatingPoint 11 53) (fp.abs x))
(define-fun r () (_ FloatingPoint 11 53) (fp.sub RNE (fp.add RNE a C) C))
(define-fun out () (_ FloatingPoint 11 53) (ite (fp.lt a C) (ite (fp.isNegative x) (fp.neg r) r) x))
(assert (not (= out (fp.roundToIntegral RNE x))))
(check-sat)

(echo "binary64: with C a binade lower it is not rint: expect sat")
(reset)
(set-logic ALL)
(declare-const x (_ FloatingPoint 11 53))
(define-fun C () (_ FloatingPoint 11 53) ((_ to_fp 11 53) #x4320000000000000))
(define-fun a () (_ FloatingPoint 11 53) (fp.abs x))
(define-fun r () (_ FloatingPoint 11 53) (fp.sub RNE (fp.add RNE a C) C))
(define-fun out () (_ FloatingPoint 11 53) (ite (fp.lt a C) (ite (fp.isNegative x) (fp.neg r) r) x))
(assert (not (= out (fp.roundToIntegral RNE x))))
(check-sat)

(echo "binary64: without the sign restored it is not rint: expect sat")
(reset)
(set-logic ALL)
(declare-const x (_ FloatingPoint 11 53))
(define-fun C () (_ FloatingPoint 11 53) ((_ to_fp 11 53) #x4330000000000000))
(define-fun a () (_ FloatingPoint 11 53) (fp.abs x))
(define-fun r () (_ FloatingPoint 11 53) (fp.sub RNE (fp.add RNE a C) C))
(define-fun out () (_ FloatingPoint 11 53) (ite (fp.lt a C) r x))
(assert (not (= out (fp.roundToIntegral RNE x))))
(check-sat)
