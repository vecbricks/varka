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

; The narrowings of a bigint (VARKA-332). For a target of w bits, 8, 16 or 32:
;
;   LEGACY  kernel(x) = narrow_w(l2i(x))      L2I, then I2B or I2S at the store
;   ANSI    declines where x < min_w || x > max_w     the range guard's two compares
;
; where narrow_w(v) is the sign extension of v's low w bits, Java's (byte), (short) and
; (int) narrowing, and min_w and max_w are the target's range (VarkaTypes.integralRange,
; read here). Spark answers (byte) (int) x under LEGACY (Cast.castToByte) and raises
; CAST_OVERFLOW under ANSI exactly where x != (byte) x (CastUtils.longToByteExact and its
; kin, LongExactNumeric.toInt). The claims, for every long: the kernel's wrap is the
; reduction of x mod 2^w, and the guard holds exactly where x differs from it. The sat
; checks place both ends of the range inside it and one past each, and the long's ends,
; outside; the neighbours move a compare's end by one, which is wrong there.
;
; Rendered by VarkaProofFiles. VarkaProofFilesSuite fails when this file differs from its
; rendering, and VARKA_PROOFS_REGEN=true build/sbt 'catalyst/testOnly *VarkaProofFilesSuite'
; rewrites it. Run by dev/varka_prove.sh, which inserts java.smt2 after the set-logic line.

(set-logic QF_NIA)

(echo "casts: a bigint narrowed to byte is its reduction mod 2^8: expect unsat")
(push 1)
(declare-const x Int)
(assert (jlong.in x))
(assert (not (= (- (mod (+ (l2i x) 128) 256) 128) (- (mod (+ x 128) 256) 128))))
(check-sat)
(pop 1)

(echo "casts: the byte guard holds exactly where the bigint is not its narrowing: expect unsat")
(push 1)
(declare-const x Int)
(assert (jlong.in x))
(assert (not (= (or (< x (- 128)) (> x 127)) (not (= x (- (mod (+ x 128) 256) 128))))))
(check-sat)
(pop 1)

(echo "casts: both ends of the byte range are inside the guard: expect sat")
(push 1)
(declare-const lo Int)
(declare-const hi Int)
(assert (= lo (- 128)))
(assert (= hi 127))
(assert (= lo (- (mod (+ lo 128) 256) 128)))
(assert (= hi (- (mod (+ hi 128) 256) 128)))
(check-sat)
(pop 1)

(echo "casts: one past each end of the byte range, and the long's ends, are outside it: expect sat")
(push 1)
(declare-const a Int)
(declare-const b Int)
(assert (= a (- 129)))
(assert (= b 128))
(assert (not (= a (- (mod (+ a 128) 256) 128))))
(assert (not (= b (- (mod (+ b 128) 256) 128))))
(assert (not (= jlong.MIN (- (mod (+ jlong.MIN 128) 256) 128))))
(assert (not (= jlong.MAX (- (mod (+ jlong.MAX 128) 256) 128))))
(check-sat)
(pop 1)

(echo "casts: with the bottom compare one higher the byte guard is wrong: expect sat")
(push 1)
(declare-const x Int)
(assert (jlong.in x))
(assert (not (= (or (< x (- 127)) (> x 127)) (not (= x (- (mod (+ x 128) 256) 128))))))
(check-sat)
(pop 1)

(echo "casts: with the top compare one lower the byte guard is wrong: expect sat")
(push 1)
(declare-const x Int)
(assert (jlong.in x))
(assert (not (= (or (< x (- 128)) (> x 126)) (not (= x (- (mod (+ x 128) 256) 128))))))
(check-sat)
(pop 1)

(echo "casts: a bigint narrowed to short is its reduction mod 2^16: expect unsat")
(push 1)
(declare-const x Int)
(assert (jlong.in x))
(assert (not (= (- (mod (+ (l2i x) 32768) 65536) 32768) (- (mod (+ x 32768) 65536) 32768))))
(check-sat)
(pop 1)

(echo "casts: the short guard holds exactly where the bigint is not its narrowing: expect unsat")
(push 1)
(declare-const x Int)
(assert (jlong.in x))
(assert (not (= (or (< x (- 32768)) (> x 32767)) (not (= x (- (mod (+ x 32768) 65536) 32768))))))
(check-sat)
(pop 1)

(echo "casts: both ends of the short range are inside the guard: expect sat")
(push 1)
(declare-const lo Int)
(declare-const hi Int)
(assert (= lo (- 32768)))
(assert (= hi 32767))
(assert (= lo (- (mod (+ lo 32768) 65536) 32768)))
(assert (= hi (- (mod (+ hi 32768) 65536) 32768)))
(check-sat)
(pop 1)

(echo "casts: one past each end of the short range, and the long's ends, are outside it: expect sat")
(push 1)
(declare-const a Int)
(declare-const b Int)
(assert (= a (- 32769)))
(assert (= b 32768))
(assert (not (= a (- (mod (+ a 32768) 65536) 32768))))
(assert (not (= b (- (mod (+ b 32768) 65536) 32768))))
(assert (not (= jlong.MIN (- (mod (+ jlong.MIN 32768) 65536) 32768))))
(assert (not (= jlong.MAX (- (mod (+ jlong.MAX 32768) 65536) 32768))))
(check-sat)
(pop 1)

(echo "casts: with the bottom compare one higher the short guard is wrong: expect sat")
(push 1)
(declare-const x Int)
(assert (jlong.in x))
(assert (not (= (or (< x (- 32767)) (> x 32767)) (not (= x (- (mod (+ x 32768) 65536) 32768))))))
(check-sat)
(pop 1)

(echo "casts: with the top compare one lower the short guard is wrong: expect sat")
(push 1)
(declare-const x Int)
(assert (jlong.in x))
(assert (not (= (or (< x (- 32768)) (> x 32766)) (not (= x (- (mod (+ x 32768) 65536) 32768))))))
(check-sat)
(pop 1)

(echo "casts: a bigint narrowed to int is its reduction mod 2^32: expect unsat")
(push 1)
(declare-const x Int)
(assert (jlong.in x))
(assert (not (= (l2i x) (- (mod (+ x 2147483648) 4294967296) 2147483648))))
(check-sat)
(pop 1)

(echo "casts: the int guard holds exactly where the bigint is not its narrowing: expect unsat")
(push 1)
(declare-const x Int)
(assert (jlong.in x))
(assert (not (= (or (< x (- 2147483648)) (> x 2147483647)) (not (= x (- (mod (+ x 2147483648) 4294967296) 2147483648))))))
(check-sat)
(pop 1)

(echo "casts: both ends of the int range are inside the guard: expect sat")
(push 1)
(declare-const lo Int)
(declare-const hi Int)
(assert (= lo (- 2147483648)))
(assert (= hi 2147483647))
(assert (= lo (- (mod (+ lo 2147483648) 4294967296) 2147483648)))
(assert (= hi (- (mod (+ hi 2147483648) 4294967296) 2147483648)))
(check-sat)
(pop 1)

(echo "casts: one past each end of the int range, and the long's ends, are outside it: expect sat")
(push 1)
(declare-const a Int)
(declare-const b Int)
(assert (= a (- 2147483649)))
(assert (= b 2147483648))
(assert (not (= a (- (mod (+ a 2147483648) 4294967296) 2147483648))))
(assert (not (= b (- (mod (+ b 2147483648) 4294967296) 2147483648))))
(assert (not (= jlong.MIN (- (mod (+ jlong.MIN 2147483648) 4294967296) 2147483648))))
(assert (not (= jlong.MAX (- (mod (+ jlong.MAX 2147483648) 4294967296) 2147483648))))
(check-sat)
(pop 1)

(echo "casts: with the bottom compare one higher the int guard is wrong: expect sat")
(push 1)
(declare-const x Int)
(assert (jlong.in x))
(assert (not (= (or (< x (- 2147483647)) (> x 2147483647)) (not (= x (- (mod (+ x 2147483648) 4294967296) 2147483648))))))
(check-sat)
(pop 1)

(echo "casts: with the top compare one lower the int guard is wrong: expect sat")
(push 1)
(declare-const x Int)
(assert (jlong.in x))
(assert (not (= (or (< x (- 2147483648)) (> x 2147483646)) (not (= x (- (mod (+ x 2147483648) 4294967296) 2147483648))))))
(check-sat)
(pop 1)
