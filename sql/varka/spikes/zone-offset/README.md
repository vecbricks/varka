# How a kernel can read a time zone: facts and costs

Four probes for milestone 9's time-zone design (section 2.2 of `sql/varka/plans/m9/PLAN.md`, and
row 657's plan, `sql/varka/plans/m9/VARKA-657.md`). Each is one Java file, run with the source
launcher: no build and no Spark.

| probe | what it answers | its output |
| :--- | :--- | :--- |
| `src/ZoneFacts.java` | What the JDK's time-zone data allows: how many changes of offset a zone has and how close they come, whether a step formula for a local time equals `java.time` under each of its three rules, when an offset stops being a whole minute, and how often a batch spans a change | `results/facts.txt` |
| `src/ZoneForms.java` | What each way of turning a batch of instants into local times costs a row, checked against `java.time` on every row | `results/forms.txt` |
| `src/ZoneKernels.java` | What a whole field over a zoned instant costs a row, and the way back, a local time to an instant, under the earlier-offset rule and the kept-offset rule, checked against `java.time` on every row | `results/kernels.txt` |
| `src/ZoneResolve.java` | Whether one pair of local thresholds a change serves all three of `java.time`'s rules for a local time as formulas, the gap's-end rule among them, to the year 2400 | `results/resolve.txt` |

    JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./run.sh facts > results/facts.txt
    JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./run.sh forms > results/forms.txt
    JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./run.sh kernels > results/kernels.txt
    JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./run.sh resolve > results/resolve.txt

`run.sh forms` runs three times: at the host's vector width, with AVX2 at 256 bits (the runners'
AMD EPYC 7763 has no AVX-512), and with AVX2 at 128 bits. It takes about eleven minutes on an
idle x86 machine. The script fails when a run fails or when a probe's own check does.

## What they found

On 25 November 2026, with JDK 25, time-zone data 2026f and an AMD Ryzen AI 9 HX PRO 370:

- **The step formula is `java.time`.** With `LT = T + max(offset before, offset after)` for each
  change at instant `T`, a local time's offset is the one after the last `LT` at or before it.
  That equals `LocalDateTime.atZone` at every one of 257,568,575 local times around the 65,794
  changes of the 604 zone ids. With one exception inside an overlap, where the source's offset
  wins if it is one of the two, it equals `ZonedDateTime.ofLocal` at every one of 237,632,993
  local times inside a gap or an overlap, each with up to three kept offsets: 712,898,979 pairs.
  Midnight is strictly inside a gap on one day, 31 March 1919, in six zone ids.
- **Changes are sparse.** The closest two of one zone are 601,200 s apart, and no zone has more
  than 5 in 365 days or 42 in ten years. 349 distinct sets of rules serve the 604 ids, and 397
  ids have no change after 2025.
- **One offset a batch declines instants in no order.** In `America/Los_Angeles`, 1000 of 1000
  batches of a year of such instants span a change; in time order, 2 of 1000 do.
- **Steps written out are cheap.** At four lanes, two steps cost 0.15 ns a row and eight cost
  0.76, where `java.time` pays about 10 for the offset and about 26 for the hour of the day.
  The same steps read in a loop cost twice as much at eight changes.
- **A table costs the same at any number of changes:** 0.88 to 0.95 ns a row at four lanes. So
  it wins past about nine changes a batch there, and past about twenty at eight lanes.
- **The range check is not free.** Checked by one OR a vector in the same loop, two steps cost
  0.23 ns a row at four lanes against 0.15 unchecked, each the minimum of two runs taken in
  turn. A separate pass for the least and greatest instant costs 0.23 on its own. At eight lanes
  the check costs 0.01.
- **At two 64-bit lanes the masked forms are not compiled:** 7 to 63 ns a row, and the table 39.
  The step written as a subtract, a shift and an AND costs 0.69 there with its check.

The third probe, for row 657's plan (`sql/varka/plans/m9/VARKA-657.md` 2.3), on the same machine
and day:

- **A whole `hour` kernel costs 0.7 ns a row at four lanes** where `java.time` pays 25: the two
  steps are 0.23 of it. With no mask the same kernel costs 0.9.
- **The way back is cheaper still, and the row path's is dearer.** A local time to an instant
  under the earlier-offset rule costs 0.14 ns a row at four lanes where `LocalDateTime.atZone`
  pays 42, and under the kept-offset rule 0.36 where `ZonedDateTime.ofLocal` pays 43.
- **At two lanes the masked kept-offset form does not beat `java.time`** (44 against 43 ns a
  row); the masked `hour` does (16 against 25), and with no mask it costs 1.6.

The fourth probe, for the same plan's 2.4:

- **One pair of thresholds a change serves the three rules.** With `lo = T + min(offset before,
  offset after)` and `hi = T + max(...)` for each change, the earlier-offset rule, the kept-offset
  rule and the gap's-end rule are each one formula over the count of `hi` at or before the local
  time. Against `LocalDateTime.atZone`, `ZonedDateTime.ofLocal` and `LocalDate.atStartOfDay` in
  the 604 zone ids, at each change to the year 2400 and at 3000 random instants a zone:
  18,399,068 checks and no mismatch. The earlier-offset rule and the gap's-end rule differ at six
  midnights, 31 March 1919 in six ids.

## What they do not show

- The timings are the laptop's, not the runners'. Row 691 of the plan measures the forms in
  kernels on the EPYC 7763.
- The two timing probes use one zone, `America/Los_Angeles`. `ZoneForms` times
  `instant + offset` alone. `ZoneKernels` times one field, the hour, as a quotient in doubles
  that holds on its rows, the local times of 2025, and not on every long.
- The table's two rows touch 57 and 574 of its 11,482 buckets, a few kilobytes of its 269 KB, so
  they do not show a batch spread over the whole table.
