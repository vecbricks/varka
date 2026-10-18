# Two compilers, two queues

A test suite's JVM, pinned to four cores, compiled 1,242 methods in a form that records no
profile. The same suite on twenty-four cores compiled 252 that way. The program was the same;
what differed was the length of one queue inside the JVM. Among the 1,242 was a Vector API
template our kernels inline, and the kernel compiled in that JVM found no profile at the
template's inner call and kept a virtual call there for the life of the process. To see how a
queue decides such a thing, you need the picture of how HotSpot's two compilers are joined.

> **In short**
>
> 1. A hot method passes through up to five forms: interpreted, three from C1, one from C2. The
>    profile C2 optimises on is written by two of them only, the interpreter and C1's tier 3.
> 2. Each compiler has a queue, and C2's queue length feeds back into what C1 is asked to build:
>    while it is long, C1 builds the form with counters and no profile.
> 3. The policy decides at the moments a method's counters report in, with the queue as it is
>    then. A warm-up is a burst of such moments.

## Five forms of one method

| tier | built by | what runs | what it records |
| :--- | :--- | :--- | :--- |
| 0 | the interpreter | bytecode | calls and loop back-edges; once the method has a profile object, receiver types and branches too |
| 1 | C1 | optimised code, no profiling | nothing; for trivial methods, accessors and constant getters |
| 2 | C1 | optimised code with counters | calls and back-edges, in the method's counters |
| 3 | C1 | code that also profiles, about 30% slower than tier 2 | calls, back-edges, receiver types at calls, branch frequencies and type checks, in the profile object (the MDO) |
| 4 | C2 | code optimised on that profile | nothing; a failed speculation sends the method back to tier 0 and leaves a trap count and a decompile count in the profile |

Everything C2 knows about a method's calls and branches it reads from the MDO, and only tiers 0
and 3 write there. The rest follows from that.

## Two queues

![Two compilers, two queues](figures/svg/fig56-two-compilers-two-queues.svg)

*Figure 1. Where a method's compile requests go, and what each form records. The dashed road is
the one a long C2 queue opens.*

Compile requests go to two queues, one per compiler. The compiler threads are split a third to
C1 and two thirds to C2, and their number grows slowly with the cores: a four-core host has
three, one of them C1's; a sixteen- or twenty-four-core host has twelve, eight of them C2's.

Both are priority queues. For each waiting method the policy keeps an event rate, calls plus
back-edges per millisecond; a compiler thread takes the method with the highest rate next, and an
entry whose method has not been called for 50 ms is dropped as stale.

![A compile queue is a priority queue](figures/svg/fig60-a-compile-queue-is-a-priority-queue.svg)

*Figure 2. C1's queue on a four-core host, a sketch: the thread takes the highest rate, not the
oldest entry; an idle entry is dropped; and the queue's length raises every waiting method's
threshold.*

The two queues also talk to each other. C1's queue length scales the thresholds: the longer it
is, the more calls a method needs before C1 is asked for it at all. C2's queue length chooses the
tier: while it is longer than `Tier3DelayOn` (5) times C2's threads, a method due for tier 3 is
built at tier 2 instead, because tier 2 is faster and the method would otherwise sit in the slow
profiling form until C2 got to it. It is meant to come back: once the queue is down to
`Tier3DelayOff` (2) times the threads, the next report from a tier-2 method sends it to tier 3.

| cores | compiler threads, C1 + C2 | tier 2 while C2's queue is above | back to tier 3 at or below |
| ---: | :--- | ---: | ---: |
| 4 | 3, 1 + 2 | 10 | 4 |
| 8 | 4, 1 + 3 | 15 | 6 |
| 16 or 24 | 12, 4 + 8 | 40 | 16 |
| 64 | 18, 6 + 12 | 60 | 24 |

## How a method moves

![How a method moves between the tiers](figures/svg/fig57-how-a-method-moves.svg)

*Figure 3. The transitions and their conditions at the default thresholds.*

A method starts interpreted, and the interpreter reports its counters to the policy every 128
calls and every 1,024 back-edges. At a report the policy applies the first thresholds, 200 calls,
or 100 once calls and back-edges together pass 2,000, scaled up by C1's load, and queues the
method for tier 3, or for tier 2 if C2's queue is over its line. Tier-3 code reports every 1,024
calls, and the next thresholds are read from the profile's own counts: 5,000 calls, or 600 once
the sum passes 15,000, scaled by C2's load; then the method is queued for C2. Tier-2 code reports
every 2,048 calls and goes to tier 3 when the queue allows it, or once the method is old, ten
times the first thresholds, which overrides the queue.

There are shortcuts. A method in which C1 found nothing to profile goes from tier 2 to C2
directly; a trivial method goes to tier 1 and stays; and a method that waits long enough in the
interpreter gets its profile object there, and goes to C2 straight from the interpreter if that
profile reaches the tier-4 counts before C1 delivers.

The one arrow that goes backwards is deoptimisation. C2 compiles speculations from the profile, a
receiver type at a call, a branch never taken, and guards each with a trap. When a guard fails
the compiled code is discarded, the thread continues in the interpreter, and the profile keeps a
count of the trap and a decompile count, which the next compile reads. Past its limits C2 stops
speculating, and it sums those counts over every inlined copy of a method, which is the story of
[the previous post](https://vecbricks.github.io/why-a-warmed-kernel-can-still-allocate/).

## When C2 falls behind

![C2's queue chooses the tier](figures/svg/fig58-c2s-queue-chooses-the-tier.svg)

*Figure 4. The queue's length over a JVM's first seconds on four cores, a sketch, against the two
lines of the policy. A method that crosses its first threshold above the upper line is built
without a profile.*

Here is the policy's own trace (`-XX:+PrintTieredEvents`) of one Vector API template,
`IntVector.max`, in two runs of the same test on four cores. The framework's code is heating up
and C1's queue is long, so the threshold scale `k` sits between 1 and 6.6 and the template's
first compile does not fire at 200 calls but at 640 in one run and 768 in the other. In the first
run C2's queue was 7 at that instant: tier 3, a profile fed to 23,552 calls, and C2 compiled the
template and every kernel that inlined it from that. In the second it was 17: tier 2, the profile
frozen at 641 calls, and at the template's first tier-2 report the policy sent it straight to
tier 4. C2 trusts a profile only past 1,000 calls, a fifth of the tier-4 count, so a kernel
compiled afterwards found no receiver at the template's inner call and kept a virtual call
there. One instant, one queue length, two different programs from then on.

![One template, two runs](figures/svg/fig59-one-template-two-runs.svg)

*Figure 5. The policy's reports for `IntVector.max` in the two runs, on a log scale of calls:
the threshold scale `k` at each report, the first compile with C2's queue at that instant, the
profile's growth against C2's maturity line, and the tier-4 compile.*

![Compiles by tier](figures/svg/fig54-compiles-by-tier.svg)

*Figure 6. Every compile of the same test suite's JVM by tier, four cores at 256-bit vectors
against twenty-four cores.*

At the scale of the whole run that is the opening's 1,242 against 252. A virtual call on a
vector returns a value whose shape C2 cannot see, so the kernel boxed the vector on every
iteration, and the warm-up that waits for a kernel to stop allocating gave up on it. With
`-XX:Tier3DelayOn=1000`, which turns the detour off, the same suite on the same four cores
compiled it clean, in the one run we made.

## What a warm-up has to do

* **Run before what it shares code with.** A deoptimisation leaves a count in a profile that
  outlives the recompile, and a later compile adds it once per inlined copy. A warm-up that runs
  after the code it is meant to help deoptimises that code.
* **Not in a burst, and not too briefly.** The decision for each method is made at the moment
  it reports, with C2's queue as it is then. The queue is readable, `jcmd <pid> Compiler.queue`
  or the same operation of the `DiagnosticCommand` MBean from inside the process, and the two
  lines come from the JVM's own flags, so a warm-up can hold its rounds while the queue is over
  the lower line. We tried exactly that, and it was not enough: under the scaled thresholds the
  templates' first compile fired after the rounds had ended. A warm-up has to call each method
  often enough that its first compile falls inside the rounds, and past 2,000 calls the policy
  stops consulting the queue for that method at all; then the hold decides the tier.
* **Give the profile what C2 should see.** Tier 3 records the receivers and the branches the
  warm-up exercises, and C2 speculates on exactly those: every class, every branch, before the
  first compile.
* **Read the tiers, not the timings.** `-XX:+LogCompilation` names each compile's tier
  (`<nmethod ... level='2'>`), and `-XX:+PrintTieredEvents` prints every decision of the policy
  with both queue lengths beside it. A method at tier 4 whose last profile came from tier 2 is a
  method C2 compiled blind.

## Where this comes from

JDK 25, `src/hotspot/share/compiler/compilationPolicy.cpp` at tag `jdk-25-ga`: the transitions
are `transition_from_none`, `transition_from_limited_profile` and `transition_from_full_profile`,
the queues `select_task`, the thread split `initialize`; the header comment of the matching
`.hpp` explains the design in the authors' words, and the thresholds are the defaults of
`java -XX:+PrintFlagsFinal`. Figures 5 and 6 and the traced runs are in
[`VARKA-389.md`](https://github.com/vecbricks/varka/blob/master/sql/varka/plans/m7/VARKA-389.md)
9.5, on an AMD Zen 5 laptop pinned to four cores with `taskset` and `-XX:MaxVectorSize=32`.
