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

; The bitmap functions' lowering (VARKA-335) is Spark's for every long. Spark's
; BitmapExpressionUtils, with N = NUM_BITS:
;
;   bitmapBucketNumber(v) = v > 0 ? 1 + (v - 1) / N : v / N
;   bitmapBitPosition(v)  = v > 0 ? (v - 1) % N : (-v) % N
;
; VarkaNodeCompiler.bitmap, over the long lane, with N = 2^shift:
;
;   w = v > 0 ? v - 1 : -v           a blend of a wrapping subtract and negate
;   position = w & (N - 1)
;   q = w >>> shift
;   bucket = v > 0 ? q + 1 : -q      a second blend on the same comparison
;
; w is never negative but at Long.MIN_VALUE, where -v wraps to itself, Spark's (-v) % N
; is 0 and so is the mask, and w >>> shift is 2^(63 - shift), whose negation is
; Long.MIN_VALUE / N. Every operation is by a constant, so each claim is linear. Beside
; them, that the domain admits Long.MIN_VALUE, and the neighbours: the shift one less, the
; mask one bit narrower, and the arithmetic shift in place of the logical one. The logic
; is ALL because cvc5 answers the position's claim unknown under QF_NIA and proves it in a
; fraction of a second under ALL.
;
; Rendered by VarkaProofFiles. VarkaProofFilesSuite fails when this file differs from its
; rendering, and VARKA_PROOFS_REGEN=true build/sbt 'catalyst/testOnly *VarkaProofFilesSuite'
; rewrites it. Run by dev/varka_prove.sh, which inserts java.smt2 after the set-logic line.

(set-logic ALL)

(echo "bitmap_bucket_number: the lowering is BitmapExpressionUtils.bitmapBucketNumber for every long: expect unsat")
(push 1)
(declare-const v Int)
(assert (jlong.in v))
(define-fun w () Int (ite (> v 0) (jlong.sub v 1) (jlong.neg v)))
(assert (not (= (ite (> v 0) (jlong.add (jlong.ushr w 15) 1) (jlong.neg (jlong.ushr w 15))) (ite (> v 0) (jlong.add 1 (jlong.div (jlong.sub v 1) 32768)) (jlong.div v 32768)))))
(check-sat)
(pop 1)

(echo "bitmap_bit_position: the lowering is BitmapExpressionUtils.bitmapBitPosition for every long: expect unsat")
(push 1)
(declare-const v Int)
(assert (jlong.in v))
(define-fun w () Int (ite (> v 0) (jlong.sub v 1) (jlong.neg v)))
(assert (not (= (jlong.and.low w 32768) (ite (> v 0) (jlong.rem (jlong.sub v 1) 32768) (jlong.rem (jlong.neg v) 32768)))))
(check-sat)
(pop 1)

(echo "the domain admits Long.MIN_VALUE, where -v wraps: expect sat")
(push 1)
(declare-const v Int)
(assert (jlong.in v))
(define-fun w () Int (ite (> v 0) (jlong.sub v 1) (jlong.neg v)))
(assert (= v jlong.MIN))
(assert (= w jlong.MIN))
(check-sat)
(pop 1)

(echo "bitmap_bucket_number: with the shift one less it is not: expect sat")
(push 1)
(declare-const v Int)
(assert (jlong.in v))
(define-fun w () Int (ite (> v 0) (jlong.sub v 1) (jlong.neg v)))
(assert (not (= (ite (> v 0) (jlong.add (jlong.ushr w 14) 1) (jlong.neg (jlong.ushr w 14))) (ite (> v 0) (jlong.add 1 (jlong.div (jlong.sub v 1) 32768)) (jlong.div v 32768)))))
(check-sat)
(pop 1)

(echo "bitmap_bucket_number: with the arithmetic shift it is not, at Long.MIN_VALUE: expect sat")
(push 1)
(declare-const v Int)
(assert (jlong.in v))
(define-fun w () Int (ite (> v 0) (jlong.sub v 1) (jlong.neg v)))
(assert (not (= (ite (> v 0) (jlong.add (jlong.shr w 15) 1) (jlong.neg (jlong.shr w 15))) (ite (> v 0) (jlong.add 1 (jlong.div (jlong.sub v 1) 32768)) (jlong.div v 32768)))))
(check-sat)
(pop 1)

(echo "bitmap_bit_position: with the mask a bit narrower it is not: expect sat")
(push 1)
(declare-const v Int)
(assert (jlong.in v))
(define-fun w () Int (ite (> v 0) (jlong.sub v 1) (jlong.neg v)))
(assert (not (= (jlong.and.low w 16384) (ite (> v 0) (jlong.rem (jlong.sub v 1) 32768) (jlong.rem (jlong.neg v) 32768)))))
(check-sat)
(pop 1)
