# Reading for the option matrix: one option at a time, pairs, or random

Read on 29 October 2026 at the owner's request, to ask whether the option matrix (row 507) spends
its time well: it runs Varka's suites under the defaults and under 48 configurations, each one
emit option (or one fixed group) moved off the defaults, as `VarkaMatrixRuns.configurations()`
builds them from `VarkaEmitOption.TABLE`. Both papers were read from their arXiv copies:
Medeiros et al. from arXiv v3 (16 February 2016), which calls itself an extended version of the
ICSE 2016 paper, and Halin et al. from arXiv v3 (11 June 2018), the preprint of the journal
article. Differences from the published versions were not checked. Neither arXiv copy prints a
licence that allows one to be kept, so `sql/varka/papers/` holds no transcription (its
`README.md`, Licensing); the journal version of Halin et al. is CC BY 4.0 and could be transcribed
later. **[R]** means read; **[I]** inferred.

| paper | source | licence of the copy |
| :--- | :--- | :--- |
| Medeiros, Kastner, Ribeiro, Gheyi, Apel: "A Comparison of 10 Sampling Algorithms for Configurable Systems" (ICSE 2016) | arXiv, `https://arxiv.org/abs/1602.02052` | arXiv non-exclusive distribution licence; the ICSE version is ACM's |
| Halin, Nuttinck, Acher, Devroey, Perrouin, Baudry: "Test them all, is it worth it? Assessing configuration sampling on the JHipster Web development stack" (Empirical Software Engineering 24(2), 2019, DOI 10.1007/s10664-018-9635-4) | arXiv, `https://arxiv.org/abs/1710.07980`; dataset `https://github.com/xdevroey/jhipster-dataset` | arXiv non-exclusive distribution licence; the journal version is CC BY 4.0 |

## 1. Medeiros et al.: ten sampling algorithms against 135 known faults

**What it is [R].** A comparison of how many known configuration-related faults each sampling
algorithm would have exposed, and how many configurations it needs. The subjects are 24 C
systems (Linux, BusyBox, Apache, GCC, Libssh and others) whose options are `#ifdef`s. The fault
detector is taken out of the comparison: each of the 135 faults has a presence condition (the
configurations in which it shows), and an algorithm detects a fault when one of its sampled
configurations satisfies that condition. The algorithms: one-enabled (each option on, all others
off), one-disabled (each option off, all others on; Abal et al.'s proposal from 42 Linux bugs),
most-enabled-disabled (two configurations: all on, all off), statement-coverage (every `#ifdef`
block enabled at least once), random (n random assignments per file), and t-wise for t = 2 to 6
from precomputed optimal covering arrays. Sampling is per file, and a first study ignores option
constraints, header files and the build system; a second study on 47 Linux and BusyBox faults
lifts each of these assumptions in turn.

**What it measured [R].** Faults found of 135, with the mean configurations per file:

| algorithm | faults | configurations per file |
| :--- | ---: | ---: |
| statement-coverage | 90 | 1.3 |
| most-enabled-disabled | 105 | 1.3 |
| one-enabled | 107 | 1.7 |
| one-disabled | 108 | 1.7 |
| random | 124 | 2.6 |
| pair-wise | 125 | 1.8 |
| three-wise | 129 | 2.5 |
| four-wise | 132 | 3.7 |
| five-wise, six-wise | 135 | 6.0, 10.0 |

Ranked by configurations per fault: most-enabled-disabled, pair-wise, statement-coverage,
one-disabled, one-enabled, three-wise, random, then four- to six-wise. Of unions of algorithms,
the Pareto front starts at pair-wise plus one-disabled: 131 faults at 3.5 configurations per file.
The paper's Table 3 gives each fault's presence condition: 75 faults (56%) need one option set,
38 (28%) need two, and 22 (16%) need three to seven; 30 of the 135 need some options on and others
off at once. The paper's own summary is that almost 84% are exposed by setting one or two
options. Lifting the assumptions changed the ranking: with constraints, the pair-wise tool's
heuristic covering arrays missed faults pair-wise should guarantee, and three-wise and above could
not be generated for Linux at all; under global (whole-system) analysis one-enabled and
one-disabled grew from 1.7 to about 7,943 configurations and no t-wise array could be built.
A Cppcheck experiment (Appendix A) gave rankings it calls comparable; it was seen here only as
its Pareto figure.

**What Varka takes from it [I].**

1. *Where one-at-a-time stops.* One-disabled and one-enabled stop near 80% of faults because the
   other 20% need two options together, and pair-wise reaches 93% with barely more configurations
   per file (1.8 against 1.7). The 44% of faults needing two or more options are the population a
   one-option matrix can only reach by accident.
2. *Singles plus pairs is the efficient union.* The front's first point, pair-wise plus
   one-disabled, is the shape this reading proposes for Varka: keep every single arm, which
   localizes a failure to one option, and add the pairs of the groups that interact.
3. *A dead arm needs a base where it is live.* Medeiros's constraints are Varka's subjects:
   `validityOrFirst` emits nothing at the defaults, so its configuration runs over
   `denseValidityOnce=false,validityByBitmap=false` (VARKA-247). A pair configuration must be
   built the same way, over a base where both of its options act, or it tests nothing.
4. *Small t is cheap at Varka's size.* The tools failed at thousands of options. Varka's matrix
   draws 40 of the table's 48 options, most of them two-valued; a strength-2 covering array of 40
   two-valued factors needs 9 rows (the smallest N with C(N-1, ceil(N/2)) >= 40, Kleitman-Spencer),
   and mixed levels push that only into the low tens.

**What does not transfer [I].** The detector is assumed perfect: a configuration that satisfies a
fault's presence condition finds it. In Varka a configuration finds only what its suites' shapes
reach, which is why the option audit (`m8/results/VARKA-507-option-audit.txt`) and the reach record
matter as much as the sample. Sampling is per file over a few options each (Linux files average
three); Varka's options all act in one emitter, which is the paper's global case, where
one-at-a-time grows linearly with the options (48 configurations, affordable) and pairs are
affordable only because the option count is small. Most of the corpus is syntax and memory
errors of C builds; Varka's faults are wrong answers, declines and verifier failures.

## 2. Halin et al.: every JHipster configuration, tested

**What it is [R].** A ground truth: all 26,256 valid configurations of a specialized JHipster
3.6.1 feature model (48 features, 15 constraints, 162,508 configurations before specialization)
were generated, compiled, built and tested, so each sampling technique can be scored against
every fault rather than against a mined corpus. (The contributions list says 26,257; the body
and Table 3 say 26,256.) Failures were grouped into faults by association rules with confidence 1,
then confirmed against GitHub issues. Techniques compared: t-wise for t = 1 to 4 (SPLCAT),
dissimilarity (PLEDGE), random, one-enabled, one-disabled and most-enabled-disabled with a random
valid completion (repeated 1,000 times), and their "all-" forms taking every valid completion.

**What it measured [R].** 9,376 configurations (35.70%) failed. Six faults explain 99.1% of the
failures; the other 242 were the test infrastructure (grid network errors, gulp). Five faults are
interactions of two features, one of four (MariaDB, monolith, no search engine, Docker); none
needs a single feature and none three. The authors set this against Abal et al.'s Linux count of
22 of 43 bugs being two-feature, and note there is no common trend across strengths. Exhaustive
testing cost 4,376 CPU hours (80 machines, four 13-hour nights, about 10 CPU minutes a
configuration) and 5.2 TB of disk, after 8 person-months building the scaffold. Faults found of
6, by technique (means where the technique is random):

| technique | configurations | faults | faults per configuration |
| :--- | ---: | ---: | ---: |
| JHipster's own CI configurations | 12 | 0 | 0 |
| most-enabled-disabled | 2 | 0.67 | 0.34 |
| 1-wise | 8 | 2 | 0.25 |
| one-disabled | 34 | 2.40 | 0.071 |
| one-enabled | 34 | 3.15 | 0.093 |
| random | 41 | 4.49 | 0.110 |
| dissimilarity (PLEDGE) | 41 | 4.70 | 0.115 |
| 2-wise | 41 | 5 | 0.122 |
| 3-wise | 126 | 6 | 0.048 |
| all-one-disabled | 922 | 5 | 0.005 |
| all-one-enabled | 2,340 | 6 | 0.003 |
| exhaustive | 26,256 | 6 | 0.0002 |

The last column is computed here from the faults and sizes; Table 3, as extracted here, prints
0.07% and 0.09% for one-disabled and one-enabled, against 7.1% for 2.398 / 34 (check the PDF). The
authors' summary: random is a strong baseline; 2-wise and 3-wise find faults slightly more
efficiently than random; most-enabled-disabled is efficient on average but needs luck (the
solver's first two configurations found nothing); one-enabled and one-disabled did poorly;
failure efficiency and fault efficiency do not correlate. The project's 12 CI configurations,
chosen by popularity to fit five parallel Travis jobs, found none of the six faults, which took
the community weeks to find. The lead developers' constraint was turnaround: "turn around IMO is
something you need to consider for continuous integration".

**What Varka takes from it [I].**

1. *The matrix already pays for pair-wise.* Varka's 48 configurations are more than JHipster's
   2-wise sample of 41, which found five of six faults; one-at-a-time at 34 found 2.4 to 3.2. At
   Varka's budget the papers would buy pairs, not more singles.
2. *Popular is not enough.* The defaults run is Varka's "popular configuration", the one users
   get, and it must stay; but a matrix chosen by what ships misses interactions, as the 12 did.
3. *Count faults, not failing tests.* One fault fails hundreds of configurations in JHipster and
   many tests in Varka (`checkIntOverflow=false` alone has 83 skip-list lines, one switched-off
   check). A configuration's yield is distinct causes found per run.
4. *Random is already in Varka.* `VarkaIrFuzzSuite.randomOptions` lays a random value of every
   drawn option over each draw (a flag by a coin, an enum uniformly, a count one time in five),
   so the joint space is sampled at random at the emission level on every run. Halin's strong
   baseline is covered there; what nothing covers is the full suites under two options at once.

**What does not transfer [I].** JHipster's faults are build and deployment failures, found by
compiling; one configuration costs ten CPU minutes and the space is small enough to enumerate
once. Varka's space (over 2^40 joint values) cannot be enumerated, a configuration is planned at
about 30 bin-minutes (85,776 bin-seconds over 48), and the matrix reruns on every dispatch,
so a structural blind spot recurs rather than being sampled away. JHipster's one-at-a-time rows
flip one feature against everything else on (or off); Varka's flip one option against the
defaults, a different and narrower design (section 3).

## 3. What one option at a time sees in Varka

**The blind spot, exactly [I].** A fault visible at the defaults is caught by the defaults run.
A fault needing one non-default arm is caught by that arm's configuration. What the matrix misses
is a fault that needs two or more options off their defaults at once (or a defaults-valued option
only under another's non-default arm), unless the IR fuzzer's random draws hit it on a fuzz shape.
This is narrower than either paper's one-enabled or one-disabled miss set, since their base rows
are extreme configurations nobody ships; it is the papers' "two or more options" population
restricted to non-default values. Of the existing configurations, only the validity subject sets
three non-default flags together.

**Plausible interacting groups [I], from the option table and the audit.**

- *Validity layout:* `denseValidityOnce`, `validityByBitmap`, `validityOrFirst`,
  `validityByWidth`, `validityByWord`. Each chooses how a value root's validity is written; the
  audit moves 214 to 541 coverage shapes for each of the five. Ten non-default pairs; the subject
  covers three (dense, bitmap, or-first); seven involve `validityByWidth=false` or
  `validityByWord=true` with another.
- *Division and lanes:* `division` (two non-default arms), `mulHiDivide`, `checkIntOverflow`,
  `useAVX`, `lanesOverride` (4, 16), `narrowLanes`, `narrowHalfSpecies`. Lane count, AVX level and
  the division lowering decide one instruction sequence; three of them are `C2_OPTIONS`, which
  change what C2 compiles beyond the bytes. 34 non-default value pairs; because only `division`
  and `lanesOverride` have two non-default values, four rows with every other option of the group
  flipped cover all 34, if no option masks another there.
- *Grouping and size:* `callSiteBudget=0`, `predictGrouping=false`, `planSize=false` and
  `groupBudgetUnderPrediction=true` each move 0, 68 and 122 shapes; `groupBudget=800` and
  `groupLocalSlots=false` each move 0, 148 and 0. Equal counts suggest one mechanism each (equal
  counts do not prove equal sets); a pair audit tells whether such options are redundant (the
  pair emits what one of them does) or interact.

**Low yield, and where its time goes [I].** Neither paper ranks configurations by observed
yield; the nearest evidence is that a sample choosing the wrong rows (the 12 CI configurations,
the solver's first most-enabled-disabled pair) found nothing at all. Varka's data says which
configurations reach little: the audit's all-zero rows (`rangeSets=false`, `splitConditions=false`,
`materializeChronoPrefix=false`, `splitDriver=false`, `severalKernels=false`, near zero
`exactGrouping=false`) and step 5's list of 11 configurations that move none of
`VarkaCoverageDifferentialSuite`'s 1,066 tests (`m8/results/VARKA-507-step5-local.txt`). The
audit is over the oracle's shapes only: `splitDriver=false`, `severalKernels=false` and
`splitConditions=false` still have skip-list lines in `sql/varka/matrix/skips.tsv`, so the suites
reach shapes the audit does not. Every arm stays, since which arm wins depends on the machine and
JDK; the question is only cadence and where the reach filter's savings are spent.

## For the rows

Row 507 (the fanned-out matrix) and row 508 (a pull request's matrix that reruns what it moves;
see `m8/READING_TEST_SELECTION.md`) are where these findings land.

1. *One at a time against pairs and random, in faults per configuration run [R].* Pairs win at
   equal or near-equal size in both papers: Medeiros 125 faults against 107 to 108 at 1.8 against
   1.7 configurations per file; Halin 0.122 faults per configuration for 2-wise against 0.071 and
   0.093 for one-disabled and one-enabled. Random sits between (0.110; 124 of 135), and 3-wise
   and above cost more per fault.
2. *How common are interaction faults [R].* Medeiros: 44% of 135 faults need two or more options,
   16% three or more. Halin: all six faults need two or more, five of them exactly two.
3. *Cost, and whether the evidence supports the design [I].* Exhaustive testing was affordable
   only once and only for 26,256 configurations; Varka's space is not enumerable. The single-arm
   design is supported as the base (it localizes, it is the papers' strong union partner), but
   both papers say a budget of Varka's size should also cover pairs. Pairs are needed only inside
   groups that act on one mechanism: validity layout, division and lanes, grouping and size.
4. *Low-yield configurations [I].* No paper evidence names them; Varka's own audit and reach
   record do (section 3). Run those arms on a slower cadence or let the reach filter skip their
   suites, never delete them.
5. *The proposal, measurable [I].*
   - *Step 1, emission only, no suite time:* extend `VarkaEmittedBytesSuite`'s option audit to the
     non-default pairs of the three groups, each over a base where both options act (as the
     validity subject is built). For each pair count the shapes whose bytes under the pair equal
     none of the defaults', the first single's and the second single's: the `distinctArms` rule
     applied to pairs. A pair with no new emission cannot expose a fault the singles miss, except
     through `C2_OPTIONS`, which change C2's input beyond the bytes and are kept regardless.
     Commit the table as a result file.
   - *Step 2:* add the pairs with new emissions as matrix configurations (or, for the division
     group, the four covering rows), first in the dispatched matrix only. The budget is set by the
     reach record: step 5 measured 7.6 of 40.0 configuration-hours saved (19%), room for about
     nine configurations at that run's mean of 50 minutes each.
   - *Measure, over the next ten matrix runs:* distinct causes found per configuration run, pairs
     against singles, counting a cause once however many tests it fails, with the skip list's
     expected failures excluded. Varka keeps no per-configuration fault ledger today; the skip
     list records expected failures, not faults, so this ledger is itself part of the step.
   - *Decision rule for the owner:* keep a pair configuration while it finds causes at no less
     than the singles' rate, or while its new-emission count stays above zero as the emitter
     changes; move it to the weekly matrix otherwise.
