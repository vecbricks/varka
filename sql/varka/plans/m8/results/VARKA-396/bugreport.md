# JDK bug report, ready to paste into https://bugreport.java.com/bugreport/

*Prepared 24 October 2026 from row 396's investigation (`../VARKA-396-report.md`), and submitted
by the owner the same day: reference `efdbbe29-dfaf-419b-a3c2-084dfb421bc5`.* Each section below is one field of the form; paste the text under its
heading.

## Category

Java SE / HotSpot Virtual Machine / Compiler (C2)

## Release / Java version

JDK 25 (reproduced on 25 GA, 25.0.4, 25.0.4.1 and 25.0.5)

## Operating system

Linux x86_64 (any x86_64 OS: the bug is in C2's platform-independent code, reached on targets
without AVX-512 mask registers)

## Synopsis

C2: VectorMask.fromLong(s, m.toLong()) gives wrong lanes on x86 without AVX-512 (UseAVX=2)

## Description

On x86 targets without AVX-512 mask registers (UseAVX=2), C2 miscompiles the Vector API round
trip `VectorMask.fromLong(species, mask.toLong())`: once the method is C2-compiled, the resulting
mask treats every true lane as false. A `blend` by that mask then takes the wrong input on every
true lane, and the mask stored with `intoArray(boolean[], ...)` holds a non-canonical boolean
(prints `true`, compares unequal to `true`).

Root cause, as far as we can tell from the JDK 25 source: `VectorMask.fromLong` masks its argument
to the lane count in Java before its intrinsic, so the round trip reaches C2 as

    VectorLongToMask (AndL (VectorMaskToLong (VectorStoreMask m)) (2^vlen - 1))

and `VectorLongToMaskNode::Ideal` (src/hotspot/share/opto/vectornode.cpp) folds it into a
`VectorMaskCast`. On a target whose masks are vectors (`dst_type->isa_vectmask() == nullptr`) it
first peels off the `VectorStoreMask` and casts the mask under it:

    Node* src = in(1)->in(1)->in(1);
    if (dst_type->isa_vectmask() == nullptr) {
      if (src->Opcode() != Op_VectorStoreMask) {
        return nullptr;
      }
      src = src->in(1);
    }
    ...
    return new VectorMaskCastNode(src, dst_type);

There `VectorLongToMask` produces a vector of canonical booleans (0/1 per byte), which the
`VectorLoadMask` that `fromBitsCoerced` emits after it widens. The cast keeps the mask encoding
(0/-1) instead, so a true lane arrives as 0xFF, `VectorLoadMask` turns it into 1 rather than -1,
and `vblendvpd`, which reads the sign bit, treats the lane as false.

With AVX-512 (UseAVX=3) the rule takes its mask-register branch and the result is correct. The
same program is correct in the interpreter and with C1.

`openjdk/jdk25u` master still contains this code. OpenJDK mainline has rewritten the rule (it
casts the `VectorMaskToLong`'s input itself, which on these targets is the `VectorStoreMask`, a
boolean vector), which appears to be part of JDK-8370863 (fix version 27); we have not confirmed
that change was meant to fix this case. A fix or backport for JDK 25 updates would be appreciated:
JDK 25 is the LTS release our users deploy on.

A local fix we tested (fastdebug JDK 25 build): add `VectorLongToMaskNode::Identity` returning the
`VectorStoreMask` itself when `dst_type->isa_vectmask() == nullptr` and the pattern above matches
with equal lengths and element types, and drop `Ideal`'s peeling branch. With it the test case
below passes under UseAVX=2 and no assertion fires.

## System / OS / Java runtime information

    openjdk version "25.0.4.1" 2026-08-18
    OpenJDK Runtime Environment (build 25.0.4.1+1-1-26.04.4-Ubuntu)
    OpenJDK 64-Bit Server VM (build 25.0.4.1+1-1-26.04.4-Ubuntu, mixed mode, sharing)

Linux 7.0.0, x86_64. Reproduced on an AMD Ryzen AI 9 HX PRO 370 (AVX-512) with -XX:UseAVX=2, and
on GitHub-hosted runners with an AMD EPYC 7763 (AVX2 only, UseAVX=2 by default) under Azul Zulu
25.0.4+7 and 25.0.5+8. Also reproduced on a fastdebug build of the jdk-25-ga tag (no assertion
fires).

## Steps to reproduce

    javac --add-modules jdk.incubator.vector FromLongRoundTrip.java
    java --add-modules jdk.incubator.vector -XX:UseAVX=2 FromLongRoundTrip
    java --add-modules jdk.incubator.vector FromLongRoundTrip     # on an AVX-512 machine: passes

## Expected result

    no difference in 200000 calls

The blend picks `a[i]` wherever `a[i] > b[i]`, in every call.

## Actual result

    first wrong at call 18367, row 0: want 4460076517051 got -32177637661844

From the call at which C2 compiles `roundTrip` (about call 16,000 to 40,000 depending on the
machine) every call is wrong: every row whose condition is true gets `b[i] + c[i]`.

## Source code for an executable test case

    import jdk.incubator.vector.LongVector;
    import jdk.incubator.vector.VectorMask;
    import jdk.incubator.vector.VectorOperators;
    import jdk.incubator.vector.VectorSpecies;

    public class FromLongRoundTrip {
      static final VectorSpecies<Long> S = LongVector.SPECIES_256;

      // blend(fromLong(toLong(a > b))): the mask goes to a long and straight back.
      static void roundTrip(long[] a, long[] b, long[] c, long[] out) {
        for (int i = 0; i < S.loopBound(a.length); i += S.length()) {
          LongVector va = LongVector.fromArray(S, a, i);
          LongVector vb = LongVector.fromArray(S, b, i);
          long m = va.compare(VectorOperators.GT, vb).toLong();
          LongVector s = vb.add(LongVector.fromArray(S, c, i));
          s.blend(va, VectorMask.fromLong(S, m)).intoArray(out, i);
        }
      }

      public static void main(String[] args) {
        long[] a = {4460076517051L, 0, 18898746763276L, 0, -50266100486726L, 7, 9, -49607829850524L};
        long[] b = {4421922265370L, 35258963307884L, -59385115539699L, 0, 38382192361867L, 5, 1, 0};
        long[] c = {-36599559927214L, 0, -25562645782204L, 0, -41690266522730L, 3, 4, 41053864906336L};
        long[] out = new long[a.length];
        int calls = 200_000;
        for (int call = 0; call < calls; call++) {
          roundTrip(a, b, c, out);
          for (int i = 0; i < a.length; i++) {
            long want = a[i] > b[i] ? a[i] : b[i] + c[i];
            if (out[i] != want) {
              System.out.printf("first wrong at call %d, row %d: want %d got %d%n",
                  call, i, want, out[i]);
              System.exit(1);
            }
          }
        }
        System.out.println("no difference in " + calls + " calls");
      }
    }

## Workaround

Avoid `VectorMask.fromLong` of a value that C2 can reduce to a mask's `toLong()` on targets
without mask registers - for example, build the mask with a broadcast, an AND with the lane bits
and a compare against zero. JVM-wide flags that hide the bug in our tests
(-XX:-EliminateAllocations, -XX:-DoEscapeAnalysis, -XX:-IncrementalInline) are too costly to be a
workaround. Running with AVX-512 (UseAVX=3) avoids it.

## Frequency

Always, once the method is C2-compiled, on targets without AVX-512 mask registers.

## Outcome

Filed 24 October 2026; it became JDK-8395878, closed on 27 October 2026 as a duplicate of
JDK-8378968 ("C2 VectorAVX AVX2: wrong result, bad optimization in VectorLongToMaskNode::Ideal"),
fixed in JDK 27. `m10/SCOPE.md` item 114 follows the 25u backport.
