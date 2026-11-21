# VARKA-620: measure the emitter's chain-depth cap past 16

## 1. Where this came from

On 20 November 2026 the owner decided to measure before raising `VarkaEmitBudget.MAX_CHAIN_DEPTH`
(16). Five decimal rows build shapes deeper than it: 362b, 363, 363b, 364 and 366, whose shapes
run 26 to 60 deep. A read-only investigation of the same day found that only a raise would let
them fuse. An output's height flows through the CSE slots, so parking a subtree in a slot does not
shorten it. The cap was set by `VARKA-9.md` 5.2 from a chain measured to depth 16 and no further:
"No spill cliff appears at either width through depth 16", so the cap bounds method size and
register pressure "by policy rather than marking a performance edge". Nothing has measured
register pressure, method size or speed past 16. This row is that measurement. Raising the cap is
not part of it.

## 2. The admission check, done

Read on master at `6ac76f86cb3`.

* **One constant decides the depth, in two places.** `Analysis.analyzeRoot` throws "chain deeper
  than MAX_CHAIN_DEPTH=16" past it, and `VarkaLoopEmitter.fitsBudgets`, the compiler's mirror of
  the emitter's caps, refuses the same heights through `budgetWalk`. Both have the emit options
  in hand, so one option read in both moves the depth check alone.
* **The constant has a third reader that is not a depth.** `Slots` hoists the broadcast literals
  into vector locals only when a loop body has one output and `numLiterals <= MAX_CHAIN_DEPTH`.
  That threshold is a literal count. It gets its own constant at the same value, so moving the
  depth does not move the hoist.
* **`budgetWalk` disagrees with `Analysis` on two leaves.** `Analysis` gives a `DecimalColumn`
  and a `DecimalWord` height 0, through `registerInput`. `budgetWalk`'s leaf list names
  `ColumnRef`, `LiteralSlot`, `BoolColumn`, `CondConst` and `NullValue` only, so it counts each
  decimal leaf as an op of height 1. The off-by-one is real. The compiler refuses a decimal shape
  exactly 16 high that the emitter would accept, and without a byte budget it counts the decimal
  leaves toward `MAX_FUSED_NODES` too. The fix is the only change at the default options, and it
  is its own commit with a test at the cap and one past it over each leaf.
* **Three test sites follow the constant or its message.** `VarkaEmitterContractSuite` emits a
  chain one past it. `VarkaSteering` and `VarkaIrFuzzSuite` skip a draw whose message holds
  `MAX_CHAIN_DEPTH=`. The message keeps that text, with the value in force after it.
* **What the check would have rejected.** A cap whose height is recomputed somewhere the options
  do not reach. No such site exists. `VarkaConditionCompiler` and `VarkaNodeCompiler` name the cap
  only in comments, to explain why they build balanced folds.

## 3. The design

### 3.1 The cap as an emit option, and the benchmark

`VarkaEmitOptions.maxChainDepth`, default `MAX_CHAIN_DEPTH`, is a `Count` in
`VarkaEmitOption.TABLE` with reason `KNOB` and the tag `chainDepth=`. The tag renders only off
the default, so the default canonical string and every shape hash stay as they are. It has no
audit values (corrected on 20 November: it has the value 32, so the matrix runs
`maxChainDepth=32`, see 3.1a). The fuzzer draws 16
and 32, as `VarkaEmitOptionSuite` requires of every option that is not a fault injector, so a
fuzz variant that grows a draw past 16 is emitted and checked under 32 rather than skipped. It is
a measurement knob, and no session conf sets it.

### 3.1a Correction, 20 November 2026: the matrix runs the raised cap

The first Build failed `VarkaMatrixSuite`'s check that every option the fuzzer draws has a matrix
configuration, and the review asked that the suites run under a raised cap, not only the fuzzer.
The option now has the audit value 32. The configuration emits the defaults' bytes for every shape
the default cap admits, so what it buys is the suites' answers under a raised cap: shapes past 16
that the fuzzer grows, and every contract suite. Three tests assert the default cap's verdict and
are listed in `sql/varka/matrix/skips.tsv` as expected failures: two in `VarkaEmitterBudgetSuite`
and `IR outside the emitter's shape is rejected` in `VarkaEmitterContractSuite`. Pinning the cap
in those tests, so that no skip is needed, is the cleaner form and is left for a follow-up.

`VarkaChainDepthBenchmark` is a new class, by the per-feature-files rule, since a depth ladder past
the cap is not a shape the parity file prices. Each arm is one SQL expression over two columns `x`
and `y`, parsed and compiled through `VarkaExpressionCompiler.compile` under the raised cap. So the
fused arm and Spark's row arm evaluate the same Catalyst tree. The shape is a decimal-like mix
repeated to the target height: `e + y`, `e * 3`, `shiftright(e, 2)` and `e - (CASE WHEN e > y THEN y
ELSE 0 END)`, with ANSI arithmetic as a decimal's checked operations have it (the multiply excepted,
risk 3). The `CASE` reads `e` twice rather than three times, so the Catalyst tree's paths double,
not triple, at each compare. The cycle is six levels deep. A remainder of one to five levels is
taken from the cycle's single-level steps. The arms are:

* depths 16, 24, 32, 48 and 64, at the `BIGINT` lane and the `INT` lane;
* per depth, the fused kernel under the cap raised to 64, Spark's row path (the generated
  `UnsafeProjection` over one row at a time), and a scalar Java loop of the same steps;
* at depth 16 only, the kernel under the default cap is emitted too, and its class bytes must
  equal the raised cap's (the option moves nothing a default admits). Being the same class, it
  is not timed twice.

The run drives a million rows in chunks of 4096, Spark's batch size, null-free. Before each arm
is timed, every output is checked against Spark's answers, and the fused arm is settled on the
tier witness. Beside each table the file notes each loop method's bytecode size and whether C2
compiled every dense loop method. The emit asserts that the compiled IR's height is the depth
the label names, and that the default options refuse every depth past 16. The literal table is
the same two values at every depth, so the broadcast hoist does not change along the ladder.

### 3.2 What is deliberately unchanged

* The default cap. Raising it is the owner's decision on the runner readings, not this row's.
* The masked body. The arms are null-free. A mixed-null arm is a follow-up if the dense ladder
  shows a cliff the masked body would only make worse.
* The decimal rows' own shapes. They do not fuse yet for reasons beside the depth. This ladder is
  a stand-in for their operation mix on the integer lanes.

## 4. Files

| file | what |
|---|---|
| `VarkaEmitBudget.java` | `HOIST_LITERALS`, the literal-count threshold, apart from the depth |
| `VarkaEmitOptions.java`, `VarkaEmitOption.java` | `maxChainDepth` and its table entry |
| `Analysis.java`, `VarkaLoopEmitter.java`, `Slots.java` | read the option or the new constant; `budgetWalk`'s leaves |
| `VarkaEmitterContractSuite.scala`, `VarkaEmitterBudgetSuite` | the cap at its option; the leaf fix |
| `VarkaChainDepthBenchmark.java` | the ladder |

## 5. Tests, and what each is for

* `VarkaEmitterContractSuite`: a chain one past the option's value is refused at the default and
  at a raised value, and a chain at a raised value is accepted. This catches an option that one
  of the two readers ignores.
* `VarkaEmitterBudgetSuite`: `fitsBudgets` accepts a 16-high chain over a `DecimalColumn` and
  over a `DecimalWord` leaf and refuses one 17 high, as `Analysis` does.
* `VarkaEmitOptionSuite`: the table entry, which the suite requires of a new record component.
* The benchmark's dry run: every arm builds, its height is its label, and its first call answers
  as Spark does.

## 6. The measurement

The runners, not the laptop. `VarkaChainDepthBenchmark` runs on an AMD EPYC 7763 at 256 bits and
under `-XX:MaxVectorSize=16`, which gives 128-bit vectors. The control rows are the scalar loop
and Spark's row path, which the cap cannot touch. Its two sections are `chain depth past the cap:
BIGINT lane` and `chain depth past the cap: INT lane`.

### 6.1 Predictions, registered before the run

1. **No method is too big for C2 through depth 64.** Every dense loop method stays under
   HotSpot's 8000-byte `HugeMethodLimit`, and the tier witness reads C2 code for every arm. The
   chain adds about 20 to 40 bytes of bytecode a level, which is under 3 KB at 64.
2. **Fused time grows with depth and no faster.** The shape is a dependent chain per lane group
   with three values live (the running value, `y` and one literal), well inside 16 vector
   registers. So the fused rate at depth 64 is between a quarter and a third of its rate at 16,
   with no step between adjacent rungs more than 1.25x worse than the op count predicts.
3. **The fused-to-scalar ratio holds.** At every depth it is within 20% of its value at 16. A
   fall of more than that at any rung is the register-pressure cliff this row looks for.
4. **The fused-to-Spark ratio grows with depth**, since Spark's per-row overhead is fixed and the
   kernel pays none. It is above 5x at 64 on both lanes.
5. **The BIGINT lane is the weaker one at 256 bits.** AVX2 has no 64-bit lane multiply, and C2
   builds it from 32-bit ones, so the multiply levels cost more there than at the INT lane.

**The decision rule the owner reads.** A cap of N is supported where predictions 1 and 3 hold at
every rung up to N, at both widths and both lanes. Where 1 fails, the cap stops below the first
rung whose loop method C2 does not compile. Where 3 fails, it stops below the rung that broke it.

*Correction, 20 November 2026, after the first dry run and before any timing.* Three things above
changed while the benchmark was built, and section 3.1 was edited in place for them: the compare
step is `e - (CASE WHEN e > y THEN y ELSE 0 END)`, so the cycle is six levels and a remainder
takes up to five single-level steps; the multiply wraps (risk 3); and the fuzzer draws the option
at 16 and 32, since `VarkaEmitOptionSuite` requires draws of every option that is not a fault
injector. Two statements were left as first written and are wrong: the benchmark builds each
arm's Catalyst tree directly rather than parsing SQL (the tree is the same one the SQL would
parse to), and the literal table holds three values at every depth (the multiplier 3, the shift
2 and the `ELSE 0`), not two, as prediction 2's "one literal" should also read. Two more change
what the readings can say:

* **The run is cache-resident.** One pass over a million rows streams 24 MB at the BIGINT lane,
  past L3, so a shallow rung would be bound by memory and a deep one by compute, and a ratio
  against depth 16 would read that boundary. The timed run drives a 16384-row buffer (384 KB,
  in L2) 64 times instead, a million rows in all, in chunks of 4096. The paragraph above that
  says "a million rows" means this.
* **This ladder cannot show a register-pressure cliff.** Every rung keeps a handful of vectors
  live: the running value, `y` and three literals. It prices method size, the tier C2 reaches and
  a dependent chain's speed, not the register file. So prediction 3 holding says the deeper
  chains cost what their op count says, not that no register-pressure cliff exists, and the
  decision rule supports a cap for shapes this narrow. A wide arm, with a literal per level or
  several live intermediates, which is what a 128-bit decimal pair's words make, is owed before
  a raise covers the decimal rows' shapes in general.

## 7. Risks

1. The compiler may lower the `CASE WHEN` other than as an `IfElse` two levels deep. The
   benchmark asserts each arm's height and fails the emit rather than mislabel a rung.
2. The byte budget may refuse the deepest rungs rather than the cap. That is then the rung's
   result, recorded with its reason. No other option is moved to get past it.
3. A checked `BIGINT` multiply may decline on a host whose 64-bit masks fall back. The runners
   are x86, and the dry run shows whether it fuses there. *20 November 2026, the first dry run,
   before any timing:* it declines on the laptop too, as "checked bigint multiply whose operands
   do not rule out overflow (row 104's 128-bit product)". So the multiply step wraps (Spark's
   `LEGACY` mode) at both lanes, and the adds and subtracts stay ANSI-checked. The values never
   overflow, so the answers are the checked ones.

## 8. Sequencing

1. This plan and the row.
2. `HOIST_LITERALS` and the `maxChainDepth` option, with the contract test.
3. `budgetWalk`'s decimal leaves, with the budget test.
4. The benchmark, and its dry run at the smallest size.
5. The runner dispatches at 256 and 128 bits, which the owner starts.

## 9. Outcome

Not measured yet. *20 November 2026, the dry run on the laptop (no timings):* every rung of both
lanes compiles, has the height its label names, is refused by the default cap past 16, and
answers as Spark does over 4099 rows; at depth 16 the raised cap's bytes are the default's. The
literal table holds 3 entries at every rung. The largest group method, the masked epilogue, is
1142 bytes at depth 16, 1504 at 24, 1875 at 32, 2612 at 48 and 3358 at 64, and the dense loop
619, 785, 988, 1361 and 1771, the same at both lanes: about 23 bytes a level in the dense loop,
inside prediction 1's range and far under 8000. Spark's arm is the generated
`SpecificUnsafeProjection`. A second dry run, after the timed run moved to a cache-resident buffer,
settles the depth-64 rung of each lane on the tier witness: 8 drives of a million rows each, and
`loopDense0` is C2 code (level 4) at both lanes. The fuzzer's suite, with the option drawn at 16
and 32, passes in its own JVM on the laptop (41 tests, 2 opt-in cancels); its C2 test took 8
minutes 24 seconds of the Build's 9-minute cap on a loaded machine.
