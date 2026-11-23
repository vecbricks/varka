# VARKA-642: Declare an emit option in one place, so parallel option rows stop conflicting

*Moved to milestone 9 on 23 November 2026 with row 642 (`SCOPE.md` item 138). A bare
`PLAN.md`, `results/` path or other `VARKA-<n>.md` below names milestone 8's, in `m8/`,
unless that plan moved too (`SCOPE.md` item 138).*

## 1. Where this came from

On 22 and 23 November 2026, four pull requests built in parallel each added one emit option, and
the option files conflicted between them four times:

| row | branch, head | option | `VarkaEmitOptions.java` | `VarkaEmitOption.java` |
| :--- | :--- | :--- | ---: | ---: |
| 383 | `varka-383-profile-predicate-r`, `71acaccd005` | `uncountedMainLoop` | 59 +, 5 - | 20 +, 1 - |
| 611 | `varka-611-guarded-product-r`, `9e0f70c7b71` | `checkedLongMultiply` | 98 +, 5 - | 19 +, 1 - |
| 600 | `varka-600-bigint-float-avx2-r`, `ed6d4ace513` | `narrowConversionForm` | 34 +, 3 - | 9 +, 0 - |
| 358b | `varka-358b-exp-port`, `88e8d1d2f9d` | `mathPortExp` | 30 +, 3 - | 9 +, 0 - |

The counts are `git diff --numstat` against master at `d127a7f62d8`. The hand-written lists in
the test sources `VarkaMatrixSuite`, `VarkaMatrixRuns` and `VarkaEmitOptionSuite` conflicted
too. The owner's coordinator asked for this row on 23 November 2026.

## 2. The admission check, done

`git merge-tree --write-tree` of each of the six pairs of these branches reports a conflict in
both `VarkaEmitOptions.java` and `VarkaEmitOption.java`, and every pair also conflicts in at
least one of the three suites. The conflicts come from two causes, not one:

1. **Several values packed on one line of a positional list.** The record's components,
   `DEFAULTS`, the `Builder.build()` call and `toBuilder()` list the options in order, several
   to a line. A new option inserted anywhere rewrites a shared line. 611 rewrites the tails of
   the lists; 358b rewrites the middle lines `decimalStore, wideConversionForm,
   roundToOddByShifts, mathUlpContract,` and `false, NarrowGroupStore.MASKED, ...`, and still
   conflicts with 600.
2. **Two insertions at the same point of any ordered list.** `VarkaEmitOption.TABLE` lists each
   option once, one entry to a few lines, and still conflicts: 383, 611 and 600 all insert after
   the `roundToOddByShifts` entry, and 383 and 611 both rewrite the table's last line, since a
   Java argument list takes no trailing comma. Sorted imports collide the same way.

An option costs more places than `VarkaEmitOption`'s class doc lists: the record component and
its javadoc, the `Builder` field, setter, `toBuilder()` copy and `build()` argument, the `with*`
method, the value in `DEFAULTS`, the `TABLE` entry, and for a choice its enum, import and null
check in the constructor. The class doc is out of date; this row corrects it.

`DEFAULTS` is written out by hand, not built from `TABLE`, so that an executor does not link the
table's method references at start. `VarkaEmitOptionSuite`'s test that the defaults and their
rendering never load the table holds that today, and any design here must keep it passing.

## 3. What to do

1. **Pick a registration style that survives both causes.** An ordered list written by hand
   conflicts whenever two rows insert at one point, however it is laid out, so compare:
   (a) one file per option (a small class or a resource), with the record's order derived, for
   example by name, rather than written;
   (b) one declaration per option from which the record, the builder and `DEFAULTS` are
   generated, the declarations themselves one file each or kept in an order derived by name;
   (c) the record kept, one value a line in every positional list and a fixed sentinel last
   element, so that an append never rewrites the previous line. A union merge is not part of
   this: it applies to the whole `.java` file and would silently double an edited javadoc or
   check that still compiles, as `sql/varka/.gitattributes` already warns for `skips.tsv`.
   Measure each by the lines and files an added option touches.
2. **Split the suites' lists into derived lists and oracles.** A list that only enumerates
   options to run them (`VarkaMatrixRuns.C2_OPTIONS`) can come from a per-option field. A list
   that checks the table (`VarkaEmitOptionSuite`'s `KNOB`, `MACHINE`, `FAULT_INJECTOR` and
   `PRICED_CHECK` names, `VarkaMatrixSuite`'s expected `levels`) stays hand-written, since read
   from `TABLE` it would compare the table with itself; it is laid out one entry a line.
3. Port the existing options in one change, behind no switch, with `emitted_bytes.json` and
   the canonical strings unchanged, and correct `VarkaEmitOption`'s class doc.

## 4. Done when

Adding an option changes at most one declaration plus the code that reads it and the oracle
lines that name it. A scratch merge of two branches shows no conflict in the option files or
the derived lists for three cases: two options in the same family inserted after the same
neighbour, two options both appended at the end, and two options that each add an import. The
oracle lists conflict at most in one line each. `VarkaEmitOptionSuite`'s test that the defaults
never load the table still passes, and the canonical strings and `emitted_bytes.json` are
unchanged.

## 5. Outcome

Not started.
