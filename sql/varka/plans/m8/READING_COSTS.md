# Reading: what instruction-cost tables and static throughput tools offer Varka's lowering choices

Read on 3 November 2026 for the per-host lowering choices of milestone 8 (343's conversions,
348's decimal forms) and the predictions their plans register. Four sources, licenses checked
first:

* **uops.info** (Abel and Reineke, ASPLOS 2019, arXiv 1810.04610), its web tables and
  `instructions.xml` (dated 29 March 2026), downloaded outside the repository and parsed by a
  script kept elsewhere. **No license or terms are stated**, so values are cited per instruction
  by the page's URL and no table or extract is committed.
* **llvm-mca** (llvm-project, Apache-2.0 WITH LLVM-exception): `llvm/docs/CommandGuide/llvm-mca.rst`
  and the x86 scheduling-model names. Not installed or run.
* **uiCA** (Abel and Reineke, ICS 2022, arXiv 2107.14210): the paper only; the code is AGPL-3.0,
  as is nanoBench, uops.info's measuring tool, and neither was opened.
* **Agner Fog's instruction tables**: copyrighted with no license for the documents, so not
  downloaded; uops.info covers the same ground.

Varka's side is `origin/master` `f72e4556750`. **[V]** means read in the source, **[I]** inferred.
Nothing was built or run; the only arithmetic below is on committed results.

## 1. Which tables match Varka's hosts [V]

| host | uops.info column | llvm-mca model |
| :--- | :--- | :--- |
| EPYC 7763 (Zen 3, AVX2) | `ZEN3` | `znver3` |
| EPYC 9V74 (Zen 4, AVX-512 hidden on GitHub) | `ZEN4`, the YMM rows | `znver4` |
| EPYC 9V45 (Zen 5 server, 512-bit datapath) | `ZEN5` (a Ryzen 9700X) | `znver5`, Zen 4's model |
| laptop (Zen 5, 256-bit datapath) | **none**: `ZEN5`'s ZMM rows are the desktop's | as above |
| Xeon 8573C (Emerald Rapids) | `EMR` | `emeraldrapids`, Sapphire Rapids' model |
| Xeon 6973P-C (Granite Rapids) | none (`EMR` nearest) | Sapphire Rapids' model |

uiCA models Intel Core parts only, so it covers neither the laptop nor the EPYCs.

## 2. Two values that differ by vendor (uops.info, reciprocal throughput in cycles) [V]

* **`VDIVPD`** (uops.info/html-instr/VDIVPD_ZMM_ZMM_ZMM.html and VDIVPD_XMM_XMM_XMM.html): 4 at
  every width on `ZEN5`; on `EMR` 4 at XMM, 8 at YMM and 16 at ZMM (3 uops). On Zen a double
  divide costs the same per instruction at any width the datapath holds whole.
* **`VPMULLQ`** (uops.info/html-instr/VPMULLQ_ZMM_ZMM_ZMM.html and VPMULLQ_YMM_YMM_YMM.html): 0.5
  on `ZEN5`; 1.5 with 3 uops and latency 15 on `EMR`.

So a 64-bit multiply-high built on `VPMULLQ`, or a division form, costs relatively more on the
Xeons than on the laptop or the EPYC 7763, which none of Varka's laptop or 7763 measurements can
show **[I]**.

## 3. Prediction held against committed measurement [V]

Source: `VarkaEmitterParityBenchmark-jdk25-results.txt` and its `-128bit-results.txt` twin, the
laptop. Ratios only, so no clock frequency is assumed.

* **Check A, `extract(YEAR FROM ym)` through double lanes**: two `vdivpd` per vector of int lanes
  (`m5/VARKA-149.md` 3), so 2 per 16 rows at 512 bits and 2 per 4 rows at 128. If divider-bound,
  the 512 to 128 rate ratio is 4 x TP(XMM) / TP(ZMM). With `ZEN5` as published, 4.0; with the
  laptop's datapath (a ZMM divide in two halves, TP about 8, as Zen 4's 5 and 9), about 2.
  Measured (`VarkaEmitterParityBenchmark-jdk25-results.txt` and
  `VarkaEmitterParityBenchmark-jdk25-128bit-results.txt`): 3908.7 against 2102.8, 1.86x. The
  table predicts the result only once the datapath is put in by hand. `VARKA-149.md` 3 wrote
  "roughly four times" for its own 3897.6 against 2102.7, which is 1.85x: the desktop-table
  assumption, which the arithmetic written out would have caught. Its conclusion, that the
  divider sets the speed, stands (corrected there, 3 November).
* **Check B, `year(d)`'s three divisions**: relative to the magic multiply, the true divide
  measured 0.27x at both widths and the reciprocal 0.45x and 0.44x. A throughput count from the
  table predicts about 0.6x for the divide and 0.65 to 0.75x for the reciprocal (the plan's own
  guess was 0.7 to 1.0x). A latency chain, three dependent conversions and divides, fits after the
  fact, but nothing in a table says in advance when iterations stop overlapping.

So a table predicts within about a tenth when one resource dominates and the datapath is right,
and is off by up to 2x on a many-instruction body; only the A/B got Check B right.

## 4. A static throughput tool instead of a benchmark [V/I]

uiCA's paper (Tables 1, 3 and 4, BHive blocks on Intel, cache-resident) reports llvm-mca's mean
error at 12 to 26%, IACA's 6 to 15% and uiCA's under 2%, uiCA's accuracy resting on a
reverse-engineered Intel front end and port assignment (dropping the front-end model alone raises
its error to 8.6%); nothing of the kind exists for Zen. llvm-mca's guide lists no fetch, decode,
branch or cache model. Varka's kernels stream columns past L1 and are often memory- or
divider-bound, and `dev/varka_emit.sh --asm` prints hsdis text, not assembler input, so a
converter per host would come first. Varka decides on 1.3x margins; a tool good to a fifth on
Intel and unmeasured on AMD ranks only forms 2x apart, which op counts already sort.

## 5. Sorted

### (a) Worth a plan note

1. **Write the per-host arithmetic before registering a prediction between lowerings**, from
   uops.info with the right datapath, and expect up to 2x error on many-instruction bodies
   (sections 3 and 4). A short entry in `skills/benchmarking.md`.
2. **The vendor caveat** (section 2): a winner chosen on the laptop or the EPYC 7763 may not carry
   to the Xeon runners; the same entry, beside the option matrix's machine-dependent arms.
3. **A census column with each operator loop's instructions**, read from hsdis per host
   (`VarkaOperatorCensusProbe` already forks a loop per operator, host and width): the
   prerequisite for any static cost work. Low priority, the owner's call; no row.

### (b) Not applicable to a bytecode-over-C2 target

llvm-mca or uiCA as a benchmark or as the emitter's cost model (section 4; uiCA is AGPL); cycle
costs in `VarkaEmitCost`, which prices bytecode and call sites for the compile budget, not cycles;
choosing instructions, which C2 does, Varka choosing only among Vector API forms; Agner Fog's
tables as data (copyright).

### (c) Varka already does it as well or better

* **Divider-bound reasoning** (VARKA-102, VARKA-149, VARKA-152) found that the divider, not the op
  count, sets the cost and moved to the multiply-high; the tables quantify it and add no decision.
* **The intrinsic-or-fallback census per host and width** (VARKA-376) is the first-order fact no
  cost table supplies, measured on the real hosts.
* **Machine-dependent option arms measured per host** on the option matrix: at Varka's margins,
  measurement is the only method accurate enough, and Check B says a table was no better than the
  plan's guess.

Not read: uops.info's latency methodology beyond the paper's abstract, and llvm-mca's
scheduling-model sources.
