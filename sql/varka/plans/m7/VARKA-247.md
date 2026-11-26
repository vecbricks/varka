# VARKA-247: What `validityOrFirst` is for

## 1. Where this came from

Row 247 of `m7/PLAN.md`, item 48 of `m10/SCOPE.md`: VARKA-167's option audit emitted the bytes
oracle's whole shape set - 92 coverage rows and the fuzz blocks, at both widths - under both values
of every emit option, and `validityOrFirst` was the one field that moved no hash anywhere. An
option that changes no byte over a corpus that size is dead code or a switch over a shape the
corpus lacks, and the audit cannot say which. Done when the field's subject is named: the shape
that tells its two values apart joins the oracle, or the field goes with the tests that set it.

The option is VARKA-46's (milestone 4): in a masked lane group, a value root's validity OR is
emitted before its vector computation wherever the word is already known, so that C2 parses the
OR helper first and inlines it; parsed last, it was refused with `NodeCountInliningCutoff`, and
moving it first was worth 20 to 180 per cent on the masked calendar shapes.

## 2. The admission check, done

**Why the defaults never reach it.** `VarkaBodyEmitter.emitLaneGroup` emits the per-group OR
only for a value root whose validity is not already written: not filled once by the driver
(`fillsValidityOnce`: `denseValidityOnce`, a dense body, not a `Cond`) and not written whole by
the masked driver's bitmap pass (`servedByPass`: `validityByBitmap`, a root whose word is a pure
one-operator expression over input bitmaps). Under the defaults every dense value root is filled
once, and every masked value root is either served by the pass or has a word computed inside its
own subtree - an `IfElse`, a `MakeDate`, a mixed AND/OR tree - which `wordKnownBeforeCompute`
says is not known early. So the branch `validityOrFirst` chooses is never taken: VARKA-70's
bitmap pass and the dense driver fill took its subject away.

**Measured** on 10 October 2026, over the oracle's coverage rows and 200 fuzz shapes per lane,
`validityOrFirst=true` against `false` on four bases (hashes that differ):

| base | coverage (of 92) | int fuzz | long fuzz |
| :--- | ---: | ---: | ---: |
| the defaults | 0 | 0 | 0 |
| `validityByBitmap=false` | 41 | 111 | 69 |
| `denseValidityOnce=false` | 65 | 131 | 99 |
| both false | 65 | 147 | 114 |

The same at both widths. The option is alive, on the per-group reference arms the A/Bs of the
bitmap pass and the dense fill price against, and `VarkaEmitterValiditySuite` already runs both of its
values on the first of them. What would have rejected keeping it: no movement on any base.

## 3. The design

### 3.1 An option's subject, in the options table

The option stays: its arm is the reference VARKA-46's measurement rests on, and the order it
fixes is one whose payoff C2's inlining budget decides, which a JDK can move. What changes is that
the oracle and the audit look where it acts:

* `VarkaEmitOption.Flag` gains a subject: the options under which the option has anything to do,
  with a label. Every flag but `validityOrFirst` has the defaults as its subject.
  `validityOrFirst`'s is `validityByBitmap=false` and `denseValidityOnce=false`, the base where it
  moves the most.
* The table's arms apply the subject first, so the audit compares the option's two values where
  they differ, and labels the arm with the base.
* The bytes oracle pins each arm whose subject is not the defaults, as one digest per width
  beside the `useAVX` arms, and a test asserts that the two values' digests differ there and
  agree at the defaults: if the defaults ever reach the branch again, or the subject stops
  reaching it, the test says so.

### 3.2 What is deliberately unchanged

Every emitted byte at the defaults; the option's value and default; the validity suite's test.

### 3.3 Registered op counts

None move.

## 4. Files

| file | what |
|---|---|
| `VarkaEmitOption.java` | `Subject`, the flag's subject, the arms over it, `validityOrFirst`'s |
| `VarkaEmittedBytesSuite.scala` | the subject arms pinned, the test |
| `sql/varka/emitted_bytes.json` | four digests added |
| `m7/PLAN.md`, `m10/SCOPE.md` item 48 | the records |

## 5. Tests, and what each is for

* The oracle test: the committed file, with the new arm digests, is what the emitter produces.
* The new test: `validityOrFirst`'s two values differ over its subject and agree at the defaults.
* The audit, re-run: `validityOrFirst`'s arms now move hashes.

## 6. The measurement

The bytes suite's time before and after, since each pinned arm emits every shape once per width.

### 6.1 Predictions, registered before the run

1. The audit, re-run, reports `validityOrFirst` moving 65 coverage rows at each width, as 2
   measured over the same base.
2. The bytes suite takes no more than half again as long, two arms joining five.

## 7. Risks

1. A subject that is itself a reference arm can be retired one day; the test fails then, which is
   the moment to decide this option's fate with it.

## 8. Sequencing

1. This plan. 2. The table, the oracle, the test and the file; the records.

## 9. Outcome, 10 October 2026

### 9.1 What was built

`validityOrFirst` stays, and its subject is named: `VarkaEmitOption.Subject`, a flag's base
where its two values differ, the defaults for every flag but this one, whose subject is
`validityByBitmap=false` and `denseValidityOnce=false`. `emitted_bytes.json` pins both of its
values over that subject, one digest per width beside the `useAVX` arms, and
`VarkaEmittedBytesSuite` asserts that the two values emit alike at the defaults and differ over
the subject: 65 of the 92 coverage rows at the test's width.

*Correction to 3.1:* the table's arms do not apply the subject. `VarkaMatrix.configurations`
reads them as "one option flipped from the defaults", the configurations the suites run under,
and an arm carrying its base broke that. The bytes suite applies the subject to the arms itself,
for its audit and its pinned digests, and the audit compares such an arm against the subject
rather than the defaults. Section 4's "the arms over it" is corrected by the same sentence.

*After review (#704):* the test matrix ran `validityOrFirst=false` over the defaults, where it emits
the defaults' bytes, so it tested nothing; it now runs over the subject,
`denseValidityOnce=false,validityByBitmap=false,validityOrFirst=false`. The subject's label is
derived from what it changes, in table order, so the pinned key reads
`denseValidityOnce=false,validityByBitmap=false`, and cannot drift from the base it names. The test
covers the coverage rows and 200 fuzz shapes per lane at both widths, and is tied to the flag.

### 9.2 The predictions scored

1. **Holds.** The audit, re-run, reports `validityOrFirst=false` over its subject moving 130
   coverage hashes, 65 at each width, 294 int fuzz and 228 long fuzz; `=true`, the default,
   moves none.
2. **Holds.** The oracle test took 104 s against 79 s before, under half again.

### 9.3 What this leaves

The audit now answers "moves nothing" for an option only over the base that reaches it; a
future option with the same kind of dependence gets a subject in its table entry, and the
oracle and the audit follow.
