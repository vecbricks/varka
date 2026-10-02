# `sql/varka/papers`

Machine transcriptions of third-party papers the Varka work reads closely enough
that quoting them from memory would be a mistake. They exist so that a plan, a
comment or an agent can cite a constant, a theorem number or a range bound and
have the surrounding argument at hand, in a format that greps and diffs.

## What these files are not

* **Not Varka documentation.** Nothing here is written by this project, and
  nothing here is under the Apache License that covers the rest of the
  repository. Each file names its authors, its venue, its DOI and where the
  copy came from.
* **Not authoritative.** They are conversions of PDFs, and every one of them
  loses something - most often the parts of a formula that are carried by
  typography rather than by characters. Each file opens with a "what this
  transcription loses" section. **When a constant, an exponent or an inequality
  matters, read the PDF.**
* **Not a library.** A paper earns a place here by being load-bearing for
  shipped or planned code, and the file says which task that is.

## Licensing

These are third-party works, included under the terms their open-access deposits
carry. They are reference material for developing this fork, not part of any
Spark distribution: nothing in this directory is compiled, packaged, or shipped,
and `dev/`'s license and RAT checks should treat it the way they treat other
non-distributed documentation. If a paper's terms do not permit a copy to be
kept here, keep the reading notes and the citation instead and drop the
transcription - the notes are the part this project actually owns.

## Why these are extracted mechanically, and not by a model

Converting a paper is a choice between two failure modes, and the choice is
already made here: **an extractor that drops things beats one that invents
them.**

The transcription in this directory is rebuilt from `pdftotext -bbox-layout`
word geometry - a glyph smaller than its line's body and off its baseline is a
superscript or a subscript. That recovers what a plain text layer destroys, and
it cannot fabricate, because no model ever sees a number. What it loses is
structure: tall delimiters (set-builder braces, large parentheses, fraction
bars) are separate glyphs on their own lines, so formulas built from them come
through fragmented, and figures and tables are lost.

`marker-pdf` 2.0.0 was evaluated against exactly that gap, on five pages of the
Neri-Schneider paper, and it fixes it: eight displayed formulas came out as
correct LaTeX with their braces intact, and 95 table rows including the assembly
listings the geometric extraction loses entirely. It was rejected anyway,
because on those same five pages it silently corrupted four things:

| the paper | Marker |
|---|---|
| `forall n in [0, U[` (Theorem 3) | `[0, U]` |
| `forall N_Y in [0, 734[` (Equation 20) | `[0, 734]` |
| `forall n in [0, 10441974239[` | `[0, 1044197429]` |
| the prose glyphs for epsilon and `>` | replacement characters, on five lines each |

Three half-open intervals silently closed, and one of them lost a digit from an
eleven-digit bound - a validity range for a strength reduction, which is exactly
the kind of number that would be copied into an admission check. Every one of
those reads as entirely plausible. That is what an LLM extraction pipeline does
when it cannot read something: it produces something reasonable rather than a
gap, and the reader has no way to see which is which.

So: mechanical extraction for the file of record, and **for the theorem
statements in particular, read the PDF**. If a model-based conversion is ever
wanted for a section, it belongs in a separate file that says so on every page,
not spliced into one of these.

## Adding one

Convert it, then open the file with a header giving: title, authors, venue,
DOI, the URL the copy came from, why Varka reads it (naming the task or plan),
how the conversion was made, and what it loses. Describe the method in that
header and in the commit that adds the file; no conversion script is kept here,
because these files are read rather than regenerated - a rerun would produce a
different set of losses and silently invalidate the header.

## Contents

| file | paper | read by |
|---|---|---|
| `neri-schneider-2022-euclidean-affine-functions.md` | Neri and Schneider, "Euclidean affine functions and their application to calendar algorithms", SPE 53(4), 2023 | `plans/PLAN_TASK_53.md`, and the background for `PLAN_TASK_26.md`'s admission check |
| `lang-2020-simd-control-flow-divergence.md` | Lang, Passing, Kipf, Boncz, Neumann and Kemper, "Make the most out of your SIMD investments: counter control flow divergence in compiled query pipelines", VLDB Journal 29, 2020 | `plans/SCOPE_MILESTONE_8.md` Items 16, 17, 23 and 25 (selection narrowing and its threshold) |
| `ngom-2021-filter-representation.md` | Ngom, Menon, Butrovich, Ma, Lim, Mowry and Pavlo, "Filter Representation in Vectorized Query Execution", DaMoN 2021 | `plans/SCOPE_MILESTONE_8.md` Items 3, 16 and 25 (bitmap against selection vector, and the crossover model) |
| `benson-2023-simd-compiler-intrinsics.md` | Benson, Ebeling and Rabl, "Evaluating SIMD Compiler-Intrinsics for Database Systems", ADMS 2023 | `plans/SCOPE_MILESTONE_8.md` Items 19, 22 and 25 (the compress and PEXT gate), `plans/PLAN_TASK_62.md` section 11 |
| `schmidt-2025-mimd-simd-interplay.md` | Schmidt, Pietrzyk, Hildebrandt and co-authors, "Rethinking MIMD-SIMD Interplay for Analytical Query Processing in In-Memory Database Engines", CIDR 2025 | `plans/SCOPE_MILESTONE_8.md` Item 25 (the concurrency regime the public write-up must state) |
| `slesarenko-2014-isomorphic-specialization.md` | Slesarenko, Filippov and Romanov, "First-class Isomorphic Specialization by Staged Evaluation", WGP '14 | `plans/SCOPE_MILESTONE_8.md` Item 11 (physical representation as a compiler decision: isos as first-class conversion nodes) |

Eleven further papers were read for `plans/SCOPE_MILESTONE_8.md` Item 25 on 16
September 2026 (Willhalm 2009, Raducanu 2013, Polychroniou 2015 and 2019, Feng
2015, Lang 2018, Kersten 2018, Shen 2021, Behm 2022, Boether 2023, Boivin 2026).
Their printed terms - ACM copyright with a permission notice, Creative Commons
BY-NC-ND, or an arXiv posting - do not permit a copy here, so Item 25 keeps the
reading notes and the citations instead, as the licensing section above says.

Slesarenko, Filippov and Romanov, "First-class Isomorphic Specialization by
Staged Evaluation" (WGP '14), read on 19 September 2026 for Item 11, is in the
same position - ACM copyright with a permission notice - and is transcribed
here anyway, on the repository owner's explicit decision; its header says so.
The default above stands for the next paper.
