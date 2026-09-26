# Task 173: The family chain is disjoint, as a test

*Opened 25 September 2026, from `PLAN_MILESTONE_6.md` 9.2's list after its
top five; scope item 41.*

## 1. The question

`VarkaExpressionCompiler.compileNode` dispatches a node through a chain of
groups, the date leaves, the calendar, interval, time and condition families,
then the int arithmetic and the picks, and the first group whose arms are
defined at the node compiles it. `PLAN_TASK_159.md` argued the chain is
order-safe because every arm is gated by the expression class or its data type,
so no node matches arms of two groups. An argument holds until someone adds a
fifth family or widens a guard, and then the first group in the chain wins
without anyone noticing. **Can the argument be a fact the tree keeps true?**

## 2. The change

Two pieces, both small.

* The chain becomes a named list, `VarkaExpressionCompiler.familyChain`, in
  the order `compileNode` dispatches, and `compileNode` folds that list. The
  list is what the test reads, so a group added to it is checked the day it is
  added, and a group added to `compileNode` without going through the list is
  the one thing the change makes impossible.
* `VarkaFamilyChainSuite` asks every node of every expression of the coverage
  table, and of eight shapes that sit on the seams between groups (the
  `weekday(d) + 1` the calendar family and the int arithmetic both look at, in
  both operand orders; the identity date cast; arithmetic over a calendar
  field), of each group's `isDefinedAt`, and asserts at most one answers. A
  node no group claims is the fallback's, which is allowed. It also asserts
  every group claims at least one node, or the corpus is not reaching the seam
  a group guards. Asking `isDefinedAt` runs only the arms' guards, which test
  the class and the data type and build nothing.

**The self-check.** A second test appends a copy of the calendar family to
the chain and asserts the check reports every calendar node claimed twice.
That is item 41's "fails when an arm is duplicated across two families on
purpose", kept as a test rather than done once by hand, so a green run of the
first test means the check can still see a duplicate.

## 3. Verification

* Both tests pass, and the first logs how many nodes each group claimed.
* `dev/scalastyle` passes; the emitted-bytes oracle is unchanged, since the
  dispatch is the same function folded from a list.
* `VarkaExpressionCompilerSuite` and `VarkaCoverageSuite` pass unchanged.

## 4. Outcome

**Done, 25 September 2026.** Over the 92 rows of the coverage table and the
eight seams, 100 expressions, no node is claimed by two groups, and every
group claims some: the date leaves 93 nodes, the time family 68, the calendar
50, the interval family 20, the int arithmetic 15 and the condition family 6.
The self-check reports every calendar node twice when the calendar family is
appended to the chain a second time, so the first test can see a duplicate.
The seams found nothing: `weekday(d) + 1` in either operand order is the
calendar family's alone, because the int arithmetic's `Add` arm excludes that
shape by name, which is the one place two groups look at the same node and
the guard that keeps them apart is now a tested guard. `compileNode` folds the
named list, so the emitted bytes are unchanged and the oracle says so.

## 5. Explicitly out of this task

* Reordering the chain or merging groups. The test says the order does not
  matter; it does not say what the order should be.
* Item 47, one place per node, for the reason `PLAN_MILESTONE_6.md` 2.7 gives.
