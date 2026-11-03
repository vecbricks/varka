# VARKA-222: Structural hashing of IR nodes, measured

## 1. Where this came from

Row 222 of `m7/PLAN.md`, from `m9/SCOPE.md` item 81 (moved from milestone 6): the IR's nodes are
Java records, whose `hashCode` and `equals` are structural, and the compiler and the emitter key
many maps on them - line numbers, word owners, pure words, slots, the memo that computes a shared
subtree once. A record holds no cache, so hashing a node walks its subtree on every lookup. The
row asks for the hash cached, with compile time measured before and after as the proof.

Catalyst meets the same question in `TreeNode`, and answers it with fields a record cannot have:
a lazily cached `hashCode` per node (`MurmurHash3.caseClassHash` over children whose hashes are
cached too), `fastEquals` that tries identity first, and for CSE an `ExpressionEquals` wrapper over
the cached `semanticHash` and `height`. The design chosen for Varka, should the cost be there, was
to intern the DAG once at analysis start - one bottom-up pass to one canonical instance per
structure - and key the internal maps by identity.

## 2. The admission check, done

Measured on 10 October 2026 (`sql/catalyst/benchmarks/VarkaEmissionHashProfile-jdk25-probe.txt`):
`VarkaEmissionBenchmark`'s wide four-op shape at 100, 200 and 400 outputs, compiled and emitted
under JFR as `VarkaEmissionProfile-jdk25-probe.txt` does, each execution sample counted as inside
an IR node's `hashCode` or `equals` when any frame of its stack is one (`dev/varka_jfr_ir_hash.py`):

| outputs | per emission | inside an IR `hashCode` | inside an IR `equals` |
| ---: | ---: | ---: | ---: |
| 100 | 12.0 ms | 3.7% | 1.5% |
| 200 | 25.9 ms | 2.2% | 1.0% |
| 400 | 40.4 ms | 5.0% | 0.3% |

Two things bound it:

* **No deep tree reaches the emitter.** A nested `year(date_add(greatest(...)))` chain fuses at
  depth 8 and is declined at depth 16 for the fused budget, which caps the operations in one
  output. Every subtree the emitter hashes is under that cap, so a lookup's cost is bounded and the
  total grows with the outputs, not with their square. Catalyst caches because its trees are
  unbounded; Varka's are not.
* **Equality is cheap already.** A record's `equals` compares its components with
  `Objects.equals`, which tries identity first, so a subtree shared between parents compares in one
  step - what `fastEquals` buys Catalyst.

What it would have taken to build: a share large enough that interning - a second notion of node
identity through `Analysis` and `Slots` - repays its complexity. At most 5% of emission, about
2 ms of the 40 ms a 400-output kernel takes, paid once per new shape behind the shape cache, is
not that.

## 3. The design

None: the row closes on the measurement, by the owner's decision of 10 October 2026.

### 3.1 What would reopen it

The fused budget raised far enough to admit deep outputs, or a profile of a real workload's
emission showing IR hashing well past the shares above; `dev/varka_jfr_ir_hash.py` reads either.

Row 559 of `m8/PLAN.md` reopens it (3 November 2026, `m8/VARKA-559.md`): planning a nested
chain whose levels share a node costs time exponential in its depth, in IR hashing, before the
budget declines it.

## 4. Files

| file | what |
|---|---|
| `sql/catalyst/benchmarks/VarkaEmissionHashProfile-jdk25-probe.txt` | the profile |
| `dev/varka_jfr_ir_hash.py` | the samples' share in IR hashing and equality |
| `m7/PLAN.md` | the record |

## 5. Tests, and what each is for

None: no code changes.

## 6. The measurement

Section 2.

## 9. Outcome, 10 October 2026

Closed on the measurement: IR hashing is 2 to 5 per cent of emission on the widest shapes the
emitter is given, and the fused budget bounds it.
