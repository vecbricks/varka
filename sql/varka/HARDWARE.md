# Hardware census

What Varka's numbers were measured on, and what the machines can do. Every
benchmark run prints the same report, and this table collects it so a reader
can place a committed number on a machine and a contributor can add a machine
the project has never run on.

## How to read the columns

`dev/varka_datapath.sh` prints a machine's CPU model, its vector flags, the
JVM's `UseAVX` (or `UseSVE`) and `MaxVectorSize`, and a datapath probe: the
same integer vector loop timed at 128, 256 and 512 bits, in lane-operations per
nanosecond. Two ratios follow from it.

* **control** is 256:128 and should read about 2. It is the probe's own check
  that it measures the datapath and not something else; a control far from 2
  makes the row unusable.
* **512:256** says what a 512-bit species is worth on that machine. About 2
  means a full-width 512-bit datapath; about 1 means 512-bit operations are
  issued as two 256-bit halves; about 1.3 is what Intel's server cores read,
  which retire 256-bit integer vector operations on three ports and 512-bit
  ones on two (`VARKA-62.md` section 11).

The committed benchmark files name the machine they were measured on in their
provenance lines, so a number in a results file can be looked up here.

## The machines

| machine | where | vector flags | JVM | lane-ops/ns at 128 / 256 / 512 | control | 512:256 | source |
|---|---|---|---|---|---|---|---|
| AMD Ryzen AI 9 HX PRO 370 (Zen 5, 24 threads, 83 GiB) | the owner's laptop; every committed file unless its provenance says otherwise | full `avx512` set | `UseAVX=3`, `MaxVectorSize=64` | 68 / 136 / 156 | 2.02 | 1.14 | `VARKA-62.md` 11.9 |
| AMD EPYC 7763 (Zen 3) | GitHub-hosted runner, the pool's most common draw | `avx2`, no `avx512` | `UseAVX=2`, `MaxVectorSize=32` | see source | 2.00 | 1.00 | `VARKA-62.md` 11.9, 8 runs |
| AMD EPYC 9V74 (Zen 4) | GitHub-hosted runner | no `avx512` exposed | `UseAVX=2`, `MaxVectorSize=32` | see source | 2.00 | 0.91 to 1.00 | `VARKA-62.md` 11.9, 3 runs |
| Intel Xeon Platinum 8573C | GitHub-hosted runner | full `avx512` set | `UseAVX=3`, `MaxVectorSize=64` | see source | 2.00 to 2.03 | 1.34 to 1.36 | `VARKA-62.md` 11.9, 4 runs |
| Intel Xeon 6973P-C | GitHub-hosted runner | full `avx512` set | `UseAVX=3`, `MaxVectorSize=64` | see source | 2.00 to 2.01 | 1.33 to 1.35 | `VARKA-62.md` 11.9, 2 runs |
| AMD EPYC 9V45 (Zen 5) | GitHub-hosted runner, one draw in eighteen | full `avx512` set | `UseAVX=3`, `MaxVectorSize=64` | see source | 2.04 | 1.99 | `VARKA-62.md` 11.9, 1 run |
| Arm Neoverse N2 | GitHub-hosted `ubuntu-24.04-arm` runner | `asimd sve sve2` | `UseSVE=2`, `MaxVectorSize=16` | not measured | - | - | `VARKA-153.md` section 6 (the width census, not the datapath probe) |

The laptop's 512:256 ratio of 1.14 is the number behind the README's remark
that this machine's AVX-512 is 256 bits wide; the one runner class that reads
about 2 is the Zen 5 EPYC, which is why the full-width surface numbers wait for
it (`VARKA-62.md` section 11).

## Adding a machine

Run the probe on a quiet machine and paste its output into a new issue using
the "Add my machine" template:

    dev/varka_datapath.sh

It needs a JDK 25 on the path and nothing built. A few minutes on hardware the
table lacks - an AMD AVX2 desktop, an Apple or Graviton Arm core, a NEON-only
board - is a contribution in its own right: the width census
(`sql/varka/width_audit.json`) and the committed benchmark tables are all from
the rows above, and the gaps are exactly the machines most readers have.
