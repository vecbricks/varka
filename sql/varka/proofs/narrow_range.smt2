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

; The checked narrow forms on the widened arm (VARKA-330, VARKA-329.md 4.2). The int
; lane's result of two narrow operands is exact, so the node's own check is two compares
; against the type's range, whose ends are the lane's (Lane.mostNegative and
; mostPositive, read here):
;
;   outside(r) = r < min || r > max
;
; The claim: outside(r) holds at exactly the ints that are not values of the narrow
; type, which is r differing from the sign extension of its low w bits. The sat checks
; place both ends inside and one past each end outside, and the neighbours narrow the
; compares to <= and >=, which is wrong at the end each names. A checked narrow multiply
; is formed in the int lane on either arm (VarkaVectorIR.holdsCheckedNarrowMultiply), so
; these checks cover it too.
;
; A NULL range guard clears the same mask from its validity word instead of declining
; the batch, the word being one bit a lane:
;
;   word' = word & ~toLong(outside(r))
;
; The last claims: a lane is valid in word' exactly when it was valid in word and in
; range, so an in-range lane stays valid, an out-of-range one becomes null and a null
; stays null, and the bits past the lanes are the word's. The neighbours clear by the
; mask itself, and drop the word.
;
; The logic is ALL because the file reasons over bit-vectors.
;
; Rendered by VarkaProofFiles. VarkaProofFilesSuite fails when this file differs from its
; rendering, and VARKA_PROOFS_REGEN=true build/sbt 'catalyst/testOnly *VarkaProofFilesSuite'
; rewrites it. Run by dev/varka_prove.sh, which inserts java.smt2 after the set-logic line.

(set-logic ALL)

(echo "narrow_range: the widened arm's mask is the ints that are not byte values: expect unsat")
(push 1)
(declare-const r (_ BitVec 32))
(assert (not (= (or (bvslt r #xffffff80) (bvsgt r #x0000007f)) (not (= r ((_ sign_extend 24) ((_ extract 7 0) r)))))))
(check-sat)
(pop 1)

(echo "narrow_range: the byte range admits both of its ends: expect sat")
(push 1)
(declare-const bottom (_ BitVec 32))
(declare-const top (_ BitVec 32))
(assert (= bottom #xffffff80))
(assert (= top #x0000007f))
(assert (not (or (bvslt bottom #xffffff80) (bvsgt bottom #x0000007f))))
(assert (not (or (bvslt top #xffffff80) (bvsgt top #x0000007f))))
(check-sat)
(pop 1)

(echo "narrow_range: one past each end of the byte is outside it: expect sat")
(push 1)
(declare-const below (_ BitVec 32))
(declare-const above (_ BitVec 32))
(assert (= below #xffffff7f))
(assert (= above #x00000080))
(assert (or (bvslt below #xffffff80) (bvsgt below #x0000007f)))
(assert (or (bvslt above #xffffff80) (bvsgt above #x0000007f)))
(check-sat)
(pop 1)

(echo "narrow_range: with <= at the bottom the mask is not the byte's values: expect sat")
(push 1)
(declare-const r (_ BitVec 32))
(assert (= r #xffffff80))
(assert (not (= (or (bvsle r #xffffff80) (bvsgt r #x0000007f)) (or (bvslt r #xffffff80) (bvsgt r #x0000007f)))))
(check-sat)
(pop 1)

(echo "narrow_range: with >= at the top the mask is not the byte's values: expect sat")
(push 1)
(declare-const r (_ BitVec 32))
(assert (= r #x0000007f))
(assert (not (= (or (bvslt r #xffffff80) (bvsge r #x0000007f)) (or (bvslt r #xffffff80) (bvsgt r #x0000007f)))))
(check-sat)
(pop 1)

(echo "narrow_range: the widened arm's mask is the ints that are not short values: expect unsat")
(push 1)
(declare-const r (_ BitVec 32))
(assert (not (= (or (bvslt r #xffff8000) (bvsgt r #x00007fff)) (not (= r ((_ sign_extend 16) ((_ extract 15 0) r)))))))
(check-sat)
(pop 1)

(echo "narrow_range: the short range admits both of its ends: expect sat")
(push 1)
(declare-const bottom (_ BitVec 32))
(declare-const top (_ BitVec 32))
(assert (= bottom #xffff8000))
(assert (= top #x00007fff))
(assert (not (or (bvslt bottom #xffff8000) (bvsgt bottom #x00007fff))))
(assert (not (or (bvslt top #xffff8000) (bvsgt top #x00007fff))))
(check-sat)
(pop 1)

(echo "narrow_range: one past each end of the short is outside it: expect sat")
(push 1)
(declare-const below (_ BitVec 32))
(declare-const above (_ BitVec 32))
(assert (= below #xffff7fff))
(assert (= above #x00008000))
(assert (or (bvslt below #xffff8000) (bvsgt below #x00007fff)))
(assert (or (bvslt above #xffff8000) (bvsgt above #x00007fff)))
(check-sat)
(pop 1)

(echo "narrow_range: with <= at the bottom the mask is not the short's values: expect sat")
(push 1)
(declare-const r (_ BitVec 32))
(assert (= r #xffff8000))
(assert (not (= (or (bvsle r #xffff8000) (bvsgt r #x00007fff)) (or (bvslt r #xffff8000) (bvsgt r #x00007fff)))))
(check-sat)
(pop 1)

(echo "narrow_range: with >= at the top the mask is not the short's values: expect sat")
(push 1)
(declare-const r (_ BitVec 32))
(assert (= r #x00007fff))
(assert (not (= (or (bvslt r #xffff8000) (bvsge r #x00007fff)) (or (bvslt r #xffff8000) (bvsgt r #x00007fff)))))
(check-sat)
(pop 1)

(echo "narrow_range: a NULL guard keeps a lane valid exactly when it was valid and in the byte range: expect unsat")
(push 1)
(declare-const word (_ BitVec 64))
(declare-const r15 (_ BitVec 32))
(declare-const r14 (_ BitVec 32))
(declare-const r13 (_ BitVec 32))
(declare-const r12 (_ BitVec 32))
(declare-const r11 (_ BitVec 32))
(declare-const r10 (_ BitVec 32))
(declare-const r9 (_ BitVec 32))
(declare-const r8 (_ BitVec 32))
(declare-const r7 (_ BitVec 32))
(declare-const r6 (_ BitVec 32))
(declare-const r5 (_ BitVec 32))
(declare-const r4 (_ BitVec 32))
(declare-const r3 (_ BitVec 32))
(declare-const r2 (_ BitVec 32))
(declare-const r1 (_ BitVec 32))
(declare-const r0 (_ BitVec 32))
(define-fun mask () (_ BitVec 64) ((_ zero_extend 48) (concat (ite (or (bvslt r15 #xffffff80) (bvsgt r15 #x0000007f)) #b1 #b0) (ite (or (bvslt r14 #xffffff80) (bvsgt r14 #x0000007f)) #b1 #b0) (ite (or (bvslt r13 #xffffff80) (bvsgt r13 #x0000007f)) #b1 #b0) (ite (or (bvslt r12 #xffffff80) (bvsgt r12 #x0000007f)) #b1 #b0) (ite (or (bvslt r11 #xffffff80) (bvsgt r11 #x0000007f)) #b1 #b0) (ite (or (bvslt r10 #xffffff80) (bvsgt r10 #x0000007f)) #b1 #b0) (ite (or (bvslt r9 #xffffff80) (bvsgt r9 #x0000007f)) #b1 #b0) (ite (or (bvslt r8 #xffffff80) (bvsgt r8 #x0000007f)) #b1 #b0) (ite (or (bvslt r7 #xffffff80) (bvsgt r7 #x0000007f)) #b1 #b0) (ite (or (bvslt r6 #xffffff80) (bvsgt r6 #x0000007f)) #b1 #b0) (ite (or (bvslt r5 #xffffff80) (bvsgt r5 #x0000007f)) #b1 #b0) (ite (or (bvslt r4 #xffffff80) (bvsgt r4 #x0000007f)) #b1 #b0) (ite (or (bvslt r3 #xffffff80) (bvsgt r3 #x0000007f)) #b1 #b0) (ite (or (bvslt r2 #xffffff80) (bvsgt r2 #x0000007f)) #b1 #b0) (ite (or (bvslt r1 #xffffff80) (bvsgt r1 #x0000007f)) #b1 #b0) (ite (or (bvslt r0 #xffffff80) (bvsgt r0 #x0000007f)) #b1 #b0))))
(assert (not (and (= (= ((_ extract 0 0) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 0 0) word) #b1) (not (or (bvslt r0 #xffffff80) (bvsgt r0 #x0000007f))))) (= (= ((_ extract 1 1) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 1 1) word) #b1) (not (or (bvslt r1 #xffffff80) (bvsgt r1 #x0000007f))))) (= (= ((_ extract 2 2) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 2 2) word) #b1) (not (or (bvslt r2 #xffffff80) (bvsgt r2 #x0000007f))))) (= (= ((_ extract 3 3) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 3 3) word) #b1) (not (or (bvslt r3 #xffffff80) (bvsgt r3 #x0000007f))))) (= (= ((_ extract 4 4) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 4 4) word) #b1) (not (or (bvslt r4 #xffffff80) (bvsgt r4 #x0000007f))))) (= (= ((_ extract 5 5) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 5 5) word) #b1) (not (or (bvslt r5 #xffffff80) (bvsgt r5 #x0000007f))))) (= (= ((_ extract 6 6) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 6 6) word) #b1) (not (or (bvslt r6 #xffffff80) (bvsgt r6 #x0000007f))))) (= (= ((_ extract 7 7) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 7 7) word) #b1) (not (or (bvslt r7 #xffffff80) (bvsgt r7 #x0000007f))))) (= (= ((_ extract 8 8) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 8 8) word) #b1) (not (or (bvslt r8 #xffffff80) (bvsgt r8 #x0000007f))))) (= (= ((_ extract 9 9) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 9 9) word) #b1) (not (or (bvslt r9 #xffffff80) (bvsgt r9 #x0000007f))))) (= (= ((_ extract 10 10) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 10 10) word) #b1) (not (or (bvslt r10 #xffffff80) (bvsgt r10 #x0000007f))))) (= (= ((_ extract 11 11) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 11 11) word) #b1) (not (or (bvslt r11 #xffffff80) (bvsgt r11 #x0000007f))))) (= (= ((_ extract 12 12) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 12 12) word) #b1) (not (or (bvslt r12 #xffffff80) (bvsgt r12 #x0000007f))))) (= (= ((_ extract 13 13) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 13 13) word) #b1) (not (or (bvslt r13 #xffffff80) (bvsgt r13 #x0000007f))))) (= (= ((_ extract 14 14) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 14 14) word) #b1) (not (or (bvslt r14 #xffffff80) (bvsgt r14 #x0000007f))))) (= (= ((_ extract 15 15) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 15 15) word) #b1) (not (or (bvslt r15 #xffffff80) (bvsgt r15 #x0000007f))))) (= ((_ extract 63 16) (bvand word (bvnot mask))) ((_ extract 63 16) word)))))
(check-sat)
(pop 1)

(echo "narrow_range: clearing by the mask itself is not the byte NULL guard: expect sat")
(push 1)
(declare-const word (_ BitVec 64))
(declare-const r15 (_ BitVec 32))
(declare-const r14 (_ BitVec 32))
(declare-const r13 (_ BitVec 32))
(declare-const r12 (_ BitVec 32))
(declare-const r11 (_ BitVec 32))
(declare-const r10 (_ BitVec 32))
(declare-const r9 (_ BitVec 32))
(declare-const r8 (_ BitVec 32))
(declare-const r7 (_ BitVec 32))
(declare-const r6 (_ BitVec 32))
(declare-const r5 (_ BitVec 32))
(declare-const r4 (_ BitVec 32))
(declare-const r3 (_ BitVec 32))
(declare-const r2 (_ BitVec 32))
(declare-const r1 (_ BitVec 32))
(declare-const r0 (_ BitVec 32))
(define-fun mask () (_ BitVec 64) ((_ zero_extend 48) (concat (ite (or (bvslt r15 #xffffff80) (bvsgt r15 #x0000007f)) #b1 #b0) (ite (or (bvslt r14 #xffffff80) (bvsgt r14 #x0000007f)) #b1 #b0) (ite (or (bvslt r13 #xffffff80) (bvsgt r13 #x0000007f)) #b1 #b0) (ite (or (bvslt r12 #xffffff80) (bvsgt r12 #x0000007f)) #b1 #b0) (ite (or (bvslt r11 #xffffff80) (bvsgt r11 #x0000007f)) #b1 #b0) (ite (or (bvslt r10 #xffffff80) (bvsgt r10 #x0000007f)) #b1 #b0) (ite (or (bvslt r9 #xffffff80) (bvsgt r9 #x0000007f)) #b1 #b0) (ite (or (bvslt r8 #xffffff80) (bvsgt r8 #x0000007f)) #b1 #b0) (ite (or (bvslt r7 #xffffff80) (bvsgt r7 #x0000007f)) #b1 #b0) (ite (or (bvslt r6 #xffffff80) (bvsgt r6 #x0000007f)) #b1 #b0) (ite (or (bvslt r5 #xffffff80) (bvsgt r5 #x0000007f)) #b1 #b0) (ite (or (bvslt r4 #xffffff80) (bvsgt r4 #x0000007f)) #b1 #b0) (ite (or (bvslt r3 #xffffff80) (bvsgt r3 #x0000007f)) #b1 #b0) (ite (or (bvslt r2 #xffffff80) (bvsgt r2 #x0000007f)) #b1 #b0) (ite (or (bvslt r1 #xffffff80) (bvsgt r1 #x0000007f)) #b1 #b0) (ite (or (bvslt r0 #xffffff80) (bvsgt r0 #x0000007f)) #b1 #b0))))
(assert (not (and (= (= ((_ extract 0 0) (bvand word mask)) #b1) (and (= ((_ extract 0 0) word) #b1) (not (or (bvslt r0 #xffffff80) (bvsgt r0 #x0000007f))))) (= (= ((_ extract 1 1) (bvand word mask)) #b1) (and (= ((_ extract 1 1) word) #b1) (not (or (bvslt r1 #xffffff80) (bvsgt r1 #x0000007f))))) (= (= ((_ extract 2 2) (bvand word mask)) #b1) (and (= ((_ extract 2 2) word) #b1) (not (or (bvslt r2 #xffffff80) (bvsgt r2 #x0000007f))))) (= (= ((_ extract 3 3) (bvand word mask)) #b1) (and (= ((_ extract 3 3) word) #b1) (not (or (bvslt r3 #xffffff80) (bvsgt r3 #x0000007f))))) (= (= ((_ extract 4 4) (bvand word mask)) #b1) (and (= ((_ extract 4 4) word) #b1) (not (or (bvslt r4 #xffffff80) (bvsgt r4 #x0000007f))))) (= (= ((_ extract 5 5) (bvand word mask)) #b1) (and (= ((_ extract 5 5) word) #b1) (not (or (bvslt r5 #xffffff80) (bvsgt r5 #x0000007f))))) (= (= ((_ extract 6 6) (bvand word mask)) #b1) (and (= ((_ extract 6 6) word) #b1) (not (or (bvslt r6 #xffffff80) (bvsgt r6 #x0000007f))))) (= (= ((_ extract 7 7) (bvand word mask)) #b1) (and (= ((_ extract 7 7) word) #b1) (not (or (bvslt r7 #xffffff80) (bvsgt r7 #x0000007f))))) (= (= ((_ extract 8 8) (bvand word mask)) #b1) (and (= ((_ extract 8 8) word) #b1) (not (or (bvslt r8 #xffffff80) (bvsgt r8 #x0000007f))))) (= (= ((_ extract 9 9) (bvand word mask)) #b1) (and (= ((_ extract 9 9) word) #b1) (not (or (bvslt r9 #xffffff80) (bvsgt r9 #x0000007f))))) (= (= ((_ extract 10 10) (bvand word mask)) #b1) (and (= ((_ extract 10 10) word) #b1) (not (or (bvslt r10 #xffffff80) (bvsgt r10 #x0000007f))))) (= (= ((_ extract 11 11) (bvand word mask)) #b1) (and (= ((_ extract 11 11) word) #b1) (not (or (bvslt r11 #xffffff80) (bvsgt r11 #x0000007f))))) (= (= ((_ extract 12 12) (bvand word mask)) #b1) (and (= ((_ extract 12 12) word) #b1) (not (or (bvslt r12 #xffffff80) (bvsgt r12 #x0000007f))))) (= (= ((_ extract 13 13) (bvand word mask)) #b1) (and (= ((_ extract 13 13) word) #b1) (not (or (bvslt r13 #xffffff80) (bvsgt r13 #x0000007f))))) (= (= ((_ extract 14 14) (bvand word mask)) #b1) (and (= ((_ extract 14 14) word) #b1) (not (or (bvslt r14 #xffffff80) (bvsgt r14 #x0000007f))))) (= (= ((_ extract 15 15) (bvand word mask)) #b1) (and (= ((_ extract 15 15) word) #b1) (not (or (bvslt r15 #xffffff80) (bvsgt r15 #x0000007f))))) (= ((_ extract 63 16) (bvand word mask)) ((_ extract 63 16) word)))))
(check-sat)
(pop 1)

(echo "narrow_range: the byte NULL guard without the word revives a null lane: expect sat")
(push 1)
(declare-const word (_ BitVec 64))
(declare-const r15 (_ BitVec 32))
(declare-const r14 (_ BitVec 32))
(declare-const r13 (_ BitVec 32))
(declare-const r12 (_ BitVec 32))
(declare-const r11 (_ BitVec 32))
(declare-const r10 (_ BitVec 32))
(declare-const r9 (_ BitVec 32))
(declare-const r8 (_ BitVec 32))
(declare-const r7 (_ BitVec 32))
(declare-const r6 (_ BitVec 32))
(declare-const r5 (_ BitVec 32))
(declare-const r4 (_ BitVec 32))
(declare-const r3 (_ BitVec 32))
(declare-const r2 (_ BitVec 32))
(declare-const r1 (_ BitVec 32))
(declare-const r0 (_ BitVec 32))
(define-fun mask () (_ BitVec 64) ((_ zero_extend 48) (concat (ite (or (bvslt r15 #xffffff80) (bvsgt r15 #x0000007f)) #b1 #b0) (ite (or (bvslt r14 #xffffff80) (bvsgt r14 #x0000007f)) #b1 #b0) (ite (or (bvslt r13 #xffffff80) (bvsgt r13 #x0000007f)) #b1 #b0) (ite (or (bvslt r12 #xffffff80) (bvsgt r12 #x0000007f)) #b1 #b0) (ite (or (bvslt r11 #xffffff80) (bvsgt r11 #x0000007f)) #b1 #b0) (ite (or (bvslt r10 #xffffff80) (bvsgt r10 #x0000007f)) #b1 #b0) (ite (or (bvslt r9 #xffffff80) (bvsgt r9 #x0000007f)) #b1 #b0) (ite (or (bvslt r8 #xffffff80) (bvsgt r8 #x0000007f)) #b1 #b0) (ite (or (bvslt r7 #xffffff80) (bvsgt r7 #x0000007f)) #b1 #b0) (ite (or (bvslt r6 #xffffff80) (bvsgt r6 #x0000007f)) #b1 #b0) (ite (or (bvslt r5 #xffffff80) (bvsgt r5 #x0000007f)) #b1 #b0) (ite (or (bvslt r4 #xffffff80) (bvsgt r4 #x0000007f)) #b1 #b0) (ite (or (bvslt r3 #xffffff80) (bvsgt r3 #x0000007f)) #b1 #b0) (ite (or (bvslt r2 #xffffff80) (bvsgt r2 #x0000007f)) #b1 #b0) (ite (or (bvslt r1 #xffffff80) (bvsgt r1 #x0000007f)) #b1 #b0) (ite (or (bvslt r0 #xffffff80) (bvsgt r0 #x0000007f)) #b1 #b0))))
(assert (not (and (= (= ((_ extract 0 0) (bvnot mask)) #b1) (and (= ((_ extract 0 0) word) #b1) (not (or (bvslt r0 #xffffff80) (bvsgt r0 #x0000007f))))) (= (= ((_ extract 1 1) (bvnot mask)) #b1) (and (= ((_ extract 1 1) word) #b1) (not (or (bvslt r1 #xffffff80) (bvsgt r1 #x0000007f))))) (= (= ((_ extract 2 2) (bvnot mask)) #b1) (and (= ((_ extract 2 2) word) #b1) (not (or (bvslt r2 #xffffff80) (bvsgt r2 #x0000007f))))) (= (= ((_ extract 3 3) (bvnot mask)) #b1) (and (= ((_ extract 3 3) word) #b1) (not (or (bvslt r3 #xffffff80) (bvsgt r3 #x0000007f))))) (= (= ((_ extract 4 4) (bvnot mask)) #b1) (and (= ((_ extract 4 4) word) #b1) (not (or (bvslt r4 #xffffff80) (bvsgt r4 #x0000007f))))) (= (= ((_ extract 5 5) (bvnot mask)) #b1) (and (= ((_ extract 5 5) word) #b1) (not (or (bvslt r5 #xffffff80) (bvsgt r5 #x0000007f))))) (= (= ((_ extract 6 6) (bvnot mask)) #b1) (and (= ((_ extract 6 6) word) #b1) (not (or (bvslt r6 #xffffff80) (bvsgt r6 #x0000007f))))) (= (= ((_ extract 7 7) (bvnot mask)) #b1) (and (= ((_ extract 7 7) word) #b1) (not (or (bvslt r7 #xffffff80) (bvsgt r7 #x0000007f))))) (= (= ((_ extract 8 8) (bvnot mask)) #b1) (and (= ((_ extract 8 8) word) #b1) (not (or (bvslt r8 #xffffff80) (bvsgt r8 #x0000007f))))) (= (= ((_ extract 9 9) (bvnot mask)) #b1) (and (= ((_ extract 9 9) word) #b1) (not (or (bvslt r9 #xffffff80) (bvsgt r9 #x0000007f))))) (= (= ((_ extract 10 10) (bvnot mask)) #b1) (and (= ((_ extract 10 10) word) #b1) (not (or (bvslt r10 #xffffff80) (bvsgt r10 #x0000007f))))) (= (= ((_ extract 11 11) (bvnot mask)) #b1) (and (= ((_ extract 11 11) word) #b1) (not (or (bvslt r11 #xffffff80) (bvsgt r11 #x0000007f))))) (= (= ((_ extract 12 12) (bvnot mask)) #b1) (and (= ((_ extract 12 12) word) #b1) (not (or (bvslt r12 #xffffff80) (bvsgt r12 #x0000007f))))) (= (= ((_ extract 13 13) (bvnot mask)) #b1) (and (= ((_ extract 13 13) word) #b1) (not (or (bvslt r13 #xffffff80) (bvsgt r13 #x0000007f))))) (= (= ((_ extract 14 14) (bvnot mask)) #b1) (and (= ((_ extract 14 14) word) #b1) (not (or (bvslt r14 #xffffff80) (bvsgt r14 #x0000007f))))) (= (= ((_ extract 15 15) (bvnot mask)) #b1) (and (= ((_ extract 15 15) word) #b1) (not (or (bvslt r15 #xffffff80) (bvsgt r15 #x0000007f))))) (= ((_ extract 63 16) (bvnot mask)) ((_ extract 63 16) word)))))
(check-sat)
(pop 1)

(echo "narrow_range: a NULL guard keeps a lane valid exactly when it was valid and in the short range: expect unsat")
(push 1)
(declare-const word (_ BitVec 64))
(declare-const r15 (_ BitVec 32))
(declare-const r14 (_ BitVec 32))
(declare-const r13 (_ BitVec 32))
(declare-const r12 (_ BitVec 32))
(declare-const r11 (_ BitVec 32))
(declare-const r10 (_ BitVec 32))
(declare-const r9 (_ BitVec 32))
(declare-const r8 (_ BitVec 32))
(declare-const r7 (_ BitVec 32))
(declare-const r6 (_ BitVec 32))
(declare-const r5 (_ BitVec 32))
(declare-const r4 (_ BitVec 32))
(declare-const r3 (_ BitVec 32))
(declare-const r2 (_ BitVec 32))
(declare-const r1 (_ BitVec 32))
(declare-const r0 (_ BitVec 32))
(define-fun mask () (_ BitVec 64) ((_ zero_extend 48) (concat (ite (or (bvslt r15 #xffff8000) (bvsgt r15 #x00007fff)) #b1 #b0) (ite (or (bvslt r14 #xffff8000) (bvsgt r14 #x00007fff)) #b1 #b0) (ite (or (bvslt r13 #xffff8000) (bvsgt r13 #x00007fff)) #b1 #b0) (ite (or (bvslt r12 #xffff8000) (bvsgt r12 #x00007fff)) #b1 #b0) (ite (or (bvslt r11 #xffff8000) (bvsgt r11 #x00007fff)) #b1 #b0) (ite (or (bvslt r10 #xffff8000) (bvsgt r10 #x00007fff)) #b1 #b0) (ite (or (bvslt r9 #xffff8000) (bvsgt r9 #x00007fff)) #b1 #b0) (ite (or (bvslt r8 #xffff8000) (bvsgt r8 #x00007fff)) #b1 #b0) (ite (or (bvslt r7 #xffff8000) (bvsgt r7 #x00007fff)) #b1 #b0) (ite (or (bvslt r6 #xffff8000) (bvsgt r6 #x00007fff)) #b1 #b0) (ite (or (bvslt r5 #xffff8000) (bvsgt r5 #x00007fff)) #b1 #b0) (ite (or (bvslt r4 #xffff8000) (bvsgt r4 #x00007fff)) #b1 #b0) (ite (or (bvslt r3 #xffff8000) (bvsgt r3 #x00007fff)) #b1 #b0) (ite (or (bvslt r2 #xffff8000) (bvsgt r2 #x00007fff)) #b1 #b0) (ite (or (bvslt r1 #xffff8000) (bvsgt r1 #x00007fff)) #b1 #b0) (ite (or (bvslt r0 #xffff8000) (bvsgt r0 #x00007fff)) #b1 #b0))))
(assert (not (and (= (= ((_ extract 0 0) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 0 0) word) #b1) (not (or (bvslt r0 #xffff8000) (bvsgt r0 #x00007fff))))) (= (= ((_ extract 1 1) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 1 1) word) #b1) (not (or (bvslt r1 #xffff8000) (bvsgt r1 #x00007fff))))) (= (= ((_ extract 2 2) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 2 2) word) #b1) (not (or (bvslt r2 #xffff8000) (bvsgt r2 #x00007fff))))) (= (= ((_ extract 3 3) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 3 3) word) #b1) (not (or (bvslt r3 #xffff8000) (bvsgt r3 #x00007fff))))) (= (= ((_ extract 4 4) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 4 4) word) #b1) (not (or (bvslt r4 #xffff8000) (bvsgt r4 #x00007fff))))) (= (= ((_ extract 5 5) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 5 5) word) #b1) (not (or (bvslt r5 #xffff8000) (bvsgt r5 #x00007fff))))) (= (= ((_ extract 6 6) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 6 6) word) #b1) (not (or (bvslt r6 #xffff8000) (bvsgt r6 #x00007fff))))) (= (= ((_ extract 7 7) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 7 7) word) #b1) (not (or (bvslt r7 #xffff8000) (bvsgt r7 #x00007fff))))) (= (= ((_ extract 8 8) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 8 8) word) #b1) (not (or (bvslt r8 #xffff8000) (bvsgt r8 #x00007fff))))) (= (= ((_ extract 9 9) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 9 9) word) #b1) (not (or (bvslt r9 #xffff8000) (bvsgt r9 #x00007fff))))) (= (= ((_ extract 10 10) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 10 10) word) #b1) (not (or (bvslt r10 #xffff8000) (bvsgt r10 #x00007fff))))) (= (= ((_ extract 11 11) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 11 11) word) #b1) (not (or (bvslt r11 #xffff8000) (bvsgt r11 #x00007fff))))) (= (= ((_ extract 12 12) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 12 12) word) #b1) (not (or (bvslt r12 #xffff8000) (bvsgt r12 #x00007fff))))) (= (= ((_ extract 13 13) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 13 13) word) #b1) (not (or (bvslt r13 #xffff8000) (bvsgt r13 #x00007fff))))) (= (= ((_ extract 14 14) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 14 14) word) #b1) (not (or (bvslt r14 #xffff8000) (bvsgt r14 #x00007fff))))) (= (= ((_ extract 15 15) (bvand word (bvnot mask))) #b1) (and (= ((_ extract 15 15) word) #b1) (not (or (bvslt r15 #xffff8000) (bvsgt r15 #x00007fff))))) (= ((_ extract 63 16) (bvand word (bvnot mask))) ((_ extract 63 16) word)))))
(check-sat)
(pop 1)

(echo "narrow_range: clearing by the mask itself is not the short NULL guard: expect sat")
(push 1)
(declare-const word (_ BitVec 64))
(declare-const r15 (_ BitVec 32))
(declare-const r14 (_ BitVec 32))
(declare-const r13 (_ BitVec 32))
(declare-const r12 (_ BitVec 32))
(declare-const r11 (_ BitVec 32))
(declare-const r10 (_ BitVec 32))
(declare-const r9 (_ BitVec 32))
(declare-const r8 (_ BitVec 32))
(declare-const r7 (_ BitVec 32))
(declare-const r6 (_ BitVec 32))
(declare-const r5 (_ BitVec 32))
(declare-const r4 (_ BitVec 32))
(declare-const r3 (_ BitVec 32))
(declare-const r2 (_ BitVec 32))
(declare-const r1 (_ BitVec 32))
(declare-const r0 (_ BitVec 32))
(define-fun mask () (_ BitVec 64) ((_ zero_extend 48) (concat (ite (or (bvslt r15 #xffff8000) (bvsgt r15 #x00007fff)) #b1 #b0) (ite (or (bvslt r14 #xffff8000) (bvsgt r14 #x00007fff)) #b1 #b0) (ite (or (bvslt r13 #xffff8000) (bvsgt r13 #x00007fff)) #b1 #b0) (ite (or (bvslt r12 #xffff8000) (bvsgt r12 #x00007fff)) #b1 #b0) (ite (or (bvslt r11 #xffff8000) (bvsgt r11 #x00007fff)) #b1 #b0) (ite (or (bvslt r10 #xffff8000) (bvsgt r10 #x00007fff)) #b1 #b0) (ite (or (bvslt r9 #xffff8000) (bvsgt r9 #x00007fff)) #b1 #b0) (ite (or (bvslt r8 #xffff8000) (bvsgt r8 #x00007fff)) #b1 #b0) (ite (or (bvslt r7 #xffff8000) (bvsgt r7 #x00007fff)) #b1 #b0) (ite (or (bvslt r6 #xffff8000) (bvsgt r6 #x00007fff)) #b1 #b0) (ite (or (bvslt r5 #xffff8000) (bvsgt r5 #x00007fff)) #b1 #b0) (ite (or (bvslt r4 #xffff8000) (bvsgt r4 #x00007fff)) #b1 #b0) (ite (or (bvslt r3 #xffff8000) (bvsgt r3 #x00007fff)) #b1 #b0) (ite (or (bvslt r2 #xffff8000) (bvsgt r2 #x00007fff)) #b1 #b0) (ite (or (bvslt r1 #xffff8000) (bvsgt r1 #x00007fff)) #b1 #b0) (ite (or (bvslt r0 #xffff8000) (bvsgt r0 #x00007fff)) #b1 #b0))))
(assert (not (and (= (= ((_ extract 0 0) (bvand word mask)) #b1) (and (= ((_ extract 0 0) word) #b1) (not (or (bvslt r0 #xffff8000) (bvsgt r0 #x00007fff))))) (= (= ((_ extract 1 1) (bvand word mask)) #b1) (and (= ((_ extract 1 1) word) #b1) (not (or (bvslt r1 #xffff8000) (bvsgt r1 #x00007fff))))) (= (= ((_ extract 2 2) (bvand word mask)) #b1) (and (= ((_ extract 2 2) word) #b1) (not (or (bvslt r2 #xffff8000) (bvsgt r2 #x00007fff))))) (= (= ((_ extract 3 3) (bvand word mask)) #b1) (and (= ((_ extract 3 3) word) #b1) (not (or (bvslt r3 #xffff8000) (bvsgt r3 #x00007fff))))) (= (= ((_ extract 4 4) (bvand word mask)) #b1) (and (= ((_ extract 4 4) word) #b1) (not (or (bvslt r4 #xffff8000) (bvsgt r4 #x00007fff))))) (= (= ((_ extract 5 5) (bvand word mask)) #b1) (and (= ((_ extract 5 5) word) #b1) (not (or (bvslt r5 #xffff8000) (bvsgt r5 #x00007fff))))) (= (= ((_ extract 6 6) (bvand word mask)) #b1) (and (= ((_ extract 6 6) word) #b1) (not (or (bvslt r6 #xffff8000) (bvsgt r6 #x00007fff))))) (= (= ((_ extract 7 7) (bvand word mask)) #b1) (and (= ((_ extract 7 7) word) #b1) (not (or (bvslt r7 #xffff8000) (bvsgt r7 #x00007fff))))) (= (= ((_ extract 8 8) (bvand word mask)) #b1) (and (= ((_ extract 8 8) word) #b1) (not (or (bvslt r8 #xffff8000) (bvsgt r8 #x00007fff))))) (= (= ((_ extract 9 9) (bvand word mask)) #b1) (and (= ((_ extract 9 9) word) #b1) (not (or (bvslt r9 #xffff8000) (bvsgt r9 #x00007fff))))) (= (= ((_ extract 10 10) (bvand word mask)) #b1) (and (= ((_ extract 10 10) word) #b1) (not (or (bvslt r10 #xffff8000) (bvsgt r10 #x00007fff))))) (= (= ((_ extract 11 11) (bvand word mask)) #b1) (and (= ((_ extract 11 11) word) #b1) (not (or (bvslt r11 #xffff8000) (bvsgt r11 #x00007fff))))) (= (= ((_ extract 12 12) (bvand word mask)) #b1) (and (= ((_ extract 12 12) word) #b1) (not (or (bvslt r12 #xffff8000) (bvsgt r12 #x00007fff))))) (= (= ((_ extract 13 13) (bvand word mask)) #b1) (and (= ((_ extract 13 13) word) #b1) (not (or (bvslt r13 #xffff8000) (bvsgt r13 #x00007fff))))) (= (= ((_ extract 14 14) (bvand word mask)) #b1) (and (= ((_ extract 14 14) word) #b1) (not (or (bvslt r14 #xffff8000) (bvsgt r14 #x00007fff))))) (= (= ((_ extract 15 15) (bvand word mask)) #b1) (and (= ((_ extract 15 15) word) #b1) (not (or (bvslt r15 #xffff8000) (bvsgt r15 #x00007fff))))) (= ((_ extract 63 16) (bvand word mask)) ((_ extract 63 16) word)))))
(check-sat)
(pop 1)

(echo "narrow_range: the short NULL guard without the word revives a null lane: expect sat")
(push 1)
(declare-const word (_ BitVec 64))
(declare-const r15 (_ BitVec 32))
(declare-const r14 (_ BitVec 32))
(declare-const r13 (_ BitVec 32))
(declare-const r12 (_ BitVec 32))
(declare-const r11 (_ BitVec 32))
(declare-const r10 (_ BitVec 32))
(declare-const r9 (_ BitVec 32))
(declare-const r8 (_ BitVec 32))
(declare-const r7 (_ BitVec 32))
(declare-const r6 (_ BitVec 32))
(declare-const r5 (_ BitVec 32))
(declare-const r4 (_ BitVec 32))
(declare-const r3 (_ BitVec 32))
(declare-const r2 (_ BitVec 32))
(declare-const r1 (_ BitVec 32))
(declare-const r0 (_ BitVec 32))
(define-fun mask () (_ BitVec 64) ((_ zero_extend 48) (concat (ite (or (bvslt r15 #xffff8000) (bvsgt r15 #x00007fff)) #b1 #b0) (ite (or (bvslt r14 #xffff8000) (bvsgt r14 #x00007fff)) #b1 #b0) (ite (or (bvslt r13 #xffff8000) (bvsgt r13 #x00007fff)) #b1 #b0) (ite (or (bvslt r12 #xffff8000) (bvsgt r12 #x00007fff)) #b1 #b0) (ite (or (bvslt r11 #xffff8000) (bvsgt r11 #x00007fff)) #b1 #b0) (ite (or (bvslt r10 #xffff8000) (bvsgt r10 #x00007fff)) #b1 #b0) (ite (or (bvslt r9 #xffff8000) (bvsgt r9 #x00007fff)) #b1 #b0) (ite (or (bvslt r8 #xffff8000) (bvsgt r8 #x00007fff)) #b1 #b0) (ite (or (bvslt r7 #xffff8000) (bvsgt r7 #x00007fff)) #b1 #b0) (ite (or (bvslt r6 #xffff8000) (bvsgt r6 #x00007fff)) #b1 #b0) (ite (or (bvslt r5 #xffff8000) (bvsgt r5 #x00007fff)) #b1 #b0) (ite (or (bvslt r4 #xffff8000) (bvsgt r4 #x00007fff)) #b1 #b0) (ite (or (bvslt r3 #xffff8000) (bvsgt r3 #x00007fff)) #b1 #b0) (ite (or (bvslt r2 #xffff8000) (bvsgt r2 #x00007fff)) #b1 #b0) (ite (or (bvslt r1 #xffff8000) (bvsgt r1 #x00007fff)) #b1 #b0) (ite (or (bvslt r0 #xffff8000) (bvsgt r0 #x00007fff)) #b1 #b0))))
(assert (not (and (= (= ((_ extract 0 0) (bvnot mask)) #b1) (and (= ((_ extract 0 0) word) #b1) (not (or (bvslt r0 #xffff8000) (bvsgt r0 #x00007fff))))) (= (= ((_ extract 1 1) (bvnot mask)) #b1) (and (= ((_ extract 1 1) word) #b1) (not (or (bvslt r1 #xffff8000) (bvsgt r1 #x00007fff))))) (= (= ((_ extract 2 2) (bvnot mask)) #b1) (and (= ((_ extract 2 2) word) #b1) (not (or (bvslt r2 #xffff8000) (bvsgt r2 #x00007fff))))) (= (= ((_ extract 3 3) (bvnot mask)) #b1) (and (= ((_ extract 3 3) word) #b1) (not (or (bvslt r3 #xffff8000) (bvsgt r3 #x00007fff))))) (= (= ((_ extract 4 4) (bvnot mask)) #b1) (and (= ((_ extract 4 4) word) #b1) (not (or (bvslt r4 #xffff8000) (bvsgt r4 #x00007fff))))) (= (= ((_ extract 5 5) (bvnot mask)) #b1) (and (= ((_ extract 5 5) word) #b1) (not (or (bvslt r5 #xffff8000) (bvsgt r5 #x00007fff))))) (= (= ((_ extract 6 6) (bvnot mask)) #b1) (and (= ((_ extract 6 6) word) #b1) (not (or (bvslt r6 #xffff8000) (bvsgt r6 #x00007fff))))) (= (= ((_ extract 7 7) (bvnot mask)) #b1) (and (= ((_ extract 7 7) word) #b1) (not (or (bvslt r7 #xffff8000) (bvsgt r7 #x00007fff))))) (= (= ((_ extract 8 8) (bvnot mask)) #b1) (and (= ((_ extract 8 8) word) #b1) (not (or (bvslt r8 #xffff8000) (bvsgt r8 #x00007fff))))) (= (= ((_ extract 9 9) (bvnot mask)) #b1) (and (= ((_ extract 9 9) word) #b1) (not (or (bvslt r9 #xffff8000) (bvsgt r9 #x00007fff))))) (= (= ((_ extract 10 10) (bvnot mask)) #b1) (and (= ((_ extract 10 10) word) #b1) (not (or (bvslt r10 #xffff8000) (bvsgt r10 #x00007fff))))) (= (= ((_ extract 11 11) (bvnot mask)) #b1) (and (= ((_ extract 11 11) word) #b1) (not (or (bvslt r11 #xffff8000) (bvsgt r11 #x00007fff))))) (= (= ((_ extract 12 12) (bvnot mask)) #b1) (and (= ((_ extract 12 12) word) #b1) (not (or (bvslt r12 #xffff8000) (bvsgt r12 #x00007fff))))) (= (= ((_ extract 13 13) (bvnot mask)) #b1) (and (= ((_ extract 13 13) word) #b1) (not (or (bvslt r13 #xffff8000) (bvsgt r13 #x00007fff))))) (= (= ((_ extract 14 14) (bvnot mask)) #b1) (and (= ((_ extract 14 14) word) #b1) (not (or (bvslt r14 #xffff8000) (bvsgt r14 #x00007fff))))) (= (= ((_ extract 15 15) (bvnot mask)) #b1) (and (= ((_ extract 15 15) word) #b1) (not (or (bvslt r15 #xffff8000) (bvsgt r15 #x00007fff))))) (= ((_ extract 63 16) (bvnot mask)) ((_ extract 63 16) word)))))
(check-sat)
(pop 1)
