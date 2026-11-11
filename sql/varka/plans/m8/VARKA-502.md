# VARKA-502: Every IR leaf and node replays from a reproduction file

## 0. What exists now (admission check)

From master at `18f6565d073`, and from branch `varka-347-reader` (`07752759e36`, pull request
#1236, open). Paths are under `sql/catalyst/src/test/java/.../codegen/varka/` unless they say
otherwise.

1. **The parser.** `VarkaCanonicalIr.parse` (`VarkaCanonicalIr.java:63-76`) tokenizes the
   canonical form, builds a tree, and refuses a string whose tree does not render back to it. The
   head switch (`:133-194`) has arms for every value node and for `Compare`, `NullSafeEqual`,
   `And`, `Or`, `Not`, `IsNotNull`, `InRanges`, `IfElse`, `CondValue` and `BoolOutput`. The leaf
   reader (`:211-233`) reads `col:`, `lit:` and, since #1230, `dec:N:long:pP`.
2. **What it does not read.** The five boolean records of row 331, as the canonical form writes
   them (`VarkaVectorIR.java:1592-1600`): the leaf `bool:N[:lane]` (`BoolColumn`), and the heads
   `condConst:TRUTH[:lane]` (`CondConst`, a head with no children), `boolCmp:OP` (`BoolCompare`),
   `condIf` (`CondIf`) and `boolNullSafeEq` (`BoolNullSafeEq`). A `bool:` token fails as "not a
   leaf" and each head as "no IR node is rendered as". So a reproduction file of a kernel over a
   boolean column cannot be replayed. That kernel is reachable from SQL today:
   `VarkaConditionCompiler` builds all five (`VarkaConditionCompiler.java:324-331`, the
   `coalesce` and `CASE WHEN` folds at `:435-461`).
3. **The completeness check exists.** `everyNodeTypeRoundTrips`
   (`VarkaReproductionSuite.java:160-179`, from #1230, `VARKA-347.md` 10.2 finding 2) round-trips
   `everyNodeType()` (`:68-91`) and `theRest()` (`:94-110`). It collects the records under the
   sealed `VarkaVectorIR` with `getPermittedSubclasses` (`:122-128`) and the types the trees
   reach through `childrenOf` (`:131-136`). It fails on any record that no tree reaches, unless the
   record is in `NOT_PARSED_YET` (`:118-119`), which lists the five boolean records. It also fails
   if a listed record is reached, so the list cannot go stale.
4. **What the check misses.**
   - *The escape list.* Any new record can be put in `NOT_PARSED_YET` and pass, so the check
     depends on a hand-kept exemption.
   - *Variants.* It asks only that a record is reached once. A parse arm can read one form and
     misread another, and nothing checks every form. The forms are the enum members (`CompareOp`,
     `IntOp`, `Overflow`, `BitOpKind`, `TruncLevel`, `Truth`), the booleans (`IntNeg.abs`,
     `MakeDate.failOnError`) and the lane tag (`laneTag`, `VarkaVectorIR.java:398-400`, which
     writes nothing for `INT` and `:lane` for every other lane). The drawn trees reach many of
     these, but nothing requires all of them: the hand trees, for example, have no `abs` and no
     long-lane `CondValue`.
   - *Fields the rendering does not carry.* The render-back check proves that the parsed tree
     has the same shape hash. It does not prove the tree is the same. The javadoc
     (`VarkaCanonicalIr.java:38-44`) names the two fields that are not rendered: `ConstDivide`'s
     dividend bound and `BoundedDivide`'s derived pair. Only 60 long-lane draws compare bytes
     (`VarkaReproductionSuite.java:198-201`).
5. **Byte identity is noted, never asserted.** `VarkaReplay.of` emits the class again, compares
   it with the class the file carries, and only adds a note (`VarkaReplay.java:113-118`).
   `reproduced()` compares signatures only (`:67-69`). The end-to-end suite's `assertReplays`
   (`sql/core/.../VarkaReproductionEndToEndSuite.scala:116-119`) checks only `reproduced()`. So
   today no test fails if a replay rebuilds a different class.
6. **The writer handles both column kinds.** A boolean input is recorded at width 1 with its
   bitmap of `(rows + 7) / 8` bytes (`sql/core/.../varka/VarkaKernelRunner.java:185-193, 538`).
   A decimal input is recorded at its vector's width, 16. `VarkaReplay.run` lays out `rows *
   width` bytes per input, enough for either. Only the parser stands in the way.
7. **The fault the end-to-end tests plant does not touch a boolean column.**
   `misdescribeDataBytes` shifts `dataBytes` (`VarkaBodyEmitter.java:307-325`) and the decimal
   segment (`:1689-1706`). A boolean column is mapped at `validityBytes` (`:1675-1677`). So a
   filter over a boolean column alone maps no segment the fault moves and writes no file.
8. **The IR fuzz grammar draws none of the boolean records and not `DecimalColumn`.** Both reach
   tests name them as out until row 352's refit (`VarkaIrFuzzSuite.scala:994-995, 1021-1022`). So
   `drawnTreesRoundTrip` cannot reach them, and the hand trees are their only round trip.
9. **Pull request #1236 does not touch the parser or `VarkaReproductionSuite`.** Diffed against
   master, it changes neither `VarkaCanonicalIr.java`, `VarkaReproductionSuite.java` nor
   `VarkaReplay.java`. The decimal lines of `theRest()` (`:102-104`) are already on master, from
   #1230. It adds one end-to-end test, a decimal filter whose short mapping fails and replays
   (`VarkaReproductionEndToEndSuite.scala`, after the filter test, 28 lines). So every line
   reference above in the catalyst files holds after #1236 merges. In the end-to-end suite, only
   lines after its line 156 move.

### What differs from the row text

- The row says the parser does not read the decimal leaf and the round trip has no completeness
  check. Both were fixed in #1230 (item 3). What is left is the five boolean records, the escape
  list, the variants, and the byte check (items 2, 4 and 5).
- "A decimal kernel replays from its reproduction file" is held by #1236's end-to-end test, once
  it merges. On master no decimal kernel comes from SQL, since the compiler declines every
  operator over a decimal (`VARKA-347.md` 10.2, departure 1). This row adds a catalyst-level
  decimal replay that does not wait on #1236 (3.3).

## 1. Goal and non-goal

**Goal.** Every record under `VarkaVectorIR` parses back from its canonical form, in every form
its constructor admits, to a tree equal to the original. One test holds this with no exemption
list, so a new record or enum member fails it until its parse arm exists. A reproduction file of
a boolean kernel and of a decimal kernel replays, and the replay rebuilds the failing class byte
for byte.

**Non-goal.** No change to the canonical rendering, so no shape hash moves. No grammar
production for the boolean or decimal records (row 352). No change to the writer or to
`VarkaEmitDump`.

## 2. Design

### 2.1 The parse arms

In `VarkaCanonicalIr`:

- `leaf`: `bool:N` and `bool:N:lane`, read like `col:`, building `BoolColumn`.
- `node`: `condConst:TRUTH` and `condConst:TRUTH:lane`, a head with no children, so the arm reads
  no child before the closing `)`. Its lane is read case-insensitively, as the leaf's is. A head
  with more than three parts is refused.
- `boolCmp:OP` over two `cond()`, `condIf` over three, `boolNullSafeEq` over two. `cond()`
  already refuses a value where a condition goes.

The javadoc's last sentence (`:46-49`) becomes the rule of 2.2.

### 2.2 The check: every record, every form, the same tree

Under the recommended answer to question 7.1, `everyNodeTypeRoundTrips` keeps its sealed
enumeration and loses `NOT_PARSED_YET`, with the stale-entry check that came with it. A record
that no tree reaches fails the test, with no exemption. `canonical` is already an exhaustive
switch, so a record that does not render is a compile error. Only parsing back is checked at run
time, and this test is where that check lives. Three more checks join it:

1. **The boolean trees** go into `theRest()`, at the int lane and the long lane:
   `BoolOutput(CondIf(BoolColumn, BoolCompare(LE, BoolColumn, CondConst(NULL)),
   BoolNullSafeEq(BoolColumn, CondConst(TRUE))))`, and a long-lane `IfElse` whose condition is a
   `BoolNullSafeEq` of a `BoolColumn` and a long `Compare`, over a `CondValue` at the long lane.
   `everyNodeType()` stays as it is, because `VarkaShapeCacheSuite` pins its hash.
2. **The variant sweep.** For every subtree of `everyNodeType()` and `theRest()` (the trees the
   reach check is over, not the drawn shapes), and for each record component of an enum type or
   `boolean`, the test rebuilds the record with every other value through its canonical
   constructor (by reflection over `getRecordComponents`), keeping the children. A constructor that refuses the value (an `IllegalArgumentException`, such as a
   decimal leaf at the int lane or a `CondValue` at a narrow lane) skips it. Each rebuilt record
   round-trips. This covers every `CompareOp`, `Truth` and lane tag the constructors admit, with
   nothing listed by hand. A new enum member, such as row 359's `ULT`, is swept with no edit to
   the test.
3. **The same tree, not only the same text.** `assertRoundTrips` also asserts that the parsed
   tree equals the original as a record. The one documented exception is normalised first, to
   what the parser builds (`VarkaCanonicalIr.java:180-186`): a `ConstDivide` off the int lane
   gets `EXACT_DIVIDEND_BOUND`, rebuilt through `withChildren`. An int-lane one is left alone,
   since the int grammar and the parser both use the two-argument constructor and its unstated
   bound (`VarkaIrGrammar.java:731`); the long grammar states a bound (`:1079`), which is the case
   the normalisation is for. Record equality covers every field, the unrendered ones too. The
   emitter is a function of the tree, the counts and the options, so an equal tree makes the
   same bytes. Bytes for the normalised case stay with the 60 long-lane draws.

### 2.3 Byte identity at the replay

`VarkaReplay` gains a component `Optional<Boolean> sameClass`: empty when the file carries no
class, and otherwise whether the rebuilt class equals it. The note stays. `assertReplays` in the
end-to-end suite asserts `sameClass` is true whenever the file carries a class. That applies to
every existing test as well as the new one. The emission-failure test's file carries no class and
is unaffected.

## 3. Tests and proofs

1. **The round trip** of 2.2, in `VarkaReproductionSuite`, with the boolean refusals added to
   `malformedIrIsRefused`: `boolCmp:LT` over two value leaves, `condConst:MAYBE`, `bool:0:long:p9`
   and `condConst:TRUE:long:x`.
2. **Planted faults, each run once and reverted.** They are never committed.
   - A parse arm left out (`condIf`): the round trip fails naming `CondIf`.
   - An arm that reads one form wrong (`condConst` ignoring its lane): only the sweep catches it,
     because the int-lane tree still passes.
   - A parse that drops a field the text carries: the `ConstDivide` arm giving an int-lane
     division the long bound. Record equality catches it. The render-back check does not.
   - A record added with no arm (a scratch `record Probe(VarkaVectorIR child)` in the sealed list,
     rendered as `(probe <child>)`): the test fails naming `Probe`.
3. **Catalyst replays without Spark**, modelled on `bitOutputReplays` (`:395-427`):
   - a boolean kernel over two `BoolColumn` bitmaps and an int column, at the int lane, whose
     file carries the class from `VarkaLoopEmitter.emit` and a recorded call;
   - a decimal kernel, the leaf alone and under a long add, as `VarkaDecimalLoadSuite`'s kernels
     are, with 16-byte slots that hold valid values.
   Each asserts that the replay returned and that `sameClass` is true.
4. **End to end** (`VarkaReproductionEndToEndSuite`): a cached view of a nullable boolean `b` and
   an int `x`, `SELECT IF(coalesce(b, x > 400), x, 0)` under `misdescribeDataBytes(-4)`. The int
   column's short mapping makes the kernel throw. The test asserts that the file's output names
   `bool:` and `condIf`, that one input has width 1 with `(rows + 7) / 8` data bytes, and that
   the file replays to its signature with `sameClass`. If the compiler does not fuse that
   expression, the build picks the nearest one that does, and keeps the same assertions on the
   file (**[I]**: the plan did not run the compiler on it).

## 4. Predictions and costs, registered before the build

- P1: every existing round trip and replay passes the new equality and `sameClass` checks
  unchanged. A failure there is a finding about the parser or the emitter, not a test to relax.
- P2: the sweep builds fewer than 2,000 trees and adds under two seconds to the suite on the
  laptop (**[I]**, from about 60 subtrees and at most 6 values per component).
- P3: no committed file moves: no shape hash, no `emitted_bytes.json`, no `coverage.json`.
- P4: the end-to-end suite gains one test, about as long as the filter test it is modelled on.

## 5. Order, files and size

One pull request, test code only, on master:

| File | Change |
|---|---|
| `VarkaCanonicalIr.java` | four head arms, the `bool:` leaf, the javadoc rule |
| `VarkaReproductionSuite.java` | the boolean trees, `NOT_PARSED_YET` gone, the sweep, record equality, the refusals, two replays |
| `VarkaReplay.java` | `sameClass` |
| `sql/core/.../VarkaReproductionEndToEndSuite.scala` | `assertReplays` checks `sameClass`; the boolean test |
| `sql/varka/skills/emitter-and-ir.md`, `testing-and-debugging.md` | the round trip joins the reach list, with no exemption |

Size: small, as the row says.

**Against #1236.** The two touch the end-to-end suite only, in different places: #1236 adds its
test after the filter test (line 156), and this row edits `assertReplays` (`:116-119`) and adds
its test at the end. Whichever merges second merges master in. Once both are in, #1236's decimal
test is held to `sameClass` too.

**Against the rows about to add records.** Each adds its arm and a tree, under the same check:

- **`WidenDecimal`** (row 157, `VARKA-157.md` 2.1): `case "wdec" -> new WidenDecimal(node())`, and
  a `theRest()` tree with it as a root over a decimal sum. Its plan writes the form as
  `wdec(<child>)`. The tokenizer reads only `(head child...)`, so the rendering must be
  `(wdec <child>)` (section 9).
- **`ConvertLane`** (row 343, `VARKA-343.md` 2.1): its plan gives no rendering. On the
  conversions' pattern it would be `(convert:<TARGET> <child>)`, read by `lane(parts)`, with a
  tree that converts from int to double and from double to long. The sweep then tries every
  target and drops the ones the constructor refuses.
- **`DecimalWord`** (row 359, `VARKA-359.md` 2.2): `decw:<ordinal>:lo|hi:p<P>` in `leaf`, its
  `Word` read from `lo` and `hi`, and a tree that reads both words. The sweep covers `Word` and the
  four unsigned `CompareOp` members. It also tries `BoolCompare` with `ULT`, so if that
  constructor accepts the unsigned members, row 359 learns it here.

This row should land first. It is small and touches test code only, and once it is in, each of
those rows' parse arms is checked by a test rather than by the row's own care. None of them
depends on it to build.

## 6. Out of this row

- The grammar productions for the boolean and decimal records (row 352).
- A replay over a file written by another JDK or vector width. The file records both, and that
  replay is a separate question.
- `canonicalShallow`, the line-map rendering, which `VarkaEmitterContractSuite` already pins.

## 7. Questions for the owner

1. **Delete `NOT_PARSED_YET` outright**, so a record without a parse arm cannot merge, rather than
   keep an empty list for the next row to use? Recommended: delete. A row that adds a record adds
   its arm in the same pull request, which is a few lines.
2. **The variant sweep by reflection** (2.2 item 2), or hand-written trees per form? Recommended:
   the sweep. It needs no upkeep and catches new enum members, at the price of one reflective
   helper in a test.

*The owner took both recommendations (31 October 2026):* `NOT_PARSED_YET` is deleted, and the
variant sweep is the reflective one.

## 8. Done when

- the five boolean records have parse arms, and `NOT_PARSED_YET` is gone;
- `everyNodeTypeRoundTrips` fails on a record that no tree reaches, sweeps every enum and boolean
  form the constructors admit, and holds the parsed tree equal to the original; the planted
  faults of 3.2 each fail it;
- a boolean kernel's file, written end to end, replays to its signature with the same class
  bytes; a decimal kernel's file replays the same way, in the catalyst test here and, after
  #1236, end to end;
- `assertReplays` holds every end-to-end replay that carries a class to `sameClass`.

## 9. Open points for the lead

1. The brief said #1236 adds lines to `theRest()`. It does not (item 0.9). The decimal lines
   there are from #1230, so this plan's references hold after #1236.
2. `VARKA-157.md` 2.1 writes `WidenDecimal`'s form as `wdec(<child>)`. That needs a one-word fix
   in that plan, to `(wdec <child>)`, before 157b builds it. Otherwise the parse arm cannot read
   it.
3. `VARKA-343.md` names no canonical form for `ConvertLane`. Its builder picks one; 5 suggests
   `(convert:<TARGET> <child>)`.
4. P1 assumes the evaluator defines the class `VarkaLoopEmitter.emit` builds under the shape
   name, with nothing rewritten after (**[I]**: the plan did not trace the shape cache's admit
   path for a rewrite). If `sameClass` fails on an existing end-to-end test, that is the first
   place to look. A warmed kernel is not a cause: its mark is in the shape hash
   (`VarkaShapeCacheImpl.java:539`), the class name is the hash (`:505-507`), and the replay names
   the class from the file's hash.

## 10. Outcome

## Build sheet (11 November 2026)

Follow `BUILD_SHEETS.md` (same folder) for the environment, the lock, the linters, the commit and
where to stop. Everything below was read at origin/master `c62f492fc89`. Paths without a
directory are under `sql/catalyst/src/test/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/`.

**What is stale above.** Sections 0 to 9 were written at `18f6565d073`. Their line numbers have
moved; use the symbols below. #1236 has merged: the decimal end-to-end test is on master
("a decimal column's short mapping fails in its kernel ..."). The `wdec`, `convert`, `decw`,
`cross`, `null`, `math` and `round` arms and their `theRest()` trees have landed (section 5's
list is done), and `everyCompareOpRoundTrips` exists. What is left is exactly: the five boolean
parse arms, `NOT_PARSED_YET` gone, the variant sweep, record equality, `sameClass`, two catalyst
replays and one end-to-end boolean test. `dev/varka_push_check.py` names `NOT_PARSED_YET` too, so
this row is not test-only: it edits that script, and ruff applies.

### B0. Premise re-check

Each command prints what follows it; anything else is a stop.

| Command (from the worktree root) | Expected |
|---|---|
| `grep -c NOT_PARSED_YET <dir>/VarkaReproductionSuite.java` | `3` |
| `grep -cE '"(bool\|condConst\|boolCmp\|condIf\|boolNullSafeEq)"' <dir>/VarkaCanonicalIr.java` | `0` |
| `grep -n 'byte for byte' <dir>/VarkaReplay.java` | one line, inside `of`, a `notes.add` |
| `grep -n 'private def assertReplays' sql/core/src/test/scala/org/apache/spark/sql/execution/VarkaReproductionEndToEndSuite.scala` | one line |
| `grep -n NOT_PARSED_YET dev/varka_push_check.py` | two lines: `REGISTRY_PATTERN` and the `VarkaReproductionSuite` entry of `REGISTRIES` |
| `grep -nE '"\((condConst:\|boolCmp:\|condIf \|boolNullSafeEq )\|"bool:"' sql/catalyst/src/main/java/org/apache/spark/sql/catalyst/expressions/codegen/varka/VarkaVectorIR.java` | ten lines, five in `canonical` and five in `canonicalShallow` |

(`<dir>` is the directory named above. In the table `\|` is a plain `|` inside the regex.)

### B1. Scope

1. **`VarkaCanonicalIr.java`.**
   - `leaf`: in the final `switch (parts[0])`, add `case "bool" -> new BoolColumn(index, lane);`.
     The existing length check (2 or 3 parts) and lane parsing serve it unchanged. Add
     `{@code bool:N}` to the method's javadoc.
   - `node`: four arms in the head switch, beside `bool-out`:
     - `case "condConst" -> condConst(parts);` with a private static helper that requires
       `parts.length` of 2 or 3 (else `IllegalArgumentException("not a condition constant: ...")`),
       reads `Truth.valueOf(parts[1])` and the lane as `leaf` does
       (`LaneType.valueOf(parts[2].toUpperCase(Locale.ROOT))`, `LaneType.INT` when absent), and
       returns `new CondConst(truth, lane)`. It reads no child: the `expect(")")` after the switch
       closes it.
     - `case "boolCmp" -> new BoolCompare(CompareOp.valueOf(parts[1]), cond(), cond());`
     - `case "condIf" -> new CondIf(cond(), cond(), cond());`
     - `case "boolNullSafeEq" -> new BoolNullSafeEq(cond(), cond());`
   - Class javadoc: replace its last sentence ("A node type added to the IR without a case here
     fails that check, ...") with: "The round trip (`VarkaReproductionSuite`) reaches every record
     of the sealed IR with no exemption, rebuilds each with every enum and boolean value its
     constructor admits, and holds the parsed tree equal to the original, so a record or an enum
     member without a case here fails it."
2. **`VarkaReproductionSuite.java`.**
   - Delete `NOT_PARSED_YET`, its javadoc, `missing.removeAll(NOT_PARSED_YET)` and the three
     `stale` lines. Change the comment above the completeness check to "Complete over the sealed
     interface, so a new node type fails here until a tree above reaches it."
   - `theRest()`: change the last tree's comment to "Over comparisons." and add, after it:
     `new BoolOutput(new CondIf(new BoolColumn(0), new BoolCompare(CompareOp.LE, new BoolColumn(1),
     new CondConst(Truth.NULL)), new BoolNullSafeEq(new BoolColumn(0), new CondConst(Truth.TRUE))))`
     and, at the long lane (`x` is the method's `ColumnRef(1, LONG)`):
     `new IfElse(new BoolNullSafeEq(new BoolColumn(0, LaneType.LONG), new Compare(CompareOp.LT, x,
     new LiteralSlot(0, LaneType.LONG))), new CondValue(new CondConst(Truth.FALSE, LaneType.LONG),
     LaneType.LONG), x)`. Leave `everyNodeType()` as it is (`VarkaShapeCacheSuite` pins its hash).
   - `assertRoundTrips(tree)`: after the text check add
     `assertEquals(normalised(tree), parsed, () -> "the tree read back differs: " + text);` where
     `normalised` rebuilds the tree bottom-up through `VarkaVectorIR.withChildren` and replaces a
     `ConstDivide` whose `child().laneType() != LaneType.INT` by
     `new ConstDivide(child, divisor, ConstDivide.EXACT_DIVIDEND_BOUND)`, as the parser builds it.
   - The sweep, a new test after `everyNodeTypeRoundTrips`:
     `@DisplayName("every record reads back in every enum and boolean form its constructor admits")`
     `void everyFormRoundTrips()`. For every subtree of `everyNodeType()` and of each `theRest()`
     tree (a recursive walk over `VarkaVectorIR.childrenOf`, the tree itself included), call
     `variants(subtree)` and `assertRoundTrips` each result. `variants(node)`:
     - `RecordComponent[] cs = node.getClass().getRecordComponents()`; the canonical constructor
       is `node.getClass().getDeclaredConstructor(<the components' getType()>)`; the current
       values come from each component's `getAccessor().invoke(node)`;
     - for each component whose type `isEnum()` (every constant of `getEnumConstants()`) or is
       `boolean.class` (`false`, `true`), and for each value other than the current one, build a
       copy of the values with that one changed and call `newInstance`. The trailing `int hash`
       components keep their value: every compact constructor recomputes it;
     - an `InvocationTargetException` whose `getCause()` is an `IllegalArgumentException`
       (`SparkIllegalArgumentException` is one) skips that value; any other exception fails the
       test (throw it, do not catch it).
     The test method declares `throws ReflectiveOperationException`.
   - `malformedIrIsRefused`: add four `assertThrows(IllegalArgumentException.class, ...)` lines,
     with the comment "A boolean head over values, an unknown truth, and a boolean leaf or
     constant with a part too many.": `(boolCmp:LT col:0 lit:0)`, `(condConst:MAYBE)`,
     `bool:0:long:p9`, `(condConst:TRUE:long:x)`.
   - Two replays, after `bitOutputReplays`, modelled on it:
     - `@DisplayName("a boolean kernel's file replays to the class it carries, byte for byte")`:
       outputs `List.of(new BoolOutput(new CondIf(new BoolColumn(0), new Compare(CompareOp.LT,
       new ColumnRef(2), new LiteralSlot(0)), new BoolColumn(1))))`, 3 inputs, 1 literal, 100
       rows; inputs `Input(1, 16, 0, 0, false, <13 random bytes>, null)` twice and
       `Input(4, 400, 0, 0, false, <400 random bytes>, null)` (`new Random(502)`); output widths
       `List.of(VarkaReproduction.KernelCall.BIT_OUTPUT)`; int literals `List.of(0)`; class bytes
       from `VarkaLoopEmitter.emit(VarkaShapeCacheImpl.classNameFor(hash), outputs, 3, 1,
       VarkaShapeCacheImpl.sourceFileFor(hash), "shape " + hash, VarkaEmitOptions.DEFAULTS)`, as
       `verifyFailureReplays` builds them. Read the file back through
       `VarkaReproduction.parse(written.render())`, replay it, and assert `actual()` is empty,
       `notes()` contains "the kernel returned", and `sameClass()` is `Optional.of(true)`. Then
       replay a copy whose class bytes have byte 100 incremented and assert `sameClass()` is
       `Optional.of(false)`.
     - `@DisplayName("a decimal kernel's file replays to the class it carries, byte for byte")`:
       outputs `List.of(new DecimalColumn(0, LaneType.LONG, 9), new IntArith(IntOp.ADD,
       Overflow.WRAP, new DecimalColumn(0, LaneType.LONG, 9), new LiteralSlot(0, LaneType.LONG)))`,
       1 input, 1 literal, 64 rows; input `Input(16, 1024, 0, 0, false, data, null)` where slot
       `r` holds the long `(r - 32) * 1001L` little-endian in bytes `16r .. 16r+7` and its sign
       extension (`0` or `-1` bytes) in `16r+8 .. 16r+15`; output widths `List.of(8, 8)`; long
       literals `List.of(5L)`, int literals empty. Same three assertions as above.
3. **`VarkaReplay.java`.** Add a fourth record component `Optional<Boolean> sameClass` with the
   javadoc `@param sameClass whether the emitter rebuilt the class the file carries byte for byte;
   empty when the file carries none, or the emitter threw`. In `of`, compute
   `Optional<Boolean> same = file.classBytes() == null ? Optional.empty()
   : Optional.of(Arrays.equals(bytes, file.classBytes()))` where the note is written (keep the
   note), and pass it to `done` and `failed`, which gain the parameter. The emitter-threw path
   passes `Optional.empty()`.
4. **`sql/core/.../execution/VarkaReproductionEndToEndSuite.scala`.**
   - `assertReplays`: after the `reproduced()` assertion, when `file.classBytes() != null`,
     `assert(replay.sameClass().isPresent && replay.sameClass().get().booleanValue(),
     replay.report().asScala.mkString("\n"))`.
   - One test after the decimal test, "a boolean column's kernel writes a file with its bitmap,
     which replays to the same class": a view `varka_reproduction_bools` built for both sessions
     as the decimal test builds its view, from
     `range(0, 1000).selectExpr("IF(id % 3 = 2, NULL, id % 2 = 0) AS b", "CAST(id AS INT) AS i")`;
     the query `SELECT i FROM varka_reproduction_bools WHERE CASE WHEN b THEN i > 400 END` (the
     coverage row `CASE WHEN b THEN i > 5 END`, which fuses as a predicate), under
     `withMisdescribeDataBytes(-4)` and `withFailureDeclared`, as the filter test runs. Assert:
     the kind is `IndexOutOfBoundsException`; the joined `file.outputs()` contains `bool:` and
     `condIf`; `call.selectionBitmaps()`; some input has `width() == 1` and
     `data().length == (call.rows() + 7) / 8`; `file.classBytes() != null`; then
     `assertReplays(file)`.
5. **`dev/varka_push_check.py`.** In `REGISTRY_PATTERN` drop `|NOT_PARSED_YET`. The
   `VarkaReproductionSuite` entry of `REGISTRIES` becomes `("round trip", BUILT, ())`.
6. **`sql/varka/skills/testing-and-debugging.md`**, the bullet "The canonical IR is a complete
   serialization of a kernel": replace its last sentence ("A new node type fails that test until
   the parser has a case for it.") with "The round trip has no exemption list: a new record, or a
   new value of an enum a record holds, fails it until the parser has a case for it, and a
   replay asserts that it rebuilt the file's class byte for byte (`VarkaReplay.sameClass`)."
7. **`PLAN.md` row 502**: replace the row's opening `**Planned**` with `**Done**`.

**Out of scope:** any change to `VarkaVectorIR.java` or another main file; the canonical
rendering (no shape hash moves); grammar productions for the boolean or decimal records (row
352); `canonicalShallow`; `VarkaEmitDump`; `VARKA-157.md` and `VARKA-343.md` (section 9 points 2
and 3 are done or moot); any other test of `VarkaReproductionEndToEndSuite`.

### B2. Tests and their oracles

| Test | Asserts | Oracle |
|---|---|---|
| `everyNodeTypeRoundTrips` | every record is reached, with no exemption | the sealed hierarchy (`getPermittedSubclasses`) |
| `everyFormRoundTrips` (new) | each admitted enum and boolean form parses back to an equal record | the constructors' own refusals, and record `equals` |
| `malformedIrIsRefused` | the four new strings are refused | the canonical grammar |
| the two catalyst replays (new) | the kernel returns; `sameClass` true, and false after one byte changes | the class bytes `VarkaLoopEmitter.emit` built before the file was written |
| `VarkaReproductionEndToEndSuite`, all tests | every replay that carries a class rebuilds it byte for byte | the class the failing JVM defined |
| the boolean end-to-end test (new) | a boolean kernel's file is written with its bitmap and replays | Spark's row engine (`checkAnswer`) and the file |

### B3. Planted faults

Each is made, its test run, the failure seen, and the change reverted with `git checkout -- <file>`
or by hand. None is committed.

1. Delete the `condIf` arm: `everyNodeTypeRoundTrips` fails, its message naming "no IR node is
   rendered as 'condIf'".
2. In the `condConst` helper ignore `parts[2]` (always `LaneType.INT`): `everyNodeTypeRoundTrips`
   fails on the long-lane tree, and `everyFormRoundTrips` fails too.
3. In `divc` build `new ConstDivide(child, divisor, ConstDivide.EXACT_DIVIDEND_BOUND)` for every
   lane: `everyNodeTypeRoundTrips` fails on record equality (the render-back check alone passes).
4. In `VarkaReplay.of` compute `same` as `Optional.of(true)` always: the boolean replay test fails
   on its `Optional.of(false)` assertion.

### B4. Commands, in order

```
flock "$VARKA_HEAVY_LOCK" build/sbt 'catalyst/testOnly *VarkaReproductionSuite'
# expected: every test passes, the two new replays and everyFormRoundTrips among them
flock "$VARKA_HEAVY_LOCK" build/sbt 'set LocalProject("sql") / Test / javaOptions += "-Dvarka.sanitizeMemory=true"' 'sql/testOnly *VarkaReproductionEndToEndSuite'
# expected: every test passes, none canceled (the sanitizer test runs)
python3 dev/varka_push_check.py --self-test     # expected: OK
ruff check dev/varka_push_check.py && ruff format --check dev/varka_push_check.py
```

Then the planted faults (B3), then section 6 of `BUILD_SHEETS.md`. `git status --short` after the
last run lists only the files of B1: no generated file moves.

### B5. Done when

`grep -c NOT_PARSED_YET <dir>/VarkaReproductionSuite.java dev/varka_push_check.py` prints `0`
twice; the two `testOnly` runs above pass with no canceled test; `python3 dev/varka_push_check.py`
exits 0.

### B6. Size

About 300 counted lines: the suite about 170, the parser 30, the replay 15, the end-to-end
suite 40, the script 2, the skill 3, the plan and outcome 40.

### B7. Stop rules

- `sameClass` is false on an existing end-to-end test (section 9 point 4's risk): stop and report
  the test and its replay report; do not relax the assertion.
- `everyFormRoundTrips` fails on a record this row does not touch (a form that parses to another
  record, or renders differently): stop and report the record and the form. That is a parser or
  rendering bug for the lead, not a test to narrow.
- A constructor in the sweep throws anything but an `IllegalArgumentException`.
- The boolean query does not fuse (no file is written) or its outputs lack `bool:` or `condIf`:
  stop; do not try another query.
- Either catalyst replay throws in the emitter or its kernel throws.

### B8. Decisions already taken

- "The owner took both recommendations (31 October 2026): `NOT_PARSED_YET` is deleted, and the
  variant sweep is the reflective one." (section 7)
- The end-to-end query and the catalyst replays' shapes are fixed by this sheet.

### B9. Afterwards (the lead, not the agent)

Review and push. No benchmark, no EPYC dispatch. Open pull requests touching the same files:
#1428, #1478 and #1484 edit `PLAN.md` only (rebase if one merged first; the conflict is in
other rows).
