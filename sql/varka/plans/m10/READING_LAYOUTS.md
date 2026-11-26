# Reading for milestone 8: column layouts and translation validation

Read on 11 October 2026 at the owner's request, after item 91 was added from a weak arXiv paper
on bit-sliced predicates: the canonical bit-sliced scan, three modern lightweight-compression
papers, and the origin of translation validation. None was in the record: a search of
`SKILLS.md`, the plans and the lessons found no mention of BitWeaving, BtrBlocks or Necula, and
FastLanes and ALP only by name. Three surveys, one per group, each read the whole paper and
answered against the items it bears on: 7 (the Arrow cache's physical layout), 90 (translation
validation of emitted kernels) and 91 (bit-sliced columns for filters).

Every claim carries how it was checked:

* **[A]** a survey read it in the paper, at the page given, and the author of this note did not
  re-read it;
* **[I]** the survey's own arithmetic or inference, not a claim of the paper.

Page numbers are the copies the surveys read: BitWeaving's 12-page SIGMOD author copy; FastLanes'
printed PVLDB pages 2132 to 2144; BtrBlocks' TUM author version, PDF pages 1 to 14; ALP's CWI
preprint (`ir.cwi.nl/pub/33334`), PDF pages, whose published layout may differ; Necula's 12-page
author copy, not the proceedings pages 83 to 95. Numbers read off a plot are marked "fig" and
are good to about a tenth of a cycle. No number below was measured by this project.

| paper | venue | copy read |
| :--- | :--- | :--- |
| Li and Patel, "BitWeaving: Fast Scans for Main Memory Data Processing" | SIGMOD 2013 | `pages.cs.wisc.edu/~jignesh/publ/BitWeaving.pdf` |
| Kuschewski, Sauerwein, Alhomssi and Leis, "BtrBlocks" | SIGMOD 2023 | TUM author version |
| Afroozeh and Boncz, "The FastLanes Compression Layout" | PVLDB 16(9), 2023 | vldb.org |
| Afroozeh, Kuffo and Boncz, "ALP: Adaptive Lossless floating-Point Compression" | SIGMOD 2024 | CWI preprint |
| Necula, "Translation Validation for an Optimizing Compiler" | PLDI 2000 | `people.eecs.berkeley.edu/~necula/Papers/tv_pldi00.pdf` |

BitWeaving's extended version, whose appendices hold the lookup, the SIMD and the selectivity
results, could not be obtained; what it would add is unread.

## 1. BitWeaving (Li and Patel, SIGMOD 2013)

**Two layouts** [A]. *BitWeaving/H* stores each k-bit code in a field of k + 1 bits whose top bit
is a delimiter, about w/(k + 1) codes a word, segments of k + 1 words (p.3), and compares with
Lamport-style word arithmetic, about three or four instructions a word, the result bits put in
row order with k + 1 shifts and ORs (Alg.1, pp.4-5); cost O(n(k + 1)/w) (Table 1, p.8).
*BitWeaving/V* transposes each segment of w codes into k words, word i holding bit i of every
code (p.5), and compares most significant plane first (Alg.2, p.5): `mlt |= meq & C_i & ~v_i`,
then `meq &= ~(v_i ^ C_i)`, each constant bit an all-ones or all-zeros word; cost O(nk/w) (p.8).

**Early pruning** [A]. A segment stops once `meq` is all zero, with probability
P(b) = (1 - 2^-b)^(w f) of having stopped by bit b (p.6). /V groups planes four at a time so a
pruned scan stays sequential and checks the cut-off once a group (pp.7-8, Fig.9); without that
care for the branch predictor it ran up to 40% slower (p.8). Pruning is a net loss on /H (p.8).
Each predicate yields one bit per row; a result vector seeds the next column's `meq`, which
lowers the fill factor and cuts off sooner (pp.2, 7-8).

**Lookup** [A]. Reading a value back is "poor" for /V, its bits spread across the bit groups
(p.9). Measured on random positions: /H about 200 cycles flat; /V about 380 cycles at 12 bits
and 650 at 32; /V with pruning about 1380 at 32, from cache misses across groups (Fig.11c, fig,
pp.10-11).

**The evaluation** [A]. A 2.67 GHz six-core Xeon, 12 MB L3, 64-bit ALU and 128-bit SSE, g++ 3.4.6
-O3, one thread (p.9); one billion uniform codes, `COUNT(*) WHERE a < C` at 10% selectivity, so
always out of cache (p.9). The baselines all pay an unpack: a naive shift-and-mask scan,
SIMD-Scan (packed codes unpacked into four 32-bit lanes), whole-column bit-slicing, and a
Blink-like packed scan (pp.9-10). **No baseline is a vector compare over unpacked int32.** At
12 bits, in cycles a code (Fig.10a, fig, p.9): naive 8.8, SIMD-Scan 5, Blink-like 3,
whole-column bit-sliced 1.7, /H 1.0, /V 0.9; /V stays near 1 past 12 bits (p.10), and is about
0.3 at 4 bits (Fig.1, p.1). /V's L3 misses stay near 0.002 a code where whole-column planes
reach 0.056 at 32 bits (Fig.10b, fig), the gain of interleaving planes per segment. 128-bit SIMD
gave only 20% on /V and 10% on /H: 40% fewer instructions, 1.5 times the CPI (p.10). Pruning
pays only past about 16 bits (Fig.11a, fig). TPC-H SF10 Q6, with `l_shipdate` in 12 bits at
about 2% selectivity, in cycles a row (fig, p.11): naive 33, SIMD 28, Blink-like 10.5,
whole-column 8, /H 3.5, /V 5 - /V loses because it must extract the 24-bit `l_extendedprice`;
used as an index beside a normal column it gets about 30% faster (p.11).

**Against item 91** [I]. The paper never measures a vector compare over an int32 column, so it
does not answer item 91. From Alg.2: `<` on 11 bits with the constant fixed at emission costs a
load and one or two logical operations a plane, about 30 to 35 operations a W codes, against a
load and a compare per W/32 codes, about 64 a W codes, for a vertical compare (plus turning the
mask into bitmap bits); BETWEEN about 55 against 128. The bytes are 1.375 a code against 4, a
2.9 times cut that likely dominates out of the last-level cache; in cache the gap is the
operation ratio, under two. /V's result is already a W-bit row bitmap, Varka's selection form.
Pruning does nothing at 11 bits and gets worse with width: P(8) is about 0.78 at w = 64 and
0.37 at w = 256, P(11) 0.97 against 0.88; and its cut-off is a data-dependent branch in a
branch-free kernel. /V needs only `and`, `or`, `xor` and `andNot` on `LongVector`, no popcount,
no PEXT; /H needs lane-wise add and shift in 64-bit lanes, which the Vector API has, so the
paper's 2013 "limited SIMD" verdict on /H (p.8) no longer holds.

**Against the record's ByteSlice reading** [A]. ByteSlice's early-stop probability is this
paper's P(b) (p.6), checked every four planes per 64-code segment (p.8). "A load per slice" is
right for the bit-grouped layout; with segment-contiguous planes one random lookup is about one
cache miss and k word operations (p.11). The paper's own answer to projections is an index
beside the normal column (p.11, and up to about 15% on Q5 and Q17, p.12), which supports reading
bit-slicing as a filter-side cache layout only.

**Critique** [A]. 2010-era hardware, 128-bit SSE, an old compiler, one thread (p.9); uniform
random data at 10% selectivity by default, the sweep in the unread appendix (p.9); every run out
of cache; the strongest baseline unpacks on every scan; lookup measured only at random positions,
never as converting a batch back; no build or encode cost; P(b) assumes independent random bits
(p.6), which a clustered or sorted date column is not.

## 2. BtrBlocks, FastLanes and ALP

### 2.1 BtrBlocks (Kuschewski et al., SIGMOD 2023)

**Schemes and their choice** [A]. RLE, One Value, Dictionary, Frequency (one top value, a bitmap
and exceptions), SIMD-FastPFOR, SIMD-FastBP128, FSST, Roaring for nulls and a new Pseudodecimal
(Table 1, p.2; Fig.3, p.4), over blocks of 64,000 values (p.2); the pool grew by adding schemes
where Bzip2 beat it and dropping slow ones (p.5). Statistics (min, max, unique count, average run
length) exclude schemes first - RLE under runs of two, Frequency at half the values unique (p.4)
- and the rest compress a sample of ten runs of 64 values from random non-overlapping places,
about 1% of the block. That costs 1.2% of compression time, picks the best scheme 77% of the
time and loses 3.3% against the best (p.9); runs of 16 or more do about equally well (p.9).
Schemes cascade, up to three levels by default (p.4).

**Results** [A]. Decompression 2.6, 3.6 and 3.8 times Parquet, Parquet with Snappy and Parquet
with Zstd on Public BI (p.10), 2.6, 3.9 and 4.2 on TPC-H (p.11); ratio 5.28 against 3.37
(Parquet) and 6.05 (Parquet with Zstd), and on integers 6.70 against 5.73 and 6.97 (Table 2,
p.7); a scan from S3 1.8 times cheaper than Parquet with Zstd (Table 5, p.12). With every decoder
scalar it is 17% slower, still 2.3 times the best Parquet (p.12). The vectorised dictionary
decode is an AVX2 gather (Listing 3, p.6), and Pseudodecimal falls back to scalar for any block
holding an exception (p.7). It decodes into a plain array, never into a pipeline; open formats
"should optimize for raw decompression speed first", and operating on compressed data is possible
"in principle" (p.13).

**Critique** [A]. One machine, a Skylake c5n.18xlarge, AVX2 code (p.7); only each Public BI
dataset's largest table (p.8); sampling accuracy scored on each column's first block (p.9); date
and timestamp columns dropped (p.8); decompression to memory with no query; Parquet and ORC tuned.

### 2.2 FastLanes (Afroozeh and Boncz, PVLDB 2023)

**The layout** [A]. Values are packed against a virtual 1024-bit register: consecutive W-bit
values spread round-robin across S = 1024/T lanes of T bits (Fig.1, p.2134). The instructions are
LOAD, STORE, AND_LSHIFT, AND_RSHIFT, AND, OR, XOR, ADD and SET - no permute, shuffle or gather
(Listing 1, p.2135); a narrower machine runs the same instructions over adjacent chunks
(pp.2134-2135), and plain C++ written so is auto-vectorised by clang about as well as
hand-written intrinsics (Fig.8, pp.2139-2140). The kernels are generated, 116 of them, one per
packed width and lane width (p.2135). Unpacking alone yields "perfectly sequential unpacked
integers" (Fig.3 caption, p.2135); only DELTA and FastLanes-RLE need the "04261537" tile order
(pp.2136-2137), and then every column of a table must share it (footnote 4, p.2137).

**Encodings and speed** [A]. FOR and DICT decoded into compressed vectors are "just bit-unpacking"
(p.2140); DELTA keeps a base a lane, about a bit a value, about 3% if the bases are themselves
delta-coded (pp.2142-2143); RLE becomes one-bit DELTA plus a dictionary and wins under runs of
about 333 (Fig.16, p.2143). Up to 70 values a cycle at T = 8, W = 1 - 140 billion a second at
2 GHz, a 1024-value vector in 17 cycles (pp.2133, 2135); 40 to 70 times scalar at 8-bit lanes,
3 to 4 times at 64-bit (p.2139); measured on Ice Lake 8375C, Zen3, Zen4, M1, Graviton2 and
Graviton3 (Table 2, p.2139). Fusing unpack with FOR saves a store and a load (Fig.11, p.2140).
Against SIMD-BP128's 4-way layout the interleaved one gains 2 times on AVX2 and 4 on AVX-512
where 4-way barely moves (Fig.13, p.2141), and costs nothing in scalar code (Fig.9, p.2140);
Zen4 is no faster than Zen3 (p.2140). Bit-sliced layouts (BitWeaving, ByteSlice) give no fast
decompression, VBP unpacking about 30 times slower (p.2141).

**Into Vector API lanes** [I]. A T = 32 row is four `IntVector` loads at 256 bits, two at 512 or
eight at 128, under the same constant shifts and masks; T = 64 is `LongVector`. The operations
are contiguous `fromMemorySegment` loads, a logical right shift and AND, an OR with the
left-shifted piece of the next word for a straddling value (Listing 2, p.2135), and an ADD of the
base for FOR. Use LSHR, never ASHR, on packed words; and T is fixed at encode time, so it must
match the kernel's lane (32 for days, 64 for micros) or pay a widening convert. The 116 generated
kernels map onto one emitted class per width in Varka's emitter.

**Critique** [A]. The microbenchmarks repeat one vector 30 million times, all in L1, turbo off
(p.2139); the one end-to-end test is SUM over a uniform random uint32 column (p.2141); no
compression ratios on real data; exceptions and cascading left to future work (p.2143).

### 2.3 ALP (Afroozeh, Kuffo and Boncz, SIGMOD 2024)

**The encoding** [A]. A double encodes as `round(n x 10^e x 10^-f)` and decodes as
`d x 10^f x 10^-e`, one exponent e and factor f a 1024-value vector, f stripping the trailing
zeros a large e creates (pp.5-6), rounded with the add-and-subtract 2^51 + 2^52 trick (p.6).
A value that fails the round trip is an exception, stored raw in 64 bits with a 16-bit position,
its slot filled with the first value that encoded (p.7). The integers then go through FastLanes'
FFOR, FOR fused with bit-packing (p.7): ALP is built on FastLanes. *ALP_rd*, for doubles that are
not decimals, cuts each value at a bit p of at least 48, bit-packs the low part and dictionary-
codes the front bits (at most eight entries, 16-bit exceptions), decoded scalar, a shuffle
suggested rather than a gather (pp.7-8); it is about four times slower to decode and compresses
at most about 1.2 times (p.11). Sampling is two-level: eight vectors of 32 values a row-group of
100 vectors keep the five best (e, f) pairs, then 32 values a vector with early exit (p.8; the
paper swaps its m and n between pp.7 and 8), about 6% of compression time and under 1% worse
than brute force (p.11).

**Results** [A]. Average bits a value (Table 4, p.9): ALP 21.7, Zstd 20.6, Elf 23.1, Chimp128
28.7, PDE 31.4, Patas 35.5, Chimp 37.7, Gorilla 42.2; ALP cascaded after Dictionary or RLE 18.8.
Values decoded a cycle on Ice Lake (Table 5, p.10): ALP 2.609, PDE 0.387, Zstd 0.101, Gorilla
0.047; encoding 0.487. Fusing with FFOR is a median of about 40% faster (p.10). An end-to-end SCAN
on one thread: 1.337 values a cycle against 0.565 uncompressed (Table 6, p.11).

**For Varka's doubles** [I]. The encode-time check and the kernel must multiply in the same
order, `(d x F10[f]) x i_F10[e]`, with no FMA fusion: ALP checks each value with the decode
expression itself (Algorithm 1, p.6). An int64-to-double convert is not one AVX2 instruction;
ALP met a related problem on Zen3 (p.10); to be confirmed with JIT flags on the laptop.

**Critique** [A]. Speed is one vector repeated 300,000 times (p.10); the 30 datasets are the
competitors', 28 of them decimals at source (p.8); Zstd at level 3 on 1 MB blocks; Elf absent
from the end-to-end test, which covers five datasets in Tectorwise (p.11). None of the three
papers tests a date or timestamp column.

## 3. Translation validation (Necula, PLDI 2000)

**What is compared** [A]. gcc's RTL dumped before and after each of up to 13 passes, each pair a
source S against a target T (pp.1, 9), in an IL with explicit memory, `sel`, `upd` and `updcall`
(p.2). Two executions are equivalent if they make the same sequence of calls and returns with
equal memory, arguments and results (p.3). A simulation relation is a set of triples
(PC_S, PC_T, E), E equalities between S's and T's temporaries, correct if executions started in
states satisfying E are equivalent (pp.2-3). It is checked row by row: walk both sides to a
return or another related pair, then discharge that pair's constraints; correctness by
induction, termination given enough rows (p.3).

**How the relation is found** [A]. Symbolic evaluation turns each basic block into a pure
function of the memory and live registers at entry (Fig.4, pp.5-6), which alone absorbs
scheduling, renaming and the reordering CSE causes (p.5). *Scan* walks both programs in step over
pairs of blocks (Fig.5, p.7); at each source branch *Branch* chooses among eliminating either
arm, the same branch, or the same branch reversed, by heuristic scores - the conditions' shape,
call-sequence prefixes, related successors, gcc's instruction serial numbers and line notes
(pp.7-8); *Solve* substitutes simple constraints, pushes them back to predecessors, puts
`E1 - E2 = 0` into a linear canonical form and simplifies `sel` and `upd` by gcc's own aliasing
rules (pp.8-9). Loops are handled only through related pairs at their heads (pp.3-4); target
branches must copy source branches, so one of gcc's four unrolling variants is out of reach
(pp.6, 8). Spill slots become fresh temporaries, which undoes spilling (p.3). The checker is "a
pattern matcher" transcribing logical rules (Fig.3, p.5), 10,500 lines of OCaml - symbolic
evaluation about 1,000, Solve about 3,000 - against a single gcc pass of over 10,000 lines of C
(pp.5, 9), with an occurs check before substitution (p.8). It needs nothing from gcc but its
dumps, though Necula argues compiler-supplied branch annotations or whole relations are the
robust answer (pp.8, 11).

**Results** [A]. False alarms by pass, branch failures and unsolved constraints (Table 2, p.10):
branch 1.2% and 1.3%, CSE 1.5% and 3.5%, loop 3.0% and 3.2%, register allocation 0% and 0.1%,
scheduling 0% and 0.01%; their causes unrolling and adjacent conditionals CSE merged, and most
unsolved constraints a wrong Branch guess rather than a weak solver (p.10); about 10% of
functions alarm for some pass (p.1). Over gcc 2.91 (5,909 functions, 4 million RTL instructions)
and Linux 2.2 (Table 1). Validating a pass costs about four times the pass, doubled with dump and
parse, almost all in the solver (p.10). Known register-allocation and unrolling bugs in gcc
2.7.2.2 showed up as residual constraints; the 100-line unrolling test also raised three false
alarms (p.10).

**Against item 90** [I]. Symbolic evaluation of both sides into pure terms applies directly: the
IR is already a DAG, and symbolically evaluating the JVM's operand stack and locals erases slot
planning and local reuse as it erased register allocation; hash-consed shared terms (p.10) make
CSE across outputs invisible. A kernel is one vector loop and a scalar tail, so the relation is
four rows - entry, the vector loop head (`i` equal, `i % VL == 0`, memory equal on the written
prefix), the tail's head, exit - which the emitter can write out itself, Necula's recommended
route (p.11), with no Branch heuristics. Necula's linear canonical form treats `+` and `*` as
integers, unsound for int32 and int64 wrap-around (the paper does not discuss overflow): Varka
needs bit-vector terms, which VARKA-240's Z3 and cvc5 setup already discharges. Beyond Necula:
a lane model (a vector as N bit-vectors, compares to masks, blends, `convertShape` halves as
slices), a memory model of `MemorySegment` (byte arrays a column, little-endian, in-bounds side
conditions), the tail equal to per-lane IR evaluation on [n - n % VL, n), validity packing
checked per bit (`bit j of word w = valid(row 64w + j)`), and a refused batch as an observable
event, as Necula's calls are; scratch outputs are don't-care, as his spill memory is (Fig.3).

**Risks, as Varka would meet them** [I]. Substituted CSE terms blow up unless shared (p.10):
hash-cons and memoise from the start. Weak simplifiers cause false alarms (p.9): magic-number
division and the date arithmetic go to the existing SMT lemmas, not to rewrite rules. A wrong
structural match cascades (p.10): grouping outputs into methods under byte budgets changes the
call structure, so either inline the generated methods symbolically or validate each method
against the sub-DAG the emitter says it implements and then the composition; shared prefixes the
same. A dropped or reused validity word looks like an unrelated store, so the memory relation
states which words are live. Necula's checker inherits gcc's aliasing assumptions (pp.4, 9);
Varka's should take non-aliasing from segment identity, not from the emitter.

**Critique** [A]. Validation is per pass of one compiler version; the alarm rates come with
Branch's heuristics, which Varka would not use; the cost, four to eight times a pass, is a
solver's 2000-era cost, and the paper plans memoisation it did not report.

## 4. What it changes in the record

**Item 7, the cache's physical layout.** The record names SIMD-BP128's 4-way layout for
frame-of-reference and bit-packing of cached date columns; FastLanes measures its interleaved
layout ahead of that one by 2 times on AVX2 and 4 on AVX-512 and level in scalar code (Fig.13,
p.2141; Fig.9, p.2140), keeps row order under FOR and DICT (p.2135) so a packed column sits
beside untouched Arrow columns, and unpacks with loads, logical shifts, masks, ORs and an ADD
only, which the Vector API has [I]. The surveyor's recommendation [I]: DATE as FOR with
interleaved packing at T = 32, one base and width per 1024 values, One Value or Dictionary when
sampling says so; TIMESTAMP the same at T = 64 (about 2 times for micros within an hour, by the
surveyor's arithmetic; DELTA does better on sorted data but reorders the batch, p.2137); doubles
as ALP over FFOR on int64 lanes, ALP_rd as the fallback, Dictionary first when values repeat
(Table 4, p.9). Two consequences: the kernel reads the packed bytes directly, with the per-scan
Arrow decode kept as the fallback for consumers that are not Varka kernels, since decoding first
discards the fusion gain (FastLanes p.2140; ALP p.10); and the serializer needs an encoding tag a
column and a batch, since none of these layouts is Arrow. A filter compares in the encoded
domain: the constant rebased once a vector to `c - base`, a dictionary constant mapped once to a
code (BtrBlocks p.13 for ordered dictionaries). BtrBlocks' sampling fits a cache that compresses
once; its gather-based dictionary decode and its 4-way FastBP128 layout do not fit Varka.

**Item 91, bit-sliced columns.** BitWeaving refines the measurement item 91 asks for: its /V,
planes contiguous within a segment, beats whole-column planes on cache misses (Fig.10b); its bit
groups and pruning pay only past about 16 bits (Fig.11a) and pruning shrinks with width [I], so
the arm to measure is plain segment-contiguous VBP, no bit groups, no pruning, against Varka's
vertical compare, whose absence from BitWeaving's baselines is why the paper does not answer the
question. The surveyor's operation count [I] puts the planes ahead by under two times in cache
and by the 2.9 times byte cut out of it, for an 11-bit `<`. FastLanes (p.2141) and BtrBlocks
(p.13) both argue against bit-slicing any column a projection must materialise; BitWeaving's own
projection answer is an index beside the normal column (p.11).

**Item 90, translation validation.** Necula's frame fits the spike: symbolic evaluation of the IR
and of the emitted bytecode into hash-consed bit-vector terms, a relation the emitter supplies at
the vector loop's head and the tail's rather than one inferred, equalities discharged as SMT
queries through VARKA-240's setup, a lane model and a `MemorySegment` model, and validation per
method against the sub-DAG the emitter says it implements before any whole-class check. Expect a
few times a kernel's compile cost [I], which makes it a CI and fuzz-time check, never a per-query
one. Not to attempt: inferred relations, arbitrary loops or unrolling, the JIT or the intrinsics
below their documented semantics. The spike's first kernels, by the surveyor [I]: an int32
arithmetic kernel with validity, and one with a guard and a magic-number division.
