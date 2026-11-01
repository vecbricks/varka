# VARKA-487: The census held to the compiler

## 0. What exists now (admission check)

From master at `b9c587374b6`, read with `dev/varka_census.py`'s own functions (imported, not
reimplemented) on 1 November 2026.

1. **The census and its counts.** `sql/varka/plans/m8/census.tsv` has 627 entries. Its columns
   are `name`, `class`, `section`, `scope`, `reason`, `types`, `result`, `fused_today`,
   `new_lanes`, `need`, `rows` and `note`, with no SQL form per entry. `dev/varka_census.py
   --summary` reports 183 entries in scope, 881 (entry, type) pairs and 286 fused today.
2. **The check runs one way.** `row` (`dev/varka_census.py:438-505`) holds every type in
   `fused_today` to `coverage_index` (`:342-361`): a claimed type needs a coverage row with a
   node of the entry's class over an operand of that type. The reverse is not checked: a type
   that a coverage row fuses for the class, and the census does not claim, passes.
3. **The reverse direction finds a real gap today.** The plan ran the reverse comparison
   (coverage operands of the class, within the entry's admitted types, minus `fused_today`) over
   the 183 entries. 29 entries report a type, in four kinds:
   - *a real gap*: `=`, `==`, `<`, `<=`, `>`, `>=`, `<=>`, `isnull` and `isnotnull` over `dec`.
     #1236 added coverage rows with `EqualTo:dec`, `LessThan:dec`, `IsNull:dec` and the rest, and
     the census still claims none, so it reports `dec` as 0 of 68. Branch `varka-348-census` may
     be correcting this. The point stands: no check failed;
   - *the cast aliases* (`cast`, `try_cast` and the ten type-named casts such as `int`): a
     `Cast` row's operand is the cast's source, which the cast matrix owns (row 486), not the
     entry's admitted type;
   - *a class shared by a rewrite*: `ifnull` and `nvl` resolve to `Coalesce`, so `coalesce`'s rows
     credit them;
   - *an operand typed unlike the entry*: `date_sub`'s `int` amount, `ym_div_num`'s `int` divisor.
     The credit is by operand, so a second operand's type reads as a claim on the entry.

   At least nine of the 29 are real, and `!` over `bool`, `equal_null`, `date_minus_date` and
   `extract_dt_days` are still to be read. So a reverse check over coverage operands is worth
   having, and without rules for the other three kinds it is noise.
4. **What substitution can reach.** 101 of the 183 entries have at least one coverage row
   naming their class. Those entries hold 583 of the 881 pairs. The other 298 pairs sit on 82
   entries with no row of their class: no row exists to substitute a column into, so the
   compiler cannot be asked about them that way.
5. **The half that is done, for casts.** Row 486 built the cast half (`VARKA-486.md` 1):
   `VarkaCastMatrixSuite` compiles every legal cast over every form ("casts_fused.tsv is what the
   compiler fuses, over every form of every legal cast", `VarkaCastMatrixSuite.java:231`). It
   writes `sql/varka/plans/m8/casts_fused.tsv` under `VARKA_CASTS_REGEN=true` (`:235`) and fails
   on a difference otherwise. `dev/varka_census.py` derives `casts.tsv`'s `fused_today` from that
   file (`casts_fused`, `:807-833`). This row's census half copies that shape.
6. **The compile path exists.** `VarkaCoverageSuite` resolves a row's SQL against its column
   table with `VarkaSqlResolve.resolve(CatalystSqlParser.parseExpression(sql), columns)`
   (`VarkaCoverageSuite.scala:823`). The column table has two columns of each type (`:132`).

### What differs from the row text

- The row asks first how far substitution reaches. Counted by class rather than by compiling
  (item 4), the ceiling is 583 of 881 pairs. The compile can only lower that, by ill-typed
  substitutions.
- The row says "so a type an entry fuses over and the census does not claim is found". Such
  types exist on master now (0.3, `dec`), and a cheap reverse check over coverage operands
  would find them without the compiler. The plan builds that first.

## 1. Goal and non-goal

**Goal.** Every (entry, type) pair the compiler can be asked about is asked. The census's
`fused_today` agrees with the answer in both directions, and every pair that cannot be asked is
named with its reason, in a committed file a suite regenerates and a script checks.

**Non-goal.** No change to what fuses. No new coverage rows beyond those a disagreement proves
are owed. No SQL form for the 82 entries without a row: those are named, and a form for them is
question 1.

## 2. Design

### 2.1 Step 1: the reverse check over coverage operands, with its three rules

`dev/varka_census.py`'s `row` gains the reverse comparison of 0.3. It fails on a type that a
coverage row's operand of the entry's class takes, within the entry's admitted types, that
`fused_today` omits. The three kinds that are not gaps get rules:

- cast aliases are left to the cast matrix (their `types` is already `legal`);
- a class two entries share is credited to the entry whose name a coverage row's SQL uses, and
  the other entry's claims stay hand-kept with a note;
- a credit comes only from operands whose position the entry's types describe. That needs the
  coverage row to say which operand is the entry's. `coverage.json` records `Class:type` per
  operand with no position, so this rule reads `operands` in order and takes the first operand of
  the class (**[I]**: true for every case in 0.3, not checked over all 362 rows).

The `dec` gap fails this check on master until the census claims it. If `varka-348-census` has
merged first, it passes.

### 2.2 Step 2: the compiler, by substitution

A catalyst suite, `VarkaCensusProbeSuite`, for each in-scope entry with a coverage row of its
class:

1. takes each such row's SQL, and for each admitted type of the entry, replaces the columns of
   the row's credited type with the column table's two columns of the new type;
2. resolves it as `VarkaCoverageSuite` does, and records `ill-typed` if resolution fails;
3. compiles it through the real compiler at the defaults, and records `fused` or `declined`, with
   the decline's reason.

An entry-type pair is `fused` if any of its rows' substitutions fuses. The answers go to
`sql/varka/plans/m8/census_fused.tsv`: one line per pair, with the verdict, the SQL that decided
it and the decline reason. It is written under `VARKA_CENSUS_REGEN=true` and compared otherwise,
as `casts_fused.tsv` is. `dev/varka_census.py --check` then requires `fused_today` to equal the
fused set of `census_fused.tsv` for every reachable pair, and names each unreachable pair with
its reason (`no coverage row of the class`, or `ill-typed`).

Whether `fused_today` is then *derived* from the file, as `casts.tsv`'s is, or stays hand-written
and is checked against it, is question 2.

### 2.3 The measurement before step 2 is kept

The row asks to measure reach before deciding. Step 2's first run prints, and the plan records:
how many of the 583 reachable pairs resolve, how many fuse, how many disagree with the census,
and the suite's time. If fewer than half resolve, the substitution design is not worth
committing. The row then stops at step 1 and records why.

## 3. Tests and proofs

1. `dev/varka_census.py --self-test`: a doctest per rule of 2.1, and one with a planted claim the
   reverse check must fail on.
2. `VarkaCensusProbeSuite` compares `census_fused.tsv` line for line, and fails naming the pairs
   that moved.
3. Planted faults, run once and reverted: a census claim removed for a fused pair fails
   `--check`; a coverage row's type changed fails the probe's comparison.
4. `dev/varka_census.py --check` in the pre-commit hook and the docs job, as today.

## 4. Predictions and costs, registered before the build

- P1: step 1 fails on master on exactly the nine `dec` entries of 0.3, unless row 348's census
  change merges first.
- P2: step 2 resolves most of the 583 reachable pairs. The registered threshold for keeping the
  design is half (2.3).
- P3: step 2 finds at least one pair, beyond the `dec` ones, that fuses and is not claimed. The
  basis is that the reverse check by operand already finds `!` over `bool` and
  `date_minus_date` over `date` (**[I]**: each is to be read before it counts).
- P4: the probe costs seconds, as the cast probe over 365 form pairs does (`VARKA-486.md` 5).
  That is to be read against the calibration table before the build.

## 5. Order, files and size

Two pull requests:

1. step 1 (2.1) with its doctests and the census corrected for what it finds;
2. step 2 (2.2) with the measurement of 2.3 in this plan's outcome, `census_fused.tsv`, and the
   census check against it.

| File | Change |
|---|---|
| `dev/varka_census.py` | the reverse check and its rules; reading `census_fused.tsv` |
| `sql/varka/plans/m8/census.tsv` | the claims the checks prove owed |
| `sql/catalyst/src/test/java/.../VarkaCensusProbeSuite.java` | the probe, JUnit 5 under `@VarkaSuite` |
| `sql/varka/plans/m8/census_fused.tsv` | its output |
| `sql/varka/AGENTS.md`, `ADDING_A_TYPE.md` | one line each: the census is held both ways |

Size: medium, as the row says.

## 6. Out of this row

- A SQL form for the 82 entries without a coverage row (question 1).
- Option configurations: the probe records the defaults, as `casts_fused.tsv` does.
- The done-when's item 2 counts themselves. This row makes them exact, and the rows that fuse
  move them.

## 7. Questions for the owner

1. **The 298 unreachable pairs.** Options: (a) name them unreachable and stop; (b) a second form
   built from Spark's function registry, `name(col, ...)` at the arity a registry lookup accepts;
   (c) a coverage row for each entry first, which is row 328's rule and the fusing rows' job.
   Recommended: (a) here, with (b) as a scope item if the post's claim needs the whole census
   asked.
2. **Derive or check `fused_today`.** Derived, as for casts, or kept by hand and checked?
   Recommended: checked, because the census's `fused_today` also carries `~` claims (derived
   operands) that the probe does not model.

## 8. Done when

- the reverse check runs in the census check with its three rules, and the census claims what it
  found;
- every reachable pair's verdict in `census_fused.tsv` agrees with `fused_today`;
- every unreachable pair is named with its reason;
- or, if the measurement of 2.3 rejects substitution, step 1 is in and the record says why step 2
  was not built.

## 9. Open points for the lead

1. `varka-348-census` (open, matrix-green on 1 November) likely adds `dec` to the comparison
   entries. If it merges first, P1 changes to "step 1 passes on master". This row should not edit
   the same census lines in parallel.
2. The operand-position rule of 2.1 rests on `coverage.json` listing operands in order. If a row
   lists them otherwise, the rule needs a position field in `coverage.json`, which
   `VarkaCoverageSuite` writes. That is a small change but moves a generated file.

## 10. Outcome
