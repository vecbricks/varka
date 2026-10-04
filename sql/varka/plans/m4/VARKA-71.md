# VARKA-71: what the loop-method budget is actually bounding

## 1. Where this came from

`m4/PLAN.md` row 71 and section 2.35, out of VARKA-32 step B2's
regeneration. `GROUP_BUDGET = 16` rests on one measurement, VARKA-17's: two
outputs over a shared depth-8 chain ran about 1.4x faster as two loop methods
than as one, read as register pressure. The parity file has carried both arms
ever since so that a retune would be measured rather than argued, and they have
measured the other way since `aef0b82260e`.

## 2. What is already established, before this plan proposes anything

### 2.1 The reversal is real, and it is not the file's noise

The committed file, both widths:

| width | budget 16, split | budget 24, merged | relative |
|---|---|---|---|
| AVX-512 | 5002.7 | 6593.1 | 1.3X |
| 128-bit | 2319.5 | 3078.9 | 1.3X |

And it is not the file's noise. Over VARKA-77's ten runs per width of an
unchanged file (10 September 2026, idle and pinned), merged is ahead in **ten
runs of ten at both widths**, with the merged-over-split ratio ranging 1.29 to
1.32 at AVX-512 and 1.32 to 1.33 at 128-bit. Both arms sit in the band's quiet
tiers - 3.6% and 4.4% spread at AVX-512, 0.8% and 1.0% at 128-bit - so a 31% gap
is an order of magnitude outside anything the band would excuse. Those ten-run
medians are scratch data and are deliberately not quoted here; the committed
rates above are the file's, and the ratio range and the win count are facts about
the experiment rather than numbers wanting a home.

The shipped default loses about 31% on the one shape it was chosen from.

### 2.2 Nothing measured so far favours a narrow method

B2's ceiling ladder (`VARKA-32.md` 7.6) put `add_months` outputs over one
date into one method against several: one method won at six, eight and twelve
outputs at both widths, and every split cost - 9% and 17% at twelve outputs
against a ceiling of 400, 31% and 36% against a ceiling of 200.

### 2.3 The counterweight is compile time, not register pressure

Also from that ladder, under `-XX:+PrintCompilation`: past about 1900 bytes C1
refuses a loop method ("out of virtual registers in LIR"), so it runs
*interpreted* until C2 lands - about 340 ms at 376 ops, once per shape per JVM
behind VARKA-18's class cache. That is what stopped the ceiling at 400 rather
than 700, and it is the cost a budget ladder has to price too.

### 2.4 There are two bounds, and the asymmetry is the real question

`groupOutputs` decides with one condition
(`VarkaLoopEmitter.java:891-892`):

    boolean fits = group.ops + marginal <= options.groupBudget()
        || (withNext.saved > 0 && group.ops + marginal <= options.fusedCeiling());

`saved` counts **civil-from-days prefix reuse only** - `walk` adds to it exactly
when `sharePrefix && isChrono(node)` and the group already computes that date's
prefix. So a group whose outputs share a *calendar prefix* may reach 400 ops in
one method, while a group whose outputs share an *arithmetic chain* is held to
16. VARKA-17's pair is the second kind. It has twenty distinct ops and no prefix,
so clause 2 never applies to it and the budget splits it - and merging it wins
by 31%.

**So the row's question is narrower than the situation.** "Is 16 still the
budget" treats the number as the variable. The measurement says the variable may
be what `saved` counts. Section 2.35 already anticipated this - "whether clause 2
should widen with it is the same measurement's second column" - and this plan
takes it as a first-class arm rather than a footnote.

## 3. The admission check, to do first

### 3.1 What is the merged arm's win actually made of?

The javadoc's own reading is that VARKA-17's loss was a refused `orValidityBitsAt`
call in the wider method, which VARKA-46's reordering let inline, and that
register pressure was never binding at 24 ops. **That is a hypothesis with a
number attached to it and no direct evidence.** If it is right, the budget was
never bounding what it claims to bound, and picking a new number without knowing
that is picking a number.

Establish it before the ladder: `dev/varka_emit.sh --table` for the op counts of
both arms, `codeSize` per loop method at each rung, and `-XX:+PrintInlining` on
the merged arm to see whether the OR is inlined now where it was refused before.
The recipe is `SKILLS.md`'s "A refused call is refused by the caller's budget,
and the caller's budget is spent in program order", which measured exactly this
on this helper.

### 3.2 Which rows can carry a verdict, and which cannot

Section 2.35 names VARKA-17's pair, two chains over a shared subchain, the
`CASE WHEN` arms, the DAG-CSE outputs and the mod-7 pair. VARKA-77's band says
they are not equally readable:

| row | AVX-512 | 128-bit |
|---|---|---|
| VARKA-17's pair, both arms | tier 1 (3.6%, 4.4%) | tier 0 (0.8%, 1.0%) |
| `CASE WHEN, depth-4 arms` | tier 2 (11.8%, 21.8%) | tier 1-2 |
| `arithmetic depth 4` | tier 2 (10.3%) | tier 2 (23.5%, 24.6%) |
| `fused, CSE` (DAG-CSE) | **unreadable** (28.7%) | tier 2 (11.8%) |

Only VARKA-17's pair can settle a small effect. The DAG-CSE row cannot carry a
wide-width verdict at all, and the plan says so rather than quoting it. Where a
rung's effect lands inside a row's tier, the honest report is "this row does not
distinguish these rungs", not a ranking.

### 3.3 What a default change costs in pinned oracles

The default is not a number in isolation: moving it re-partitions loop methods,
and a large part of the emitter suite addresses methods **by name**. Enumerate
before choosing, because this cost may exceed the gain:

* Method-count assertions taken at the default -
  `VarkaLoopEmitterSuite.scala:1810-1856` (six `loops(...) === N`, one of them
  reasoning explicitly "1 + 38 > 16"), `:2792`, `:2806-2811`, `:3014-3015`,
  and `:3030` which pins the names `loopMasked0`/`loopMasked1`.
* Roughly two dozen pinned op-count oracles read off `loopDense0`/`loopMasked0`
  by name; a regroup that renumbers or merges methods invalidates the addressing
  even where the total does not move.
* `VarkaAssemblySuite.scala:634-694` pins literal method names inside expected
  C2 frame strings.

Whether these are re-pinned or the default stays and only the *rule* changes is
an outcome of the ladder, not an assumption of it.

### 3.4 There is no existing guard that a budget change moves only what it should

B2's byte-identity test
(`VarkaLoopEmitterSuite.scala:2820-2865`, "with no prefix to reuse, sharing
changes no loop method") compares `shareChronoPrefix` **off against on at one
budget**. It is not a budget guard, and section 2.35's sentence that it "stays as
the assertion that a budget change changes exactly the shapes it names" reads it
as more than it is. This task owes the guard that sentence describes: a corpus
whose grouping must not move between two budgets, asserted on method names and
`codeSize`.

### 3.5 What the check would have rejected

That `GROUP_BUDGET` is a hard constant needing new plumbing (it seeds
`DEFAULTS` and the emitter reads `options.groupBudget()`; `withGroupBudget`
exists and the benchmark already uses it, so a ladder is extra `emit` calls and
fresh case ids). That B2's guard covers budgets. That the DAG-CSE row can rank
rungs. That the question is only the number. And that the reversal might be
noise - 2.1 settles that.

## 4. The design

### 4.1 The ladder, and its second column

Two dimensions, measured together because they answer one question:

* **The budget**, at 16, 24, 32, 48 and 64, on VARKA-17's pair and on the shapes
  3.2 admits, at both widths.
* **Clause 2's reach**, as an emit option so it is priced rather than argued:
  today `saved` counts prefix reuse only; the arm counts whole-node reuse too,
  which is what VARKA-17's pair has. If the wider clause makes the budget
  irrelevant for these shapes, that is a better answer than a bigger number,
  because it leaves the bound where compile time actually needs it.

Compile time beside throughput at every rung, per the standing rule and 2.3:
first tier-4 landing per loop method, whether C1 refused, and the kernel's total
settle time.

### 4.2 The guard 3.4 found missing

A budget-identity test over a corpus with nothing to regroup - a single output,
two outputs sharing nothing, an output already wider than any rung - asserting
identical method names and `codeSize` across two budgets. It fails the day a
budget change reaches a shape it has no business reaching.

### 4.3 What this task does not do

The fragment mechanism, `FUSED_CEILING`, a single output's internal width (VARKA-43 measured it and left the decision to the emitter), `MAX_FUSED_NODES`, and
VARKA-44's epilogue - which 7.6 shows is what makes a twelve-output kernel slow
to settle at any grouping.

## 5. Files

| file | what |
|---|---|
| `VarkaEmitOptions.java` | the clause-2 reach as an option, if 4.1's second arm is built |
| `VarkaLoopEmitter.java` | `groupOutputs`' condition; `GROUP_BUDGET`'s javadoc, which currently sends the retune to "VARKA-43's question" and means this row |
| `VarkaEmitterParityBenchmark.scala` | the ladder's rungs, on fresh case ids (600 and 601 are taken; enumerate with `dev/varka_bench_ids.sh`) |
| `VarkaLoopEmitterSuite.scala` | 4.2's guard, and whatever 3.3's enumeration says must be re-pinned |
| `VarkaAssemblySuite.scala` | only if the default moves and the frame names with it |
| benchmarks + bands | regenerated, gated, and the band re-measured if grouping moves enough rows |
| `m4/PLAN.md`, this file | row 71, section 2.35's correction about the guard, section 9 |

## 6. Tests

* **4.2's budget-identity guard**, which is the one this task owes outright.
* **The grouping assertions of 3.3**, re-pinned with their reasoning updated
  where the default moves - each one states an arithmetic ("1 + 38 > 16") that
  is part of the record, not incidental.
* **The differential is untouched**: grouping changes which method holds an op,
  never the answer. If any differential moves, the change is wrong.

## 7. The measurement, and predictions registered before it

1. **The merged arm's win is the inlining, not the budget.** `-XX:+PrintInlining`
   shows `orValidityBitsAt` inlined in the merged arm at 24 ops where VARKA-17's
   evidence had it refused. If instead it is refused in both and merged still
   wins, the register-pressure reading was wrong for a different reason and the
   ladder is measuring something nobody has named.
2. **The budget's win keeps growing to at least 32 and then flattens**, because
   nothing in 2.2 favours narrow and 2.3's cliff is a byte count that twenty
   ops do not approach.
3. **The wider clause 2 makes the budget nearly irrelevant on these shapes**: at
   any rung, whole-node reuse groups VARKA-17's pair, so its rows differ by less
   than their band. That would make the rule the answer and leave the number to
   compile time.
4. **A default change re-pins more than ten assertions.** 3.3's enumeration is
   the estimate; the prediction is that the true count exceeds it, because
   pinned oracles address methods by name and the enumeration found them by
   grep.

## 8. Risks

1. **A number that helps one shape and hurts another.** The corpus is small and
   VARKA-17's pair is the only quiet one, so a rung that wins there and moves the
   others inside their band is not a measured win. State it as such.
2. **The pinned-oracle churn is the real cost** and it is paid in a file where
   the numbers are also the record. 3.3 is enumerated before anything is chosen.
3. **Compile time is a startup cost, so a throughput ladder will not see it.**
   2.3's method is the one that does, and it is part of the measurement rather
   than a follow-up.
4. **Widening clause 2 touches every shape with a shared subtree**, which is far
   more than VARKA-17's pair. 4.2's guard is what bounds it, and it is written
   before the option.

## 9. Sequencing

1. This plan and row 71.
2. 3.1's mechanism probe, which decides whether the ladder is measuring the
   budget or the inliner.
3. 3.3's enumeration of what a default change would re-pin.
4. 4.2's guard, before either arm is built.
5. The ladder, both dimensions, both widths, with compile time beside it.
6. The default and the rule chosen from the numbers; the re-pinning; the
   regeneration, gated and banded.
7. Section 2.35's correction, row 71, `SKILLS.md`, section 10.

## 10. Outcome

### 10.1 Step 3.1's mechanism probe: the win is the CSE, and prediction 1 is wrong

Emitted both arms directly and counted what is in them, then ran each alone in
its own JVM under `-XX:+PrintCompilation` and `-XX:+PrintInlining`.

**What each arm contains**, per loop method, from the emitted bytes:

| budget | loop methods | bytes each | `IntVector` calls | `VarkaVectorSupport` calls |
|---|---|---|---|---|
| 16 | 2 | 432 | 30 each | 5 each |
| 24, 32, 48, 64 | 1 | 504 | 43 | 5 |

Merging takes the kernel from 60 vector ops and 10 support calls per lane group
to 43 and 5 - a 31% reduction in work. The committed throughput gain is 1.3X.
The two numbers match closely enough that nothing else needs explaining: the
merged arm wins because the shared depth-8 chain is computed once instead of
twice, which is the cross-output CSE the budget was splitting.

**Prediction 1 is refuted, and by the absence of the thing it named.** It said
the win would be `orValidityBitsAt` inlining in the merged arm. That call is
**not emitted at all** for this shape - zero occurrences across a full
`-XX:+PrintInlining` log of both kernels. VARKA-70's bitmap pass serves these
roots (two chains over one column, so the word is a bare leaf), which removed
the per-group OR entirely. The javadoc's hypothesis is about a call that no
longer exists here. It may still be the right account of *why the rows reversed
at `aef0b82260e`*, which predates VARKA-70 and is not something this probe can
reach; what it is not is the reason the merged arm wins today.

**Compilation behaviour is identical per method**, which took a second run to
establish honestly. Run in the benchmark's own order the split arm showed ten
compilation events against the merged arm's three, which looks like a
recompilation difference and is not: the split arm runs first and pays the
warmup. Run alone in a fresh JVM each, every loop method in both arms compiles
four times at tier 3 and three at tier 4. The split simply has two methods, so
it compiles twice as much. (Three tier-4 compiles of one method is itself more
than a healthy method needs, but it is the same in both arms, so it is not what
separates them - that is VARKA-90's.)

### 10.2 What this changes in the ladder

**Rungs above 24 are byte-identical on this shape.** The table above is the
whole of it: 24, 32, 48 and 64 emit the same single 504-byte method. So the
five-rung ladder of 4.1 has exactly two distinct outcomes here, and any ranking
among the upper rungs measured on this shape would be measuring the file's
noise. The ladder needs shapes that straddle the higher budgets to say anything
about them, and 3.2 already showed the other candidate shapes are too noisy to
rank small effects. **What the ladder can actually establish is where the
split/merge boundary should sit, not which of several wide budgets is best.**

### 10.3 Step 3.3's enumeration: the re-pinning cost is one assertion, and
prediction 4 is wrong

The enumeration was done by changing the default and running the suites, not by
grepping for what might move - a grep finds what addresses a method by name, and
what matters is what that addressing actually *sees*.

| default | catalyst Varka suites | sql/core Varka suites |
|---|---|---|
| 16 (shipped) | 267 pass | 180 pass |
| 24 | 267 pass, 0 fail | - |
| 64 | 266 pass, **1 fail** | 180 pass, 0 fail |

**At 24, nothing moves at all.** The roughly two dozen pinned op-count oracles
that read `loopDense0`/`loopMasked0` by name are unaffected, because the shapes
they use are single-output or already grouped; the addressing is only fragile in
principle.

**At 64, exactly one assertion fails**, and it is the one whose reasoning is
written out in the source: `VarkaLoopEmitterSuite.scala:1838-1841`, "year reuses
nothing against [x + 1] and 1 + 38 > 16, so it opens a group of its own, which
month then joins". At 64 that sum fits and the two become one method. The
assertion did exactly what an assertion with its arithmetic spelled out is for.

**Prediction 4 said a default change would re-pin more than ten assertions, and
that the true count would exceed the grep's estimate.** It is one, and only past
24. The prediction reasoned from how the tests *address* methods rather than from
what those tests actually construct, which is the same error in miniature that
2.39 made - inferring from a shape instead of measuring it.

**Two gaps in this enumeration, stated rather than buried.** `VarkaAssemblySuite`
cancels all 23 of its tests in this environment for want of a disassembler, so
its `loopDense0` frame-name pins were never exercised at any budget; they are
checked on a host that has one, or the task ships not knowing. And the
benchmarks were not run here - the parity file's case names carry loop-method
counts, so a default change moves the file's text as well as its numbers.

**The differential does not move**, at any budget tried, which is the claim
section 6 makes: grouping decides which method holds an op, never the answer.

### 10.4 Step 4.2's guard, written before either ladder arm

`VarkaLoopEmitterSuite`, "VARKA-71: a budget change reaches only the shapes whose
grouping it decides". Its corpus is shapes no rung can regroup - one output is
one group whatever the budget, and outputs whose combined weight exceeds every
rung stay apart at all of them - asserted across 16, 24, 32, 48 and 64 on method
names and `codeSize`:

* one depth-8 chain, a single output;
* one `year`, 38 ops against the shipped 16, so a single output wider than the
  budget which stays one method as the budget grows past it;
* one `add_months`, 112 ops, wider than every rung;
* two `year`s over *different* dates, 76 ops with no prefix to reuse, so clause
  1 splits them and clause 2 never opens.

A shape that legitimately regroups - two small disjoint outputs, which a wider
budget should merge - is deliberately absent, because that is what the budget is
for and asserting it unchanged would assert the feature away.

10.3 learned the cost of a default change by making it and running the suites.
That is the right cost and the wrong way to learn it; this is the assertion that
makes it a check.

### 10.5 Step 5, static half: the boundary is between 16 and 24, and nothing
above 24 buys anything

Before timing any rung, emit a corpus at each and count what the budget actually
regroups. Methods are `loopDense*`; ops are `IntVector` calls summed over them.

| shape | 16 | 24 | 32 | 48 | 64 |
|---|---|---|---|---|---|
| VARKA-17: 2 outputs, shared depth-8 chain | 2 / 60 | 1 / 43 | 1 / 43 | 1 / 43 | 1 / 43 |
| 3 outputs, shared depth-8 chain | 2 / 61 | 1 / 44 | 1 / 44 | 1 / 44 | 1 / 44 |
| `date_add` + `year` over one date | 2 / 38 | 2 / 38 | 2 / 38 | 1 / 37 | 1 / 37 |
| 2 outputs, shared depth-4 chain | 1 / 35 | 1 / 35 | 1 / 35 | 1 / 35 | 1 / 35 |
| DAG-CSE: `date_add(d,1)` and `datediff` over it | 1 / 7 | 1 / 7 | 1 / 7 | 1 / 7 | 1 / 7 |
| 2 plain chains, nothing shared | 1 / 28 | 1 / 28 | 1 / 28 | 1 / 28 | 1 / 28 |
| `year` + `month` over one date | 1 / 40 | 1 / 40 | 1 / 40 | 1 / 40 | 1 / 40 |
| `year` over two dates | 2 / 68 | 2 / 68 | 2 / 68 | 2 / 68 | 2 / 68 |
| `dayofweek` + `weekday` | 1 / 34 | 1 / 34 | 1 / 34 | 1 / 34 | 1 / 34 |

**Three of nine shapes regroup at all, and only one of them above 24.** The two
that move at 24 each drop 17 ops, about 28%. The one that moves at 48 drops
**one op of 38** - and it is the shape whose assertion 10.3 found failing at 64.
So going past 24 buys a single lane op on one shape, costs the one pinned
assertion, and grows every method's bound toward the C1 refusal 2.3 records.

Six shapes never move, and two of them say why the budget is narrower than it
looks: `year + month` is clause 2's, and `year` over two dates has nothing to
share at any budget. **The budget decides grouping only where outputs share
nodes but no calendar prefix and their merged weight straddles the rung.**

This is the ladder's answer, and it needed no timing run. Prediction 2 said the
budget's win would keep growing to at least 32 and then flatten; it flattens at
24, one rung earlier, and the static count is what shows it rather than a
throughput measurement that would have had to beat the file's noise.

**And it points at 4.1's second dimension rather than at a number.** Read the
condition again with the survey in hand. `marginal` already counts only nodes
*new* to the group, so `group.ops + marginal` is the merged method's total, with
the shared chain counted once: VARKA-17's pair is 14 + 6 = 20 against a budget of
16. Two methods cost 28 nodes of work to the merged method's 20, so the merge is
strictly less work - and clause 1 rejects it anyway, because it bounds the
method rather than the work.

Clause 2 exists for exactly that case and does not fire, because `saved` counts
civil-from-days prefix reuse only. Widen it to count *any* reuse and VARKA-17's
pair merges at a budget of 16, with no rung moved: `saved` becomes 8, clause 2
opens to `FUSED_CEILING`, and the same 28% saving arrives without loosening the
bound that keeps compile time in hand. The justification is identical to the one
B2 wrote for prefixes - joining lets the output skip work the group already
does, which is less work rather than a trade.

So the shape of the answer is a rule, not a number, which is what prediction 3
registered.

### 10.6 The rule, built and measured - prediction 3 confirmed

`VarkaEmitOptions.shareWholeNodes`, on by default, `GROUP_BUDGET` unchanged at
16. Clause 2's reuse is now measured as what an output would cost alone less what
it actually adds, which is the prefix accounting generalised - a reused prefix is
reused nodes. The gate stays `> 0`: reuse opens the wider bound, its size does
not.

**It merges what a wider budget would and nothing else**, asserted method for
method in `VarkaLoopEmitterSuite` over a ten-shape corpus, in both directions:
identical to a budget of 24 for the two shapes that share nodes without sharing a
prefix, identical to the shipped setting for the five that have no reuse to act
on, and the sharing corpus is required to actually differ from the shipped
setting so it cannot quietly stop exercising the rule.

**The regeneration**, first to be read by VARKA-77's band and gate rather than by
eye:

| width | reuse off, two methods | reuse on, one method | relative |
|---|---|---|---|
| AVX-512 | 5019.8 | 6507.6 | 1.3X |
| 128-bit | 2317.9 | 3063.4 | 1.3X |

Both within 0.4% of the same kernels' numbers in the previous file, which is what
says the shapes did not drift. **No row moved past the band**; seventeen the band
calls unreadable were set aside unclassified, several swinging more than 18%,
each of which under a flat threshold would have wanted a second regeneration to
interpret. Controls within 2.4%, the gate clean at both widths, the requote list
empty.

**The A/B had to be rewritten, not just regenerated.** With the rule shipped, the
old "budget 16" arm merges too, and the pair that has measured this question
since VARKA-17 would have become two copies of one kernel. The arms are now the
rule off and on at the shipped budget. Their band entries were renamed rather
than remeasured, because the kernels are unchanged - the suite asserts the
reuse-off arm emits what budget 16 emitted and the shipped arm what budget 24
emitted.

### 10.7 What moved that the plan did not list

**Three of four predictions were wrong, and all three the same way.** 1 said the
win was an inlining effect and named a call this shape no longer emits; 2 said
the win would grow to at least 32 and it flattens at 24; 4 said a default change
would re-pin more than ten assertions and it is one. Each reasoned from the shape
of the code - how tests address methods, what a javadoc says a call costs -
rather than measuring it. That is section 2.39's failure in miniature, three
times, in a plan written to correct exactly that.

**VARKA-79's four benchmark cases had never been regenerated into the committed
file.** They appear as new rows here. Nothing was wrong with them; the task added
the cases and the file was not regenerated, which is invisible until someone
regenerates for another reason.

### 10.8 What this leaves for later

VARKA-79's four rows are now committed but **unbanded** - the band was measured
before they existed, so `--band` will report them without a tier until the parity
band is remeasured. That belongs with milestone 5's VARKA-90, which already owes
the arithmetic benchmark's band.

`VarkaAssemblySuite` cancels its 23 tests here for want of a disassembler, so its
`loopDense0` frame-name pins were never exercised against a grouping change at
any setting. The rule does not move those shapes, but that is reasoning rather
than a check.

And `FUSED_CEILING` is now the bound that matters for shapes with reuse, since
clause 2 admits more of them. Nothing here suggests 400 is wrong - B2 chose it on
compile time and that argument is untouched - but the ceiling now governs a wider
set than when it was set, which is worth a sentence in whatever revisits it.
