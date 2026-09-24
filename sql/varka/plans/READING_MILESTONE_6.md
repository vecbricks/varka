# Reading for milestone 6: what eleven papers change for Varka

Eleven papers read on 24 September 2026 for milestone 6's compiler work - how a
wide projection is split into kernels and methods (tasks 190, 191) - and for the
claim its post makes (tasks 171, 192). The owner downloaded them to
`~/Downloads/64k_issue_2`; mechanical transcriptions, made the way
`sql/varka/papers/README.md` describes and checked word for word against each
PDF's text layer (98.9 to 100 per cent kept), sit beside them in `markdown/`.
They are not copied into `sql/varka/papers`: most carry ACM copyright, and that
directory's default is reading notes rather than copies. These are the notes.

The query-compilation papers most relevant to the milestone had already been
read - Kohn, Leis and Neumann's adaptive execution, Kersten, Leis and Neumann's
Flying Start, Gubner and Boncz's VOILA, Menon's relaxed operator fusion - and
are in `SCOPE_MILESTONE_7.md` Item 25. This set adds the tensor compilers, which
the record held nothing from, and the sources the post needs.

## 1. Why the cliff exists for Spark and not for Varka

**Neumann, "Efficiently Compiling Efficient Query Plans for Modern Hardware",
PVLDB 4(9), 2011.** The produce/consume model Spark's whole-stage codegen
descends from. Section 4.2 sets the rule that decides this milestone: a query is
not compiled into one function, but "one has to make sure that the hot path does
not cross a function boundary", so each pipelining fragment becomes one compact
function.

That rule is the explanation the ladder's figure was missing. Spark's generated
code is tuple-at-a-time: the hot path is one row through the whole projection,
so the projection cannot be split into several methods without putting calls on
the per-row path - which is why Spark does not split inside whole-stage code,
why `spark.sql.codegen.splitConsumeFuncByOperator` splits only between operators
(task 171 found the projection's own consume method is the one that crosses 8000
bytes), and why the only way off the cliff Spark offers is to stop generating
whole-stage code at all (task 192). Varka is vector-at-a-time: each group of
outputs is its own tight loop over the batch, so splitting between groups
crosses no hot path, and the byte budget can split as finely as it needs. The
post should say this: the cliff is a consequence of the execution model, not of
a missing setting.

**Kersten, Leis, Kemper, Neumann, Pavlo and Boncz, "Everything You Always Wanted
to Know About Compiled and Vectorized Queries But Were Afraid to Ask", PVLDB
11(13), 2018.** Section 8.2 notes that "Spark falls back to interpreted
tuple-at-a-time execution if a pipeline generates more than 8 KB Java byte
code". Stock Spark does not fall back at 8 KB - its own limit is 65535 bytes -
it is HotSpot that stops compiling the method; task 171's
`VarkaSizeLadderJitSuite` is the precise statement of what the paper describes,
and the post can cite both. The same section records that LLVM compile time is
"often super-linear to code size", with HyPer's mitigations (its own register
allocator, an IR interpreter for the first morsels); Varka's emission time is
super-linear too (task 191). Section 8.6's summary - compiled engines win on
computation, vectorized ones on compile time, profiling and adaptivity - places
Varka as the hybrid of Figure 13: compiled fused loops over vectors, with
per-batch dispatch between dense and masked bodies as its adaptivity.

## 2. What the native engines said about leaving JVM code generation

**Behm et al., "Photon: A Fast Query Engine for Lakehouse Systems", SIGMOD
2022.** Section 3.2 moves off the JVM because workloads became CPU-bound;
section 3.3 chooses interpreted vectorization over code generation because code
generation was harder to build and debug, harder to observe per operator, and
harder to adapt to changing data. It does not cite method size or the JIT's
limits. An earlier recollection in this milestone's discussion - that Photon's
motivation names the JVM's code-generation limits - is wrong, and the post must
not say it.

Each of Photon's three reasons has a Varka answer worth stating in the post: the
emitted class carries its IR and a line map (`VarkaDebugInfo`), so a stack frame
names the IR node; the kernel is per operator, so metrics stay per operator; and
the dense and masked bodies are chosen per batch.

**Pedreira et al., "Velox: Meta's Unified Execution Engine", PVLDB 15(12),
2022.** Section 4.3.3: Velox's experimental code generation compiles C++ with a
regular compiler, "compilation times are usually high (up to 10s in some cases),
and are not meant to be used in short lived queries or interactive workloads".
Varka emits bytecode directly, in milliseconds (`VarkaEmissionBenchmark`), and
runs it on the JVM's own JIT - a real difference from the native engines'
codegen paths, and one for the post. Section 4.5.1's adaptive filter order,
scored by time / (1 + values in - values out), is a concrete rule for the filter
work (`SCOPE_MILESTONE_7.md` Items 16 and 20).

## 3. Splitting a wide projection: task 190's second step

Task 190 step 2 chooses between a split driver in one class (A') and several
kernels per projection (B). The tensor compilers faced the same choice at scale.

**Snider and Liang, "Operator Fusion in XLA: Analysis and Evaluation",
arXiv:2301.13062, 2023.** XLA's sibling multi-output fusion is Varka's
multi-output kernel, justified the same way - a shared input read once. Its
fusion-merger rule is the constraint B needs: a producer is merged into its
consumers only if it fuses with all of them and the duplication stays low
(`CodeDuplicationTooHigh`). **For Varka: cut a projection into kernels only
between outputs that share no subtree, and treat a cut through a shared
calendar prefix as a cost.** XLA also bounds a fused kernel by the hardware's
limits - the same role the byte budget plays - and chooses rules over search for
compile time, as Varka's plan-time admission has to.

**Zheng et al., "AStitch", ASPLOS 2022.** Names the dilemma directly: fuse and
recompute shared producers, or split and pay for more kernels. Its answer is
*hierarchical data reuse*: a shared producer is computed once and kept in a
buffer its consumers read, rather than recomputed per kernel (section 3.2).
**For Varka this is a third form beside A' and B: a shared prefix - the
civil-from-days decomposition, repeated today in every group that uses it
(`PLAN_TASK_87.md` 3.3) - computed once per batch into a scratch vector that
later groups or kernels read.** Whether a lane of recomputation costs more than
a store and a load is a measurement, and the milestone's benchmarks can make it.
AStitch's *assume-relax-apply* (section 4.5) - assume a resource bound, build,
relax - is the shape of Varka's emit-measure-regroup loop, which is reassurance
the design is not unusual, and a hint: start from a conservative grouping and
relax it, so the common case emits once.

**Yang et al., "Equality Saturation for Tensor Graph Superoptimization"
(TENSAT), MLSys 2021.** Section 5.1: greedy extraction "would ignore the sharing
and overestimate the cost"; an ILP over the e-graph finds the optimum.
`groupOutputs` is a greedy pass of the same kind - it walks outputs in order and
closes a group when the next would pass the budget. **For Varka: the best
partition of outputs in their given order is computable exactly by dynamic
programming** (each prefix's best split, costed with the shared prefixes each
group recomputes and the byte bound per method), which is quadratic in the
outputs and cheap at plan time; reordering outputs so sharers are adjacent is
the harder, NP-flavoured part the milestone's debt register already names.
TENSAT's other lesson is for the egg port: rewrites matching several patterns at
once grow the e-graph quadratically, and it caps their iterations.

**Chen et al., "TVM", OSDI 2018, and Niu et al., "DNNFusion", PLDI 2021.** Both
classify operators before fusing them - TVM as injective, reduction,
complex-out-fusable and opaque; DNNFusion by mapping type (one-to-one,
one-to-many, many-to-many, reorganize, shuffle) with a table of which pairs
fuse profitably, which do not, and which need a measurement. Varka's operators
are all one-to-one today, so every fusion is legal and profitable, and the
question has not come up. **As coverage grows - aggregates, hash-based `IN`,
strings - each IR node should carry its mapping type, and fusion across a
many-to-one or opaque node (a UDF, XLA's custom-call) should be a kernel
boundary by rule, with DNNFusion's "needs profiling" cases measured.**

## 4. Choosing among splits: cost models and search

**Adams et al., "Learning to Optimize Halide with Tree Search and Random
Programs", SIGGRAPH 2019, and Zheng et al., "Ansor", OSDI 2020.** Both search a
space of schedules with a learned cost model; Halide trains its model on
thousands of randomly generated programs. **For Varka: the IR fuzz grammar
already generates random programs, and the emitted class is measured exactly
(`VarkaEmittedClass`), so a model predicting each method's bytes from its node
counts can be fitted from the fuzzer's own shapes.** With it, the regroup can
predict a split's bytes before emitting it, so the common case emits once -
which is also a cheap way at task 191's cost - and the dynamic program of
section 3 has a cost to minimise. Ansor's observation (section 2, Figure 3) that
a cost model ranks complete programs well and partial ones poorly supports
keeping the last word with the measurement of the built class, as Varka does.
Neither search is suited to plan-time admission: both spend minutes to hours per
model, and XLA's rule-based choice is the right precedent for a JIT.

## 5. What to do with it

In the order that pays soonest:

1. **The post (tasks 171, 192):** Neumann's hot-path rule as the explanation;
   Kersten's 8 KB sentence as the prior art, corrected; Velox's codegen compile
   times against Varka's; Photon's stated reasons, answered, and nothing
   attributed to it that it does not say. 2. **Task 190 step 2:** measure the
   materialized-prefix form (AStitch) beside A' and B, and cut only where no
   shared subtree is duplicated (XLA). 3. **Grouping:** an exact dynamic program
   over the output order in place of the greedy `groupOutputs`, costed with
   repeated prefixes and a byte bound (TENSAT's point about sharing); worth a
   task row if step 2 shows grouping matters. 4. **Bytes before emission:** a
   method-size model fitted from the fuzzer's shapes (Halide's method), for the
   regroup and for task 191. 5. **Coverage:** a mapping type per IR node as
   aggregates and strings arrive (TVM, DNNFusion).