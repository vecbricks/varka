# VARKA-154: the width-audit census pins verdicts only

*Opened and closed 20 September 2026, from `m5/PLAN.md` 2.90.*

## 1. The problem

`sql/varka/width_audit.json` is VARKA-153's census: per shape and per vector
width, what C2 printed under `PrintIntrinsics` for the shape's Vector API calls
in a forked JVM. C2 prints three kinds of line for a call it did not inline on
an attempt, and the suite's own comment separated them: `not supported` is
architectural and the verdict the audit exists for; `missing constant` is a
first late-inline attempt C2 may retry; `unbox failed` is a vector reaching a
call as a heap object once the shared templates have seen other kernels. The
file recorded all three, and the check compared all three.

The first regenerations after VARKA-153 showed why that cannot hold. Two runs of
an unchanged tree on the same host moved `missing constant` lines on shapes
nothing had touched - one appearing under `add_months(d, i)` at 128 bits, one
disappearing under `greatest(l, l2)` at 256, a third under
`CASE WHEN l < l2 THEN l ELSE l2 END` in an earlier run - because when C2
reaches a method in the probe depends on timing, not on the machine. So the
local check failed on a quiet tree, and a regeneration blessed whichever timing
that run had.

## 2. The change

The census file carries the `not supported` lines alone. The probe and the
in-memory census are unchanged - every line is still read, and the invariant
that nothing is refused at the host's width still rests on `isRefusal` - but
`render` writes `verdicts(census)`, the refusal lines per shape, and the file's
description says what it pins and why the other two kinds are left out. The
coverage table's *128-bit lanes* column already filtered to `not supported`
lines, so it is unchanged; its comment now says the file carries only those.

A new test states the property the file rests on: a second probe at the
preferred width gives the same verdict lines per shape as the first, whatever
else C2 printed. It costs one more forked JVM, about twenty seconds.

## 3. Outcome

Two regenerations on this host, one after the other, produced byte-identical
files. Against the committed census the diff removes the `missing constant`
and `unbox failed` lines and nothing else: every `not supported` line at every
width is where it was, so the audit's findings (2.89) stand as recorded and the
file no longer changes with the run that wrote it.
