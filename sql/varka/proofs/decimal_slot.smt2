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

; The decimal leaf's digit check (VarkaVectorWalk.emitDecimalLoad, VARKA-347). Per lane,
; lo the slot's low word as a long and B = 10^p - 1 the precision's largest unscaled value
; (DecimalColumn.bound), the walk condemns the lane where
;
;   Long.compareUnsigned(lo + B, 2 * B) > 0        LongVector.lanewise ADD, compare UGT
;
; in place of the two signed compares lo > B || lo < -B. Over 64-bit vectors, whose bvadd
; and bvugt are Java's long + and the unsigned compare exactly: the two agree for every
; long at every precision from 1 to MAX_LONG_PRECISION, and a comparand one off either way
; disagrees somewhere. A lo inside [-B, B] moves to [0, 2B] without wrapping; one above B
; lands past 2B, wrapping or not; one below -B wraps to at least 2^63 + B, past 2B. So the
; identity holds for any bound from 0 to 2^63 - 1, which the last check states over a
; symbolic B. The file declares ALL so that the prelude, which it does not use, can be
; inserted.
;
; Rendered by VarkaProofFiles. VarkaProofFilesSuite fails when this file differs from its
; rendering, and VARKA_PROOFS_REGEN=true build/sbt 'catalyst/testOnly *VarkaProofFilesSuite'
; rewrites it. Run by dev/varka_prove.sh, which inserts java.smt2 after the set-logic line.

(set-logic ALL)

(define-fun dec.signed ((lo (_ BitVec 64)) (b (_ BitVec 64))) Bool
  (or (bvsgt lo b) (bvslt lo (bvneg b))))
(define-fun dec.unsigned ((lo (_ BitVec 64)) (b (_ BitVec 64)) (c (_ BitVec 64))) Bool
  (bvugt (bvadd lo b) c))

(echo "precision 1: (lo + 9) UGT 18 is lo outside [-9, 9] for every long: expect unsat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x0000000000000009 #x0000000000000012) (dec.signed lo #x0000000000000009))))
(check-sat)
(pop 1)

(echo "and with the comparand 17 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x0000000000000009 #x0000000000000011) (dec.signed lo #x0000000000000009))))
(check-sat)
(pop 1)

(echo "and with the comparand 19 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x0000000000000009 #x0000000000000013) (dec.signed lo #x0000000000000009))))
(check-sat)
(pop 1)

(echo "precision 2: (lo + 99) UGT 198 is lo outside [-99, 99] for every long: expect unsat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x0000000000000063 #x00000000000000c6) (dec.signed lo #x0000000000000063))))
(check-sat)
(pop 1)

(echo "and with the comparand 197 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x0000000000000063 #x00000000000000c5) (dec.signed lo #x0000000000000063))))
(check-sat)
(pop 1)

(echo "and with the comparand 199 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x0000000000000063 #x00000000000000c7) (dec.signed lo #x0000000000000063))))
(check-sat)
(pop 1)

(echo "precision 3: (lo + 999) UGT 1998 is lo outside [-999, 999] for every long: expect unsat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x00000000000003e7 #x00000000000007ce) (dec.signed lo #x00000000000003e7))))
(check-sat)
(pop 1)

(echo "and with the comparand 1997 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x00000000000003e7 #x00000000000007cd) (dec.signed lo #x00000000000003e7))))
(check-sat)
(pop 1)

(echo "and with the comparand 1999 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x00000000000003e7 #x00000000000007cf) (dec.signed lo #x00000000000003e7))))
(check-sat)
(pop 1)

(echo "precision 4: (lo + 9999) UGT 19998 is lo outside [-9999, 9999] for every long: expect unsat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x000000000000270f #x0000000000004e1e) (dec.signed lo #x000000000000270f))))
(check-sat)
(pop 1)

(echo "and with the comparand 19997 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x000000000000270f #x0000000000004e1d) (dec.signed lo #x000000000000270f))))
(check-sat)
(pop 1)

(echo "and with the comparand 19999 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x000000000000270f #x0000000000004e1f) (dec.signed lo #x000000000000270f))))
(check-sat)
(pop 1)

(echo "precision 5: (lo + 99999) UGT 199998 is lo outside [-99999, 99999] for every long: expect unsat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x000000000001869f #x0000000000030d3e) (dec.signed lo #x000000000001869f))))
(check-sat)
(pop 1)

(echo "and with the comparand 199997 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x000000000001869f #x0000000000030d3d) (dec.signed lo #x000000000001869f))))
(check-sat)
(pop 1)

(echo "and with the comparand 199999 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x000000000001869f #x0000000000030d3f) (dec.signed lo #x000000000001869f))))
(check-sat)
(pop 1)

(echo "precision 6: (lo + 999999) UGT 1999998 is lo outside [-999999, 999999] for every long: expect unsat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x00000000000f423f #x00000000001e847e) (dec.signed lo #x00000000000f423f))))
(check-sat)
(pop 1)

(echo "and with the comparand 1999997 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x00000000000f423f #x00000000001e847d) (dec.signed lo #x00000000000f423f))))
(check-sat)
(pop 1)

(echo "and with the comparand 1999999 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x00000000000f423f #x00000000001e847f) (dec.signed lo #x00000000000f423f))))
(check-sat)
(pop 1)

(echo "precision 7: (lo + 9999999) UGT 19999998 is lo outside [-9999999, 9999999] for every long: expect unsat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x000000000098967f #x0000000001312cfe) (dec.signed lo #x000000000098967f))))
(check-sat)
(pop 1)

(echo "and with the comparand 19999997 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x000000000098967f #x0000000001312cfd) (dec.signed lo #x000000000098967f))))
(check-sat)
(pop 1)

(echo "and with the comparand 19999999 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x000000000098967f #x0000000001312cff) (dec.signed lo #x000000000098967f))))
(check-sat)
(pop 1)

(echo "precision 8: (lo + 99999999) UGT 199999998 is lo outside [-99999999, 99999999] for every long: expect unsat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x0000000005f5e0ff #x000000000bebc1fe) (dec.signed lo #x0000000005f5e0ff))))
(check-sat)
(pop 1)

(echo "and with the comparand 199999997 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x0000000005f5e0ff #x000000000bebc1fd) (dec.signed lo #x0000000005f5e0ff))))
(check-sat)
(pop 1)

(echo "and with the comparand 199999999 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x0000000005f5e0ff #x000000000bebc1ff) (dec.signed lo #x0000000005f5e0ff))))
(check-sat)
(pop 1)

(echo "precision 9: (lo + 999999999) UGT 1999999998 is lo outside [-999999999, 999999999] for every long: expect unsat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x000000003b9ac9ff #x00000000773593fe) (dec.signed lo #x000000003b9ac9ff))))
(check-sat)
(pop 1)

(echo "and with the comparand 1999999997 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x000000003b9ac9ff #x00000000773593fd) (dec.signed lo #x000000003b9ac9ff))))
(check-sat)
(pop 1)

(echo "and with the comparand 1999999999 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x000000003b9ac9ff #x00000000773593ff) (dec.signed lo #x000000003b9ac9ff))))
(check-sat)
(pop 1)

(echo "precision 10: (lo + 9999999999) UGT 19999999998 is lo outside [-9999999999, 9999999999] for every long: expect unsat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x00000002540be3ff #x00000004a817c7fe) (dec.signed lo #x00000002540be3ff))))
(check-sat)
(pop 1)

(echo "and with the comparand 19999999997 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x00000002540be3ff #x00000004a817c7fd) (dec.signed lo #x00000002540be3ff))))
(check-sat)
(pop 1)

(echo "and with the comparand 19999999999 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x00000002540be3ff #x00000004a817c7ff) (dec.signed lo #x00000002540be3ff))))
(check-sat)
(pop 1)

(echo "precision 11: (lo + 99999999999) UGT 199999999998 is lo outside [-99999999999, 99999999999] for every long: expect unsat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x000000174876e7ff #x0000002e90edcffe) (dec.signed lo #x000000174876e7ff))))
(check-sat)
(pop 1)

(echo "and with the comparand 199999999997 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x000000174876e7ff #x0000002e90edcffd) (dec.signed lo #x000000174876e7ff))))
(check-sat)
(pop 1)

(echo "and with the comparand 199999999999 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x000000174876e7ff #x0000002e90edcfff) (dec.signed lo #x000000174876e7ff))))
(check-sat)
(pop 1)

(echo "precision 12: (lo + 999999999999) UGT 1999999999998 is lo outside [-999999999999, 999999999999] for every long: expect unsat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x000000e8d4a50fff #x000001d1a94a1ffe) (dec.signed lo #x000000e8d4a50fff))))
(check-sat)
(pop 1)

(echo "and with the comparand 1999999999997 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x000000e8d4a50fff #x000001d1a94a1ffd) (dec.signed lo #x000000e8d4a50fff))))
(check-sat)
(pop 1)

(echo "and with the comparand 1999999999999 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x000000e8d4a50fff #x000001d1a94a1fff) (dec.signed lo #x000000e8d4a50fff))))
(check-sat)
(pop 1)

(echo "precision 13: (lo + 9999999999999) UGT 19999999999998 is lo outside [-9999999999999, 9999999999999] for every long: expect unsat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x000009184e729fff #x000012309ce53ffe) (dec.signed lo #x000009184e729fff))))
(check-sat)
(pop 1)

(echo "and with the comparand 19999999999997 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x000009184e729fff #x000012309ce53ffd) (dec.signed lo #x000009184e729fff))))
(check-sat)
(pop 1)

(echo "and with the comparand 19999999999999 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x000009184e729fff #x000012309ce53fff) (dec.signed lo #x000009184e729fff))))
(check-sat)
(pop 1)

(echo "precision 14: (lo + 99999999999999) UGT 199999999999998 is lo outside [-99999999999999, 99999999999999] for every long: expect unsat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x00005af3107a3fff #x0000b5e620f47ffe) (dec.signed lo #x00005af3107a3fff))))
(check-sat)
(pop 1)

(echo "and with the comparand 199999999999997 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x00005af3107a3fff #x0000b5e620f47ffd) (dec.signed lo #x00005af3107a3fff))))
(check-sat)
(pop 1)

(echo "and with the comparand 199999999999999 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x00005af3107a3fff #x0000b5e620f47fff) (dec.signed lo #x00005af3107a3fff))))
(check-sat)
(pop 1)

(echo "precision 15: (lo + 999999999999999) UGT 1999999999999998 is lo outside [-999999999999999, 999999999999999] for every long: expect unsat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x00038d7ea4c67fff #x00071afd498cfffe) (dec.signed lo #x00038d7ea4c67fff))))
(check-sat)
(pop 1)

(echo "and with the comparand 1999999999999997 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x00038d7ea4c67fff #x00071afd498cfffd) (dec.signed lo #x00038d7ea4c67fff))))
(check-sat)
(pop 1)

(echo "and with the comparand 1999999999999999 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x00038d7ea4c67fff #x00071afd498cffff) (dec.signed lo #x00038d7ea4c67fff))))
(check-sat)
(pop 1)

(echo "precision 16: (lo + 9999999999999999) UGT 19999999999999998 is lo outside [-9999999999999999, 9999999999999999] for every long: expect unsat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x002386f26fc0ffff #x00470de4df81fffe) (dec.signed lo #x002386f26fc0ffff))))
(check-sat)
(pop 1)

(echo "and with the comparand 19999999999999997 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x002386f26fc0ffff #x00470de4df81fffd) (dec.signed lo #x002386f26fc0ffff))))
(check-sat)
(pop 1)

(echo "and with the comparand 19999999999999999 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x002386f26fc0ffff #x00470de4df81ffff) (dec.signed lo #x002386f26fc0ffff))))
(check-sat)
(pop 1)

(echo "precision 17: (lo + 99999999999999999) UGT 199999999999999998 is lo outside [-99999999999999999, 99999999999999999] for every long: expect unsat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x016345785d89ffff #x02c68af0bb13fffe) (dec.signed lo #x016345785d89ffff))))
(check-sat)
(pop 1)

(echo "and with the comparand 199999999999999997 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x016345785d89ffff #x02c68af0bb13fffd) (dec.signed lo #x016345785d89ffff))))
(check-sat)
(pop 1)

(echo "and with the comparand 199999999999999999 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x016345785d89ffff #x02c68af0bb13ffff) (dec.signed lo #x016345785d89ffff))))
(check-sat)
(pop 1)

(echo "precision 18: (lo + 999999999999999999) UGT 1999999999999999998 is lo outside [-999999999999999999, 999999999999999999] for every long: expect unsat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x0de0b6b3a763ffff #x1bc16d674ec7fffe) (dec.signed lo #x0de0b6b3a763ffff))))
(check-sat)
(pop 1)

(echo "and with the comparand 1999999999999999997 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x0de0b6b3a763ffff #x1bc16d674ec7fffd) (dec.signed lo #x0de0b6b3a763ffff))))
(check-sat)
(pop 1)

(echo "and with the comparand 1999999999999999999 it is not: expect sat")
(push 1)
(declare-const lo (_ BitVec 64))
(assert (not (= (dec.unsigned lo #x0de0b6b3a763ffff #x1bc16d674ec7ffff) (dec.signed lo #x0de0b6b3a763ffff))))
(check-sat)
(pop 1)

(echo "and for every bound B from 0 to 2^63 - 1, with 2B wrapped as the code's long multiply wraps it: expect unsat")
(push 1)
(declare-const lo (_ BitVec 64))
(declare-const b (_ BitVec 64))
(assert (bvsge b #x0000000000000000))
(assert (not (= (dec.unsigned lo b (bvmul b #x0000000000000002)) (dec.signed lo b))))
(check-sat)
(pop 1)
