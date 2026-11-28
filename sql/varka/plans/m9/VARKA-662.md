# VARKA-662: The nanosecond timestamps' layout

*Planned on 28 November 2026, the third writing. The first (27 November) and the second (28
November) were each read by a builder; sections 16 and 17 say where every finding of the two
reviews went. A third review (28 November) found seventeen defects of the text and the tools and
none of the design; section 18 says where each went. On 28 November 2026 the owner answered
seven of section 15's eight questions; section 19 says what each answer changed. The plan
stands alone: a builder needs this file and the repository's `AGENTS.md` files.
The row is not started: milestone 9 waits for the
owner's word (`m9/PLAN.md` decision 18) and for milestone 8's close (the tag `varka-m8`). The
code facts were checked on master `9e7b1612402`, in `/home/max/proj/varka-m9-read-w`; the edits
to other plans (section 13) on master `92fd5a62d13`, which differs from it in three plan files
only. In this writing the plan is narrowed to the study: the storage work of the second writing
(its parts N1 to N3) is no longer here (4.11). And a JVM was run, on JDK-only classes: every
generated loop was compiled by `javac` of JDK 25 and run over the Python checker's cases at 512,
256 and 128 bits, with and without `-XX:UseAVX=2`, and its inlining tree was read as the
benchmark reads it. Section 3 says what ran and what it showed. The benchmark, the checker and
the suite, which need Spark on the class path, were compiled against stubs and not against
Spark; 5.0 has the table of every name they use and the repair procedure.*

## 1. What this row is

**The row's text** (`m9/PLAN.md` section 3): "The nanosecond timestamps' layout." Its done-when
today: "a nanosecond column is read from the cache's struct and written back; both layouts are
built and measured on row 655's classes, the slower kept as an option arm; the zone-free entries
fuse; a field over the microsecond word, a cast to microseconds and `second` with its fraction
fuse; and the values at the range's ends answer as `TimestampNanosVal` does".

**What changed on 28 November 2026.** The owner wrote Spark's proposal for these types
(SPARK-56822, public). Its choice of two parts, a long of microseconds and a short of nanoseconds
within one, was made for Spark's scalar row engine, and he said it "might be not efficient for
vectorized implementation ... We should do the same for Varka": prototype the candidate
representations for vectors and measure them, with no measurement from outside Varka. He added
that Varka may later choose among "different isomorphic representations that depend on user's
query and how the data comes from data sources (in which form)", perhaps with an e-graph. Asked
how many representations to build, he did not know, and guessed: "at least 2: micros (Long) +
nanos of micros (Short) and seconds (Long) + nanos Of second (Int). Maybe one more date (Int) +
time of day (Long) as nanos of day."

So the row is cut in two. **This row is the study**: it measures, and it builds nothing a
kernel runs. **A new row, 699**, filed by this plan's pull request (section 13), builds the
storage (the cache's struct in and out) and the kernels that compute, and is planned after this
row's numbers are on master and row 661 is built. The number 698, which the second writing
used, is taken: row 698 of `m9/PLAN.md` is the spike of a two-word load with no mask at two long
lanes, and section 12 says how this row meets it.

**This row, in plain words.**

1. *A pilot* (parts P1a to P4). The formulas, the generator, a harness, four operations and
   three arms: Spark's parts with the short in a short vector, Spark's parts as a scalar loop,
   and seconds with nanoseconds in a mixed-width layout. It answers, before the full study is
   run: do the loops settle under C2; does any Vector API call stay in Java where this plan's
   own JVM runs found none; how long a run takes.
2. *The study* (parts S1 to S3). Thirteen operations over four representations in every
   layout, 322 loops, each held to Spark's own methods, timed on the laptop at 512, 256 and 128
   bits and on the AMD EPYC 7763 runners at 256 and 128.
3. *The proofs, the cost table and the rule* (parts T1, T2a and T2b). Each conversion between two
   representations as an equality with its exact range, proved, and each guard's lane formula
   proved exact; the numbers as a table a later tool can read; and a rule with no judgement that
   says what each class of operation defaults to, by host and width, among the representations
   row 699 builds: all three that hold the whole range, by the owner's answer of 28 November
   2026, and one long where it is the fastest (section 19).

**The done-when after this plan's edit** (section 13, edit 1), clause by clause, each a check.

| # | clause | the check that says it is met | part |
| ---: | :--- | :--- | :--- |
| 1 | the candidate representations are measured in hand loops, each held to Spark's methods | the results files of `VarkaNanosRepresentationSpikeBenchmark` are in `sql/catalyst/benchmarks` for the laptop at three widths and the EPYC 7763 at two, with their provenance files; `VarkaNanosRepresentationSpikeSuite` passes | P4, S3 |
| 2 | the numbers are a cost table, each conversion an equality with its range, proved | `python3 dev/varka_nanos_costs.py check sql/catalyst/benchmarks/VarkaNanosRepresentationSpikeBenchmark-*-results.txt` exits 0; `dev/varka_prove.sh --solver both nanos_representations.smt2 nanos_guards.smt2` and `dev/varka_prove.sh nanos_divisions.smt2` pass | T1, T2b |
| 3 | a rule names each class's default representation by host and width, among those row 699 builds | section 11 holds the output of `python3 dev/varka_nanos_costs.py rule sql/varka/plans/m9/results/VARKA-662/nanos_representation_costs.tsv` and the owner's answers to section 15 | T2b |

**What must be merged before it starts.**

| what | why | how the builder checks it | for |
| :--- | :--- | :--- | :--- |
| this plan on master | the build cites it and takes its files from it | `test -f sql/varka/plans/m9/VARKA-662.md && echo merged` prints `merged` | every part |
| milestone 8's close | `m9/PLAN.md` decision 11 | `git tag -l varka-m8` prints `varka-m8` | every part |

No part needs another row: the parts touch no production file and read no census. Row 661 (the
two type ids) and row 655's nanosecond baselines are needed by row 699, not by this row.

**What it gives the rows after it.**

| row | what it reads of this row | where |
| :--- | :--- | :--- |
| 699, the nanosecond storage and kernels | the rule's outcome and the owner's answers (section 11); the cost table; the equalities, the guards and their proof files; the divisions with no divide; the outline of the storage (4.11) | 4.8 to 4.12; `sql/varka/plans/m9/results/VARKA-662/nanos_representation_costs.tsv` |
| 698, the two-word load with no mask at two long lanes | the 128-bit results files and their `intrinsic check` lines, which name the loops whose masks fall back there; the loads of the two-word forms as generated | section 12; `VarkaNanosSpikeLoopsR1.java`, `R3.java`, `R4.java` |
| 673 to 675, the nanosecond fields, casts and functions | the same as row 699, through it | the same |
| 679, the library `vechrono` | the study's numbers for the shapes it holds, and the divisions with no divide (4.2) | `VARKA-679.md` 10.1, by section 13's edit 8 |
| a later chooser of a representation by query and by source (`m10/SCOPE.md` item 11) | the cost table's rows, with what may and may not be concluded from them, and the equalities with their range conditions | 4.8, 4.9 |

**The goals it serves** (`m9/PLAN.md` 1.1), each with the item of 1.2 it moves.

- **G4**: "**G4. Nanosecond `TIMESTAMP_NTZ` and `TIMESTAMP_LTZ`, precision 7 to 9 (phase 2).**
  Admitted while Spark's `spark.sql.timestampNanosTypes.enabled` is on, as Spark itself admits
  them; the functions Spark accepts over them fuse, and the casts among `DATE`, `TIME`, the
  microsecond and the nanosecond timestamps." Item 4 of 1.2: "**G4:** at least three quarters of
  each nanosecond type's pairs fused under the flag, and at least two thirds of the nanosecond
  types' cast pairs row 661 adds (20, `VARKA-660.md` 4.2) fused or declined with their reason."
  This row serves G4 only through row 699: it fuses no entry, and `python3 dev/varka_census.py
  --summary` prints the same before and after every part of it. What it moves is what row 699's
  kernels default to: a reader checks that by section 11's sentence `Row 699 builds ...`.
- **G7**, only through row 699: this row times hand loops, not kernels, and no bar of 1.2 is
  read on a hand loop.
- **G1, G2, G3, G5 and G6** are not served.

## 2. What an agent must know

**Spark's value.** `TimestampNanosVal` (`common/unsafe`) holds `epochMicros`, a long, and
`nanosWithinMicro`, a short from 0 to 999. The exact instant or local time in nanoseconds is
`epochMicros * 1000 + nanosWithinMicro`. So the first word is the floor of the nanoseconds by
1000, also before the epoch: one nanosecond before the epoch is `(-1, 999)`. Two values are
ordered by the first word and then by the second (`TimestampNanosVal.compareTo`). The precision
`p` is in the type and not in the value: at precision 7 the second word is a multiple of 100.

**The cache's struct.** Spark's Arrow cache writes a column of either type as an Arrow
`StructVector` with two children: `epochMicros`, eight bytes a row, little-endian; and
`nanosWithinMicro`, two bytes a row. That struct is the only source of a nanosecond column that
reaches a kernel today, so the study's loops read Spark's two parts from two such buffers. No
part of this row reads or writes an Arrow vector: 4.11 says who does.

**A representation**, or form, is how the value is held as numbers. Four are measured:

| | the value | the words as stored, a row | the range of first words it holds | outside it |
| :--- | :--- | :--- | :--- | :--- |
| R1 | `m` epoch microseconds, `n` nanoseconds within one, 0 to 999 | a long and a short: 10 bytes | every long `m`: Spark's storage | nothing is outside |
| R2 | `x = m * 1000 + n`, epoch nanoseconds | a long: 8 bytes | `m` from -9223372036854775 to 9223372036854774: 1677-09-21 to 2262-04-11 | a batch declines |
| R3 | `s` epoch seconds, `f` nanoseconds of the second, 0 to 999999999 | a long and an int: 12 bytes | every long `m`: `s` then runs from -9223372036855 to 9223372036854 | nothing |
| R4 | `d` epoch days, `t` nanoseconds of the day, 0 to 86399999999999 | an int and a long: 12 bytes | every long `m`: `d` then runs from -106751992 to 106751991 | nothing |

R1, R3 and R4 are the owner's three, and each holds every value Spark can hold (4.9 proves the
ranges). R2 is the form data arrives in from a Parquet file's `INT64` of nanoseconds and from
Arrow's nanosecond timestamp, so the study measures the conversion from it into each of the
others, and R2 itself as what a kernel computes in, marked range-limited. A fifth form, the
nanoseconds as a 72-bit integer (SPARK-56822's rejected design C.4), is not measured: the first
writing's prototype found that it holds first words from -2361183241434822606 to
2361183241434822605 only, about 74800 years each way, so it is range-limited as R2 is, and its
arithmetic is two-limb where R2's is one word. It stays in 4.9's table as an equality. A reason
to build it as an arm would be a source that stores it; none does.

**The arriving form** is the form a loop's inputs are stored in, and **the stored form**, or
sink, the form a timestamp result is written in. For a kernel both are R1 today. The study also
measures data that arrives as R2, and, for R3 and R4, data already held in the form.

**A layout** is how the words of a form sit in vector lanes.

| layout | every long word | a narrow word (`n`, `f` or `d`) | exists |
| :--- | :--- | :--- | :--- |
| H | a `LongVector` of the preferred species | Spark's short `n` in a `ShortVector`; `f` and `d`, where a conversion edge meets them, as in M | every width; R1 |
| L | the same | widened into a `LongVector` at its load, narrowed at its store | every width |
| M | the same | an `IntVector` of as many lanes, half the long vector's bits | every width; R1, R3, R4 |
| S | no vector: a plain scalar loop | an `int` | every width |

**H is R1's first layout.** It is the owner's first form as he said it, "micros (Long) + nanos of
micros (Short)": the two words in the lanes of their own widths, with no conversion at the load
or the store. `ShortVector` species exist at 64, 128, 256 and 512 bits. A long vector of eight
lanes pairs with the 128-bit one and of four with the 64-bit one. At two long lanes the pair
would be a 32-bit vector, which does not exist: there every layout reads and writes the short
through the 64-bit species, four shorts, of which the group's two are used, and layout H crosses
a mask between its four short lanes and the two long lanes by the mask's bits, where at four
lanes and more it is a cast (4.3). So H exists at every width, with one text of each loop.

**An arm** is a form in a layout: R1H, R1L, R1M, R1S, R2L, R2S, R3L, R3M, R3S, R4L, R4M, R4S,
twelve. **A loop** is an operation in an arm for an arriving form and a sink.

**What C2 does with these loops** (`sql/varka/skills/the-jit.md`,
`sql/varka/skills/vector-api-and-width.md`, `sql/varka/skills/emitter-and-ir.md`), the six facts
the study is built around. The first four were read; the last two this plan's own JVM runs
found (section 3):

- *The conversions to a double.* C2 lowers `L2D` and `D2L` only with AVX-512DQ. On the EPYC
  7763, an AVX2 host, a division of a long lane through double lanes runs as the Vector API's
  Java code, which is correct and allocates. So no loop of the study divides and none converts
  to a double (4.2).
- *One species an element type.* A second species of one element type in a JVM makes the shared
  vector templates bimorphic, and C2 then boxes a vector an iteration. The study uses one long
  species, one int species and one short species, and its suite runs in a JVM of its own.
- *Level 4 is not proof.* A loop at level 4 can still call the Java fallback. The proof is the
  loop's inlining tree, read in a child JVM (VARKA-577's check, 4.5).
- *A loop calls nothing of its own.* C2 stops inlining a method's callees once the method's
  graph passes 18000 nodes (`NodeCountInliningCutoff`, a develop flag that "cannot be set on a
  product JVM at all", `emitter-and-ir.md`). Only a JDK method marked `@ForceInline` is exempt,
  which a `CompileCommand=inline` does not make of a method. A loop of this study passes that
  count. The second writing's loops called shared steps of `VarkaNanosSpikeOps` and asked for
  them by that compile command; run, the larger loops left those steps as calls (`failed to
  inline: NodeCountInliningCutoff`), with their vectors boxed. So a generated loop now makes
  every Vector API call in its own body (4.4), and the run needs one compile command, not two.
- *No minimum or maximum of long lanes with AVX2.* `reduceLanes(MIN)` and `reduceLanes(MAX)` of
  a long vector are left in Java under `-XX:UseAVX=2` at every width (AVX2 has no 64-bit signed
  minimum or maximum). The second writing's range guard ended in them, so every guarded loop
  would have been a fallback on the EPYC 7763. A guard is now one OR accumulator, whose
  reduction is lowered at every width with AVX2 (4.3).
- *At two lanes, no mask.* On x86 at 128 bits C2 lowers no mask of two lanes, of longs or of
  ints: `compare`, `blend`, a masked `add` or `sub`, a mask's `and` and `or`, a mask's `cast`,
  `toLong` and `fromLong` are Java code. Everything else the loops are made of is lowered there,
  the two-lane `IntVector`'s loads, arithmetic and conversions included. So at 128 bits the
  loops with no mask are vector code in every layout, and the loops with a mask are not, in any
  layout. The scalar arms are there for that width, and the mask-free forms are row 698's (4.7,
  section 12).

**The names this row reads.**

| name | where | meaning |
| :--- | :--- | :--- |
| `TimestampNanosVal`, `TimestampNTZNanosType` | Spark, `common/unsafe` and `sql/api` | the value and a type of it |
| `DateTimeUtils.getHours`, `getSecondsWithFractionNanos`, `getYear`, `microsToDays`, `timestampNanosAddDayTime`, `truncTimestampNanos`, `DateTimeUtils$.MODULE$.TRUNC_TO_HOUR`; `XXH64.hashLong`, `hashInt` | Spark, catalyst | the oracle of the study's loops (4.6) |
| `VarkaBenchmarkBase`, `VarkaBenchmark`, `VarkaDecimalRemainderSpikeBenchmark` | catalyst tests, package `org.apache.spark.sql` | the benchmark base; the spike whose harness the study copies |
| `VarkaEmitterTestSupport.awaitC2Idle`, `@VarkaSuite(ownJvm = true)` | catalyst tests | the wait for a quiet C2; a suite in a JVM of its own |
| `.github/workflows/benchmark.yml`, `dev/varka_bench_sections.py` | the repository | the runner's dispatch; the tool that joins the sections of two results files |

**The names this row adds.**

| name | part | where | meaning |
| :--- | :--- | :--- | :--- |
| `dev/varka_nanos_spike.py` | P1a | `dev` | the lane values, R1 and R2 as formulas, the loops, and the check on numbers |
| `dev/varka_nanos_spike_r34.py` | P1b | `dev` | R3 and R4 as formulas |
| `dev/varka_nanos_spike_gen.py`, `VarkaNanosSpikeOps`, `VarkaNanosSpikeLoopsR1` to `R4` | P2 | `dev`; catalyst tests, package `org.apache.spark.sql` | the Java of the loops, generated from the formulas; the species and the buffers the loops share |
| `VarkaNanosSpikeCheck`, `VarkaNanosRepresentationSpikeSuite` | P3 | catalyst tests | each loop held to Spark's methods, and to the table of the rows it must decline |
| `VarkaNanosRepresentationSpikeBenchmark`, `VarkaNanosSpikeLoopsControl`, the results and provenance files | P4, S3 | catalyst tests; `sql/catalyst/benchmarks` | the harness; the control loop; the numbers |
| `dev/varka_nanos_spike_proofs.py`, `sql/varka/proofs/nanos_representations.smt2`, `nanos_divisions.smt2`, `nanos_guards.smt2` | T1 | `dev`; `sql/varka` | the equalities, the divisions and the guards, proved |
| `dev/varka_nanos_costs.py`, `sql/varka/plans/m9/results/VARKA-662/nanos_representation_costs.tsv` | T2a, T2b | `dev`; `sql/varka` | the cost table and the rule |

## 3. The admission check

Each fact was checked on master `9e7b1612402`, in `/home/max/proj/varka-m9-read-w`. Below,
`U=common/unsafe/src/main/java/org/apache/spark/unsafe/types`,
`CS=sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst`,
`G=sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen`, `V=$G/varka`,
`TQ=sql/catalyst/src/test/java/org/apache/spark/sql`, `TJ=$TQ/catalyst/expressions/codegen/varka`,
`W=.github/workflows`, `K=sql/varka/skills` and
`X64=sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/XXH64.java`. Line
numbers are those of that commit and are given to find the code once; a builder reads the
method. Each command runs as printed, in `bash`, from the repository's root.

**How Spark holds a nanosecond timestamp, and its rules for the study's operations.**

| # | fact | where | command | prints |
| ---: | :--- | :--- | :--- | :--- |
| 1 | The value is a long and a short | `$U/TimestampNanosVal.java`, lines 57 and 59 | `grep -c 'public final long epochMicros;' $U/TimestampNanosVal.java; grep -c 'public final short nanosWithinMicro;' $U/TimestampNanosVal.java` | `1`; `1` |
| 2 | The order is the first word, then the second | the same file, `compareTo` at 125 | `grep -c 'int cmp = Long.compare(epochMicros, that.epochMicros);' $U/TimestampNanosVal.java` | `1` |
| 3 | The sum with a day-time interval adds to the first word and keeps the second; it raises where the microseconds overflow | `$CS/util/DateTimeUtils.scala`, `timestampNanosAddDayTime` at 334 | `grep -c 'TimestampNanosVal.fromParts(epochMicros, start.nanosWithinMicro)' $CS/util/DateTimeUtils.scala` | `2` (the months' sum is the other) |
| 4 | A truncation drops the second word | the same file, `truncTimestampNanos` at 717 | `grep -c 'TimestampNanosVal.fromParts(truncatedMicros, 0.toShort)' $CS/util/DateTimeUtils.scala` | `1` |
| 5 | `extract(SECOND ...)` is the microseconds of the minute times 1000 plus the second word | the same file, `getSecondsWithFractionNanos` at 159 | `grep -c 'getMicroseconds(ts.epochMicros, zoneId) \* NANOS_PER_MICROS + ts.nanosWithinMicro' $CS/util/DateTimeUtils.scala` | `1` |
| 6 | A hash folds the first word by `hashLong` and then the second by `hashInt` | `$CS/expressions/hash.scala`, line 797 | `grep -c 'case t: TimestampNanosVal => hashInt(t.nanosWithinMicro, hashLong(t.epochMicros, seed))' $CS/expressions/hash.scala` | `1` |
| 7 | `XXH64.hashLong` and `hashInt` are public and static | `$X64`, lines 60 and 71 | `grep -c 'public static long hashInt(int input, long seed) {' $X64; grep -c 'public static long hashLong(long input, long seed) {' $X64` | `1`; `1` |
| 8 | Epoch nanoseconds unpack by `floorDiv` and `floorMod` | `$CS/util/DateTimeUtils.scala`, `epochNanosToTimestampNanos` at 394 | `grep -c 'val epochMicros = Math.floorDiv(epochNanos, NANOS_PER_MICROS)' $CS/util/DateTimeUtils.scala` | `1` |

**What the house's long lane does for a division today, the spike to copy, and the JIT's limits.**

| # | fact | command | prints |
| ---: | :--- | :--- | :--- |
| 9 | The long lane's `ConstDivide` takes the conversion through doubles, or, where the host's converts fall back, a form built on the bits of `2^52` that needs a mask | `grep -c 'static boolean takesMagicDivide(Analysis analysis, ConstDivide n) {' $V/VarkaDivisionLowering.java` | `1` |
| 10 | The exact floor division of every long exists only for a divisor whose odd part keeps the shifted dividend under 2^52; 1000 and 1000000 do not | `grep -c 'its odd part leaves the shifted dividend past the exact range' $G/VarkaNodeCompiler.java` | `1` |
| 11 | The remainder spike refuses to run without its compile commands | `grep -c 'static final String DONTINLINE_LOOPS = "-XX:CompileCommand=dontinline,"' $TQ/VarkaDecimalRemainderSpikeBenchmark.java` | `1` |
| 12 | Its intrinsic check reads a child JVM's inlining tree | `grep -c 'static List<String> fallbacks(List<String> lines, String owner, String method, boolean body) {' $TQ/VarkaDecimalRemainderSpikeBenchmark.java` | `1` |
| 13 | A timed case warms up for two seconds and runs at least five iterations over two seconds | `grep -c 'return minIterations(5).warmup(Duration.ofSeconds(2)).minTime(Duration.ofSeconds(2));' $TQ/VarkaBenchmark.java` | `1` |
| 14 | A new source file whose first lines say `Generated by` counts nothing toward a pull request's size | `grep -c 'GENERATED_MARK = re.compile(' dev/varka_msg_check.py` | `1` |
| 15 | The node count past which C2 stops inlining cannot be raised on a product JVM | `grep -c 'flag it actually is cannot be set on a product JVM at all' $K/emitter-and-ir.md` | `1` |
| 16 | A long OR reduction is lowered at two long lanes on AVX2 | `grep -c '^## A lane read, a lane insert and a long OR reduction lower at two long lanes on AVX2' $K/vector-api-and-width.md` | `1` |

**How a results file is written, and how two are joined.**

| # | fact | command | prints |
| ---: | :--- | :--- | :--- |
| 17 | A run that writes a results file refuses `-Dvarka.bench.only` and a dry run | `grep -c 'runs part of a benchmark, which must not' $TQ/VarkaBenchmarkBase.java` | `1` |
| 18 | The workflow hands the extra JVM options to the driver as one argument, and splits them by words only for a line that prints flags | `grep -c 'EXTRA_ARGS+=(--driver-java-options "$EXTRA_JAVA_OPTIONS")' $W/benchmark.yml` | `1` |
| 19 | A results file prints its times as whole milliseconds and its rate to a decimal of a million rows a second | `grep -c '"%5.0f" format result.bestMs' core/src/test/scala/org/apache/spark/benchmark/Benchmark.scala; grep -c '"%10.1f" format result.bestRate' core/src/test/scala/org/apache/spark/benchmark/Benchmark.scala` | `1`; `1` |
| 20 | `dev/varka_bench_sections.py splice` writes one results file with the named sections of a second, and appends a section the first lacks | `grep -c 'dev/varka_bench_sections.py splice OLD NEW --sections' dev/varka_bench_sections.py; grep -c 'A title OLD lacks is appended.' dev/varka_bench_sections.py` | `1`; `1` |
| 21 | `dev/varka_bench_merge.py` joins the shards of the date surface alone, by their provenance | `grep -c 'DateSurfaceBenchmark --shard I/N' dev/varka_bench_merge.py` | `1` |
| 22 | The scoped catalyst job of a Build has one step that runs a check with no JVM, on its first shard | `grep -c "if: matrix.module == 'catalyst' && matrix.shard == '0/3'" $W/build_and_test.yml` | `1` |

**What was run** in this writing. The files are the appendices'; the runs were made on copies in
the planner's scratch folder, on the laptop (AVX-512, a 256-bit datapath), one JVM at a time.
Nothing was timed.

- `python3 dev/varka_nanos_spike.py` (appendices A and B), 33 seconds: `divisions: 9 floor
  divisions of every long and 15 bounded ones agree`, then `forms: R1 R2 R3 R4`, then `loops: 322
  loops over 3271 cases each: 811898 answers equal the exact integers, 39598 declined`, then `all
  checks passed`. With appendix A alone (no file of R3 and R4), 11 seconds: `forms: R1 R2`,
  `loops: 112 loops over 3207 cases each: 227964 answers equal the exact integers, 26730
  declined`. Each loop's body is run on numbers with wrapping lane arithmetic, the divisions as
  the lanes compute them, and held to exact integers; each guard's lane formula is held to the
  exact test it stands for on every case; and a loop declines a case exactly where the table of
  the guards says (`must_decline`, 4.6).
- **Every generated loop compiled.** `javac` of JDK 25 (`--add-modules jdk.incubator.vector
  -Xlint:all`) over `VarkaNanosSpikeOps.java` and the four generated classes of the set `study`,
  322 loops in 15562 lines: no error and no warning but the incubator's. The second writing's
  text compiled too (its 315 loops): no Vector API call it printed was missing or had another
  signature.
- **Every loop's answers equal the Python reference's.** A JDK-only harness (not a file of this
  plan) ran each of the 322 loops over the Python checker's 3271 cases, the rows its arriving
  form holds: 852772 rows a loop answers, each compared word for word with the formula's own run
  on numbers, and 39598 rows a loop must decline, each run alone as a batch of 64 copies. Under
  each of `-XX:MaxVectorSize=64`, `32`, `16`, `-XX:UseAVX=2`, `-XX:UseAVX=2
  -XX:MaxVectorSize=16`, and in the interpreter at 16: not one line differs, in the first calls
  and after 100 to 300 more calls of each loop, with the loops kept out of line as the harness
  keeps them.
- **The planted faults fail in Java as in Python.** Thirteen, each planted in a copy, the
  classes generated and compiled again and the harness run at 256 bits. The eight in a formula
  (a carry's `>=` made `>`, seconds and then days; a day count's 306 made 305; the repair of a
  division left out; a window's bound one wider; a sum's guard left out; the window's second
  difference left out; the floor guard's complement left out) fail `python3
  dev/varka_nanos_spike.py` by an assertion, and the classes generated from the faulted formula
  differ from the reference in 18 to 21620 lines. The five in what the generator prints (`ASHR`
  printed `LSHR`; a short's width 8 for 2; a crossed mask inverted; `GE` printed `GT`; an
  exclusive or printed `or`) pass the Python check, which cannot see them, and differ in the
  harness in 124 to 136805 lines, or stop it at the first loop by a read past a buffer's end.
- **The checker's table of the rows a loop must decline** (`VarkaNanosSpikeCheck.declines`,
  appendix F) was compiled alone, JDK only, and held to the Python table over 771956 rows of
  the loops' cases: none differs.
- **The intrinsic check of every loop**, as the benchmark makes it (a child JVM a loop, the
  inlining tree of its last level-4 compile, 4.5), under the six flag sets of section 14. 4.7 has
  the table of what it found. In short: at 512 and 256 bits, with AVX-512 and under
  `-XX:UseAVX=2`, every call of every vector loop is intrinsified, the guarded loops included;
  at 128 bits the loops with a mask have calls left in Java and the others have none.
- **The hand-written Java against stubs.** `VarkaNanosSpikeCheck`, the suite, the benchmark and
  the control were compiled by `javac` against stubs of the Spark and JUnit classes they name,
  each stub with the signatures of 5.0's table, and the suite's four tests were run under them
  over all 322 loops, with `java.time` standing in for Spark's methods: `Passed 4, Failed 0` at
  512, 256 and 128 bits. The first run failed, and rightly: the checker read a result of
  seconds or of days back by `Math.multiplyExact`, which overflows for the 775808 microseconds
  above `Long.MIN_VALUE`, where the first second times a million is under the smallest long
  though the value is not. `VarkaNanosSpikeCheck.result` now multiplies in exact integers. 5.0
  says what the stubs show and what they do not.
- The three proof files under the solvers of `~/.cache/varka-solvers`.
  `nanos_representations.smt2`: 34 checks, and `nanos_guards.smt2`: 30 checks, each as expected
  under Z3 5.1.0 and under cvc5 1.4.1 with `--incremental`, each run well under a second.
  `nanos_divisions.smt2`: 47 checks, each as expected under Z3 in one second; under cvc5 it
  answers four and does not finish the fifth within 60 seconds, so that file is Z3's (4.9).
- `python3 dev/varka_nanos_costs.py selftest` (appendix D): `selftest: 103 checks, 0 failed`, and
  its fourteen planted faults fail it (5.9).
- `ruff` 0.14.8 on the five Python appendices, as copies in a directory `dev/` beside a copy of
  the repository's `pyproject.toml`: `ruff check` prints `All checks passed!` and `ruff format
  --check` prints `5 files already formatted`. The appendices are the formatter's output; after
  it every generated class and every proof file has the sum it had before, and no Python line
  that `dev/varka_precommit.sh` reports is over 100 columns.
- The Scala declarations behind every call the hand-written Java makes into a Scala object or
  class, read in the source (5.0's table of calls). The stubs now have the shape scalac writes:
  a class `DateTimeUtils$` with `MODULE$`, and no static `TRUNC_TO_HOUR`.
- Every premise grep above, every B0 line of section 5 and every old text of section 13.

**What I could not check.**

- No loop was timed, so nothing here says which representation is faster, that a loop settles
  under the harness, or how long a run takes. 4.7 has the count of cells and a priced estimate;
  the pilot's reading scales it.
- The intrinsic check was run on the laptop, where `-XX:UseAVX=2` stands for an AVX2 host. The
  EPYC 7763 is a Zen 3 and not this CPU with a flag: the pilot's first runner reading is the
  check of it (5.5).
- The benchmark, the checker and the suite were not compiled against Spark and JUnit. What the
  stubs cannot show is in 5.0, with the repair procedure.
- The two `gh` commands of 5.5 that read a run's minutes: no run exists and the planner had no
  network. The log's line format is `gh`'s as the house's other runs print it; where the
  first command finds no line, 5.5 says what the lead writes.
- Row 661 is not built, and rows 655, 656 and 654 neither. This row reads none of them.
## 4. The design

### 4.1 The study: what is measured

**A loop** is named by five things: the operation; the form it computes in; the layout; the
form its operands arrive in; the form a timestamp result is stored in. Its method is
`loop<Operation>R<form><layout>A<arrival>Z<sink>`, as `loopHourR3MA1Z1`, and its label in a
results file is `<OPERATION> R<form><layout> <arrival>><sink>`, as `HOUR R3M 1>1`. A loop loads
its operands as they are stored, converts them to the form it computes in, computes, converts a
timestamp result to the form it is stored in, and stores. So a representation pays its
conversions inside what is timed, where a kernel would.

**The operations.** `a` and `b` are timestamp columns, `q` a column of microseconds.

| operation | SQL it stands for | result | class (4.10) |
| :--- | :--- | :--- | :--- |
| `ROUND` | nothing: load `a`, store it | a timestamp | the base of every cost |
| `LT` | `a < b` | a selection word a 64 rows | compare |
| `PICK` | `CASE WHEN a < b THEN a ELSE b END` | a timestamp | choice |
| `ADD` | `a + q`, `q` a day-time interval column | a timestamp | interval |
| `ADDC` | `a + INTERVAL '1:00:01.5' HOUR TO SECOND` | a timestamp | interval |
| `HOUR` | `hour(a)` | an int a row | clock |
| `SEC` | `extract(SECOND FROM a)`: the nanoseconds of the minute | a long a row | clock |
| `YEAR` | `year(a)` | an int a row | date |
| `TRUNC` | `date_trunc('HOUR', a)` | a timestamp | truncate |
| `MICROS` | `CAST(a AS TIMESTAMP_NTZ)`: the epoch microseconds | a long a row | stored |
| `ZONE` | the local time of the instant `a` in a zone of three offsets around two changes | a timestamp | zone |
| `HASH` | `xxhash64(a)` | a long a row | stored |
| `CHAIN` | `date_trunc('HOUR', CASE WHEN a < b THEN a ELSE b END + INTERVAL '1:00:01.5' HOUR TO SECOND)` | a timestamp | chain |

The literal is one hour and a second and a half, 3601500000 microseconds, so that its split
has a part in every word: 3601 seconds and half a second for R3; no day, and 3601 and a half
seconds as nanoseconds, for R4. With a whole hour a kernel would know the carry dead and the
loop would time nothing of it.

`ZONE` is plain arithmetic and reads no class of row 682: three offsets around two changes,
as constants. The zone is `America/Los_Angeles` over the year 2025: the offset is -28800
seconds before 2025-03-09T10:00:00Z (epoch second 1741514400), -25200 from it to
2025-11-02T09:00:00Z (1762074000), and -28800 after. Each form compares in its own unit: R1
on `m` against the change in microseconds, R2 on `x` in nanoseconds, R3 on `s` in seconds, R4 on
the pair `(d, t)`. The oracle is `java.time.zone.ZoneRules.getOffset` of that zone, and every
`ZONE` row that is compared with it is an instant of 2025, where the two agree.

`EQ` is not a loop of its own. Its cost is bounded by `LT`'s, which makes the same loads and
two of the same three compares; an `IN` list of `k` literals is `k` of it. A null test costs
the same in every form, since a kernel reads validity from the stored bitmap.

**Which loops exist.** 322, listed by `python3 dev/varka_nanos_spike_gen.py table study`:

| arms | arriving as Spark's parts (R1) | arriving as epoch nanoseconds (R2) | arriving in the arm's own form | conversion edges into the form | loops |
| :--- | :--- | :--- | :--- | :--- | ---: |
| R1H, R1L, R1M, R1S | all 13: this is their own form | `ROUND`, `LT`, `HOUR`, `CHAIN` | - | from R2, R3, R4 | 20 each |
| R2L, R2S | `ROUND`, `LT`, `PICK`, `ADD`, `ADDC`, `CHAIN` | all 13: its own form | - | from R1, R3, R4 | 22 each |
| R3L, R3M, R3S | all 13 | `ROUND`, `LT`, `HOUR`, `CHAIN` | all 13 | from R1, R2, R4 | 33 each |
| R4L, R4M, R4S | all 13 | `ROUND`, `LT`, `HOUR`, `CHAIN` | all 13 | from R1, R2, R3 | 33 each |

The first column is what a kernel meets today, and the rule of 4.10 reads it. The second is
the mirror: data that arrives as epoch nanoseconds, every operation for R2 and four for the
others, which pay a conversion first. The third is the operation alone, over a value already
in the form: its loops are what the cost table's cells are made of (4.8), and they are also
what a source that stores seconds or days would hand a kernel. An edge is a `ROUND` that
arrives in one form and is stored in another: the conversion with no operation. R2 over
Spark's parts runs the compares and the sums only, the loops where one long is natural; its
fields over Spark's parts would be R1's with a conversion in front.

**The pilot's loops** are twelve of them: `ROUND`, `LT`, `HOUR` and `ADDC` in R1H, R1S and R3M,
arriving and stored as Spark's parts. Between them they hold a load and a store of each width, a
compare whose mask crosses lane widths, a selection word, a floor division, a bounded division,
a masked add and a masked subtract, a guard of each of two kinds (the sum's in `ADDC R1H`, the
window's in `ROUND R3M` and `ADDC R3M`), and a scalar loop of each.

**The buffers each loop reads and writes**, for layout L (M, H and S touch the same buffers).
A loop reads exactly the buffers its result depends on: the generator drops every statement
nothing reads, so this table is read off the generated code, not written by hand. `a`, `b`
and `o` are the two operands and the output of a timestamp result; the letter after is the
buffer of 4.3; `q`, `ol`, `oi` and `words` are the interval column, the long output, the int
output and the selection words.

| operation | R1 from Spark's parts | R2 from epoch nanoseconds | R3 from Spark's parts | R4 from Spark's parts | R3 held | R4 held |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `ROUND` | `aM aN > oM oN` | `aX > oX` | `aM aN > oM oN` | `aM aN > oM oN` | `aF aS > oS oF` | `aD aT > oD oT` |
| `LT` | `aM aN bM bN > words` | `aX bX > words` | `aM aN bM bN > words` | `aM aN bM bN > words` | `aF aS bF bS > words` | `aD aT bD bT > words` |
| `PICK` | `aM aN bM bN > oM oN` | `aX bX > oX` | `aM aN bM bN > oM oN` | `aM aN bM bN > oM oN` | `aF aS bF bS > oS oF` | `aD aT bD bT > oD oT` |
| `ADD` | `aM aN q > oM oN` | `aX q > oX` | `aM aN q > oM oN` | `aM aN q > oM oN` | `aF aS q > oS oF` | `aD aT q > oD oT` |
| `ADDC` | `aM aN > oM oN` | `aX > oX` | `aM aN > oM oN` | `aM aN > oM oN` | `aF aS > oS oF` | `aD aT > oD oT` |
| `HOUR` | `aM > oi` | `aX > oi` | `aM > oi` | `aM aN > oi` | `aS > oi` | `aT > oi` |
| `SEC` | `aM aN > ol` | `aX > ol` | `aM aN > ol` | `aM aN > ol` | `aF aS > ol` | `aT > ol` |
| `YEAR` | `aM > oi` | `aX > oi` | `aM > oi` | `aM > oi` | `aS > oi` | `aD > oi` |
| `TRUNC` | `aM > oM oN` | `aX > oX` | `aM > oM oN` | `aM aN > oM oN` | `aS > oS oF` | `aD aT > oD oT` |
| `MICROS` | `aM > ol` | `aX > ol` | `aM aN > ol` | `aM aN > ol` | `aF aS > ol` | `aD aT > ol` |
| `ZONE` | `aM aN > oM oN` | `aX > oX` | `aM aN > oM oN` | `aM aN > oM oN` | `aF aS > oS oF` | `aD aT > oD oT` |
| `HASH` | `aM aN > ol` | `aX > ol` | `aM aN > ol` | `aM aN > ol` | `aF aS > ol` | `aD aT > ol` |
| `CHAIN` | `aM aN bM bN > oM oN` | `aX bX > oX` | `aM aN bM bN > oM oN` | `aM aN bM bN > oM oN` | `aF aS bF bS > oS oF` | `aD aT bD bT > oD oT` |

Two lines of it carry a finding: `HOUR` in R4 over Spark's parts reads the short too, since
the nanoseconds of the day are made of both words, where R1 and R3 read the long alone; and
`MICROS` in R3 and R4 reads both words where R1 reads one.

### 4.2 The divisions, with no divide and no conversion to a double

Every form needs divisions by constants. C2 has no vector integer divide, and its `L2D` and
`D2L` need AVX-512DQ, so the study's loops use three forms built from multiplies and shifts
alone, which C2 lowers on AVX2 and on AVX-512, at every lane count:

| form | the lane operations | exact for | the formula (appendix A) |
| :--- | :--- | :--- | :--- |
| one multiply | `(u >>> k) * M >>> p` | `0 <= u <= U`, where the product stays under 2^64 | `lane_udiv`, `MULSHIFT` |
| two multiplies and a repair | `v = u >>> k`, `q = v * M >>> p` (the quotient or one less), `r = v - q * odd`, then `q + 1 - ((r - odd) >>> 63)` | `0 <= u <= U` with a quotient under 2^20 | `lane_udiv`, `DIVFIX` |
| the multiply-high | the high 64 bits of `(u >>> k) * M`, from four products of 32-bit halves, then `>>> s` | every `0 <= u < 2^63` | `lane_mul_high`, `lane_udiv` |
| the floor of every long | `sign = x >> 63`; the multiply-high of `x ^ sign`; the result `^ sign` | every long `x`: for a negative `x` the floor is the complement of the quotient of its complement | `lane_fdiv` |

This is the method the house has for the int lane (`VarkaDivisionLowering.emitMulHiDivide`,
VARKA-149) and plans for the long lane (`VARKA-30.md` 2.4, "the four-`vpmuludq` long magic"),
written here as lane operations. Each is a Python function over lane values, so the same
sequence runs on numbers, where it is held to exact division on every call, and on symbols,
where each operation prints one Java statement into the loop. The constants are found by
`dev/varka_nanos_spike.py` (`find_mul_shift`, `find_div_fix`, `find_mul_high`); each is checked
over its whole dividend range by `nanos_divisions.smt2` (4.9). The house's two existing
long-lane forms are not used: the conversion form falls back on AVX2, and `emitMagicDivide`
needs a mask, which falls back at two long lanes. The pilot's control loop times the
conversion form once, for the comparison (4.5).

**Every division of every form**, and the form it takes:

| division | dividend | form | used by |
| :--- | :--- | :--- | :--- |
| floor by 86400000000, by 3600000000 | every long `m` | multiply-high | R1: the day (`HOUR`, `SEC`, `YEAR`), `TRUNC`, `CHAIN`; R1 to R4 |
| floor by 1000000 | every long `m` or `q` | multiply-high | R1 to R3; R3's `ADD` |
| floor by 1000, by 1000000000, by 86400000000000, by 3600000000000 | every long `x` | multiply-high | R2 to R1, to R3, to R4; R2's `HOUR`, `SEC`, `YEAR`, `TRUNC`, `MICROS`, `HASH` |
| floor by 86400, by 3600 | every long `s` | multiply-high | R3: the day, `TRUNC`, `CHAIN`; R3 to R4 |
| by 3600000000, by 60000000 | microseconds of a day, under 2^37 | one multiply | R1: `HOUR`, `SEC` |
| by 3600, by 60 | seconds of a day | one multiply | R3: `HOUR`, `SEC` |
| by 1000 | nanoseconds of a second, under 2^30 | one multiply | R3 to R1; R3's `MICROS`, `HASH` |
| by 3600000000000, by 60000000000 | nanoseconds of a day, under 2^47 | two multiplies and a repair | R4 and R2: `HOUR`, `SEC`, `TRUNC`, `CHAIN` |
| by 1000000000 | nanoseconds of a day | two multiplies and a repair | R4 to R3 |
| by 1000 | nanoseconds of a day | multiply-high | R4 to R1; R4's `MICROS`, `HASH` |
| by 146097, 1460, 36524, 146096, 365, 100 | a day count under 2^28 and its parts | one multiply | `YEAR`'s calendar, every form |

**The divisions no single multiply covers** are the four of the nanoseconds of a day: a
dividend of 47 bits leaves no 64-bit product room for an exact multiplier. So R4's and R2's
clock fields pay two multiplies and a repair where R1's and R3's pay one multiply, and R4's
store back to Spark's parts pays a multiply-high. This is a cost of the representation under
this method, and the study measures it as such. A cheaper exact form for a 47-bit dividend
would move R4's cells; none is known to the planner, and section 9 lists it as a risk to the
reading of R4.

**The year of a day** is the same code for every form, on long lanes with no mask: the
proleptic Gregorian count from the day of 0000-03-01 (Hinnant's `civil_from_days`), with the
day moved up by 800 eras of 146097 days so that every dividend is non-negative; `year =
yoe + era * 400 - 320000 + 1 + ((doy - 306) >> 63)`. It is exact for the days -106751992 to
106751991, the days of every long of microseconds, which is every day a loop can meet. The
forms differ in `YEAR` by how they reach the day: R4 has it, the others floor-divide.

### 4.3 What every loop does the same

**The buffers.** A column is held in each form at once, as plain little-endian buffers in
native memory, each with 64 bytes of padding past its last row:

| letter | form | what | element |
| :--- | :--- | :--- | :--- |
| `M`, `N` | R1 | epoch microseconds; nanoseconds within one. These are the data buffers of the cache's struct: its `BigIntVector` child and its `SmallIntVector` child | a long; a short |
| `X` | R2 | epoch nanoseconds, as Arrow's nanosecond timestamp and Parquet's `INT64` hold them | a long |
| `S`, `F` | R3 | epoch seconds; nanoseconds of the second | a long; an int |
| `D`, `T` | R4 | epoch days; nanoseconds of the day | an int; a long |

**No tail.** A loop's row count is a multiple of 64, which every lane count divides, as in
both of the house's spikes. The harness and the suite build no other count, and
`VarkaNanosSpikeCheck.data` refuses one. A kernel's tail is the emitter's and is the same
whatever holds the value.

**The over-read and the over-write.** At two long lanes a short buffer is read and written
through the 64-bit short species: four shorts, of which the group's two are used. So the load
reads, and the store writes, four bytes past its lane group. Inside a buffer the next group
overwrites them; at the last group they fall in the padding. No other step of any loop
touches a byte outside its lane group, at any width.

**Validity.** No loop reads or writes a validity bitmap. A kernel combines validity a 64-row
word at a time, an AND of the inputs' words, whatever form holds the value, so it would add the
same to every arm. **A null row holds zeros** in every buffer of every form, which is the
value 1970-01-01T00:00:00 and is inside every range, so no loop has to skip it. One thing is
lost: a kernel's guard must not count a null lane, which costs one AND a lane group in a batch
with nulls; only the guarded loops below would pay it, so their times are a lower bound by
that.

**Masks.** A vector arm compares to a mask, blends and adds under a mask, as a kernel does at
four lanes and more. A carry is a compare and two masked adds; a selection word is
`mask.toLong() << k`. In layout M a mask of the narrow word's lanes crosses to the long lanes
by `VectorMask.cast`. In layout H it crosses by `SHORT_LANES ? k.cast(LS) :
VectorMask.fromLong(LS, k.toLong())`, where `SHORT_LANES` is a constant of the JVM: a cast
needs equal lane counts, which the short and the long vector have at four lanes and more.

**Guards.** A loop returns a long: negative where a value left a range and the batch would
decline, else zero or positive. A guard is one lane formula whose sign bit is set where the
value is outside. A vector loop ORs it into one accumulator (`ov` of long lanes, `ovi` of int
lanes) and reads the accumulator's sign once after its last lane group, `bad |=
ov.reduceLanes(OR) >> 63`; a scalar loop ORs it into `bad`. Three forms, each exact:

| form | the lanes | exact where | why it is exact |
| :--- | :--- | :--- | :--- |
| the window | `(v - lo) \| (hi - v)` | `lo <= v <= hi` is tested for every `v` of the lane, where `hi - lo` is under half the lane's range (2^63 for a long, 2^31 for an int) | at most one of the two differences wraps, and it wraps to a negative number |
| the sum | `(x ^ r) & (y ^ r)`, `r = x + y` | every long `x` and `y`, a column or a constant | a wrapped sum has the sign neither operand has |
| the floor | `x & ~r`, `r` the floor of `x` to a multiple | every long `x` | the floor is at most `x`, so it has wrapped exactly where `x` is negative and `r` is not |

The window cannot serve a bound at an end of a long: a window from the smallest long to the
largest less a literal is wider than 2^63, and one difference then wraps for a value the other
does not catch. `nanos_guards.smt2` shows that as a `sat` check and proves each guard below at
both ends of its range (4.9). So the one-sided guards take the sum's form or the floor's, each
on a value the loop computes anyway. The guards, each with its form:

| guard | on | form | in |
| :--- | :--- | :--- | :--- |
| R1 to R2 | `m` in -9223372036854775 to 9223372036854774 | window | every loop that computes in R2 over Spark's parts, and the edge |
| R3 to R1 | `s` in -9223372036854 to 9223372036853 | window | every loop that stores a result of R3 as Spark's parts; R3's `MICROS` and `HASH` |
| R4 to R1 | `d` in -106751991 to 106751990 | window, of long lanes in L and of int lanes in M | the same of R4 |
| R3 to R2, R4 to R2 | `s` in -9223372036 to 9223372035; `d` in -106751 to 106750 | window | the edges, and the loops that store so |
| R3 to R4 | `s` whose day is an int, -185542587187200 to 185542587187199 | window | the edge, and the loops that store so. No `s` made of a long of microseconds is outside it, so no batch of the study reaches it: the proof file holds it alone |
| the interval of a sum in R2 | `q` in R2's range of `m` | window | `ADD` in R2 |
| the sum of a column | `m + q`; `x + q * 1000` | sum | `ADD` in R1 and in R2 |
| the literal's sum | `m + 3601500000`; `x + 3601500000000` | sum, the constant its second operand | `ADDC`, `CHAIN` in R1 and R2: it declines exactly above the largest long less the literal |
| the zone's offset | `m - 28800000000`; `x - 28800000000000` | sum, the constant negative | `ZONE` in R1 and R2: it declines exactly under the smallest long plus eight hours |
| the floor to an hour | the floor of `m`, of `x` | floor | `TRUNC` in R1 and R2: it declines exactly under the least whole hour that is a long, -9223372036800000000 microseconds and -9223369200000000000 nanoseconds. `CHAIN` needs none: its sum is at least the literal above the smallest long |

R3 and R4 hold more than Spark's parts do, so their own sums cannot leave them; they pay
their window where the result goes back to a narrower form. **The sure range** is first words
of at most 4000000000000000 in magnitude (the years 1843 to 2096) and intervals of at most
1000000000000000 microseconds: inside it every form holds every operand and every result, and
no loop may decline. The benchmark's data is inside it, so no timed batch declines.

**Why no minimum and maximum.** The second writing kept a running minimum and maximum of the
guarded word and reduced them after the loop. The lane-wise `min` and `max` are lowered with
AVX2; their reductions are not. The planner's probe (section 14) left
`VectorSupport::reductionCoerced` in Java under `-XX:UseAVX=2` at 256 and at 128 bits. The other
repair the second review named, reading the two accumulators out by `toArray()` and taking the
minimum in scalar Java, left `VectorSupport::store` in Java under all six flag sets. The OR
accumulator's reduction is lowered under all six, of long lanes and of int lanes, the two-lane
int vector included.

**At 128 bits.** The layouts are the same code at every width. What differs at two long lanes:

| | what it is at 128 bits | lowered by C2 there |
| :--- | :--- | :--- |
| a long word | a `LongVector` of two lanes | its loads, stores, arithmetic, shifts and the OR reduction: yes. Its masks: no |
| layout L's narrow word | widened at its load into the long vector: `I2L` from a two-lane int vector; `S2L` from the first two of four shorts | yes |
| layout M's narrow word | an `IntVector` of two lanes, 64 bits | its loads, stores, arithmetic, `I2L`, `L2I`, `S2I`, `I2S` and its OR reduction: yes. Its masks: no |
| layout H's short | a `ShortVector` of four lanes, 64 bits, the group's two used | its loads, stores, `S2L`, `L2S`: yes. Its own masks have four lanes and are lowered; crossed to the two long lanes they are not |

So at 128 bits a loop with no mask is vector code in every layout, and a loop with a mask
(`LT`, `PICK`, `CHAIN` and `ZONE` in every form; `ADD` and `ADDC` in R3 and R4, whose carry is
a compare) has calls left in Java in every layout: 4.7 has the count. The second review read
the first probe's `convert` and `binaryOp` lines as the two-lane int vector's conversions and
arithmetic. They are not: a mask's `cast` is a `VectorSupport::convert`, and a mask's `and`
and a masked `add` are `VectorSupport::binaryOp`. A probe of one call a loop (section 14) shows
`I2L`, `L2I` and the int arithmetic lowered at two lanes, with and without AVX2.

The alternative the review named, an `IntVector` of four lanes (128 bits) beside the two-lane
long vector, was probed too. Its loads, `I2L` from its first two lanes, `L2I` into it, its
arithmetic and its own masks (four lanes) are all lowered at 128 bits. It does not help these
loops: every mask they need is of the long lanes or crosses to them, a four-lane mask cannot
be cast to two lanes, and the two-lane int vector lacks nothing else. So no layout changes at
128 bits. What would make the masked loops vector code there is a form with no mask (a carry
as arithmetic, a compare as a subtract and a shift): that is row 698's question for a two-word
value and row 689's for a zone step, and this row does not answer it (section 12).

### 4.4 One formula a loop, checked on numbers and printed as Java

Each operation and each conversion is one Python function over lane values
(`dev/varka_nanos_spike.py` and `dev/varka_nanos_spike_r34.py`, appendices A and B): the tables
`CONVERT`, `ADD`, `ADD_LITERAL`, `TRUNC_HOUR`, `OF_DAY`, `SECOND`, `DAY` and `ZONE` hold each
form's formula, `OPS` the thirteen operations, and `body` puts a loop together from its five
names. A lane value has two implementations with one interface. On numbers (`Val`) an operation
computes with 64, 32 or 16-bit wrapping, a division runs the lane sequence of 4.2 and is held
to the exact quotient, a bounded division refuses a dividend past its bound, and a guard's
lanes are held to the exact test. On symbols (`Sym`, in `dev/varka_nanos_spike_gen.py`,
appendix C) the same call records one Java statement. So the Java of a loop is the formula
that was checked, not a second copy of it: there is no place where a builder writes a loop's
arithmetic by hand.

`python3 dev/varka_nanos_spike.py` runs every loop of the forms present on numbers over 3271
cases and holds it to exact integers; 4.6 has the cases. `python3 dev/varka_nanos_spike_gen.py
gen <set> <dir>` writes four classes, `VarkaNanosSpikeLoopsR1.java` to `R4.java`, one for the
loops that compute in each form, each a static method a loop and a list of its arms. A set is
`pilot` (twelve loops), `r12` (128: the 124 that compute in R1 and R2, and the pilot's four in
R3, which stay) or `study` (322). Every generated file
says `Generated by dev/varka_nanos_spike_gen.py` in its first lines; `check <set> <dir>` fails
where a file is not what `gen` writes, and `check any <dir>` where the files are those of no
set.

**A loop calls nothing of its own.** Every statement of a generated loop is a Vector API call,
or a read of a constant of `VarkaNanosSpikeOps` (a species, the byte order, a layout of a row).
A division is its ten to twenty lane statements in the loop's own body. The reason is the
fourth fact of section 2: a shared step would be a call in every loop whose graph passes C2's
node count, and a vector that crosses a call is boxed.

**A vector loop**, as generated (layout M, so the nanoseconds of the second are an
`IntVector`, the literal's half second is added in int lanes, and the carry's mask crosses to
the long lanes):

      /** ADDC in R3, layout M: reads aF, aS; writes oS, oF. */
      static long loopAddcR3MA3Z3(Cols c, int n) {
        MemorySegment aF = c.aF;
        MemorySegment aS = c.aS;
        MemorySegment oS = c.oS;
        MemorySegment oF = c.oF;
        long bad = 0;
        for (int r = 0; r < n; r += L) {
          var t1 = LongVector.fromMemorySegment(LS, aS, r * 8L, LE);
          var t2 = IntVector.fromMemorySegment(IS, aF, r * 4L, LE);
          var t3 = t2.add(500000000);
          var t4 = t3.compare(GE, 1000000000);
          var t5 = t1.add(3601L);
          var t6 = t4.cast(LS);
          var t7 = t5.add(1L, t6);
          var t8 = t3.sub(1000000000, t4);
          t7.intoMemorySegment(oS, r * 8L, LE);
          t8.intoMemorySegment(oF, r * 4L, LE);
        }
        return bad;
      }

**A guarded loop** (the floor of Spark's microseconds to an hour, its division as the lanes
compute it, the floor's guard, the short stored as a short vector of zeros):

      /** TRUNC in R1, layout H: reads aM; writes oM, oN. */
      static long loopTruncR1HA1Z1(Cols c, int n) {
        MemorySegment aM = c.aM;
        MemorySegment oM = c.oM;
        MemorySegment oN = c.oN;
        long bad = 0;
        var ov = LongVector.zero(LS);
        for (int r = 0; r < n; r += L) {
          var t1 = LongVector.fromMemorySegment(LS, aM, r * 8L, LE);
          var t3 = t1.lanewise(ASHR, 63);
          var t4 = t1.lanewise(XOR, t3);
          var t5 = t4.lanewise(LSHR, 10);
          var t6 = t5.and(4294967295L);
          var t7 = t5.lanewise(LSHR, 32);
          var t8 = t6.mul(3405838667L);
          var t9 = t6.mul(2501999L);
          var t10 = t7.mul(3405838667L);
          var t11 = t7.mul(2501999L);
          var t12 = t8.lanewise(LSHR, 32);
          var t13 = t9.and(4294967295L);
          var t14 = t12.add(t13);
          var t15 = t10.and(4294967295L);
          var t16 = t14.add(t15);
          var t17 = t9.lanewise(LSHR, 32);
          var t18 = t11.add(t17);
          var t19 = t10.lanewise(LSHR, 32);
          var t20 = t18.add(t19);
          var t21 = t16.lanewise(LSHR, 32);
          var t22 = t20.add(t21);
          var t23 = t22.lanewise(LSHR, 11);
          var t24 = t23.lanewise(XOR, t3);
          var t25 = t24.mul(3600000000L);
          var t26 = t25.not();
          var t27 = t1.and(t26);
          var t28 = ShortVector.zero(HS);
          ov = ov.or(t27);
          t25.intoMemorySegment(oM, r * 8L, LE);
          t28.intoMemorySegment(oN, r * 2L, LE);
        }
        bad |= ov.reduceLanes(OR) >> 63;
        return bad;
      }

**A scalar loop** is the same formula as plain Java, with `Math.floorDiv` for a floor division
and `/` for a bounded one, which C2 turns into its own multiplies:

      /** HOUR in R4, layout S: reads aT; writes oi. */
      static long loopHourR4SA4Z4(Cols c, int n) {
        MemorySegment aT = c.aT;
        MemorySegment oi = c.oi;
        long bad = 0;
        for (int r = 0; r < n; r += 1) {
          long t2 = aT.get(JL, r * 8L);
          long t3 = t2 / 3600000000000L;
          oi.set(JI, r * 4L, (int) t3);
        }
        return bad;
      }

A scalar loop is whatever C2 makes of it; C2 may vectorise a simple one itself. That is what
a scalar loop is worth on that host, and its label says S.

**The species** (`VarkaNanosSpikeOps`, appendix E): `LS`, the preferred long species, of `L`
lanes; `IS`, the int species of half its bits, of `L` lanes; `HS`, the short species of a
quarter of its bits, 64 bits at the least. One species an element type, so no template is
bimorphic.

**In layout M the narrow word is held narrow.** It is loaded, compared, blended and stored in
int lanes. Where a formula mixes it with a long word it is widened there (`I2L`) and a narrow
result is narrowed back (`L2I`); where the arithmetic fits an int, as R3's carry does, it stays
in int lanes. So a mixed-width loop that only moves or compares the word pays no conversion,
and one that divides it pays two. Layout H does the same with Spark's short in short lanes.

### 4.5 The harness and the JIT evidence

`VarkaNanosRepresentationSpikeBenchmark` (appendix H) is VARKA-577's harness
(`VarkaDecimalRemainderSpikeBenchmark`) with the study's arms. What it does, in order:

1. It refuses to run without the compile command in the JVM's arguments, word for word:

        -XX:CompileCommand=dontinline,org.apache.spark.sql.VarkaNanosSpikeLoops*::loop*

   It keeps every loop a compile unit of its own. No second command folds shared steps into
   the loops: a loop has none (4.4).
2. **The intrinsic check**, once a loop, before any timing: a child JVM with this JVM's
   arguments plus `-Xbatch`, `-XX:+PrintCompilation` and a `PrintInlining` command over the
   loops calls the loop 20000 times over 64 rows, and `fallbacks` reads the inlining tree of
   the loop's last level-4 compile that is no on-stack replacement. A `VectorSupport` call
   site whose last message is not `(intrinsic)` or `late inline succeeded` is a call left in
   Java; so is a Vector API method that `failed to inline`. The results file gets one line a
   loop, `intrinsic check, <label>: every call intrinsified` or `intrinsic check, <label>: <n>
   Vector API call(s) left in Java, by method: {...}`, and a loop with a call left in Java is
   timed under the label `<label> [Java fallback]`.
3. Each loop is checked against Spark's methods before it is timed
   (`VarkaNanosSpikeCheck.requireAgrees`), driven until `Compiler.codelist` shows its method at
   level 4 in use and two drives are within 30 percent with C2 idle, which prints `<label> at
   <n> rows settled after <k> drives, level 4` on the console, and checked again in compiled
   code.
4. It is timed by the house rule (`VarkaBenchmark.Builder.timed`: a two-second warm-up, then
   at least five iterations over at least two seconds). An iteration of a loop with no call
   left in Java is 2^26 rows, so that the file's whole milliseconds resolve it (fact 19): a
   loop of a quarter of a nanosecond a row is 17 ms. A loop with a call left in Java is timed
   in a table of its own, titled `..., Java fallback`, an iteration 2^20 rows: its time is
   never read, and at 2^26 rows a fallback of 300 nanoseconds a row would take 100 seconds.
5. After each table the results file gets `allocated, <label> at <n> rows: <b> bytes a row`,
   read from the thread's allocated bytes over one drive: a loop the check passed alone can
   still box beside the other loops' compiles.

**A cell whose loop has a call left in Java, or allocates over a byte a row, is not a time of
vector code.** The cost tool marks it `fallback`, and the rule never reads it (4.10).

**The control**, `VarkaNanosSpikeLoopsControl.loopHourcR1LA1Z1` (appendix I), written by hand:
`hour` of Spark's microseconds with the divisions the engine's long lane has today, through
`L2D` and `D2L`. On an AVX2 host its intrinsic check must report calls left in Java. If it
does not, the check proves nothing and the run is void. On an AVX-512DQ host it says how the
conversion form compares with the multiply-high of `HOUR R1L 1>1`.

**The tables of a results file.** One section, `nanosecond timestamp hand loops at <bits>
bits`; a run asked for some operations by `-Dvarka.nanos.ops` (4.7) adds `: ` and the
operations to the title. A table an operation and rung, titled `<n> rows - <level>,
<OPERATION>`, a line a loop with Spark's benchmark columns, and beside it the table of that
operation's fallbacks where it has any. The rungs are VARKA-359's: 4096 rows ("in L2"), 262144
("in L3") and 4194304 ("past L3"). Every loop is timed at the first, which the cost table and
the rule read. At the other two only `ROUND` and `CHAIN` stored as they arrived are timed, 60
loops: they show where memory takes over from arithmetic for each layout's bytes a row.

### 4.6 The reference: Spark's own methods, and the rows a loop must decline

`VarkaNanosSpikeCheck` (appendix F) builds a batch from the first and second words of two
columns and an interval column, in all four forms, runs a loop, and compares each row's output
with Spark's answer:

| operation | Spark's method, with file and line in the reading worktree |
| :--- | :--- |
| `ROUND` | the value itself, `TimestampNanosVal.equals` (`TimestampNanosVal.java` 105) |
| `LT`, `PICK` | `TimestampNanosVal.compareTo` (`TimestampNanosVal.java` 125) |
| `ADD`, `ADDC` | `DateTimeUtils.timestampNanosAddDayTime(v, micros, ZoneOffset.UTC, TimestampNTZNanosType(9), DayTimeIntervalType)` (`DateTimeUtils.scala` 334) |
| `HOUR` | `DateTimeUtils.getHours(m, ZoneOffset.UTC)` (104) |
| `SEC` | `DateTimeUtils.getSecondsWithFractionNanos(v, ZoneOffset.UTC).toUnscaledLong` (159) |
| `YEAR` | `DateTimeUtils.getYear(DateTimeUtils.microsToDays(m, ZoneOffset.UTC))` (200; `SparkDateTimeUtils.scala` 363) |
| `TRUNC` | `DateTimeUtils.truncTimestampNanos(v, DateTimeUtils$.MODULE$.TRUNC_TO_HOUR(), ZoneOffset.UTC)` (717, 578) |
| `MICROS` | `v.epochMicros`, as `Cast` does (`Cast.scala`, `buildCast[TimestampNanosVal](_, v => v.epochMicros)`) |
| `ZONE` | `ZoneId.of("America/Los_Angeles").getRules().getOffset(Instant.ofEpochSecond(floorDiv(m, 1000000)))`, added to `m` |
| `HASH` | `XXH64.hashInt(n, XXH64.hashLong(m, 42L))` (`XXH64.java` 60, 71), the rule of `hash.scala` 797 |
| `CHAIN` | the three above, composed |

**The operand a loop is asked about** is the value its arriving form holds. Spark's parts,
seconds and days hold every value. One long of nanoseconds holds what its long holds: for a
first word outside R2's range the buffer `X` holds the wrapped product, which is another
timestamp, and a loop that arrives as R2 is asked about that one (`Data.a`, `Data.b`). So no
row is skipped for its arriving form.

A row where Spark raises is not compared; nor is a `ZONE` row outside 2025, or a `TRUNC` row
past 2^62 microseconds (where Spark's truncation takes its slow path).

**A declined batch is compared, not skipped.** The checker holds the guards' table of 4.3 a
second time, in exact integers (`VarkaNanosSpikeCheck.declines`, over `BigInteger`): for a
loop's five names and a row's operands, whether the loop must decline the row. It shares no
line with the formulas or the generator. `requireAgrees` then asserts, for a loop and a batch:

1. the loop returns a negative number exactly where the table declines a row of the batch;
2. where it declines none, every row equals Spark's answer;
3. where it declines some, the rows the table accepts are run again as a batch of their own
   (the first accepted row repeated up to whole selection words), which the loop must answer,
   every row equal to Spark's;
4. in the suite, each row the table declines is run alone, 64 copies of it, and the loop must
   decline it.

So a loop is compared at every value its guards accept, the ends of a long included, and is
shown to decline at every value they refuse: a guard one step too wide, and one too narrow,
both fail. The Python check holds the same table (`must_decline`) to the formulas' own guards
on every case.

**The suite's batches** (`VarkaNanosRepresentationSpikeSuite`, appendix G), each run through
every loop:

| batch | first words | second words | rows |
| :--- | :--- | :--- | ---: |
| sure | 20: -1, 0, 1; one second before the epoch and a microsecond either side of it; a day before and after the epoch and a microsecond either side; the first and last microsecond of 2025; each zone change and the microsecond before it; a day's last microsecond in mid-2025; plus and minus 4000000000000000; three days before the epoch plus 7. Then the fifteen carry rows | 0, 1, 500, 999 | 6464 |
| wide | 11: -1, 0, 1; 0001-01-01 (-62135596800000000) and 9999-12-31T23:59:59.999999 (253402300799999999); the ends of R2's range and one past each; plus and minus 2^62 | 0, 1, 500, 999 | 1984 |
| ends | 32: the smallest and largest long and their neighbours; then, for each guard of 4.3, the last first word it accepts and the first it declines: the seconds and the days whose microseconds are longs; the seconds and the days whose nanoseconds are longs; the literal's sum in microseconds and in nanoseconds; the least whole hour in both; the zone's offset in both | 0, 999 | 4096 |
| random, sure | uniform in plus or minus 4000000000000000, `new Random(662)` | uniform in 0 to 999 | 1024 |
| random, wide | uniform in plus or minus 2^62, `new Random(663)` | the same | 1024 |
| the benchmark's | 4096 rows of 4.7's data | | 4096 |

The first three take every ordered pair of their first words with each of their second words,
so a pair that ties on the first word and differs on the second is there, and floor and
truncation differ on every negative row; the counts are 16 x 20 x 20 + 15 = 6415, padded to
6464; 16 x 11 x 11 = 1936, padded to 1984; and 4 x 32 x 32 = 4096. The suite asserts the six
row counts. The interval of row `i` is the `i`-th, in turn, of 0, 1, -1, 999999, 1000000,
-1000001, a day, a day and a microsecond back, three days and 7, a day less one, and plus and
minus 1000000000000000. **The carry rows** land sums exactly on a second and exactly on a day,
from the epoch, from 2025 and from three days before the epoch, for the literal and for a
column, where a carry's `>=` is decided: without them a carry written `>` passes.

**Planted faults**, each made, its command run, and undone. Every one was run by the planner
through the JDK-only harness of section 3, where the suite's first test stands:

| fault | where | fails |
| :--- | :--- | :--- |
| `carry = f.ge(E9)` made `f.gt(E9)` | `dev/varka_nanos_spike_r34.py`, `add_literal_3` | `python3 dev/varka_nanos_spike.py`, by `past a bound in a row that answers`; and, regenerated, the suite's first test at a carry row of `CHAIN R3L 1>1` |
| `doy.sub(306)` made `doy.sub(305)` | `dev/varka_nanos_spike.py`, `year_of_day` | the script at `YEAR`; regenerated, the suite's first test |
| `run.guard(v.sub(lo).bor(run.const(v.kind, hi).sub(v)),` made `run.guard(v.sub(lo),` | the same file, `guard_window` | the script, by `a guard's lanes and its exact test differ`; regenerated, the suite's first test at a row a loop must decline |
| `"ASHR"` made `"LSHR"` in `Sym.shr` | `dev/varka_nanos_spike_gen.py` | the script passes: it cannot see what the generator prints. Regenerated, the suite's first test at the first negative row of `HOUR R1H 1>1` |
| `"short": ("H", 2, "JS")` made `("H", 8, "JS")` | the same file, `STORED` | the script passes. Regenerated, the suite's first test at `ROUND R1H 1>1`: a read past the buffer's end |
| `-25_200L` made `-21_600L` in the suite's last assertion | the suite | its fourth test, by its last assertion |

Each fails by `VarkaBenchmarkBase.require`'s exception or a JUnit assertion, with the loop's
label, the row, the values, the loop's answer and Spark's.

### 4.7 The readings

**The data**: `VarkaNanosSpikeCheck.benchmarkData`, from `new Random(662)`. First words
uniform in 2025 less a day and the literal, so every sum stays in the year's neighbourhood and
every `ZONE` row is in 2025; second words uniform in 0 to 999. `b` equals `a` on both words
in one row of sixteen and on the first word alone in one more, else it is independent. `q` is
uniform in plus or minus a day. The suite's fourth test asserts what this reaches: both
outcomes of the compare, both kinds of tie, the literal's carry into the second on about half
the rows and into the day on about one in 24, all 24 hours, and both offsets of the zone.

**The count**, which `python3 dev/varka_nanos_spike_gen.py count study` prints: `322 loops, 442
cells; with the control 323 and 443`. A cell is a loop at a rung: 322 loops at the first rung
and 60 at each of the other two. The count is the same at every width: every layout exists at
128 bits. The pilot: `12 loops, 18 cells; with the control 13 and 19`.

**What the planner's intrinsic check found**, on the laptop, a loop a child JVM, as the
benchmark makes it. `clean` is a loop with every call intrinsified. The scalar loops have no
Vector API call and are clean by that:

| flags | bits | loops | clean | with calls left in Java |
| :--- | ---: | ---: | ---: | :--- |
| none | 512 | 322 | 322 | none |
| `-XX:MaxVectorSize=32` | 256 | 322 | 322 | none |
| `-XX:MaxVectorSize=16` | 128 | 322 | 241 | 81: every vector loop of `LT` (20), `PICK` (13), `ZONE` (12) and `CHAIN` (20), and `ADD` (8) and `ADDC` (8) in R3 and R4. The calls: `compare` 218, `binaryOp` 200, `convert` 66, `blend` 55, `maskReductionCoerced` 21, `fromBitsCoerced` 5 |
| `-XX:UseAVX=2` | 256 | 322 | 322 | none |
| `-XX:UseAVX=2 -XX:MaxVectorSize=32` | 256 | 322 | 322 | none |
| `-XX:UseAVX=2 -XX:MaxVectorSize=16` | 128 | 322 | 241 | 81: every vector loop of `LT` (20), `PICK` (13), `ZONE` (12) and `CHAIN` (20), and `ADD` (8) and `ADDC` (8) in R3 and R4. The calls: `compare` 218, `binaryOp` 200, `convert` 66, `blend` 55, `maskReductionCoerced` 21, `fromBitsCoerced` 5 |

So the expectation the pilot and the study are read against:

- at 512 and at 256 bits, on AVX-512 and on AVX2: every loop but the control reads `every call
  intrinsified`. A loop that does not is a finding, and section 11 names it;
- at 128 bits: the loops of the table's last column read calls left in Java, and no other loop
  does. Those loops are timed in the fallback tables and their times are never read.

**The time of a run**, priced, to be scaled by the pilot's reading. A cell is a two-second
warm-up and at least two seconds of timing, with the drive to compiled code under a second:
about 5 seconds. The intrinsic check is a child JVM a loop: about 3 seconds where every call is
lowered. The check against Spark at the largest rung is two passes of 4194304 rows through
`java.time`: about 8 seconds a loop there. So a run at 256 bits is about 443 x 5 + 323 x 3 +
60 x 8 = 3664 seconds, an hour. At 128 bits the loops with a mask run the Vector API's Java
code: their child JVM's 20000 calls and their drive to a steady time are slow, and a loop that
does not settle waits 30 seconds: 81 such loops may add most of an hour. The pilot's reading
gives seconds a cell and seconds an intrinsic check at each width; part S3's sheet has the
rule that takes a run as two or as four where the scaled time passes 240 minutes.

**A run in parts.** `-Dvarka.nanos.ops=<operations, with commas>` makes the benchmark time
`ROUND`, the control and the operations it lists, in a section whose title ends `: ` and the
operations with spaces. It is not `-Dvarka.bench.only`, which a run that writes a results file
refuses (fact 17): a part times each of its operations whole, in every arm, with the base of
its costs beside it, so every ratio the rule reads and every subtraction of the cost table is
made inside one run on one machine. No class of 4.10 is split between two parts. The parts'
files are joined by `dev/varka_bench_sections.py splice` (5.7). `dev/varka_bench_merge.py` is
not the tool: it joins the shards of the date surface by a provenance this benchmark does not
write (fact 21).

**Where.**

| host | widths | how | files (in `sql/catalyst/benchmarks`, each with its `-provenance.txt`) |
| :--- | :--- | :--- | :--- |
| the laptop, idle, pinned | 512, 256, 128 | `-XX:MaxVectorSize=64`, `32`, `16` and the compile command (5.5) | `VarkaNanosRepresentationSpikeBenchmark-jdk25-laptop-results.txt`, `-laptop-256bit-results.txt`, `-laptop-128bit-results.txt` |
| a GitHub runner with an AMD EPYC 7763, by `benchmark.yml` | 256, 128 | the dispatches of 5.5 and 5.7 | `...-jdk25-runner-epyc7763-results.txt`, `-runner-epyc7763-128bit-results.txt` |
| a runner with AVX-512, where a dispatch lands on one | 512 | the same dispatch with no `expected-cpu`, kept when its CPU has `avx512dq` | `...-jdk25-runner-avx512-results.txt` |

The laptop's datapath is 256 bits wide, so its 512-bit file is double-pumped, and its header
says so. The runner pool has EPYC 7763 machines and some with AVX-512: `sql/varka/HARDWARE.md`
lists the Intel Xeon Platinum 8573C, the Intel Xeon 6973P-C and the AMD EPYC 9V45 with the full
`avx512` set (VARKA-577 read an EPYC 9V45 there). Part S3 dispatches the class three times with no
`expected-cpu`; a run whose provenance names a CPU other than the EPYC 7763 and whose `lscpu`
flags hold `avx512dq` is kept as the AVX-512 file. If none of the three lands on one, section 11
records `no AVX-512 runner in three dispatches: the 512-bit reading is the laptop's,
double-pumped`, and the project's defaults (4.10, step 6) then rest on the laptop's block alone.

*28 November 2026:* the owner gave AVX-512 high priority (section 15, answer 1). The dispatches
already hold an AVX-512 runner's reading, these three, so none is added: S3 stays at the two
dispatches on the EPYC 7763 and the three with no `expected-cpu`, each taken as one run, two or
four by 5.7's table. A dispatch cannot ask for a CPU, so a fourth try would buy a chance and not a
reading. The EPYC 7763's two readings stay: it is the CPU the committed baselines are read on.
### 4.8 The cost table

The file `nanos_representation_costs.tsv`, in `sql/varka/plans/m9/results/VARKA-662/`, written
by `python3 dev/varka_nanos_costs.py table <results files>` (appendix D) and held to those
files by its `check`. Tab-separated, one header, one line a row; every time is nanoseconds a
row by best time at the 4096-row rung:

    kind  host  width  form  layout  arrival  sink  operation  ns  base  limit  jit  alloc  spread  flag  source

**The measured rows are the truth.** A `loop` row is one timed loop and nothing else. Every
other kind is derived from loop rows by a subtraction or a sum, says so by its kind, and
carries what it was derived from.

| kind | what it is | `ns` | `base` | `limit` | `flag` |
| :--- | :--- | :--- | :--- | :--- | :--- |
| `loop` | one timed loop: measured | its time | `-` | `-` | `-` |
| `base` | a form's `ROUND` loop stored as it arrived, in a layout: the load and the store of the value, which every derived row subtracts. The same measurement as its `loop` row, repeated under this kind so that the base is a row of the table | its time | `-` | `-` | `-` |
| `cell` | derived: an operation's own cost in a form | the loop's time less its base, or `0.000` (the floor, below) | what was subtracted | `exact`, `reads+` or `shape` | `-`, `within noise` or `not additive` |
| `edge` | derived: a conversion between two forms | the same | the same | `shape` | the same |
| `residual` | a measured loop that arrives in one form, computes in another and stores as it arrived, beside the sum of the base, the edges and the cell that predict it | the measured loop's time | the predicted sum | `x` and the measured time over the predicted sum | `ok` inside the band of 1.3, `off` outside it, `no data` where a part is a fallback or not additive |

| column | holds |
| :--- | :--- |
| `host` | the `cpu:` line of the results file's provenance file |
| `width` | the vector bits of the run, from the section title |
| `form`, `layout` | the form computed in, `R1` to `R4`, and the layout; for an edge, the form converted to |
| `arrival`, `sink` | the form the operands arrive in and the form a timestamp result is stored in; for an edge, the form converted from, and to |
| `operation` | a name of 4.1 |
| `jit` | `ok`, or `fallback` where the loop's label says `[Java fallback]` or it allocates over a byte a row; a derived row is `fallback` if any loop it is made of is |
| `alloc` | bytes allocated a row; the only meaning of bytes in this table |
| `spread` | for a loop: its average iteration less its best, never under the half millisecond the file rounds a time to, as nanoseconds a row. For a derived row: the sum of the spreads of the loops it is made of, each weighted as it is subtracted |
| `source` | the results file; after `#`, the operations of its section where the run was taken in parts (4.7) |

**How a loop's time and spread are read.** A results file prints the best and the average
iteration as whole milliseconds and the rate to a decimal of a million rows a second (fact 19).
The tool takes the time from the rate, `1000 / rate`, where the rate is 31.6 or more, and from
the `Per Row(ns)` column under it: at least three digits either way. The spread is `ns x
max(avg - best, 0.5) / max(best, 0.5)`, the two times in the file's milliseconds: the run-to-run
spread of the timed iterations of one loop in one run, with the file's rounding as its floor.
An iteration of 2^26 rows is what makes the whole milliseconds mean something (4.5).

**How a cell is derived.** An operation's own cost in a form is the loop that arrives in that
form, computes in it and stores in it, less that form's base in the same layout and the same
run. Three cases:

- The result is a timestamp read from one column (`ADDC`, `TRUNC`, `ZONE`): the two loops
  touch the same buffers. `limit` is `exact`.
- The loop reads one more column (`PICK`, `ADD`, `CHAIN`): the cell holds that column's load
  too. `limit` is `reads+`.
- The result is a selection word, an int or a long (`LT`, `HOUR`, `SEC`, `YEAR`, `MICROS`,
  `HASH`): half the base is subtracted, for the load without the store. `limit` is `shape`:
  the cell is good to that half, and it counts a loop that reads one word of two as reading
  both.

**How an edge is derived**: the `ROUND` loop that arrives in one form and is stored in
another, less half the base of each of the two forms. `limit` is `shape`. Its range condition
and its proof are 4.9's table, by the pair of forms.

**The floor and the flag.** A subtraction of two measured times can come out negative: an
operation cheaper than the half of the base it was charged, or two loops that C2 compiled
differently. The table never holds a negative cost. For a difference `d` and the row's spread
`s`:

| the difference | `ns` | `flag` | it means |
| :--- | :--- | :--- | :--- |
| `d >= s` | `d` | `-` | a cost the measurement resolves |
| `-s < d < s` | `0.000` | `within noise` | no cost the measurement can tell from none: it lies between 0 and `s`, and `spread` holds `s` |
| `d <= -s` | `0.000` | `not additive` | the loop is cheaper than its base by more than the spread: the subtraction does not hold for this row |

The subtracted amount stays in `base`, so the difference itself is the loop's row less `base`
for anyone who wants it.

**The residual** is the table's own error bar, a row for each loop over Spark's parts or over
epoch nanoseconds that computes in another form. For a loop of operation `op` that arrives in
`A`, computes in `X` and stores in `A`, with `k` the operands it converts (2 for `LT`, `PICK`
and `CHAIN`, else 1):

    predicted = (base(A), or half of it where the result is no timestamp)
              + k x edge(A, X) + cell(op, X) + (k - 1) x (base(A) - base(X)) / 2
              + edge(X, A) where the result is a timestamp

`cell(ROUND, X)` is nothing. The third term moves the second operand's load from the form the
cell was measured in to the form it arrives in. The residual is `ok` where the measured time
over that sum is between 1 / 1.3 and 1.3, the house's band, and `off` where it is not.

**What a later cost model may conclude from the table, and what it may not.**

- It *may* take a `loop` row as the cost of exactly that loop on that host at that width at
  the first rung: thirteen operations, four forms, the arrivals of 4.1.
- It *may* rank the forms for a tree by the sum `edge(A, X)` plus the cells of its operations
  in `X` plus `edge(X, Z)`: the base is the same for every `X` and drops out of the comparison.
  It may do so where the residual rows of that host, width and layout are `ok`, and it reads
  their ratios as the error of such a sum: a difference between two forms inside that error is
  no difference.
- It *may not* read a row flagged `within noise` as free: its cost is between 0 and its
  `spread`.
- It *may not* use a row flagged `not additive`, or a `residual` flagged `off` or `no data`,
  or any row whose `jit` is `fallback`.
- It *may not* read a `shape` row closer than half its `base`.
- It *may not* price a chain of `n` operations as `n` cells without a residual that covers
  it: the table validates a sum at one operation, and at three for `CHAIN`. C2 fuses and
  reorders across a loop's parts, and a sum of `n` flags of `within noise` hides up to `n`
  spreads.
- It *may not* price what the table does not hold: a null test; a choice not made by `PICK`;
  `IN` with `k` literals (4.1 bounds it by `LT`); a cast to a date; an operand that is no
  timestamp; another rung, where memory and not arithmetic is the cost; another host or width.
- It *must* establish the range condition of every edge it uses (4.9), by a batch test or a
  column statistic.

The table has no chooser; `dev/varka_nanos_costs.py rule` is the static rule of 4.10 and
nothing more.

### 4.9 The equalities between the forms, and the guards

Each conversion is an equality between the exact values, true under a range condition on one
word. `sql/varka/proofs/nanos_representations.smt2`, which `dev/varka_nanos_spike_proofs.py`
writes (appendix J), states each with Java's wrapping operators over the prelude
`java.smt2`, proves it under its condition, and shows one step outside the condition that it
fails. `floor` is `Math.floorDiv`.

| # | from, to | the conversion | holds for | one step outside |
| :--- | :--- | :--- | :--- | :--- |
| E12 | R1 to R2 | `x = m * 1000 + n` | `m` from -9223372036854775 to 9223372036854774 | the product or the sum wraps for some `n` |
| E21 | R2 to R1 | `m = floor(x, 1000)`, `n = x - m * 1000` | every long `x` | - |
| E13 | R1 to R3 | `s = floor(m, 1000000)`, `f = (m - s * 1000000) * 1000 + n` | every long `m` | - |
| E31 | R3 to R1 | `q = f / 1000`, `m = s * 1000000 + q`, `n = f - q * 1000` | `s` from -9223372036854 to 9223372036853 | `m` wraps for some `f` |
| E14 | R1 to R4 | `d = floor(m, 86400000000)`, `t = (m - d * 86400000000) * 1000 + n` | every long `m`; then `d` is from -106751992 to 106751991 | - |
| E41 | R4 to R1 | `q = t / 1000`, `m = d * 86400000000 + q`, `n = t - q * 1000` | `d` from -106751991 to 106751990 | `m` wraps for some `t` |
| E23 | R2 to R3 | `s = floor(x, 1000000000)`, `f = x - s * 1000000000` | every long `x` | - |
| E32 | R3 to R2 | `x = s * 1000000000 + f` | `s` from -9223372036 to 9223372035 | `x` wraps for some `f` |
| E24 | R2 to R4 | `d = floor(x, 86400000000000)`, `t = x - d * 86400000000000` | every long `x` | - |
| E42 | R4 to R2 | `x = d * 86400000000000 + t` | `d` from -106751 to 106750 | `x` wraps for some `t` |
| E34 | R3 to R4 | `d = floor(s, 86400)`, `t = (s - d * 86400) * 1000000000 + f` | every long `s`, as a value; `d` is an int for `s` from -185542587187200 to 185542587187199 | the day is no int |
| E43 | R4 to R3 | `q = t / 1000000000`, `s = d * 86400 + q`, `f = t - q * 1000000000` | every int `d` | - |
| E5 | R1 to the 72-bit form | the 72 bits of `m * 1000 + n` | `m` from -2361183241434822606 to 2361183241434822605 | not proved here: not built (section 2) |

A condition on one word gives up a little: R2 could hold part of the microsecond below and
above E12's range, and R1 part of the second and of the day outside E31's and E41's. The
guards are one word wide so that each is one window of one lane (4.3).

**What the first writing had wrong** (the first review's finding 22), restated true:

- E21 holds for **every** long `x`. No `x` is excluded, `Long.MIN_VALUE` included.
- "E21 then E12 gives `x` back" holds for `x` from -9223372036854775000 to
  9223372036854774999. For the 808 values above, 9223372036854775000 to `Long.MAX_VALUE`, and
  the 808 below, `Long.MIN_VALUE` to -9223372036854775001, E21 is exact and its first word is
  outside E12's condition, so a kernel that unpacks and packs again declines the batch. The
  proof file states both ends as `sat` checks.
- Every constant is in the script (`R2_M`, `R1_S`, `R1_D`, `R2_S`, `R2_D`, `R4_S`,
  `R1_DAYS`), and the proof file is rendered with the same numbers.

The file also proves, for R1, R3 and R4, that the order of the first word and then the
second is the order of the exact values and that equal words are equal values; and that the
first word of R1 is the floor of the exact value by 1000, where the truncating quotient
differs. 34 checks. Z3 5.1.0 and cvc5 1.4.1 answer each as expected.

`sql/varka/proofs/nanos_guards.smt2`, from the same script, proves the three guard forms of
4.3, each stated over the signs of its wrapping terms (the sign of `a | b` is set where
either's is, of `a & b` where both are, of `a ^ b` where the two differ, of `~a` where `a`'s is
not; the script computes each guard with the bit operators themselves on every case and holds
it to the same test). 30 checks, each as expected under Z3 and under cvc5:

| what | checks | verdict |
| :--- | ---: | :--- |
| the window is exact for every long and every window whose `hi - lo` is under 2^63; the same of int lanes under 2^31 | 2 | `unsat` |
| a window from the smallest long to the largest less the literal is not exact: why a bound at an end of a long takes another form | 1 | `sat` |
| each of the seven windows of 4.3 at its numbers: exact for every long; and at its ends, the two values one outside decline and the two ends do not | 14 | `unsat` |
| the two windows on `d`, in int lanes | 2 | `unsat` |
| the sum's sign is set exactly where `x + y` is no long, for every `x` and `y` | 1 | `unsat` |
| the literal's sum declines exactly above the largest long less the literal, in R1 and in R2; the zone's exactly under the smallest long plus eight hours, in both | 4 | `unsat` |
| the floor's sign is set exactly under the least whole hour that is a long; from there on the lanes' floor is the exact one; and after the literal's sum it needs no guard: each in R1 and in R2 | 6 | `unsat` |

`sql/varka/proofs/nanos_divisions.smt2`, from the same script, proves each division constant of
4.2 exact over its whole dividend range: the fifteen bounded divisions, the nine multiply-highs
over every non-negative long, and for each of those nine that a negative long's floor is the
complement of its complement's quotient; and, for each of the fourteen bounded divisions that
multiply once or twice, that the lane's product stays under 2^64, where a lane wraps and the
exact integers of the first check do not. 47 checks. Z3 answers each as expected in a second.
cvc5 does not finish the file within 60 seconds, so the file is run under Z3, which is what
`dev/varka_prove.sh --lint` runs every file under but two. What it does not prove: that the
four products of 32-bit halves are the high 64 bits of a product. That identity has two
variables multiplied, which neither solver decides; the script runs the lanes' own sequence on
numbers and holds its quotient to the exact one on every call (`Val.fdiv`, `Val.udiv`), over
every case.

No file is rendered from an IR node: this row builds none. Row 699 gives each conversion
it builds a node, and its proof is then the line of this table.

### 4.10 The decision rule

`python3 dev/varka_nanos_costs.py rule <table>` applies it; this section is its text, and the
tool's self-test holds the twenty-nine made-up tables below to their outcomes. It has no
judgement. What it cannot decide is the owner's, in section 15.

**What changed on 28 November 2026.** The owner answered section 15 (section 19 has each answer).
Row 699 builds all three whole-range forms, R1, R3 and R4, whatever the numbers, so the rule
computes no built set: the third writing's step 4, its 2.0 bar, its choice of the better of R3 and
R4 and its line for the case that no host decides between them are gone. R2 may be built where it
is the fastest, so it is a candidate. A scalar loop is a kernel's body where it is the fastest, so
no class is declined at a width. And the AVX-512 readings come first where one default is named
for the project. What the rule gives now: each class's default form and layout on each host and
width, how much every other form loses there, and the project's defaults.

**What it reads.** The `loop` rows of one host and width whose operands arrive and are stored as
Spark's parts. A ratio of 1.3 or more is beyond the band; under it the two are a tie, as the house
reads a ratio.

**A fallback is never a time, and a missing time is never a win.** A loop whose `jit` is
`fallback` has no time. A candidate with no time in a class has *no data* there, which is not a
loss: the class is then undecided, it has no default, and the output says which form lacks the
reading. The rule never compares a form with nothing.

**The candidates.** R1, R3 and R4 in every class: they hold every value Spark can hold, and row
699 builds all three. R2 in the four classes where it has loops over Spark's parts (4.1): compare,
choice, interval and chain. In the other five (clock, date, truncate, zone, stored) R2 has no loop
over Spark's parts and is no candidate: its work there is R1's with a conversion in front and, for
a timestamp result, one behind, so it cannot be the fastest.

**What R2's time holds, and what it does not.** A loop that computes in R2 over Spark's parts
loads both of R1's words, tests the first word against R2's range (the window guard of 4.3, ORed
into the accumulator in every lane group and read once after the last), converts (`m * 1000 + n`),
computes, and for a timestamp result converts back and stores R1's two words. All of that is
inside what is timed: `LT R2L 1>1` is, for each operand, two loads, the guard's two subtractions
and its OR, and the conversion; then the compare. So over Spark's parts R2's time is the whole
price of an R2 kernel on a batch inside 1677 to 2262, its batch guard included, and its comparison
with R1, R3 and R4 is fair there as the loops are: no loop is added, and no generated class
changes (section 19 has the check). Two things the study does not time. The first is in the rule's
own line: a batch with a value outside the range goes to the row path, after the loop has run over
it once for nothing, so such a batch costs R2's loop and the row path's time, and how often a
batch is one is the data's. The second is the guard's AND with the validity word in a batch with
nulls (4.3), which every guarded loop of every form lacks alike. Both are row 699's to measure, on
row 655's classes (4.12).

**The classes.** compare (`LT`), choice (`PICK`), interval (`ADD`, `ADDC`), clock (`HOUR`, `SEC`),
date (`YEAR`), truncate (`TRUNC`), zone (`ZONE`), stored (`MICROS`, `HASH`), chain (`CHAIN`). A
class of two loops takes their geometric mean. `ROUND` is in no class: it is the base.

**Step 1, a form's reading in a class.** Its vector time is the least over its vector layouts (H,
L, M) in which every loop of the class is no fallback. Its scalar time is its scalar loop's (S),
where that is no fallback. Its reading is the vector time; or the scalar time, where no vector
layout has a time, or where the vector time is 1.3 times the scalar time or more (the vector loop
is the slower by the band). A reading taken from the scalar loop is *a scalar body*: row 699
builds a kernel whose body is that loop (4.12), and the output has a line `scalar <class> <form>S
stands for <form>:` with the reason, `no vector loop without a fallback` or `its best vector loop
(<form><layout>) takes <ratio> times its time`. A form with neither time has no data in the class.
A scalar body is what is expected at 128 bits of the compare, the choice, the zone and the chain
in every form, and of the interval in R3 and R4, where every vector loop has a mask and falls back
(4.7): each form's scalar loop stands for it there, and the default is the fastest of them by step
3.

**Step 2, an undecided class.** Where the table holds no loop of the class in any candidate at the
host and width (a part of a run that was lost, an operation that was not timed), the class's line
is `default <class> undecided: no loop of the class in the table`. Where some candidate has a
reading and another has none, it is `default <class> undecided: no data for <forms>`, followed by
the readings there are. An undecided class has no default: nothing is named from a part of the
data.

**Step 3, the default of a class**, at one host and width. Among the candidates, the forms whose
reading is within 1.3 of the fastest are *tied*. Where the fastest is tied with no other, it
*wins* and is the default. Otherwise the default is the first of the tied forms in the order R1,
R3, R4, R2. R1 is first because it is the storage, pays no conversion and holds every value; R3 is
the owner's second form and R4 his third; R2 is last because it holds 1677 to 2262 alone. So R2 is
a default only where it wins: where every other candidate takes 1.3 times its time or more. The
line is one of

    default   <class>   <form><layout> <ns>[, a scalar body,] wins; <each other candidate: its reading and (<ratio> times)>
    default   <class>   <form><layout> <ns>[, a scalar body,] first of a tie of <forms>; <the same>

The ratio is the other form's time over the default's, so the line says how much each form loses
where it is not the default; in a tie a ratio can be under 1. A form's layout is the one that gave
its reading in step 1, so a default can differ by width, as row 600 allows.

**Step 4, R2 as a default.** Its line ends `; R2 holds 1677 to 2262 alone: a batch with a value
outside goes to the row path; of the forms that hold every value: <form><layout>`. The last is
step 3 applied to R1, R3 and R4 alone: the same class's default for a kernel that must hold every
value.

**Step 5, data that arrives as epoch nanoseconds.** For the compare and the chain, from the mirror
loops: R2's reading, which computes on the data as it is, against the best of R1, R3 and R4, which
convert first. Where R2 is 1.3 times faster or more the line ends `it wins`. This is the
measurement behind row 699's item "a column that arrives as epoch nanoseconds" (4.12; the owner's
answer 3). The mirror loops `HOUR` and `ROUND` are read by no step of the rule: they are rows of
the cost table, where they give the residual of a clock field and the edges from R2.

**Step 6, the project's defaults: AVX-512 first.** Each host and width takes its own defaults, by
steps 1 to 4. Where one default must be named for the project (a document, the first kernels
built, the order in which row 699 builds the forms), the readings are taken in this order, by the
owner's word that AVX-512 has high priority: the laptop's block at 512 bits (the host whose name
starts `AMD Ryzen AI 9 HX PRO 370`, as `sql/varka/HARDWARE.md` names it); then a block at 512 bits
of another host, a runner with AVX-512, several such hosts in the order of their names; then the
AMD EPYC 7763 at 256 bits. The output's line `the
project's defaults, each from the first of these readings that has one:` lists the readings the
table holds, in that order. A class's project default is the default of the first of them that has
one, and its line names the reading: `project <class> <form><layout> (<host> at <bits> bits)`. A
class undecided in all of them reads `project <class> undecided: no reading of the order has a
default`. A table with none of the three readings ends in `the project's defaults: none, for want
of a reading at 512 bits or of the AMD EPYC 7763 at 256 bits`. Two lines follow the classes.
`order R1 (the storage; <n> classes), <R3 or R4> (<n>), <the other> (<n>)` is the order in which
row 699 builds the forms: R1 first, then R3 and R4 by the count of classes each is the project's
default of, R3 first between equal counts. And `r2` lists every class, host and width where R2 is
the default, as `a default, behind its batch guard: <class> on <host> at <bits> bits; ...`, or
says `nowhere a default`: row 699 builds an R2 kernel for the listed classes and for no other.

The EPYC 7763's readings stay, at 256 and at 128 bits. It is the CPU the committed baselines and
the house's bars are read on; its 256-bit block is the last of the order; and its own `default`
lines are what a kernel takes on that host.

**The arithmetic of every step.** The tool compares the times as the cost table holds them, three
decimals, in binary floating point, and prints each ratio to two decimals beside the verdict it
gave. A ratio that is 1.30 by hand can fall on either side there (`1.69 / 1.3` is under `1.3` in a
double), so at a boundary the verdict is the tool's line and not a division by hand, and the
printed ratio says how near it was.

**The rule applied by hand to twenty-nine made-up tables** (the tool's `CASES`; times in
nanoseconds a row; a scalar loop is four times its vector loop unless said; every loop `ok` unless
said; R1 is 2.0 on every loop; a form the table does not name, R2 included, is 3.0 on every loop;
the host is the EPYC 7763 at 256 bits unless said). Tables 1 to 4, 6, 7, A, B2, C, C2, C3, D, E,
F, G, H, M, Z and R are the third writing's, each with its outcome under the rule as it now is.
Table 5 is the third writing's with other times on `YEAR`. Tables 8, N, Q, S, S2, S3, P1, P2 and
P3 are new. The third writing's tables 8 (the third form built at the 2.0 bar), B (a second copy
of B2) and U (an undecided deciding host) had nothing left to show and are gone.

| # | the table | what the output holds | what it shows |
| :--- | :--- | :--- | :--- |
| 1 | nothing but the common times | every class `R1L 2.000 wins`, each other form `(1.50 times)`; the compare's line lists `R2L` beside `R3L` and `R4L`, the clock's does not; `order R1 (the storage; 9 classes), R3 (0), R4 (0)`; `r2 nowhere a default` | R2 is listed where it is a candidate and nowhere else; with no 512-bit reading the project's defaults are the runner's |
| 2 | R3 2.2, but 1.25 on `HOUR` and `SEC`; R4 2.0 on those two; no row of R2 | clock `R3L`, a win by 1.6 times; date `R1L 2.000 first of a tie of R1, R3` | a win, and a tie that goes to R1 |
| 3 | R3 2.2, 1.8 on the clock loops; R4 0.5 on `HOUR`, 0.6 on `SEC`, 1.0 on `YEAR`, 0.8 on `TRUNC` | clock, date and truncate `R4L`; compare `R1L`; `order R1 (the storage; 6 classes), R4 (3), R3 (0)` | the order follows the classes each form defaults |
| 4 | R2 2.4; arriving as epoch nanoseconds, `LT` is 1.0 in R2, 3.0 in R1, 3.5 in R3 | compare `R1L 2.000 first of a tie of R1, R2`; `arriving compare as epoch nanoseconds: R2L is 3.00 times the best other: it wins` | R2 inside the band of R1 is no default; the arriving line is the owner's own case |
| 5 | R3 2.2, 1.4 on the clock loops, 1.1 on `YEAR`; R4 2.0 on the clock loops, 1.0 on `YEAR`; no row of R2 | clock `R3L`, a win by 1.43 times; date `R3L 1.100 first of a tie of R3, R4`, though R4 is the faster | a tie without R1 keeps the order too |
| 6 | R4 1.0 on `HOUR` and `SEC`, and its two vector loops there are `fallback` | clock `R1L 2.000 wins; R3L 3.000 (1.50 times), R4S 4.000 (2.00 times)`; `scalar clock R4S stands for R4: no vector loop without a fallback` | a fallback's 1.0 is never read; the form's scalar loop stands for it |
| 7 | R3 2.5 and R4 2.4; no row of R2 | clock `R1L 2.000 first of a tie of R1, R3, R4`; compare `undecided: no data for R2; R1L 2.000, R3L 2.500, R4L 2.400` | a tie of all three; a candidate with no row leaves its class undecided |
| 8 | R2 1.0 on `LT`, 1.6 on `PICK` | compare `R2L 1.000 wins`, its line ending `; R2 holds 1677 to 2262 alone: a batch with a value outside goes to the row path; of the forms that hold every value: R1L`; choice `R1L 2.000 first of a tie of R1, R2`, with no such ending; `r2 a default, behind its batch guard: compare on AMD EPYC 7763 (made up) at 256 bits` | R2 as a default, with its range, its fallback and the whole-range form beside it |
| A | R3 2.2, but 1.33 on `HOUR` and `SEC` and 2.8 on `LT` | clock `R3L`, a win by 1.50 times; compare `R1L 2.000 wins`, by 1.40; date `R1L` | the band at its edge |
| B2 | R3 1.6; R4 and R2 3.5 | every class a tie of R1 and R3, so `R1L` | 1.25 is inside the band |
| C | R3 1.0, but 0.5 on `ADD` and `ADDC`, whose vector loops are `fallback` | interval `R1L 2.000 first of a tie of R1, R3; R3S 2.000 (1.00 times)`; clock `R3L` | the fallback's 0.5 is not read: R3's scalar loop, at 2.0, is |
| C2 | R3 and R4 0.5, every vector loop of both a `fallback`; no row of R2 | clock `R1L 2.000 first of a tie of R1, R3, R4; R3S 2.000` | the same in every class |
| C3 | R3 1.0; R3's vector `ADDC` alone a `fallback` | interval `R1L 2.000 wins; R3S 4.000 (2.00 times)` | a class of two loops needs both in one layout |
| N | R3 1.0; R3's `HOUR` a `fallback` in layout L and in S | clock `undecided: no data for R3; R1L 2.000, R4L 3.000` | no data is no default, whoever else has a time |
| R | R2 1.0 on `LT`; R4's `LT` a `fallback` in L and in S | compare `undecided: no data for R4; R1L 2.000, R3L 3.000, R2L 1.000`; `project compare undecided: no reading of the order has a default` | R2's time is printed in an undecided class, and wins nothing there |
| Z | no row of `ZONE` in any form | zone `undecided: no loop of the class in the table`; clock `R1L` | a class with no row is said to have none |
| D | at 256 bits R4 0.5 on the clock loops; at 128 bits R3 0.5 on them | at 256 bits clock `R4L`; at 128 bits `clock R3L 0.500 wins` | each width has its own default |
| E | R2 2.2; arriving as epoch nanoseconds R2 1.0 on `LT` and 1.5 on `CHAIN`, against R1's 3.0 and 6.0 | compare `R1L`; `arriving chain as epoch nanoseconds: R2L is 4.00 times the best other: it wins` | the chain's arriving line; the mirror `HOUR` is read by no step |
| F | R3 2.2, but 1.0 on the clock loops; R2 2.2, but 1.1 there | `clock R3L 1.000 wins; R1L 2.000 (2.00 times), R4L 3.000` | R2's 1.1 takes no part in a class where it is no candidate |
| Q | R2 0.5 on `HOUR` and `SEC` | clock `R1L` | the same, where R2's row is the fastest of all |
| G | R1 also in layout H, 1.0 on every loop | `R1H` in every class | layout H is read |
| H | as G, but R1's `SEC` in layout H is a `fallback` | clock `R1L`; date `R1H` | a layout counts where every loop of the class is no fallback |
| M | R3 also in layout M, 3.0 but 1.0 on `HOUR` and `SEC` | clock `R3M`; date `R1L` | layout M is read |
| S | R2 1.0 on `LT`; every vector `LT` of all four forms a `fallback` | compare `R2S 4.000, a scalar body, wins; R1S 8.000 (2.00 times)`, ending `of the forms that hold every value: R1S`; `scalar compare R1S stands for R1: no vector loop without a fallback`; clock `R1L` | the masked classes at 128 bits: the default is a scalar body, of the fastest form |
| S2 | every scalar loop at 0.7 times its vector loop | clock `R1S 1.400, a scalar body, wins; R3S 2.100 (1.50 times)`; `scalar clock R1S stands for R1: its best vector loop (R1L) takes 1.43 times its time` | a vector loop slower by the band gives way to the scalar loop |
| S3 | every scalar loop at 0.8 times its vector loop | clock `R1L`; no `scalar` line | 1.25 is inside the band: the vector loop stays |
| P1 | the runner at 256 bits as table 1; an Intel Xeon Platinum 8573C at 512 bits with R4 1.0; the laptop at 512 bits with R3 1.0 | the order's line ends `one: AMD Ryzen AI 9 HX PRO 370 (made up) at 512 bits; Intel Xeon Platinum 8573C (made up) at 512 bits; AMD EPYC 7763 (made up) at 256 bits`; `project clock R3L (AMD Ryzen AI 9 HX PRO 370 (made up) at 512 bits)`; `order R1 (the storage; 0 classes), R3 (9), R4 (0)`; the runner's own clock `R1L` | AVX-512 first, the laptop before another host; each host keeps its own defaults |
| P2 | the runner at 256 bits with R4 1.0; the laptop at 512 bits with R3 1.0, its `HOUR` a `fallback` in L and in S | `project clock R4L (AMD EPYC 7763 (made up) at 256 bits)`; `project date R3L (AMD Ryzen AI 9 HX PRO 370 (made up) at 512 bits)`; `order R1 (the storage; 0 classes), R3 (8), R4 (1)` | a class undecided in the first reading takes the next |
| P3 | the runner at 128 bits alone | `the project's defaults: none, for want of a reading at 512 bits or of the AMD` | no reading of the order, no project default |

Table 4 is the owner's own case: one long wins where the data arrives as one long, and the rule
says so in a line of its own. Table 8 is his answer 2: R2 is a default where it wins, and its line
carries its range and its fallback. Tables 6, C, C2, C3 and N show that a fallback is no time and
a missing time no win. Tables F and Q hold R2 to the classes where it has loops. Tables G, H and M
hold the rule to every vector layout; Z shows that a class with no row is not a decided class; D
that each width has its own default; S, S2 and S3 the scalar body from both sides of the band; and
P1 to P3 the project's order. The quoted lines of the third column are the tool's, but for the
runs of spaces it pads its columns with; the self-test checks each table's defaults and the lines
its entry in `CASES` names.

### 4.11 The storage: what the second writing planned here, and what it waits for

The second writing had three more parts, N1 to N3, which this plan no longer has. They were
the half of the work that no measurement can change: Spark's cache holds R1 as an Arrow struct,
so whatever a kernel computes in, it reads that struct's two children and writes two.

**What they were.**

| part | what it built |
| :--- | :--- |
| N1 | the registry's entries of the two types turned from `Declined`; a nanosecond column admitted by the evaluator as its struct's two children, each a kernel input that computes nothing (two new kinds of `VarkaDerivedKind`); the null tests `isnull` and `isnotnull` fused over the struct's validity |
| N2 | a nanosecond output: two roots an entry, written as one Arrow struct the cache takes back, its children's validity set where the struct's is; `coalesce`, `ifnull` and `nvl` fused, the one form that gives the write a caller |
| N3 | the two types held to the conformance contract (two suites, the coverage columns, the first census claims), over 52 values at the range's ends |

**Why they left.** The second review read them as their builder and found them below the
standard of `VARKA-656.md`: their premises were row 661's plan and not the code (three of
their premise greps print `0` until row 661 is built, and every edit of "the comment row 661
wrote" quotes text no repository has); the census edit named a file that row 653 moves; the
suites were tables of cases with no call of the kernel harness; and three facts they rest on
only a JVM with Spark can settle. The parts P1a to T2 are buildable because every file of
theirs is whole in an appendix and was run; the storage parts had none of that.

**What they wait for.**

| wait for | why |
| :--- | :--- |
| row 661 built (its parts A to D) | the two type ids, the `Declined` entries and the decline text that the storage work turns, as code that a premise grep can read |
| row 653 built | the census files' place and form, which the first census claims edit |
| three answers from a JVM with Spark on the class path | whether Spark parses the literals the conformance fixture needs, and builds its columns; whether Arrow's loader hands a cached struct with no null a validity buffer; whether a nanosecond column passes through a Varka node once row 661 has declined the type |
| this row's part T2b | nothing of the storage itself: but row 699's plan is one plan, and its kernels wait for the rule |

**Where they go.** They are the first item of row 699 (4.12), planned in row 699's own plan
after row 661 is built, with a sheet of `VARKA-656.md`'s kind: each edit as the old and the new
text on the code as it then is, each test as a file, the census edit as a before and an after.
Row 699's planner starts from this page and from the code as it then is; nothing of the second
writing's sheets binds it.

### 4.12 The outline of what is left: row 699

Not planned here and not sheeted. Each item names what it needs.

| item | what it is | what it needs |
| :--- | :--- | :--- |
| the storage | the cache's struct in and out, the null tests, `coalesce`, the conformance suites and the first census claims (4.11) | row 661 and row 653 built; the three answers of 4.11. Nothing of the study |
| the three forms, in order | R1, R3 and R4 are all built, whatever the numbers (the owner's answer 6). R1 first, then R3 and R4 in the order of the rule's `order` line | the rule's step 6 |
| the conversions as IR nodes | a node an edge of 4.9 among R1, R3 and R4, and the edges to and from R2 where an R2 kernel is built, each with its range condition readable on the node, its lowering, its proof line and its arm in every switch over the IR | the guards' forms of 4.3; the rule's `r2` line; the divisions' measured cost, which says whether the lowering is the multiply-high or the house's forms by host |
| the comparisons, `<=>`, `greatest`, `least` | over the words of each form; the default by host and width is the compare class's | the compare and choice classes' `default` lines |
| `IN`, `in_set`, `between`, `equal_null` | `k` equalities, or Spark's rewrites of them | the same |
| `if`, `when`, `nvl2`, `nullif` | a pick under any fused condition | the choice class |
| `hash`, `xxhash64` | Spark's steps over R1's two words | the stored class: whether a value computed in another form pays its edge back |
| the cast to microseconds, `hour`, `minute`, `second` with its fraction, the date fields | in each form, the clock and date classes' defaults first | those classes; the cells of R1 against R3 and R4 |
| the option | how a kernel is told which form to compute in. Its default is by host and width, from the rule's `default` lines, and the rule's `project` lines where one default is named; every form that is not a default stays an arm of the option matrix | the rule's output; row 600's word that a default may differ by width |
| the R2 kernels with their batch guard and fallback | for each class the rule's `r2` line lists: a kernel that computes in one long of nanoseconds. Its guard runs on every batch (4.3's window on the first word), and a batch that holds a value outside 1677 to 2262 goes to the row path (the owner's answer 2, which reverses `VARKA-660.md` 4.7; section 13, edit 10). **Done when** each such kernel answers as the row path does on a batch inside the range and hands every batch with a value outside it to the row path. Not known: how often a real batch is outside; what such a batch costs, the kernel's wasted pass and the row path (the study times neither; 4.10); how the guard counts null lanes (4.3); how the census marks a cell that is fused for 1677 to 2262 alone | the `r2` line; the guards' proofs in `nanos_guards.smt2`; row 655's classes for the two costs |
| a kernel whose body is a scalar loop | where the rule's default is a scalar body (`<form>S, a scalar body`), as is expected of the masked classes at 128 bits: a kernel that runs the form's scalar loop, not a decline (the owner's answer 5). **Done when** each class whose default at a host and width is a scalar body runs inside a kernel there and answers as the vector kernels do. It is a new kind of kernel, and nothing of it is designed here. Not known: how the emitter emits a scalar loop; whether a scalar body still beats the row path with a kernel's validity, tail and guard around it (the study's scalar loops have none of the three); what it adds to the option matrix | row 698's verdict, read first: a mask-free two-lane vector form that wins there replaces the scalar body. Then the classes whose default at 128 bits is a scalar body, and row 655's 128-bit sections |
| a column that arrives as epoch nanoseconds | it is admitted as it is, with the form's range: a kernel computes in R2 on the long as it arrives and pays no conversion from Spark's parts (the owner's answer 3). The study's arriving loops (4.1's mirror) are the measurement behind it. Not known: no source hands a Varka node such a column today, and which node admits it is row 699's plan to say | the rule's `arriving` lines (step 5) |
| the measurement on row 655's classes | R1, R3 and R4, and R2 where it is built, on `VarkaTimestampNanosBaselineBenchmark` and its three siblings, at 256 and 128 bits | nothing: it measures kernels, and the study's rule is scored against it |

**The recommendation: a new row, 699**, filed by this plan's pull request (section 13, edit
2), and row 662 closes on the three clauses of section 1. The reasons: every item above but
the first waits on numbers that do not exist, and the first on rows that are not built, so a
sheet written now would be written on a guess, as the first writing's eleven code parts were;
row 662's done-when is then all checks a builder can run; and rows 673 to 675 get one row to
name as "first".

### 4.13 What is out of this row

| excluded | who holds it |
| :--- | :--- |
| the cache's struct in and out, the null tests, `coalesce`, the conformance suites, the census claims | row 699 (4.11, 4.12) |
| every kernel that compares or computes a nanosecond value, in R1, R3, R4 or R2; the conversion nodes; the option | row 699 (4.12) |
| every field but none, every cast, `timestampadd`, the sums, `date_trunc`, the `unix_*` functions | rows 673, 674, 675, after row 699 |
| a load, a compare or a carry with no mask at two long lanes | row 698 for a two-word value, row 689 for a zone step (section 12) |
| an e-graph, an extraction, a rewrite engine, a dependency on jegg | `m10/SCOPE.md` items 11 and 85; this row adds no dependency |
| the 72-bit form as an arm | none: not built (section 2) |
| the house's conversion and `2^52` division forms as arms of every loop | none: the control times the first once; question 4 of section 15 |
| a scalar body in a kernel | row 699 (4.12), by the owner's answer 5; this row designs none |
| a kernel input that arrives as one long of nanoseconds | row 699 (4.12), by the owner's answer 3; which node admits it is its plan's to say |
| a nanosecond timestamp surface against stock Spark | none (`VARKA-655.md` 14: the owner decided that this milestone has none) |
## 5. The parts

| part | title | branch | needs | gives | counted lines | built by |
| :--- | :--- | :--- | :--- | :--- | ---: | :--- |
| P1a | `[VARKA-662] Write the nanosecond study's loops over Spark's parts and one long as formulas` | `varka-662p1a-formulas` | this plan merged | the lane values, the divisions, the guards, R1 and R2, the loops and their check on numbers | 943 | a cheaper model |
| P1b | `[VARKA-662] Add seconds and days to the nanosecond study's formulas` | `varka-662p1b-formulas` | P1a | R3 and R4; the check now runs all 322 loops; P2 and T1 read both files | 210 | a cheaper model |
| P2 | `[VARKA-662] Generate the study loops' Java from the formulas` | `varka-662p2-loops` | P1b | the generator, `VarkaNanosSpikeOps`, the pilot's twelve loops, compiled, and the check of them in CI | 705 | full strength |
| P3 | `[VARKA-662] Hold the study's loops to Spark's methods` | `varka-662p3-suite` | P2 | the checker and the suite: every later loop is tested the day it is generated | 751 | full strength |
| P4 | `[VARKA-662] Time the pilot of the nanosecond representations' study` | `varka-662p4-pilot` | P3 | the harness, the control, the pilot's five readings and its report | 662 | full strength, with the lead |
| S1 | `[VARKA-662] Add the study's loops over Spark's parts and one long` | `varka-662s1-loops` | P4, and the lead's go (5.5) | 128 loops, tested: the 124 of R1 and R2, and the pilot's four of R3 | 10 | full strength |
| S2 | `[VARKA-662] Add the study's loops over seconds and over days` | `varka-662s2-loops` | S1 | 322 loops, tested | 10 | a cheaper model |
| S3 | `[VARKA-662] Time the nanosecond representations on the laptop and the runners` | `varka-662s3-readings` | S2 | the results and provenance files | 40 | the lead, with a builder |
| T1 | `[VARKA-662] Prove the conversions and the guards of the nanosecond representations` | `varka-662t1-proofs` | P1b | the three proof files | 531 | a cheaper model |
| T2a | `[VARKA-662] Add the tool of the representations' cost table and rule` | `varka-662t2a-tool` | this plan merged | `dev/varka_nanos_costs.py`, held by its self-test on made-up tables | 906 | a cheaper model |
| T2b | `[VARKA-662] Write the representations' cost table and apply the rule` | `varka-662t2b-costs` | S3, T1, T2a | the cost table, the rule's outcome, section 11; row 699 can be planned | 90 | the lead, with a builder |

Where this plan says T2 with no letter it means T2a and then T2b.

**The order, and why.** The check comes before what it checks and the measurement before what it
decides: the formulas are checked on numbers (P1a, P1b) before any Java exists; the Java is held
to Spark (P3) before anything is timed (P4); the pilot's reading comes before the 310 loops it
prices (S1, S2); the numbers (S3) come before the table and the rule (T2), and the rule before
row 699's plan. T1 can be built any time after P1b, and T2a any time. Every part leaves master
green. The counted lines are exact where the file is in an appendix: `python3
dev/varka_msg_check.py size` counts a `.py`, a `.java` and a `.sh` file's lines, and counts
nothing for a new file whose first lines say `Generated by` (fact 14), which the four loop
classes do, nor for a results file.

**The cut lines.** A pull request stays under 1000 counted lines (section 10). The counts
above are of the files as `ruff format` leaves them, which is how the appendices hold them
(5.0). The second writing's P1 was one file of 771 counted lines with no cut. It
is now two parts, each checked alone: P1a is `dev/varka_nanos_spike.py` with R1 and R2, whose
check runs the 112 loops that meet no other form; P1b adds `dev/varka_nanos_spike_r34.py`, and
the same command then runs all 322. P1a (943) and T2a (906) are the two parts near
the cap. Each is one file taken whole from this plan and nothing else (P1a with the two lines
of the status edit), and a builder changes neither but by the formatter (5.0), so neither
grows. T2 was one part; the tool and what the lead writes from the numbers together passed
the cap, so it is cut where the tool's self-test ends: T2a is the tool, T2b the table, the
rule's output, section 11 and the skill's section. T2b stops at 300 counted lines: past that,
the skill's section and `SKILLS.md` go to a pull request of their own, after T2b. P3 is
751; if a repair takes it to 900 or more, its cut line is the suite's fourth test, "the
benchmark's columns reach every outcome the loops decide", which moves to P4 with the
benchmark whose data it holds. P2 is 705 and P4 662, of which 80 are the estimate of
the pilot's report in section 11; no other part is over 300.

**Who builds what, and why.** P1a, P1b and T1 are files taken whole from this plan and one
command that decides, and so is T2a: a cheaper model. P2 is the first compile under the
repository's own `javac` flags and linters of text that so far met the JDK's `javac` alone, with
the repair procedure: full strength. P3 and P4 are the first run against Spark. S1 is the first
run of the suite over 128 loops at three widths: full strength. S2 is the same task over forms
whose loops have compiled and run JDK-only: a cheaper model. `m9/PLAN.md` 1.4 puts this row in
phase 2's foundation, built at full strength; the five parts given to a cheaper model are those
whose every decision is a file of this plan and whose oracle is one command.

### 5.0 What every sheet shares

**Paths.** `TQ=sql/catalyst/src/test/java/org/apache/spark/sql`,
`TJ=$TQ/catalyst/expressions/codegen/varka`, `M9=sql/varka/plans/m9`,
`B=sql/catalyst/benchmarks`, `C=VarkaNanosRepresentationSpikeBenchmark`.

**Every command that starts a JVM** runs under the heavy slot's lock. Its file is
`$VARKA_HEAVY_LOCK` where the lead has set that, and else `$HOME/proj/.varka-locks/heavy.lock`,
the default of the repository's own tools (`dev/varka_regen_generated.sh`, line 142;
`dev/varka_push_check.py`, line 488). So a part's shell first runs

    L=${VARKA_HEAVY_LOCK:-$HOME/proj/.varka-locks/heavy.lock}; mkdir -p "$(dirname "$L")"

and a command is then

    JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 SPARK_LOCAL_IP=127.0.0.1 \
      flock "$L" build/sbt -batch '<task>'

Where a sheet or section 14 prints `build/sbt <task>` bare, this is the line that is run, with
the task quoted as the sheet has it. A variable a sheet puts before a command
(`JDK_JAVA_OPTIONS=...`) goes with the other two, before `flock`; 5.4 and 5.6 print such lines
whole. `dev/varka_push_check.py` takes the lock itself and is never run under `flock`. A JUnit
suite's run ends with sbt's line `Passed: Total <n>, Failed 0, Errors 0, Passed <n>`. After a part's
code: `python3 dev/varka_push_check.py`, exit 0; then `python3 dev/varka_msg_check.py size`,
which prints the counted lines. The builder never pushes, never dispatches a workflow, never
runs a timed benchmark and opens no pull request: it stops with the branch, the commit and the
commands' outputs, and the lead does the rest.

**A B0 line that does not print what the sheet says** ends the part, with the line and its
output reported.

**The row's status.** Part P1a's first step changes `**Planned**` to `**Started**` in row
662's line of `$M9/PLAN.md`. Part T2b's last step changes it to `**Done**`.

**What `git status --short` shows** when a part's steps are done, before its commit. `??`
is a new file, `M` an edited one; a part that shows another line has touched what it must not.

| part | lines |
| :--- | :--- |
| P1a | `M $M9/PLAN.md`; `?? dev/varka_nanos_spike.py` |
| P1b | `?? dev/varka_nanos_spike_r34.py` |
| P2 | `M dev/varka_scoped_suites.sh`; `?? dev/varka_nanos_spike_gen.py`; `?? $TQ/VarkaNanosSpikeOps.java`; `?? $TQ/VarkaNanosSpikeLoopsR1.java` to `R4.java`, four lines |
| P3 | `?? $TQ/VarkaNanosSpikeCheck.java`; `?? $TQ/VarkaNanosRepresentationSpikeSuite.java` |
| P4 | `M $TQ/VarkaNanosSpikeCheck.java`; `?? $TQ/VarkaNanosSpikeLoopsControl.java`; `?? $TQ/$C.java`; ten `??` lines of `$B/$C-jdk25-...-results.txt` and `-provenance.txt`; `M $M9/VARKA-662.md` |
| S1 | `M $TQ/VarkaNanosSpikeLoopsR1.java`; `M $TQ/VarkaNanosSpikeLoopsR2.java`; `M $M9/VARKA-662.md`. The classes of R3 and R4 do not change: the set `r12` keeps the pilot's loops |
| S2 | `M $TQ/VarkaNanosSpikeLoopsR3.java`; `M $TQ/VarkaNanosSpikeLoopsR4.java`; `M $M9/VARKA-662.md` |
| S3 | ten `M` lines of the laptop's and the runner's results and provenance files, and two `??` of the AVX-512 file where there is one; `M $M9/VARKA-662.md` |
| T1 | `?? dev/varka_nanos_spike_proofs.py`; three `??` lines of `sql/varka/proofs/nanos_*.smt2`; `M sql/varka/proofs/README.md` |
| T2a | `?? dev/varka_nanos_costs.py` |
| T2b | `?? $M9/results/VARKA-662/` (a new directory shows as one line); `M $M9/VARKA-662.md`; `M $M9/PLAN.md`; `M sql/varka/skills/vector-api-and-width.md`; `M SKILLS.md` |

**Taking a file from this plan.** Each appendix is a heading `### File: <name>` and the file,
indented by four spaces. This prints one:

    python3 - $M9/VARKA-662.md '<name>' > <where> <<'EOF'
    import sys
    lines = open(sys.argv[1]).read().split("\n")
    out = []
    for line in lines[lines.index("### File: " + sys.argv[2]) + 1:]:
        if line.startswith("#"):
            break
        out.append(line[4:] if line.startswith("    ") else line)
    sys.stdout.write("\n".join(out).strip("\n") + "\n")
    EOF

and `sha256sum <where>` must print the file's line below. A sum that differs: stop.

| appendix | file | goes to | lines | SHA-256 |
| :--- | :--- | :--- | ---: | :--- |
| A | `dev/varka_nanos_spike.py` | `dev/varka_nanos_spike.py` | 941 | `bc9c9372b78e2aa09dfd8fb968e96e8f3db632e9b04dd68c5aa66cddecf8903d` |
| B | `dev/varka_nanos_spike_r34.py` | `dev/varka_nanos_spike_r34.py` | 210 | `fc9716e51d275c5f4ecadadd99ec18c7e650378d1e2972d9e31cfcb9290974b2` |
| C | `dev/varka_nanos_spike_gen.py` | `dev/varka_nanos_spike_gen.py` | 538 | `7c952667d67e6bb002e3654e544b0b4f949272b11af94d9f178cb49338cf9699` |
| D | `dev/varka_nanos_costs.py` | `dev/varka_nanos_costs.py` | 906 | `ac0cb3b6e5fa98c590ec668472266b449b31f94279b8534566862f1b52044d2c` |
| E | `VarkaNanosSpikeOps.java` | `$TQ/` | 160 | `19775c93da85a4e0ea3e2db75e43da5f2548806ee58b9135d74de36b53b65075` |
| F | `VarkaNanosSpikeCheck.java` | `$TQ/` | 496 | `fcaf86a39180051d70a29998ee0210e11d5fc04e7088e3b804c155998481bef1` |
| G | `VarkaNanosRepresentationSpikeSuite.java` | `$TQ/` | 255 | `2eefa2c8a026f372ca89e266213de638a1e9269d94febd0c2d0b0a84891f6384` |
| H | `VarkaNanosRepresentationSpikeBenchmark.java` | `$TQ/` | 498 | `c96cef51caae5b1a0f9c415111fc6f8a237ff12122e7ea1a89f9ce018dd1bd08` |
| I | `VarkaNanosSpikeLoopsControl.java` | `$TQ/` | 83 | `f103b5b7cb1669005ab026a8d1c99b62d629ffe2e220a6f8cf291fe5b3561c72` |
| J | `dev/varka_nanos_spike_proofs.py` | `dev/varka_nanos_spike_proofs.py` | 528 | `7a7922524d68e5de3899ed3f5b42926d1826f6b5c3bdc5d166e79352e6a016ac` |

**The Python linter.** `dev/varka_precommit.sh` and CI's `dev/lint-python` hold every Python
file to `ruff check` and `ruff format --check` under the repository's `pyproject.toml`. The
five Python appendices are the formatter's own output (ruff 0.14.8) and pass both; the planner
ran the two on copies in a directory `dev/` beside a copy of that `pyproject.toml`, and after
the formatter every generated class and proof file kept its sum. Each part that adds a Python
file runs, from the repository's root,

    ruff check <file>             # All checks passed!
    ruff format --check <file>    # 1 file already formatted

If a newer ruff wants another form, the builder runs `ruff format <file>`, then the part's own
check again, and reports the file's new sum: the formatter changes no string, so the sums of
the generated classes and of the proof files must still hold, and a sum of those that moves
ends the part. That is the one change a builder makes to a pinned Python file. `ruff check`
finding anything is reported and not repaired by the builder.

**What was compiled, and against what.** The generated loop classes and `VarkaNanosSpikeOps`
name the JDK alone. The planner compiled them with `javac` of JDK 25 and ran every loop (section
3): their text is the text `gen` writes, byte for byte, and the sums of 5.3 and 5.6 pin it. The
other four hand-written classes name Spark and JUnit. The planner compiled them against stubs,
one stub a class, each with the members of the table below and no other, and ran the suite's
four tests under the stubs at 512, 256 and 128 bits, `java.time` standing in for Spark's
methods: `Passed 4, Failed 0` at each. What that shows: the hand-written Java has no syntax or
type error against those signatures; the checker's batches, row counts, declined-batch logic
and the suite's assertions hold together; the six batches run through 322 loops. What it does
not show: that the real classes have exactly these members (the table says where each was
read); that Spark's methods answer as `java.time` does at every row (4.6's oracle is theirs,
and the suite's first run against Spark decides); the benchmark's run, which the stubs do not
execute; the repository's linters.

| name | file, and line at `9e7b1612402` |
| :--- | :--- |
| `VarkaBenchmarkBase`: `section(String, Runnable)`, `benchmark(String, long)`, `note(String)`, `dryRun()`, `only()`, `runCases(VarkaBenchmark)`, `require(boolean, String)`, `requirementFailed(String)` | `$TQ/VarkaBenchmarkBase.java`, 86, 94, 103, 135, 150, 159, 203, 210 |
| `VarkaBenchmark.Builder.timed()`, `build()`; `VarkaBenchmark.addTimerCase(String, TimerCase)`; `Timer.startTiming()`, `stopTiming()` | `$TQ/VarkaBenchmark.java`, 155, 187, 65, 105 (the class `Timer` is at 93) |
| `VarkaEmitterTestSupport.awaitC2Idle(long)`, a `boolean` | `$TJ/VarkaEmitterTestSupport.java`, 470 |
| `@VarkaSuite(ownJvm = true)` | `$TJ/VarkaSuite.java`, 93 (the annotation at 57); used in `$TQ/VarkaDecimalStoreBenchmarkSuite.java`, 61 |
| `TimestampNanosVal.fromParts(long, short)`, `compareTo`, `equals`, `epochMicros`, `nanosWithinMicro` | `common/unsafe/src/main/java/org/apache/spark/unsafe/types/TimestampNanosVal.java`, 88, 125, 105, 57, 59 |
| `DateTimeUtils.getHours(long, ZoneId)`, `getSecondsWithFractionNanos(TimestampNanosVal, ZoneId)` (a `Decimal`), `getYear(int)`, `timestampNanosAddDayTime(TimestampNanosVal, long, ZoneId, DataType, DataType)`, `truncTimestampNanos(TimestampNanosVal, int, ZoneId)`, and `DateTimeUtils$.MODULE$.TRUNC_TO_HOUR()`; `microsToDays(long, ZoneId)` | `sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/util/DateTimeUtils.scala`, 104, 159, 200, 334, 717, 578; `sql/api/src/main/scala/org/apache/spark/sql/catalyst/util/SparkDateTimeUtils.scala`, 363 |
| `Decimal.toUnscaledLong()` | `sql/api/src/main/scala/org/apache/spark/sql/types/Decimal.scala`, 225 |
| `XXH64.hashInt(int, long)`, `hashLong(long, long)` | `sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/XXH64.java`, 60, 71 |
| `DataTypes.createDayTimeIntervalType()`; `new TimestampNTZNanosType(int)` | `sql/api/src/main/java/org/apache/spark/sql/types/DataTypes.java`, 189; `sql/api/src/main/scala/org/apache/spark/sql/types/TimestampNTZNanosType.scala`, 37 |
| `levelInUse`, `codeList`, `childClassPath`, `COMPILE`, `summary`, copied whole into appendix H | `$TQ/VarkaDecimalRemainderSpikeBenchmark.java`, 1295 to 1323, 1469 to 1500, 1581 to 1590 |

**The calls into Scala, and why each compiles.** `DateTimeUtils` is `object DateTimeUtils
extends SparkDateTimeUtils` (line 41), and its file declares no class or trait of that name. For
such an object scalac writes two classes: `DateTimeUtils$`, which holds every member as an
instance method and the object as the static field `MODULE$`; and `DateTimeUtils`, which holds
a static forwarder for each public method of the object, its own or inherited from a trait.
A member with an access qualifier, `private[sql]`, gets no forwarder and stays a public
instance method of `DateTimeUtils$`. The planner read each declaration below in the source;
the third review read the same in the compiled classes' bytes. The stubs had hidden the
seventh line: they gave `TRUNC_TO_HOUR` a static method.

| the call in the Java | its declaration | why it compiles |
| :--- | :--- | :--- |
| `DateTimeUtils.getHours(long, ZoneId)` | `def getHours`, `DateTimeUtils.scala` 104, no modifier | a public method of the object: a static forwarder |
| `DateTimeUtils.getSecondsWithFractionNanos(TimestampNanosVal, ZoneId)` | `def`, 159, no modifier | the same |
| `DateTimeUtils.getYear(int)` | `def`, 200, no modifier | the same |
| `DateTimeUtils.timestampNanosAddDayTime(...)` | `def`, 334, no modifier | the same |
| `DateTimeUtils.truncTimestampNanos(TimestampNanosVal, int, ZoneId)` | `def`, 717, no modifier | the same |
| `DateTimeUtils.microsToDays(long, ZoneId)` | `def`, `SparkDateTimeUtils.scala` 363, in the trait, no modifier | a public method the object inherits: a static forwarder too |
| `DateTimeUtils$.MODULE$.TRUNC_TO_HOUR()` | `private[sql] val TRUNC_TO_HOUR = 4`, `DateTimeUtils.scala` 578 | no forwarder, so the object's own getter is called, as `VarkaChronoCompiler.java` 1186 calls `DateTimeUtils$.MODULE$.TRUNC_TO_YEAR()`; the import of `DateTimeUtils$` is in appendix F |
| `toUnscaledLong()` on a `Decimal` | `def toUnscaledLong: Long`, `Decimal.scala` 225, in the class | an instance method of a class |
| `new TimestampNTZNanosType(9)` | `case class TimestampNTZNanosType(precision: Int)`, 37 | a class's public constructor |

Every other class the hand-written Java names is written in Java: `TimestampNanosVal`,
`XXH64`, `DataTypes`, `VarkaBenchmarkBase`, `VarkaBenchmark`, `VarkaEmitterTestSupport` and
`VarkaSuite`.

Every Vector API call of the generated loops and of the control but two was compiled and run
by the planner: `fromMemorySegment`, `intoMemorySegment`, `add`, `sub`, `mul`, `and`, `or`,
`not`, `lanewise` with `XOR`, `ASHR`, `LSHR` and `LSHL`, `compare`, `blend`, the masked `add`
and `sub`, a mask's `and`, `or`, `cast`, `toLong` and `VectorMask.fromLong`, `convertShape`
with `I2L`, `L2I`, `S2L`, `S2I`, `L2S` and `I2S`, `broadcast`, `zero`, `reduceLanes(OR)`. The
two are the control's `convertShape` with `L2D` and `D2L` and its `DoubleVector.div`, which the
tree makes already (`dev/varka_canary/L2DProbe.java`, 47).

**The repair procedure**, for parts P2 to S2. Where `javac` or the linters refuse the text:

- *The builder may change*: an import; a cast or a generic type argument; the name or the
  argument order of a method of Spark or of the test tree where the tree has it otherwise with
  the same operands and the same meaning; in `dev/varka_nanos_spike_gen.py`, the Java text a
  statement prints (`Sym`, `SymRun`, `loop_java`, `class_java`), where a linter refuses its
  form; a hand-written class's private helper where what it returns is unchanged. After a
  change to the generator: `gen` again, and the sums of the generated classes in the sheet no
  longer hold; report the new sums.
- *The builder may not change*: anything of `dev/varka_nanos_spike.py` or
  `dev/varka_nanos_spike_r34.py` (a formula, a constant, a range, the list of loops), but for
  the formatter's change of 5.0's paragraph on the Python linter; a loop's
  method name or label; the oracle, the table of the rows a loop must decline and the batches
  of `VarkaNanosSpikeCheck` and of the suite; a generated class by hand; a results file.
- *Stop, and report the loop, the row, both answers and the generated method*: a loop that
  compiles and disagrees with Spark where `python3 dev/varka_nanos_spike.py` passes; a loop
  that declines a batch the checker's table accepts, or the reverse; `python3
  dev/varka_nanos_spike.py` failing.

### 5.1 Part P1a: the formulas of R1 and R2

**B0.** `test -f $M9/VARKA-662.md && echo merged` prints `merged`; `git branch --list
'varka-662p1*'` prints nothing; `ls dev/varka_nanos_spike.py` fails.

**Steps.** (1) The status edit of 5.0. (2) `dev/varka_nanos_spike.py`: appendix A, whole, by
5.0's command; `chmod +x`.

**Tests.** The script is its own test. Without the file of R3 and R4 it holds: nine floor
divisions of every long and fifteen bounded ones, each run as the lanes compute it, against
Python's exact division, over the ends of a long, the multiples of each divisor and 20000
random dividends each; then the 112 loops whose three forms are R1 or R2, run on numbers, over
the 3207 cases of its `cases()`: the first words of 4.6's batches and the ends of the guards
that R1 and R2 have, each with the second words 0, 1, 500 and 999; 3000 random ones; each with a
partner and an interval; the fifteen carry rows; and 28 sums of a column at the ends of a long.
A loop passes a case when it declines exactly where the guards' table says (`must_decline`),
and else its answer is the exact integer answer; inside the sure range it may not decline. The
failure only it catches: a formula that is wrong before any Java exists, where the Java would
be right about the formula and wrong about the value.

**Planted faults**: 4.6's second and third, in the script. Each run of `python3
dev/varka_nanos_spike.py` must end in an `AssertionError`.

**Commands.**

    python3 dev/varka_nanos_spike.py        # about 11 s; its last three lines:
    # forms: R1 R2
    # loops: 112 loops over 3207 cases each: 227964 answers equal the exact integers, 26730 declined
    # all checks passed
    ruff check dev/varka_nanos_spike.py             # All checks passed!
    ruff format --check dev/varka_nanos_spike.py    # 1 file already formatted
    python3 dev/varka_msg_check.py size     # the counted lines: the script's, and the plan's two

**Done when** the three lines print, `ruff` passes twice and both faults fail. **Not here:** no
Java; no R3 and no R4.

### 5.2 Part P1b: the formulas of R3 and R4

**B0.** `python3 dev/varka_nanos_spike.py | tail -3 | head -1` prints `forms: R1 R2`; `ls
dev/varka_nanos_spike_r34.py` fails.

**Steps.** `dev/varka_nanos_spike_r34.py`: appendix B, whole. It is a module and not a script:
no `chmod`. `dev/varka_nanos_spike.py` loads it where it exists, and it adds its entries to
that file's tables; no line of `dev/varka_nanos_spike.py` changes.

**Tests.** The same command, which now runs every loop: 322, over 3271 cases (64 more: the
ends of the windows of R3 and R4, each with four second words). The failure only it catches:
the same, for R3 and R4 and every conversion that meets them.

**Planted fault**: 4.6's first. `python3 dev/varka_nanos_spike.py` must end in an
`AssertionError`.

**Commands.**

    python3 dev/varka_nanos_spike.py        # about 33 s; its last three lines:
    # forms: R1 R2 R3 R4
    # loops: 322 loops over 3271 cases each: 811898 answers equal the exact integers, 39598 declined
    # all checks passed
    ruff check dev/varka_nanos_spike_r34.py             # All checks passed!
    ruff format --check dev/varka_nanos_spike_r34.py    # 1 file already formatted

**Done when** the three lines print, `ruff` passes twice and the fault fails. **Not here:** no
Java; no change to P1a's file.

### 5.3 Part P2: the generator and the first loops

**B0.** `python3 dev/varka_nanos_spike.py | tail -1` prints `all checks passed`; `ls
$TQ/VarkaNanosSpikeOps.java` fails; `git branch --list 'varka-662p2*'` prints nothing; and
this prints `1`:

    grep -c -F -x 'matrix=${VARKA_MATRIX:-dev/varka_matrix.sh}   # the runner; a test puts a stub here' dev/varka_scoped_suites.sh

**Steps.**

1. `dev/varka_nanos_spike_gen.py`: appendix C, whole; `chmod +x`.
2. `$TQ/VarkaNanosSpikeOps.java`: appendix E, whole.
3. `python3 dev/varka_nanos_spike_gen.py gen pilot $TQ`. It writes the four loop classes and
   prints their sums, which must be:

        34db1cec5486168c993c2cf6352cdce5f44427e4624b8b832ab17b1e2f89aa7f  VarkaNanosSpikeLoopsR1.java
        def05eb116ca50dc4300ee5ae9693a527e9bd0925a98f7e5ef3b4bc61d33af07  VarkaNanosSpikeLoopsR2.java
        2f5b9bbdf8c4c7d733af51b4f3171891d62e2d7e0926d05218c0ac932e442eaf  VarkaNanosSpikeLoopsR3.java
        aa6a13ff7fa724e5a2c85bd466ec9ce50d6b55a46a1a1496c4495c8c00f8824c  VarkaNanosSpikeLoopsR4.java

   `VarkaNanosSpikeLoopsR2` and `R4` have no loop yet and hold an empty list.
4. **The check in CI.** `dev/varka_scoped_suites.sh` is the script behind the step `Run the
   Varka suites of catalyst, <part>` of the job `varka-scoped` ("Varka suites: catalyst ...")
   of `.github/workflows/build_and_test.yml`; a change to it is a Varka change, where a change
   to the workflow would run Spark's whole matrix. After its line

        matrix=${VARKA_MATRIX:-dev/varka_matrix.sh}   # the runner; a test puts a stub here

   add a blank line and these six:

        # The generated loop classes of the nanosecond representations' study are what their
        # generator writes (VARKA-662). A generated class counts nothing toward a pull request's
        # size, so a hand edit of one would otherwise merge unread; the check takes a second.
        if [ "$module" = catalyst ]; then
          python3 dev/varka_nanos_spike_gen.py check any sql/catalyst/src/test/java/org/apache/spark/sql
        fi

   The script runs under `set -euo pipefail`, so a stale class ends the job before a suite
   starts. `check any` passes where the four classes are those of one of the sets, so the line
   does not change in S1 or S2.
5. `build/sbt catalyst/Test/compile`. Where it fails: the repair procedure.

**Tests.** `python3 dev/varka_nanos_spike_gen.py check pilot $TQ` exits 0 and prints
nothing. This part runs no loop: part P3 does, and nothing is timed before it. The failure
only this part's commands catch: Java that the repository's compiler flags or linters refuse.

**Planted faults.** (a) One character of a generated loop edited by hand: `python3
dev/varka_nanos_spike_gen.py check pilot $TQ` prints `stale: VarkaNanosSpikeLoopsR1.java; run
gen` and exits 1, and the line CI runs, `python3 dev/varka_nanos_spike_gen.py check any $TQ`,
prints `stale: the classes are those of no set; run gen` and exits 1. (b) `bash -n
dev/varka_scoped_suites.sh` prints nothing: the script still parses.

**Commands.** Steps 3 and 5; `python3 dev/varka_nanos_spike_gen.py table pilot`, which prints
twelve lines, the first `loopRoundR1HA1Z1	aM,aN	oM,oN`; `python3 dev/varka_nanos_spike_gen.py
count pilot`, which prints `12 loops, 18 cells; with the control 13 and 19`; `ruff check
dev/varka_nanos_spike_gen.py` and `ruff format --check dev/varka_nanos_spike_gen.py`, which
print `All checks passed!` and `1 file already formatted`; `dev/lint-java`, no finding in the
new files; `python3 dev/varka_msg_check.py size`.

**Done when** the compile passes and the report lists every repair made, each as the line
before and after. **Not here:** no test class, no benchmark.

### 5.4 Part P3: the checker and the suite

**B0.** `ls $TQ/VarkaNanosSpikeLoopsR1.java` prints the path; `ls $TQ/VarkaNanosSpikeCheck.java`
fails; and the Scala members that appendix F calls are declared as 5.0's table of calls says,
with `D=sql/catalyst/src/main/scala/org/apache/spark/sql/catalyst/util/DateTimeUtils.scala` and
`S=sql/api/src/main/scala/org/apache/spark/sql/catalyst/util/SparkDateTimeUtils.scala`:

| command | prints |
| :--- | :--- |
| `grep -c -E '^  def (getHours\|getSecondsWithFractionNanos\|getYear\|timestampNanosAddDayTime\|truncTimestampNanos)\(' $D` | `5` |
| `grep -c '^  private\[sql\] val TRUNC_TO_HOUR = 4$' $D` | `1` |
| `grep -c '^  def microsToDays(' $S` | `1` |
| `grep -c -E '^(final \|abstract \|sealed )?(class\|trait) DateTimeUtils\b' $D` | `0` |

Where the lead has catalyst's classes compiled he may read the same of the classes, one JVM
under the lock: `javap -cp sql/catalyst/target/scala-2.13/classes
'org.apache.spark.sql.catalyst.util.DateTimeUtils$' | grep -c 'public int TRUNC_TO_HOUR()'`
prints `1`. The planner did not run it; the third review read those bytes.

**Steps.** `$TQ/VarkaNanosSpikeCheck.java`: appendix F, whole.
`$TQ/VarkaNanosRepresentationSpikeSuite.java`: appendix G, whole.

**Tests** (`VarkaNanosRepresentationSpikeSuite`, catalyst, Java, JUnit 5, `@VarkaSuite(ownJvm
= true)`):

| test | inputs | expected, and its oracle | the failure only it catches |
| :--- | :--- | :--- | :--- |
| "every loop of every arm agrees with Spark's methods" | 4.6's six batches through every loop; their row counts asserted, 6464, 1984, 4096, 1024, 1024, 4096 | for each loop and batch, `requireAgrees`' four assertions of 4.6: the loop declines the batch exactly where the checker's table declines a row; the rows it accepts equal Spark's answers, row by row; each row it declines is declined alone | a loop right over 2025, the benchmark's data, and wrong before the epoch, at a tie, at a carry or at an end; a guard one step too wide or too narrow; a guarded loop never compared at the ends of a long |
| "no loop declines a batch inside the sure range, and one long declines outside it" | the sure batch; the wide batch | every loop returns zero or more over the first; every loop that computes in R2 over Spark's parts returns a negative number over the second | a guard that fires on good data; a loop in R2 that wraps silently. The first test asserts both too, through the table; this one asserts them with no table |
| "each loop is a method of its own, and the species are the layouts'" | the list of arms | distinct methods and labels; `IS` has `L` lanes and half the bits; `HS` has `max(4, L)` lanes; `SHORT_LANES` is true from four long lanes on | two arms timing one compiled method; an int species that is a second species |
| "the benchmark's columns reach every outcome the loops decide" | 4096 rows of the benchmark's data | 4.7's counts | a branch-free loop timed over data that never takes its carry |

Its work: 12 loops (322 after S2) times 18688 rows, each row one call of Spark's method and
one evaluation of the table, and for a batch a loop declines, the accepted rows again and a
call of the loop for each declined row. At 322 loops that is about 6 million calls of Spark's
methods. The prediction is under five minutes in its own JVM, to be read at S2 against the
calibration table (`python3 dev/varka_calibration.py`).

**Planted faults**: 4.6's fourth, fifth and sixth; and one in the checker, a window of
`WINDOWS` one wider (`9_223_372_036_853L` made `9_223_372_036_854L`): the suite's first test
fails with `the loop returned -1 over a batch with 0 rows to decline`, at the first loop that
meets that window over the batch of ends (in S2, `ROUND R1H 3>1 over batch 2, the rows it
accepts`; with the pilot's loops, `ROUND R3M 1>1`). The planner ran the last two under the
stubs.

**Commands.**

    JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 SPARK_LOCAL_IP=127.0.0.1 \
      flock "$L" build/sbt -batch 'catalyst/testOnly *VarkaNanosRepresentationSpikeSuite'
    # Passed: Total 4, Failed 0, Errors 0, Passed 4

**Done when** the four pass and the four faults fail as said. **Not here:** no timing.

### 5.5 Part P4: the pilot

**B0.** The suite's command prints `Passed: Total 4`; `ls $TQ/$C.java` fails.

**Steps.**

1. `$TQ/VarkaNanosSpikeLoopsControl.java`: appendix I, whole.
2. `$TQ/$C.java`: appendix H, whole. It holds `levelInUse`, `codeList`, `childClassPath`,
   `COMPILE` and `summary` as copies of `$TQ/VarkaDecimalRemainderSpikeBenchmark.java`'s
   (5.0's table has their lines), so nothing is copied by the builder.
3. `VarkaNanosSpikeCheck.arms()` gains `VarkaNanosSpikeLoopsControl.arms()` as the last
   argument of its `Stream.of(...)`.
4. The builder stops and hands the lead the branch. **The lead** opens the draft pull
   request `<n>` and takes five readings:

        # the laptop, idle, pinned, once for each of 64, 32 and 16:
        L=${VARKA_HEAVY_LOCK:-$HOME/proj/.varka-locks/heavy.lock}; mkdir -p "$(dirname "$L")"
        JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 SPARK_GENERATE_BENCHMARK_FILES=1 \
          SPARK_LOCAL_IP=127.0.0.1 flock "$L" taskset -c 0,12,13,14,15,1,2,3 \
          build/sbt "project catalyst" 'set Test/javaOptions ++= Seq("-XX:MaxVectorSize=<bytes>",
            "-XX:CompileCommand=dontinline,org.apache.spark.sql.VarkaNanosSpikeLoops*::loop*")' \
          "Test/runMain org.apache.spark.sql.VarkaNanosRepresentationSpikeBenchmark"

        # the runner, at 256 bits:
        opts='-XX:CompileCommand=dontinline,org.apache.spark.sql.VarkaNanosSpikeLoops*::loop*'
        gh workflow run benchmark.yml --repo vecbricks/varka --ref master -f pr=<n> \
          -f create-commit=false -f jdk=25 -f expected-cpu="AMD EPYC 7763" \
          -f class=org.apache.spark.sql.VarkaNanosRepresentationSpikeBenchmark \
          -f extra-java-options="$opts"
        # the runner, at 128 bits: the same, its last line
          -f extra-java-options="-XX:MaxVectorSize=16 $opts"

   The two forms of `extra-java-options` are those of VARKA-577's runs, whose provenance
   files record them. The workflow hands the value to the driver as one argument (fact 18).
   It also expands it unquoted in one line that prints the JVM's flags: the `*` of the compile
   command is safe there while no file of the runner's working directory matches the whole
   word, which begins `-XX:`; none does.

   A laptop run writes `$B/$C-jdk25-results.txt`; the lead renames it to the name of 4.7 for
   its width before the next run, and writes its provenance file with the fields of
   `$B/VarkaDecimalPairSpikeBenchmark-jdk25-laptop-128bit-provenance.txt`, the `run:` line the
   command above. A runner's files come back in the run's artifact as
   `benchmark-results-25-2.13.tar`, which holds `$C-jdk25-results.txt` and
   `$C-jdk25-provenance.txt`; the lead renames `$C-jdk25-` to `$C-jdk25-runner-epyc7763-` and,
   for the second run, to `$C-jdk25-runner-epyc7763-128bit-`. A run that fails its CPU check is
   dispatched again.
5. The builder gets the ten files back with a file `minutes.txt`, a line a run: `<host>
   <bits> <minutes>`, the minutes of the benchmark alone, whole and rounded up. The lead takes
   each so:

   - *A runner's run.* The job's step `Run benchmarks` builds before it runs (`Test/package`),
     so its duration is not the number. The start is the time of the first line of the run's
     log that the harness prints, one holding ` settled after ` or ` did not settle in `
     (appendix H); the end is the step's `completedAt`, since only the provenance file is
     written after the benchmark:

            gh run view <id> --repo vecbricks/varka --log \
              | grep -m1 -E ' settled after | did not settle in ' | grep -o -m1 '20[0-9T:-]*'
            gh run view <id> --repo vecbricks/varka --json jobs --jq \
              '.jobs[].steps[] | select(.name == "Run benchmarks") | .completedAt'

     The start so read leaves out the first loop's warm-up, seconds of a run of minutes. Where
     the log has no such line, the start is the step's `startedAt` and the line of
     `minutes.txt` ends ` upper bound`: the scaled minutes are then too high by the build's
     share, and 5.7 is read with that said.
   - *A laptop run.* The lead first runs `build/sbt catalyst/Test/compile` once on the commit,
     so that the timed line compiles nothing; then `date +%s` before and after the `build/sbt`
     line of step 4, and the difference over 60.

   The planner ran neither command: no run exists, and it had no network. The builder checks
   each results file (below), commits them, and writes **the pilot's report** into section 11.

**The checks of a results file** `W` with its provenance `P`:

| check | command | must print |
| :--- | :--- | :--- |
| the section | `grep -c '^nanosecond timestamp hand loops at [0-9]* bits$' $W` | `1` |
| the width | `grep -o 'hand loops at [0-9]* bits' $W` | the file's width |
| every loop was checked | `grep -c '^intrinsic check, ' $W` | `13` |
| every cell was timed | `grep -c '^[A-Z]* R[1-4][LMHS] [1-4]>[1-4]' $W` | `19`: thirteen loops, and `ROUND` three times more at each of two rungs |
| the control is caught where the conversions fall back | `grep -c '^intrinsic check, HOURC R1L 1>1: [0-9]* Vector API call(s) left in Java' $W` | `1` in both runner files; `0` or `1` in a laptop file, recorded |
| the runner's CPU | `grep -c '^cpu: *AMD EPYC 7763' $P` | `1` for the two runner files |

The control's line is the check of the check. If a runner file reads `HOURC R1L 1>1: every
call intrinsified`, the inlining tree is not being read as the plan assumes: the part stops
there, nothing is committed, and the report says so.

**The pilot's report** (section 11), a table a host and width with these columns, then the go
or the stop:

1. the minutes of the run, and from them the study's scaled minutes: `minutes x 443 / 19`;
2. the loops that settled, `<k>` of 13, and each line `did not settle` in full;
3. each loop's intrinsic check line that is not `every call intrinsified`, in full;
4. each `allocated` line over one byte a row;
5. the twelve loops' and the control's nanoseconds a row at 4096 rows;
6. the control against `HOUR R1L 1>1` of the study, where S3 has it: in the pilot, against
   `HOUR R1H 1>1`, which runs the same division; as a ratio;
7. every repair of parts P2 and P3.

**The go**, restated after the guard's repair. The study goes on, as planned, when:

1. in the runner's 256-bit file every loop but the control reads `every call intrinsified` and
   allocates at most one byte a row, the guarded loops `ADDC R1H 1>1`, `ROUND R3M 1>1` and `ADDC
   R3M 1>1` among them; and
2. in the runner's 128-bit file the loops `ROUND` and `HOUR` of R1H and R3M and `ADDC R1H 1>1`
   read `every call intrinsified`, and `LT R1H 1>1`, `LT R3M 1>1` and `ADDC R3M 1>1`, whose
   masks have two lanes, read calls left in Java whose methods are among `compare`,
   `binaryOp`, `blend`, `convert`, `maskReductionCoerced` and `fromBitsCoerced`.

These are what the planner's own check found on the laptop under `-XX:UseAVX=2` (4.7). A
runner file that reads otherwise is the finding the pilot exists for, and the report names
the loops and the calls. What the lead then does has two cases. Where every loop that reads
otherwise has no guard (`ROUND R1H 1>1`, `HOUR R1H 1>1`, `HOUR R3M 1>1`, an `LT`), S1 goes on:
the rule reads such a loop as no data (4.10), and section 11 lists it. Where one of them is a
guarded loop that the go expects clean (`ADDC R1H 1>1` at either width; `ROUND R3M 1>1` or
`ADDC R3M 1>1` at 256 bits), the guard's form is not vector code on the runner and every
guarded loop of the study would be no data: S1 does not start, and the lead puts the report to
the owner with one question, whether the guard gets another form (a change to appendix A and
to 4.3, so a new writing of this plan) or the study runs with its guarded loops undecided.
What the lead decides from the report, by
section 15: question 4, if the control beats `HOUR R1H 1>1` by 1.3 times or more on a host
where it does not fall back.

**Tests.** The suite, now with thirteen arms: `Passed: Total 4`. The failure only this part's
readings catch: a loop that is right and is not vector code on the runner's CPU.

**Done when** the ten files are on master and section 11 holds the report. **Not here:** no
loop beyond the pilot's; no cost table.

### 5.6 Parts S1 and S2: the study's loops

**B0** (S1). Section 11 holds the pilot's report and its line `go`; `python3
dev/varka_nanos_spike_gen.py check pilot $TQ` exits 0 (or, where P2 repaired the generator,
the report's sums hold).

**Steps** (S1). `python3 dev/varka_nanos_spike_gen.py gen r12 $TQ`; `build/sbt
catalyst/Test/compile`; the repair procedure where it fails; the suite. **Steps** (S2): the
same with `gen study`. The sums, where the generator is as this plan has it. S1, the set
`r12`:

        b6968ad7d33ae2665e40105dca65b74a999a7d7bb406ea905ee06a35e1cbc389  VarkaNanosSpikeLoopsR1.java
        76f7bf56e435a8d4f7a579ab1a67c3eb21fbf7e6c95edeb5983a201413bfb60e  VarkaNanosSpikeLoopsR2.java
        2f5b9bbdf8c4c7d733af51b4f3171891d62e2d7e0926d05218c0ac932e442eaf  VarkaNanosSpikeLoopsR3.java
        aa6a13ff7fa724e5a2c85bd466ec9ce50d6b55a46a1a1496c4495c8c00f8824c  VarkaNanosSpikeLoopsR4.java

S2, the set `study`:

        b6968ad7d33ae2665e40105dca65b74a999a7d7bb406ea905ee06a35e1cbc389  VarkaNanosSpikeLoopsR1.java
        76f7bf56e435a8d4f7a579ab1a67c3eb21fbf7e6c95edeb5983a201413bfb60e  VarkaNanosSpikeLoopsR2.java
        0333f4a5b59900fbfc5b39d4a8809ada97daec2ad58da3518ec7f6a63dbbe579  VarkaNanosSpikeLoopsR3.java
        5beec3773959f8e9f51963a2aa6c7b8ada20196a300af18637b721958a1145aa  VarkaNanosSpikeLoopsR4.java

**Tests.** The suite's four tests, unchanged: its first two now run 128 loops (129 arms with
the control) in S1 and 322 in S2. In S1 the builder also runs the suite once under each of
`-XX:MaxVectorSize=32` and `-XX:MaxVectorSize=16` (the suite's own JVM takes
`JDK_JAVA_OPTIONS`), since the species follow the width and layout H crosses its masks
another way at two long lanes. In S2 the builder runs it at the three widths again, and
plants 4.6's first fault.

**Commands.** The suite, as 5.4 prints it, and under a width with the variable before `flock`:

    JDK_JAVA_OPTIONS=-XX:MaxVectorSize=16 JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 \
      SPARK_LOCAL_IP=127.0.0.1 flock "$L" build/sbt -batch \
      'catalyst/testOnly *VarkaNanosRepresentationSpikeSuite'

Each run: `Passed: Total 4, Failed 0, Errors 0, Passed 4`. `python3 dev/varka_nanos_spike_gen.py
table study | wc -l`: `322` (S2; `128` in S1 with `r12`). `python3 dev/varka_nanos_spike_gen.py
check any $TQ`: exit 0. `python3 dev/varka_msg_check.py size`: the four generated classes count
nothing; a repair of the generator counts its lines.

**Done when** the suite passes at the three widths with the full list. **Not here:** no
timing; no change to a formula.

### 5.7 Part S3: the readings

The lead's part. A builder checks the files and commits them.

**How many runs a host and width takes.** From the pilot's report, the study's scaled minutes
`m` for that host and width (5.5):

| `m` | runs | each run's `-Dvarka.nanos.ops` | loops and cells of each, with the control (`gen`'s `count study <ops>`) |
| :--- | ---: | :--- | :--- |
| at most 240 | 1 | none | 323 and 443 |
| over 240, at most 480 | 2 | `LT,PICK,ADD,ADDC,CHAIN,ZONE`; `HOUR,SEC,YEAR,TRUNC,MICROS,HASH` | 205 and 325; 185 and 245 |
| over 480 | 4 | `LT,PICK,CHAIN`; `ADD,ADDC,ZONE`; `HOUR,SEC,YEAR`; `TRUNC,MICROS,HASH` | 147 and 267; 125 and 185; 131 and 191; 121 and 181 |

Every run times `ROUND` and the control beside its operations, so a part is whole (4.7), and
no class of the rule is split. GitHub ends a job at 360 minutes; 240 leaves room for a slow
machine.

1. **The laptop**, in an idle window, the command of 5.5 at 64, 32 and 16 bytes. For a run in
   parts, one more string in its `Seq(...)`: `"-Dvarka.nanos.ops=<ops>"`.
2. **The runner**, the two dispatches of 5.5, with `-f pr=<n>` of this part's draft pull
   request. For a run in parts the option goes first in the value, which has no space and no
   quote of its own:

        -f extra-java-options="-Dvarka.nanos.ops=LT,PICK,ADD,ADDC,CHAIN,ZONE $opts"
        -f extra-java-options="-Dvarka.nanos.ops=HOUR,SEC,YEAR,TRUNC,MICROS,HASH -XX:MaxVectorSize=16 $opts"

   (the first at 256 bits, the second at 128; the other lists the same way).
3. **Joining a run's parts.** Each part writes `$C-jdk25-results.txt` with one section, titled
   `nanosecond timestamp hand loops at <bits> bits: <ops with spaces>`. The lead saves the
   first part's two files under the name of 4.7 and each later part's as `part-<k>-results.txt`
   and `part-<k>-provenance.txt` outside the tree, then, for each later part:

        python3 dev/varka_bench_sections.py splice $B/$C-jdk25-runner-epyc7763-results.txt \
          part-2-results.txt \
          --sections "nanosecond timestamp hand loops at 256 bits: HOUR SEC YEAR TRUNC MICROS HASH" \
          --out $B/$C-jdk25-runner-epyc7763-results.txt

   `splice` appends a section the first file lacks (fact 20). The provenance file is the first
   part's, with one line added for each later part, `spliced: <the section's title> from <its
   run: line>, <its cpu: line>, <its date: line>`, the form the workflow keeps below a
   provenance file's header. Every part of a runner file must have the CPU check passed
   against the EPYC 7763.
4. **AVX-512 from the pool**: the first dispatch of 5.5 three times more with no
   `expected-cpu`. A run whose results file says `hand loops at 512 bits` ran on AVX-512; its
   two files are kept as `$C-jdk25-runner-avx512-results.txt` and `-provenance.txt`. With
   none in three, section 11 gets the sentence of 4.7.

**The checks of a results file**: 5.5's width, which prints once a section; its control,
which prints `1` a part in a runner file; its CPU; and

| check | command | must print |
| :--- | :--- | :--- |
| the sections | `grep -c '^nanosecond timestamp hand loops at [0-9]* bits' $W` | `1`, `2` or `4`: the run's parts |
| every loop was checked | `grep -c '^intrinsic check, ' $W` | the sum of the parts' loops with the control: `323` for one run; `390` for two; `524` for four |
| every cell was timed | `grep -c '^[A-Z]* R[1-4][LMHS] [1-4]>[1-4]' $W` | the sum of the parts' cells with the control: `443`; `570`; `824` |
| the fallbacks are the expected ones | `grep -c '^intrinsic check, .*left in Java' $W` | at 512 and 256 bits, the control's line alone: once a part in a runner file of the EPYC 7763, and none or once a part elsewhere; at 128 bits `81` more than that, 4.7's loops, each in one part |

A file that fails one of the first three is not committed; the lead runs it again. A file
whose fallbacks are not the expected ones is committed as it is, and section 11 lists every
loop that differs, in full: the rule reads such a loop as no data, and says so.

**Done when** the laptop's three files and the runner's two are on master with their
provenance, and the AVX-512 file or its sentence. **Not here:** no reading of the numbers:
part T2b.

### 5.8 Part T1: the proof files

**B0.** `python3 dev/varka_nanos_spike.py | tail -3 | head -1` prints `forms: R1 R2 R3 R4`; `ls
sql/varka/proofs/nanos_representations.smt2` fails.

**Steps.** (1) `dev/varka_nanos_spike_proofs.py`: appendix J, whole; `chmod +x`. (2) `python3
dev/varka_nanos_spike_proofs.py write sql/varka/proofs`, which prints
`nanos_divisions.smt2: 47 checks`, `nanos_guards.smt2: 30 checks` and
`nanos_representations.smt2: 34 checks`; the three files' sums are

        be27bffa32c568d7dffadf3a56d6d2bff82e3de1870735a884fe8b9c00f5c850  nanos_divisions.smt2
        86335bad21ef1bc472e0aeddf14d4766a137a12c1ebbc17c92aa6233b8e5d279  nanos_guards.smt2
        93e361caab37ea3929744627757127f5e25cc5afe2fcf37a60ac2d0bc5e4f0b5  nanos_representations.smt2

(3) `sql/varka/proofs/README.md`: three rows after the last row of its table of proofs, the
third column `dev/varka_nanos_spike_proofs.py`: `nanos_representations.smt2`, "each
conversion between the four representations of a nanosecond timestamp (VARKA-662) exact under
its range condition and wrapping one step outside it; the orders of the two-word forms";
`nanos_guards.smt2`, "each guard of the representations' study loops, a window, a sum or a
floor, with its sign set exactly where the value is outside its range"; `nanos_divisions.smt2`,
"each division constant of the representations' study loops exact over its whole dividend
range; run under Z3 (cvc5 does not finish it)".

**Tests.** The solvers. The failure only they catch: a range one step too wide, which every
case of the script that is not exactly at the end passes; a guard form used outside the
windows it is exact for.

**Planted faults**: in `dev/varka_nanos_spike.py`, `R2_M`'s upper end one more
(`9223372036854775`): the write changes `nanos_representations.smt2`, and `dev/varka_prove.sh`
fails its first check with `sat` for `unsat`. `find_mul_shift`'s `m * vmax < (1 << 64)` made
`< (1 << 65)`: `python3 dev/varka_nanos_spike.py` fails at a division, and
`nanos_divisions.smt2` fails its check `division of 0 to 86399999999999 by 3600000000000: the
lane's product is under 2^64` with `sat` for `unsat`. In `dev/varka_nanos_spike_proofs.py`, the
second check of `guards`, `"(= lo jlong.MIN)"` made `"(= lo 0)"`: the window is then under 2^63
wide, and `nanos_guards.smt2`'s second check answers `unsat` for `sat`. The planner ran all
three.

**Commands.**

    dev/varka_prove.sh --solver both nanos_representations.smt2 nanos_guards.smt2   # as expected
    dev/varka_prove.sh nanos_divisions.smt2                        # under Z3: every check as expected
    python3 dev/varka_nanos_spike_proofs.py check sql/varka/proofs # exit 0
    ruff check dev/varka_nanos_spike_proofs.py                     # All checks passed!
    ruff format --check dev/varka_nanos_spike_proofs.py            # 1 file already formatted

`dev/varka_prove.sh` takes Z3 and cvc5 from `$VARKA_Z3` and `$VARKA_CVC5`, else from
`target/varka-solvers/`, else from `~/.cache/varka-solvers/`, else from `PATH`. Where it says
that a solver `is not installed`, the builder stops and reports: installing one
(`dev/varka_prove.sh --install both`) fetches from the network and is the lead's.

`VarkaProofFilesSuite` holds only the files `VarkaProofFiles` renders, so it does not read
these three, as it does not read `java.smt2`.

**Done when** the five commands pass. **Not here:** no IR node; no change to
`VarkaProofFiles`.

### 5.9 Parts T2a and T2b: the cost tool, then the cost table and the rule

**B0** (T2a). `ls dev/varka_nanos_costs.py` fails. **B0** (T2b). `ls
$B/$C-jdk25-runner-epyc7763-results.txt` prints the path; `python3 dev/varka_nanos_costs.py
selftest` prints `selftest: 103 checks, 0 failed`.

**Steps.** Step 1 is part T2a, whose tests, planted faults and commands are the self-test's
below. Steps 2 to 6 are part T2b.

1. `dev/varka_nanos_costs.py`: appendix D, whole; `chmod +x`.
2. `mkdir -p $M9/results/VARKA-662`; `python3 dev/varka_nanos_costs.py table $B/$C-*-results.txt
   > $M9/results/VARKA-662/nanos_representation_costs.tsv`.
3. `python3 dev/varka_nanos_costs.py rule $M9/results/VARKA-662/nanos_representation_costs.tsv`:
   its output, whole, goes into section 11 as an indented block. Where a class's `project` line is
   `undecided`, section 11 names the reading that lacked a time and why.
4. Section 11 also gets: the `residual` rows of the EPYC 7763 at 256 bits, counted by flag
   (`awk -F'\t' '$1=="residual" && $2 ~ /EPYC 7763/ && $3==256 {n[$15]++} END {for (f in n)
   print f, n[f]}'` over the table), and every such row whose flag is `off`, in full; the count
   of derived rows flagged `within noise` and `not additive`, by host and width (`awk -F'\t'
   '$15=="within noise" || $15=="not additive" {n[$2 ", " $3 " bits, " $15]++} END {for (k in n)
   print k ": " n[k]}'` over the table; no line means none); the answers
   the owner gives to section 15; and the sentence `Row 699 builds R1, R3 and R4 in the
   order <the rule's order line>, and R2 for <the rule's r2 line>`.
5. `docs`: none. `SKILLS.md`, at the repository's root, is written from the files of
   `sql/varka/skills/`. Of those, `sql/varka/skills/vector-api-and-width.md`
   gains a section of what the study found about a mixed-width loop, the guards' forms and
   the divisions, written from section 11; then `dev/varka_toc.py SKILLS.md --from
   sql/varka/skills`.
6. The status edit of 5.0: `**Started**` to `**Done**`.

**Tests** (T2a). `python3 dev/varka_nanos_costs.py selftest`: `selftest: 103 checks, 0 failed`. It
parses a made-up results file of one section, two rungs and a fallback table (the first rung alone
is read; a fast loop's time is taken from its rate; a labelled fallback and a loop over a byte a
row are both `fallback`; the base has a row of its own; a cell is its loop less half its base; an
edge less both halves; an edge inside the spread is `within noise` and `0.000`, never negative; a
cost negative beyond the spread is `not additive`; a residual has its ratio and its flag; the
table reads back) and applies the rule to 4.10's twenty-nine tables. The failure only it catches:
a rule that reads a fallback's time, counts a missing time as a win, names a default from a part
of the data, calls a class with no row decided, drops a vector layout, makes R2 a candidate where
it has no loop or leaves it out where it has, prints an R2 default without its range, passes over
a scalar loop that is the faster by the band, or reads a runner before an AVX-512 reading.

**Planted faults** (T2a), each run by the planner on the file as the appendix has it, in memory,
the self-test's failing checks counted:

| the line | made | the self-test fails in |
| :--- | :--- | :--- |
| `BAND = 1.3` | `BAND = 1.01` | 12 checks, at tables 2, 4, 5, 7, 8, B2 and S3 and at the residual's flag. (At `1.0` no form is within the band of itself, and the tool stops on an empty tie) |
| `if r["jit"] == "ok":` in `class_times` | `if True:` | 21, at tables 6, C, C2, C3, H, N, P2, R and S: a fallback's time is read |
| `for layout in "LMHS":` in `class_times` | `"LMS"` | 3, at tables G and H: the rule reads no layout H |
| the same line | `"LHS"` | 1, at table M: the rule reads no layout M |
| `if not part["seen"].intersection(forms):` in `judge` | `if False:` | 1, at table Z: a class with no row is not said to have none |
| `if missing:` in `judge` | `if False:` | 9, at tables 7, N, P2 and R: a default is named from a part of the data |
| `(("R2",) if name in R2_CLASSES else ())` in `judge` | `(("R2",) if True else ())` | 14, at tables 1, 2, 5, 7, C2, F, N and Q: R2 is a candidate in every class |
| the same line | `(("R2",) if False else ())` | 11, at tables 1, 4, 7, 8, R and S: R2 is a candidate in none |
| `if vector and (not scalar or vector[0] / scalar[0] < BAND):` in `pick` | `if vector:` | 3, at table S2: a slow vector loop keeps the scalar loop out |
| `return scalar` in `pick` | `return None` | 16, at tables 6, C, C2, C3, S and S2: no scalar body at all |
| `ORDER = ("R1", "R3", "R4", "R2")` | `ORDER = ("R2", "R1", "R3", "R4")` | 5, at tables 4, 8 and E: a tie goes to R2 |
| `if form == "R2":` in `judge` | `if False:` | 2, at tables 8 and S: an R2 default without its range and its fallback |
| `key=lambda k: not k[0].startswith(LAPTOP))` in `rule` | `key=lambda k: k[0].startswith(LAPTOP))` | 3, at table P1: the laptop's 512 bits are not first |
| `(k for k in keys if k[1] == "512")` in `rule` | `(k for k in keys if k[1] == "0")` | 5, at tables P1 and P2: the runner is read before AVX-512 |

**Commands** (T2a). The self-test; `ruff check dev/varka_nanos_costs.py` and `ruff format
--check dev/varka_nanos_costs.py`, which print `All checks passed!` and `1 file already
formatted`; `python3 dev/varka_msg_check.py size`. **Commands** (T2b). `python3
dev/varka_nanos_costs.py check $B/$C-*-results.txt`, exit 0; `python3 dev/varka_quote_check.py
$M9/VARKA-662.md`, 0 orphans: every number section 11 quotes is in the table or a results file.

**Done when** (T2a) the self-test passes and the fourteen faults fail it; (T2b) section 11 holds
the rule's output and the sentence that names what row 699 builds, or the question that keeps it
open. **Not here:** no kernel; row 699's plan is another pull request.
## 6. Tests, proofs, fuzzing and coverage

| check | module, language | what it holds | the failure only it catches |
| :--- | :--- | :--- | :--- |
| `python3 dev/varka_nanos_spike.py` | `dev`, Python | every loop's formula on numbers against exact integers; the divisions as the lanes compute them against exact division; each guard's lanes against its exact test; each loop declining exactly where the guards' table says, and never inside the sure range | a wrong formula, before any Java |
| `VarkaNanosRepresentationSpikeSuite` | catalyst, Java, JUnit 5, own JVM | every loop of every arm against Spark's methods over six batches; a declined batch split into the rows the checker's table accepts, which are compared, and the rows it declines, each of which must be declined alone; the species; the benchmark's data | a number in the study's table that belongs to a wrong loop; a guard one step too wide or too narrow |
| `dev/varka_scoped_suites.sh`'s line, in the `varka-scoped` job of every Build | `dev`, shell | the committed loop classes are what the generator writes | a generated class edited by hand, which counts nothing toward a pull request's size |
| the benchmark's own checks | catalyst, Java | each loop against Spark before it is timed and again in compiled code; the intrinsic check; the control | a loop that is right and is not vector code |
| `nanos_representations.smt2`, `nanos_guards.smt2`, `nanos_divisions.smt2` | `sql/varka/proofs`, Z3 and cvc5 | each conversion's equality at the exact ends of its range; each guard form exact, each guard at its ends; each division constant over its whole range, and each lane product under 2^64 | a range one step too wide; a guard form used where it is not exact |
| `python3 dev/varka_nanos_costs.py selftest` | `dev`, Python | the results parser, the floor and the flags of the derived rows, and the rule on twenty-nine made-up tables | a rule that reads a fallback's time or counts a missing one as a win; an R2 default with no range beside it; a runner read before AVX-512; a negative cost in the table |

**The edges, and where each has a case.** Nulls: the study's loops read no validity (4.3).
The ends of a long and of every guard: the suite's `ends` batch, whose rows a guard declines
are run alone and whose other rows are compared; the script's `GUARD_ENDS`; the proof files.
Negative epochs, where floor and truncation differ: the `sure` and `wide` batches, and
`nanos_representations.smt2`'s last check. Second words 0 and 999: every pair batch. Carries:
the carry rows. A batch with no live row: none; a loop has no validity. Two long lanes: the
suite under `-XX:MaxVectorSize=16` (S1, S2); the readings at 128 bits. AVX2 against AVX-512:
the runner's files against the laptop's; the control.

**Fuzzing.** None: this row adds no IR record and no option, and no fuzzer draws a benchmark.

**Coverage.** None: this row fuses no entry, so it adds no coverage row and no census claim.

**What runs where.** The builder runs the suites of its sheet. The option matrix and the
width audit run in CI only, and this row changes neither's inputs. The check of the generated
classes runs in CI in every Build's catalyst jobs, and by hand in P2, S1 and S2. The timed
runs are the lead's.

**Mutation and JaCoCo.** Neither is asked beyond the planted faults: this row adds no
production class.

## 7. Measurement

The readings, their hosts, widths, files and counts are 4.7's; the checks of a file are
5.5's and 5.7's. No time is quoted here: none exists.

**Predictions.** The first eleven were registered on 28 November 2026 with the second writing,
before any run. Each stands as it was registered. Where this writing's JVM runs or its changed
rule have already answered or changed one, a dated correction follows it; the correction is
what section 11 scores, and the registered text is scored too, as it reads.

1. The pilot, the runner at 256 bits: every loop but the control reads `every call
   intrinsified` and allocates at most one byte a row. Yes or no. (The runner's file.)
   *Correction, 28 November 2026, third writing:* as registered, this would have been no: the
   pilot's `ROUND R3M 1>1` carried a guard whose reduction AVX2 does not have, and the larger
   loops' shared steps were left as calls. Both are repaired (4.3, 4.4), the pilot now has
   twelve loops, and the planner's check under `-XX:UseAVX=2` found every one of them clean at
   256 bits. The prediction is restated for the twelve: yes.
2. The control reads calls left in Java in both runner files, and `every call intrinsified`
   in the laptop's 512-bit file. Yes or no.
3. At 128 bits on both hosts `LT R1L 1>1` and `LT R3M 1>1` read calls left in Java, and the
   `ROUND` and `HOUR` loops of the pilot do not. Yes or no.
   *Correction, the same day:* the pilot has no `LT R1L 1>1` now; its R1 arm is R1H. Restated:
   at 128 bits on both hosts `LT R1H 1>1`, `LT R3M 1>1` and `ADDC R3M 1>1` read calls left in
   Java, and `ROUND` and `HOUR` of R1H and R3M and `ADDC R1H 1>1` do not. The second review
   read the first probe as saying that `HOUR` and `ROUND R3M` fall back at 128 bits, through
   the two-lane int vector's conversions; the planner's check of the loops as they now are
   found them clean there (4.7). Yes.
4. The study's run takes under 120 minutes at each width on the runner. (`minutes.txt`.)
5. The rule on the EPYC 7763 at 256 bits builds R1 alone: over Spark's parts no other form
   wins a class, since each pays its conversion in every loop.
   *Correction, the same day:* the rule no longer ends in R1 alone (4.10, step 4). Restated:
   over Spark's parts no form wins a class over R1 beyond the band, every default is R1, and
   the second form is chosen by the band and the mean: R3.
   *Correction, 28 November 2026, after the owner's answers:* the rule builds no set now: R1, R3
   and R4 are all built, and R2 is a candidate in four classes (4.10, section 19). Restated: on
   the EPYC 7763 at 256 bits R1 is the default of every class, as a win or as the first of a tie,
   and the rule's `r2` line does not name that host.
6. The cells say more than the loops over Spark's parts do: R4's `HOUR` cell is at most half
   of R1's, and R3's `HOUR` cell is within the band of R1's. Yes or no, each. (The cost table,
   the EPYC 7763 at 256 bits, layout L.)
7. Arriving as epoch nanoseconds, R2 wins the compare: the rule's `arriving compare` line
   ends `it wins`. Yes or no.
8. Layouts M and H are not faster than L beyond the band for R1 in any class at 256 bits on
   the runner. Yes or no.
9. The sum of an edge in, a cell and an edge out is within 1.3 times the measured loop for
   every loop of R3 and R4 over Spark's parts at 256 bits on the runner. Yes or no, with the
   count that are not.
   *Correction, the same day:* the sum is 4.8's `predicted`, with the base in it, and the
   count is of the `residual` rows flagged `off`.
10. `VarkaNanosRepresentationSpikeSuite` runs in under two minutes with 315 loops.
    *Correction, the same day:* 322 loops, and the suite now runs a declined batch's accepted
    rows again and each declined row alone. Restated: under five minutes.
11. The parts merge in 12 pull requests and this plan, with at most one part cut in two.
    *Correction, the same day:* the plan now has eleven parts. Restated: eleven pull requests and
    this plan, with at most one part cut in two.

Registered with this writing, on 28 November 2026, before any timed run:

12. In the runner's 128-bit file of the study exactly 81 loops beside the control read calls
    left in Java, the 81 of 4.7. Yes or no, with the loops that differ.
13. No derived row of the EPYC 7763 at 256 bits is flagged `not additive`. Yes or no, with
    the count.
14. No dispatch of S3 is taken in parts: the scaled minutes are at most 240 at both widths.

## 8. Who builds it

Section 5's table has the builder of each part and its reason. In one line: the files taken
whole from this plan with a one-command oracle go to a cheaper model (P1a, P1b, S2, T1, T2a); the
first compile under the repository's build, the first run against Spark and the first run at
three widths go to full strength (P2, P3, P4, S1); the dispatches, the readings and the rule
go to the lead with a builder (S3, T2b).

## 9. Risks

1. **The Java does not compile under the repository's build.** The generated loops compiled
   under the JDK's `javac` and ran; the hand-written classes compiled against stubs. What is
   left is the repository's compiler flags, its linters, and a member of Spark that a stub has
   wrong. Held by the repair procedure, and by the split of P2 from P3: the first compile
   touches no test.
2. **A loop is right and is not vector code.** Held by the intrinsic check, the allocation
   line, the control that proves the check, the rule's refusal to read a fallback, and the
   planner's own run of the check over every loop (4.7).
3. **The runner's JIT differs from the laptop's under a flag.** `-XX:UseAVX=2` on an AVX-512
   CPU is not a Zen 3. Held by the pilot's go, which names the loops and the lines expected at
   each width, and by the rule: a loop that falls back on the runner alone is no data, never a
   win.
4. **C2 compiles a loop a second time and reads otherwise.** The planner saw it: one loop was
   compiled again after a trap, and its inlining tree then held the Vector API's cold lookup
   of a fallback's implementation as a call. The check passes that lookup over (`COLD_LOOKUP`,
   appendix H), and the allocation line is the second witness. Another such line of another
   kind would mark a clean loop `[Java fallback]`: the rule then has no data for it and says
   so, and section 11 lists it.
5. **The study prices R4's and R2's clock fields too high.** A 47-bit dividend takes two
   multiplies and a repair here (4.2). If a cheaper exact form exists, R4's cells move. Held
   by naming it: section 11 reports R4's `HOUR` cell beside the count of its lane operations.
   R4 is built whatever its cell reads (the owner's answer 6), so a cheaper form would move a
   default and not what is built.
6. **The study's divisions are not a kernel's.** The house's long lane divides through
   doubles where the host lowers the conversion. Held by the control, and by question 4.
7. **Costs do not add.** Then cells and edges mispredict a tree. Held by the residual rows,
   by the flags `within noise` and `not additive`, and by 4.8's list of what a model may not
   conclude.
8. **A run passes the runner's time limit.** Held by the pilot's scaled minutes and 5.7's
   runs in parts, each whole.
9. **A run in parts is read across machines.** Every ratio of the rule's steps 1 to 5 and every
   subtraction of the cost table stays inside one part, since no class is split (4.7). The third
   writing had one number that crossed parts, the geometric mean that chose between R3 and R4; it
   is gone with the built set. What crosses parts now is the `order` line's counts of classes,
   which are verdicts and not times.
10. **No dispatch lands on AVX-512.** Then the project's first reading is the laptop's 512-bit
    block alone, double-pumped, and section 11 says so.
11. **The suite's own JVM picks other species under a width flag** than the timed run. It
    cannot: both read `LongVector.SPECIES_PREFERRED` under the same `MaxVectorSize`.
12. **Spark's surface moves** (the preview changes weekly). A method of 4.6 that is renamed
    fails the suite's compile at the sync that moves it, and the sync's pull request follows
    it.
13. **A hand edit of a generated class.** Held by the line in `dev/varka_scoped_suites.sh`,
    which every Build's catalyst jobs run.
14. **An R2 default rests on batches inside its range.** The rule's time for R2 is the price of a
    batch inside 1677 to 2262; a batch outside costs the loop and the row path, and the study
    times neither that nor how often it happens. Held by the rule's line, which names the range
    and the fallback beside every R2 default and the whole-range default of the same class; by
    4.12's item, which lists what is not known; and by row 699's measurement on row 655's classes.
15. **A scalar body is slower inside a kernel than the study's scalar loop.** The study's scalar
    loops carry no validity, no tail and no null-aware guard. Held by 4.12's item, which says so,
    and by row 698's verdict being read first.
16. **The project's first reading is double-pumped.** The laptop's datapath is 256 bits wide
    (4.7), so its 512-bit block may name defaults that a full-width AVX-512 CPU would not. Held by
    the order's second reading, an AVX-512 runner where a dispatch lands on one, whose block is
    printed beside the laptop's; and by each host keeping its own defaults, so that only the
    project's named default follows the laptop.

## 10. The house rules that bind this row

From `sql/varka/AGENTS.md`:

- "Any performance claim ... traces to a committed benchmark result file": the plan quotes no
  time before the files exist, and section 11 quotes only what `dev/varka_quote_check.py`
  finds in them.
- The results are "five iterations over two-second windows, generated on an otherwise idle
  machine": the laptop's runs are the lead's, in an idle window; a builder never times.
- "The Varka code carries no `TODO` or `FIXME` comments", and "A comment describes the code
  as it is."
- "A pull request stays under 1000 changed lines".

Do not: open `/home/max/proj/runtime-master`; use any measurement from outside Varka; open
OpenJDK's `java.time` sources; push, dispatch, time or open a pull request as a builder; edit
a generated class or a results file by hand; change a formula to make a test pass; kill a
process by pattern; use `git stash`.

## 11. Outcome

To be written as the parts merge. Part P4 writes **the pilot's report** (5.5) and its line
`go` or `stop: <the loops and the calls>`. Part S3 lists the files, the loops whose intrinsic
check differs from 4.7's table, and the AVX-512 sentence. Part T2b writes the rule's output,
the residuals, the flagged rows' counts, the predictions of section 7 scored, the owner's
answers, and the sentence `Row 699 builds ...`.

## 12. Seams with the plans beside this one

**Row 698**, "A two-word load and store with no mask at two long lanes, as a spike"
(`m9/PLAN.md` section 3; filed on 28 November 2026, after the second writing took its number).
It asks whether a two-word value can win over the row path at 128 bits on x86 at all, for the
decimal pair and for this row's two-word timestamps, and it needs "rows 662 and 689" first.
Where the two rows meet:

- *What row 698 reads of this row.* The two 128-bit results files,
  `VarkaNanosRepresentationSpikeBenchmark-jdk25-laptop-128bit-results.txt` and
  `-runner-epyc7763-128bit-results.txt`: their `intrinsic check` lines, which name the 81
  loops whose masks fall back at two lanes and the calls left in Java in each; the times of
  the 133 vector loops with no mask there, among them the loads and stores of each two-word
  form with nothing else (`ROUND R1H 1>1`, `ROUND R1L 1>1`, `ROUND R1M 1>1`, `ROUND R3L 3>3`,
  `ROUND R3M 3>3`, `ROUND R4L 4>4`, `ROUND R4M 4>4`); and the scalar loops' times beside them.
  And the loops' text, `VarkaNanosSpikeLoopsR1.java`, `R3.java` and `R4.java`: a load of this
  study is a `fromMemorySegment` of each word and, for a narrow word, one `convertShape`; none
  is a two-vector `rearrange`, a `selectFrom` or a `compress`, the calls row 698's admission
  check names.
- *What this row leaves to row 698.* The question of a load, a compare, a choice and a carry
  with no mask at two long lanes. This row's loads at 128 bits are already mask-free and are
  lowered (4.3). Its compares, choices and carries use masks, as a kernel does at four lanes
  and more, and at 128 bits they are timed as the fallbacks they are, in the fallback tables,
  and never read. This row tries no mask-free form of them: no lane read and lane insert, no
  compare as a subtract and a shift. So this row's 128-bit files say what the masked forms
  cost and which loops need another form; whether another form wins is row 698's verdict.
- *How the two cannot contradict.* The rule's lines at 128 bits name a scalar body as the default
  of every class whose vector loops have a mask (`<form>S, a scalar body`), or are `undecided`.
  They are labelled as this study's loops, with the masks of four lanes and more, and are not a
  verdict on a two-lane kernel. Row 699's item "a kernel whose body is a scalar loop" (4.12) reads
  row 698's answer first: a mask-free two-lane vector form that wins there replaces the scalar
  body.

**Row 689** (zone steps at two lanes; its plan is written beside this one and was not read).
It gives each zone step a form with no mask at two lanes. This row's `ZONE` loop is three
offsets as masked adds, and at 128 bits it is one of the 81 fallbacks. This row adds no emit
option and no refusal text, so it shares no name with row 689.

**Row 661** (the two type ids): this row touches nothing of it. What row 661 hands to "row
662" (the ids, the declined entries, the census cells that name a row) goes to row 699:
section 13's edit 6 says so in its plan. **Row 656** fuses `in_set` over `TIMESTAMP` and
rewords the two-lane text; this row touches neither. **Row 655**: this row reads none of its
classes; row 699 does (edit 7). **Row 679** (the library): its contract is Spark's two parts
with one long as an arm, which stands; edit 8 tells it what the study adds. **Rows 673 to
675** wait for rows 662 and 699 (edit 3).

**Deviations from the shared names of the zone family:** none; `ZONE` reads no name of it.

## 13. Edits to other plans, made by this plan's own pull request

Each old text was matched exactly once on master `92fd5a62d13` with `grep -c -F`, but for the
two edits made by `sed`, whose counts are given. An old text printed here in an indented block
is the file's line without the block's four spaces. `P=sql/varka/plans`.

**Edit 1. `$P/m9/PLAN.md`, section 3, row 662's line.** Three changes in the one line.

Old:

    **Filed** (25 November 2026; the admission check: the cache stores the value

New:

    **Planned** (`VARKA-662.md`, 28 November 2026: a pilot and a study of four representations in hand loops, their cost table, their conversions and guards proved, and a rule, in eleven parts; the cache's struct and the kernels are row 699; filed 25 November 2026; the admission check: the cache stores the value

Old:

    **Done when** a nanosecond column is read from the cache's struct and written back; both layouts are built and measured on row 655's classes, the slower kept as an option arm; the zone-free entries fuse; a field over the microsecond word, a cast to microseconds and `second` with its fraction fuse; and the values at the range's ends answer as `TimestampNanosVal` does

New:

    **Done when**, by the owner's word of 28 November 2026 that the representation be measured for vectors before it is built: the candidate representations (Spark's two parts, one long of nanoseconds, seconds with nanoseconds, days with nanoseconds) are measured in hand loops, each held to Spark's methods; the numbers are a cost table, each conversion an equality with its range, proved; and a rule names each class's default representation by host and width, among those row 699 builds

Old:

    | owner's decision 1; rows 655 and 661 first, and row 359's two words | large |

New:

    | owner's decision 1; no row first | large |

**Edit 2. The same file, section 3: a new row, 699**, added after the table's last row, the
line that begins `| 698 |`. Before it, `grep -c '^| 699 ' $P/m9/PLAN.md $P/m8/PLAN.md` prints
two zeros: on master `92fd5a62d13` the table of `m9/PLAN.md` ends at 698 and that of
`m8/PLAN.md` at 652; the open pull request of row 697 (#1798) adds 697 to `m8/PLAN.md`; and no
open pull request adds a row 699. Read again on master `4e1d1f3a749` (28 November 2026, 05:34):
the same two zeros, the table of `m9/PLAN.md` still ends at 698, and every old text of this
section is there once; the plan pull requests then open add rows 700 to 708 to `m8/PLAN.md`
and none adds a 699. If one of the two counts is not zero when this plan's pull
request is made, the lead takes the next free number
and changes every `699` of this plan with it, by `sed -i 's/\b699\b/<n>/g'
sql/varka/plans/m9/VARKA-662.md`, run before any edit of this section is made: the new row's
text and the `sed` commands of edits 3, 6 and 7 are in this file, so they are renumbered with
it. The number is in prose and in section 13's new texts only, and in no file of an appendix.

    | 699 | The nanosecond timestamps' storage and kernels over the study's forms. **Filed** (28 November 2026, from `VARKA-662.md` 4.11 and 4.12; the admission check: row 662 measures the representations in hand loops and builds nothing a kernel runs; *28 November 2026, the owner's answers to `VARKA-662.md` section 15:* all three whole-range representations are built whatever the numbers, one long of nanoseconds where it is the fastest, a scalar body where no vector form wins, and a column that arrives as epoch nanoseconds is admitted) The cache's struct in and out, the null tests and `coalesce`, the two conformance suites and the first census claims; the conversions among Spark's two parts, seconds with nanoseconds and days with nanoseconds as IR nodes with their range conditions and guards; the comparisons, `IN`, `between`, the choices, the hashes, the cast to microseconds, `hour` and `second` with its fraction in all three, each class defaulting by host and width to the form row 662's rule names, the AVX-512 readings first where one default is named for the project; the kernels in one long of nanoseconds for the classes where the rule names it the default, each with its batch guard, a batch that holds a value outside 1677 to 2262 going to the row path; a kernel whose body is a scalar loop where the rule's default is a scalar body, read after row 698's verdict, since a mask-free two-lane form that wins there replaces it; a column that arrives as epoch nanoseconds admitted as it is, with the form's range; the option that names the form; the forms measured on row 655's nanosecond classes. Planned after row 662's part T2b and after row 661 is built. **Done when** a nanosecond column is read from the cache's struct and written back; the zone-free entries fuse or decline by name; a field over the microsecond word, a cast to microseconds and `second` with its fraction fuse; each of the three representations is measured on row 655's classes at 256 and 128 bits, and the one long where it is built, each built form that is not a class's default kept as an option arm; a class whose default is a scalar body runs that body inside a kernel and answers as the vector kernels do; a batch outside the one long's range answers through the row path; and the values at the range's ends answer as `TimestampNanosVal` does | rows 662, 661 and 653 first; row 655's parts F and G; row 698's verdict for 128 bits; `VARKA-662.md` 4.10 to 4.12 and 19 | large |

**Edit 3. The same file, section 3: rows 673, 674 and 675 wait for row 699 too.** The text
`row 662 first | medium |` ends three lines, so each is changed in its own line:

    sed -i -E '/^\| (673|674|675) /s/row 662 first \| medium \|$/rows 662 and 699 first | medium |/' sql/varka/plans/m9/PLAN.md

Before it `grep -c -E '^\| (673|674|675) .*row 662 first \| medium \|$'
sql/varka/plans/m9/PLAN.md` prints `3`, and after it `0`.

**Edit 4. The same file, section 2.5.** Old, one line:

    benchmark of the two shapes on the same values (`VARKA-679.md` 5.2, L14 and L17) is their first

(with the file's two spaces before it). The line after it is `  measurement.`, the bullet's
last; this paragraph is added after that line, each line with the bullet's two spaces:

    *28 November 2026:* the owner asked that the representation be measured for vectors before it
    is built, as SPARK-56822 analysed it for a scalar engine. Row 662 is now that measurement
    alone: four representations in hand loops (Spark's two parts, one long, seconds with
    nanoseconds, days with nanoseconds), their cost table, and their conversions as proved
    equalities. Row 699 builds the cache's struct in and out and the kernels: over all three
    representations that hold the whole range, by the owner's answer of the same day, and over one
    long where it is the fastest, with the measurement naming each class's default. Where this
    section or a row's line says that row 662 builds the struct, a layout or a kernel, read row
    699. The two layouts above are no longer assumed. A representation is not fixed for the type:
    the table and the equalities are kept so that a choice by query and by source (`m10/SCOPE.md`
    item 11) stays open.

**Edit 5. The same file, section 3.1, the table of goals and rows.** Old, one line:

    | G4 nanoseconds | 660 to 662, 673 to 675, and 681 |

New:

    | G4 nanoseconds | 660 to 662, 673 to 675, 681 and 699 |

**Edit 6. `$P/m9/VARKA-661.md`: what it hands to "row 662" goes to row 699.** Row 661 is not
built, and its plan names row 662 in 26 lines: prose, the decline text, the census cells and
its own commands. In each the row meant is the one that builds the struct and the kernels. So
every one changes, and then one note is added:

    sed -i -E 's/\b662\b/699/g' sql/varka/plans/m9/VARKA-661.md

Before it `grep -c -w 662 $P/m9/VARKA-661.md` prints `26` and `grep -c -w 699` prints `0`;
after it, `0` and `26`. Then, in section 11, after the last line of the bullet that now begins
`- **Row 699** has no plan.` (the line that ends `holds over the type's whole range.`), with
the bullet's two spaces:

    *28 November 2026:* this plan named row 662 where it now names row 699. Row 662 became
    the measurement of the representations alone (`VARKA-662.md`) and touches nothing of
    this row; row 699 takes the ids, the struct in and out and every cell.

**Edit 7. `$P/m9/VARKA-655.md`: the kernels it measures are row 699's.** Its nine lines that
name row 662 mean the row that builds the kernels. First:

    sed -i -E 's/\b662\b/699/g' sql/varka/plans/m9/VARKA-655.md

Before it `grep -c -w 662 $P/m9/VARKA-655.md` prints `9`; after it, `0`. Then, in section
4.14, old, one line (as the `sed` left it):

    | the two nanosecond layouts measured | row 699, on parts F and G's classes |

New, two lines:

    | the nanosecond representations measured in hand loops | row 662, in a spike class of its own (`VARKA-662.md` 4.7) |
    | the nanosecond kernels measured | row 699, on parts F and G's classes |

Nothing else of `VARKA-655.md` assumes a representation: its classes time SQL over the cache.

**Edit 8. `$P/m9/VARKA-679.md`, section 10.1.** Old, one line (the file's two spaces before
it):

    new pin; and the file `shapes` of L17 as the first measurement of its two layouts.

New: the same line, then, with the same two spaces:

    *28 November 2026:* row 662 now measures four representations in Varka's own harness
    (`VARKA-662.md` 4.1), with divisions that neither divide nor convert to a double (its 4.2) and
    guards with no minimum or maximum (its 4.3), which the library's kernels can take. The
    library's contract stands: a caller hands it Spark's two parts, and one long is its arm. Where
    this plan says that row 662 builds a layout or a kernel, read row 699. Row 699 builds seconds
    and days too, by the owner's answer of the same day (`VARKA-662.md` section 19), so the forms
    of 5.3 gain those two shapes as arms, by an issue each; the file `shapes` stays the library's
    own measurement.

**Edit 9. `$P/m10/SCOPE.md`, item 11.** Old, one line (the file's two spaces before it):

    `DateDayVector`-only match in `isArrowBacked` is the first line to redesign.

New: the same line, then, with the same two spaces:

    *28 November 2026:* the nanosecond timestamps are the first type measured this way
    (`m9/VARKA-662.md` 4.8 to 4.10): four representations, a measured cost an operation in
    each and a cost a conversion between each two
    (`m9/results/VARKA-662/nanos_representation_costs.tsv`), with what a cost model may and
    may not conclude from them, and each conversion an equality under a range condition in
    `proofs/nanos_representations.smt2`. No conversion node exists yet: row 699 adds one a
    conversion it builds.

**Edit 10. `$P/m9/VARKA-660.md`, section 4.7: the owner's answer of 28 November 2026.** Added on
28 November 2026, with the owner's answers (section 19). Old, one line, the last of the paragraph
that begins `**What follows for a fused cell.**`:

    row 662, which has no plan yet (section 11).

`grep -c -F` of it prints `1` on master `92fd5a62d13` and on master `4e1d1f3a749`. New: the same
line, then an empty line, then this paragraph:

    *28 November 2026:* the owner changed this for the nanosecond timestamps (`VARKA-662.md` section
    15, answer 2; the kernels are row 699's). The one long may be built where it is the fastest, and
    a batch that holds a value outside 1677 to 2262 then goes to the row path: no two-word kernel
    has to stand behind it. So a cell's default arm may be the one long, and such a cell is fused
    for the years 1677 to 2262 and answers on the row path outside them. The rule that a fused cell
    holds over the type's whole range no longer binds these two types; the census rule above stands,
    a layout is no census type. How the census marks a cell whose default arm is the one long is for
    row 699's plan to say.

**What the owner's answers changed in edits 1, 2, 4 and 8.** Their new texts above are as the
owner's answers leave them, and the files of this plan's pull request hold them word for word.
Edit 1: the done-when's last clause was `a rule names the representations row 699 builds and each
class's default`. Edit 2: row 699's line names all three forms, the one long, the scalar body and
the arriving column. Edit 4: the note's sentence on what row 699 builds. Edit 8: the note's last
sentence, which was a condition on the rule and is now the owner's answer. Not changed, and to be
read with edit 10: `VARKA-660.md` section 13, question 6, and section 15 record the agreement of
27 November 2026 as it was made, and `VARKA-661.md` section 11 cites it.

## 14. What only a JVM can settle, and what the planner's JVM settled

Item 1 was run by the planner in this writing, on the laptop; its outputs are quoted. Items 2
and 3 are for the lead, each a command with the output that decides it.

**1. Does C2 lower what the loops are made of, at each width, on AVX-512 and on AVX2?** One
JDK-only class with no Spark on its class path, **one Vector API call a loop**, so that a line
of the output names the call it tests. The second writing's probe had four loops of several
calls each; its `mixed` loop's `convert` and `binaryOp` lines were read as the two-lane int
vector's conversions and arithmetic, and were its masks'. The class is
`NanosCallProbe.java`, in an empty directory outside the worktree:

    import static jdk.incubator.vector.VectorOperators.*;

    import java.lang.foreign.Arena;
    import java.lang.foreign.MemorySegment;
    import java.nio.ByteOrder;
    import jdk.incubator.vector.*;

    /** One Vector API call a loop: which calls C2 leaves in Java, by width and instruction set. */
    public class NanosCallProbe {
      static final VectorSpecies<Long> LS = LongVector.SPECIES_PREFERRED;
      static final int L = LS.length();
      static final VectorSpecies<Integer> IS =
          VectorSpecies.of(int.class, VectorShape.forBitSize(LS.vectorBitSize() / 2));
      static final VectorSpecies<Integer> I4 =
          VectorSpecies.of(int.class, VectorShape.forBitSize(LS.vectorBitSize()));
      static final VectorSpecies<Short> HS =
          VectorSpecies.of(short.class, VectorShape.forBitSize(Math.max(64, LS.vectorBitSize() / 4)));
      static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;
      static final int N = 4096;
      static final Arena ARENA = Arena.ofAuto();
      static final MemorySegment a = ARENA.allocate(N * 8L + 64, 64);
      static final MemorySegment b = ARENA.allocate(N * 8L + 64, 64);
      static final MemorySegment o = ARENA.allocate(N * 8L + 64, 64);
      static final MemorySegment f = ARENA.allocate(N * 4L + 64, 64);
      static final MemorySegment g = ARENA.allocate(N * 4L + 64, 64);
      static final MemorySegment h = ARENA.allocate(N * 2L + 64, 64);
      static final MemorySegment k = ARENA.allocate(N * 2L + 64, 64);

      static LongVector ll(MemorySegment s, int r) { return LongVector.fromMemorySegment(LS, s, r * 8L, LE); }
      static IntVector ii(MemorySegment s, int r) { return IntVector.fromMemorySegment(IS, s, r * 4L, LE); }
      static IntVector i4(MemorySegment s, int r) { return IntVector.fromMemorySegment(I4, s, r * 4L, LE); }
      static ShortVector hh(MemorySegment s, int r) { return ShortVector.fromMemorySegment(HS, s, r * 2L, LE); }

      static long loopLLoadAddStore() {
        for (int r = 0; r < N; r += L) { ll(a, r).add(ll(b, r)).add(7L).intoMemorySegment(o, r * 8L, LE); }
        return 0;
      }
      static long loopLMulSub() {
        for (int r = 0; r < N; r += L) { ll(a, r).mul(1000L).sub(ll(b, r).mul(ll(a, r))).intoMemorySegment(o, r * 8L, LE); }
        return 0;
      }
      static long loopLShiftXorAnd() {
        for (int r = 0; r < N; r += L) {
          var x = ll(a, r);
          x.lanewise(ASHR, 63).lanewise(XOR, x).lanewise(LSHR, 13).lanewise(LSHL, 2).and(0xFFFFFFFFL).or(x)
              .intoMemorySegment(o, r * 8L, LE);
        }
        return 0;
      }
      static long loopLCompareToLong() {
        long w = 0;
        for (int r = 0; r < N; r += L) { w |= ll(a, r).compare(LT, ll(b, r)).toLong(); }
        return w;
      }
      static long loopLCompareConst() {
        long w = 0;
        for (int r = 0; r < N; r += L) { w |= ll(a, r).compare(GE, 1000000000L).toLong(); }
        return w;
      }
      static long loopLMaskAndOr() {
        long w = 0;
        for (int r = 0; r < N; r += L) {
          var x = ll(a, r); var y = ll(b, r);
          w |= x.compare(LT, y).or(x.compare(EQ, y).and(y.compare(LT, 5L))).toLong();
        }
        return w;
      }
      static long loopLBlend() {
        for (int r = 0; r < N; r += L) {
          var x = ll(a, r); var y = ll(b, r);
          y.blend(x, x.compare(LT, y)).intoMemorySegment(o, r * 8L, LE);
        }
        return 0;
      }
      static long loopLMaskedAdd() {
        for (int r = 0; r < N; r += L) {
          var x = ll(a, r);
          x.add(1L, x.compare(GE, 0L)).sub(5L, x.compare(LT, 9L)).intoMemorySegment(o, r * 8L, LE);
        }
        return 0;
      }
      static long loopLOrReduce() {
        var ov = LongVector.zero(LS);
        for (int r = 0; r < N; r += L) { ov = ov.or(ll(a, r)); }
        return ov.reduceLanes(OR) >> 63;
      }
      static long loopLMinMaxLanes() {
        var lo = LongVector.broadcast(LS, Long.MAX_VALUE);
        var hi = LongVector.broadcast(LS, Long.MIN_VALUE);
        for (int r = 0; r < N; r += L) { var x = ll(a, r); lo = lo.min(x); hi = hi.max(x); }
        long[] l = lo.toArray(); long[] u = hi.toArray();
        long mn = l[0]; long mx = u[0];
        for (int i = 1; i < l.length; i++) { mn = Math.min(mn, l[i]); mx = Math.max(mx, u[i]); }
        return mn + mx;
      }
      static long loopLMinMaxReduce() {
        var lo = LongVector.broadcast(LS, Long.MAX_VALUE);
        var hi = LongVector.broadcast(LS, Long.MIN_VALUE);
        for (int r = 0; r < N; r += L) { var x = ll(a, r); lo = lo.min(x); hi = hi.max(x); }
        return lo.reduceLanes(MIN) + hi.reduceLanes(MAX);
      }
      /** The window guard: (x - lo) | (hi - x), ORed into one accumulator. */
      static long loopGuardWindow() {
        var ov = LongVector.zero(LS);
        for (int r = 0; r < N; r += L) {
          var x = ll(a, r);
          ov = ov.or(x.sub(-9223372036854775L).or(x.neg().add(9223372036854774L)));
        }
        return ov.reduceLanes(OR) >> 63;
      }
      /** The sum guard: (x ^ r) & (y ^ r). */
      static long loopGuardSum() {
        var ov = LongVector.zero(LS);
        for (int r = 0; r < N; r += L) {
          var x = ll(a, r); var y = ll(b, r); var s = x.add(y);
          ov = ov.or(x.lanewise(XOR, s).and(y.lanewise(XOR, s)));
          s.intoMemorySegment(o, r * 8L, LE);
        }
        return ov.reduceLanes(OR) >> 63;
      }
      /** The floor guard: x & ~r. */
      static long loopGuardFloor() {
        var ov = LongVector.zero(LS);
        for (int r = 0; r < N; r += L) {
          var x = ll(a, r); var s = x.lanewise(ASHR, 12).mul(4096L);
          ov = ov.or(x.and(s.not()));
          s.intoMemorySegment(o, r * 8L, LE);
        }
        return ov.reduceLanes(OR) >> 63;
      }
      static long loopILoadAddStore() {
        for (int r = 0; r < N; r += L) { ii(f, r).add(500000000).mul(3).intoMemorySegment(g, r * 4L, LE); }
        return 0;
      }
      static long loopII2L() {
        for (int r = 0; r < N; r += L) {
          ((LongVector) ii(f, r).convertShape(I2L, LS, 0)).intoMemorySegment(o, r * 8L, LE);
        }
        return 0;
      }
      static long loopIL2I() {
        for (int r = 0; r < N; r += L) { ll(a, r).convertShape(L2I, IS, 0).intoMemorySegment(g, r * 4L, LE); }
        return 0;
      }
      static long loopICompareToLong() {
        long w = 0;
        for (int r = 0; r < N; r += L) { w |= ii(f, r).compare(GE, 1000000000).toLong(); }
        return w;
      }
      static long loopIMaskCastToL() {
        for (int r = 0; r < N; r += L) {
          ll(a, r).add(1L, ii(f, r).compare(GE, 1000000000).cast(LS)).intoMemorySegment(o, r * 8L, LE);
        }
        return 0;
      }
      static long loopIMaskedSub() {
        for (int r = 0; r < N; r += L) {
          var x = ii(f, r);
          x.sub(1000000000, x.compare(GE, 1000000000)).intoMemorySegment(g, r * 4L, LE);
        }
        return 0;
      }
      static long loopIBlend() {
        for (int r = 0; r < N; r += L) {
          var x = ii(f, r); var y = ii(g, r);
          y.blend(x, ll(a, r).compare(LT, ll(b, r)).cast(IS)).intoMemorySegment(g, r * 4L, LE);
        }
        return 0;
      }
      static long loopIGuardWindow() {
        var ov = IntVector.zero(IS);
        for (int r = 0; r < N; r += L) {
          var x = ii(f, r);
          ov = ov.or(x.sub(-106751991).or(x.neg().add(106751990)));
        }
        return (long) (ov.reduceLanes(OR) >> 31);
      }
      /** An int vector of the long vector's bits: twice its lanes; the first L are a group's. */
      static long loopI4LoadI2L() {
        for (int r = 0; r < N; r += L) {
          ((LongVector) i4(f, r).convertShape(I2L, LS, 0)).intoMemorySegment(o, r * 8L, LE);
        }
        return 0;
      }
      static long loopI4L2IStore() {
        for (int r = 0; r < N; r += L) { ll(a, r).convertShape(L2I, I4, 0).intoMemorySegment(g, r * 4L, LE); }
        return 0;
      }
      static long loopI4AddStore() {
        for (int r = 0; r < N; r += L) { i4(f, r).add(500000000).intoMemorySegment(g, r * 4L, LE); }
        return 0;
      }
      static long loopI4CompareMaskedSub() {
        for (int r = 0; r < N; r += L) {
          var x = i4(f, r);
          x.sub(1000000000, x.compare(GE, 1000000000)).intoMemorySegment(g, r * 4L, LE);
        }
        return 0;
      }
      static long loopI4GuardWindow() {
        var ov = IntVector.zero(I4);
        for (int r = 0; r < N; r += L) {
          var x = i4(f, r);
          ov = ov.or(x.sub(-106751991).or(x.neg().add(106751990)));
        }
        return (long) (ov.reduceLanes(OR) >> 31);
      }
      static long loopHLoadS2L() {
        for (int r = 0; r < N; r += L) {
          ((LongVector) hh(h, r).convertShape(S2L, LS, 0)).intoMemorySegment(o, r * 8L, LE);
        }
        return 0;
      }
      static long loopHLoadS2I() {
        for (int r = 0; r < N; r += L) {
          ((IntVector) hh(h, r).convertShape(S2I, IS, 0)).intoMemorySegment(g, r * 4L, LE);
        }
        return 0;
      }
      static long loopHL2SStore() {
        for (int r = 0; r < N; r += L) { ll(a, r).convertShape(L2S, HS, 0).intoMemorySegment(k, r * 2L, LE); }
        return 0;
      }
      static long loopHI2SStore() {
        for (int r = 0; r < N; r += L) { ii(f, r).convertShape(I2S, HS, 0).intoMemorySegment(k, r * 2L, LE); }
        return 0;
      }
      static long loopHLoadStore() {
        for (int r = 0; r < N; r += L) { hh(h, r).intoMemorySegment(k, r * 2L, LE); }
        return 0;
      }
      static long loopHCompareCastToL() {
        if (HS.length() != L) { return 0; }
        long w = 0;
        for (int r = 0; r < N; r += L) { w |= hh(h, r).compare(LT, (short) 500).cast(LS).toLong(); }
        return w;
      }
      static long loopHBlend() {
        if (HS.length() != L) { return 0; }
        for (int r = 0; r < N; r += L) {
          hh(k, r).blend(hh(h, r), ll(a, r).compare(LT, ll(b, r)).cast(HS)).intoMemorySegment(k, r * 2L, LE);
        }
        return 0;
      }

      public static void main(String[] args) throws Throwable {
        var rnd = new java.util.Random(1);
        for (int i = 0; i < N; i++) {
          a.setAtIndex(java.lang.foreign.ValueLayout.JAVA_LONG, i, rnd.nextLong() >> 8);
          b.setAtIndex(java.lang.foreign.ValueLayout.JAVA_LONG, i, rnd.nextLong() >> 8);
          f.setAtIndex(java.lang.foreign.ValueLayout.JAVA_INT, i, rnd.nextInt(1000000000));
          h.setAtIndex(java.lang.foreign.ValueLayout.JAVA_SHORT, i, (short) rnd.nextInt(1000));
        }
        if (args[0].equals("list")) {
          for (var m : NanosCallProbe.class.getDeclaredMethods()) {
            if (m.getName().startsWith("loop")) { System.out.println(m.getName()); }
          }
          return;
        }
        var m = NanosCallProbe.class.getDeclaredMethod(args[0]);
        var handle = java.lang.invoke.MethodHandles.lookup().unreflect(m);
        long sink = 0;
        for (int c = 0; c < 20000; c++) { sink += (long) handle.invokeExact(); }
        System.out.println("RESULT " + args[0] + " at " + LS.vectorBitSize() + " bits: " + sink);
      }
    }

It is compiled once and run once a loop and a flag set:

    J=/usr/lib/jvm/java-25-openjdk-amd64/bin
    $J/javac --add-modules jdk.incubator.vector -d classes NanosCallProbe.java
    for loop in $($J/java --add-modules jdk.incubator.vector -cp classes NanosCallProbe list | sort); do
      for flags in "" "-XX:MaxVectorSize=32" "-XX:MaxVectorSize=16" "-XX:UseAVX=2" \
          "-XX:UseAVX=2 -XX:MaxVectorSize=32" "-XX:UseAVX=2 -XX:MaxVectorSize=16"; do
        echo "== $loop [$flags]"
        $J/java --add-modules jdk.incubator.vector -XX:+UnlockDiagnosticVMOptions -Xbatch \
          -XX:-TieredCompilation -XX:CompileCommand=quiet \
          "-XX:CompileCommand=PrintInlining,NanosCallProbe::loop*" \
          "-XX:CompileCommand=dontinline,NanosCallProbe::loop*" ${=flags} -cp classes \
          NanosCallProbe $loop \
          | awk '/VectorSupport::/ && !/\(intrinsic\)$/ && !/late inline succeeded$/' \
          | sed -E 's/.*VectorSupport::([A-Za-z]+).*/\1/' | sort | uniq -c
      done
    done

(`${=flags}` splits the words in zsh.) A run that prints only its `==` line lowered every
call. On the laptop `-XX:UseAVX=2` alone gives 256 bits, so the six flag sets are five
configurations: 512, 256 and 128 bits with AVX-512, and 256 and 128 with AVX2. **What the
planner's run printed**, 34 loops, 204 runs:

| loop | 512 | 256 | 128 | AVX2, 256 | AVX2 and `MaxVectorSize=32`, 256 | AVX2, 128 |
| :--- | :--- | :--- | :--- | :--- | :--- | :--- |
| `loopGuardFloor` | - | - | - | - | - | - |
| `loopGuardSum` | - | - | - | - | - | - |
| `loopGuardWindow` | - | - | - | - | - | - |
| `loopHBlend` | - | - | - | - | - | - |
| `loopHCompareCastToL` | - | - | - | - | - | - |
| `loopHI2SStore` | - | - | - | - | - | - |
| `loopHL2SStore` | - | - | - | - | - | - |
| `loopHLoadS2I` | - | - | - | - | - | - |
| `loopHLoadS2L` | - | - | - | - | - | - |
| `loopHLoadStore` | - | - | - | - | - | - |
| `loopI4AddStore` | - | - | - | - | - | - |
| `loopI4CompareMaskedSub` | - | - | - | - | - | - |
| `loopI4GuardWindow` | - | - | - | - | - | - |
| `loopI4L2IStore` | - | - | - | - | - | - |
| `loopI4LoadI2L` | - | - | - | - | - | - |
| `loopIBlend` | - | - | `blend` 2, `compare` 2, `convert` 2 | - | - | `blend` 2, `compare` 2, `convert` 2 |
| `loopICompareToLong` | - | - | `compare` 2, `maskReductionCoerced` 2 | - | - | `compare` 2, `maskReductionCoerced` 2 |
| `loopIGuardWindow` | - | - | - | - | - | - |
| `loopII2L` | - | - | - | - | - | - |
| `loopIL2I` | - | - | - | - | - | - |
| `loopILoadAddStore` | - | - | - | - | - | - |
| `loopIMaskCastToL` | - | - | `binaryOp` 2, `compare` 2, `convert` 2 | - | - | `binaryOp` 2, `compare` 2, `convert` 2 |
| `loopIMaskedSub` | - | - | `binaryOp` 2, `compare` 2 | - | - | `binaryOp` 2, `compare` 2 |
| `loopLBlend` | - | - | `blend` 2, `compare` 2 | - | - | `blend` 2, `compare` 2 |
| `loopLCompareConst` | - | - | `compare` 2, `maskReductionCoerced` 2 | - | - | `compare` 2, `maskReductionCoerced` 2 |
| `loopLCompareToLong` | - | - | `compare` 2, `maskReductionCoerced` 2 | - | - | `compare` 2, `maskReductionCoerced` 2 |
| `loopLLoadAddStore` | - | - | - | - | - | - |
| `loopLMaskAndOr` | - | - | `binaryOp` 4, `compare` 6, `maskReductionCoerced` 2 | - | - | `binaryOp` 4, `compare` 6, `maskReductionCoerced` 2 |
| `loopLMaskedAdd` | - | - | `binaryOp` 4, `compare` 4 | - | - | `binaryOp` 4, `compare` 4 |
| `loopLMinMaxLanes` | `store` 2 | `store` 2 | `store` 2 | `store` 2 | `store` 2 | `store` 2 |
| `loopLMinMaxReduce` | - | - | - | `reductionCoerced` 4 | `reductionCoerced` 4 | `reductionCoerced` 4 |
| `loopLMulSub` | - | - | - | - | - | - |
| `loopLOrReduce` | - | - | - | - | - | - |
| `loopLShiftXorAnd` | - | - | - | - | - | - |

A count is over the loop's compiles, the on-stack replacement and the method, so a call site
shows twice. `loopHBlend` and `loopHCompareCastToL` do nothing at 128 bits, where a short vector
has four lanes and a cast to two is refused: layout H's own form there, a mask crossed by its
bits, was checked on the generated loops (4.7).

**What it decided.** The guard is the OR accumulator: `loopGuardWindow`, `loopGuardSum`,
`loopGuardFloor`, `loopIGuardWindow` and `loopLOrReduce` are lowered under all six, where
`loopLMinMaxReduce` (the second writing's guard) is not under `-XX:UseAVX=2`, and
`loopLMinMaxLanes` (the minimum taken in scalar Java after a `toArray`) leaves the array store
in Java under all six. At 128 bits nothing but a mask of two lanes is left in Java: the
two-lane int vector's load, arithmetic, `I2L` and `L2I` are lowered. The four-lane int vector
beside the two-lane long vector is lowered too, its own masks included, and is not needed
(4.3).

The same check was then made of the loops themselves: every one of the 322 generated loops,
a child JVM a loop, read as the benchmark's `fallbacks` reads it. 4.7 has its table.

**2. Does the class wildcard of the compile command parse?** The first writing's lead ran it:

    /usr/lib/jvm/java-25-openjdk-amd64/bin/java \
      '-XX:CompileCommand=dontinline,org.apache.spark.sql.VarkaNanosSpikeLoops*::loop*' -version 2>&1 | head -3

It prints a line `CompileCommand: dontinline org/apache/spark/sql/VarkaNanosSpikeLoops*.loop*
bool dontinline = true` and no line with `error` or `unrecognized`. The planner's harness ran
every loop under that command, and each loop was a compile of its own in the child JVM's
`PrintCompilation`.

**3. What still needs a JVM**, with Spark on the class path, in order:

    # P2: the generated classes and VarkaNanosSpikeOps under the repository's compiler and linters
    build/sbt catalyst/Test/compile
    dev/lint-java
    # P3: the suite against Spark's own methods, at the three widths
    build/sbt 'catalyst/testOnly *VarkaNanosRepresentationSpikeSuite'
    JDK_JAVA_OPTIONS=-XX:MaxVectorSize=32 build/sbt 'catalyst/testOnly *VarkaNanosRepresentationSpikeSuite'
    JDK_JAVA_OPTIONS=-XX:MaxVectorSize=16 build/sbt 'catalyst/testOnly *VarkaNanosRepresentationSpikeSuite'
    # P4: the benchmark's first run, which nothing here executed: 5.5's laptop command at 32 bytes

Each ends as its sheet says: the compile with no error, the suite with `Passed: Total 4, Failed
0, Errors 0, Passed 4`, the benchmark with a results file that passes 5.5's checks. The three
questions the second writing asked of a JVM for the storage (the literals, Arrow's loader,
the declined type's node) are row 699's now (4.11).

## 15. Questions for the owner

One question is open. The owner answered the other seven on 28 November 2026; they are kept below
as a record, and section 19 says what each answer changed in this plan. The questions keep the
numbers the third writing gave them.

**The words the questions use.** A *form* is a way to hold a nanosecond timestamp in vector lanes.
*R1* is Spark's own two words: epoch microseconds in a long and the nanoseconds within the
microsecond, 0 to 999, in a short. *R2* is one long of epoch nanoseconds, which holds the years
1677 to 2262 alone. *R3* is epoch seconds in a long and the nanoseconds of the second in an int.
*R4* is epoch days in an int and the nanoseconds of the day in a long. A *layout* is how a form's
narrow word sits in vectors; *layout H* keeps R1's short in a short vector. A *class* is a group
of operations the rule decides together (compare, choice, interval arithmetic, clock fields, date
fields, truncation, zone conversion, stored conversions, a chain of three); a form *wins* a class
when the other forms take 1.3 times its time or more. Row 699 is the row that builds the kernels.
*The EPYC 7763 at 256 bits* is the GitHub runner's CPU, the machine the committed baselines are
read on.

**Open.**

4. **Asked after the pilot, with its number; nothing waits for it.** The study divides by a
   constant with multiplies alone (the high half of a 64-bit product, "the multiply-high"). One
   loop, *the control*, computes the hour the house's other way, through a conversion to a double
   and a division, which is vector code only where the CPU has the long-to-double instruction
   (AVX-512). If the pilot shows the control 1.3 times faster than the multiply-high or more
   there: (a) *Recommended, and the default, which the plan builds:* record the ratio; the study
   stays on one division method for every form, and row 699's lowering picks the division by host,
   as VARKA-538 does today. (b) Run the study a second time on such hosts with the divisions
   printed in the conversion form, one more statement form in the generator; the cost table then
   gains a `division` column.

**Answered on 28 November 2026.**

1. **If the measurements favour different forms at different widths or hosts: which host
   decides.** The owner: "AVX-512 has high priority". The lead's reading, which the plan applies:
   since all three forms are built (answer 6), each host and width takes its own default for each
   class; where one default is named for the project, the AVX-512 readings come first, the
   laptop's 512-bit block and then an AVX-512 runner's, and then the EPYC 7763 at 256 bits (4.10,
   step 6). The plan's default had been the EPYC 7763's set for every host.
2. **May R2, one long of epoch nanoseconds, be built though it holds 1677 to 2262 alone?** Yes,
   where it is the fastest, with a batch that holds a value outside 1677 to 2262 going to the row
   path. This reverses, for the nanosecond timestamps, the agreement of 27 November 2026 in
   `VARKA-660.md` 4.7 that a fused cell holds over the type's whole range (section 13, edit 10).
   The plan's default had been no.
3. **Data that arrives as epoch nanoseconds.** Row 699 admits such a column as it is, with the
   form's range; it is not only measured. The plan's default had been the measurement alone.
5. **A width where scalar loops win a class, or where no vector loop has a reading.** Row 699
   builds a scalar body inside a kernel there, not a decline. The plan's default had been a
   measured refusal.
6. **How many forms.** All three, R1, R3 and R4, whatever the numbers. The plan's default had been
   R1 and the better of R3 and R4, with the other at a bar of 2.0 times.
7. **Row 699.** A new row, as the plan proposed.
8. **Generated Java in the test sources.** Generated, as the plan proposed.

## 16. The first review's 35 findings

`reviews/VARKA-662-review-1.md` read the first writing as a builder. Each finding is closed
here, or belongs to row 699 with the item of 4.12 that holds it. The second review found four
of them still open, 1, 19 and 27 and, by its finding 6, 9; the table says what closes each now.

| # | finding, in short | here |
| ---: | :--- | :--- |
| 1 | the rule read Java-fallback times; divisions through `L2D` on AVX2 | closed, after the second review reopened it: no loop divides or converts (4.2); no guard takes a minimum or a maximum (4.3); a loop calls nothing of its own (4.4); every loop's inlining tree was read under `-XX:UseAVX=2` and is clean at 256 bits (4.7); a fallback is never read and a missing time never wins (4.10); the AVX-512 file is named (4.7) |
| 2 | the dispatch lacked the option the harness requires | closed: the one compile command in every dispatch and the laptop's command, in full (5.5) |
| 3 | the loops were not code; the script lacked a third of them | closed: every loop is a formula of appendices A and B, run on numbers, its Java printed from it (4.4), compiled and run (section 3); 322 loops, listed by one command |
| 4 | `ZONE` named an unbuilt class and an oracle that could not match | closed: three offsets around two changes as constants, the oracle's zone and year fixed (4.1, 4.6) |
| 5 | tail, over-read, validity, null data undefined | closed: 4.3 |
| 6 | which words a loop loads was unstated | closed: read off the generated code (4.1's table) |
| 7 | layout I described by calls the API lacks; no species table | closed: the species are code (appendix E), the layouts defined (section 2, 4.3, 4.4); every call was compiled and run |
| 8 | S1 over 1000; arms at a third of the model's density | closed: every hand-written file is in an appendix and counted exactly; the loops are generated (section 5) |
| 9 | runtime unestimated | closed, after the second review's finding 6 took its exit away: counted and priced (4.7), scaled by the pilot (5.5), and a long run is taken in parts that each write a whole file, joined by `dev/varka_bench_sections.py splice` (5.7) |
| 10 | JIT evidence weaker than the remainder spike's | closed: its harness and its inlining-tree check, with the lines written to the results file (4.5) |
| 11 | `YEAR`'s calendar neither code nor ranged | closed: `year_of_day`, its range the days of every long of microseconds (4.2) |
| 12 | mirror loops in name only | closed: 4.1's table says which loops exist for which arm; each is a formula |
| 13 | the suite's inputs and interface had holes; a fault read backwards | closed: batches in numbers with their row counts asserted, a loop returns its guard, the faults were run (4.6) |
| 14 | the rule's gaps (a) to (g) | closed: 4.10, and twenty-three hand applications that the tool's self-test holds |
| 15 | the rule could not choose one long for compares and chains | closed: classes are decided one by one, and step 6 answers the arriving case (table 4) |
| 16 | "no builder re-plans" was false | closed: the claim is gone; nothing after the study is sheeted |
| 17 | rule 6 contradicted the question | closed: question 1 has one default, and the tool computes the built set on the deciding host alone (4.10, table D) |
| 18 | two rules called the same | closed: one rule; the measurement on row 655's classes is row 699's (4.12) |
| 19 | the cost table could not price a tree | closed, after the second review reopened it: the measured loops are the truth and the base has its own row; a derived cost is floored at zero and flagged `within noise` or `not additive`, never negative; the residual is a row with its tolerance; and 4.8 lists what a model may and may not conclude, with what the table does not price |
| 20 | `bytes` meant two things | closed: `alloc` alone |
| 21 | the cost tool was in no sheet | closed: appendix D, part T2, with its self-test |
| 22 | E2 and E4 stated over a range the proof did not cover | closed: 4.9, proved at both ends |
| 23 | only R1 to R2 was proved | closed: all twelve conversions among the four forms, each with its range (4.9) |
| 24 | conversion nodes expanded out of sight | row 699: "the conversions as IR nodes", with the condition readable on the node |
| 25 | S2 to S4 edited the same files in parallel | closed: a class a form, generated; S1 and S2 are in order |
| 26 | part A joined what the study decides with what it does not | closed: the storage is not in this plan (4.11) |
| 27 | part B was a design, not code | row 699: the second review found the storage parts still not code (its finding 13), and they left this plan (4.11) |
| 28 | the readers of `runKernel`'s columns were not listed | row 699: "the storage" |
| 29 | part D copied a private method by instruction | row 699: "`IN`, `in_set`, `between`, `equal_null`" |
| 30 | part F1's switch sites were "found by the compiler" | row 699: "the conversions as IR nodes" |
| 31 | part G left the class of a turned form open | row 699: "the measurement on row 655's classes" |
| 32 | part I named a table that did not hold its list | row 699: "the two-lane refusals", read after row 698 |
| 33 | section 13 printed six spaces for two | closed: section 13 says how an indented old text is read, and names each file's own indent |
| 34 | holes in the test plan of parts A and C | row 699: "the storage" |
| 35 | G7's bar read on sections that mix a layout with a compaction | row 699: "the measurement on row 655's classes"; this row serves G7 through it alone (section 1) |

## 17. The second review's 20 findings

`reviews/VARKA-662-review-2.md` read the second writing as a builder, with the output of a
real JVM run of its probe.

| # | finding, in short | here |
| ---: | :--- | :--- |
| 1 | the range guard's `reduceLanes(MIN)` and `MAX` are left in Java on AVX2 | closed: a guard is one OR accumulator, in three forms, each exact where it is used and proved so (4.3, 4.9); the formulas, the generator and every sum changed; the planner's probe and its check of all 322 loops under `-XX:UseAVX=2` found no call left in Java at 256 bits (4.7, section 14). The `toArray` form the review also named leaves a store in Java, and was not taken |
| 2 | the pilot's go cannot be met on the runner; predictions 1 and 3 contradicted | closed: the go is restated on what the planner's check found at each width (5.5), and predictions 1 and 3 keep their registered texts with a dated correction (section 7) |
| 3 | the rule had data for three of nine classes on the deciding host, and read missing data as R1's win | closed: after the guard's repair every class has a reading at 256 bits (4.7); and a form with no time has no data, the class is undecided, nobody wins it (4.10, tables 6, C, C2, C3) |
| 4 | at 128 bits layout M is mostly Java | not so, and closed: one call a loop, the probe shows the two-lane int vector's `I2L`, `L2I` and arithmetic lowered; the first probe's `convert` and `binaryOp` lines were masks'. What falls back at 128 bits is every mask of two lanes, in every layout (4.3, 4.7). The four-lane int vector was probed and is not needed. The rule's step 2 and step 3 say what a class is there |
| 5 | row 698 is taken | closed: the new row is 699, in prose and in section 13 alone, since no code string names it now; section 12 says what row 698 reads of this row and what this row leaves to it |
| 6 | S3's split by `-Dvarka.bench.only` cannot write a results file | closed: `-Dvarka.nanos.ops`, the benchmark's own, times each of its operations whole with the base and writes its file; the value has no space; the files are joined by `dev/varka_bench_sections.py splice`; 5.7 has the runs, the lists and the counts |
| 7 | the planted fault `BAND = 1.0` does not fail the self-test | closed: it fails in six checks, and two more faults were added and run (5.9) |
| 8 | step 2 said the opposite of the tool | closed: the text and the tool's line both say that the best vector loop takes 1.3 times the best scalar loop or more (4.10) |
| 9 | the per-width built set contradicted question 1's default | closed: the built set is computed on the deciding host and width alone; other widths take defaults within it and name what they do not build (4.10, table D) |
| 10 | R2 vetoes a buildable form; equal counts broken by a form's name | closed: R2 takes no part in step 3 (table F); between R3 and R4 the order is wins, the band, the mean, then R3 as the owner's second form, said in step 4 |
| 11 | "R1 always" is nobody's instruction, and the rule can end in R1 alone | closed: the rule builds at least two forms, as the owner said; R1 in layout H is his first form; question 6 asks him plainly, with building one form only as its alternative. Step 6's `HOUR` and `ROUND` are said to be read as cost-table rows only |
| 12 | the cost table can go negative, has no base, and does not price some trees | closed: the first review's 19, above |
| 13 | parts N1 to N3 are not at `VARKA-656.md`'s standard | closed: they left this plan; 4.11 is the one page that says what they were and what they wait for |
| 14 | a guarded loop is never compared at the ends of a long | closed: a declined batch is split by the checker's table of the guards; the accepted rows are compared and each declined row must be declined alone (4.6); the `ends` batch holds every guard's two ends |
| 15 | generated Java: nothing in CI keeps it generated | closed: one line in `dev/varka_scoped_suites.sh`, run by the `varka-scoped` catalyst jobs of every Build, with its planted fault (5.3); question 8 says what its other answer costs, with the cut |
| 16 | two unlabeled blocks of sums; a line range that differed from a comment | closed: the blocks are labelled (5.6); the three helpers are whole in appendix H, so no line range is followed |
| 17 | P1 is 771 counted lines with no cut line | closed: P1a and P1b, each checked alone; the cut lines of section 5 |
| 18 | edit 2's place and the census edit name a state of master that moved | closed: edit 2 is placed after row 698's line; this plan edits no census file |
| 19 | predictions 4 and 10 less certain than their wording | noted by the review as needing no change; prediction 10 is restated for the suite as it now is |
| 20 | only compare and chain are read in the arriving data | closed: step 6 says that `HOUR` and `ROUND` are rows of the cost table and read by no step |

## 18. The third review's 17 findings

`reviews/VARKA-662-review-3.md` read the third writing as its builder and ran its tools. It
reopened nothing of the design; each finding is a defect of the text or of a tool.

| # | finding, in short | here |
| ---: | :--- | :--- |
| 1 | the five Python appendices fail `ruff check` and `ruff format --check`, and the plan forbade changing them | closed: the appendices are the formatter's output and pass both; one lambda became a `def`, one import block was sorted, three comment lists were moved out of a call so that no line the hook reports passes 100 columns; every generated class and proof file kept its sum; 5.0's paragraph on the Python linter, and the two commands in P1a, P1b, P2, T1 and T2a |
| 2 | `DateTimeUtils.TRUNC_TO_HOUR()` has no static forwarder | closed: appendix F calls `DateTimeUtils$.MODULE$.TRUNC_TO_HOUR()`; 5.0 lists every call into Scala with its declaration and the reason it compiles; P3's B0 greps the declarations; the stubs have scalac's shape |
| 3 | a class with no loop row printed as `scalar at this width` | closed: `undecided: no loop of the class in the table`, in step 2, in the tool and in table Z |
| 4 | the fallback for an undecided deciding host could not be read from the output | closed: every other host then prints its own set under `built provisional`; step 4 and table U |
| 5 | the self-test did not notice a dropped layout H or M | closed: tables G, H and M, and two planted faults in 5.9 |
| 6 | `minutes.txt` had no source | closed: 5.5 step 5, from the run's log and the step's end for a runner, from `date` around a compiled tree for the laptop; the commands were not run (no run exists) |
| 7 | "the owner's to name" was an open choice outside section 15 | closed: a rule (R1 and R3), printed by the tool, and question 6(e) |
| 8 | `$VARKA_HEAVY_LOCK` was never set | closed: 5.0 gives the repository's default and the whole command; 5.4 and 5.6 print theirs whole |
| 9 | no word on a missing solver | closed: 5.8 |
| 10 | step 5's "1.3 times R1" reads both ways; the boundary is not reproducible by hand | closed: step 5's wording, the ratio on a default's line, and 4.10's paragraph on the arithmetic |
| 11 | risk 9 was false for the tie-break's mean | closed: risk 9 says what crosses parts and what the lead does where it decides |
| 12 | the counted sizes were the unformatted ones; T2 had no cut line | closed: recounted; T2 is cut into T2a and T2b, since the tool with its new tables and the lead's text together passed the cap |
| 13 | questions 1, 4, 6, 7 and 8 could not be answered without the plan | closed: section 15 defines its words; 6(d) asks for the number; 4 is marked as asked after the pilot, 8 as optional |
| 14 | three small dark places | closed: the renumbering command names its file and its moment (edit 2); 5.9 step 4 has the count's command; `SKILLS.md` is at the root |
| 15 | two line numbers of 5.0's table | closed: `startTiming` at 105, `ownJvm` at 93 |
| 16 | "the lead re-plans before S1" had no content | closed: 5.5, the two cases |
| 17 | R2's line was printed in decided classes only | closed: printed under every verdict that has a buildable time; table R |

Not taken from the review's section D, which numbers no finding: the four notes on what a
later reader of the cost table could over-trust. They are for part T2b's text in section 11.

## 19. The owner's answers of 28 November 2026

The owner answered seven of section 15's questions on 28 November 2026, after this plan's pull
request was opened. This section lists what each answer changed; the letters are the lead's, the
numbers the questions'. Nothing of the loops, the formulas, the generator, the checker, the suite,
the benchmark or the proofs changed.

**A. All three whole-range forms are built (question 6).**

- 4.10: the built set is gone, with the third writing's step 4, the 2.0 bar (`CLEAR`), the choice
  of the better of R3 and R4 and the line for no host deciding between them. The rule names a
  default for each class on each host and width, and prints how much each other form loses.
- Appendix D: `build`, `defaults` and the deciding host are gone; `pick`, `first_of_tie` and the
  project's block are new. Twenty-nine tables and 103 checks, where there were twenty-three and
  102.
- 4.12, 4.13, section 1, 5.9's sentence for section 11, prediction 5 (a dated correction), risks 5
  and 9, and section 13's edits 1, 2, 4 and 8 with the files they change.

**B. AVX-512 has high priority (question 1).**

- 4.10, step 6: each host and width keeps its own defaults; the project's defaults are read from
  the laptop's 512-bit block first, then an AVX-512 runner's 512-bit block, then the EPYC 7763 at
  256 bits; the `order` line gives the order in which row 699 builds the forms.
- 4.7: the AVX-512 runner CPUs are named from `sql/varka/HARDWARE.md`. No dispatch is added: S3
  already takes three with no `expected-cpu` for an AVX-512 reading, beside the two on the EPYC
  7763. The EPYC 7763's readings stay.
- Risks 10 and 16.

**C. A scalar body in a kernel, not a decline (question 5).**

- 4.10, step 1: a form's reading is its scalar loop where no vector layout has a time or the
  vector loop is the slower by the band; the default's line then reads `<form>S, a scalar body`,
  and a `scalar` line gives the reason. The lines `scalar at this width` and `decline at this
  width` are gone.
- 4.12: the item "a kernel whose body is a scalar loop" takes the place of "the two-lane
  refusals", with its done-when, what is not known, and row 698's verdict read first. 4.13,
  section 12 and risk 15 follow.

**D. R2 may be built where it is the fastest (question 2).**

- 4.10: R2 is a candidate in the four classes where it has loops over Spark's parts, and a default
  only where it wins; its line carries its range, its fallback to the row path, and the
  whole-range default of the same class (step 4); the `r2` line lists where it is built.
- What was checked: an R2 loop over Spark's parts already times the range guard and both
  conversions (4.10, "What R2's time holds"; `loopLtR2LA1Z1` in the generated
  `VarkaNanosSpikeLoopsR2.java` was read). So no loop is added. What a batch outside the range
  costs is not timed, and 4.12's item says so.
- 4.12: the item "the R2 kernels with their batch guard and fallback". Section 13, edit 10: a
  dated note in `VARKA-660.md` 4.7. Risk 14.

**E. A column that arrives as epoch nanoseconds is admitted (question 3).**

- 4.12: the item says so, with what is not known; 4.13 moves the kernel input from the
  Arrow-native datasource's row to row 699. 4.10's step 5 keeps the arriving lines as the
  measurement behind it. Row 699's line (edit 2).

**Questions 7 and 8** were answered as the plan's defaults, and change nothing. **Question 4**
stays open, asked after the pilot.

**What sections 16 to 18 say of the rule** is the record of three reviews of the earlier rule.
Where a line there names the built set, the deciding host, `scalar at this width` or R2's line
"the owner decides", the protection it records is kept in another form: a fallback is no time
(tables 6, C, C2, C3); a class with no row is `undecided` (Z); a candidate with no data leaves its
class undecided (7, N, R); layouts H and M are read (G, H, M); R2 is printed wherever it has a
time and wins nothing where it is no candidate (F, Q, R).

**The numbers that changed.** `dev/varka_nanos_costs.py`: 906 lines where it had 931, SHA-256
`ac0cb3b6e5fa98c590ec668472266b449b31f94279b8534566862f1b52044d2c`; part T2a's counted size is the
same 906, under the cap, so no part is cut anew. The self-test: 103 checks. The planted faults:
fourteen where there were seven. The tables of 4.10: twenty-nine. The loops (322), the cells
(442), the dispatches (two on the EPYC 7763 and three with no `expected-cpu`), the laptop's three
runs and every other part's size are as they were.

**The check that nothing else moved**, run by the planner on this file: appendices A, B, C and J,
taken by 5.0's command, have the sums of 5.0's table; `python3 dev/varka_nanos_spike_gen.py gen
pilot`, `gen r12` and `gen study` print the sums that 5.3 and 5.6 list for the generated classes;
`python3 dev/varka_nanos_spike_proofs.py write` writes the three proof files with the sums of 5.8;
and `ruff check` and `ruff format --check` (0.14.8) pass on appendix D as on the other four.

## Appendices: the files of parts P1a to T2

Each is taken by 5.0's command and checked against 5.0's table of sums.

### File: dev/varka_nanos_spike.py

    #!/usr/bin/env python3
    #
    # Licensed to the Apache Software Foundation (ASF) under one or more
    # contributor license agreements.  See the NOTICE file distributed with
    # this work for additional information regarding copyright ownership.
    # The ASF licenses this file to You under the Apache License, Version 2.0
    # (the "License"); you may not use this file except in compliance with
    # the License.  You may obtain a copy of the License at
    #
    #    http://www.apache.org/licenses/LICENSE-2.0
    #
    # Unless required by applicable law or agreed to in writing, software
    # distributed under the License is distributed on an "AS IS" BASIS,
    # WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    # See the License for the specific language governing permissions and
    # limitations under the License.
    #
    """The loops of the nanosecond representations' study (VARKA-662), written once.

    A nanosecond timestamp is held in one of four forms:

        R1 (m, n)  epoch microseconds, nanoseconds within the microsecond 0..999   Spark's storage
        R2 x       epoch nanoseconds in one long                                    1677 to 2262
        R3 (s, f)  epoch seconds, nanoseconds of the second 0..999999999
        R4 (d, t)  epoch days, nanoseconds of the day 0..86399999999999

    Each operation and each conversion is one Python function over lane values. Run on numbers
    (`verify`) it is checked against exact integers; run on symbols (dev/varka_nanos_spike_gen.py) it
    prints the Java loop with the same operations. So a loop's Java is the formula that was checked,
    never a second copy of it. This file holds R1 and R2; dev/varka_nanos_spike_r34.py adds R3 and
    R4 where it exists, and the check below runs over the forms that are there.

        python3 dev/varka_nanos_spike.py     the formulas of the forms present, against exact integers
    """

    import importlib
    import importlib.util
    import random
    import sys

    MIN, MAX = -(1 << 63), (1 << 63) - 1
    BITS = {"L": 64, "I": 32, "H": 16}
    E6, E9 = 10**6, 10**9
    DAY_US, DAY_NS = 86400 * E6, 86400 * E9
    HOUR_US, HOUR_NS, MIN_US, MIN_NS = 3600 * E6, 3600 * E9, 60 * E6, 60 * E9

    # The ranges of the conversions: each is a window of one word.
    R2_M = (-9223372036854775, 9223372036854774)  # m whose m * 1000 + n is a long for every n
    R2_S = (-9223372036, 9223372035)  # s whose s * 10^9 + f is a long for every f
    R2_D = (-106751, 106750)  # d whose d * DAY_NS + t is a long for every t
    R1_S = (-9223372036854, 9223372036853)  # s whose microseconds are a long for every f
    R1_D = (-106751991, 106751990)  # d whose microseconds are a long for every t
    R4_S = (-(1 << 31) * 86400, (1 << 31) * 86400 - 1)  # s whose day is an int
    R1_DAYS = (-106751992, 106751991)  # the days of every long of microseconds
    YEAR_BIAS = 719468 + 146097 * 800  # the day of 0000-03-01, 800 eras down
    # The window a guard tests, by (the form converted from, the form converted to): its word.
    WINDOWS = {
        (1, 2): ("m", R2_M),
        (3, 1): ("s", R1_S),
        (4, 1): ("d", R1_D),
        (3, 2): ("s", R2_S),
        (4, 2): ("d", R2_D),
        (3, 4): ("s", R4_S),
    }

    # The interval literal of ADDC and CHAIN: INTERVAL '1:00:01.5' HOUR TO SECOND, in microseconds.
    C_US = 3601500000
    # The zone of ZONE: America/Los_Angeles over 2025, as three offsets around two changes (seconds).
    Z_T0, Z_T1 = 1741514400, 1762074000  # 2025-03-09T10:00:00Z, 2025-11-02T09:00:00Z
    Z_O0, Z_D1, Z_D2 = -28800, 3600, -3600  # the offset, and its change at each
    SEED = 42
    P1, P2, P3 = 0x9E3779B185EBCA87, 0xC2B2AE3D27D4EB4F, 0x165667B19E3779F9
    P4, P5 = 0x85EBCA77C2B2AE63, 0x27D4EB2F165667C5


    class PastBound(Exception):
        """A bounded division met a dividend past its bound."""


    def wrap(x, bits=64):
        x &= (1 << bits) - 1
        return x - (1 << bits) if x >> (bits - 1) else x


    def hour_floor_low(hour):
        """The least multiple of `hour` that is a long: under it a floor to the hour wraps."""
        return -(-MIN // hour) * hour


    # ---- The divisions by a constant, with no divide and no conversion to a double. -----------------


    def tz(d):
        return (d & -d).bit_length() - 1


    def find_mul_shift(d, umax):
        """(k, M, p): ((u >> k) * M) >> p == u // d for 0 <= u <= umax, the product under 2^64."""
        for k in range(tz(d), -1, -1):
            odd, vmax = d >> k, umax >> k
            for p in range(1, 64):
                m = -(-(1 << p) // odd)
                if (m * odd - (1 << p)) * vmax < (1 << p) and m * vmax < (1 << 64) and m < (1 << 63):
                    return k, m, p
        return None


    def find_div_fix(d, umax):
        """(k, M, p, odd): q1 = ((u >> k) * M) >> p is u // d or one less; one compare repairs it."""
        k = tz(d)
        odd, vmax = d >> k, umax >> k
        p = 63
        while p > 0 and (not vmax < (1 << p) or (1 << p) // odd * vmax >= (1 << 63)):
            p -= 1
        assert p > 0 and vmax < (1 << p), (d, umax)
        return k, (1 << p) // odd, p, odd


    def find_mul_high(d, bits=63):
        """(k, M, s): mulhi(u >> k, M) >> s == u // d for 0 <= u < 2^bits; mulhi the high 64 bits."""
        k = tz(d)
        odd, vmax = d >> k, (1 << (bits - k)) - 1
        for s in range(64):
            m = -(-(1 << (64 + s)) // odd)
            if m < (1 << 64) and (m * odd - (1 << (64 + s))) * vmax < (1 << (64 + s)):
                return k, m, s
        raise AssertionError(d)


    def lane_mul_high(v, m):
        """The high 64 bits of the unsigned v * m, from four products of 32-bit halves."""
        ml, mh = m & 0xFFFFFFFF, m >> 32
        vl, vh = v.band(0xFFFFFFFF), v.ushr(32)
        ll, lh, hl, hh = vl.mul(ml), vl.mul(mh), vh.mul(ml), vh.mul(mh)
        mid = ll.ushr(32).add(lh.band(0xFFFFFFFF)).add(hl.band(0xFFFFFFFF))
        return hh.add(lh.ushr(32)).add(hl.ushr(32)).add(mid.ushr(32))


    def lane_fdiv(x, d):
        """floor(x / d) for every long x: the sign folded away, a multiply-high, the sign put back.
        For a negative x the floor is the complement of the quotient of its complement."""
        k, m, s = find_mul_high(d)
        sign = x.shr(63)
        return lane_mul_high(x.bxor(sign).ushr(k), m).ushr(s).bxor(sign)


    def lane_udiv(u, d, umax):
        """u // d for 0 <= u <= umax: one multiply, or two with a repair, or the multiply-high."""
        form = udiv_form(d, umax)
        if form[0] == "MULSHIFT":
            _, k, m, p = form
            return u.ushr(k).mul(m).ushr(p)
        if form[0] == "DIVFIX":
            # The multiply's quotient is the true one or one less: the remainder's sign says which.
            _, k, m, p, odd = form
            v = u.ushr(k)
            q = v.mul(m).ushr(p)
            return q.add(1).sub(v.sub(q.mul(odd)).sub(odd).ushr(63))
        _, k, m, s = form
        return lane_mul_high(u.ushr(k), m).ushr(s)


    def udiv_form(d, umax):
        """Which form a division of 0..umax by d takes: the cheapest that is exact."""
        found = find_mul_shift(d, umax)
        if found:
            return ("MULSHIFT",) + found
        if umax // d < (1 << 20):
            return ("DIVFIX",) + find_div_fix(d, umax)
        return ("MULHIGH",) + find_mul_high(d, umax.bit_length())


    # ---- Lane values: numbers (Val) and symbols (Sym) with one interface. ---------------------------


    class Run:
        """One evaluation of a loop body on numbers; SymRun of varka_nanos_spike_gen.py is its twin."""

        symbolic = False

        def __init__(self, scalar):
            self.scalar = scalar
            self.flag = False  # a guard saw a value outside its range

        def const(self, kind, value):
            """A lane value holding one constant."""
            return Val(self, wrap(value, BITS[kind]), kind)

        def load(self, field, stored, kind, value=None):
            """The stored buffer `field` (long, int or short elements) as a lane value of `kind`."""
            return Val(self, wrap(value, BITS[kind]), kind)

        def guard(self, lane, outside):
            """A batch declines where `lane` is negative; `outside` is the exact test it stands for."""
            assert (lane.v < 0) == outside(), ("a guard's lanes and its exact test differ", lane.v)
            self.flag |= lane.v < 0


    def guard_window(run, v, lo, hi):
        """Declines where v is outside [lo, hi]: the sign of (v - lo) | (hi - v). For a window under
        half the lane's range at most one of the differences wraps, and it wraps to a negative."""
        assert 0 <= hi - lo < (1 << (BITS[v.kind] - 1)), (lo, hi)
        run.guard(v.sub(lo).bor(run.const(v.kind, hi).sub(v)), lambda: not lo <= v.v <= hi)


    def guard_sum(run, x, y, r):
        """Declines where the wrapping r = x + y overflowed: the sign of (x ^ r) & (y ^ r)."""
        run.guard(
            x.bxor(r).band(r.bxor(y)),
            lambda: not MIN <= x.v + (y if isinstance(y, int) else y.v) <= MAX,
        )


    def guard_floor(run, x, r, hour):
        """Declines where r, the floor of x to a multiple of `hour`, wrapped past the low end of a
        long: the sign of x & ~r. The floor is at most x, so it wrapped where x < 0 <= r."""
        run.guard(x.band(r.bnot()), lambda: x.v < hour_floor_low(hour))


    class Val:
        """A lane's number, wrapping at its width."""

        def __init__(self, run, v, kind):
            self.run, self.v, self.kind = run, v, kind

        def _o(self, o):
            return o.v if isinstance(o, Val) else o

        def _n(self, v, kind=None):
            kind = kind or self.kind
            return Val(self.run, v if kind == "K" else wrap(v, BITS[kind]), kind)

        def add(self, o):
            return self._n(self.v + self._o(o))

        def sub(self, o):
            return self._n(self.v - self._o(o))

        def mul(self, o):
            return self._n(self.v * self._o(o))

        def shr(self, k):
            return self._n(self.v >> k)

        def ushr(self, k):
            return self._n((self.v & ((1 << BITS[self.kind]) - 1)) >> k)

        def shl(self, k):
            return self._n(self.v << k)

        def bnot(self):
            return self._n(~self.v)

        def band(self, o):
            return self._n(self.v & self._o(o))

        def bor(self, o):
            return self._n(self.v | self._o(o))

        def bxor(self, o):
            return self._n(self.v ^ self._o(o))

        def lt(self, o):
            return Val(self.run, self.v < self._o(o), "K")

        def ge(self, o):
            return Val(self.run, self.v >= self._o(o), "K")

        def gt(self, o):
            return Val(self.run, self.v > self._o(o), "K")

        def eq(self, o):
            return Val(self.run, self.v == self._o(o), "K")

        def kand(self, o):
            return Val(self.run, self.v and o.v, "K")

        def kor(self, o):
            return Val(self.run, self.v or o.v, "K")

        def addk(self, c, k):
            return self._n(self.v + c if k.v else self.v)

        def subk(self, c, k):
            return self._n(self.v - c if k.v else self.v)

        def to(self, kind):
            return self._n(self.v, kind)

        def fdiv(self, d):
            r = self._n(self.v // d) if self.run.scalar else lane_fdiv(self, d)
            assert r.v == self.v // d, (self.v, d)
            return r

        def udiv(self, d, umax):
            if not 0 <= self.v <= umax:
                raise PastBound((self.v, d, umax))
            r = self._n(self.v // d) if self.run.scalar else lane_udiv(self, d, umax)
            assert r.v == self.v // d, (self.v, d, umax)
            return r


    def sel(k, t, f):
        """t where k, else f."""
        return (t if k.v else f) if isinstance(k, Val) else t.run.select(k, t, f)


    def wide(v):
        return v.to("L")


    # ---- The forms, their conversions and their operations. Each table is keyed by a form, or by a
    # pair of forms; dev/varka_nanos_spike_r34.py adds the entries of R3 and R4.

    # form -> ((word, the letter of its buffer in a column, the stored element), ...).
    FORMS = {
        1: (("m", "M", "long"), ("n", "N", "short")),
        2: (("x", "X", "long"),),
    }
    NARROW = {1: "n"}  # form -> its narrow word; "f" and "d" are those of R3 and R4
    # layout -> the lanes of a narrow word. H holds Spark's short in short lanes and is M for the
    # other narrow words; S is the scalar loop, whose narrow words are ints.
    LAYOUTS = {
        "L": {"n": "L", "f": "L", "d": "L"},
        "M": {"n": "I", "f": "I", "d": "I"},
        "H": {"n": "H", "f": "I", "d": "I"},
        "S": {"n": "I", "f": "I", "d": "I"},
    }


    def window(run, a, src, dst):
        """The guard of the conversion src to dst: its word inside its window."""
        word, (lo, hi) = WINDOWS[(src, dst)]
        guard_window(run, a[word], lo, hi)


    def c12(run, a, nk):
        window(run, a, 1, 2)
        return {"x": a["m"].mul(1000).add(wide(a["n"]))}


    def c21(run, a, nk):
        m = a["x"].fdiv(1000)
        return {"m": m, "n": a["x"].sub(m.mul(1000)).to(nk["n"])}


    CONVERT = {(1, 2): c12, (2, 1): c21}


    def convert(run, a, src, dst, nk):
        """The words of `a`, held in form `src`, in form `dst`; `nk` gives a narrow word's lanes."""
        return dict(a) if src == dst else CONVERT[(src, dst)](run, a, nk)


    def year_of_day(day):
        """The proleptic Gregorian year of an epoch day in R1_DAYS, on long lanes with no mask."""
        z = day.add(YEAR_BIAS)
        era = z.udiv(146097, R1_DAYS[1] + YEAR_BIAS)
        doe = z.sub(era.mul(146097))
        yoe = (
            doe.sub(doe.udiv(1460, 146096))
            .add(doe.udiv(36524, 146096))
            .sub(doe.udiv(146096, 146096))
            .udiv(365, 146096)
        )
        doy = doe.sub(yoe.mul(365).add(yoe.ushr(2)).sub(yoe.udiv(100, 399)))
        # March starts the year of this count: day 306 on is January or February of the next.
        return yoe.add(era.mul(400)).add(1 - 320000).add(doy.sub(306).shr(63))


    def rotl(v, r):
        return v.shl(r).bor(v.ushr(64 - r))


    def fmix(h):
        h = h.bxor(h.ushr(33)).mul(wrap(P2))
        h = h.bxor(h.ushr(29)).mul(wrap(P3))
        return h.bxor(h.ushr(32))


    def xxhash(m, n):
        """XXH64.hashInt(n, XXH64.hashLong(m, 42)): Spark's xxhash64 of a nanosecond timestamp."""
        h = rotl(m.mul(wrap(P2)), 31).mul(wrap(P1)).bxor(wrap(SEED + P5 + 8))
        h = fmix(rotl(h, 27).mul(wrap(P1)).add(wrap(P4)))
        h = n.band(0xFFFFFFFF).mul(wrap(P1)).bxor(h.add(wrap(P5 + 4)))
        return fmix(rotl(h, 23).mul(wrap(P2)).add(wrap(P3)))


    def lt(form, a, b):
        """a < b, a mask over long lanes: the first word, then the second where the first ties."""
        if form == 2:
            return a["x"].lt(b["x"])
        hi, lo = FORMS[form][0][0], FORMS[form][1][0]
        first, tie, second = a[hi].lt(b[hi]), a[hi].eq(b[hi]), a[lo].lt(b[lo])
        if a[lo].kind == "L" and a[hi].kind != "L":
            # R4 in a mixed layout: the long word's mask leads, so that the result is a long mask.
            return second.kand(tie).kor(first)
        return first.kor(tie.kand(second))


    def pick(form, a, b):
        k = lt(form, a, b)
        return {w: sel(k, a[w], b[w]) for w, _, _ in FORMS[form]}


    def add_literal_12(word, unit):
        def fn(run, a):
            r = a[word].add(C_US * unit)
            guard_sum(run, a[word], C_US * unit, r)
            return dict(a, **{word: r})

        return fn


    def trunc_hour_12(word, hour):
        def fn(run, a, guarded):
            r = a[word].fdiv(hour).mul(hour)
            if guarded:
                guard_floor(run, a[word], r, hour)
            return {w: r if w == word else run.const(v.kind, 0) for w, v in a.items()}

        return fn


    def add_1(run, a, q):
        r = a["m"].add(q)
        guard_sum(run, a["m"], q, r)
        return {"m": r, "n": a["n"]}


    def add_2(run, a, q):
        guard_window(run, q, *R2_M)
        y = q.mul(1000)
        r = a["x"].add(y)
        guard_sum(run, a["x"], y, r)
        return {"x": r}


    def zone_words(run, a, word, unit, guarded):
        """Three offsets around two changes, on the one word that orders the instants."""
        w = a[word]
        base = w.add(Z_O0 * unit)
        if guarded:
            guard_sum(run, w, Z_O0 * unit, base)
        local = base.addk(Z_D1 * unit, w.ge(Z_T0 * unit)).addk(Z_D2 * unit, w.ge(Z_T1 * unit))
        return dict(a, **{word: local})


    # What each operation needs of a form. ADD_LITERAL: a + C_US microseconds, the literal split at
    # compile time. TRUNC_HOUR: a floored to an hour, guarded where the floor may pass the low end
    # of a long. ADD: a + q microseconds. OF_DAY: (the time of day, its unit's count in a second).
    # SECOND: the nanoseconds of the minute from the time of the minute. DAY: the epoch day.
    ADD_LITERAL = {1: add_literal_12("m", 1), 2: add_literal_12("x", 1000)}
    TRUNC_HOUR = {1: trunc_hour_12("m", HOUR_US), 2: trunc_hour_12("x", HOUR_NS)}
    ADD = {1: add_1, 2: add_2}
    OF_DAY = {
        1: lambda a: (a["m"].sub(a["m"].fdiv(DAY_US).mul(DAY_US)), E6),
        2: lambda a: (a["x"].sub(a["x"].fdiv(DAY_NS).mul(DAY_NS)), E9),
    }
    SECOND = {
        1: lambda a, of_minute: of_minute.mul(1000).add(wide(a["n"])),
        2: lambda a, of_minute: of_minute,
    }
    DAY = {1: lambda a: a["m"].fdiv(DAY_US), 2: lambda a: a["x"].fdiv(DAY_NS)}
    ZONE = {
        1: lambda run, a: zone_words(run, a, "m", E6, True),
        2: lambda run, a: zone_words(run, a, "x", E9, True),
    }


    def op_hour(run, form, a, b, q):
        od, unit = OF_DAY[form](a)
        return ("I", od.udiv(3600 * unit, 86400 * unit - 1))


    def op_sec(run, form, a, b, q):
        """extract(SECOND FROM a): the nanoseconds of the minute, a DECIMAL(11, 9)'s unscaled long."""
        od, unit = OF_DAY[form](a)
        return ("J", SECOND[form](a, od.sub(od.udiv(60 * unit, 86400 * unit - 1).mul(60 * unit))))


    def op_hash(run, form, a, b, q):
        r1 = convert(run, a, form, 1, LAYOUTS["L"])
        return ("J", xxhash(r1["m"], wide(r1["n"])))


    def op_chain(run, form, a, b, q):
        """date_trunc('HOUR', CASE WHEN a < b THEN a ELSE b END + the literal)."""
        # The sum is at least an hour above the low end, so its floor needs no guard.
        return ("V", TRUNC_HOUR[form](run, ADD_LITERAL[form](run, pick(form, a, b)), False))


    # operation -> (its formula over (run, form, a, b, q), the columns it reads). A formula gives
    # the kind of its result (V a timestamp, W a selection word, I an int, J a long) and the result.
    OPS = {
        "ROUND": (lambda run, form, a, b, q: ("V", a), "a"),
        "LT": (lambda run, form, a, b, q: ("W", lt(form, a, b)), "ab"),
        "PICK": (lambda run, form, a, b, q: ("V", pick(form, a, b)), "ab"),
        "ADD": (lambda run, form, a, b, q: ("V", ADD[form](run, a, q)), "aq"),
        "ADDC": (lambda run, form, a, b, q: ("V", ADD_LITERAL[form](run, a)), "a"),
        "HOUR": (op_hour, "a"),
        "SEC": (op_sec, "a"),
        "YEAR": (lambda run, form, a, b, q: ("I", year_of_day(DAY[form](a))), "a"),
        "TRUNC": (lambda run, form, a, b, q: ("V", TRUNC_HOUR[form](run, a, True)), "a"),
        "MICROS": (lambda run, form, a, b, q: ("J", convert(run, a, form, 1, LAYOUTS["L"])["m"]), "a"),
        "ZONE": (lambda run, form, a, b, q: ("V", ZONE[form](run, a)), "a"),
        "HASH": (op_hash, "a"),
        "CHAIN": (op_chain, "ab"),
    }
    OP_ORDER = list(OPS)
    VALUE_OPS = ("ROUND", "PICK", "ADD", "ADDC", "TRUNC", "ZONE", "CHAIN")


    def body(run, op, form, layout, arrival, sink, columns):
        """One loop body: load in `arrival`, compute in `form`, store in `sink`.

        `columns` gives, for numbers, {"a": {word: number}, "b": ..., "q": number}; for symbols it
        is None and the buffers are read where the formula asks for them.
        """
        nk = LAYOUTS[layout]

        def column(role):
            words = {}
            for word, letter, stored in FORMS[arrival]:
                kind = nk[word] if NARROW.get(arrival) == word else "L"
                value = None if run.symbolic else columns[role][word]
                words[word] = run.load(role + letter, stored, kind, value)
            return convert(run, words, arrival, form, nk)

        fn, roles = OPS[op]
        a = column("a")
        b = column("b") if "b" in roles else None
        q = run.load("q", "long", "L", None if run.symbolic else columns["q"]) if "q" in roles else None
        kind, result = fn(run, form, a, b, q)
        if kind == "V":
            result = convert(run, result, form, sink, nk)
        return kind, result


    # ---- The loops of the study and of the pilot. ---------------------------------------------------

    SETS = {"r12": (1, 2), "study": (1, 2, 3, 4)}
    # H is first among R1's layouts: Spark's own two words, a long beside a short.
    ARMS = (
        (1, "H"),
        (1, "L"),
        (1, "M"),
        (1, "S"),
        (2, "L"),
        (2, "S"),
        (3, "L"),
        (3, "M"),
        (3, "S"),
        (4, "L"),
        (4, "M"),
        (4, "S"),
    )
    PILOT = (((1, "H"), (1, "S"), (3, "M")), ("ROUND", "LT", "HOUR", "ADDC"))


    def loops(which):
        """(op, form, layout, arrival, sink) of every loop of a set, in the order they are timed;
        only the loops whose three forms are present. Every set holds the pilot's loops."""
        pilot = [(op, form, layout, 1, 1) for form, layout in PILOT[0] for op in PILOT[1]]
        out = []
        if which == "pilot":
            return [key for key in pilot if key[1] in FORMS]
        for form, layout in ARMS:
            if form not in SETS[which]:
                continue
            for arrival in (1, 2, 3, 4):
                if arrival not in (1, 2, form):
                    continue
                for op in OP_ORDER:
                    if (
                        form == 2
                        and arrival == 1
                        and op not in ("ROUND", "LT", "PICK", "ADD", "ADDC", "CHAIN")
                    ):
                        continue  # one long over Spark's parts: the compares and the sums
                    if arrival == 2 and form != 2 and op not in ("ROUND", "LT", "HOUR", "CHAIN"):
                        continue  # the mirror loops: data that arrives as epoch nanoseconds
                    out.append((op, form, layout, arrival, arrival))
            # The conversion edges into this form: a ROUND that arrives in another and is stored here.
            for arrival in (1, 2, 3, 4):
                if arrival != form:
                    out.append(("ROUND", form, layout, arrival, form))
        out += [key for key in pilot if key not in out]
        return [key for key in out if key[1] in FORMS and key[3] in FORMS and key[4] in FORMS]


    def loop_name(op, form, layout, arrival, sink):
        return "loop%s%sR%d%sA%dZ%d" % (op[0], op[1:].lower(), form, layout, arrival, sink)


    def class_of(form):
        return "VarkaNanosSpikeLoopsR%d" % form


    # ---- verify: every loop body on numbers, against exact integers. --------------------------------


    def exact_forms(big):
        """The words of the exact count `big` of nanoseconds in each form, as exact integers."""
        return {
            1: dict(zip("mn", divmod(big, 1000))),
            2: {"x": big},
            3: dict(zip("sf", divmod(big, E9))),
            4: dict(zip("dt", divmod(big, DAY_NS))),
        }


    def exact_nanos(form, words):
        return {
            1: lambda w: w["m"] * 1000 + w["n"],
            2: lambda w: w["x"],
            3: lambda w: w["s"] * E9 + w["f"],
            4: lambda w: w["d"] * DAY_NS + w["t"],
        }[form](words)


    def year_exact(day):
        """The proleptic Gregorian year of an epoch day, by a count that shares no step with ours."""
        import datetime

        cycles, rest = divmod(day - 10957, 146097)  # 10957 is 2000-01-01
        return (datetime.date(2000, 1, 1) + datetime.timedelta(days=rest)).year + 400 * cycles


    def xxhash_exact(m, n):
        def rot(v, r):
            v &= (1 << 64) - 1
            return ((v << r) | (v >> (64 - r))) & ((1 << 64) - 1)

        def mix(h):
            h ^= h >> 33
            h = h * P2 & ((1 << 64) - 1)
            h ^= h >> 29
            h = h * P3 & ((1 << 64) - 1)
            return h ^ (h >> 32)

        mask = (1 << 64) - 1
        h = (SEED + P5 + 8) & mask
        h ^= rot((m & mask) * P2, 31) * P1 & mask
        h = mix((rot(h, 27) * P1 + P4) & mask)
        g = (h + P5 + 4) & mask
        g ^= (n & 0xFFFFFFFF) * P1 & mask
        return wrap(mix((rot(g, 23) * P2 + P3) & mask))


    def zone_exact(big):
        off = Z_O0 + (Z_D1 if big >= Z_T0 * E9 else 0) + (Z_D2 if big >= Z_T1 * E9 else 0)
        return big + off * E9


    def expected(op, a, b, q):
        """(kind, the exact answer) of `op` over the exact nanosecond counts a and b."""

        def trunc(v):
            return v - v % HOUR_NS

        return {
            "ROUND": lambda: ("V", a),
            "LT": lambda: ("W", a < b),
            "PICK": lambda: ("V", min(a, b)),
            "ADD": lambda: ("V", a + q * 1000),
            "ADDC": lambda: ("V", a + C_US * 1000),
            "HOUR": lambda: ("I", a // HOUR_NS % 24),
            "SEC": lambda: ("J", a % MIN_NS),
            "YEAR": lambda: ("I", year_exact(a // DAY_NS)),
            "TRUNC": lambda: ("V", trunc(a)),
            "MICROS": lambda: ("J", a // 1000),
            "ZONE": lambda: ("V", zone_exact(a)),
            "HASH": lambda: ("J", xxhash_exact(a // 1000, a % 1000)),
            "CHAIN": lambda: ("V", trunc(min(a, b) + C_US * 1000)),
        }[op]()


    def holds(form, big):
        """Whether form holds the exact count `big` of nanoseconds, its narrow word in its lanes."""
        return {
            1: MIN <= big // 1000 <= MAX,
            2: MIN <= big <= MAX,
            3: MIN <= big // E9 <= MAX,
            4: -(1 << 31) <= big // DAY_NS < (1 << 31),
        }[form]


    def must_decline(key, a, b, q):
        """Whether the loop must decline a row, by the table of the guards (the plan's 4.3) in exact
        integers: a second statement of the guards, which shares no line with the formulas."""
        op, form, _, arrival, sink = key

        def outside(src, dst, big):
            if (src, dst) not in WINDOWS:
                return False
            word, (lo, hi) = WINDOWS[(src, dst)]
            return not lo <= exact_forms(big)[src][word] <= hi

        if outside(arrival, form, a) or ("b" in OPS[op][1] and outside(arrival, form, b)):
            return True
        v = min(a, b) if op in ("PICK", "CHAIN") else a
        unit, hour = (1, HOUR_US) if form == 1 else (1000, HOUR_NS)
        if form in (1, 2):
            word = v // (1000 // unit)  # m of R1, x of R2
            if op == "ADD" and (
                unit == 1000 and not R2_M[0] <= q <= R2_M[1] or not MIN <= word + q * unit <= MAX
            ):
                return True
            if op in ("ADDC", "CHAIN") and word + C_US * unit > MAX:
                return True
            if op == "TRUNC" and word < hour_floor_low(hour):
                return True
            if op == "ZONE" and word + Z_O0 * E6 * unit < MIN:
                return True
        if op in ("MICROS", "HASH"):
            return outside(form, 1, a)
        return op in VALUE_OPS and outside(form, sink, expected(op, a, b, q)[1])


    def verify_one(key, cases, stats):
        """A loop declines a row exactly where the guards' table says, and else answers exactly."""
        op, form, layout, arrival, sink = key
        for (m, n), (m2, n2), q in cases:
            a, b = m * 1000 + n, m2 * 1000 + n2
            if not (holds(arrival, a) and holds(arrival, b)):
                continue
            cols = {"a": exact_forms(a)[arrival], "b": exact_forms(b)[arrival], "q": q}
            run = Run(layout == "S")
            try:
                kind, got = body(run, op, form, layout, arrival, sink, cols)
            except PastBound as bound:
                # Allowed only in a row that a guard must decline: its lanes hold wrapped values.
                assert must_decline(key, a, b, q), ("past a bound in a row that answers", key, bound)
                stats["declined"] += 1
                continue
            want_kind, want = expected(op, a, b, q)
            assert kind == want_kind, key
            assert run.flag == must_decline(key, a, b, q), ("guard", key, m, n, m2, n2, q, run.flag)
            if run.flag:
                sure = all(abs(v) <= 4 * 10**15 for v in (m, m2)) and abs(q) <= 10**15
                assert not sure, ("declined inside the sure range", key, m, n, m2, n2, q)
                stats["declined"] += 1
                continue
            if op == "ZONE" and not 1735689600 * E9 <= a < 1767225600 * E9:
                continue  # the zone's two changes are those of 2025
            stats["answered"] += 1
            if kind == "V":
                words = {w: v.v for w, v in got.items()}
                assert exact_nanos(sink, words) == want, (key, m, n, m2, n2, q, words, want)
                for word, _, _ in FORMS[sink]:
                    bound = {"n": 1000, "f": E9, "t": DAY_NS}.get(word)
                    assert bound is None or 0 <= words[word] < bound, (key, words)
            elif kind == "W":
                assert got.v == want, (key, m, n, m2, n2)
            else:
                assert got.v == wrap(want, 64), (key, m, n, got.v, want)


    # The first words at each guard's two ends, inside and outside; R3 and R4 add their windows'.
    GUARD_ENDS = [
        R2_M[0] - 1,
        R2_M[0],
        R2_M[1],
        R2_M[1] + 1,
        MAX - C_US,
        MAX - C_US + 1,
        (MAX - C_US * 1000) // 1000,
        (MAX - C_US * 1000) // 1000 + 1,
        hour_floor_low(HOUR_US) - 1,
        hour_floor_low(HOUR_US),
        hour_floor_low(HOUR_NS) // 1000 - 1,
        hour_floor_low(HOUR_NS) // 1000,
        MIN - Z_O0 * E6 - 1,
        MIN - Z_O0 * E6,
        (MIN - Z_O0 * E9) // 1000,
        (MIN - Z_O0 * E9) // 1000 + 1,
    ]


    def cases():
        rng = random.Random(662)
        edges = [
            MIN,
            MIN + 1,
            -1,
            0,
            1,
            MAX - 1,
            MAX,
            -DAY_US - 1,
            -DAY_US,
            DAY_US - 1,
            DAY_US,
            -E6,
            -E6 + 1,
            E6 - 1,
            -62135596800000000,
            253402300799999999,
            Z_T0 * E6 - 1,
            Z_T0 * E6,
            Z_T1 * E6 - 1,
            Z_T1 * E6,
            1735689600 * E6,
            1767225600 * E6 - 1,
            1751328000 * E6 - 1,
            (1 << 62),
            -(1 << 62),
        ] + GUARD_ENDS
        values = [(m, n) for m in edges for n in (0, 1, 500, 999)]
        for _ in range(3000):
            pick_range = rng.randrange(4)
            m = (
                rng.randrange(MIN, MAX + 1)
                if pick_range == 0
                else rng.randrange(R2_M[0], R2_M[1] + 1)
                if pick_range == 1
                else rng.randrange(1735689600 * E6, 1767225600 * E6)
                if pick_range == 2
                else rng.randrange(-62135596800000000, 253402300799999999 + 1)
            )
            values.append((m, rng.randrange(1000)))
        deltas = [
            0,
            1,
            -1,
            999999,
            E6,
            -E6 - 1,
            DAY_US,
            -DAY_US - 1,
            3 * DAY_US + 7,
            DAY_US - 1,
            -(10**15),
            10**15,
            R2_M[0] - 1,
            R2_M[0],
            R2_M[1],
            R2_M[1] + 1,
            MIN,
            MAX,
        ]
        out = []
        for i, a in enumerate(values):
            b = values[(i * 7 + 3) % len(values)] if i % 5 else a
            if i % 11 == 0:
                b = (a[0], (a[1] + 1) % 1000)
            out.append((a, b, deltas[i % len(deltas)] if i % 3 else rng.randrange(-DAY_US, DAY_US)))
        # Sums that land exactly on a second and on a day, where a carry's compare is decided.
        year = 1735689600 * E6
        for base in (0, year, -DAY_US * 3):
            out.append(((base + E6 - C_US % E6, 0), (base, 0), E6 - 1))
            out.append(((base + 1, 0), (base + 1, 0), E6 - 1))
            out.append(((base + DAY_US - C_US % DAY_US, 0), (base + DAY_US, 0), DAY_US - 1))
            out.append(((base + 1, 0), (base + 2, 0), DAY_US - 1))
            out.append(((base + E6 - C_US % E6, 1), (base + DAY_US - C_US % DAY_US, 999), -1))
        # Sums of a column at the ends of a long, where the sum guard is decided.
        for m, q in (
            (MAX, 0),
            (MAX, 1),
            (MAX - 5, 5),
            (MAX - 5, 6),
            (MIN, 0),
            (MIN, -1),
            (MIN + 5, -5),
            (MIN + 5, -6),
            (R2_M[1], R2_M[1]),
            (R2_M[0], R2_M[0]),
            (1, R2_M[1]),
            (R2_M[1], 1),
            (0, R2_M[1]),
            (-1, R2_M[0]),
        ):
            out += [((m, n), (m, n), q) for n in (0, 999)]
        return out


    BOUNDED = (
        (HOUR_US, DAY_US - 1),
        (MIN_US, DAY_US - 1),
        (3600, 86399),
        (60, 86399),
        (1000, E9 - 1),
        (HOUR_NS, DAY_NS - 1),
        (MIN_NS, DAY_NS - 1),
        (1000, DAY_NS - 1),
        (E9, DAY_NS - 1),
        (146097, R1_DAYS[1] + YEAR_BIAS),
        (1460, 146096),
        (36524, 146096),
        (146096, 146096),
        (365, 146096),
        (100, 399),
    )
    FLOORS = (1000, E6, DAY_US, DAY_NS, 86400, HOUR_US, HOUR_NS, 3600, E9)


    def verify():
        lanes = Run(False)
        for d in FLOORS:
            rng = random.Random(d)
            for x in [MIN, MIN + 1, -1, 0, 1, MAX, -d, -d - 1, d, d - 1] + [
                rng.randrange(MIN, MAX + 1) for _ in range(20000)
            ]:
                Val(lanes, x, "L").fdiv(d)  # asserts the lanes' quotient is the floor
        forms = {}
        for d, umax in BOUNDED:
            rng = random.Random(d + umax)
            forms[(d, umax)] = udiv_form(d, umax)
            multiples = [
                k * d + r for k in range(0, umax // d + 1, max(1, umax // d // 3000)) for r in (-1, 0)
            ]
            for u in (
                [0, 1, d - 1, d, umax] + multiples + [rng.randrange(umax + 1) for _ in range(20000)]
            ):
                if 0 <= u <= umax:
                    Val(lanes, u, "L").udiv(d, umax)
        print(
            "divisions: %d floor divisions of every long and %d bounded ones agree"
            % (len(FLOORS), len(forms))
        )
        all_cases = cases()
        stats = {"answered": 0, "declined": 0}
        keys = sorted(set(loops("study") + loops("pilot")))
        assert set(loops("pilot")) <= set(loops("study"))
        for key in keys:
            verify_one(key, all_cases, stats)
        print("forms: " + " ".join("R%d" % form for form in sorted(FORMS)))
        print(
            "loops: %d loops over %d cases each: %d answers equal the exact integers, %d declined"
            % (len(keys), len(all_cases), stats["answered"], stats["declined"])
        )
        print("all checks passed")
        return 0


    if __name__ == "__main__":
        # Run as the module of its own name, so that the file of R3 and R4 extends this one.
        sys.exit(importlib.import_module("varka_nanos_spike").verify())
    elif importlib.util.find_spec("varka_nanos_spike_r34"):
        importlib.import_module("varka_nanos_spike_r34")

### File: dev/varka_nanos_spike_r34.py

    #
    # Licensed to the Apache Software Foundation (ASF) under one or more
    # contributor license agreements.  See the NOTICE file distributed with
    # this work for additional information regarding copyright ownership.
    # The ASF licenses this file to You under the Apache License, Version 2.0
    # (the "License"); you may not use this file except in compliance with
    # the License.  You may obtain a copy of the License at
    #
    #    http://www.apache.org/licenses/LICENSE-2.0
    #
    # Unless required by applicable law or agreed to in writing, software
    # distributed under the License is distributed on an "AS IS" BASIS,
    # WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    # See the License for the specific language governing permissions and
    # limitations under the License.
    #
    """The forms R3 and R4 of the nanosecond representations' study (VARKA-662).

        R3 (s, f)  epoch seconds, nanoseconds of the second 0..999999999
        R4 (d, t)  epoch days, nanoseconds of the day 0..86399999999999

    dev/varka_nanos_spike.py holds the lane values, R1 and R2, the loops and the check; it loads
    this file where it exists, and this file adds its entries to that one's tables. So `python3
    dev/varka_nanos_spike.py` checks R1 and R2 alone without this file, and all four forms with it.
    """

    import varka_nanos_spike as sp
    from varka_nanos_spike import (
        C_US,
        DAY_NS,
        DAY_US,
        E6,
        E9,
        HOUR_NS,
        R1_D,
        R1_S,
        R2_D,
        R2_S,
        Z_D1,
        Z_D2,
        Z_O0,
        Z_T0,
        Z_T1,
        wide,
    )

    sp.FORMS.update(
        {3: (("s", "S", "long"), ("f", "F", "int")), 4: (("d", "D", "int"), ("t", "T", "long"))}
    )
    sp.NARROW.update({3: "f", 4: "d"})


    # ---- The conversions that meet R3 or R4. A guard is the window of sp.WINDOWS. -------------------


    def c13(run, a, nk):
        s = a["m"].fdiv(E6)
        return {"s": s, "f": a["m"].sub(s.mul(E6)).mul(1000).add(wide(a["n"])).to(nk["f"])}


    def c31(run, a, nk):
        sp.window(run, a, 3, 1)
        f = wide(a["f"])
        q = f.udiv(1000, E9 - 1)
        return {"m": a["s"].mul(E6).add(q), "n": f.sub(q.mul(1000)).to(nk["n"])}


    def c14(run, a, nk):
        d = a["m"].fdiv(DAY_US)
        return {"d": d.to(nk["d"]), "t": a["m"].sub(d.mul(DAY_US)).mul(1000).add(wide(a["n"]))}


    def c41(run, a, nk):
        sp.window(run, a, 4, 1)
        q = a["t"].udiv(1000, DAY_NS - 1)
        return {"m": wide(a["d"]).mul(DAY_US).add(q), "n": a["t"].sub(q.mul(1000)).to(nk["n"])}


    def c23(run, a, nk):
        s = a["x"].fdiv(E9)
        return {"s": s, "f": a["x"].sub(s.mul(E9)).to(nk["f"])}


    def c32(run, a, nk):
        sp.window(run, a, 3, 2)
        return {"x": a["s"].mul(E9).add(wide(a["f"]))}


    def c24(run, a, nk):
        d = a["x"].fdiv(DAY_NS)
        return {"d": d.to(nk["d"]), "t": a["x"].sub(d.mul(DAY_NS))}


    def c42(run, a, nk):
        sp.window(run, a, 4, 2)
        return {"x": wide(a["d"]).mul(DAY_NS).add(a["t"])}


    def c34(run, a, nk):
        sp.window(run, a, 3, 4)
        d = a["s"].fdiv(86400)
        return {"d": d.to(nk["d"]), "t": a["s"].sub(d.mul(86400)).mul(E9).add(wide(a["f"]))}


    def c43(run, a, nk):
        q = a["t"].udiv(E9, DAY_NS - 1)
        return {"s": wide(a["d"]).mul(86400).add(q), "f": a["t"].sub(q.mul(E9)).to(nk["f"])}


    sp.CONVERT.update(
        {
            (1, 3): c13,
            (3, 1): c31,
            (1, 4): c14,
            (4, 1): c41,
            (2, 3): c23,
            (3, 2): c32,
            (2, 4): c24,
            (4, 2): c42,
            (3, 4): c34,
            (4, 3): c43,
        }
    )


    # ---- The operations in R3 and in R4. Neither form's own sum can leave it, so none has a guard:
    # a result pays its window where it is stored in a narrower form.


    def add_literal_3(run, a):
        f = a["f"].add(C_US % E6 * 1000)
        carry = f.ge(E9)
        return {"s": a["s"].add(C_US // E6).addk(1, carry), "f": f.subk(E9, carry)}


    def add_literal_4(run, a):
        t = a["t"].add(C_US % DAY_US * 1000)
        carry = t.ge(DAY_NS)
        return {"d": a["d"].addk(1, carry), "t": t.subk(DAY_NS, carry)}


    def add_3(run, a, q):
        qs = q.fdiv(E6)
        f = a["f"].add(q.sub(qs.mul(E6)).mul(1000).to(a["f"].kind))
        carry = f.ge(E9)
        return {"s": a["s"].add(qs).addk(1, carry), "f": f.subk(E9, carry)}


    def add_4(run, a, q):
        qd = q.fdiv(DAY_US)
        t = a["t"].add(q.sub(qd.mul(DAY_US)).mul(1000))
        carry = t.ge(DAY_NS)
        return {"d": a["d"].add(qd.to(a["d"].kind)).addk(1, carry), "t": t.subk(DAY_NS, carry)}


    def zone_4(run, a):
        """R4 compares the pair (d, t) with each change, then moves the day where the time left it."""
        steps = []
        for change in (Z_T0, Z_T1):
            day, of = divmod(change, 86400)
            steps.append(a["t"].ge(of * E9).kand(a["d"].eq(day)).kor(a["d"].gt(day)))
        t = a["t"].add(Z_O0 * E9).addk(Z_D1 * E9, steps[0]).addk(Z_D2 * E9, steps[1])
        down = t.lt(0)
        t, d = t.addk(DAY_NS, down), a["d"].subk(1, down)
        up = t.ge(DAY_NS)
        return {"d": d.addk(1, up), "t": t.subk(DAY_NS, up)}


    sp.ADD_LITERAL.update({3: add_literal_3, 4: add_literal_4})
    sp.TRUNC_HOUR.update(
        {
            3: lambda run, a, guarded: {
                "s": a["s"].fdiv(3600).mul(3600),
                "f": run.const(a["f"].kind, 0),
            },
            4: lambda run, a, guarded: {
                "d": a["d"],
                "t": a["t"].udiv(HOUR_NS, DAY_NS - 1).mul(HOUR_NS),
            },
        }
    )
    sp.ADD.update({3: add_3, 4: add_4})
    sp.OF_DAY.update(
        {3: lambda a: (a["s"].sub(a["s"].fdiv(86400).mul(86400)), 1), 4: lambda a: (a["t"], E9)}
    )
    sp.SECOND.update(
        {3: lambda a, of_minute: of_minute.mul(E9).add(wide(a["f"])), 4: lambda a, of_minute: of_minute}
    )
    sp.DAY.update({3: lambda a: a["s"].fdiv(86400), 4: lambda a: wide(a["d"])})
    sp.ZONE.update({3: lambda run, a: sp.zone_words(run, a, "s", 1, False), 4: zone_4})

    # The first words at the ends of the windows of R3 and R4, for the check's cases.
    sp.GUARD_ENDS += [
        R1_S[0] * E6 - 1,
        R1_S[0] * E6,
        (R1_S[1] + 1) * E6 - 1,
        (R1_S[1] + 1) * E6,
        R1_D[0] * DAY_US - 1,
        R1_D[0] * DAY_US,
        (R1_D[1] + 1) * DAY_US - 1,
        (R1_D[1] + 1) * DAY_US,
        R2_S[0] * E6 - 1,
        R2_S[0] * E6,
        (R2_S[1] + 1) * E6 - 1,
        (R2_S[1] + 1) * E6,
        R2_D[0] * DAY_US - 1,
        R2_D[0] * DAY_US,
        (R2_D[1] + 1) * DAY_US - 1,
        (R2_D[1] + 1) * DAY_US,
    ]

### File: dev/varka_nanos_spike_gen.py

    #!/usr/bin/env python3
    #
    # Licensed to the Apache Software Foundation (ASF) under one or more
    # contributor license agreements.  See the NOTICE file distributed with
    # this work for additional information regarding copyright ownership.
    # The ASF licenses this file to You under the Apache License, Version 2.0
    # (the "License"); you may not use this file except in compliance with
    # the License.  You may obtain a copy of the License at
    #
    #    http://www.apache.org/licenses/LICENSE-2.0
    #
    # Unless required by applicable law or agreed to in writing, software
    # distributed under the License is distributed on an "AS IS" BASIS,
    # WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    # See the License for the specific language governing permissions and
    # limitations under the License.
    #
    """The Java of the nanosecond representations' study loops (VARKA-662).

    Runs the formulas of dev/varka_nanos_spike.py on symbols, which record one Java statement an
    operation, and writes a loop method for each loop of a set: Vector API code for the layouts L, M
    and H, a scalar loop for S. A statement nothing reads is dropped, so a loop loads exactly the
    buffers its result depends on. A loop calls no method of the study's own: every step is a Vector
    API call in the loop's own body, since C2 stops inlining a method's callees past a node count
    (NodeCountInliningCutoff, which no product JVM can raise) and a vector that crosses a call is
    boxed.

        python3 dev/varka_nanos_spike_gen.py gen SET DIR    write the loop classes of SET into DIR
        python3 dev/varka_nanos_spike_gen.py check SET DIR  the classes in DIR are what gen writes
        python3 dev/varka_nanos_spike_gen.py table SET      the loops, with the buffers each touches
        python3 dev/varka_nanos_spike_gen.py count SET [OPS]  the loops and the timed cells of a run

    SET is pilot, r12 or study; `check any DIR` passes where the classes are those of one
    of the sets, which is what dev/varka_scoped_suites.sh asks in CI, whatever set is committed. OPS
    is a list of operations with commas, as the benchmark's -Dvarka.nanos.ops takes it: the run
    times ROUND and those; with no OPS, every operation.
    """

    import hashlib
    import importlib
    import os
    import re
    import sys

    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    spike = importlib.import_module("varka_nanos_spike")
    FORMS = spike.FORMS

    VECTOR = {"L": "LongVector", "I": "IntVector", "H": "ShortVector"}
    # The stored element -> (the lanes it loads into, its bytes, its layout in a scalar loop).
    STORED = {"long": ("L", 8, "JL"), "int": ("I", 4, "JI"), "short": ("H", 2, "JS")}
    # (from, to) lanes -> the conversion; a lane group is part 0 of either vector.
    CONVERT = {
        ("I", "L"): "I2L",
        ("H", "L"): "S2L",
        ("H", "I"): "S2I",
        ("L", "I"): "L2I",
        ("L", "H"): "L2S",
        ("I", "H"): "I2S",
    }
    JAVA_TYPE = {
        "L": "long",
        "I": "int",
        "H": "short",
        "KL": "boolean",
        "KI": "boolean",
        "KH": "boolean",
    }


    def lit(c, kind):
        if kind == "L":
            return "0x%016XL" % (c & ((1 << 64) - 1)) if not -(1 << 53) < c < (1 << 53) else "%dL" % c
        assert -(1 << 31) <= c < (1 << 31), c
        return "%d" % c


    class SymRun:
        """One evaluation of a loop body on symbols, which records Java statements."""

        symbolic = True

        def __init__(self, scalar):
            self.scalar = scalar
            self.stmts = []  # (name, kind, java text, the names it reads)
            self.guards = []  # the lane values whose sign declines the batch
            self.loads = {}  # (field, kind) -> symbol
            self.count = 0

        def emit(self, kind, text, reads):
            self.count += 1
            name = "t%d" % self.count
            self.stmts.append((name, kind, text, tuple(reads)))
            return Sym(self, name, kind)

        def const(self, kind, value):
            if self.scalar:
                return self.emit(kind, lit(value, kind), ())
            text = (
                "%s.zero(%sS)" % (VECTOR[kind], kind)
                if value == 0
                else "%s.broadcast(%sS, %s)" % (VECTOR[kind], kind, lit(value, kind))
            )
            return self.emit(kind, text, ())

        def load(self, field, stored, kind, value=None):
            if (field, kind) not in self.loads:
                lanes, width, layout = STORED[stored]
                if self.scalar:
                    text = "%s.get(%s, r * %dL)" % (field, layout, width)
                    sym = self.emit(kind, "(int) " + text if stored == "short" else text, ())
                else:
                    if (field, lanes) not in self.loads:
                        self.loads[(field, lanes)] = self.emit(
                            lanes,
                            "%s.fromMemorySegment(%sS, %s, r * %dL, LE)"
                            % (VECTOR[lanes], lanes, field, width),
                            (),
                        )
                    sym = self.loads[(field, lanes)].to(kind)
                self.loads[(field, kind)] = sym
            return self.loads[(field, kind)]

        def guard(self, lane, outside):
            self.guards.append(lane)

        def select(self, k, t, f):
            if self.scalar:
                return self.emit(
                    t.kind, "%s ? %s : %s" % (k.name, t.name, f.name), (k.name, t.name, f.name)
                )
            mask = k.as_kind(t.kind)
            return self.emit(
                t.kind, "%s.blend(%s, %s)" % (f.name, t.name, mask.name), (mask.name, t.name, f.name)
            )


    class Sym:
        """A lane's symbol: each method records one Java statement."""

        VEC = {"add": "add", "sub": "sub", "mul": "mul", "band": "and", "bor": "or"}
        OPS = {"add": "+", "sub": "-", "mul": "*", "band": "&", "bor": "|", "bxor": "^"}
        CMP = {"lt": ("LT", "<"), "ge": ("GE", ">="), "gt": ("GT", ">"), "eq": ("EQ", "==")}

        def __init__(self, run, name, kind):
            self.run, self.name, self.kind = run, name, kind

        def _arg(self, o):
            return (o.name, (o.name,)) if isinstance(o, Sym) else (lit(o, self.kind), ())

        def _bin(self, op, o):
            arg, reads = self._arg(o)
            if self.run.scalar:
                text = "%s %s %s" % (self.name, self.OPS[op], arg)
            elif op == "bxor":
                text = "%s.lanewise(XOR, %s)" % (self.name, arg)
            else:
                text = "%s.%s(%s)" % (self.name, self.VEC[op], arg)
            return self.run.emit(self.kind, text, (self.name,) + reads)

        def add(self, o):
            return self._bin("add", o)

        def sub(self, o):
            return self._bin("sub", o)

        def mul(self, o):
            return self._bin("mul", o)

        def band(self, o):
            return self._bin("band", o)

        def bor(self, o):
            return self._bin("bor", o)

        def bxor(self, o):
            return self._bin("bxor", o)

        def bnot(self):
            return self.run.emit(
                self.kind, ("~%s" if self.run.scalar else "%s.not()") % self.name, (self.name,)
            )

        def _shift(self, java, vec, k):
            if k == 0:
                return self
            text = (
                "%s %s %d" % (self.name, java, k)
                if self.run.scalar
                else "%s.lanewise(%s, %d)" % (self.name, vec, k)
            )
            return self.run.emit(self.kind, text, (self.name,))

        def shr(self, k):
            return self._shift(">>", "ASHR", k)

        def ushr(self, k):
            return self._shift(">>>", "LSHR", k)

        def shl(self, k):
            return self._shift("<<", "LSHL", k)

        def _cmp(self, op, o):
            arg, reads = self._arg(o)
            text = (
                "%s %s %s" % (self.name, self.CMP[op][1], arg)
                if self.run.scalar
                else "%s.compare(%s, %s)" % (self.name, self.CMP[op][0], arg)
            )
            return self.run.emit("K" + self.kind, text, (self.name,) + reads)

        def lt(self, o):
            return self._cmp("lt", o)

        def ge(self, o):
            return self._cmp("ge", o)

        def gt(self, o):
            return self._cmp("gt", o)

        def eq(self, o):
            return self._cmp("eq", o)

        def as_kind(self, kind):
            """This mask as a mask of `kind` lanes: itself, or a statement that crosses it."""
            lanes = self.kind[1:]
            if self.run.scalar or lanes == kind:
                return self
            if "H" in (kind, lanes):
                # A short vector has the long vector's lane count only at four long lanes and more:
                # at two the mask crosses by its bits.
                text = "SHORT_LANES ? %s.cast(%sS) : VectorMask.fromLong(%sS, %s.toLong())" % (
                    self.name,
                    kind,
                    kind,
                    self.name,
                )
            else:
                text = "%s.cast(%sS)" % (self.name, kind)
            return self.run.emit("K" + kind, text, (self.name,))

        def _logic(self, java, vec, o):
            if self.run.scalar:
                return self.run.emit(
                    self.kind, "%s %s %s" % (self.name, java, o.name), (self.name, o.name)
                )
            other = o.as_kind(self.kind[1:])
            return self.run.emit(
                self.kind, "%s.%s(%s)" % (self.name, vec, other.name), (self.name, other.name)
            )

        def kand(self, o):
            return self._logic("&", "and", o)

        def kor(self, o):
            return self._logic("|", "or", o)

        def _masked(self, java, vec, c, k):
            if self.run.scalar:
                return self.run.emit(
                    self.kind,
                    "%s ? %s %s %s : %s" % (k.name, self.name, java, lit(c, self.kind), self.name),
                    (self.name, k.name),
                )
            mask = k.as_kind(self.kind)
            return self.run.emit(
                self.kind,
                "%s.%s(%s, %s)" % (self.name, vec, lit(c, self.kind), mask.name),
                (self.name, mask.name),
            )

        def addk(self, c, k):
            return self._masked("+", "add", c, k)

        def subk(self, c, k):
            return self._masked("-", "sub", c, k)

        def to(self, kind):
            """This value in lanes of `kind`: a widening or a narrowing conversion."""
            if kind == self.kind:
                return self
            if self.run.scalar:
                text = "(%s) %s" % (JAVA_TYPE[kind], self.name)
            else:
                text = "(%s) %s.convertShape(%s, %sS, 0)" % (
                    VECTOR[kind],
                    self.name,
                    CONVERT[(self.kind, kind)],
                    kind,
                )
            return self.run.emit(kind, text, (self.name,))

        def fdiv(self, d):
            if self.run.scalar:
                return self.run.emit(
                    "L", "Math.floorDiv(%s, %s)" % (self.name, lit(d, "L")), (self.name,)
                )
            return spike.lane_fdiv(self, d)

        def udiv(self, d, umax):
            if self.run.scalar:
                return self.run.emit("L", "%s / %s" % (self.name, lit(d, "L")), (self.name,))
            return spike.lane_udiv(self, d, umax)


    # ---- Java. --------------------------------------------------------------------------------------

    HEADER = """/*
     * Licensed to the Apache Software Foundation (ASF) under one or more
     * contributor license agreements.  See the NOTICE file distributed with
     * this work for additional information regarding copyright ownership.
     * The ASF licenses this file to You under the Apache License, Version 2.0
     * (the "License"); you may not use this file except in compliance with
     * the License.  You may obtain a copy of the License at
     *
     *    http://www.apache.org/licenses/LICENSE-2.0
     *
     * Unless required by applicable law or agreed to in writing, software
     * distributed under the License is distributed on an "AS IS" BASIS,
     * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
     * See the License for the specific language governing permissions and
     * limitations under the License.
     */

    // Generated by dev/varka_nanos_spike_gen.py (VARKA-662). DO NOT EDIT: change the formula in
    // dev/varka_nanos_spike.py and run the generator.

    package org.apache.spark.sql;

    %s%simport java.util.List;

    %simport org.apache.spark.sql.VarkaNanosSpikeOps.Arm;
    %s"""


    def live(run, roots):
        """The statements the roots and the guards depend on, in their order."""
        need = set(roots) | {lane.name for lane in run.guards}
        keep = []
        for name, kind, text, reads in reversed(run.stmts):
            if name in need:
                keep.append((name, kind, text))
                need.update(reads)
        return list(reversed(keep))


    def loop_java(key):
        """(the Java of one loop method, the buffers it reads, the buffers it writes)."""
        op, form, layout, arrival, sink = key
        scalar = layout == "S"
        run = SymRun(scalar)
        kind, result = spike.body(run, op, form, layout, arrival, sink, None)
        # What is stored: (the buffer, its element, the value in the lanes the element loads into).
        if kind == "V":
            stores = [("o" + letter, stored, result[word]) for word, letter, stored in FORMS[sink]]
        elif kind == "W":
            stores, word = [], result.as_kind("L")
        else:
            stores = [("oi", "int", result)] if kind == "I" else [("ol", "long", result)]
        if not scalar:
            stores = [(field, stored, v.to(STORED[stored][0])) for field, stored, v in stores]
        stmts = live(run, [word.name] if kind == "W" else [v.name for _, _, v in stores])
        kept = {name for name, _, _ in stmts}
        reads = sorted({field for (field, _), sym in run.loads.items() if sym.name in kept})
        ind = "        " if kind == "W" else "      "
        lines = [
            "%s%s %s = %s;" % (ind, JAVA_TYPE[k] if scalar else "var", name, text)
            for name, k, text in stmts
        ]
        # A guard ORs its lane value into an accumulator whose sign is read after the loop: ov for
        # long lanes, ovi for int lanes. A scalar loop ORs it into what it returns.
        accumulators = sorted({lane.kind for lane in run.guards})
        for lane in run.guards:
            acc = "bad" if scalar else {"L": "ov", "I": "ovi"}[lane.kind]
            lines.append(
                "%s%s |= %s;" % (ind, acc, lane.name)
                if scalar
                else "%s%s = %s.or(%s);" % (ind, acc, acc, lane.name)
            )
        names = {"L": "ov", "I": "ovi"}
        pre = (
            []
            if scalar
            else ["    var %s = %s.zero(%sS);" % (names[k], VECTOR[k], k) for k in accumulators]
        )
        post = (
            []
            if scalar
            else [
                "    bad |= %s.reduceLanes(OR) >> %d;" % (names[k], spike.BITS[k] - 1)
                for k in accumulators
            ]
        )
        for field, stored, v in stores:
            _, width, scalar_layout = STORED[stored]
            if scalar:
                cast = "" if JAVA_TYPE[v.kind] == stored else "(%s) " % stored
                lines.append(
                    "%s%s.set(%s, r * %dL, %s%s);" % (ind, field, scalar_layout, width, cast, v.name)
                )
            else:
                lines.append("%s%s.intoMemorySegment(%s, r * %dL, LE);" % (ind, v.name, field, width))
        if kind == "W":
            lines.append(
                "%sword |= %s;"
                % (ind, "(%s ? 1L : 0L) << k" % word.name if scalar else "%s.toLong() << k" % word.name)
            )
        writes = [field for field, _, _ in stores] or ["words"]
        step = "1" if scalar else "L"
        out = [
            "  /** %s in R%d, layout %s: reads %s; writes %s. */"
            % (op, form, layout, ", ".join(reads), ", ".join(writes)),
            "  static long %s(Cols c, int n) {" % spike.loop_name(*key),
        ]
        for field in reads + [w for w in writes if w != "words"]:
            out.append("    MemorySegment %s = c.%s;" % (field, field))
        out.append("    long bad = 0;")
        out += pre
        if kind == "W":
            out += (
                [
                    "    for (int i = 0, w = 0; i < n; i += 64, w++) {",
                    "      long word = 0;",
                    "      for (int k = 0; k < 64; k += %s) {" % step,
                    "        int r = i + k;",
                ]
                + lines
                + ["      }", "      c.words[w] = word;", "    }"]
            )
        else:
            out += ["    for (int r = 0; r < n; r += %s) {" % step] + lines + ["    }"]
        out += post + ["    return bad;", "  }"]
        for line in out:
            assert len(line) <= 100, line
        return "\n".join(out), reads, writes


    def class_java(form, keys):
        name = spike.class_of(form)
        out = [
            "/** The study's loops that compute in R%d, a method a loop. */" % form,
            "final class %s {" % name,
            "",
            "  private %s() {" % name,
            "  }",
            "",
            "  /** The loops of this class, in the order they are timed. */",
            "  static List<Arm> arms() {",
            "    return List.of(",
        ]
        rows = []
        for key in keys:
            op, _, layout, arrival, sink = key
            rows.append(
                '        new Arm("%s", %d, "%s", %d, %d, "%s",\n            %s::%s)'
                % (op, form, layout, arrival, sink, spike.loop_name(*key), name, spike.loop_name(*key))
            )
        out.append(",\n".join(rows) + ");" if rows else "    );")
        if not rows:
            out[-2:] = ["    return List.of();"]
        out.append("  }")
        for key in keys:
            out += ["", loop_java(key)[0]]
        out.append("}")
        text = "\n".join(out) + "\n"
        # Only what the loops name is imported: the linters refuse an unused import.
        operators = sorted(
            set(re.findall(r"\((ASHR|EQ|GE|GT|I2L|I2S|L2I|L2S|LSHL|LSHR|LT|OR|S2I|S2L|XOR)[,)]", text))
        )
        vectors = sorted(set(re.findall(r"\b(IntVector|LongVector|ShortVector|VectorMask)\b", text)))
        return (
            HEADER
            % (
                "".join(
                    "import static jdk.incubator.vector.VectorOperators.%s;\n" % o for o in operators
                )
                + ("import static org.apache.spark.sql.VarkaNanosSpikeOps.*;\n\n" if keys else ""),
                "import java.lang.foreign.MemorySegment;\n" if keys else "",
                "".join("import jdk.incubator.vector.%s;\n" % v for v in vectors)
                + ("\n" if vectors else ""),
                "import org.apache.spark.sql.VarkaNanosSpikeOps.Cols;\n" if keys else "",
            )
            + "\n"
            + text
        )


    def generate(which):
        """{file name: text} of the four loop classes, with the loops of the set `which`."""
        by_form = {form: [] for form in (1, 2, 3, 4)}
        for key in spike.loops(which):
            by_form[key[1]].append(key)
        return {
            spike.class_of(form) + ".java": class_java(form, keys) for form, keys in by_form.items()
        }


    def main(argv):
        if len(argv) not in (2, 3) or argv[0] not in ("gen", "check", "table", "count"):
            print(__doc__)
            return 2
        if argv[0] == "count":
            ops = argv[2].split(",") if len(argv) == 3 else spike.OP_ORDER
            keys = [key for key in spike.loops(argv[1]) if key[0] == "ROUND" or key[0] in ops]
            # A loop is timed at the first rung; ROUND and CHAIN stored as they arrived at two more.
            more = sum(2 for key in keys if key[3] == key[4] and key[0] in ("ROUND", "CHAIN"))
            print(
                "%d loops, %d cells; with the control %d and %d"
                % (len(keys), len(keys) + more, len(keys) + 1, len(keys) + more + 1)
            )
            return 0
        if argv[0] == "table":
            for key in spike.loops(argv[1]):
                _, reads, writes = loop_java(key)
                print("%s\t%s\t%s" % (spike.loop_name(*key), ",".join(reads), ",".join(writes)))
            return 0
        stale = None
        for which in ("pilot",) + tuple(spike.SETS) if argv[1] == "any" else (argv[1],):
            stale = []
            for name, text in sorted(generate(which).items()):
                path = os.path.join(argv[2], name)
                if argv[0] == "gen":
                    with open(path, "w") as out:
                        out.write(text)
                    print("%s  %s" % (hashlib.sha256(text.encode()).hexdigest(), name))
                elif not os.path.exists(path) or open(path).read() != text:
                    stale.append(name)
            if not stale:
                return 0
        print(
            "stale: %s; run gen"
            % (", ".join(stale) if argv[1] != "any" else "the classes are those of no set")
        )
        return 1


    if __name__ == "__main__":
        sys.exit(main(sys.argv[1:]))

### File: dev/varka_nanos_costs.py

    #!/usr/bin/env python3
    #
    # Licensed to the Apache Software Foundation (ASF) under one or more
    # contributor license agreements.  See the NOTICE file distributed with
    # this work for additional information regarding copyright ownership.
    # The ASF licenses this file to You under the Apache License, Version 2.0
    # (the "License"); you may not use this file except in compliance with
    # the License.  You may obtain a copy of the License at
    #
    #    http://www.apache.org/licenses/LICENSE-2.0
    #
    # Unless required by applicable law or agreed to in writing, software
    # distributed under the License is distributed on an "AS IS" BASIS,
    # WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    # See the License for the specific language governing permissions and
    # limitations under the License.
    #
    """The cost table of the nanosecond representations' study, and its decision rule (VARKA-662).

        python3 dev/varka_nanos_costs.py table FILE...   the cost table of results files, to stdout
        python3 dev/varka_nanos_costs.py check FILE...   the committed table is that table
        python3 dev/varka_nanos_costs.py rule TSV        what the rule decides from a cost table
        python3 dev/varka_nanos_costs.py selftest        the parser and the rule on made-up tables

    A FILE is a results file of VarkaNanosRepresentationSpikeBenchmark; its provenance file, named
    with -provenance.txt for -results.txt, gives the host. The table is tab-separated with one header.
    Every time is nanoseconds a row by best time, at the 4096-row rung, where memory is not the cost.

        kind       loop: one timed loop, the measurement. base: a form's ROUND loop stored as it
                   arrived, what the derived rows subtract. cell: an operation's own cost in a form.
                   edge: a conversion. residual: a measured loop beside the sum that predicts it.
        host       the provenance file's cpu line
        width      the vector bits of the run
        form       the form the loop computes in, R1 to R4; for an edge, the form converted to
        layout     L, M, H or S
        arrival    the form the operands arrive in; for an edge, the form converted from
        sink       the form a timestamp result is stored in
        operation  ROUND, LT, PICK, ADD, ADDC, HOUR, SEC, YEAR, TRUNC, MICROS, ZONE, HASH, CHAIN
        ns         loop and base: the loop's time. cell and edge: the loop's time less its base, and
                   0.00 where that is under the spread. residual: the measured loop's time
        base       cell and edge: what was subtracted. residual: the predicted sum. Else -
        limit      cell and edge: how good the subtraction is, exact, reads+ or shape. residual: the
                   measured time over the predicted sum. Else -
        jit        ok, or fallback where the run found a call left in Java or over a byte a row
        alloc      bytes allocated a row
        spread     loop: its average iteration less its best, never under the half millisecond the
                   file rounds to, in nanoseconds a row. Derived rows: the sum over their loops
        flag       cell and edge: `within noise` where the difference is inside the spread, `not
                   additive` where it is negative beyond it. residual: `ok` inside the band, `off`
                   outside it, `no data` where a part is a fallback or not additive. Else -
        source     the results file, and after `#` the operations of its section where it names them

    The measured rows are the loops. A cell, an edge and a residual are derived, and say so by kind.
    """

    import math
    import os
    import re
    import sys

    HEADER = [
        "kind",
        "host",
        "width",
        "form",
        "layout",
        "arrival",
        "sink",
        "operation",
        "ns",
        "base",
        "limit",
        "jit",
        "alloc",
        "spread",
        "flag",
        "source",
    ]
    TABLE = "sql/varka/plans/m9/results/VARKA-662/nanos_representation_costs.tsv"
    VALUE_OPS = ("ROUND", "PICK", "ADD", "ADDC", "TRUNC", "ZONE", "CHAIN")
    MORE_READS = ("PICK", "ADD", "CHAIN")
    TWO_VALUES = ("LT", "PICK", "CHAIN")  # the loops that convert two arriving operands
    BAND = 1.3  # the house's band: a ratio under it is a tie
    WHOLE = ("R1", "R3", "R4")  # the forms that hold every value Spark can hold: all three are built
    ORDER = ("R1", "R3", "R4", "R2")  # a tie keeps this order: its first form is the default
    R2_CLASSES = ("compare", "choice", "interval", "chain")  # where R2 has loops over Spark's parts
    R2_RANGE = "R2 holds 1677 to 2262 alone: a batch with a value outside goes to the row path"
    LAPTOP = "AMD Ryzen AI 9 HX PRO 370"  # its 512-bit block is the project's first reading
    RUNNER = "AMD EPYC 7763"  # its 256-bit block is the project's last reading
    CLASSES = (
        ("compare", ("LT",)),
        ("choice", ("PICK",)),
        ("interval", ("ADD", "ADDC")),
        ("clock", ("HOUR", "SEC")),
        ("date", ("YEAR",)),
        ("truncate", ("TRUNC",)),
        ("zone", ("ZONE",)),
        ("stored", ("MICROS", "HASH")),
        ("chain", ("CHAIN",)),
    )
    LABEL = re.compile(
        r"^([A-Z]+) R([1-4])([LMHS]) ([1-4])>([1-4])( \[Java fallback\])?\s+"
        r"(\d+)\s+(\d+)\s+(\d+)\s+([\d.]+)\s+([\d.]+)\s+[\d.]+X\s*$"
    )
    ALLOC = re.compile(r"^allocated, ([A-Z]+ R[1-4][LMHS] [1-4]>[1-4]) at 4096 rows: ([\d.]+) bytes")
    SECTION = re.compile(r"^nanosecond timestamp hand loops at (\d+) bits(?:: ([A-Z ]+))?$")


    def parse(path):
        """The loop rows of one results file, each section's loops under its own source."""
        provenance = path.replace("-results.txt", "-provenance.txt")
        host = "unknown"
        if os.path.exists(provenance):
            for line in open(provenance):
                if line.startswith("cpu:"):
                    host = line.split(":", 1)[1].strip()
        width, source, rung, rows, alloc = None, None, None, {}, {}
        for line in open(path):
            line = line.rstrip("\n")
            m = SECTION.match(line)
            if m:
                width = m.group(1)
                source = os.path.basename(path) + (
                    "#" + m.group(2).replace(" ", ",") if m.group(2) else ""
                )
            m = re.match(r"^(\d+) rows - ([a-zA-Z0-9 ]+), ([A-Z]+)(, Java fallback)?:", line)
            if m:
                rung = m.group(1)
            m = LABEL.match(line)
            if m and rung == "4096":
                op, form, layout, arrival, sink, fallback = m.group(1, 2, 3, 4, 5, 6)
                best, avg, rate, per_row = (float(m.group(k)) for k in (7, 8, 10, 11))
                # The rate has a decimal of a million rows a second and the time a decimal of a
                # nanosecond, so the rate is the finer reading of a fast loop.
                ns = 1000 / rate if rate >= 31.6 else per_row
                spread = ns * max(avg - best, 0.5) / max(best, 0.5)
                rows[(source, "%s R%s%s %s>%s" % (op, form, layout, arrival, sink))] = {
                    "kind": "loop",
                    "host": host,
                    "width": width,
                    "form": "R" + form,
                    "layout": layout,
                    "arrival": "R" + arrival,
                    "sink": "R" + sink,
                    "operation": op,
                    "ns": ns,
                    "base": None,
                    "limit": "-",
                    "jit": "fallback" if fallback else "ok",
                    "alloc": 0.0,
                    "spread": spread,
                    "flag": "-",
                    "source": source,
                }
            m = ALLOC.match(line)
            if m:
                alloc[(source, m.group(1))] = float(m.group(2))
        if width is None:
            raise SystemExit("%s: no section title, so not the spike's results file" % path)
        for key, row in rows.items():
            row["alloc"] = alloc.get(key, 0.0)
            if row["alloc"] > 1:
                row["jit"] = "fallback"
        return list(rows.values())


    def floored(row, raw, spread):
        """A derived row: its difference, or 0.00 and a flag where the difference is no cost."""
        flag = "-" if raw >= spread else "within noise" if raw > -spread else "not additive"
        return dict(row, ns=raw if flag == "-" else 0.0, spread=spread, flag=flag)


    def derive(loops):
        """The bases, cells, edges and residuals of loop rows."""
        by = {
            (r["source"], r["form"], r["layout"], r["arrival"], r["sink"], r["operation"]): r
            for r in loops
        }
        cells, edges, out = {}, {}, []

        def worst(*rows):
            return "fallback" if any(r["jit"] == "fallback" for r in rows) else "ok"

        for r in loops:
            own = by.get((r["source"], r["form"], r["layout"], r["form"], r["form"], "ROUND"))
            if r["operation"] == "HOURC" or own is None:
                continue
            key = (r["source"], r["form"], r["layout"], r["operation"])
            if r is own:
                out.append(dict(r, kind="base"))
            elif r["arrival"] == r["form"] == r["sink"]:
                value = r["operation"] in VALUE_OPS
                share = 1.0 if value else 0.5
                limit = "reads+" if r["operation"] in MORE_READS else "exact" if value else "shape"
                cells[key] = floored(
                    dict(r, kind="cell", base=share * own["ns"], limit=limit, jit=worst(r, own)),
                    r["ns"] - share * own["ns"],
                    r["spread"] + share * own["spread"],
                )
            elif r["operation"] == "ROUND" and r["sink"] == r["form"]:
                source = by.get(
                    (r["source"], r["arrival"], r["layout"], r["arrival"], r["arrival"], "ROUND")
                )
                if source is not None:
                    base = (own["ns"] + source["ns"]) / 2
                    edges[(r["source"], r["arrival"], r["form"], r["layout"])] = floored(
                        dict(r, kind="edge", base=base, limit="shape", jit=worst(r, own, source)),
                        r["ns"] - base,
                        r["spread"] + (own["spread"] + source["spread"]) / 2,
                    )
        out += list(cells.values()) + list(edges.values())
        # A residual: a loop that arrives in one form, computes in another and stores as it arrived,
        # beside the base, the edges and the cell that predict it.
        for r in loops:
            if r["arrival"] == r["form"] or r["sink"] != r["arrival"] or r["operation"] == "HOURC":
                continue
            src, lay, a, x, op = r["source"], r["layout"], r["arrival"], r["form"], r["operation"]
            base_a, base_x = by.get((src, a, lay, a, a, "ROUND")), by.get((src, x, lay, x, x, "ROUND"))
            parts = [base_a, base_x, edges.get((src, a, x, lay))]
            value = op in VALUE_OPS
            parts += [edges.get((src, x, a, lay))] if value else []
            parts += [cells.get((src, x, lay, op))] if op != "ROUND" else []
            row = dict(r, kind="residual", base=None, limit="-", flag="no data")
            if (
                None not in parts
                and worst(r, *parts) == "ok"
                and all(p["flag"] != "not additive" for p in parts)
            ):
                operands = 2 if op in TWO_VALUES else 1
                predicted = (
                    (base_a["ns"] if value else base_a["ns"] / 2)
                    + operands * parts[2]["ns"]
                    + (operands - 1) * (base_a["ns"] - base_x["ns"]) / 2
                    + sum(p["ns"] for p in parts[3:])
                )
                ratio = r["ns"] / predicted if predicted > 0 else float("inf")
                row = dict(
                    row,
                    base=predicted,
                    limit="x%.2f" % ratio,
                    flag="ok" if 1 / BAND < ratio < BAND else "off",
                )
            out.append(row)
        return out


    def render(rows):
        def cell(r, k):
            v = r[k]
            return "-" if v is None else ("%.3f" % v) if isinstance(v, float) else str(v)

        order = {"loop": 0, "base": 1, "cell": 2, "edge": 3, "residual": 4}
        rows = sorted(
            rows,
            key=lambda r: (
                r["host"],
                int(r["width"]),
                order[r["kind"]],
                r["form"],
                r["layout"],
                r["arrival"],
                r["sink"],
                r["operation"],
                r["source"],
            ),
        )
        return (
            "\t".join(HEADER)
            + "\n"
            + "".join("\t".join(cell(r, k) for k in HEADER) + "\n" for r in rows)
        )


    def read_table(text):
        lines = text.splitlines()
        if lines[0].split("\t") != HEADER:
            raise SystemExit("not a cost table: its header is not\n" + "\t".join(HEADER))
        rows = []
        for line in lines[1:]:
            r = dict(zip(HEADER, line.split("\t")))
            for k in ("ns", "alloc", "spread"):
                r[k] = float(r[k])
            rows.append(r)
        return rows


    # ---- The rule. ----------------------------------------------------------------------------------


    def class_times(rows, host, width, arrival):
        """{class: {"vector": {form: (ns, layout)}, "scalar": the same, "seen": the forms with a loop
        of every operation of the class in some layout, fallback or not}}. A form's vector time is
        its best vector layout whose loops are all `ok`; a class of two loops takes their geometric
        mean. A fallback's time is never read."""
        loops, seen = {}, set()
        for r in rows:
            if (
                r["kind"] == "loop"
                and (r["host"], r["width"], r["arrival"]) == (host, width, arrival)
                and r["sink"] == arrival
            ):
                seen.add((r["form"], r["layout"], r["operation"]))
                if r["jit"] == "ok":
                    loops[(r["form"], r["layout"], r["operation"])] = r["ns"]
        out = {}
        for name, ops in CLASSES:
            vector, scalar, measured = {}, {}, set()
            for form in ORDER:
                for layout in "LMHS":
                    if all((form, layout, op) in seen for op in ops):
                        measured.add(form)
                    times = [loops.get((form, layout, op)) for op in ops]
                    if None in times:
                        continue
                    mean = math.exp(sum(math.log(t) for t in times) / len(times))
                    side = scalar if layout == "S" else vector
                    if form not in side or mean < side[form][0]:
                        side[form] = (mean, layout)
            out[name] = {"vector": vector, "scalar": scalar, "seen": measured}
        return out


    def pick(part, form):
        """A form's reading in a class, (ns, layout): its best vector layout, or its scalar loop
        where no vector layout has a time or the best takes BAND times the scalar loop's time or
        more. None where the form has neither."""
        vector, scalar = part["vector"].get(form), part["scalar"].get(form)
        if vector and (not scalar or vector[0] / scalar[0] < BAND):
            return vector
        return scalar


    def first_of_tie(have, forms):
        """The default among `forms`: the first, in ORDER, of those within the band of the fastest."""
        forms = [f for f in ORDER if f in forms and f in have]
        best = min(have[f][0] for f in forms)
        tied = [f for f in forms if have[f][0] / best < BAND]
        return tied[0], tied


    def judge(parts):
        """One host and width: {class: its default as form and layout, or None where undecided} and
        the text lines. Every form of WHOLE is a candidate in every class, and R2 in R2_CLASSES."""
        chosen, lines = {}, []
        for name, _ in CLASSES:
            part = parts[name]
            forms = WHOLE + (("R2",) if name in R2_CLASSES else ())
            have = {f: pick(part, f) for f in forms if pick(part, f)}
            missing = [f for f in forms if f not in have]
            times = ", ".join("%s%s %.3f" % (f, have[f][1], have[f][0]) for f in have)
            chosen[name] = None
            if not part["seen"].intersection(forms):
                lines.append("default   %-9s undecided: no loop of the class in the table" % name)
                continue
            if missing:
                lines.append(
                    "default   %-9s undecided: no data for %s%s"
                    % (name, ", ".join(missing), "; " + times if times else "")
                )
                continue
            form, tied = first_of_tie(have, forms)
            ns, layout = have[form]
            chosen[name] = form + layout
            line = "default   %-9s %s%s %.3f%s%s; %s" % (
                name,
                form,
                layout,
                ns,
                ", a scalar body," if layout == "S" else "",
                " wins" if len(tied) == 1 else " first of a tie of " + ", ".join(tied),
                ", ".join(
                    "%s%s %.3f (%.2f times)" % (f, have[f][1], have[f][0], have[f][0] / ns)
                    for f in have
                    if f != form
                ),
            )
            if form == "R2":
                whole = first_of_tie(have, WHOLE)[0]
                line += "; %s; of the forms that hold every value: %s%s" % (
                    R2_RANGE,
                    whole,
                    have[whole][1],
                )
            lines.append(line)
            for f in have:
                vector = part["vector"].get(f)
                if have[f][1] == "S":
                    lines.append(
                        "scalar    %-9s %sS stands for %s: %s"
                        % (
                            name,
                            f,
                            f,
                            "its best vector loop (%s%s) takes %.2f times its time"
                            % (f, vector[1], vector[0] / have[f][0])
                            if vector
                            else "no vector loop without a fallback",
                        )
                    )
        return chosen, lines


    def arriving(rows, host, width):
        """Data that arrives as epoch nanoseconds, for the compare and the chain: R2, which computes
        on it as it is, against the best whole-range form, which converts first."""
        mirror, lines = class_times(rows, host, width, "R2"), []
        for name in ("compare", "chain"):
            have = {f: pick(mirror[name], f) for f in ORDER if pick(mirror[name], f)}
            others = [have[f][0] for f in WHOLE if f in have]
            if "R2" in have and others:
                ratio = min(others) / have["R2"][0]
                lines.append(
                    "arriving  %-9s as epoch nanoseconds: R2%s is %.2f times the best other%s"
                    % (name, have["R2"][1], ratio, ": it wins" if ratio >= BAND else "")
                )
            elif mirror[name]["seen"]:
                lines.append("arriving  %-9s as epoch nanoseconds: no data" % name)
        return lines


    def rule(rows):
        """The rule's text for a cost table: each host and width's defaults, then the project's."""
        keys = sorted({(r["host"], r["width"]) for r in rows}, key=lambda k: (k[0], int(k[1])))
        out, chosen = [], {}
        for host, width in keys:
            chosen[(host, width)], lines = judge(class_times(rows, host, width, "R1"))
            out.append("%s at %s bits" % (host, width))
            out += ["  " + line for line in lines + arriving(rows, host, width)]
        # AVX-512 first: the laptop's 512-bit block, then another host's, then the runner at 256.
        first = sorted((k for k in keys if k[1] == "512"), key=lambda k: not k[0].startswith(LAPTOP))
        first += [k for k in keys if k[0].startswith(RUNNER) and k[1] == "256"]
        if not first:
            out.append(
                "the project's defaults: none, for want of a reading at 512 bits or of the %s at "
                "256 bits" % RUNNER
            )
            return "\n".join(out) + "\n"
        out.append(
            "the project's defaults, each from the first of these readings that has one: "
            + "; ".join("%s at %s bits" % k for k in first)
        )
        count = dict.fromkeys(ORDER, 0)
        for name, _ in CLASSES:
            got = next(((chosen[k][name], k) for k in first if chosen[k][name]), None)
            if got:
                count[got[0][:2]] += 1
                out.append("  project   %-9s %s (%s at %s bits)" % (name, got[0], got[1][0], got[1][1]))
            else:
                out.append("  project   %-9s undecided: no reading of the order has a default" % name)
        later = sorted(("R3", "R4"), key=lambda f: -count[f])
        out.append(
            "  order     R1 (the storage; %d classes), %s"
            % (count["R1"], ", ".join("%s (%d)" % (f, count[f]) for f in later))
        )
        r2 = [
            "%s on %s at %s bits" % (name, k[0], k[1])
            for k in keys
            for name, _ in CLASSES
            if (chosen[k][name] or "")[:2] == "R2"
        ]
        out.append(
            "  r2        "
            + ("a default, behind its batch guard: " + "; ".join(r2) if r2 else "nowhere a default")
        )
        return "\n".join(out) + "\n"


    # ---- selftest: a made-up results file, and the rule on made-up tables. --------------------------

    HOST = "AMD EPYC 7763 (made up)"
    WIDE = "AMD Ryzen AI 9 HX PRO 370 (made up)"
    XEON = "Intel Xeon Platinum 8573C (made up)"
    BOTH = (("L", 1.0), ("S", 4.0))


    def made_up(times, mirror=None, fallback=(), width="256", layouts=BOTH, host=HOST):
        """Loop rows of one host: {form: {operation: ns}} for layouts L and S (S at four times L),
        or for `layouts`, arriving and stored as R1; `mirror` the same for data arriving as R2."""
        rows = []
        for arrival, table in (("R1", times), ("R2", mirror or {})):
            for form, ops in table.items():
                for op, ns in ops.items():
                    for layout, factor in layouts:
                        rows.append(
                            {
                                "kind": "loop",
                                "host": host,
                                "width": width,
                                "form": form,
                                "layout": layout,
                                "arrival": arrival,
                                "sink": arrival,
                                "operation": op,
                                "ns": ns * factor,
                                "base": None,
                                "limit": "-",
                                "jit": "fallback" if (form, layout, op) in fallback else "ok",
                                "alloc": 0.0,
                                "spread": 0.0,
                                "flag": "-",
                                "source": "made up",
                            }
                        )
        return rows


    def flat(ns, **changes):
        ops = {op: ns for _, group in CLASSES for op in group}
        ops["ROUND"] = 0.2
        ops.update(changes)
        return ops


    def every(forms, layouts="L", ops=None):
        """Every loop of the forms in the layouts (of `ops`, or of every class), as fallbacks."""
        ops = ops or [op for _, group in CLASSES for op in group]
        return {(f, layout, op) for f in forms for layout in layouts for op in ops}


    def without(ops, *gone):
        return {op: ns for op, ns in ops.items() if op not in gone}


    R1 = flat(2.0)
    BASE = {"R1": R1, "R3": flat(3.0), "R4": flat(3.0), "R2": flat(3.0)}
    R2_TEXT = "; R2 holds 1677 to 2262 alone: a batch with a value outside goes to the row path; of "
    # name -> (rows, {a class: its default at HOST and 256 bits; another key: a text the output holds,
    # or, under a key that starts with `no`, a text it must not hold}).
    CASES = {
        "1": (
            made_up(BASE),
            {
                "clock": "R1L",
                "compare": "R1L",
                "a": "default   compare   R1L 2.000 wins; R3L 3.000 (1.50 times), R4L 3.000 "
                "(1.50 times), R2L 3.000 (1.50 times)",
                "b": "clock     R1L 2.000 wins; R3L 3.000 (1.50 times), R4L 3.000 (1.50 times)\n",
                "c": "  project   clock     R1L (%s at 256 bits)" % HOST,
                "d": "  order     R1 (the storage; 9 classes), R3 (0), R4 (0)",
                "e": "  r2        nowhere a default",
            },
        ),
        "2": (
            made_up(
                {"R1": R1, "R3": flat(2.2, HOUR=1.25, SEC=1.25), "R4": flat(3.0, HOUR=2.0, SEC=2.0)}
            ),
            {"clock": "R3L", "date": "R1L", "a": "date      R1L 2.000 first of a tie of R1, R3; "},
        ),
        "3": (
            made_up(
                {
                    "R1": R1,
                    "R2": flat(3.0),
                    "R3": flat(2.2, HOUR=1.8, SEC=1.8),
                    "R4": flat(3.0, HOUR=0.5, SEC=0.6, YEAR=1.0, TRUNC=0.8),
                }
            ),
            {
                "clock": "R4L",
                "date": "R4L",
                "truncate": "R4L",
                "compare": "R1L",
                "a": "  order     R1 (the storage; 6 classes), R4 (3), R3 (0)",
            },
        ),
        "4": (
            made_up(
                dict(BASE, R2=flat(2.4)),
                {
                    "R1": {"LT": 3.0, "CHAIN": 6.0, "ROUND": 1.5},
                    "R2": {"LT": 1.0, "CHAIN": 5.5, "ROUND": 0.2},
                    "R3": {"LT": 3.5, "CHAIN": 7.0, "ROUND": 2.0},
                },
            ),
            {
                "compare": "R1L",
                "a": "compare   R1L 2.000 first of a tie of R1, R2; ",
                "b": "compare   as epoch nanoseconds: R2L is 3.00 times the best other: it wins\n",
            },
        ),
        "5": (
            made_up(
                {
                    "R1": R1,
                    "R3": flat(2.2, HOUR=1.4, SEC=1.4, YEAR=1.1),
                    "R4": flat(3.0, HOUR=2.0, SEC=2.0, YEAR=1.0),
                }
            ),
            {"clock": "R3L", "date": "R3L", "a": "date      R3L 1.100 first of a tie of R3, R4; "},
        ),
        "6": (
            made_up(
                dict(BASE, R4=flat(3.0, HOUR=1.0, SEC=1.0)),
                fallback={("R4", "L", "HOUR"), ("R4", "L", "SEC")},
            ),
            {
                "clock": "R1L",
                "a": "clock     R1L 2.000 wins; R3L 3.000 (1.50 times), R4S 4.000 (2.00 times)",
                "b": "scalar    clock     R4S stands for R4: no vector loop without a fallback",
            },
        ),
        "7": (
            made_up({"R1": R1, "R3": flat(2.5), "R4": flat(2.4)}),
            {
                "clock": "R1L",
                "compare": None,
                "a": "clock     R1L 2.000 first of a tie of R1, R3, R4; ",
                "b": "compare   undecided: no data for R2; R1L 2.000, R3L 2.500, R4L 2.400\n",
            },
        ),
        "8": (
            made_up(dict(BASE, R2=flat(3.0, LT=1.0, PICK=1.6))),
            {
                "compare": "R2L",
                "choice": "R1L",
                "a": "(3.00 times)" + R2_TEXT + "the forms that hold every value: R1L\n",
                "b": "  r2        a default, behind its batch guard: compare on %s at 256 bits\n"
                % HOST,
                "no": "choice    R1L 2.000 first of a tie of R1, R2; R3L 3.000 (1.50 times), R4L 3.000 "
                "(1.50 times), R2L 1.600 (0.80 times); R2 holds",
            },
        ),
        "A": (
            made_up(dict(BASE, R3=flat(2.2, HOUR=1.33, SEC=1.33, LT=2.8))),
            {"clock": "R3L", "compare": "R1L", "date": "R1L", "a": "compare   R1L 2.000 wins; "},
        ),
        "B2": (
            made_up({"R1": R1, "R3": flat(1.6), "R4": flat(3.5), "R2": flat(3.5)}),
            {"clock": "R1L", "compare": "R1L"},
        ),
        # A fallback is no time: the form's scalar loop stands for it, or it has no data.
        "C": (
            made_up(
                dict(BASE, R3=flat(1.0, ADD=0.5, ADDC=0.5)),
                fallback={("R3", "L", "ADD"), ("R3", "L", "ADDC")},
            ),
            {
                "interval": "R1L",
                "clock": "R3L",
                "a": "interval  R1L 2.000 first of a tie of R1, R3; R3S 2.000 (1.00 times), ",
            },
        ),
        "C2": (
            made_up({"R1": R1, "R3": flat(0.5), "R4": flat(0.5)}, fallback=every(("R3", "R4"))),
            {"clock": "R1L", "a": "clock     R1L 2.000 first of a tie of R1, R3, R4; R3S 2.000"},
        ),
        "C3": (
            made_up(dict(BASE, R3=flat(1.0)), fallback={("R3", "L", "ADDC")}),
            {"interval": "R1L", "a": "interval  R1L 2.000 wins; R3S 4.000 (2.00 times), "},
        ),
        "N": (
            made_up(dict(BASE, R3=flat(1.0)), fallback=every(("R3",), "LS", ("HOUR",))),
            {
                "clock": None,
                "a": "default   clock     undecided: no data for R3; R1L 2.000, R4L 3.000\n",
            },
        ),
        "R": (
            made_up(dict(BASE, R2=flat(3.0, LT=1.0)), fallback=every(("R4",), "LS", ("LT",))),
            {
                "compare": None,
                "a": "compare   undecided: no data for R4; R1L 2.000, R3L 3.000, R2L 1.000\n",
                "b": "  project   compare   undecided: no reading of the order has a default",
            },
        ),
        "Z": (
            made_up({f: without(ops, "ZONE") for f, ops in BASE.items()}),
            {
                "clock": "R1L",
                "zone": None,
                "a": "default   zone      undecided: no loop of the class in the table",
            },
        ),
        # Each width has its own default.
        "D": (
            made_up(dict(BASE, R4=flat(3.0, HOUR=0.5, SEC=0.5)))
            + made_up(dict(BASE, R3=flat(3.0, HOUR=0.5, SEC=0.5)), width="128"),
            {"clock": "R4L", "a": "default   clock     R3L 0.500 wins"},
        ),
        "E": (
            made_up(
                dict(BASE, R2=flat(2.2)),
                {
                    "R1": {"LT": 3.0, "CHAIN": 6.0, "HOUR": 3.0, "ROUND": 1.5},
                    "R2": {"LT": 1.0, "CHAIN": 1.5, "HOUR": 1.0, "ROUND": 0.2},
                    "R3": {"LT": 3.5, "CHAIN": 7.0, "HOUR": 3.0, "ROUND": 2.0},
                },
            ),
            {
                "compare": "R1L",
                "a": "chain     as epoch nanoseconds: R2L is 4.00 times the best other: it wins\n",
            },
        ),
        # R2 is a candidate where it has loops over Spark's parts, and nowhere else.
        "F": (
            made_up(
                {
                    "R1": R1,
                    "R3": flat(2.2, HOUR=1.0, SEC=1.0),
                    "R2": flat(2.2, HOUR=1.1, SEC=1.1),
                    "R4": flat(3.0),
                }
            ),
            {"clock": "R3L", "a": "clock     R3L 1.000 wins; R1L 2.000 (2.00 times), R4L 3.000"},
        ),
        "Q": (made_up(dict(BASE, R2=flat(3.0, HOUR=0.5, SEC=0.5))), {"clock": "R1L"}),
        # Every vector layout is read.
        "G": (
            made_up(BASE) + made_up({"R1": flat(1.0)}, layouts=(("H", 1.0),)),
            {"clock": "R1H", "compare": "R1H"},
        ),
        "H": (
            made_up(BASE)
            + made_up({"R1": flat(1.0)}, fallback={("R1", "H", "SEC")}, layouts=(("H", 1.0),)),
            {"clock": "R1L", "date": "R1H"},
        ),
        "M": (
            made_up(BASE) + made_up({"R3": flat(3.0, HOUR=1.0, SEC=1.0)}, layouts=(("M", 1.0),)),
            {"clock": "R3M", "date": "R1L"},
        ),
        # A scalar body: where no vector loop has a time, and where the vector loop is the slower.
        "S": (
            made_up(dict(BASE, R2=flat(3.0, LT=1.0)), fallback=every(ORDER, "L", ("LT",))),
            {
                "compare": "R2S",
                "clock": "R1L",
                "a": "compare   R2S 4.000, a scalar body, wins; R1S 8.000 (2.00 times), ",
                "b": "scalar    compare   R1S stands for R1: no vector loop without a fallback",
                "c": "the forms that hold every value: R1S\n",
            },
        ),
        "S2": (
            made_up(BASE, layouts=(("L", 1.0), ("S", 0.7))),
            {
                "clock": "R1S",
                "a": "clock     R1S 1.400, a scalar body, wins; R3S 2.100 (1.50 times), ",
                "b": "scalar    clock     R1S stands for R1: its best vector loop (R1L) takes 1.43 "
                "times its time",
            },
        ),
        "S3": (made_up(BASE, layouts=(("L", 1.0), ("S", 0.8))), {"clock": "R1L", "no": "scalar  "}),
        # The project's defaults: AVX-512 first, the laptop before another host, then the runner.
        "P1": (
            made_up(BASE)
            + made_up(dict(BASE, R4=flat(1.0)), width="512", host=XEON)
            + made_up(dict(BASE, R3=flat(1.0)), width="512", host=WIDE),
            {
                "clock": "R1L",
                "a": "one: %s at 512 bits; %s at 512 bits; %s at 256 bits\n" % (WIDE, XEON, HOST),
                "b": "  project   clock     R3L (%s at 512 bits)" % WIDE,
                "c": "  order     R1 (the storage; 0 classes), R3 (9), R4 (0)",
            },
        ),
        "P2": (
            made_up(dict(BASE, R4=flat(1.0)))
            + made_up(
                dict(BASE, R3=flat(1.0)),
                fallback=every(("R3",), "LS", ("HOUR",)),
                width="512",
                host=WIDE,
            ),
            {
                "clock": "R4L",
                "a": "  project   clock     R4L (%s at 256 bits)" % HOST,
                "b": "  project   date      R3L (%s at 512 bits)" % WIDE,
                "c": "  order     R1 (the storage; 0 classes), R3 (8), R4 (1)",
            },
        ),
        "P3": (
            made_up(BASE, width="128"),
            {"a": "the project's defaults: none, for want of a reading at 512 bits or of the AMD"},
        ),
    }


    def sample():
        """A results file of two sections and two rungs, in the layout Spark's Benchmark prints."""
        columns = "Best Time(ms)   Avg Time(ms)   Stdev(ms)    Rate(M/s)   Per Row(ns)   Relative"
        lines = []

        def table(title, rows):
            lines.append("%-41s %s" % (title + ":", columns))
            for label, per_row, avg in rows:
                best = per_row * 67.108864  # 2^26 rows an iteration, in ms
                lines.append(
                    "%-40s %14s %14s %11s %12s %13s %10s"
                    % (
                        label,
                        "%5.0f" % best,
                        "%4.0f" % (best * avg),
                        "%5.0f" % 0,
                        "%10.1f" % (1000 / per_row),
                        "%6.1f" % per_row,
                        "%3.1fX" % 1.0,
                    )
                )

        lines += [
            "nanosecond timestamp hand loops at 256 bits: HOUR",
            "intrinsic check, HOUR R1L 1>1: every call intrinsified",
        ]
        table(
            "4096 rows - in L2, HOUR",
            [
                ("HOUR R1L 1>1", 2.5, 1.0),
                ("HOUR R4S 4>4", 1.5, 1.0),
                ("HOUR R4L 4>4", 0.3, 1.0),
                ("HOUR R4L 1>1", 2.35, 1.0),
            ],
        )
        table("4096 rows - in L2, HOUR, Java fallback", [("HOUR R4M 4>4 [Java fallback]", 100.0, 1.0)])
        table(
            "4096 rows - in L2, ROUND",
            [
                ("ROUND R1L 1>1", 0.25, 1.0),
                ("ROUND R4L 4>4", 0.4, 1.2),
                ("ROUND R4L 1>4", 2.0, 1.0),
                ("ROUND R1L 4>1", 0.33, 1.0),
            ],
        )
        lines += [
            "allocated, HOUR R1L 1>1 at 4096 rows: 0.0 bytes a row",
            "allocated, HOUR R4S 4>4 at 4096 rows: 3.0 bytes a row",
        ]
        table("262144 rows - in L3, ROUND", [("ROUND R1L 1>1", 9.0, 1.0)])
        return "\n".join(lines) + "\n"


    def selftest():
        import tempfile

        with tempfile.TemporaryDirectory() as tmp:
            path = os.path.join(tmp, "X-results.txt")
            with open(path, "w") as out:
                out.write(sample())
            with open(path.replace("-results.txt", "-provenance.txt"), "w") as out:
                out.write("cpu:         AMD EPYC 7763 64-Core Processor\n")
            loops = parse(path)
        rows = loops + derive(loops)
        got = {(r["kind"], r["operation"], r["form"] + r["layout"], r["arrival"]): r for r in rows}
        checks = [
            (len(loops), 9, "nine loop rows of the first rung, none of the second"),
            (got[("loop", "HOUR", "R1L", "R1")]["host"], "AMD EPYC 7763 64-Core Processor", "host"),
            (got[("loop", "HOUR", "R1L", "R1")]["source"], "X-results.txt#HOUR", "a section's source"),
            ("%.3f" % got[("loop", "ROUND", "R1L", "R1")]["ns"], "0.250", "a fast loop by its rate"),
            (got[("loop", "HOUR", "R4M", "R4")]["jit"], "fallback", "a labelled fallback"),
            (got[("loop", "HOUR", "R4S", "R4")]["jit"], "fallback", "a loop over a byte a row"),
            (got[("base", "ROUND", "R1L", "R1")]["kind"], "base", "the base has a row of its own"),
            ("%.3f" % got[("cell", "HOUR", "R1L", "R1")]["ns"], "2.375", "a cell: its loop less half"),
            (got[("cell", "HOUR", "R1L", "R1")]["limit"], "shape", "an int result's limit"),
            ("%.3f" % got[("edge", "ROUND", "R4L", "R1")]["ns"], "1.675", "an edge less both halves"),
            (got[("edge", "ROUND", "R1L", "R4")]["flag"], "within noise", "an edge inside the spread"),
            ("%.2f" % got[("edge", "ROUND", "R1L", "R4")]["ns"], "0.00", "and floored, not negative"),
            (got[("cell", "HOUR", "R4L", "R4")]["flag"], "-", "a cell beyond the spread"),
            (got[("residual", "HOUR", "R4L", "R1")]["flag"], "ok", "a residual inside the band"),
            (got[("residual", "HOUR", "R4L", "R1")]["limit"], "x1.24", "its ratio"),
            (read_table(render(rows))[0]["kind"], "loop", "the table reads back"),
        ]
        not_additive = floored({"kind": "cell"}, -0.5, 0.1)
        checks.append(
            (
                (not_additive["ns"], not_additive["flag"]),
                (0.0, "not additive"),
                "a cost negative beyond the spread",
            )
        )
        for name, (table, wanted) in CASES.items():
            text = rule(table)
            lines = text.splitlines()
            at = lines.index(HOST + " at 256 bits") + 1 if HOST + " at 256 bits" in lines else 0
            block = lines[at:]  # from the defaults of the runner at 256 bits on
            for key, want in wanted.items():
                if key in dict(CLASSES):
                    found = [l.split()[2] for l in block if l.startswith("  default   %-9s " % key)]
                    checks.append((found[0].replace("undecided:", ""), want or "", name + ": " + key))
                else:
                    checks.append((want in text, not key.startswith("no"), name + ": " + want))
        failed = [(want, got_, what) for got_, want, what in checks if got_ != want]
        for want, got_, what in failed:
            print("selftest: %s: got %r, want %r" % (what, got_, want))
        print("selftest: %d checks, %d failed" % (len(checks), len(failed)))
        return 1 if failed else 0


    def main(argv):
        if argv[:1] == ["selftest"]:
            return selftest()
        if argv[:1] == ["rule"] and len(argv) == 2:
            sys.stdout.write(rule(read_table(open(argv[1]).read())))
            return 0
        if argv[:1] in (["table"], ["check"]) and len(argv) > 1:
            loops = [r for path in argv[1:] for r in parse(path)]
            text = render(loops + derive(loops))
            if argv[0] == "table":
                sys.stdout.write(text)
                return 0
            if not os.path.exists(TABLE) or open(TABLE).read() != text:
                print("%s is not the table of these results files; write it with `table`" % TABLE)
                return 1
            return 0
        print(__doc__)
        return 2


    if __name__ == "__main__":
        sys.exit(main(sys.argv[1:]))

### File: VarkaNanosSpikeOps.java

    /*
     * Licensed to the Apache Software Foundation (ASF) under one or more
     * contributor license agreements.  See the NOTICE file distributed with
     * this work for additional information regarding copyright ownership.
     * The ASF licenses this file to You under the Apache License, Version 2.0
     * (the "License"); you may not use this file except in compliance with
     * the License.  You may obtain a copy of the License at
     *
     *    http://www.apache.org/licenses/LICENSE-2.0
     *
     * Unless required by applicable law or agreed to in writing, software
     * distributed under the License is distributed on an "AS IS" BASIS,
     * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
     * See the License for the specific language governing permissions and
     * limitations under the License.
     */

    package org.apache.spark.sql;

    import java.io.Serializable;
    import java.lang.foreign.MemorySegment;
    import java.lang.foreign.ValueLayout;
    import java.nio.ByteOrder;

    import jdk.incubator.vector.LongVector;
    import jdk.incubator.vector.VectorShape;
    import jdk.incubator.vector.VectorSpecies;

    /**
     * What every loop of the nanosecond representations' study shares (VARKA-662): the species, the
     * byte order and the layouts of a row, the buffers of a batch, and what a loop is. The loops are
     * generated by {@code dev/varka_nanos_spike_gen.py}.
     *
     * <p>One species an element type, in every JVM that loads this class: the preferred long species,
     * the int species of as many lanes (half its bits), and a short species of as many lanes (a
     * quarter of its bits) or, at two long lanes, of four, since the Vector API has no vector of 32
     * bits. A second species of one element type in a JVM makes the shared vector templates
     * bimorphic, and C2 then boxes a vector an iteration ({@code vector-api-and-width.md}).
     *
     * <p>This class holds no step of a loop. A generated loop makes every Vector API call in its own
     * body: C2 stops inlining a method's callees once the method's graph passes a node count ({@code
     * NodeCountInliningCutoff}, a develop flag that no product JVM can set), only a JDK method
     * marked {@code @ForceInline} is exempt, and a vector that crosses a call is boxed. A loop of the
     * study passes that count, so a shared step here would be a call in most of them.
     */
    final class VarkaNanosSpikeOps {

      private VarkaNanosSpikeOps() {
      }

      static final VectorSpecies<Long> LS = LongVector.SPECIES_PREFERRED;

      /** The rows of a lane group. */
      static final int L = LS.length();

      /** The int species of {@link #L} lanes. */
      static final VectorSpecies<Integer> IS =
          VectorSpecies.of(int.class, VectorShape.forBitSize(LS.vectorBitSize() / 2));

      /** The short species of {@link #L} lanes; of four lanes at two long lanes. */
      static final VectorSpecies<Short> HS =
          VectorSpecies.of(short.class, VectorShape.forBitSize(Math.max(64, LS.vectorBitSize() / 4)));

      /**
       * Whether a short vector has a long vector's lane count. At two long lanes it has four, and a
       * mask crosses between the two by its bits.
       */
      static final boolean SHORT_LANES = HS.length() == L;

      /**
       * The bytes every buffer holds past its last row. At two long lanes a short load reads, and a
       * short store writes, four shorts: two past its lane group. No other step passes its group.
       */
      static final long PAD = 64;

      static final ByteOrder LE = ByteOrder.LITTLE_ENDIAN;

      /** A row's element in a buffer, for a scalar loop and for the checker. */
      static final ValueLayout.OfLong JL = ValueLayout.JAVA_LONG_UNALIGNED.withOrder(LE);
      static final ValueLayout.OfInt JI = ValueLayout.JAVA_INT_UNALIGNED.withOrder(LE);
      static final ValueLayout.OfShort JS = ValueLayout.JAVA_SHORT_UNALIGNED.withOrder(LE);

      // ---------------------------------------------------------------------------------------
      // A batch and a loop
      // ---------------------------------------------------------------------------------------

      /**
       * The buffers of a batch. A column is held in each form at once: {@code M} and {@code N} are
       * Spark's epoch microseconds (a long a row) and nanoseconds within one (a short a row), as the
       * Arrow cache's struct holds them; {@code X} epoch nanoseconds (a long); {@code S} and {@code F}
       * epoch seconds (a long) and nanoseconds of the second (an int); {@code D} and {@code T} epoch
       * days (an int) and nanoseconds of the day (a long). {@code a} and {@code b} are the operands,
       * {@code o} the output of a loop whose result is a timestamp; {@code q} is a column of
       * microseconds to add, {@code ol} and {@code oi} the outputs of a long and of an int result,
       * and {@code words} a selection word a 64 rows.
       */
      static final class Cols {
        MemorySegment aM, aN, aX, aS, aF, aD, aT;
        MemorySegment bM, bN, bX, bS, bF, bD, bT;
        MemorySegment oM, oN, oX, oS, oF, oD, oT;
        MemorySegment q;
        MemorySegment ol;
        MemorySegment oi;
        long[] words;
      }

      /** A loop over {@code n} rows, a multiple of 64; negative where a guard saw a value outside. */
      @FunctionalInterface
      interface Loop extends Serializable {
        long apply(Cols c, int n);
      }

      /**
       * One loop of the study: the operation, the form it computes in (1 to 4 for R1 to R4), the
       * layout (L, M, H or S), the form its operands arrive in and the form a timestamp result is
       * stored in, and its method.
       */
      record Arm(String op, int form, String layout, int arrival, int sink, String method,
          Loop loop) {

        /** The label of the results files: {@code <operation> R<form><layout> <arrival>><sink>}. */
        String label() {
          return op + " R" + form + layout + " " + arrival + ">" + sink;
        }

        /** The class that holds the loop's method. */
        String unitClass() {
          return method.startsWith("loopHourc") ? "org.apache.spark.sql.VarkaNanosSpikeLoopsControl"
              : "org.apache.spark.sql.VarkaNanosSpikeLoopsR" + form;
        }
      }

      // ---------------------------------------------------------------------------------------
      // A row of a buffer, for the checker: a loop reads and writes its buffers itself
      // ---------------------------------------------------------------------------------------

      static long getL(MemorySegment s, int r) {
        return s.get(JL, r * 8L);
      }

      static int getI(MemorySegment s, int r) {
        return s.get(JI, r * 4L);
      }

      static short getS(MemorySegment s, int r) {
        return s.get(JS, r * 2L);
      }

      static void putL(MemorySegment s, long v, int r) {
        s.set(JL, r * 8L, v);
      }

      static void putI(MemorySegment s, int v, int r) {
        s.set(JI, r * 4L, v);
      }

      static void putS(MemorySegment s, int v, int r) {
        s.set(JS, r * 2L, (short) v);
      }
    }

### File: VarkaNanosSpikeCheck.java

    /*
     * Licensed to the Apache Software Foundation (ASF) under one or more
     * contributor license agreements.  See the NOTICE file distributed with
     * this work for additional information regarding copyright ownership.
     * The ASF licenses this file to You under the Apache License, Version 2.0
     * (the "License"); you may not use this file except in compliance with
     * the License.  You may obtain a copy of the License at
     *
     *    http://www.apache.org/licenses/LICENSE-2.0
     *
     * Unless required by applicable law or agreed to in writing, software
     * distributed under the License is distributed on an "AS IS" BASIS,
     * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
     * See the License for the specific language governing permissions and
     * limitations under the License.
     */

    package org.apache.spark.sql;

    import static org.apache.spark.sql.VarkaBenchmarkBase.require;
    import static org.apache.spark.sql.VarkaNanosSpikeOps.getI;
    import static org.apache.spark.sql.VarkaNanosSpikeOps.getL;
    import static org.apache.spark.sql.VarkaNanosSpikeOps.getS;
    import static org.apache.spark.sql.VarkaNanosSpikeOps.putI;
    import static org.apache.spark.sql.VarkaNanosSpikeOps.putL;
    import static org.apache.spark.sql.VarkaNanosSpikeOps.putS;

    import java.lang.foreign.Arena;
    import java.lang.foreign.MemorySegment;
    import java.math.BigInteger;
    import java.time.Instant;
    import java.time.ZoneId;
    import java.time.ZoneOffset;
    import java.time.zone.ZoneRules;
    import java.util.ArrayList;
    import java.util.Arrays;
    import java.util.List;
    import java.util.Random;
    import java.util.stream.Stream;

    import org.apache.spark.sql.VarkaNanosSpikeOps.Arm;
    import org.apache.spark.sql.VarkaNanosSpikeOps.Cols;
    import org.apache.spark.sql.catalyst.expressions.XXH64;
    import org.apache.spark.sql.catalyst.util.DateTimeUtils;
    import org.apache.spark.sql.catalyst.util.DateTimeUtils$;
    import org.apache.spark.sql.types.DataTypes;
    import org.apache.spark.sql.types.TimestampNTZNanosType;
    import org.apache.spark.unsafe.types.TimestampNanosVal;

    /**
     * The columns the study's loops run over, each loop's answer by Spark's own methods, and the
     * rows each loop must decline (VARKA-662). The benchmark checks every loop here before it times
     * it and again in compiled code, and {@code VarkaNanosRepresentationSpikeSuite} asserts the same
     * in every build.
     */
    final class VarkaNanosSpikeCheck {

      private VarkaNanosSpikeCheck() {
      }

      static final long E6 = 1_000_000L;
      static final long DAY_US = 86_400_000_000L;

      /** The interval literal of ADDC and CHAIN, in microseconds: one hour and a second and a half. */
      static final long C_US = 3_601_500_000L;

      /** The year the benchmark's values and every ZONE row lie in: 2025, in epoch microseconds. */
      static final long YEAR_FROM = 1_735_689_600L * E6;
      static final long YEAR_TO = 1_767_225_600L * E6;

      /** Inside it no loop may decline: every form holds every value an operation makes of it. */
      static final long SURE_MICROS = 4_000_000_000_000_000L;
      static final long SURE_INTERVAL = 1_000_000_000_000_000L;

      /** The first words one long of nanoseconds holds for every second word. */
      static final long R2_LO = -9223372036854775L;
      static final long R2_HI = 9223372036854774L;

      private static final ZoneRules ZONE = ZoneId.of("America/Los_Angeles").getRules();
      private static final TimestampNTZNanosType TYPE = new TimestampNTZNanosType(9);

      /** Every loop, in the order the generator lists them. */
      static List<Arm> arms() {
        return Stream.of(VarkaNanosSpikeLoopsR1.arms(), VarkaNanosSpikeLoopsR2.arms(),
                VarkaNanosSpikeLoopsR3.arms(), VarkaNanosSpikeLoopsR4.arms())
            .flatMap(List::stream).toList();
      }

      /** A batch: the values of two timestamp columns and an interval column, and their buffers. */
      record Data(int numRows, long[] am, short[] an, long[] bm, short[] bn, long[] q, Cols cols) {

        /**
         * The operand {@code a} of row {@code i} as a loop reads it. Spark's parts, seconds and days
         * hold every value. One long of nanoseconds holds the value its long holds: for a first word
         * outside its range that is the wrapped product, another timestamp, and a loop that arrives
         * as one long is asked about that one.
         */
        TimestampNanosVal a(int arrival, int i) {
          return arrival == 2 ? ofNanos(am[i] * 1000 + an[i])
              : TimestampNanosVal.fromParts(am[i], an[i]);
        }

        /** The operand {@code b} of row {@code i}, as {@link #a}. */
        TimestampNanosVal b(int arrival, int i) {
          return arrival == 2 ? ofNanos(bm[i] * 1000 + bn[i])
              : TimestampNanosVal.fromParts(bm[i], bn[i]);
        }
      }

      private static TimestampNanosVal ofNanos(long nanos) {
        return parts(Math.floorDiv(nanos, 1000L), Math.floorMod(nanos, 1000L));
      }

      /** The benchmark's columns: {@code n} rows from one seed, every value inside 2025. */
      static Data benchmarkData(Arena arena, int n) {
        var r = new Random(662);
        long[] am = new long[n];
        short[] an = new short[n];
        long[] bm = new long[n];
        short[] bn = new short[n];
        long[] q = new long[n];
        for (int i = 0; i < n; i++) {
          am[i] = r.nextLong(YEAR_FROM, YEAR_TO - DAY_US - C_US);
          an[i] = (short) r.nextInt(1000);
          // One row in sixteen ties on both words, one in sixteen on the first alone.
          int tie = r.nextInt(16);
          bm[i] = tie < 2 ? am[i] : r.nextLong(YEAR_FROM, YEAR_TO - DAY_US - C_US);
          bn[i] = tie == 0 ? an[i] : (short) r.nextInt(1000);
          q[i] = r.nextLong(-DAY_US, DAY_US);
        }
        return data(arena, am, an, bm, bn, q);
      }

      /** The buffers of the given values, each column in all four forms. */
      static Data data(Arena arena, long[] am, short[] an, long[] bm, short[] bn, long[] q) {
        int n = am.length;
        require(n > 0 && n % 64 == 0, n + " rows: a loop takes whole selection words");
        var c = new Cols();
        c.aM = buffer(arena, n, 8);
        c.aN = buffer(arena, n, 2);
        c.aX = buffer(arena, n, 8);
        c.aS = buffer(arena, n, 8);
        c.aF = buffer(arena, n, 4);
        c.aD = buffer(arena, n, 4);
        c.aT = buffer(arena, n, 8);
        c.bM = buffer(arena, n, 8);
        c.bN = buffer(arena, n, 2);
        c.bX = buffer(arena, n, 8);
        c.bS = buffer(arena, n, 8);
        c.bF = buffer(arena, n, 4);
        c.bD = buffer(arena, n, 4);
        c.bT = buffer(arena, n, 8);
        c.oM = buffer(arena, n, 8);
        c.oN = buffer(arena, n, 2);
        c.oX = buffer(arena, n, 8);
        c.oS = buffer(arena, n, 8);
        c.oF = buffer(arena, n, 4);
        c.oD = buffer(arena, n, 4);
        c.oT = buffer(arena, n, 8);
        c.q = buffer(arena, n, 8);
        c.ol = buffer(arena, n, 8);
        c.oi = buffer(arena, n, 4);
        c.words = new long[n / 64];
        for (int i = 0; i < n; i++) {
          store(c.aM, c.aN, c.aX, c.aS, c.aF, c.aD, c.aT, i, am[i], an[i]);
          store(c.bM, c.bN, c.bX, c.bS, c.bF, c.bD, c.bT, i, bm[i], bn[i]);
          putL(c.q, q[i], i);
        }
        return new Data(n, am, an, bm, bn, q, c);
      }

      /** The rows {@code rows} of {@code d}, in that order, as a batch of its own. */
      static Data rows(Arena arena, Data d, int[] rows) {
        int n = rows.length;
        long[] am = new long[n];
        short[] an = new short[n];
        long[] bm = new long[n];
        short[] bn = new short[n];
        long[] q = new long[n];
        for (int i = 0; i < n; i++) {
          am[i] = d.am[rows[i]];
          an[i] = d.an[rows[i]];
          bm[i] = d.bm[rows[i]];
          bn[i] = d.bn[rows[i]];
          q[i] = d.q[rows[i]];
        }
        return data(arena, am, an, bm, bn, q);
      }

      /** A buffer of {@code n} elements of {@code bytes} bytes, and the padding past them. */
      private static MemorySegment buffer(Arena arena, int n, int bytes) {
        return arena.allocate((long) n * bytes + VarkaNanosSpikeOps.PAD, 64);
      }

      /** Row {@code i} of a column in every form. One long of nanoseconds wraps where it must. */
      private static void store(MemorySegment m, MemorySegment n, MemorySegment x, MemorySegment s,
          MemorySegment f, MemorySegment d, MemorySegment t, int i, long micros, short nanos) {
        putL(m, micros, i);
        putS(n, nanos, i);
        putL(x, micros * 1000 + nanos, i);
        putL(s, Math.floorDiv(micros, E6), i);
        putI(f, (int) (Math.floorMod(micros, E6) * 1000 + nanos), i);
        putI(d, (int) Math.floorDiv(micros, DAY_US), i);
        putL(t, Math.floorMod(micros, DAY_US) * 1000 + nanos, i);
      }

      /**
       * The words of a timestamp result at row {@code i}, read from the output in {@code form}. The
       * seconds and the days are multiplied out in exact integers: the first second and the first
       * day of a long of microseconds times their unit are under the smallest long, though the
       * value is not.
       */
      private static TimestampNanosVal result(Cols c, int form, int i) {
        return switch (form) {
          case 1 -> TimestampNanosVal.fromParts(getL(c.oM, i), getS(c.oN, i));
          case 2 -> ofNanos(getL(c.oX, i));
          case 3 -> ofNanos(big(getL(c.oS, i)).multiply(E9).add(big(getI(c.oF, i))));
          case 4 -> ofNanos(big(getI(c.oD, i)).multiply(big(86_400_000_000_000L))
              .add(big(getL(c.oT, i))));
          default -> throw new IllegalArgumentException("form " + form);
        };
      }

      /** The value of an exact count of nanoseconds; it raises where its microseconds are no long. */
      private static TimestampNanosVal ofNanos(BigInteger nanos) {
        return parts(floor(nanos, THOUSAND).longValueExact(), nanos.mod(THOUSAND).longValue());
      }

      private static TimestampNanosVal parts(long micros, long nanos) {
        return TimestampNanosVal.fromParts(micros, (short) nanos);
      }

      /**
       * Spark's answer to {@code op} at row {@code i} over operands that arrive in {@code arrival}:
       * a {@link TimestampNanosVal}, a {@code Long}, an {@code Integer} or a {@code Boolean}; null
       * where Spark raises or the operation is not asked (ZONE outside 2025, whose two changes are
       * all the loop knows; TRUNC past 2^62 microseconds).
       */
      static Object spark(String op, int arrival, Data d, int i) {
        TimestampNanosVal a = d.a(arrival, i);
        TimestampNanosVal b = d.b(arrival, i);
        try {
          return switch (op) {
            case "ROUND" -> a;
            case "LT" -> a.compareTo(b) < 0;
            case "PICK" -> a.compareTo(b) < 0 ? a : b;
            case "ADD" -> plus(a, d.q[i]);
            case "ADDC" -> plus(a, C_US);
            case "HOUR", "HOURC" -> DateTimeUtils.getHours(a.epochMicros, ZoneOffset.UTC);
            case "SEC" ->
                DateTimeUtils.getSecondsWithFractionNanos(a, ZoneOffset.UTC).toUnscaledLong();
            case "YEAR" ->
                DateTimeUtils.getYear(DateTimeUtils.microsToDays(a.epochMicros, ZoneOffset.UTC));
            case "TRUNC" -> truncated(a);
            case "MICROS" -> a.epochMicros;
            case "ZONE" -> {
              if (a.epochMicros < YEAR_FROM || a.epochMicros >= YEAR_TO) {
                yield null;
              }
              long seconds = Math.floorDiv(a.epochMicros, E6);
              int offset = ZONE.getOffset(Instant.ofEpochSecond(seconds)).getTotalSeconds();
              yield TimestampNanosVal.fromParts(a.epochMicros + offset * E6, a.nanosWithinMicro);
            }
            case "HASH" -> XXH64.hashInt(a.nanosWithinMicro, XXH64.hashLong(a.epochMicros, 42L));
            case "CHAIN" -> truncated(plus(a.compareTo(b) < 0 ? a : b, C_US));
            default -> throw new IllegalArgumentException("no operation " + op);
          };
        } catch (ArithmeticException | java.time.DateTimeException e) {
          return null;
        }
      }

      private static TimestampNanosVal plus(TimestampNanosVal v, long micros) {
        return DateTimeUtils.timestampNanosAddDayTime(v, micros, ZoneOffset.UTC, TYPE,
            DataTypes.createDayTimeIntervalType());
      }

      private static TimestampNanosVal truncated(TimestampNanosVal v) {
        // Past 2^62 microseconds Spark's truncation takes its slow path; the loops are not asked.
        if (Math.abs(v.epochMicros) > 1L << 62) {
          throw new ArithmeticException("outside the rows TRUNC is asked");
        }
        // TRUNC_TO_HOUR is private[sql] in Scala, so the object has it and no static forwarder does.
        int hour = DateTimeUtils$.MODULE$.TRUNC_TO_HOUR();
        return DateTimeUtils.truncTimestampNanos(v, hour, ZoneOffset.UTC);
      }

      // ---------------------------------------------------------------------------------------
      // The rows a loop must decline: the guards' table (VARKA-662.md 4.3) in exact integers. It
      // shares no line with the loops or with the script that generates them.
      // ---------------------------------------------------------------------------------------

      private static final BigInteger THOUSAND = BigInteger.valueOf(1000);
      private static final BigInteger E9 = BigInteger.valueOf(1_000_000_000L);
      private static final BigInteger HOUR_NS = BigInteger.valueOf(3_600_000_000_000L);
      private static final BigInteger LONG_MIN = BigInteger.valueOf(Long.MIN_VALUE);
      private static final BigInteger LONG_MAX = BigInteger.valueOf(Long.MAX_VALUE);

      private static BigInteger big(long v) {
        return BigInteger.valueOf(v);
      }

      /** The floor of {@code v / d} for a positive {@code d}. */
      private static BigInteger floor(BigInteger v, BigInteger d) {
        return v.subtract(v.mod(d)).divide(d);
      }

      /**
       * The windows of the conversions that have one: the form converted from and to as two digits,
       * the nanoseconds in a unit of the first word of the form converted from, and the least and
       * the greatest first word the conversion takes.
       */
      private static final long[][] WINDOWS = {
          {12, 1000L, R2_LO, R2_HI},
          {31, 1_000_000_000L, -9_223_372_036_854L, 9_223_372_036_853L},
          {41, 86_400_000_000_000L, -106_751_991L, 106_751_990L},
          {32, 1_000_000_000L, -9_223_372_036L, 9_223_372_035L},
          {42, 86_400_000_000_000L, -106_751L, 106_750L},
          {34, 1_000_000_000L, -(1L << 31) * 86_400L, (1L << 31) * 86_400L - 1}};

      /**
       * Whether the conversion from one form to another declines a value of {@code nanos} exact
       * nanoseconds: its first word in the form converted from is outside the conversion's window.
       */
      static boolean outside(int from, int to, BigInteger nanos) {
        for (long[] w : WINDOWS) {
          if (w[0] == from * 10 + to) {
            BigInteger word = floor(nanos, big(w[1]));
            return word.compareTo(big(w[2])) < 0 || word.compareTo(big(w[3])) > 0;
          }
        }
        return false;
      }

      /** The exact answer of an operation whose result is a timestamp, in nanoseconds. */
      private static BigInteger value(String op, BigInteger a, BigInteger b, long q) {
        BigInteger literal = big(C_US).multiply(THOUSAND);
        return switch (op) {
          case "ROUND" -> a;
          case "PICK" -> a.min(b);
          case "ADD" -> a.add(big(q).multiply(THOUSAND));
          case "ADDC" -> a.add(literal);
          case "TRUNC" -> a.subtract(a.mod(HOUR_NS));
          case "ZONE" -> {
            long offset = -28_800L + (a.compareTo(big(1_741_514_400L).multiply(E9)) >= 0 ? 3600 : 0)
                - (a.compareTo(big(1_762_074_000L).multiply(E9)) >= 0 ? 3600 : 0);
            yield a.add(big(offset).multiply(E9));
          }
          case "CHAIN" -> {
            BigInteger sum = a.min(b).add(literal);
            yield sum.subtract(sum.mod(HOUR_NS));
          }
          default -> null;
        };
      }

      /**
       * Whether a loop must decline a row whose operands hold {@code a} and {@code b} exact
       * nanoseconds and whose interval is {@code q} microseconds: a conversion's window on an
       * operand as it arrives; the operation's own guard in R1 and in R2; the window of the
       * conversion to Spark's parts for MICROS and HASH; and a timestamp result's window where it
       * is stored.
       */
      static boolean declines(String op, int form, int arrival, int sink, BigInteger a,
          BigInteger b, long q) {
        boolean two = op.equals("LT") || op.equals("PICK") || op.equals("CHAIN");
        if (outside(arrival, form, a) || (two && outside(arrival, form, b))) {
          return true;
        }
        BigInteger v = op.equals("PICK") || op.equals("CHAIN") ? a.min(b) : a;
        if (form <= 2) {
          // The one word the operation works on: m of R1, x of R2, and a microsecond in its unit.
          BigInteger unit = form == 1 ? BigInteger.ONE : THOUSAND;
          BigInteger word = form == 1 ? floor(v, THOUSAND) : v;
          BigInteger hour = big(3_600_000_000L).multiply(unit);
          switch (op) {
            case "ADD" -> {
              BigInteger sum = word.add(big(q).multiply(unit));
              if ((form == 2 && (q < R2_LO || q > R2_HI)) || sum.compareTo(LONG_MIN) < 0
                  || sum.compareTo(LONG_MAX) > 0) {
                return true;
              }
            }
            case "ADDC", "CHAIN" -> {
              if (word.add(big(C_US).multiply(unit)).compareTo(LONG_MAX) > 0) {
                return true;
              }
            }
            case "TRUNC" -> {
              // Under the least whole hour that is a long, the floor is no long.
              if (word.subtract(word.mod(hour)).compareTo(LONG_MIN) < 0) {
                return true;
              }
            }
            case "ZONE" -> {
              if (word.subtract(big(28_800_000_000L).multiply(unit)).compareTo(LONG_MIN) < 0) {
                return true;
              }
            }
            default -> {
            }
          }
        }
        if (op.equals("MICROS") || op.equals("HASH")) {
          return outside(form, 1, a);
        }
        BigInteger result = value(op, a, b, q);
        return result != null && outside(form, sink, result);
      }

      private static BigInteger nanos(TimestampNanosVal v) {
        return big(v.epochMicros).multiply(THOUSAND).add(big(v.nanosWithinMicro));
      }

      /** Whether {@code arm}'s loop must decline row {@code i} of {@code d}. */
      static boolean declines(Arm arm, Data d, int i) {
        return declines(arm.op(), arm.form(), arm.arrival(), arm.sink(), nanos(d.a(arm.arrival(), i)),
            nanos(d.b(arm.arrival(), i)), d.q[i]);
      }

      // ---------------------------------------------------------------------------------------
      // A loop against Spark
      // ---------------------------------------------------------------------------------------

      /**
       * Runs {@code arm}'s loop over {@code d}. The loop must decline the batch exactly where the
       * guards' table says a row of it must be declined. A batch it answers is held to Spark's
       * answers, row by row. A batch it declines is not skipped: the rows the table accepts are run
       * again as a batch of their own, which the loop must answer, and, where {@code rowByRow}, each
       * row the table declines is run alone, and the loop must decline it.
       */
      static void requireAgrees(Arm arm, Data d, String when, boolean rowByRow) {
        Cols c = d.cols();
        Arrays.fill(c.words, 0x5555_5555_5555_5555L);
        for (MemorySegment out : new MemorySegment[] {c.oM, c.oN, c.oX, c.oS, c.oF, c.oD, c.oT, c.ol,
            c.oi}) {
          // What no row answers, so that a slot a loop left unwritten fails.
          out.fill((byte) 0x7F);
        }
        long returned = arm.loop().apply(c, d.numRows());
        String what = arm.label() + " " + when;
        var accepted = new ArrayList<Integer>();
        var declined = new ArrayList<Integer>();
        for (int i = 0; i < d.numRows(); i++) {
          (declines(arm, d, i) ? declined : accepted).add(i);
        }
        require((returned < 0) == !declined.isEmpty(), what + ": the loop returned " + returned
            + " over a batch with " + declined.size() + " rows to decline, the first row "
            + (declined.isEmpty() ? "none" : describe(d, declined.get(0))));
        if (!declined.isEmpty()) {
          if (!accepted.isEmpty()) {
            try (Arena arena = Arena.ofConfined()) {
              requireAgrees(arm, rows(arena, d, padded(accepted)), when + ", the rows it accepts",
                  false);
            }
          }
          int[] alone = new int[64];
          for (int i = 0; rowByRow && i < declined.size(); i++) {
            Arrays.fill(alone, declined.get(i));
            try (Arena arena = Arena.ofConfined()) {
              require(arm.loop().apply(rows(arena, d, alone).cols(), 64) < 0, what + ": the loop"
                  + " answers a row it must decline, " + describe(d, declined.get(i)));
            }
          }
          return;
        }
        for (int i = 0; i < d.numRows(); i++) {
          Object want = spark(arm.op(), arm.arrival(), d, i);
          if (want == null) {
            continue;
          }
          Object got = switch (want) {
            case TimestampNanosVal _ -> result(c, arm.sink(), i);
            case Boolean _ -> (c.words[i >> 6] >>> (i & 63) & 1L) != 0;
            case Integer _ -> getI(c.oi, i);
            case Long _ -> getL(c.ol, i);
            default -> throw new IllegalStateException("an answer of " + want.getClass());
          };
          require(want.equals(got), what + ": row " + i + " of " + describe(d, i) + " is " + got
              + ", Spark's " + want);
        }
      }

      /** The row numbers, the first repeated up to whole selection words. */
      private static int[] padded(List<Integer> rows) {
        int[] out = new int[(rows.size() + 63) / 64 * 64];
        for (int i = 0; i < out.length; i++) {
          out[i] = rows.get(i < rows.size() ? i : 0);
        }
        return out;
      }

      private static String describe(Data d, int i) {
        return "(" + d.am[i] + ", " + d.an[i] + "), (" + d.bm[i] + ", " + d.bn[i] + ") and " + d.q[i];
      }
    }

### File: VarkaNanosRepresentationSpikeSuite.java

    /*
     * Licensed to the Apache Software Foundation (ASF) under one or more
     * contributor license agreements.  See the NOTICE file distributed with
     * this work for additional information regarding copyright ownership.
     * The ASF licenses this file to You under the Apache License, Version 2.0
     * (the "License"); you may not use this file except in compliance with
     * the License.  You may obtain a copy of the License at
     *
     *    http://www.apache.org/licenses/LICENSE-2.0
     *
     * Unless required by applicable law or agreed to in writing, software
     * distributed under the License is distributed on an "AS IS" BASIS,
     * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
     * See the License for the specific language governing permissions and
     * limitations under the License.
     */

    package org.apache.spark.sql;

    import static org.junit.jupiter.api.Assertions.assertEquals;
    import static org.junit.jupiter.api.Assertions.assertFalse;
    import static org.junit.jupiter.api.Assertions.assertTrue;

    import java.lang.foreign.Arena;
    import java.util.ArrayList;
    import java.util.HashSet;
    import java.util.List;
    import java.util.Random;

    import org.junit.jupiter.api.DisplayName;
    import org.junit.jupiter.api.Test;

    import org.apache.spark.sql.VarkaNanosSpikeCheck.Data;
    import org.apache.spark.sql.VarkaNanosSpikeOps.Arm;
    import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaSuite;
    import org.apache.spark.sql.catalyst.util.DateTimeUtils;
    import org.apache.spark.unsafe.types.TimestampNanosVal;

    /**
     * The nanosecond representations' loops against Spark's own methods, in every build (VARKA-662):
     * the check the benchmark makes before it times a loop, over the values where a representation
     * can be wrong and the benchmark's year 2025 cannot show it. Which representation is faster is
     * the benchmark's question, not this suite's. It runs in a JVM of its own: the loops' int and
     * short species are second species in the JVM the Varka suites share.
     *
     * <p>A batch a loop declines is not skipped. The checker's table of the guards says which rows a
     * loop must decline; the loop must decline the batch exactly where the table has such a row,
     * answer the other rows as a batch of their own, and decline each such row alone.
     */
    @VarkaSuite(ownJvm = true)
    public final class VarkaNanosRepresentationSpikeSuite {

      private static final long E6 = VarkaNanosSpikeCheck.E6;
      private static final long DAY = VarkaNanosSpikeCheck.DAY_US;
      private static final long C = VarkaNanosSpikeCheck.C_US;
      private static final long Y0 = VarkaNanosSpikeCheck.YEAR_FROM;
      private static final short[] SECOND_WORDS = {0, 1, 500, 999};

      /** Inside the sure range: no loop may decline a batch of these. */
      private static final long[] SURE = {-1, 0, 1, -E6, -E6 + 1, E6 - 1, -DAY - 1, -DAY, DAY - 1, DAY,
          Y0, VarkaNanosSpikeCheck.YEAR_TO - 1, 1_741_514_400L * E6 - 1, 1_741_514_400L * E6,
          1_762_074_000L * E6 - 1, 1_762_074_000L * E6, 1_751_328_000L * E6 - 1,
          -VarkaNanosSpikeCheck.SURE_MICROS, VarkaNanosSpikeCheck.SURE_MICROS, -3 * DAY + 7};

      /** Spark's years 1 and 9999, the ends of one long of nanoseconds and one past each, 2^62. */
      private static final long[] WIDE = {-1, 0, 1, -62_135_596_800_000_000L,
          253_402_300_799_999_999L, VarkaNanosSpikeCheck.R2_LO - 1, VarkaNanosSpikeCheck.R2_LO,
          VarkaNanosSpikeCheck.R2_HI, VarkaNanosSpikeCheck.R2_HI + 1, -(1L << 62), 1L << 62};

      /**
       * The ends of a long and of every guard: the last first word a guard accepts and the first it
       * declines. In order: the seconds and the days whose microseconds are longs; the seconds and
       * the days whose nanoseconds are longs; the literal's sum in microseconds and in nanoseconds;
       * the least whole hour of microseconds and of nanoseconds; the zone's offset in both.
       */
      private static final long[] ENDS = {Long.MIN_VALUE, Long.MIN_VALUE + 1, Long.MAX_VALUE - 1,
          Long.MAX_VALUE,
          -9_223_372_036_854L * E6 - 1, -9_223_372_036_854L * E6, 9_223_372_036_854L * E6 - 1,
          9_223_372_036_854L * E6, -106_751_991L * DAY - 1, -106_751_991L * DAY,
          106_751_991L * DAY - 1, 106_751_991L * DAY,
          -9_223_372_036L * E6 - 1, -9_223_372_036L * E6, 9_223_372_036L * E6 - 1,
          9_223_372_036L * E6, -106_751L * DAY - 1, -106_751L * DAY, 106_751L * DAY - 1,
          106_751L * DAY,
          Long.MAX_VALUE - C, Long.MAX_VALUE - C + 1, 9_223_368_435_354_775L, 9_223_368_435_354_776L,
          -9_223_372_036_800_000_001L, -9_223_372_036_800_000_000L, -9_223_369_200_000_001L,
          -9_223_369_200_000_000L,
          Long.MIN_VALUE + 28_800L * E6 - 1, Long.MIN_VALUE + 28_800L * E6,
          -9_223_343_236_854_776L, -9_223_343_236_854_775L};

      /** The second words of the batch of ends: a guard reads the first word alone. */
      private static final short[] END_SECOND_WORDS = {0, 999};

      /** The intervals a row adds, in turn; each is inside the sure range. */
      private static final long[] INTERVALS = {0, 1, -1, 999_999, E6, -E6 - 1, DAY, -DAY - 1,
          3 * DAY + 7, DAY - 1, -VarkaNanosSpikeCheck.SURE_INTERVAL,
          VarkaNanosSpikeCheck.SURE_INTERVAL};

      /**
       * Every ordered pair of {@code firsts} with each of {@code seconds}, an interval a row in turn,
       * then {@code extra} rows, padded with its last row to whole selection words.
       */
      private static Data pairs(Arena arena, long[] firsts, short[] seconds, long[][] extra) {
        var rows = new ArrayList<long[]>();
        for (long am : firsts) {
          for (short an : seconds) {
            for (long bm : firsts) {
              for (short bn : seconds) {
                rows.add(new long[] {am, an, bm, bn, INTERVALS[rows.size() % INTERVALS.length]});
              }
            }
          }
        }
        rows.addAll(List.of(extra));
        while (rows.size() % 64 != 0) {
          rows.add(rows.get(rows.size() - 1));
        }
        return data(arena, rows);
      }

      private static Data data(Arena arena, List<long[]> rows) {
        int n = rows.size();
        long[] am = new long[n];
        short[] an = new short[n];
        long[] bm = new long[n];
        short[] bn = new short[n];
        long[] q = new long[n];
        for (int i = 0; i < n; i++) {
          long[] row = rows.get(i);
          am[i] = row[0];
          an[i] = (short) row[1];
          bm[i] = row[2];
          bn[i] = (short) row[3];
          q[i] = row[4];
        }
        return VarkaNanosSpikeCheck.data(arena, am, an, bm, bn, q);
      }

      /**
       * Sums that land exactly on a second and on a day, where a carry's compare is decided: the
       * literal's and a column's, from the epoch, from 2025 and from three days before the epoch.
       */
      private static long[][] carries() {
        var rows = new ArrayList<long[]>();
        for (long base : new long[] {0, Y0, -3 * DAY}) {
          rows.add(new long[] {base + E6 - C % E6, 0, base, 0, E6 - 1});
          rows.add(new long[] {base + 1, 0, base + 1, 0, E6 - 1});
          rows.add(new long[] {base + DAY - C % DAY, 0, base + DAY, 0, DAY - 1});
          rows.add(new long[] {base + 1, 0, base + 2, 0, DAY - 1});
          rows.add(new long[] {base + E6 - C % E6, 1, base + DAY - C % DAY, 999, -1});
        }
        return rows.toArray(new long[0][]);
      }

      /** 1024 rows whose first words are uniform in {@code [-bound, bound]}, from one seed. */
      private static Data random(Arena arena, long bound, long seed) {
        var r = new Random(seed);
        var rows = new ArrayList<long[]>();
        for (int i = 0; i < 1024; i++) {
          long am = r.nextLong(-bound, bound + 1);
          long bm = i % 8 == 0 ? am : r.nextLong(-bound, bound + 1);
          rows.add(new long[] {am, r.nextInt(1000), bm, r.nextInt(1000), r.nextLong(-DAY, DAY)});
        }
        return data(arena, rows);
      }

      @Test
      @DisplayName("every loop of every arm agrees with Spark's methods")
      void everyLoopAgrees() {
        try (Arena arena = Arena.ofConfined()) {
          List<Data> batches = List.of(
              pairs(arena, SURE, SECOND_WORDS, carries()),
              pairs(arena, WIDE, SECOND_WORDS, new long[0][]),
              pairs(arena, ENDS, END_SECOND_WORDS, new long[0][]),
              random(arena, VarkaNanosSpikeCheck.SURE_MICROS, 662),
              random(arena, 1L << 62, 663),
              VarkaNanosSpikeCheck.benchmarkData(arena, 4096));
          // The rows of each: 20 first words give 6400 pairs and 15 carry rows, padded to 6464.
          assertEquals(List.of(6464, 1984, 4096, 1024, 1024, 4096),
              batches.stream().map(Data::numRows).toList());
          for (Arm arm : VarkaNanosSpikeCheck.arms()) {
            for (int b = 0; b < batches.size(); b++) {
              VarkaNanosSpikeCheck.requireAgrees(arm, batches.get(b), "over batch " + b, true);
            }
          }
        }
      }

      @Test
      @DisplayName("no loop declines a batch inside the sure range, and one long declines outside it")
      void declinesOnlyOutside() {
        try (Arena arena = Arena.ofConfined()) {
          Data sure = pairs(arena, SURE, SECOND_WORDS, carries());
          Data wide = pairs(arena, WIDE, SECOND_WORDS, new long[0][]);
          for (Arm arm : VarkaNanosSpikeCheck.arms()) {
            assertTrue(arm.loop().apply(sure.cols(), sure.numRows()) >= 0, arm.label());
            // One long of nanoseconds cannot hold the years 1 and 9999: the loop must say so.
            if (arm.form() == 2 && arm.arrival() == 1) {
              assertTrue(arm.loop().apply(wide.cols(), wide.numRows()) < 0, arm.label());
            }
          }
        }
      }

      @Test
      @DisplayName("each loop is a method of its own, and the species are the layouts'")
      void armsAreLoops() {
        var methods = new HashSet<String>();
        var labels = new HashSet<String>();
        for (Arm arm : VarkaNanosSpikeCheck.arms()) {
          assertTrue(arm.method().startsWith("loop"), arm.method() + " is not a loop method");
          assertTrue(methods.add(arm.method()), arm.method() + " serves two arms");
          assertTrue(labels.add(arm.label()), arm.label() + " twice");
        }
        assertFalse(methods.isEmpty());
        assertEquals(VarkaNanosSpikeOps.L, VarkaNanosSpikeOps.IS.length());
        assertEquals(VarkaNanosSpikeOps.LS.vectorBitSize(), 2 * VarkaNanosSpikeOps.IS.vectorBitSize());
        assertEquals(Math.max(4, VarkaNanosSpikeOps.L), VarkaNanosSpikeOps.HS.length());
        assertEquals(VarkaNanosSpikeOps.L >= 4, VarkaNanosSpikeOps.SHORT_LANES);
      }

      @Test
      @DisplayName("the benchmark's columns reach every outcome the loops decide")
      void dataReachesEveryOutcome() {
        try (Arena arena = Arena.ofConfined()) {
          Data d = VarkaNanosSpikeCheck.benchmarkData(arena, 4096);
          int less = 0;
          int equal = 0;
          int firstTies = 0;
          int secondCarries = 0;
          int dayCarries = 0;
          var hours = new HashSet<Integer>();
          var offsets = new HashSet<Long>();
          for (int i = 0; i < d.numRows(); i++) {
            var a = TimestampNanosVal.fromParts(d.am()[i], d.an()[i]);
            var b = TimestampNanosVal.fromParts(d.bm()[i], d.bn()[i]);
            less += a.compareTo(b) < 0 ? 1 : 0;
            equal += a.equals(b) ? 1 : 0;
            firstTies += d.am()[i] == d.bm()[i] && d.an()[i] != d.bn()[i] ? 1 : 0;
            // The literal's carry into the second (R3) and into the day (R4).
            secondCarries += Math.floorMod(d.am()[i], E6) + C % E6 >= E6 ? 1 : 0;
            dayCarries += Math.floorMod(d.am()[i], DAY) + C % DAY >= DAY ? 1 : 0;
            hours.add((Integer) VarkaNanosSpikeCheck.spark("HOUR", 1, d, i));
            var local = (TimestampNanosVal) VarkaNanosSpikeCheck.spark("ZONE", 1, d, i);
            offsets.add(local.epochMicros - d.am()[i]);
            assertTrue(DateTimeUtils.microsToDays(d.am()[i], java.time.ZoneOffset.UTC) >= 20089);
          }
          assertTrue(less > 1000 && less < 3000, less + " rows less");
          assertTrue(equal > 100 && firstTies > 100, equal + " equal, " + firstTies + " tie");
          assertTrue(secondCarries > 1000 && secondCarries < 3000, secondCarries + " second carries");
          assertTrue(dayCarries > 50 && dayCarries < 1000, dayCarries + " day carries");
          assertEquals(24, hours.size());
          assertEquals(java.util.Set.of(-28_800L * E6, -25_200L * E6), offsets);
        }
      }
    }

### File: VarkaNanosRepresentationSpikeBenchmark.java

    /*
     * Licensed to the Apache Software Foundation (ASF) under one or more
     * contributor license agreements.  See the NOTICE file distributed with
     * this work for additional information regarding copyright ownership.
     * The ASF licenses this file to You under the Apache License, Version 2.0
     * (the "License"); you may not use this file except in compliance with
     * the License.  You may obtain a copy of the License at
     *
     *    http://www.apache.org/licenses/LICENSE-2.0
     *
     * Unless required by applicable law or agreed to in writing, software
     * distributed under the License is distributed on an "AS IS" BASIS,
     * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
     * See the License for the specific language governing permissions and
     * limitations under the License.
     */

    package org.apache.spark.sql;

    import java.io.File;
    import java.io.IOException;
    import java.io.UncheckedIOException;
    import java.lang.foreign.Arena;
    import java.lang.management.ManagementFactory;
    import java.net.URISyntaxException;
    import java.net.URL;
    import java.net.URLClassLoader;
    import java.nio.charset.StandardCharsets;
    import java.nio.file.Files;
    import java.nio.file.Path;
    import java.util.ArrayList;
    import java.util.HashMap;
    import java.util.LinkedHashSet;
    import java.util.List;
    import java.util.Map;
    import java.util.TreeMap;
    import java.util.concurrent.TimeUnit;
    import java.util.concurrent.atomic.AtomicBoolean;
    import java.util.regex.Matcher;
    import java.util.regex.Pattern;

    import javax.management.ObjectName;

    import org.apache.spark.sql.VarkaNanosSpikeCheck.Data;
    import org.apache.spark.sql.VarkaNanosSpikeOps.Arm;
    import org.apache.spark.sql.catalyst.expressions.codegen.varka.VarkaEmitterTestSupport;

    /**
     * What an operation over a nanosecond timestamp costs in a Vector API loop under each
     * representation of the value, written with no emitter (VARKA-662's study, which measures the
     * representations before any kernel computes in one).
     *
     * <p>Spark stores the value as epoch microseconds and the nanoseconds within one (R1). The
     * candidates beside it are one long of epoch nanoseconds (R2), epoch seconds with the
     * nanoseconds of the second (R3) and epoch days with the nanoseconds of the day (R4). A loop
     * reads its operands in one form, computes in another and stores a timestamp result in a third,
     * so that a representation pays its conversions where a kernel would; an arm's label is {@code
     * <operation> R<computing form><layout> <arriving form>><stored form>}. The layouts: H holds
     * Spark's short in a short vector beside the long vector; L holds every word in a long lane; M
     * holds a narrow word in an int vector of half the bits; S is a scalar loop. The loops are
     * generated by {@code dev/varka_nanos_spike_gen.py} from formulas that {@code
     * dev/varka_nanos_spike.py} checks against exact integers, into {@code VarkaNanosSpikeLoopsR1}
     * to {@code R4}; {@link VarkaNanosSpikeLoopsControl} is written by hand.
     *
     * <p>Every loop is checked against Spark's own methods before it is timed and again in compiled
     * code ({@link VarkaNanosSpikeCheck}). Each loop is a method of its own, kept out of line, that
     * calls nothing but the Vector API, and is driven to level-4 code by VARKA-359's harness. Level 4
     * is not proof that vector code is timed: before timing, {@link #intrinsicRefusals} compiles each
     * loop once more in a child JVM with this run's flags and reads its inlining tree, VARKA-577's
     * check. An arm with a call left in Java is timed in a table of its own, its label says so, and
     * the results file lists the calls beside the tables.
     *
     * <p>Row counts are multiples of 64, so no loop has a tail, and no loop reads a validity bitmap:
     * a kernel combines validity a 64-row word at a time, the same whatever holds the value.
     *
     * <p>Run it with {@link #DONTINLINE_LOOPS}, which the run requires, at {@code
     * -XX:MaxVectorSize=64}, {@code 32} and {@code 16} for the three widths:
     * <pre>
     *   build/sbt 'project catalyst' 'set Test/javaOptions ++= Seq("-XX:MaxVectorSize=32",
     *       "-XX:CompileCommand=dontinline,org.apache.spark.sql.VarkaNanosSpikeLoops*::loop*")' \
     *     'Test/runMain org.apache.spark.sql.VarkaNanosRepresentationSpikeBenchmark'
     * </pre>
     * {@code -Dvarka.nanos.ops=LT,PICK} times ROUND, the control and the operations it lists, in a
     * section titled with them, so that a long run is taken as several whose files are joined by
     * {@code dev/varka_bench_sections.py splice}. It is no filter of {@code -Dvarka.bench.only}'s
     * kind: every operation it names is timed whole, with its base, and the run writes its file.
     */
    public class VarkaNanosRepresentationSpikeBenchmark extends VarkaBenchmarkBase {

      /** A rung of the ladder: a row count and where its working set lies. */
      private record Rung(int numRows, String level) {}

      /** VARKA-359's rungs. A timestamp column is 8 to 12 bytes a row, by its form. */
      private static final List<Rung> LADDER = List.of(
          new Rung(4096, "in L2"),
          new Rung(262144, "in L3"),
          new Rung(4194304, "past L3"));

      /** The compile command that keeps every loop a compile unit of its own. */
      static final String DONTINLINE_LOOPS =
          "-XX:CompileCommand=dontinline,org.apache.spark.sql.VarkaNanosSpikeLoops*::loop*";

      /**
       * The rows of a timed iteration of a loop with no call left in Java: enough that the file's
       * whole milliseconds resolve a loop of a quarter of a nanosecond a row (17 ms), since the cost
       * table reads a loop's spread from the average and the best time.
       */
      private static final long ROWS_PER_ITERATION = 1L << 26;

      /** The rows of a drive while a loop settles, and of an iteration of a loop that falls back. */
      private static final long ROWS_PER_DRIVE = 1L << 20;

      /** The operations {@code -Dvarka.nanos.ops} lists; none for every operation. */
      private static List<String> opsAsked() {
        String ops = System.getProperty("varka.nanos.ops", "");
        return ops.isEmpty() ? List.of() : List.of(ops.split(","));
      }
      private static final int MIN_WARM_DRIVES = 8;
      private static final double STEADY_FRACTION = 0.3;
      private static final long WARM_WAIT_MILLIS = 30_000;
      private static final long IDLE_WAIT_MILLIS = 10_000;
      private static final int PROBE_ROWS = 64;
      private static final int PROBE_CALLS = 20_000;
      private static final long PROBE_TIMEOUT_SECONDS = 300;

      /** What the loops return, summed, so that no call is dead. */
      private static long sink;

      /**
       * Whether an arm is timed past the first rung: the loops that only move the value (ROUND
       * stored as it arrived) and the chain, which show where memory takes over from arithmetic.
       */
      private static boolean pastL2(Arm arm) {
        return arm.arrival() == arm.sink() && (arm.op().equals("ROUND") || arm.op().equals("CHAIN"));
      }

      // levelInUse, codeList, childClassPath, COMPILE and summary below are VARKA-577's, from
      // VarkaDecimalRemainderSpikeBenchmark, unchanged.

      /**
       * The highest tier of {@code method}'s code in use by {@code Compiler.codelist}, 0 if none, -1
       * if the list cannot be read; the drive's steady times show a shared loop's rung settled. The
       * list does not mark an on-stack-replacement version, so a level-4 OSR compile, whose boxed
       * vectors {@link #fallbacks} passes over, also reads as 4: at the rungs past L2 a drive is four
       * calls or one, and a loop whose method code was made not entrant there may be timed in it.
       */
      private static int levelInUse(String owner, String method) {
        try {
          String prefix = owner + "." + method + "(";
          int level = 0;
          for (String line : String.valueOf(codeList()).split("\\R")) {
            // "<id> <tier> <state> <class>.<method><descriptor> [<addresses>]"; state 0 is in use.
            String[] fields = line.strip().split(" ", 5);
            if (fields.length >= 4 && fields[3].startsWith(prefix) && "0".equals(fields[2])) {
              level = Math.max(level, Integer.parseInt(fields[1]));
            }
          }
          return level;
        } catch (Exception | LinkageError e) {
          return -1;
        }
      }

      private static Object codeList() throws Exception {
        return ManagementFactory.getPlatformMBeanServer().invoke(
            new ObjectName("com.sun.management:type=DiagnosticCommand"), "compilerCodelist",
            new Object[] {null}, new String[] {String[].class.getName()});
      }

      /** Refuses a run that cannot read the code list, or that lacks the compile command. */
      private static void requireHarness() {
        try {
          codeList();
        } catch (Exception | LinkageError e) {
          throw new IllegalStateException("Compiler.codelist cannot be read (" + e + "), so no loop"
              + " can be shown compiled; run on a HotSpot JVM with the DiagnosticCommand MBean", e);
        }
        List<String> args = ManagementFactory.getRuntimeMXBean().getInputArguments();
        require(args.contains(DONTINLINE_LOOPS), "no " + DONTINLINE_LOOPS + " is set, so a loop may"
            + " be inlined into the drive and show no code of its own in the code list");
      }

      private static void drive(Arm arm, Data d, int reps) {
        for (int r = 0; r < reps; r++) {
          sink += arm.loop().apply(d.cols(), d.numRows());
        }
      }

      /**
       * Drives {@code arm} until its loop method is level-4 code and its drive time is steady. It
       * first calls the loop {@link #PROBE_CALLS} times over {@code small}'s {@link #PROBE_ROWS}
       * rows, as the intrinsic check does: C2 takes a loop method at its invocation count, and an arm
       * that runs the Java fallback in level-3 code may not make that many calls of a rung's rows in
       * the wait (VARKA-577.md 10.4).
       */
      private static void driveToCompiledCode(Arm arm, Data d, Data small, int reps) {
        for (int c = 0; c < PROBE_CALLS; c++) {
          sink += arm.loop().apply(small.cols(), PROBE_ROWS);
        }
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(WARM_WAIT_MILLIS);
        long previous = 0;
        int drives = 0;
        String name = "VarkaNanosRepresentationSpikeBenchmark: " + arm.label() + " at " + d.numRows()
            + " rows";
        while (true) {
          long start = System.nanoTime();
          drive(arm, d, reps);
          long took = System.nanoTime() - start;
          drives++;
          boolean idle = VarkaEmitterTestSupport.awaitC2Idle(IDLE_WAIT_MILLIS);
          int level = levelInUse(arm.unitClass(), arm.method());
          boolean steady = previous > 0 && Math.abs(took - previous) <= previous * STEADY_FRACTION;
          if (drives >= MIN_WARM_DRIVES && idle && steady && level == 4) {
            System.out.println(name + " settled after " + drives + " drives, level " + level);
            return;
          }
          if (System.nanoTime() > deadline) {
            System.out.println(name + " did not settle in " + WARM_WAIT_MILLIS + " ms (" + drives
                + " drives, last " + TimeUnit.NANOSECONDS.toMicros(took) + " us after "
                + TimeUnit.NANOSECONDS.toMicros(previous) + " us, level " + level + ", C2 "
                + (idle ? "idle" : "busy") + "); it is timed as it is");
            return;
          }
          previous = took;
        }
      }

      /**
       * The calls C2 left in Java in {@code arm}'s loop under this run's flags, empty where every
       * Vector API call was intrinsified. A child JVM of {@link
       * IntrinsicProbe} calls the loop alone, with this JVM's arguments but its agents, plus {@code
       * -Xbatch}, {@code PrintCompilation} and a {@code PrintInlining} command over the loops;
       * {@link #fallbacks} reads the answer from what it prints.
       */
      static List<String> intrinsicRefusals(Arm arm) {
        var command = new ArrayList<String>();
        command.add(Path.of(System.getProperty("java.home"), "bin", "java").toString());
        for (String arg : ManagementFactory.getRuntimeMXBean().getInputArguments()) {
          if (!arg.startsWith("-agentlib") && !arg.startsWith("-agentpath")
              && !arg.startsWith("-javaagent") && !arg.startsWith("-Xrun")) {
            command.add(arg);
          }
        }
        command.addAll(List.of("-XX:+UnlockDiagnosticVMOptions", "-Xbatch", "-XX:+PrintCompilation",
            "-XX:CompileCommand=quiet",
            "-XX:CompileCommand=PrintInlining,org.apache.spark.sql.VarkaNanosSpikeLoops*::loop*",
            "-cp", childClassPath(), IntrinsicProbe.class.getName(), arm.method()));
        Path log = null;
        try {
          log = Files.createTempFile("varka-nanos-probe", ".log");
          Process child = new ProcessBuilder(command).redirectErrorStream(true)
              .redirectOutput(log.toFile()).start();
          if (!child.waitFor(PROBE_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            child.destroyForcibly();
            throw requirementFailed("the intrinsic check of " + arm.method() + " did not finish in "
                + PROBE_TIMEOUT_SECONDS + " s");
          }
          List<String> lines = Files.readAllLines(log, StandardCharsets.UTF_8);
          require(child.exitValue() == 0, "the intrinsic check of " + arm.method() + " failed (exit "
              + child.exitValue() + "); its last lines:\n"
              + String.join("\n", lines.subList(Math.max(0, lines.size() - 20), lines.size())));
          return fallbacks(lines, arm.unitClass(), arm.method(), arm.layout().equals("S"));
        } catch (IOException e) {
          throw new UncheckedIOException(e);
        } catch (InterruptedException e) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException(e);
        } finally {
          if (log != null) {
            try {
              Files.deleteIfExists(log);
            } catch (IOException e) {
              System.out.println("could not delete " + log + ": " + e);
            }
          }
        }
      }

      /**
       * The child's class path: this JVM's own, plus every local jar and directory its context class
       * loaders were given. Under {@code spark-submit}, which is how the benchmark workflow runs a
       * benchmark, the test jars that hold the probe come in through {@code --jars} and are only there.
       */
      private static String childClassPath() {
        var entries = new LinkedHashSet<String>();
        for (String entry : System.getProperty("java.class.path").split(File.pathSeparator)) {
          if (!entry.isEmpty()) {
            entries.add(entry);
          }
        }
        for (ClassLoader loader = Thread.currentThread().getContextClassLoader(); loader != null;
            loader = loader.getParent()) {
          if (loader instanceof URLClassLoader urls) {
            for (URL url : urls.getURLs()) {
              if (url.getProtocol().equals("file")) {
                try {
                  entries.add(new File(url.toURI()).getPath());
                } catch (URISyntaxException e) {
                  throw new IllegalArgumentException("not a file URL: " + url, e);
                }
              }
            }
          }
        }
        return String.join(File.pathSeparator, entries);
      }

      /** A {@code PrintCompilation} record: its flags ({@code %} for an OSR), level and method. */
      private static final Pattern COMPILE = Pattern.compile(
          "^\\s*\\d+\\s+\\d+\\s+([%sbn! ]*)\\s+(\\d)\\s+(\\S+::\\S+)");

      /**
       * The lookup of an operator's Java implementation ({@code LongVector::binaryOperations} and
       * its like, and the refusal of an unknown operator): the miss path of the Vector API's cache
       * of those implementations. It takes no vector and runs once an operator a JVM, so where C2
       * leaves it a call (it is 322 bytes, "too big") the loop is vector code all the same. C2
       * compiles that path in when a loop is compiled a second time after a trap.
       */
      private static final Pattern COLD_LOOKUP = Pattern.compile(
          "Vector::\\w+Operations \\(|VectorOperators\\$ImplCache::badOp \\(");

      /**
       * The calls left in Java in {@code method}'s last level-4 compile that is not an on-stack
       * replacement, from a child's {@code PrintCompilation} and {@code PrintInlining} lines; under
       * {@code -Xbatch} a compile's inlining tree follows its record. A {@code VectorSupport} call
       * site whose last message is not {@code (intrinsic)} or {@code late inline succeeded} is a call
       * to the Java fallback, which boxes its vectors. A Vector API method that failed to inline is
       * a call too, with its vectors boxed, but for {@link #COLD_LOOKUP}. A compile C2 skipped is
       * passed over. With no such compile the check throws, and so it does for a vector loop whose
       * tree holds no Vector API site, since an empty answer would prove nothing; a {@code scalar}
       * loop has none by design.
       */
      static List<String> fallbacks(List<String> lines, String owner, String method, boolean scalar) {
        List<String> last = null;
        List<String> current = null;
        boolean sites = false;
        // The compile before the one being read, which a skipped compile hands the answer back to.
        List<String> kept = null;
        boolean keptSites = false;
        for (String line : lines) {
          Matcher m = COMPILE.matcher(line);
          boolean record = m.find();
          boolean ours = record && m.group(3).equals(owner + "::" + method)
              && m.group(2).equals("4") && !m.group(1).contains("%");
          if (line.contains("made not entrant")) {
            continue;
          } else if (line.contains("COMPILE SKIPPED")) {
            if (ours && current == last) {
              last = kept;
              sites = keptSites;
            }
            current = null;
          } else if (record) {
            current = new ArrayList<>();
            if (ours) {
              kept = last;
              keptSites = sites;
              last = current;
              sites = false;
            }
          } else if (current != null && (line.contains("jdk.internal.vm.vector.VectorSupport::")
              || line.contains("jdk.incubator.vector."))) {
            sites |= current == last;
            // A site's messages are separated by runs of spaces, its verdict the last of them.
            String[] said = line.strip().split("\\s{2,}");
            String verdict = said[said.length - 1];
            boolean fallback = line.contains("VectorSupport::")
                ? !verdict.equals("(intrinsic)") && !verdict.equals("late inline succeeded")
                : verdict.startsWith("failed to inline") && !COLD_LOOKUP.matcher(line).find();
            if (fallback) {
              current.add(line.strip().replaceAll("\\s+", " "));
            }
          }
        }
        require(last != null, "the intrinsic check found no level-4 compile of " + method);
        require(sites || scalar, "the intrinsic check found no Vector API site in " + method
            + "'s compile");
        return last;
      }

      /** The fallbacks counted by the method each calls. */
      private static String summary(List<String> refusals) {
        var counts = new TreeMap<String, Integer>();
        for (String r : refusals) {
          // A site reads "@ <bci> <class>::<method> (<n> bytes) <verdicts>".
          String site = r.split(" ")[2];
          counts.merge(site.substring(site.lastIndexOf('.') + 1), 1, Integer::sum);
        }
        return refusals.size() + " Vector API call(s) left in Java, by method: " + counts;
      }

      /** The intrinsic check's child JVM: calls the loop it names until C2 has compiled it. */
      static final class IntrinsicProbe {

        private IntrinsicProbe() {
        }

        public static void main(String[] args) {
          Arm arm = VarkaNanosSpikeCheck.arms().stream()
              .filter(a -> a.method().equals(args[0])).findFirst()
              .orElseThrow(() -> requirementFailed("no arm's loop is " + args[0]));
          try (Arena arena = Arena.ofConfined()) {
            Data d = VarkaNanosSpikeCheck.benchmarkData(arena, PROBE_ROWS);
            for (int c = 0; c < PROBE_CALLS; c++) {
              sink += arm.loop().apply(d.cols(), PROBE_ROWS);
            }
          }
        }
      }

      @Override
      public void runBenchmarkSuite(String[] mainArgs) {
        List<String> ops = opsAsked();
        List<Arm> all = VarkaNanosSpikeCheck.arms();
        for (String op : ops) {
          require(all.stream().anyMatch(a -> a.op().equals(op)), "-Dvarka.nanos.ops names " + op
              + ", which is no operation of an arm");
        }
        // ROUND is every cost's base and the control proves the intrinsic check, so both are in
        // every run, whatever operations it is asked for.
        var arms = all.stream()
            .filter(a -> ops.isEmpty() || a.op().equals("ROUND") || a.op().equals("HOURC")
                || ops.contains(a.op()))
            .filter(a -> only().map(a.label()::contains).orElse(true)).toList();
        require(!arms.isEmpty(), "no arm's label holds \"" + only().orElse("") + "\"");
        if (dryRun()) {
          return;
        }
        requireHarness();
        section("nanosecond timestamp hand loops at " + VarkaNanosSpikeOps.LS.vectorBitSize()
            + " bits" + (ops.isEmpty() ? "" : ": " + String.join(" ", ops)), () -> {
          var labels = new HashMap<Arm, String>();
          for (Arm arm : arms) {
            List<String> refusals = intrinsicRefusals(arm);
            labels.put(arm, arm.label() + (refusals.isEmpty() ? "" : " [Java fallback]"));
            note("intrinsic check, " + arm.label() + ": " + (refusals.isEmpty()
                ? "every call intrinsified" : summary(refusals)));
          }
          for (Rung rung : LADDER) {
            runRung(rung, arms, labels);
          }
        });
      }

      private void runRung(Rung rung, List<Arm> arms, Map<Arm, String> labels) {
        int n = rung.numRows();
        int driveReps = (int) Math.max(1, ROWS_PER_DRIVE / n);
        try (Arena arena = Arena.ofConfined()) {
          Data d = VarkaNanosSpikeCheck.benchmarkData(arena, n);
          Data small = VarkaNanosSpikeCheck.benchmarkData(arena, PROBE_ROWS);
          // A table an operation, its arms in the generator's order; the arms with a call left in
          // Java in a second table, whose iterations are short: a fallback's time is never read.
          var tables = new TreeMap<String, List<Arm>>();
          for (Arm arm : arms) {
            if (n == LADDER.get(0).numRows() || pastL2(arm)) {
              boolean fallback = !labels.get(arm).equals(arm.label());
              tables.computeIfAbsent(arm.op() + (fallback ? ", Java fallback" : ""),
                  title -> new ArrayList<>()).add(arm);
            }
          }
          for (var entry : tables.entrySet()) {
            int reps = (int) Math.max(1, (entry.getKey().endsWith("fallback") ? ROWS_PER_DRIVE
                : ROWS_PER_ITERATION) / n);
            var benchmark = benchmark(n + " rows - " + rung.level() + ", " + entry.getKey(),
                (long) n * reps).timed().build();
            for (Arm arm : entry.getValue()) {
              VarkaNanosSpikeCheck.requireAgrees(arm, d, "before it is timed", false);
              var settled = new AtomicBoolean();
              benchmark.addTimerCase(labels.get(arm), timer -> {
                if (settled.compareAndSet(false, true)) {
                  driveToCompiledCode(arm, d, small, driveReps);
                  VarkaNanosSpikeCheck.requireAgrees(arm, d, "in compiled code", false);
                }
                timer.startTiming();
                drive(arm, d, reps);
                timer.stopTiming();
              });
            }
            runCases(benchmark);
            // What each compiled loop allocates, read after its timing: the intrinsic check compiles
            // a loop alone, and a loop it passed can still box beside every other arm's compiles.
            for (Arm arm : entry.getValue()) {
              note("allocated, " + arm.label() + " at " + n + " rows: "
                  + allocatedPerRow(arm, d, driveReps) + " bytes a row");
            }
          }
        }
      }

      /** The bytes this thread allocates a row over one drive of {@code arm}, to one decimal. */
      private static String allocatedPerRow(Arm arm, Data d, int reps) {
        var threads = (com.sun.management.ThreadMXBean) ManagementFactory.getThreadMXBean();
        long before = threads.getCurrentThreadAllocatedBytes();
        drive(arm, d, reps);
        long bytes = threads.getCurrentThreadAllocatedBytes() - before;
        return String.format(java.util.Locale.ROOT, "%.1f", bytes / ((double) d.numRows() * reps));
      }
    }

### File: VarkaNanosSpikeLoopsControl.java

    /*
     * Licensed to the Apache Software Foundation (ASF) under one or more
     * contributor license agreements.  See the NOTICE file distributed with
     * this work for additional information regarding copyright ownership.
     * The ASF licenses this file to You under the Apache License, Version 2.0
     * (the "License"); you may not use this file except in compliance with
     * the License.  You may obtain a copy of the License at
     *
     *    http://www.apache.org/licenses/LICENSE-2.0
     *
     * Unless required by applicable law or agreed to in writing, software
     * distributed under the License is distributed on an "AS IS" BASIS,
     * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
     * See the License for the specific language governing permissions and
     * limitations under the License.
     */

    package org.apache.spark.sql;

    import static jdk.incubator.vector.VectorOperators.ASHR;
    import static jdk.incubator.vector.VectorOperators.D2L;
    import static jdk.incubator.vector.VectorOperators.L2D;
    import static jdk.incubator.vector.VectorOperators.L2I;
    import static org.apache.spark.sql.VarkaNanosSpikeOps.IS;
    import static org.apache.spark.sql.VarkaNanosSpikeOps.L;
    import static org.apache.spark.sql.VarkaNanosSpikeOps.LE;
    import static org.apache.spark.sql.VarkaNanosSpikeOps.LS;

    import java.lang.foreign.MemorySegment;
    import java.util.List;

    import jdk.incubator.vector.DoubleVector;
    import jdk.incubator.vector.LongVector;
    import jdk.incubator.vector.VectorSpecies;

    import org.apache.spark.sql.VarkaNanosSpikeOps.Arm;
    import org.apache.spark.sql.VarkaNanosSpikeOps.Cols;

    /**
     * The study's control (VARKA-662): {@code hour} of Spark's microseconds with the divisions the
     * engine's long lane has today, through double lanes ({@code VarkaNodeCompiler.floorDivLong},
     * {@code VarkaDivisionLowering.emitDoubleDivide}). C2 lowers {@code L2D} and {@code D2L} only
     * with AVX-512DQ, so on an AVX2 host this loop must show Vector API calls left in Java. A run
     * whose intrinsic check does not say so there has an intrinsic check that proves nothing; a run
     * with AVX-512DQ says how the conversion form compares with the multiply-high.
     */
    final class VarkaNanosSpikeLoopsControl {

      private VarkaNanosSpikeLoopsControl() {
      }

      private static final VectorSpecies<Double> DS = DoubleVector.SPECIES_PREFERRED;

      /** A day of microseconds is {@code 2^13 * 10546875}; an hour of them {@code 2^10 * 3515625}. */
      private static final long DAY_ODD = 10_546_875L;
      private static final long DAY_US = 86_400_000_000L;
      private static final double HOUR_ODD = 3_515_625;

      static List<Arm> arms() {
        return List.of(new Arm("HOURC", 1, "L", 1, 1, "loopHourcR1LA1Z1",
            VarkaNanosSpikeLoopsControl::loopHourcR1LA1Z1));
      }

      /** HOUR in R1, layout L, the divisions through double lanes: reads aM; writes oi. */
      static long loopHourcR1LA1Z1(Cols c, int n) {
        MemorySegment aM = c.aM;
        MemorySegment oi = c.oi;
        for (int r = 0; r < n; r += L) {
          LongVector m = LongVector.fromMemorySegment(LS, aM, r * 8L, LE);
          // floorDiv(m, a day): the shift floors by 2^13, a negative value moves down by the odd
          // part less one, and the truncating division by the odd part then floors.
          LongVector y = m.lanewise(ASHR, 13);
          LongVector down = y.lanewise(ASHR, 63).and(1 - DAY_ODD);
          LongVector day = (LongVector) ((DoubleVector) y.add(down).convertShape(L2D, DS, 0))
              .div((double) DAY_ODD).convertShape(D2L, LS, 0);
          LongVector ofDay = m.sub(day.mul(DAY_US));
          LongVector hour = (LongVector) ((DoubleVector) ofDay.lanewise(ASHR, 10)
              .convertShape(L2D, DS, 0)).div(HOUR_ODD).convertShape(D2L, LS, 0);
          hour.convertShape(L2I, IS, 0).intoMemorySegment(oi, r * 4L, LE);
        }
        return 0;
      }
    }

### File: dev/varka_nanos_spike_proofs.py

    #!/usr/bin/env python3
    #
    # Licensed to the Apache Software Foundation (ASF) under one or more
    # contributor license agreements.  See the NOTICE file distributed with
    # this work for additional information regarding copyright ownership.
    # The ASF licenses this file to You under the Apache License, Version 2.0
    # (the "License"); you may not use this file except in compliance with
    # the License.  You may obtain a copy of the License at
    #
    #    http://www.apache.org/licenses/LICENSE-2.0
    #
    # Unless required by applicable law or agreed to in writing, software
    # distributed under the License is distributed on an "AS IS" BASIS,
    # WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    # See the License for the specific language governing permissions and
    # limitations under the License.
    #
    """The proof files of the nanosecond representations' study (VARKA-662).

        python3 dev/varka_nanos_spike_proofs.py write sql/varka/proofs   write the two files
        python3 dev/varka_nanos_spike_proofs.py check sql/varka/proofs   they are what this writes

    nanos_representations.smt2: each conversion between the four forms, written with Java's wrapping
    operators, is the exact value under its range condition, and is not one step outside it.
    nanos_divisions.smt2: each division constant of dev/varka_nanos_spike.py is exact over its whole
    dividend range. nanos_guards.smt2: each guard's lane formula has its sign bit set exactly where
    the value is outside the guard's range. The ranges and the constants are that script's, so a
    proof cannot keep proving a number the loops no longer have. dev/varka_prove.sh runs the files.
    """

    import importlib
    import os
    import sys

    sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
    sp = importlib.import_module("varka_nanos_spike")
    assert len(sp.FORMS) == 4, "the proofs need dev/varka_nanos_spike_r34.py beside this file"

    LICENSE = "".join(
        "; " + line[2:] if len(line) > 2 else ";\n"
        for line in open(os.path.abspath(sp.__file__)).readlines()[2:16]
    )


    def n(v):
        return "(- %d)" % -v if v < 0 else "%d" % v


    def rng(var, lo, hi):
        return "(and (<= %s %s) (<= %s %s))" % (n(lo), var, var, n(hi))


    class File:
        def __init__(self, comment):
            self.out = [LICENSE.rstrip("\n"), ""] + ["; " + c for c in comment]
            self.out += ["", "(set-logic ALL)"]

        def line(self, text):
            self.out.append(text)

        def check(self, title, verdict, asserts):
            self.out += ['(echo "%s: expect %s")' % (title, verdict), "(push 1)"]
            self.out += ["(assert %s)" % a for a in asserts]
            self.out += ["(check-sat)", "(pop 1)", ""]

        def text(self):
            return "\n".join(self.out).rstrip("\n") + "\n"


    def representations():
        comment = [
            "The representations of a nanosecond timestamp and the conversions between them",
            "(VARKA-662). R1 is (m, n): epoch microseconds and the nanoseconds within one, 0 to 999.",
            "R2 is x: epoch nanoseconds. R3 is (s, f): epoch seconds and the nanoseconds of the",
            "second. R4 is (d, t): epoch days and the nanoseconds of the day. Each conversion below",
            "is written with Java's wrapping long operators and is the exact value under its range",
            "condition; one step outside the condition it is not.",
            "Written by dev/varka_nanos_spike_proofs.py. DO NOT EDIT: change the script.",
        ]
        f = File(comment)
        f.line("(define-fun DAYNS () Int %d)" % sp.DAY_NS)
        f.line("(define-fun DAYUS () Int %d)" % sp.DAY_US)
        for name in ("m", "nn", "x", "s", "f", "d", "t"):
            f.line("(declare-const %s Int)" % name)
        f.line("(assert (and (jlong.in m) (jlong.in x) (jlong.in s) (jint.in d)))")
        f.line("(assert (and (<= 0 nn) (<= nn 999) (<= 0 f) (< f 1000000000) (<= 0 t) (< t DAYNS)))")
        f.line("; The exact count of nanoseconds of each form.")
        f.line("(define-fun v1 () Int (+ (* m 1000) nn))")
        f.line("(define-fun v3 () Int (+ (* s 1000000000) f))")
        f.line("(define-fun v4 () Int (+ (* d DAYNS) t))")
        f.line("")

        def guarded(tag, what, var, bounds, define, exact, word):
            """A conversion exact inside `bounds` of `var`, and not exact one step outside."""
            for d in define:
                f.line(d)
            f.check(
                "%s, %s: exact for %s in the range" % (tag, what, var),
                "unsat",
                [rng(var, *bounds), "(not %s)" % exact],
            )
            f.check(
                "%s: one step under the range it wraps for some %s" % (tag, word),
                "sat",
                ["(= %s %s)" % (var, n(bounds[0] - 1)), "(not %s)" % exact],
            )
            f.check(
                "%s: one step over the range it wraps for some %s" % (tag, word),
                "sat",
                ["(= %s %s)" % (var, n(bounds[1] + 1)), "(not %s)" % exact],
            )

        def whole(tag, what, define, claim):
            for d in define:
                f.line(d)
            f.check("%s, %s: exact for every value" % (tag, what), "unsat", ["(not %s)" % claim])

        guarded(
            "E12",
            "R1 to R2",
            "m",
            sp.R2_M,
            ["(define-fun x12 () Int (jlong.add (jlong.mul m 1000) nn))"],
            "(= x12 v1)",
            "n",
        )
        whole(
            "E21",
            "R2 to R1",
            [
                "(define-fun m21 () Int (jlong.floorDiv x 1000))",
                "(define-fun n21 () Int (jlong.sub x (jlong.mul m21 1000)))",
            ],
            "(and (<= 0 n21) (<= n21 999) (= (+ (* m21 1000) n21) x))",
        )
        back = (sp.R2_M[0] * 1000, sp.R2_M[1] * 1000 + 999)
        f.check(
            "E21 then E12: x comes back for x from %d to %d" % back,
            "unsat",
            [rng("x", *back), "(not %s)" % rng("m21", *sp.R2_M)],
        )
        f.check(
            "E21 then E12: at %d the first word is past E12's range" % (back[1] + 1),
            "sat",
            ["(= x %s)" % n(back[1] + 1), "(not %s)" % rng("m21", *sp.R2_M)],
        )
        f.check(
            "E21 then E12: at %d the first word is under E12's range" % (back[0] - 1),
            "sat",
            ["(= x %s)" % n(back[0] - 1), "(not %s)" % rng("m21", *sp.R2_M)],
        )
        whole(
            "E13",
            "R1 to R3",
            [
                "(define-fun s13 () Int (jlong.floorDiv m 1000000))",
                "(define-fun f13 () Int"
                " (jlong.add (jlong.mul (jlong.sub m (jlong.mul s13 1000000)) 1000) nn))",
            ],
            "(and (<= 0 f13) (< f13 1000000000) (= (+ (* s13 1000000000) f13) v1))",
        )
        guarded(
            "E31",
            "R3 to R1",
            "s",
            sp.R1_S,
            [
                "(define-fun q31 () Int (div f 1000))",
                "(define-fun m31 () Int (jlong.add (jlong.mul s 1000000) q31))",
                "(define-fun n31 () Int (- f (* q31 1000)))",
            ],
            "(and (<= 0 n31) (<= n31 999) (= (+ (* m31 1000) n31) v3))",
            "f",
        )
        whole(
            "E14",
            "R1 to R4",
            [
                "(define-fun d14 () Int (jlong.floorDiv m DAYUS))",
                "(define-fun t14 () Int"
                " (jlong.add (jlong.mul (jlong.sub m (jlong.mul d14 DAYUS)) 1000) nn))",
            ],
            "(and (<= 0 t14) (< t14 DAYNS) (= (+ (* d14 DAYNS) t14) v1) %s)" % rng("d14", *sp.R1_DAYS),
        )
        guarded(
            "E41",
            "R4 to R1",
            "d",
            sp.R1_D,
            [
                "(define-fun q41 () Int (div t 1000))",
                "(define-fun m41 () Int (jlong.add (jlong.mul d DAYUS) q41))",
                "(define-fun n41 () Int (- t (* q41 1000)))",
            ],
            "(and (<= 0 n41) (<= n41 999) (= (+ (* m41 1000) n41) v4))",
            "t",
        )
        whole(
            "E23",
            "R2 to R3",
            [
                "(define-fun s23 () Int (jlong.floorDiv x 1000000000))",
                "(define-fun f23 () Int (jlong.sub x (jlong.mul s23 1000000000)))",
            ],
            "(and (<= 0 f23) (< f23 1000000000) (= (+ (* s23 1000000000) f23) x))",
        )
        guarded(
            "E32",
            "R3 to R2",
            "s",
            sp.R2_S,
            ["(define-fun x32 () Int (jlong.add (jlong.mul s 1000000000) f))"],
            "(= x32 v3)",
            "f",
        )
        whole(
            "E24",
            "R2 to R4",
            [
                "(define-fun d24 () Int (jlong.floorDiv x DAYNS))",
                "(define-fun t24 () Int (jlong.sub x (jlong.mul d24 DAYNS)))",
            ],
            "(and (<= 0 t24) (< t24 DAYNS) (jint.in d24) (= (+ (* d24 DAYNS) t24) x))",
        )
        guarded(
            "E42",
            "R4 to R2",
            "d",
            sp.R2_D,
            ["(define-fun x42 () Int (jlong.add (jlong.mul d DAYNS) t))"],
            "(= x42 v4)",
            "t",
        )
        whole(
            "E34",
            "R3 to R4",
            [
                "(define-fun d34 () Int (jlong.floorDiv s 86400))",
                "(define-fun t34 () Int"
                " (jlong.add (jlong.mul (jlong.sub s (jlong.mul d34 86400)) 1000000000) f))",
            ],
            "(and (<= 0 t34) (< t34 DAYNS) (= (+ (* d34 DAYNS) t34) v3) (=> %s (jint.in d34)))"
            % rng("s", *sp.R4_S),
        )
        f.check(
            "E34: one second over the range the day is no int",
            "sat",
            ["(= s %s)" % n(sp.R4_S[1] + 1), "(not (jint.in d34))"],
        )
        whole(
            "E43",
            "R4 to R3",
            [
                "(define-fun q43 () Int (div t 1000000000))",
                "(define-fun s43 () Int (jlong.add (jlong.mul d 86400) q43))",
                "(define-fun f43 () Int (- t (* q43 1000000000)))",
            ],
            "(and (<= 0 f43) (< f43 1000000000) (= (+ (* s43 1000000000) f43) v4))",
        )
        f.line("; A second value of each form, for the orders.")
        for name in ("m2", "nn2", "s2", "f2", "d2", "t2"):
            f.line("(declare-const %s Int)" % name)
        f.line("(assert (and (jlong.in m2) (jlong.in s2) (jint.in d2)))")
        f.line(
            "(assert (and (<= 0 nn2) (<= nn2 999) (<= 0 f2) (< f2 1000000000) (<= 0 t2) (< t2 DAYNS)))"
        )
        for form, hi, lo, hi2, lo2, val, val2 in (
            ("R1", "m", "nn", "m2", "nn2", "v1", "(+ (* m2 1000) nn2)"),
            ("R3", "s", "f", "s2", "f2", "v3", "(+ (* s2 1000000000) f2)"),
            ("R4", "d", "t", "d2", "t2", "v4", "(+ (* d2 DAYNS) t2)"),
        ):
            f.check(
                "order of %s: the first word, then the second, is the order of the exact values" % form,
                "unsat",
                [
                    "(not (= (or (< %s %s) (and (= %s %s) (< %s %s))) (< %s %s)))"
                    % (hi, hi2, hi, hi2, lo, lo2, val, val2)
                ],
            )
            f.check(
                "equality of %s: equal words are equal values and the reverse" % form,
                "unsat",
                ["(not (= (and (= %s %s) (= %s %s)) (= %s %s)))" % (hi, hi2, lo, lo2, val, val2)],
            )
        f.check(
            "cast to microseconds: the first word of R1 is the floor of the exact value by 1000",
            "unsat",
            ["(not (= m (div v1 1000)))"],
        )
        f.check(
            "cast to microseconds: the truncating quotient differs for some value",
            "sat",
            ["(not (= m (trunc.div v1 1000)))"],
        )
        return f.text()


    def guards():
        comment = [
            "The guards of the nanosecond representations' study loops (VARKA-662). A guard is one",
            "lane formula whose sign bit is set where a value is outside its range; a loop ORs it",
            "into one accumulator and reads the sign once. Three forms: the window,",
            "(v - lo) | (hi - v); the sum, (x ^ r) & (y ^ r) for r = x + y; and the floor, x & ~r for",
            "r the floor of x to a multiple. Each is stated over the signs of its wrapping terms: the",
            "sign of a | b is set where either's is, of a & b where both are, of a ^ b where the two",
            "differ, and of ~a where a's is not. dev/varka_nanos_spike.py computes each with the bit",
            "operators themselves, on every case it runs, and holds it to the same exact test.",
            "Written by dev/varka_nanos_spike_proofs.py. DO NOT EDIT: change the script.",
        ]
        f = File(comment)
        for name in ("v", "x", "y", "lo", "hi", "w"):
            f.line("(declare-const %s Int)" % name)
        f.line(
            "(assert (and (jlong.in v) (jlong.in x) (jlong.in y) (jlong.in lo) (jlong.in hi)"
            " (jint.in w)))"
        )
        f.line("; The window's sign in long lanes and in int lanes; the sum's; the floor's.")
        f.line(
            "(define-fun win ((a Int) (l Int) (h Int)) Bool"
            " (or (< (jlong.sub a l) 0) (< (jlong.sub h a) 0)))"
        )
        f.line(
            "(define-fun wini ((a Int) (l Int) (h Int)) Bool"
            " (or (< (jint.sub a l) 0) (< (jint.sub h a) 0)))"
        )
        f.line(
            "(define-fun ovf ((a Int) (b Int)) Bool (and (distinct (< a 0) (< (jlong.add a b) 0))"
            " (distinct (< b 0) (< (jlong.add a b) 0))))"
        )
        f.line("(define-fun flr ((a Int) (h Int)) Int (jlong.mul (jlong.floorDiv a h) h))")
        f.line("(define-fun wrapped ((a Int) (h Int)) Bool (and (< a 0) (>= (flr a h) 0)))")
        f.line("")
        out = "(or (< %s %s) (> %s %s))"
        f.check(
            "window: exact for every long, whatever the window, where hi - lo is under 2^63",
            "unsat",
            [
                "(and (<= lo hi) (<= (- hi lo) jlong.MAX))",
                "(not (= (win v lo hi) %s))" % (out % ("v", "lo", "v", "hi")),
            ],
        )
        f.check(
            "window: a window of 2^63 or more is not exact, so a bound at an end of a long"
            " takes another form",
            "sat",
            [
                "(= lo jlong.MIN)",
                "(= hi %s)" % n(sp.MAX - sp.C_US),
                "(not (= (win v lo hi) %s))" % (out % ("v", "lo", "v", "hi")),
            ],
        )
        f.check(
            "window of int lanes: exact for every int where hi - lo is under 2^31",
            "unsat",
            [
                "(and (jint.in lo) (jint.in hi) (<= lo hi) (<= (- hi lo) jint.MAX))",
                "(not (= (wini w lo hi) %s))" % (out % ("w", "lo", "w", "hi")),
            ],
        )
        windows = [
            ("R%d to R%d, %s" % (src, dst, word), bounds)
            for (src, dst), (word, bounds) in sorted(sp.WINDOWS.items())
        ]
        for name, (lo, hi) in windows + [("the interval of a sum in R2, q", sp.R2_M)]:
            f.check(
                "window %s in %d to %d: exact for every long" % (name, lo, hi),
                "unsat",
                ["(not (= (win v %s %s) %s))" % (n(lo), n(hi), out % ("v", n(lo), "v", n(hi)))],
            )
            f.check(
                "window %s: one under and one over decline, the two ends do not" % name,
                "unsat",
                [
                    "(not (and (win %s %s %s) (win %s %s %s) (not (win %s %s %s))"
                    " (not (win %s %s %s))))"
                    % (
                        n(lo - 1),
                        n(lo),
                        n(hi),
                        n(hi + 1),
                        n(lo),
                        n(hi),
                        n(lo),
                        n(lo),
                        n(hi),
                        n(hi),
                        n(lo),
                        n(hi),
                    )
                ],
            )
        for name, (lo, hi) in (("R4 to R1, d", sp.R1_D), ("R4 to R2, d", sp.R2_D)):
            f.check(
                "window %s in int lanes: exact for every int" % name,
                "unsat",
                ["(not (= (wini w %s %s) %s))" % (n(lo), n(hi), out % ("w", n(lo), "w", n(hi)))],
            )
        f.check(
            "sum: the sign is set exactly where x + y is no long",
            "unsat",
            ["(not (= (ovf x y) (not (jlong.in (+ x y)))))"],
        )
        for name, c in (("the literal in R1", sp.C_US), ("the literal in R2", sp.C_US * 1000)):
            f.check(
                "sum of %s: declines exactly above %d" % (name, sp.MAX - c),
                "unsat",
                ["(not (= (ovf x %d) (> x %d)))" % (c, sp.MAX - c)],
            )
        for name, c in (
            ("the zone's offset in R1", sp.Z_O0 * sp.E6),
            ("the zone's offset in R2", sp.Z_O0 * sp.E9),
        ):
            f.check(
                "sum of %s: declines exactly under %d" % (name, sp.MIN - c),
                "unsat",
                ["(not (= (ovf x %s) (< x %s)))" % (n(c), n(sp.MIN - c))],
            )
        for name, hour, c in (("R1", sp.HOUR_US, sp.C_US), ("R2", sp.HOUR_NS, sp.C_US * 1000)):
            low = sp.hour_floor_low(hour)
            f.check(
                "floor to an hour in %s: the sign is set exactly under %d" % (name, low),
                "unsat",
                ["(not (= (wrapped x %d) (< x %s)))" % (hour, n(low))],
            )
            f.check(
                "floor to an hour in %s: from %d on the lanes' floor is the exact one" % (name, low),
                "unsat",
                ["(>= x %s)" % n(low), "(not (= (flr x %d) (* (div x %d) %d)))" % (hour, hour, hour)],
            )
            f.check(
                "floor to an hour in %s: after the literal's sum it needs no guard" % name,
                "unsat",
                ["(jlong.in (+ x %d))" % c, "(< (+ x %d) %s)" % (c, n(low))],
            )
        return f.text()


    def divisions():
        comment = [
            "The divisions by a constant of the nanosecond representations' study loops",
            "(VARKA-662): each constant that dev/varka_nanos_spike.py finds, exact over its whole",
            "dividend range. A bounded dividend takes one multiply and a shift, or two multiplies",
            "and a repair of a quotient one short; a floor division of every long takes the high 64",
            "bits of a product, over the complement for a negative long.",
            "Written by dev/varka_nanos_spike_proofs.py. DO NOT EDIT: change the script.",
        ]
        f = File(comment)
        f.line("(declare-const u Int)")
        for d, umax in sp.BOUNDED:
            form = sp.udiv_form(d, umax)
            if form[0] == "MULSHIFT":
                _, k, m, p = form
                quotient = "(div (* (div u %d) %d) %d)" % (1 << k, m, 1 << p)
                what = "one multiply"
            elif form[0] == "DIVFIX":
                _, k, m, p, odd = form
                q1 = "(div (* (div u %d) %d) %d)" % (1 << k, m, 1 << p)
                r = "(- (div u %d) (* %s %d))" % (1 << k, q1, odd)
                quotient = "(ite (>= %s %d) (+ %s 1) %s)" % (r, odd, q1, q1)
                what = "two multiplies and the repair"
            else:
                _, k, m, s = form
                quotient = "(div (* (div u %d) %d) %d)" % (1 << k, m, 1 << (64 + s))
                what = "the multiply-high"
            f.check(
                "division of 0 to %d by %d, %s: exact" % (umax, d, what),
                "unsat",
                ["(and (<= 0 u) (<= u %d))" % umax, "(not (= %s (div u %d)))" % (quotient, d)],
            )
            if form[0] != "MULHIGH":
                # The check above multiplies in exact integers; a lane's product wraps at 2^64.
                f.check(
                    "division of 0 to %d by %d: the lane's product is under 2^64" % (umax, d),
                    "unsat",
                    [
                        "(and (<= 0 u) (<= u %d))" % umax,
                        "(not (< (* (div u %d) %d) 18446744073709551616))" % (1 << k, m),
                    ],
                )
        for d in sp.FLOORS:
            k, m, s = sp.find_mul_high(d)
            f.check(
                "floor division of every long by %d: the multiply-high of a non-negative long" % d,
                "unsat",
                [
                    "(and (<= 0 u) (<= u 9223372036854775807))",
                    "(not (= (div (* (div u %d) %d) %d) (div u %d)))" % (1 << k, m, 1 << (64 + s), d),
                ],
            )
            f.check(
                "floor division of every long by %d: a negative long through its complement" % d,
                "unsat",
                [
                    "(and (jlong.in u) (< u 0))",
                    "(not (= (jlong.not (div (jlong.not u) %d)) (div u %d)))" % (d, d),
                ],
            )
        return f.text()


    FILES = {
        "nanos_representations.smt2": representations,
        "nanos_divisions.smt2": divisions,
        "nanos_guards.smt2": guards,
    }


    def main(argv):
        if len(argv) != 2 or argv[0] not in ("write", "check"):
            print(__doc__)
            return 2
        stale = []
        for name, render in sorted(FILES.items()):
            path, text = os.path.join(argv[1], name), render()
            if argv[0] == "write":
                with open(path, "w") as out:
                    out.write(text)
                print("%s: %d checks" % (name, text.count("(check-sat)")))
            elif not os.path.exists(path) or open(path).read() != text:
                stale.append(name)
        if stale:
            print("stale: " + ", ".join(stale) + "; run write")
            return 1
        return 0


    if __name__ == "__main__":
        sys.exit(main(sys.argv[1:]))
