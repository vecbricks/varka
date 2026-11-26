# VARKA-264: Sweep milestone 4's debt register

## 1. Where this came from

Row 264 of `m7/PLAN.md`, from the plan review (2.3): `m4/PLAN.md` section 9 is a register of
twenty debts opened during milestone 4, and `sql/varka/AGENTS.md` says a swept entry is rewritten
in the past tense with what the sweep found, never deleted. Several had been swept as their
tasks landed; the rest had not been read since.

## 2. The admission check, done

Each entry was read against the plan rows and the code as they stand on 9 October 2026
(`996542e6a7e`). The check is that every entry ends owned: closed by a named task or commit, or
moved to a numbered item of `m10/SCOPE.md`, or kept as the record of a decision. The domain is the
twenty entries; the record of what each turned out to be is the "Swept" paragraph under it.

| disposition | entries |
|---|---|
| already closed or swept | 4 (width-named validity helpers, a calendar field once per output, `DateVectorOpsBenchmark`, two outputs over one shift) |
| closed now | 8: `dev/varka_emit.sh`'s silent crash (`cfb52324110`), the 128-bit 64-op collapse (VARKA-77), the guard under a `CASE` arm (VARKA-79), the parity cluster (VARKA-90), the `GROUP_BUDGET` retune (VARKA-71), greedy grouping (VARKA-200's exact grouping), the bimodal `dayofweek` rows (the band, VARKA-90), the narrowing-only projection (VARKA-78) |
| moved to `m10/SCOPE.md` | 7: items 15 (the guard walk, the epilogue's budget, string compaction), 39 (the `weekofyear` half of the guarded-producer entry), and new 86 (the `INT` answers' tightening), 87 (the week fold's cost), 88 (the parity harness's stream placement) |
| kept as a decision | 1: the detect-and-resample loop |

What the check would have rejected: an entry that no document owned. There is none left.

## 3. The design

Documentation only. Each open entry gets a paragraph in the form the file already uses
("**Swept 9 October 2026 (VARKA-264), closed.** ..."), the register's header says what the sweep
did, and `m10/SCOPE.md` gets items 86 to 88, each with the entry's own description and what closing
it takes. No entry was deleted or rewritten; the originals' text is unchanged above each paragraph.

### 3.2 What is deliberately unchanged

The three entries moved to item 15 and the one to item 39 stay where the earlier moves put them
(15 and 21 September 2026); this sweep only says so in the register. Nothing was re-measured: the
two entries that ask for a measurement (the week fold, the parity harness's streams) are moved
with that said.

### 3.3 Registered op counts

None.

## 4. Files

| file | what |
|---|---|
| `m4/PLAN.md` section 9 | the header and sixteen "Swept" paragraphs |
| `m10/SCOPE.md` | items 86 to 88 |
| `m7/PLAN.md`, `VARKA-264.md` | the row and this record |

## 5. Tests, and what each is for

None: no code. `dev/varka_quote_check.py` and `dev/varka_precommit.sh` run on the diff.

## 6. The measurement

None.

## 7. Risks

1. **A closed entry that is not.** Each closure cites the row or file that says Done; the one
   judgment is greedy grouping, closed by VARKA-200's exact grouping, which the entry's own
   example (`year(d), year(d2), month(d)`) is not tested against here.
2. **Item numbers** 86 to 88 are the next free ones after item 85 of 6 October 2026; a PR that
   adds an item meanwhile would collide and renumber.

## 8. Sequencing

One commit.

## 9. Outcome

Done on 9 October 2026. The register has twenty entries and every one is owned. Three new scope
items (86 to 88) hold what no row did: tightening the `INT` ranges, the week fold's assembly, and
the parity harness's buffer placement.
