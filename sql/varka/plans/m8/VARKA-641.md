# VARKA-641: The bounded magic `BIGINT` to `DOUBLE` form at two long lanes

## 1. Where this came from

The same two runs as row 640 (`results/VARKA-640-wide-conversion-runs.txt`, head `ed6d4ace513`
of #1625, EPYC 7763) read the opt-in `wideConversionForm=MAGIC` arm of `L2D` against the
default XOR split:

| rung | 256 bits: magic | XOR split | 128 bits: magic | XOR split |
| :--- | ---: | ---: | ---: | ---: |
| L2 | 4853.1 | 4810.3 | 40.0 | 3421.2 |
| L3 | 4320.6 | 4136.9 | 39.0 | 3014.4 |
| past L3 | 1365.8 | 1349.0 | 37.8 | 1552.7 |

At 256 bits (run 45071805478) the magic form ties the XOR split. At 128 bits (run 45071808355,
`-XX:MaxVectorSize=16`) it runs at about 40 M/s on every rung, 85 times slower than the XOR
split in L2. This is not new: master's two committed 128-bit runner files read 40.3 and 40.1
in L2 (`VarkaWideConversionBenchmark-jdk25-runner-epyc7763-128bit-results.txt` and
`VarkaWideConversionBenchmark-jdk25-runner-epyc7763-128bit-2-results.txt`), and
`VARKA-600.md` 2 item 4 records it and leaves it out of row 600 (3.2). So the collapse holds
over three runs, but no row owns it.

`VARKA-600.md` 2 item 4 names the likely cause: the form's guard is
`VarkaVectorWalk.emitRangeGuard`, a long-lane compare to a mask, and at two 64-bit lanes C2 on
x86 lowers no mask (`VARKA-503.md` 1, row 628's `-XX:+PrintIntrinsics` lines). Row 636, filed on
the unpushed branch `varka-math-port-bench`, reads the same shape in the `exp` port and
`vfdlibm`: at two `DOUBLE` lanes under `-XX:UseAVX=2` both box their vectors and run several
times slower than a scalar call. This collapse is likely the same fallback, but nothing in these
runs shows boxing.

## 2. The admission check, done

The form is a `MACHINE` choice (`VarkaEmitOption.TABLE`, `wideConversionForm`), not a default,
so no user query takes it today unless the option is set. But the option matrix and the fuzzer
draw it (`sql/varka/matrix/reach-format.tsv` names `useAVX=2,wideConversionForm=MAGIC`), and
the same guard shape may sit under other emitted forms at two long lanes. Production declines
some two-lane long masks on x86 (VARKA-503, `spark.sql.codegen.varka.declineTwoLaneConditions`),
but the benchmark compiles under emit options alone, with no session admission (row 628). So
whether a session with the option set would fuse this kernel at two lanes is unread, and step 1
reads it.

## 3. What to do

1. **Find why.** Run the benchmark's magic arm on the laptop under
   `-XX:UseAVX=2 -XX:MaxVectorSize=16` with `-XX:+PrintIntrinsics` and an allocation count
   (`skills/the-jit.md`; the way rows 628 and 636 read theirs). Record which calls are
   `not supported`, whether the vectors box, and the bytes a row. Read whether a session under
   the option admits the kernel at two lanes.
2. **Keep the compiler off it at two long lanes.** Either the option resolves to the XOR split
   where the emission lane has two long lanes, with a test that names the width, or the guard is
   written without a mask (row 600's `WINDOWED` test, `reduceLanes(OR)`, which `VARKA-600.md`
   3.2 leaves as a separate change) and measured.
3. **Look for the same guard elsewhere.** List every emitted use of `emitRangeGuard` on a long
   lane and say for each whether two lanes reach it.

## 4. Done when

The cause is recorded with its `-XX:+PrintIntrinsics` lines and allocation count. At two long
lanes the magic form is either never emitted, with a test, or measured on the EPYC 7763 at 128
bits no slower than the XOR split. The other uses of the guard are listed with a verdict each.
Row 636's answer, if it lands first, is read and reused.

## 5. Outcome

Not started.
