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

; The AVX2 forms of L2D and L2F (VARKA-343.md 2.3), where the host has no 64-bit
; integer conversion (convertsFallBack()). With M = 0x1.8p52, MB its bits and
; bits(x) = Double.doubleToRawLongBits(x), over the long lane:
;
;   L2D, split    hi = v >> 32, lo = v & 0xFFFFFFFF
;                 dh = (bitsToDouble(hi + MB) - M) * 0x1p32
;                 dl = bitsToDouble(lo | bits(0x1p52)) - 0x1p52
;                 d = dh + dl
;   L2D, magic    bitsToDouble(v + MB) - M, under a guard of |v| <= 2^51
;   L2F           s = v >> 63, m = (v ^ s) - s read unsigned (2^63 at Long.MIN_VALUE)
;                 sel = -(((m >>> 52) + Long.MAX_VALUE) >>> 63), all ones where
;                   m >>> 52 != 0 (big)
;                 t12 = (m >>> 12) | (((m & 0xFFF) + 0xFFF) >>> 12)
;                 t = m ^ ((m ^ t12) & sel), t12 where big and m elsewhere
;                 p = (bits(0x1p52) | (v & Long.MIN_VALUE)) + (sel & (12 << 52))
;                 d = bitsToDouble(t | p) - bitsToDouble(p), f = (float) d
;
; These are the operations VarkaConversionLowering emits, from its constants; the L2F form
; has no mask, since C2 on x86 lowers none at two 64-bit lanes. p is 2^52 below 2^52, where
; d is m exactly, and 2^64 on the big arm, whose ulp 2^12 scales t as it converts, so that
; d is t * 2^12 exactly; p's sign bit is v's, which negates both operands of the
; subtraction.
;
; The claims: the split is (double) v and the magic is (double) v inside its window, and
; the L2F form is (float) v, for every long.
;
; The split is proven in three lemmas, since stated whole it is past the per-check limit
; (Z3 past 30 s, cvc5 about 17 s): dh and dl are exact, the doubles of hi * 2^32 and of lo,
; and hi * 2^32 + lo = v over 66 bits. dh is shown equal to both the conversion of
; hi * 2^32 toward -infinity and toward +infinity, which differ on every inexact value
; of either sign; dl to both lo's conversion toward zero and toward +infinity, which for
; lo, unsigned and so never negative, are the conversions toward -infinity and +infinity
; again.
; Then d = dh + dl is IEEE 754's addition of two exact values, the rounding to nearest
; of their exact sum v, which is (double) v: that last step is the definition of the
; addition, not a check.
;
; L2F rests on Boldo and Melquiond, "Emulation of a FMA and correctly-rounded sums:
; proved algorithms using rounding to odd", IEEE Transactions on Computers 57(4), 2008,
; Theorem 3 (To_Odd_Even_is_Even), read from the authors' HAL deposit inria-00080427v2
; (sql/varka/plans/m8/READING_ROUNDING_TO_ODD.md):
;
;   Assuming p >= 2, k >= 2, and E_e >= 2 + E_w,
;   for all x in R, round_p(odd_{p+k}(x)) = round_p(x).
;
; For m in [2^j, 2^(j+1)), j from 52 to 63, t << 12 is m rounded to odd at j - 11 bits
; (truncated, the sticky bit or-ed into the last kept bit), and p = 24, so k = j - 35 >=
; 17. Since k moves with the binade, the instance is checked per binade, beside the form
; itself per binade, over the magnitude, with its sticky bit shown to be the theorem's
; rounding to odd. The sign: m is |v| read unsigned and (float) v is m's rounding negated
; where v < 0; v & Long.MIN_VALUE is set exactly there; with the sign bit set in both
; operands each arm's subtraction is the negated magnitude, for every t from 1 (t = 0
; would give +0.0 either way, and t >= 1 where v < 0); and D2F of a negated double is the
; negated float, all by the symmetry of rounding to nearest. That k >= 2 is the theorem's
; bound, and not slack in it, is shown on the top binade: rounding to odd at 26 bits then
; to nearest at 24 is one rounding, at 25 bits it is not.
;
; The sat checks: the domain admits Long.MIN_VALUE; the split with MB or the low word's
; magic one higher, or a shift of 31, is wrong; the magic is wrong one past its window at
; either end; the sign with m = v ^ s, without the - s, is wrong; L2F without the sticky
; bit, or as two roundings to nearest, or with a shift of 11 (t reaches 2^52) or the small
; arm up to 2^53 (m meets the magic's exponent), is wrong. A shift of 13 is still right
; (k = 16 at 2^52): the theorem's slack, kept as an unsat check so that the bound the code
; sits at is not mistaken for the only one.
;
; The logic is ALL because the file reasons over bit-vectors and floating point. Each
; check runs in a context of its own, after a (reset) that restates the logic and the
; rto definitions, rather than between push and pop: once a file pushes, Z3 answers from
; its incremental core, where a single 64-bit fp.sub costs seconds and four of these
; checks pass the per-check limit, while a context that never pushed bit-blasts each in
; a fraction of a second. Nothing here uses java.smt2, which the reset discards.
;
; Rendered by VarkaProofFiles. VarkaProofFilesSuite fails when this file differs from its
; rendering, and VARKA_PROOFS_REGEN=true build/sbt 'catalyst/testOnly *VarkaProofFilesSuite'
; rewrites it. Run by dev/varka_prove.sh, which inserts java.smt2 after the set-logic line.

(set-logic ALL)

(echo "l2d split: dh is exact, the double of hi * 2^32, for every int hi: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const hi (_ BitVec 32))
(assert (not (and (= (fp.mul RNE (fp.sub RNE (rto.bitsToDouble (bvadd ((_ sign_extend 32) hi) #x4338000000000000)) rto.M) rto.P32) ((_ to_fp 11 53) RTN (concat hi #x00000000))) (= (fp.mul RNE (fp.sub RNE (rto.bitsToDouble (bvadd ((_ sign_extend 32) hi) #x4338000000000000)) rto.M) rto.P32) ((_ to_fp 11 53) RTP (concat hi #x00000000))))))
(check-sat)

(echo "l2d split: dl is exact, the double of lo, for every unsigned int lo: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const lo (_ BitVec 32))
(assert (not (and (= (fp.sub RNE (rto.bitsToDouble (bvor ((_ zero_extend 32) lo) #x4330000000000000)) rto.P52) ((_ to_fp_unsigned 11 53) RTZ ((_ zero_extend 32) lo))) (= (fp.sub RNE (rto.bitsToDouble (bvor ((_ zero_extend 32) lo) #x4330000000000000)) rto.P52) ((_ to_fp_unsigned 11 53) RTP ((_ zero_extend 32) lo))))))
(check-sat)

(echo "l2d split: hi * 2^32 + lo is v for every long, over 66 bits: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const v (_ BitVec 64))
(assert (not (= ((_ sign_extend 2) v) (bvadd (bvshl ((_ sign_extend 2) (bvashr v #x0000000000000020)) (_ bv32 66)) ((_ zero_extend 2) (bvand v #x00000000ffffffff))))))
(check-sat)

(echo "l2d split: the domain admits Long.MIN_VALUE, whose hi is -2^31: expect sat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const v (_ BitVec 64))
(assert (= v #x8000000000000000))
(assert (= (bvashr v #x0000000000000020) #xffffffff80000000))
(check-sat)

(echo "l2d split: with MB one higher it is not (double) v: expect sat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const v (_ BitVec 64))
(assert (not (= (fp.add RNE (fp.mul RNE (fp.sub RNE (rto.bitsToDouble (bvadd (bvashr v #x0000000000000020) #x4338000000000001)) rto.M) rto.P32) (fp.sub RNE (rto.bitsToDouble (bvor (bvand v #x00000000ffffffff) #x4330000000000000)) rto.P52)) ((_ to_fp 11 53) RNE v))))
(check-sat)

(echo "l2d split: with the low word's magic one higher it is not (double) v: expect sat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const v (_ BitVec 64))
(assert (not (= (fp.add RNE (fp.mul RNE (fp.sub RNE (rto.bitsToDouble (bvadd (bvashr v #x0000000000000020) #x4338000000000000)) rto.M) rto.P32) (fp.sub RNE (rto.bitsToDouble (bvor (bvand v #x00000000ffffffff) #x4330000000000001)) rto.P52)) ((_ to_fp 11 53) RNE v))))
(check-sat)

(echo "l2d split: with a shift of 31 the parts do not sum to v: expect sat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const v (_ BitVec 64))
(assert (not (= ((_ sign_extend 2) v) (bvadd (bvshl ((_ sign_extend 2) (bvashr v #x000000000000001f)) (_ bv32 66)) ((_ zero_extend 2) (bvand v #x00000000ffffffff))))))
(check-sat)

(echo "l2d magic: bitsToDouble(v + MB) - M is (double) v for every |v| <= 2^51: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const v (_ BitVec 64))
(assert (bvsle v #x0008000000000000))
(assert (bvsge v #xfff8000000000000))
(assert (not (= (fp.sub RNE (rto.bitsToDouble (bvadd v #x4338000000000000)) rto.M) ((_ to_fp 11 53) RNE v))))
(check-sat)

(echo "l2d magic: at 2^51 + 1 it is not: expect sat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const v (_ BitVec 64))
(assert (= v #x0008000000000001))
(assert (not (= (fp.sub RNE (rto.bitsToDouble (bvadd v #x4338000000000000)) rto.M) ((_ to_fp 11 53) RNE v))))
(check-sat)

(echo "l2d magic: at -(2^51 + 1) it is not: expect sat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const v (_ BitVec 64))
(assert (= v #xfff7ffffffffffff))
(assert (not (= (fp.sub RNE (rto.bitsToDouble (bvadd v #x4338000000000000)) rto.M) ((_ to_fp 11 53) RNE v))))
(check-sat)

(echo "l2f: (float) v is the rounding of m = |v| read unsigned, negated where v < 0, for every long: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const v (_ BitVec 64))
(assert (not (= ((_ to_fp 8 24) RNE v) (ite (= (bvashr v #x000000000000003f) #x0000000000000000) ((_ to_fp_unsigned 8 24) RNE (bvsub (bvxor v (bvashr v #x000000000000003f)) (bvashr v #x000000000000003f))) (fp.neg ((_ to_fp_unsigned 8 24) RNE (bvsub (bvxor v (bvashr v #x000000000000003f)) (bvashr v #x000000000000003f))))))))
(check-sat)

(echo "l2f: with m = v ^ s, without the - s, it is not (float) v: expect sat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const v (_ BitVec 64))
(assert (not (= ((_ to_fp 8 24) RNE v) (ite (= (bvashr v #x000000000000003f) #x0000000000000000) ((_ to_fp_unsigned 8 24) RNE (bvxor v (bvashr v #x000000000000003f))) (fp.neg ((_ to_fp_unsigned 8 24) RNE (bvxor v (bvashr v #x000000000000003f))))))))
(check-sat)

(echo "l2f: v & Long.MIN_VALUE is set exactly where v >> 63 is, for every long: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const v (_ BitVec 64))
(assert (not (= (= (bvand v #x8000000000000000) #x8000000000000000) (not (= (bvashr v #x000000000000003f) #x0000000000000000)))))
(check-sat)

(echo "l2f: with v's sign bit the magic of 2^52 gives the negated double, for every t in [1, 2^52): expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const t (_ BitVec 64))
(assert (= ((_ extract 63 52) t) #b000000000000))
(assert (not (= t #x0000000000000000)))
(assert (not (= (fp.sub RNE (rto.bitsToDouble (bvor t (bvor #x4330000000000000 #x8000000000000000))) (rto.bitsToDouble (bvor #x4330000000000000 #x8000000000000000))) (fp.neg (fp.sub RNE (rto.bitsToDouble (bvor t #x4330000000000000)) (rto.bitsToDouble #x4330000000000000))))))
(check-sat)

(echo "l2f: with v's sign bit the magic of 2^64 gives the negated double, for every t in [1, 2^52): expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const t (_ BitVec 64))
(assert (= ((_ extract 63 52) t) #b000000000000))
(assert (not (= t #x0000000000000000)))
(assert (not (= (fp.sub RNE (rto.bitsToDouble (bvor t (bvor #x43f0000000000000 #x8000000000000000))) (rto.bitsToDouble (bvor #x43f0000000000000 #x8000000000000000))) (fp.neg (fp.sub RNE (rto.bitsToDouble (bvor t #x43f0000000000000)) (rto.bitsToDouble #x43f0000000000000))))))
(check-sat)

(echo "l2f: D2F of a negated double is the negated float: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const x Float64)
(assert (not (= ((_ to_fp 8 24) RNE (fp.neg x)) (fp.neg ((_ to_fp 8 24) RNE x)))))
(check-sat)

(echo "l2f: the sticky bit (((m & 0xFFF) + 0xFFF) >>> 12) is m rounded to odd at 2^12, for every m: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (not (= (bvor (bvlshr m #x000000000000000c) (bvlshr (bvadd (bvand m #x0000000000000fff) #x0000000000000fff) #x000000000000000c)) (bvor (bvlshr m #x000000000000000c) (ite (= ((_ extract 11 0) m) #b000000000000) #x0000000000000000 #x0000000000000001)))))
(check-sat)

(echo "l2f: below 2^52 the form is the rounding of m: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 52) m) #b000000000000))
(assert (not (= ((_ to_fp 8 24) RNE (fp.sub RNE (rto.bitsToDouble (bvor (bvxor m (bvand (bvxor m (bvor (bvlshr m #x000000000000000c) (bvlshr (bvadd (bvand m #x0000000000000fff) #x0000000000000fff) #x000000000000000c))) (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)))) (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000)))) (rto.bitsToDouble (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000))))) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: for m in [2^52, 2^53) the form is the rounding of m: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 52) m) #b000000000001))
(assert (not (= ((_ to_fp 8 24) RNE (fp.sub RNE (rto.bitsToDouble (bvor (bvxor m (bvand (bvxor m (bvor (bvlshr m #x000000000000000c) (bvlshr (bvadd (bvand m #x0000000000000fff) #x0000000000000fff) #x000000000000000c))) (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)))) (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000)))) (rto.bitsToDouble (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000))))) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: for m in [2^53, 2^54) the form is the rounding of m: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 53) m) #b00000000001))
(assert (not (= ((_ to_fp 8 24) RNE (fp.sub RNE (rto.bitsToDouble (bvor (bvxor m (bvand (bvxor m (bvor (bvlshr m #x000000000000000c) (bvlshr (bvadd (bvand m #x0000000000000fff) #x0000000000000fff) #x000000000000000c))) (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)))) (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000)))) (rto.bitsToDouble (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000))))) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: for m in [2^54, 2^55) the form is the rounding of m: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 54) m) #b0000000001))
(assert (not (= ((_ to_fp 8 24) RNE (fp.sub RNE (rto.bitsToDouble (bvor (bvxor m (bvand (bvxor m (bvor (bvlshr m #x000000000000000c) (bvlshr (bvadd (bvand m #x0000000000000fff) #x0000000000000fff) #x000000000000000c))) (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)))) (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000)))) (rto.bitsToDouble (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000))))) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: for m in [2^55, 2^56) the form is the rounding of m: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 55) m) #b000000001))
(assert (not (= ((_ to_fp 8 24) RNE (fp.sub RNE (rto.bitsToDouble (bvor (bvxor m (bvand (bvxor m (bvor (bvlshr m #x000000000000000c) (bvlshr (bvadd (bvand m #x0000000000000fff) #x0000000000000fff) #x000000000000000c))) (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)))) (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000)))) (rto.bitsToDouble (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000))))) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: for m in [2^56, 2^57) the form is the rounding of m: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 56) m) #b00000001))
(assert (not (= ((_ to_fp 8 24) RNE (fp.sub RNE (rto.bitsToDouble (bvor (bvxor m (bvand (bvxor m (bvor (bvlshr m #x000000000000000c) (bvlshr (bvadd (bvand m #x0000000000000fff) #x0000000000000fff) #x000000000000000c))) (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)))) (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000)))) (rto.bitsToDouble (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000))))) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: for m in [2^57, 2^58) the form is the rounding of m: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 57) m) #b0000001))
(assert (not (= ((_ to_fp 8 24) RNE (fp.sub RNE (rto.bitsToDouble (bvor (bvxor m (bvand (bvxor m (bvor (bvlshr m #x000000000000000c) (bvlshr (bvadd (bvand m #x0000000000000fff) #x0000000000000fff) #x000000000000000c))) (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)))) (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000)))) (rto.bitsToDouble (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000))))) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: for m in [2^58, 2^59) the form is the rounding of m: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 58) m) #b000001))
(assert (not (= ((_ to_fp 8 24) RNE (fp.sub RNE (rto.bitsToDouble (bvor (bvxor m (bvand (bvxor m (bvor (bvlshr m #x000000000000000c) (bvlshr (bvadd (bvand m #x0000000000000fff) #x0000000000000fff) #x000000000000000c))) (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)))) (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000)))) (rto.bitsToDouble (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000))))) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: for m in [2^59, 2^60) the form is the rounding of m: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 59) m) #b00001))
(assert (not (= ((_ to_fp 8 24) RNE (fp.sub RNE (rto.bitsToDouble (bvor (bvxor m (bvand (bvxor m (bvor (bvlshr m #x000000000000000c) (bvlshr (bvadd (bvand m #x0000000000000fff) #x0000000000000fff) #x000000000000000c))) (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)))) (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000)))) (rto.bitsToDouble (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000))))) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: for m in [2^60, 2^61) the form is the rounding of m: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 60) m) #b0001))
(assert (not (= ((_ to_fp 8 24) RNE (fp.sub RNE (rto.bitsToDouble (bvor (bvxor m (bvand (bvxor m (bvor (bvlshr m #x000000000000000c) (bvlshr (bvadd (bvand m #x0000000000000fff) #x0000000000000fff) #x000000000000000c))) (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)))) (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000)))) (rto.bitsToDouble (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000))))) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: for m in [2^61, 2^62) the form is the rounding of m: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 61) m) #b001))
(assert (not (= ((_ to_fp 8 24) RNE (fp.sub RNE (rto.bitsToDouble (bvor (bvxor m (bvand (bvxor m (bvor (bvlshr m #x000000000000000c) (bvlshr (bvadd (bvand m #x0000000000000fff) #x0000000000000fff) #x000000000000000c))) (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)))) (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000)))) (rto.bitsToDouble (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000))))) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: for m in [2^62, 2^63) the form is the rounding of m: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 62) m) #b01))
(assert (not (= ((_ to_fp 8 24) RNE (fp.sub RNE (rto.bitsToDouble (bvor (bvxor m (bvand (bvxor m (bvor (bvlshr m #x000000000000000c) (bvlshr (bvadd (bvand m #x0000000000000fff) #x0000000000000fff) #x000000000000000c))) (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)))) (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000)))) (rto.bitsToDouble (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000))))) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: for m in [2^63, 2^64) the form is the rounding of m: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 63) m) #b1))
(assert (not (= ((_ to_fp 8 24) RNE (fp.sub RNE (rto.bitsToDouble (bvor (bvxor m (bvand (bvxor m (bvor (bvlshr m #x000000000000000c) (bvlshr (bvadd (bvand m #x0000000000000fff) #x0000000000000fff) #x000000000000000c))) (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)))) (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000)))) (rto.bitsToDouble (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000))))) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: Theorem 3 at k = 17: for m in [2^52, 2^53) rounding t << 12 to 24 bits is rounding m: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 52) m) #b000000000001))
(assert (not (= ((_ to_fp_unsigned 8 24) RNE (bvshl (bvor (bvlshr m #x000000000000000c) (ite (= ((_ extract 11 0) m) #b000000000000) #x0000000000000000 #x0000000000000001)) #x000000000000000c)) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: Theorem 3 at k = 18: for m in [2^53, 2^54) rounding t << 12 to 24 bits is rounding m: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 53) m) #b00000000001))
(assert (not (= ((_ to_fp_unsigned 8 24) RNE (bvshl (bvor (bvlshr m #x000000000000000c) (ite (= ((_ extract 11 0) m) #b000000000000) #x0000000000000000 #x0000000000000001)) #x000000000000000c)) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: Theorem 3 at k = 19: for m in [2^54, 2^55) rounding t << 12 to 24 bits is rounding m: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 54) m) #b0000000001))
(assert (not (= ((_ to_fp_unsigned 8 24) RNE (bvshl (bvor (bvlshr m #x000000000000000c) (ite (= ((_ extract 11 0) m) #b000000000000) #x0000000000000000 #x0000000000000001)) #x000000000000000c)) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: Theorem 3 at k = 20: for m in [2^55, 2^56) rounding t << 12 to 24 bits is rounding m: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 55) m) #b000000001))
(assert (not (= ((_ to_fp_unsigned 8 24) RNE (bvshl (bvor (bvlshr m #x000000000000000c) (ite (= ((_ extract 11 0) m) #b000000000000) #x0000000000000000 #x0000000000000001)) #x000000000000000c)) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: Theorem 3 at k = 21: for m in [2^56, 2^57) rounding t << 12 to 24 bits is rounding m: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 56) m) #b00000001))
(assert (not (= ((_ to_fp_unsigned 8 24) RNE (bvshl (bvor (bvlshr m #x000000000000000c) (ite (= ((_ extract 11 0) m) #b000000000000) #x0000000000000000 #x0000000000000001)) #x000000000000000c)) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: Theorem 3 at k = 22: for m in [2^57, 2^58) rounding t << 12 to 24 bits is rounding m: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 57) m) #b0000001))
(assert (not (= ((_ to_fp_unsigned 8 24) RNE (bvshl (bvor (bvlshr m #x000000000000000c) (ite (= ((_ extract 11 0) m) #b000000000000) #x0000000000000000 #x0000000000000001)) #x000000000000000c)) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: Theorem 3 at k = 23: for m in [2^58, 2^59) rounding t << 12 to 24 bits is rounding m: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 58) m) #b000001))
(assert (not (= ((_ to_fp_unsigned 8 24) RNE (bvshl (bvor (bvlshr m #x000000000000000c) (ite (= ((_ extract 11 0) m) #b000000000000) #x0000000000000000 #x0000000000000001)) #x000000000000000c)) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: Theorem 3 at k = 24: for m in [2^59, 2^60) rounding t << 12 to 24 bits is rounding m: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 59) m) #b00001))
(assert (not (= ((_ to_fp_unsigned 8 24) RNE (bvshl (bvor (bvlshr m #x000000000000000c) (ite (= ((_ extract 11 0) m) #b000000000000) #x0000000000000000 #x0000000000000001)) #x000000000000000c)) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: Theorem 3 at k = 25: for m in [2^60, 2^61) rounding t << 12 to 24 bits is rounding m: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 60) m) #b0001))
(assert (not (= ((_ to_fp_unsigned 8 24) RNE (bvshl (bvor (bvlshr m #x000000000000000c) (ite (= ((_ extract 11 0) m) #b000000000000) #x0000000000000000 #x0000000000000001)) #x000000000000000c)) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: Theorem 3 at k = 26: for m in [2^61, 2^62) rounding t << 12 to 24 bits is rounding m: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 61) m) #b001))
(assert (not (= ((_ to_fp_unsigned 8 24) RNE (bvshl (bvor (bvlshr m #x000000000000000c) (ite (= ((_ extract 11 0) m) #b000000000000) #x0000000000000000 #x0000000000000001)) #x000000000000000c)) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: Theorem 3 at k = 27: for m in [2^62, 2^63) rounding t << 12 to 24 bits is rounding m: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 62) m) #b01))
(assert (not (= ((_ to_fp_unsigned 8 24) RNE (bvshl (bvor (bvlshr m #x000000000000000c) (ite (= ((_ extract 11 0) m) #b000000000000) #x0000000000000000 #x0000000000000001)) #x000000000000000c)) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: Theorem 3 at k = 28: for m in [2^63, 2^64) rounding t << 12 to 24 bits is rounding m: expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 63) m) #b1))
(assert (not (= ((_ to_fp_unsigned 8 24) RNE (bvshl (bvor (bvlshr m #x000000000000000c) (ite (= ((_ extract 11 0) m) #b000000000000) #x0000000000000000 #x0000000000000001)) #x000000000000000c)) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: the domain admits Long.MIN_VALUE, read unsigned as 2^63: expect sat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const v (_ BitVec 64))
(assert (= v #x8000000000000000))
(assert (= (bvsub (bvxor v (bvashr v #x000000000000003f)) (bvashr v #x000000000000003f)) #x8000000000000000))
(check-sat)

(echo "l2f: Theorem 3's bound, on [2^63, 2^64): rounding to odd at 26 bits then to nearest at 24 is one rounding (k = 2): expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 63) m) #b1))
(assert (not (= ((_ to_fp_unsigned 8 24) RNE (bvshl (bvor (bvlshr m #x0000000000000026) (ite (= ((_ extract 37 0) m) #b00000000000000000000000000000000000000) #x0000000000000000 #x0000000000000001)) #x0000000000000026)) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: Theorem 3's bound, on [2^63, 2^64): rounding to odd at 25 bits then to nearest at 24 is not one rounding (k = 1): expect sat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 63) m) #b1))
(assert (not (= ((_ to_fp_unsigned 8 24) RNE (bvshl (bvor (bvlshr m #x0000000000000027) (ite (= ((_ extract 38 0) m) #b000000000000000000000000000000000000000) #x0000000000000000 #x0000000000000001)) #x0000000000000027)) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: without the sticky bit the form is not the rounding of m: expect sat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 63) m) #b1))
(assert (not (= ((_ to_fp 8 24) RNE (fp.sub RNE (rto.bitsToDouble (bvor (bvxor m (bvand (bvxor m (bvlshr m #x000000000000000c)) (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)))) (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000)))) (rto.bitsToDouble (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000))))) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: two roundings to nearest, to a double then to a float, are not the rounding of m: expect sat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 63) m) #b1))
(assert (not (= ((_ to_fp 8 24) RNE ((_ to_fp_unsigned 11 53) RNE m)) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: with a shift of 11 the form is wrong on the top binade, where t reaches 2^52: expect sat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 63) m) #b1))
(assert (not (= ((_ to_fp 8 24) RNE (fp.sub RNE (rto.bitsToDouble (bvor (bvxor m (bvand (bvxor m (bvor (bvlshr m #x000000000000000b) (bvlshr (bvadd (bvand m #x00000000000007ff) #x00000000000007ff) #x000000000000000b))) (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)))) (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00b0000000000000)))) (rto.bitsToDouble (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00b0000000000000))))) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: with the small arm up to 2^53 the form is wrong there: expect sat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 53) m) #b00000000000))
(assert (not (= ((_ to_fp 8 24) RNE (fp.sub RNE (rto.bitsToDouble (bvor (bvxor m (bvand (bvxor m (bvor (bvlshr m #x000000000000000c) (bvlshr (bvadd (bvand m #x0000000000000fff) #x0000000000000fff) #x000000000000000c))) (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000035) #x7fffffffffffffff) #x000000000000003f)))) (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000035) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000)))) (rto.bitsToDouble (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000035) #x7fffffffffffffff) #x000000000000003f)) #x00c0000000000000))))) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)

(echo "l2f: the theorem's slack: with a shift of 13 the form is still right on [2^52, 2^53): expect unsat")
(reset)
(set-logic ALL)
(define-fun rto.M () Float64 ((_ to_fp 11 53) #x4338000000000000))
(define-fun rto.P52 () Float64 ((_ to_fp 11 53) #x4330000000000000))
(define-fun rto.P32 () Float64 ((_ to_fp 11 53) #x41f0000000000000))
(define-fun rto.bitsToDouble ((x (_ BitVec 64))) Float64 ((_ to_fp 11 53) x))
(declare-const m (_ BitVec 64))
(assert (= ((_ extract 63 52) m) #b000000000001))
(assert (not (= ((_ to_fp 8 24) RNE (fp.sub RNE (rto.bitsToDouble (bvor (bvxor m (bvand (bvxor m (bvor (bvlshr m #x000000000000000d) (bvlshr (bvadd (bvand m #x0000000000001fff) #x0000000000001fff) #x000000000000000d))) (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)))) (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00d0000000000000)))) (rto.bitsToDouble (bvadd #x4330000000000000 (bvand (bvneg (bvlshr (bvadd (bvlshr m #x0000000000000034) #x7fffffffffffffff) #x000000000000003f)) #x00d0000000000000))))) ((_ to_fp_unsigned 8 24) RNE m))))
(check-sat)
