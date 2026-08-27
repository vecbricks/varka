# Varka Milestone 4 Scope: breadth

This is a scope document, not a task plan - the shape `PLAN_MILESTONE_3.md` had
before its measurements turned it into one. It is written ahead of milestone 3's
execution because milestone 3 was carrying items that are not reach:
`TimestampNTZ` lanes, the calendar extraction family and boolean outputs were
its tasks 22-24, and they are vocabulary. They live here now, together with the
deferrals that file's section 8 already listed.

Milestone 1 built kernels. Milestone 2 built the emitter and proved it on int32
date chains. Milestone 3 makes that fast path reach real queries. Milestone 4 is
**breadth**: the engine stops being a single-type demo and learns the types,
expressions and operators a query actually contains.

Task numbering continues the project's single sequence and resumes at 23, after
milestone 3's 18-22. No task numbers are assigned here on purpose. This file
becomes a task plan when milestone 3's measurements - and the survey section 5
asks for - can order it, exactly as milestone 3's own catalogue became one.

## 1. Why breadth, and why the Vector API is the map

Varka compiles eighteen expression classes over one type.
`VarkaExpressionCompiler` accepts `DateAdd`, `DateSub`, `DateDiff`,
`DayOfWeek`, `WeekDay`, `Greatest`, `Least`, `If`, `CaseWhen` and int-valued
`Literal`s as values, and the five
comparisons plus `And`/`Or`/`Not` as interior conditions - all over `DateType`
columns. Everything else declines to Janino. Milestone 3 widens *where* that
vocabulary applies: filters, real predicates, cached scans. It does not add a
word to the vocabulary itself.

A second reading agrees with the first. An audit of `jdk.incubator.vector` in
JDK 25 against every call the emitter emits and every call the hand-written
kernels make found Varka using roughly two dozen distinct members and operator
constants out of some 1,200 - overloads counted - across the module's fourteen
exported types. Five types are touched: `IntVector`, `VectorMask`,
`VectorSpecies`, `VectorOperators`, and `Vector` (that one only as the erased
parameter type in emitted descriptors). Nine are not touched at all.

That gap is a map rather than an indictment. The API's unused groups line up
close to one-to-one with families of Spark expressions the engine cannot
express, which is what makes them usable as this milestone's skeleton - with the
standing caveat that an API tells you what is *possible*, never what is
*profitable*. Section 5 says what should decide the order instead.

## 2. What the engine can say today

* **One lane type.** `VarkaVectorIR.LaneType` has a single value, `INT`, and the
  emitter rejects anything else rather than assuming. Milestone 2 built that
  door deliberately; nothing has walked through it.
* **One species.** `IntVector.SPECIES_PREFERRED`, read with `getstatic` so it
  stays a JIT constant - the thing that makes C2 intrinsify the loop at all.
* **Fourteen `IntVector` members**: the `SPECIES_PREFERRED` field, `broadcast`,
  `fromMemorySegment`, `intoMemorySegment`, `add`, `sub`, `mul`, `div`, `and`,
  `min`, `max`, `compare`, `blend`, `lanewise(Binary, int)`. Six
  `VectorOperators` constants: `LT`, `LE`, `GT`, `GE`, `EQ`, `LSHR`.
* **Masks round-trip through `long` bitmaps.** `VectorMask.fromLong` and
  `toLong` against Arrow validity, with `and`/`or`/`not` for the three-valued
  algebra. No mask is ever *interrogated*: `anyTrue`, `allTrue`, `trueCount` and
  `firstTrue` are unused, so no lane group is ever skipped or branched on.
* **The tail is a second walk of the IR**, emitted as scalar bytecode node by
  node. The API's own answer to a partial lane group, `indexInRange`, is unused.

## 3. Three invariants, and why they decide the task split

Every item below breaks at least one of milestone 2's structural invariants.
Which one it breaks matters more than how many Spark expressions it unlocks,
because it decides what can share a task:

1. **One lane width per kernel.** An expression mixing int32 with int64 or
   double changes the loop's trip count, not just a type tag on an IR node.
   Items 1, 2, 3, and the multiply half of item 4.
2. **Every value is lane-shaped.** A kernel reads N lanes and writes N lanes; a
   reduction produces one value per batch, which nothing in the emitted method
   shape can hold today. Item 7.
3. **No lane reads its neighbour.** Compaction, windows and prefix sums all
   move data across lanes. Items 8, 9, 10.

A task that takes two of these at once is a task whose failure cannot be
attributed to one of them. The ordering in section 5 keeps them apart.

## 4. Scope catalogue

### Item 1. Lane-width conversion, and mixed-type expression trees

**Spark surface.** Every numeric `Cast`; the implicit promotions Catalyst
inserts everywhere (`int + long`, `int * double`); `date` to `timestamp`; and
any expression whose result width differs from its inputs', such as a
`datediff`-shaped int result over int64 lanes.

**Vector API it needs**, none of it used today: `Vector.convert`,
`convertShape`, `castShape`, `reinterpretShape`; the forty
`VectorOperators.Conversion` constants (`I2L`, `D2I`, and the rest);
`VectorSpecies.withLanes`, `withShape`, `partLimit`; and `VectorShape` itself.

**Design input.** The hard part is not the conversion, it is the lane count. At
one shape an int32 species holds twice the lanes of an int64 species, so
`convertShape(I2L, longSpecies, part)` yields *one long vector per part* and
`partLimit` says how many parts exist. A kernel whose IR mixes widths must
therefore choose: drive the loop at the narrowest lane count and leave the wide
lanes half empty, or emit a part loop per conversion and carry two trip counts.
That is a measurable choice and should be measured before either is built into
the emitter - it is the one decision in this item that is expensive to reverse.
Note also that Spark's narrowing `Cast` throws under ANSI and wraps without it,
which ties this item to item 4.

**Where it sits.** Early, because items 2, 3 and the multiply half of 4 are all
cheaper once the width machinery exists. Each of them can also be built
width-locked and retrofitted, which is the fallback if part loops measure badly.

### Item 2. int64 lanes: `TimestampNTZ`, `bigint`, and the second lane width

*Moved from milestone 3 (its task 24, catalogue item 1).*

**Spark surface.** `TimestampNTZType` - pure int64 microseconds - with
comparisons, differences and literal arithmetic; comparisons and diffs on
`TimestampType`; `LongType` columns generally. Zoned day and month arithmetic
stays out until its semantics are written down as carefully as milestone 2's
section 2.6 wrote the date ones.

**Vector API it needs**: the whole `LongVector` family (150 overloads),
`VectorMask` at the long species, and `reduceLanesToLong` if item 7 lands.

**Design input**, kept from milestone 3. `LongVector` halves the lanes, so every
parity gate reruns at both widths and the per-row cost of the same expression
roughly doubles: a chain that ran 2x over Janino at int32 has less headroom at
int64, and that is a number to commit rather than a surprise to discover.
Second-to-day and micros-to-second conversions are divisions by invariant
constants (86400, 1000000); the Vector API has no multiply-high on long lanes,
so task 17's technique - narrow the range first, then a low-32-bit magic
multiply - is the first thing to try. The fallback is lanewise `DIV`, which the
parity file prices at roughly an eighth of the magic-multiply rate (652 against
5657 M rows/s on the `dayofweek` case), so the difference is worth the work.
When zoned operations do enter, the datetime vector-algorithms note names the
technique: pack the IANA tzdata transitions into flat `long[]` interval arrays
and resolve a vector of timestamps against them with a SIMD binary search,
rather than per-row `ZoneRules` lookups.

**Where it sits.** The natural first *type*: the only one whose semantics are
already written down and whose expressions Varka already compiles at another
width.

### Item 3. Float and double lanes, and the numeric function family

*Moved from milestone 3 (its catalogue item 7, "wider species").*

**Spark surface.** `DoubleType` and `FloatType` arithmetic; `abs`, `signum`,
`sqrt`, `exp`, `log`, `log10`, `pow`, `hypot`, `atan2`, the trig family;
`isnan`, `nanvl`.

**Vector API it needs**: `DoubleVector` and `FloatVector`; `lanewise(Unary, ..)`
with `SQRT`, `EXP`, `LOG`, `LOG10`, `CBRT`, `SIN` through `TANH`, `EXPM1`,
`LOG1P`; `lanewise(Binary, ..)` with `POW`, `ATAN2`, `HYPOT`; the `FMA` ternary;
and the `Test` operators `IS_NAN`, `IS_INFINITE`, `IS_FINITE` with
`Vector.test`.

**Design input**, and the reasons this item is not as cheap as its op count
suggests:

* *The transcendentals are real vector calls.* JDK 25 ships `libjsvml.so` inside
  the `jdk.incubator.vector` module, and `VectorMathLibrary` looks its symbols
  up through a `SymbolLookup` at first use - so `lanewise(EXP, ..)` on x64
  reaches Intel's SVML port rather than a per-lane `Math.exp` loop. What
  aarch64 does instead must be checked before any doc claims the same.
* *So the oracle has to change.* Every Varka differential so far has been
  byte-exact against the row path. SVML is not bit-identical to `Math` and
  `StrictMath`, so a double differential must be ULP-bounded, and Spark's own
  accuracy guarantee for these functions has to be read before a bound is
  picked. This is the milestone's single largest correctness decision, and it
  should be settled in writing first, the way section 2.6 settled dates.
* *Comparison is not IEEE.* Spark's equality and ordering on doubles go through
  `SQLOrderingUtil.compareDoubles`, which is `if (x == y) 0 else
  java.lang.Double.compare(x, y)`: NaN equals NaN, NaN sorts above everything,
  and `-0.0` equals `0.0`. `VectorOperators.EQ` and `LT` are IEEE, where NaN
  compares false against everything including itself. Every emitted double
  comparison therefore needs an explicit NaN fix-up on the mask.
  `NormalizeFloatingNumbers` does not save us: it rewrites only window partition
  keys and equi-join keys, so a plain projection's comparison arrives
  unnormalised.
* *`round` and `DecimalType` are not this item.* `round(x, n)` is
  scale-dependent and decimals are not a lane type at all - see item 11.

**Where it sits.** The largest expression count of any item, gated behind the
oracle decision. Take the decision early even if the kernels come late.

### Item 4. ANSI-correct integer arithmetic, priced rather than assumed

*Moved from milestone 3 (its catalogue item 6).*

**Spark surface.** `Add`, `Subtract`, `Multiply`, `UnaryMinus` and `Abs` on
`IntegerType` and `LongType` - which is to say most arithmetic in most queries -
plus `try_add`, `try_subtract`, `try_multiply`, and `Divide` by zero.
`datediff(d2, d1) + 1` is the shape that keeps appearing in date work and cannot
compile today.

**Vector API it needs**: `VectorMath.addSaturating` and `subSaturating` (and the
unsigned variants), the `SADD`, `SSUB`, `SUADD`, `SUSUB` binary operators, and
`VectorMask.anyTrue` to leave the loop.

**Design input.** Milestone 3 recorded this as "still a trap - a SIMD lane
cannot throw row-accurately for free". That is true, and the API makes the trap
cheaper than it reads. Overflow detection is one extra op per arithmetic node:
compute the wrapping `add` and the saturating `addSaturating` over the same
inputs, and the lanes where they differ are exactly the lanes that overflowed;
`compare(NE, ..).anyTrue()` then decides whether to leave the vector loop at
all. On the overwhelmingly common no-overflow path that is one vector op and one
well-predicted branch. When the branch is taken, a scalar re-walk of the
offending lane group raises the ANSI error against the right row - which is the
ghost-fallback discipline the project already runs on.

Two gaps to size before committing:

* There is no saturating *multiply*. `Multiply` overflow has to widen to long
  lanes and compare against the narrowed result, which makes it item 1's
  dependent rather than this item's.
* `try_add` and friends want *nulls*, not throws - the easy case, and possibly
  the whole of a first task: the difference mask becomes output validity and no
  branch is needed at all. If the ANSI path prices badly, `try_*` alone is still
  worth shipping.

`date_add` stays exempt: it wraps by spec, which is why milestone 2 could ship
it at all.

### Item 5. Boolean outputs

*Moved from milestone 3 (its task 22, catalogue item 4).*

**Spark surface.** Comparisons and `And`/`Or`/`Not` as projection *results*
rather than interior conditions - `SELECT d > date '2000-01-01' AS flag` - and
the boolean columns a filter's pushdown leaves behind.

**Vector API it needs**: `VectorMask.toVector`, or a `blend` of one and zero if
that measures better; `trueCount` if the output wants a count. The bit-packing
is a memory-format problem rather than an API one.

**Design input.** Cheap once milestone 3's task 21 makes masks first-class
values that leave the loop. The only new format question is Spark's bit-packed
boolean vector against Arrow's, and the three-valued rules have to hold at the
output boundary exactly as they hold in the interior - a null input must produce
a null output, not a false one.

**Where it sits.** The cheapest item here, and the only pure continuation of
milestone 3. It goes first if task 21 lands clean.

### Item 6. Calendar field extraction, `year` first

*Moved from milestone 3 (its task 23, catalogue item 5).*

**Spark surface.** `year` first, then `month`, `dayofmonth`, `quarter`,
`dayofyear`, `weekofyear`, and `date_trunc` at date-level units.

**Vector API it needs**: nothing new. This is the one vocabulary item that fits
inside milestone 2's machinery as it stands - int32 lanes, the operators the
emitter already emits, and the range-narrowed magic-multiply technique task 17
recorded. Its cost is algorithm work, not API work, which is what makes it a
good first task rather than a big one.

**Design input**, kept from milestone 3. The candidate algorithms are
Neri-Schneider (branchless O(1) civil-from-days over the 400-year Gregorian
cycle, the preferred fit for lanes), Cassels' March-shifted year (month and day
from a linear formula like `(5 * d + 2) / 153`), and Hinnant's `std::chrono`
decomposition, the one Velox and DuckDB borrow. All three lean on division by
invariant constants. The calibration that keeps this honest: TPC-DS
pre-materialises calendar parts as integer dimension columns (`d_year`, `d_moy`,
`d_dom`, `d_qoy`, `d_dow`), so extraction appears zero times there, while TPC-H
q7, q8 and q9 use `year(date)` and nothing else. Intuition overweights this
item; the corpus says it is one function wide.

**Where it sits.** First or second - no new invariant, and `year` alone is what
stands between Varka and a TPC-H projection.

### Item 7. Aggregation: the first horizontal reduction

*Moved from milestone 3 (its catalogue item 7, "heavier operators").*

**Spark surface.** `HashAggregateExec`'s partial aggregation without grouping
keys, to begin with: `sum`, `min`, `max`, `count`, `avg`, `bit_and`, `bit_or`,
`bit_xor`, `bool_and`, `bool_or`. Then the shape milestone 3's survey named and
declined to take: `CASE WHEN <date cmp> THEN x ELSE 0 END` inside `sum(..)`
(TPC-DS q21 and q40). That one is aggregate-*input* fusion - Varka computes the
CASE, the aggregate consumes the result - which is a different wiring from the
projection path and is the reason it was deferred rather than folded in.

**Vector API it needs**: `reduceLanes(Associative)` and its masked overload,
`reduceLanesToLong`, and the `Associative` operator set - `ADD`, `MUL`, `MIN`,
`MAX`, `AND`, `OR`, `XOR`, `FIRST_NONZERO`.

**Design input.** The reduction belongs at the *end* of the batch, not per lane
group: accumulate into vector accumulators inside the loop and reduce once, with
the multi-accumulator unrolling the design notes describe (acc0 through acc3,
breaking the dependency chain a single accumulator's adds would form). The
masked `reduceLanes` overload handles nulls without a branch. `sum` over
`LongType` inherits item 4's overflow question, and `avg` is `sum` plus a
`trueCount`. Grouped aggregation is *not* this item - grouping is hashing and
partitioning, which is item 8's machinery and probably its own milestone.

**Where it sits.** The first item that changes what an operator *is* rather than
what an expression computes, so it wants milestone 3's plan-shape lessons from
filters behind it.

### Item 8. Byte and short lanes: strings, hashing, dictionaries

*Absorbs milestone 3's catalogue item 12, SWAR string-to-date parsing.*

**Spark surface.** The largest and least tractable: `length`, `upper` and
`lower` on the ASCII fast path, `LIKE 'prefix%'`, `startswith`, `endswith`,
`contains`, `substring`, and `cast(string AS DATE)` done properly rather than
folded - milestone 3's task 20 folds only the literal case. Alongside them,
`hash`, `xxhash64` and `murmur3`, which are join and grouped-aggregation
machinery more than an expression family. It also picks up the plain bit
expressions there is no reason to keep skipping: `bit_count`, `shiftleft`,
`shiftright`, `shiftrightunsigned`, and the bitwise `&`, `|`, `^`, `~`.

**Vector API it needs**: `ByteVector` and `ShortVector`; `VectorShuffle` in
full, with `rearrange` and `selectFrom` (including the two-vector overload) for
byte permutation and dictionary decode; `ROL` and `ROR` with `rotateLeft` and
`rotateRight`, since murmur3's mix is rotate-multiply-xor; `BIT_COUNT`,
`LEADING_ZEROS_COUNT`, `TRAILING_ZEROS_COUNT`, `REVERSE`, `REVERSE_BYTES`;
`BITWISE_BLEND` with `bitwiseBlend`; `COMPRESS_BITS` and `EXPAND_BITS`; and
`LSHL` and `ASHR`, the two shifts the emitter has never had a use for.

**Design input.**

* *Variable width is the whole problem.* Arrow strings are offsets plus bytes,
  so every operation is data-dependent in length and the fixed-lane-count loop
  stops being the right shape. This is why milestone 3 pushed SWAR date parsing
  into its own design pass, and why it should stay there.
* *SWAR, kept from milestone 3 item 12*: load the digit bytes as one word,
  subtract `0x30303030`, collapse to an integer with a multiply-add - three or
  four instructions per field, no per-character loop - and validate the
  separators with one vector compare whose failing lanes send the whole batch to
  the existing parser. That fallback shape is the ghost-fallback discipline
  again, which is why the idea fits this project at all.
* *There is no off-heap gather.* Gather and scatter exist only on the `int[]`
  array overloads (`fromArray(species, a, off, indexMap, mapOff)`), never on
  `MemorySegment`. A dictionary decode over an Arrow dictionary in off-heap
  memory therefore has no vector gather available at all: it either copies the
  dictionary on-heap or uses `rearrange`/`selectFrom` with a dictionary small
  enough to sit in one vector. Write that constraint into any dictionary design
  before costing it.

**Where it sits.** Last, and probably past this milestone's edge. It is written
out in full because the gather constraint is cheap to learn now and expensive to
learn mid-task.

### Item 9. Cross-lane movement: windows, prefix sums, row indices

**Spark surface.** `WindowExec` where the frame lives inside one batch: `lag`
and `lead` by a small constant offset, running aggregates over `ROWS BETWEEN
UNBOUNDED PRECEDING AND CURRENT ROW`, `row_number` within a batch, and the
standalone `monotonically_increasing_id`.

**Vector API it needs**: `slice(int, Vector)` and `unslice` - the cross-lane
shift that *is* `lag` and `lead`; `addIndex`; `VectorSpecies.iotaShuffle`;
`rearrange`.

**Design input.** `lag(x, 1)` across a lane group is exactly `slice(lanes - 1,
previousVector)`, and a running sum is the classic log-step prefix scan: shift
by 1, 2, 4 and add, which is `slice` plus `add` and nothing else. What makes
this an operator change rather than an expression change is the carry - a window
frame crosses batch boundaries, so the kernel needs carry-in and carry-out
state and the partition boundary has to be visible to the loop. That is a
contract like milestone 3's selection vector, not a new IR node.

**Where it sits.** After item 7. It shares the not-lane-shaped problem and adds
a state contract on top of it.

### Item 10. Compaction, mask interrogation, and the scalar tail

**Spark surface.** No new expressions - this item makes existing ones cheaper,
and makes every other item in this file cheaper to write.

**Vector API it needs**: `Vector.compress` and `expand`, `VectorMask.compress`;
`anyTrue`, `allTrue`, `trueCount`, `firstTrue`, `laneIsSet`, `toVector`; and
`VectorSpecies.indexInRange` with `VectorMask.indexInRange`.

**Design input.**

* *The tail is the argument.* `indexInRange` produces the mask for a partial
  lane group directly, which would let the emitter drop its scalar tail
  entirely. That tail is currently a *second full walk of the IR*, emitting
  scalar bytecode for every node - so this is not a micro-optimisation but
  roughly half of the emitter's per-node code, and every new node type in items
  2, 3, 4 and 6 otherwise has to be written twice. Price it before those items
  double the surface.
* *Compaction.* `compress(mask)` is the primitive a selection vector wants. On
  x64 with AVX-512 it intrinsifies to `VPCOMPRESSD`; elsewhere it falls back to
  something considerably slower. If milestone 3's task 21 ships a scalar
  compaction loop, this is the follow-up that replaces it - and it has to be
  measured on a machine *without* AVX-512 too, because the development machine
  (Zen 5, `avx512f` through `avx512_bf16` in `/proc/cpuinfo`) will flatter it.
* *Interrogation.* `anyTrue` and `allTrue` give per-lane-group all-null and
  all-valid fast paths, where the prologue today has them only per batch.

**Where it sits.** Early, on the tail argument alone.

### Item 11. Considered and set aside

Recorded so they are not re-proposed:

* **`Float16`**, JDK 25's addition to the module: no Spark type maps to it, not
  even through Arrow. Out unless Spark grows one.
* **Unsigned comparison and min/max** (`ULT`, `ULE`, `UGT`, `UGE`, `UMIN`,
  `UMAX`, `VectorMath.minUnsigned` and `maxUnsigned`): Spark has no unsigned
  integer type. They stay available as *internals* - hash bucketing,
  `shiftrightunsigned` - but they are not an expression family.
* **`DecimalType`**: not a lane type. Precision up to 18 fits an int64 unscaled
  value and could ride item 2, but the general case is 128-bit and the Vector
  API has no 128-bit lane at any species, while scale alignment on multiply and
  divide needs exactly that wide intermediate. It needs its own design pass, not
  an item here - and that pass is `SCOPE_MILESTONE_5.md` items 1 and 2, which the
  benchmark survey made urgent: decimals are what TPC-DS and TPC-H add up.
* **`CPUFeatures`**: the natural way to ask whether AVX-512 compaction is
  available, and not usable - the outer class is package-private, so only the
  JDK's own code can read `CPUFeatures.X64.SUPPORTS_AVX512F`. Any fallback
  decision has to come from a measurement or from the species width, never from
  a feature query.
* **Hash joins**: out of this milestone and milestone 3 both. The Hydra split
  has them as scalar probing over off-heap tables with SIMD reserved for radix
  partitioning and post-probe projection; they want item 7's machinery first,
  and a milestone of their own after it.
* **`reinterpretAs*` and `viewAs*`**: bit-level reinterpretation, useful
  *inside* item 3 for NaN canonicalisation and float-bit tricks, but not an
  item.

## 5. Ordering, and the survey that should set it

The ordering below is an argument, not a decision. Milestone 3's ordering came
from a corpus survey - 53-78% of date-column references sit in WHERE clauses,
and the whole corpus holds exactly five DATE-typed projection expressions -
which is why its spine is filters rather than functions. **That survey has since
been run** and lives in `SCOPE_MILESTONE_5.md` section 1: a census by type and
family over the in-tree TPC-DS and TPC-H corpus, plus the taxi benchmark's
published queries. Its section 2 records what the count corrects in this file,
and the three findings that matter most here are:

* No `DOUBLE` or `FLOAT` column exists in either TPC-DS or TPC-H, so item 3's
  "largest expression count" is worth nothing on those two corpora and
  everything on taxi. Re-argue item 3 as the taxi benchmark's item; its rank
  below follows whichever corpus is chosen as the headline.
* `DecimalType`, which item 11 sets aside, is the most-aggregated type in both
  benchmarks (247 aggregate-argument references against 172 for `INT`). The
  judgement that it needs its own design pass stands; that pass is milestone 5's
  items 1 and 2.
* Item 8 is really two items. String *functions* are rare (`substr(` 23,
  `upper(` 2, `LIKE` 8) while string *keys* are everywhere (275 group-by
  references), so the equality-and-grouping subset is pulled forward into
  milestone 5's item 3, and the functions stay here.

Item 6's own calibration survived the count: `year(` appears 3 times and
`month(`, `quarter(` and `dayofweek(` zero times, exactly as it predicted.

With that said, the argued order below stands for the items the survey did not
move:

| Order | Item | Why here |
|---|---|---|
| 1 | 10, tail and compaction | Halves the per-node emitter surface every later item would otherwise pay twice |
| 2 | 6, `year` | No new invariant, and the one function TPC-H needs |
| 3 | 5, boolean outputs | Pure continuation of milestone 3's task 21 |
| 4 | 1, lane widths | The enabler for items 2 and 3, and for multiply overflow in 4 |
| 5 | 2, int64 and `TimestampNTZ` | The first new type; semantics already written |
| 6 | 4, ANSI integer arithmetic | The most common expression family; one op to detect |
| 7 | 3, float and double lanes | The largest expression count, behind the oracle decision |
| 8 | 7, aggregation | First operator change; wants filters' lessons first |
| 9 | 9, windows | Adds a state contract on top of item 7's problem |
| 10 | 8, strings and hashing | Variable width; likely its own milestone |

A defensible spine is the first five - items 10, 6, 5, 1, 2 - with 4 and 3
following if their decisions (the overflow price, the ULP oracle) land early
enough to be measured rather than argued.

## 6. Verification, inherited

The gates do not change, and two of them get harder:

* Differential against the row engine over every new shape, null patterns
  included, at the preferred width **and** `-XX:MaxVectorSize=16` - now at every
  lane width the milestone adds, not just the vector widths.
* Parity: an emitted loop stays at or above the hand-written kernel where one
  exists; committed results regenerated in a single run on an idle machine.
* The ghost fallback still never fails a query.
* **New:** the byte-exact oracle stops being universal. Item 3 needs a
  ULP-bounded differential and item 4 needs an error-*identity* differential -
  the same `SparkException` with the same row - which is a kind of assertion the
  suites have never had to make.

## 7. Explicitly out of milestone 4

* **Grouped aggregation, hash joins, sorting.** Item 7 stops at partial
  aggregation without grouping keys.
* **`DecimalType`**, per item 11.
* **The Arrow-native Parquet reader and writer** (milestone 3, item 11): still
  the project owner's work, and still the thing that would make every number
  here apply to scans rather than cached tables. Coordinate, do not duplicate.
* **Buffer alignment enforcement** (milestone 3, item 8): unchanged, still
  waiting on a measurement that shows it matters.
* **Whole-stage code generation.** Milestone 3's task 22 answers the charter
  question in writing; whatever it answers, this milestone does not build it.

## 8. Open questions, to settle before this becomes a task plan

1. **The ULP oracle** (item 3). What accuracy does Spark promise for `exp`,
   `log`, `pow` and the trig family, and what bound does a vector differential
   assert? Every double kernel waits on this, and it is a reading task, not a
   measurement.
2. **Mixed-width loop shape** (item 1). Narrowest-lane-count drive, or a part
   loop per conversion? Measure both on a `cast(int AS long) + long` chain
   before either goes into the emitter.
3. **What the scalar tail actually costs** (item 10). Its share of emission time
   and of loop runtime is not measured. If it is negligible at runtime, item
   10's case rests entirely on emitter code size - still a good case, but a
   different one, and it should be made on the honest number.
4. **Where the survey's corpus ends** (section 5). TPC-DS and TPC-H answered the
   date question; they may not answer the type question, since neither uses
   `TimestampNTZ` and both pre-materialise calendar parts. If the corpus cannot
   rank the types, the ranking has to come from somewhere else - and saying so
   now is cheaper than discovering it in task three.
