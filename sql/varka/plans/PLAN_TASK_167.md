# Task 167: the bytes oracle pins shapes, not options

*Scoped 22 September 2026 (milestone 5 section 2.103, row 167) from the
milestone's closing review; built the same night.*

## 1. The question

`sql/varka/emitted_bytes.json` is the emitter's oracle: a hash of every emitted
method body, for ninety-two coverage rows and ten thousand fuzz shapes, at two
widths. Any change to what the emitter produces moves a hash, and a task that
moves one has to say why. It is the reason a refactor as large as task 159's
six seams could land without anyone reading the bytecode.

It pins one point in the option space: `VarkaEmitOptions.DEFAULTS`. That was
enough while the options were a testing surface - a suite that wanted a variant
asked for it and asserted on the result in the suite itself. Task 121 changed
the shape of the question by giving one field a session configuration,
`spark.sql.codegen.varka.emit.useAVX`, so a user can now select an emission
that no committed hash covers, on purpose, in production.

The question this task answers is therefore narrow and checkable: which options
change the emitted bytes at all, which of them can a configuration reach, and
what does the oracle have to hold so that "the bytes did not move" keeps meaning
what it says.

## 2. The change

An opt-in audit in `VarkaEmittedBytesSuite` that emits the oracle's own shapes
under every value of every option and counts the hashes that differ from the
defaults, and an `option_arms` section in the oracle that pins the emissions a
session can select.

The audit is a measurement rather than an assertion: it fails only if it
emitted nothing, because a report of "no option moves anything" produced by a
loop over an empty list would be the worst possible outcome. It runs as

    VARKA_OPTION_AUDIT=true build/sbt \
      'catalyst/testOnly *VarkaEmittedBytesSuite -- -z "option audit"'

and writes `target/varka-option-audit.txt`. Both values of every boolean are
emitted, not just the non-default one, so the report shows which value is the
default in its own numbers - the arm that moves nothing - instead of resting on
a reader's memory of the `DEFAULTS` constructor.

The oracle gains one digest per arm per width rather than a shape-by-shape
block per arm. The file is already large, and five arms of ten thousand shapes
would multiply it by five to say the same thing: a digest over every coverage
row and every fuzz shape catches any change in that arm's emission, while the
defaults keep the shape-by-shape detail that says *which* shape moved.

## 3. Predictions, registered before the run

1. **`useAVX` moves bytes.** It selects between the conversion form and the
   magic form at the long lane, so the long-lane shapes move and the int-lane
   ones do not.
2. **Most other options move bytes too**, since each exists because it changed
   something worth measuring, and the ones that move nothing on these shapes
   will be the ones whose subject the shape set does not reach.
3. **No option outside `useAVX` is reachable from a configuration**, so the
   oracle needs exactly one family of arms.

## 4. Verification

The audit over ninety-two coverage rows and two hundred fuzz shapes per lane at
both widths; the oracle regenerated and its own test green against it;
`dev/scalastyle`, `dev/lint-java`, the column and non-ASCII scans.

## 5. Outcome

*Measured 22 September 2026; the report is `target/varka-option-audit.txt` from
the run described above.*

**Prediction 1 held, and sharpened into a number.** `useAVX` at 0, 1 and 2
moves 24 coverage hashes and 168 long-lane fuzz shapes at each width and no
int-lane shape at all, exactly as the lowering predicts: below level 3 the
converts fall back and the long lane emits the magic form. The oracle's new
digests then say something the counts could not - that levels 0, 1 and 2 emit
*identically to each other*, and that level 3 and an unstated level do too. The
option has five values and two emissions.

**Prediction 2 held with three exceptions, and they are the interesting rows.**
`validityOrFirst` moves nothing at either value, on any shape, at either width;
`narrowHalfSpecies=true` moves six coverage hashes and no fuzz shape; and
`guardDayProducers=false` moves eighteen int-lane shapes and no coverage row.
The first is the one worth following: an option whose subject the whole oracle
cannot reach is either dead or guarding something the shape set does not
contain, and this task has not established which. That is a new row rather than
a finding here: `SCOPE_MILESTONE_8.md` item 48.

**Prediction 3 held.** `useAVX` is the only field a configuration reaches; every
other field is set through a test hook. `VarkaEmitOptions`' class javadoc now
says so, names the two `misdescribe*` fields as the fault injectors they are -
emission under `misdescribeWordLiveness` raises rather than producing a class,
which the audit records rather than dying of - and states the rule that a field
which gains a configuration has to gain a pinned arm with it.

## 6. Explicitly out of this task

Widening the arms to the options a *benchmark* selects rather than a session:
`dev/varka_bench_surface.sh`'s arms reach the emitter through the same test
hook, and pinning them would pin a measurement harness rather than a product
surface. Deciding what `validityOrFirst` is for. And the audit's own cost: it
emits the shape set once per arm and takes seconds, so nothing here argues for
making it cheaper.
