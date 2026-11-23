# VARKA-642: Declare an emit option in one place, so parallel option rows stop conflicting

## 1. Where this came from

On 22 and 23 November 2026, four pull requests built in parallel each added one emit option, and
`VarkaEmitOptions.java` conflicted between them four times:

| row | branch | option |
| :--- | :--- | :--- |
| 383 | `varka-383-profile-predicate-r` | `uncountedMainLoop` |
| 611 | `varka-611-guarded-product-r` | `checkedLongMultiply` |
| 600 | `varka-600-bigint-float-avx2-r` | `narrowConversionForm` |
| 358b | `varka-358b-exp-port` | `mathPortExp` |

The lists in the test sources `VarkaMatrixSuite`, `VarkaMatrixRuns` and `VarkaEmitOptionSuite`
conflicted too. The owner's coordinator asked for this row on 23 November 2026.

## 2. The admission check, done

Why every option conflicts: `VarkaEmitOption`'s class doc lists what one option costs today:
"the record component, its builder setter and `with*` method, its javadoc, its value in
`VarkaEmitOptions#DEFAULTS`, and one entry here" in `VarkaEmitOption.TABLE`. Each of the four
branches changes about 26 to 66 lines of `VarkaEmitOptions.java` and 9 to 11 of
`VarkaEmitOption.java` (`git diff --numstat` against `origin/master` at `d127a7f62d8`).
Each removes the same three lines of `VarkaEmitOptions.java`: the last line of the record's
component list, of `DEFAULTS` and of the builder's positional constructor call. All three are
positional lists, so every new option appends at the same tail, and any two branches collide
there, even when their options have nothing to do with each other.

`TABLE` already enumerates every option once, in the record's order, and `canonical()`, the
bytes inventory, the IR fuzzer and `VarkaEmitDump` read it. `DEFAULTS` is written out by hand
and not built from `TABLE`, because linking the table's method references costs about 17 ms in
a fresh JVM, which every executor would pay (the comment on `DEFAULTS`). Any design here must
keep that cost off the executor.

## 3. What to do

1. **Pick a registration style where one option is one declaration.** Candidates, to compare by
   the lines an added option touches and by the executor's start cost:
   (a) options grouped into small per-family records (math, conversion, validity, budgets), so
   two rows in different families edit different lists;
   (b) one declaration per option (name, type, default, reason, tag) from which the record, the
   builder and `DEFAULTS` are generated, with the generator checked by a suite;
   (c) keep the record, but write each positional list one option a line, so that two appended
   options are two adjacent lines a union merge can keep, as `sql/varka/matrix/skips.tsv` is
   merged today (#1633). This is the cheapest and the least safe: a union merge of Java can
   keep two lines that do not compile together, so the build has to catch it.
2. **Make the test lists read `TABLE`.** Where `VarkaMatrixSuite`, `VarkaMatrixRuns` and
   `VarkaEmitOptionSuite` name options by hand, read them from `TABLE` or from a per-option
   field, so a new option adds no line there.
3. Port the existing options in one change, behind no switch, with `emitted_bytes.json` and
   the canonical strings unchanged.

## 4. Done when

Adding a new emit option changes at most one declaration plus the code that reads it, two
branches that each add one option merge without a conflict in the option files or the three
suites (shown by merging two such branches in a scratch tree), `DEFAULTS` costs no more at
start than today, and the canonical strings and `emitted_bytes.json` are unchanged.

## 5. Outcome

Not started.
