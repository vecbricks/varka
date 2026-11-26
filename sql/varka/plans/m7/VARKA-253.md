# VARKA-253: The fallback scratch's contract

## 1. Where this came from

Row 253 of `m7/PLAN.md`, item 68 of `m10/SCOPE.md`, from VARKA-198's build and the review of
VARKA-209. A kernel with a materialized calendar prefix needs `scratchBytesPerRow() * length`
bytes of scratch. Called through the forms of `run` that take an address, its caller provides
them; called through the seven- or eight-argument forms, its emitted code takes them from
`VarkaScratch`, one buffer per thread from the global arena, regrown by doubling and never freed.
The evaluator (`VarkaKernelRunner`) and the warm-up pass their own; the suites, probes and tools
use the fallback, and the class doc says the buffer is for them. Nothing enforced it: a future
production caller that took the shorter form would inherit a buffer a pool thread keeps for the
JVM's life, unseen. Done when the contract is stated in `VarkaFusedKernel`'s doc and enforced by a
test.

## 2. The admission check, done

**Who calls the forms without an address.** In main code, nobody: `VarkaKernelRunner` passes the
task's scratch at both lanes, and `VarkaKernelWarmup` its own, in its probe and its calls. The
fallback's callers are the catalyst suites and probes and the benchmarks in catalyst's test
sources, all on threads of their own; `sql/varka/bench` drives kernels through Spark sessions.

**Which rule.** Item 68 offered two: refuse the fallback outside test code, or release the buffer
with its thread. A test-code rule (`Utils.isTesting`, as VARKA-275 uses) would be blind where it
matters: every end-to-end suite runs with it set, so a production path that took the shorter form
would be served silently in exactly the runs meant to catch it. Releasing the buffer keeps the
fallback silent. The structural line is the Spark task: production runs kernels in tasks (and on
the warm-up's threads, which pass scratch), and the suites and tools call kernels on threads
without a `TaskContext`. So `VarkaScratch.forRows` refuses on a thread with a `TaskContext`.

**The check**: the gate at both widths with the refusal in place, to see that no suite reaches
the fallback inside a task. Section 9 records it.

## 3. The design

### 3.1 The refusal, and the contract

* `VarkaScratch.forRows` throws `IllegalStateException` naming the bytes per row and VARKA-253
  when `TaskContext.get()` is set. The check is in the fallback, not the emitted kernel: no
  emitted byte changes, and a kernel without scratch never reaches it.
* `VarkaFusedKernel.scratchBytesPerRow`'s doc states the contract: a caller inside a Spark task
  passes its own scratch through the forms that take an address; the forms without one are for
  callers outside a task, the suites, the probes and the tools, and inside a task they refuse a
  kernel with scratch.

### 3.2 What is deliberately unchanged

The buffer's lifetime for the callers it serves; the emitted kernels; the evaluator and the
warm-up, which already pass their own.

### 3.3 Registered op counts

None move.

## 4. Files

| file | what |
|---|---|
| `VarkaScratch.java` | the refusal on a task thread, and its doc |
| `VarkaFusedKernel.java` | the contract |
| `VarkaEmitterChronoSuite.scala` | the test |
| `m7/PLAN.md`, `m10/SCOPE.md` item 68 | the records |

## 5. Tests, and what each is for

* New, in `VarkaEmitterChronoSuite` beside VARKA-198's scratch test: inside a `TaskContext`, a
  kernel with scratch refuses the seven-argument `run`, runs with an address, and a kernel without
  scratch runs; off the task, the fallback serves as before.
* The gate at both widths: no suite, end-to-end ones included, reaches the fallback inside a task.

## 6. The measurement

None: a check on a path production does not take.

### 6.1 Predictions, registered before the run

1. The gate passes with the refusal in place: no suite calls the fallback inside a task.

## 7. Risks

1. A tool that runs kernels inside a Spark job (a benchmark that drives the emitter from a task)
   would now fail; it passes its own scratch, as the evaluator does.

## 8. Sequencing

1. This plan. 2. The refusal, the doc, the test and the records.

## 9. Outcome, 10 October 2026

The refusal, the contract and the test as section 3 describes.

**Prediction 1 holds.** The gate at both widths passed with the refusal in place (every Varka suite
of `catalyst` and `sql/core`, 671 s and 669 s): no suite, the end-to-end ones that run kernels in
tasks among them, reached the fallback inside a task. The new test refuses there, runs the form
with an address there, and is served off the task.

Nothing is left over: the evaluator and the warm-up already passed their own scratch, and the
fallback's callers are all off a task.
