# VARKA-433: The compiled check runs a long-lane kernel with int literals

*Row 433 of `m8/PLAN.md`, found by row 336 on 21 October 2026.*

## 0. What exists now (admission check)

Read on 1 November 2026 from the worktree at `130280562f3` (code as of master `5c660201b35`); the
line numbers are that tree's. Master has since moved to `1bb9c97b5d0`, whose diff to the files these
rows touch (`Analysis.java`, `Slots.java`, `VarkaIrGrammar.java`, row 157's decimal store) shifts
some lines and changes no finding. Nothing was run.

1. **The case carries one table.** `VarkaFuzzCase` has one literal component, `long[] lits`
   (`VarkaFuzzCase.java`). `VarkaCompiledCheck` passes it as the lane's table and, truncated to
   int, as the other lane's. The truncation happens twice: once for the compared calls
   (`compare`, `:605-613`) and once for the warm-up's call arguments (`Args`, `:731-736`). The
   long lane's compared call goes through `VarkaKernelChecks.runLoadedLong(context, kernel,
   roots, numInputs, lits, batch, declineAllowed, hook)` (`:643-648`), which builds a `LongLane`
   with `NO_INT_LITS`.
2. **The harness already has the two-table form.** A second overload, `runLoadedLong(...,
   long[] lits, int[] intLits, LongBatch batch, ..., SparkAnswers spark, CallHook hook)`
   (`VarkaKernelChecks.java:657-664`), picks `MixedLane(batch, intLits, lits, roots)` when
   `holdsBothLanes(roots)`. The IR fuzzer's mixed corpus and `VarkaEmitterMixedLaneSuite` use
   it. The guard verdict also has a two-table form, `VarkaGuardSpecs.verdict(..., long[] lits,
   int[] intLits, ...)`, which counts `lits.length + intLits.length` literals (`:247-252`).
   `VarkaCompiledCheck` calls the one-table form (`:817`, `:903`).
3. **The exclusion.** `VarkaCoverageCompositionFuzzSuite`'s compiled test keeps only `oneTable`
   kernels: those with no int literal, or int-lane kernels with no long literal (`:946-956`). The
   comment names `shiftright(l, i ^ 5)`. `compiledCase` already computes both tables: `lits` is
   the long table for a 64-bit kernel, and `intLits` is the int table (`:791-805`). It uses both
   for the zero-divisor redraw and the guard verdict, and then builds the `VarkaFuzzCase` with
   `lits` alone (`:826`).
4. **Every constructor call.** `new VarkaFuzzCase(` appears in `VarkaAvx2SelectSuite`,
   `VarkaShrinker`, `VarkaVariantChecks`, `VarkaCoverageCompositionFuzzSuite`, `VarkaIrFuzzSuite`
   (two), `VarkaShrinkerSuite`, `VarkaTierWitnessSuite` and `VarkaVariantSuite`, and in the
   record's own seven `with*` copies. Thirty-three other places read `.lits()`.
5. **Nothing pins the sample.** The default compiled test takes 4 kernels a lane from a seeded
   shuffle of `oneTable` (`:961-965`). No committed file records which rows it took: not
   `known_failures.tsv`, not the reach report under `sql/varka/coverage/reach/`, and not row
   518's catalyst reach record. `dev/varka_compiled_fuzz.sh` runs every row
   (`-Dvarka.fuzz.coverageCompiled=all`, `:130`).

### What differs from the row text

- The fix is in the case and the check, as the row says. The harness and the guard verdict
  already take two tables, so no new harness code is needed.
- **The sample moves.** Widening the pool changes which 4 rows a lane the seeded shuffle takes at
  the default, for every lane that had excluded rows. That is expected and nothing pins it
  (item 5). The PR says which rows the default sample now takes.
- A DOUBLE kernel is 64-bit and takes the long branch. Whether `MixedLane` serves a kernel driven
  at the DOUBLE lane that reads int slots is not settled by reading. The build checks it, and if
  it does not, such kernels stay excluded with their own reason (risk 2).

## 1. Goal and non-goal

Goal: `VarkaFuzzCase` carries the int table beside the lane's table, `VarkaCompiledCheck` passes
each table where it belongs, and the coverage sample's `oneTable` exclusion is gone, its rows
running through the compiled histories.

Non-goal: the composition fuzzer's draws of mixed kernels (row 352's, which waits on this row,
`VARKA-352.md` 0 item 13), and the IR fuzzer's compiled cases, which are single-lane.

## 2. The design

1. `VarkaFuzzCase` gains `int[] intLits` after `lits`. A compact constructor default is not
   possible for a record component, so every constructor call (item 4) passes it: `new int[0]`
   for the single-lane cases, and the plan's int table in `compiledCase`. A `withIntLits` copy is
   added beside `withLits`, and `describe` prints `intLiterals=[...]` when the table is not
   empty, so a failure's replay line shows it.
2. `VarkaCompiledCheck`:
   - `compare`: for a 64-bit case whose roots hold both lanes, call the two-table
     `runLoadedLong` with `c.intLits()` and a null `SparkAnswers`. Otherwise keep the call as it
     is. The int-lane branch keeps its truncation of `lits`, which is the int table.
   - `Args`: the warm-up's call arguments take `c.intLits()` for a long case in place of the
     truncated copy.
   - The guard calls (`:817`, `:903`) take the two-table `Guards` count and `verdict`.
3. `VarkaShrinker` keeps `intLits` through each shrink step. A shrink that drops a root keeps
   the whole table, as `lits` is kept today.
4. `VarkaCoverageCompositionFuzzSuite`: `compiledCase` passes `intLits`, and the `oneTable`
   filter and its comment go. If risk 2 holds, the filter keeps only the DOUBLE case, with its
   reason in the comment.

## 3. Tests

- The compiled test itself is the test: `shiftright(l, i ^ 5)` named with
  `-Dvarka.fuzz.coverageRow`, run through every history, passes. The PR's description gives the
  replay line.
- A new case in `VarkaTierWitnessSuite`'s style is not needed. Instead, one assertion in the
  compiled test: when run with `-Dvarka.fuzz.coverageCompiled=all`, the count of kernels equals
  the count of rows, which the `info` line already prints as "N of M coverage rows' kernels".
  That is the guard against a future exclusion slipping in silently.
- `VarkaShrinkerSuite`: one case that shrinks a two-table case and keeps its int table.

## 4. Predictions and costs, registered before the build

- P1: the "N of M" line under `coverageCompiled=all` reads N = M. Today N is M less the
  long-lane rows with an int slot. No number is predicted for that gap, since only a JVM run
  counts it.
- P2: the default compiled test's time is unchanged within its spread. It still takes 4 kernels
  a lane, and the calibration table's VARKA-274b row (321 s for all rows unsanitized, on six
  cores) is what the full run costs. `dev/varka_compiled_fuzz.sh`'s full run grows by the
  formerly excluded rows' share of that.
- P3: no emitted byte, digest or committed file moves.

## 5. Risks

1. A kernel that reads both tables may also reach a harness path that assumes one table, for
   example the edge batches of `guardEdges` (`:816-880`). The full run (`coverageCompiled=all`)
   on the PR's CI is the check.
2. A DOUBLE-driven kernel with int slots may not be served by `MixedLane`, whose int columns are
   the low halves of a long lane. If so, those kernels keep a narrowed exclusion (2 item 4).

## 6. Order, files and size

| File | Change |
|---|---|
| `VarkaFuzzCase.java` | `intLits`, `withIntLits`, `describe` |
| `VarkaCompiledCheck.java` | `compare`, `Args`, the guard calls |
| `VarkaShrinker.java`, `VarkaVariantChecks.java` | keep the table |
| the six other files of 0 item 4 | the constructor's new argument |
| `VarkaCoverageCompositionFuzzSuite.scala` | `compiledCase`, the filter gone, the count assertion |
| `VarkaShrinkerSuite.scala` | the shrink case |

One pull request, verified by its PR Build, an option-matrix dispatch, and one
`dev/varka_compiled_fuzz.sh` run on CI if a workflow carries it, or on the laptop as a debugging
run otherwise (question 1). Size: small (about a day).

## 7. Out of scope

- The composition fuzzer's mixed draws (row 352).
- A third table for the narrow or decimal lanes: their rows' work.

## 8. Questions for the owner

1. **Where the full compiled run is taken.** No workflow runs `dev/varka_compiled_fuzz.sh`'s
   all-rows mode, which is the only run that reaches every formerly excluded row. (a) Run it once
   on the laptop under the shared JVM slots as the PR's evidence; (b) dispatch it in the
   nightly fuzz workflow. *Recommended: (a).* CI verifies the PR through its Build and matrix,
   and the all-rows run is a one-off check of the widened pool. A scheduled all-rows run is
   row 537's workflow change if wanted.

## 9. Done when

Row 433's done-when: the case carries both tables, the check passes each its own, and the
exclusion is gone, with `shiftright(l, i ^ 5)` and the other formerly excluded rows passing
through the compiled histories in an all-rows run.

## 10. Open points for the lead

1. Row 352's composition-fuzzer step for mixed kernels waits on this row (`VARKA-352.md` 5,
   step list). This row should merge before that step.

## 11. Outcome

(Filled in when the row is built.)
