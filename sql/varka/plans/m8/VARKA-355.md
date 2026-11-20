# VARKA-355: The milestone's post, "One engine, every number"

*Planned 20 November 2026 on master `342ed6851f8`, from milestone 8's row 355 (1.1 item 7,
`m8/PLAN.md` 2.11). Nothing of the post is written; it is drafted once the rows it counts have
landed, and its sources merge before the `varka-m8` tag (row 354). The model is milestone 7's own
post, `m7/VARKA-270.md` and `m7/POST_CORRECTNESS.md`.*

## 1. Where this came from

1.1 item 7: the milestone ends with *One engine, every number: Varka across Spark's numeric and
temporal types*. The claim it has to earn is the census's: what share of Spark's scalar
expressions over the thirteen types a kernel answers bit for bit with the row engine, and how that
is known - the exhaustive sweeps, the SMT proofs, the fuzzers - with every declined case named. "A
share without the 'how it is known' half is not the post." 2.11 sets the reader (JVM and database
engineers, as milestone 7's post had) and the pointers for the floating-point ground under it:
Goldberg's D.3.2, D.3.5, D.4.1.4, D.4.3, D.5.2 and D.7 and the Handbook of Floating-Point
Arithmetic's 2.6.1 and 4.2.

The standing decisions on how a Varka post is written, each the owner's, which this plan follows:

* **A story with a figure per section** (the owner's decision of 16 October 2026, after he rejected
  two posts as not interesting): open on a measured surprise with an "In short" box after it, plain
  `##` headings in story order, one figure per section drawn from a committed file, three to five
  in all, round numbers in prose, provenance and links in a closing "How these were measured".
* **Short** (22 September 2026): one idea per section; a section a reader would skip is cut; what
  the engine cannot do is a few sentences beside the numbers, not a section; no reproduction
  recipe in the post, the README holds it.
* **Each finding in the one post whose reader acts on it** (25 September 2026): a user acts on a
  knob or a query, a developer on a mechanism. The users' table is `docs/sql-varka.md` (row 353)
  and the math-on-the-host story is already row 587's post for users; this one keeps both to a
  link.
* **Every improving item is owed before publication** (28 September 2026, for milestone 6's closing
  post): there is no "would make it better but does not gate it"; section 6's table is all
  required.
* **Outline-only figures** (18 October 2026): `rough.py`'s default `fill_style="outline"`, the
  `box()` text pattern, no hatching.
* **Headline numbers from GitHub runners, never the laptop** (22 September 2026): the laptop is a
  256-bit datapath; a timing a post quotes is a runner's committed file.
* **One pull request** (9 November 2026): the post, its figure scripts and data and this plan go
  together even over the 1000-line cap, pushed with `VARKA_PUSH_CHECK_SKIP=R4` once the size rule
  is the only R4 finding.

## 2. The admission check, done

Read on master `342ed6851f8`, 20 November 2026:

1. **No draft exists.** No file under `sql/varka/plans/` holds the title but `m8/PLAN.md`, no
   branch carries `varka-355`, and no commit names the post.
2. **The evidence it needs exists in committed files, and is not final.** The census
   (`m8/census.tsv`, `--summary`): 180 entries in scope, 878 pairs, 533 fused today, per type from
   `dec` at 2 of 66 to `ntz` at 49 of 54. The cast matrix (`casts.tsv`): 106 legal pairs, proven by
   exhaustion (62), SMT (29), sampled regions (12) or both (3), 42 fused at least in part. The
   proofs: 33 `.smt2` files under `sql/varka/proofs/`, 21 of them added since milestone 7 closed
   (the decimal, narrow-type, rounding and cast files among them). The exhaustive sweeps: 15
   suites in `sql/varka/ci/sweeps.tsv`. Each row's verification: the generated per-type counts
   in `docs/sql-varka.md` (`refinement`, `rendered`, `proof`, `exhaustive`, `owed`,
   `differential`). The share moves with every type row, so the post is drafted on the commit
   after the last of them, not now.
3. **The pointers are not in the repository.** Neither Goldberg nor the Handbook is transcribed
   under `sql/varka/papers/`, and the post only points to them; the section numbers are checked
   against a copy of each text before publication (step 6), and one that cannot be checked is
   dropped rather than cited from memory.
4. **Section 7's question 1 is open**: the headline is the census share alone, or the share with a
   per-type benchmark beside it, "decided later by the owner". Question 1 below.

## 3. The post

### 3.1 The claim and the opening

**The claim:** of Spark's scalar expressions over the thirteen numeric and temporal types, a kernel
answers a stated share bit for bit, per type and per family, and every pair is either proven,
swept, fuzzed or named as declined with its reason.

**The opening** is chosen at drafting from a measured surprise in a committed file, not invented
here. Candidates in the record today, each to be re-read then:

* Vanilla Spark answers the same checked decimal-to-integer cast of the same cached column by two
  rules, by whether a row or a column reader serves it (`m8/PLAN.md` section 7 question 3 and
  `VARKA-365.md` 11); the kernel had to pick the one Spark's SQL reads give.
* How few of Spark's functions are in scope at all: 180 entries of 600 registered names and 28
  implicit operators.
* A share that is high for the 32-bit types and lowest for `DECIMAL`, because an entry counts only
  when every form of a type fuses (`census_curated.txt`'s `^` rule).

### 3.2 The outline

Body without task ids, row or pull request numbers or milestone words; those go to the closing
section. Each section answers one question, one figure each, every figure's numbers read by its
script from a committed file so that it cannot drift.

1. **Opening and "In short".** No figure; the opening's number is Figure 1's.
2. **What fuses?** What share, per type. fig70: fused over admitted pairs per type, from
   `census.tsv`, the in-part pairs drawn apart.
3. **How is a cast known to be right?** Which proof holds which pair. fig71: the 13 by 13 matrix
   by proof kind, from `casts.tsv` and `casts_fused.tsv`.
4. **Where the width allows, every value.** What exhaustion and SMT cover. fig72: per type, the
   coverage rows by verification kind, from `coverage.json`'s `verified` field.
5. **Floating point, where exhaustion stops.** How NaN, signed zero and rounding are held. fig73:
   the sampled regions and the fuzzers' draws per floating pair, from the committed fuzz and
   sampled-arm records the rows leave.
6. **What stays Spark's, and why.** What is declined, and on whose decision. fig74: unfused pairs
   by reason class (a later row, a declined band, an owner's decision), from `census.tsv`'s
   `reason` column.
7. **How these were measured.** Provenance, links and 2.11's pointers; no figure.

Words the post defines in one clause where they first appear, as `m7/VARKA-270.md` 3 did: kernel,
census, entry and pair, decline, exhaustion, SMT, refinement, fuzzer, oracle.

### 3.3 What the post leaves to others

* The users' question "does my query fuse" is the support table's (row 353), linked.
* The math host's story is row 587's post, linked; this post says only that the math family is
  exact by default and how that is held.
* Timings: none in the body if question 1 is answered as recommended; the runners' per-type
  baseline files (`*BaselineBenchmark-jdk25-runner-epyc7763-results.txt`) are linked from the
  closing section.

## 4. Files

| file | what |
|---|---|
| `sql/varka/plans/m8/POST_EVERY_NUMBER.md` | the post |
| `sql/varka/plans/figures/fig70.py` to `fig74.py`, `figures/svg/` | the figures |
| `sql/varka/plans/figures/data/*.tsv` | only where a figure's data is not already one file |
| `sql/varka/plans/m8/VARKA-355.md` | this plan, its outcome in section 9 |

## 5. Tests, and what each is for

* Each figure script asserts its totals against the file's own (`--summary`'s 878 pairs or what
  they are then, the 106 cast pairs), so a figure that misreads its data fails to render.
* `dev/varka_quote_check.py` over the post: every decimal number traces to a committed file.
* `grep -nE 'VARKA-|#[0-9]{3,}|row [0-9]+|milestone' POST_EVERY_NUMBER.md` above the closing
  section finds nothing.
* `dev/varka_post_page.py` renders the page with every figure inlined.

## 6. What the post owes before publication

All required, per the decision of 28 September 2026:

* the final census share per type and family: the type and function rows of waves 0 to 4
  (`m8/PLAN.md` 4.1);
* the generated support table to link: 353;
* every type drawn by both fuzzers: 352;
* Spark's own cast suites agreeing with the matrix pair by pair: 368;
* the fuzzer's count arm: 369;
* the proofs caught up with the new types: 490;
* the math family exact, and the opt-in mode's measured bounds if the post names the mode: 356
  to 358 and 367;
* the Goldberg and Handbook section numbers checked against the texts: step 6;
* the owner's edit: step 7.

## 7. Steps

1. **Freeze the data** on the commit after the owed rows merge. Done when `python3
   dev/varka_census.py --check` exits 0 there and the summary is recorded in section 9.
2. **Choose the opening** from 3.1's candidates or a better one found then, its source file named in
   section 9. Done when the opening's number passes the quote check.
3. **The figures.** Done when `for n in 70 71 72 73 74; do python3
   sql/varka/plans/figures/fig$n.py; done` writes five SVGs and a second run changes none.
4. **The draft.** Done when the body, title to the closing section, counts 1,800 to 2,400 words
   (`sed -n '1,/^## How these were measured/p' POST_EVERY_NUMBER.md | wc -w`) and section 5's
   checks pass.
5. **A private review page** rendered with `dev/varka_post_page.py` and published as a private
   artifact for the owner; screenshots for the card from a path under `$HOME`, since the snap
   browser cannot read `/tmp`. Done when the page renders with five figures.
6. **The pointers.** Each of the eight section numbers read in a copy of the text; any not found is
   removed. Done when section 9 lists each with the page it was read on.
7. **The owner's edit**, then the pull request (one, not split); merged before the tag. Done when
   `python3 dev/varka_msg_check.py pr` passes on its body and the owner has merged it.
8. **Publish**, on the owner's word: `dev/varka_post_page.py sql/varka/plans/m8/POST_EVERY_NUMBER.md
   --out ~/proj/vecbricks.github.io/one-engine-every-number --og-image
   https://vecbricks.github.io/one-engine-every-number/card.png`, the post's own SVGs and a
   1200x630 card from Figure 1, the index entry, committed and pushed on that repository's main.
   Done when the address answers and row 355 reads **Done** with it; row 354 quotes it in the
   changelog and the tag.

## 8. Predictions, registered before the draft, and risks

1. The body is 1,800 to 2,400 words before the owner's edit, five figures, each from a committed
   file. Milestone 7's post came in 180 to 260 words over its budget twice (`m7/VARKA-270.md`
   10.2, 10.3), so the range's top is the expected landing.
2. `DECIMAL` has the lowest per-type share of fused pairs in Figure 1, for the crediting rule in
   3.1's third candidate.
3. The quote check finds no orphan in the post.

Risks: the share moves after the freeze if a row lands late (step 1 is rerun and the figures with
it, which read the files); a section reads as a spec sheet rather than a story (the owner's review
in step 7, which rejected two posts before); and a claim of "every pair proven" overreaches where a
pair is held only by the differential suites (Figure 3 shows that kind as its own bar, so the
prose cannot round it away).

Size: medium; one pull request of about 1,200 to 1,800 counted lines (the post about 300 lines,
five figure scripts about 150 each, this plan's outcome), over the cap by the decision of
9 November 2026.

## 9. Outcome

*Filled as the post is drafted.*

## 10. Questions for the owner

1. **The headline: the census share alone, or with a per-type benchmark** (`m8/PLAN.md` section 7
   question 1, left to the owner). Recommended: the share alone, the runners' committed per-type
   baseline files linked from the closing section. The post's claim is coverage and how it is
   known (2.11: "built from the census rather than from a benchmark"), a benchmark half would need
   fresh runner dispatches at the close that only the owner starts, and the committed baselines
   are before-and-after files whose sections decline as often as they fuse. The plan is written on
   this answer; the other adds one section, one figure from those files and about 300 words.
2. **The address** `one-engine-every-number`. Recommended as the title's words; the owner has named
   the earlier addresses by their titles.

## 11. Done when

The post is published with the census share per type and family and the proofs behind it - the
sweeps, the SMT proofs, the sampled arms and the fuzzers, each declined case named - every number
from a committed file, every item of section 6 delivered, and row 355 records its address.
