# How a kernel can read a time zone: facts and costs

Two probes for milestone 9's time-zone design (section 2.2 of the plan that pull request #1712
opens, `sql/varka/plans/m9/PLAN.md`). Each is one Java file, run with the source launcher: no
build and no Spark.

| probe | what it answers | its output |
| :--- | :--- | :--- |
| `src/ZoneFacts.java` | What the JDK's time-zone data allows: how many changes of offset a zone has and how close they come, whether a step formula for a local time equals `java.time` under each of its three rules, when an offset stops being a whole minute, and how often a batch spans a change | `results/facts.txt` |
| `src/ZoneForms.java` | What each way of turning a batch of instants into local times costs a row, checked against `java.time` on every row | `results/forms.txt` |

    JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./run.sh facts > results/facts.txt
    JAVA_HOME=/usr/lib/jvm/java-25-openjdk-amd64 ./run.sh forms > results/forms.txt

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

## What they do not show

- The timings are the laptop's, not the runners'. Row 657 of the plan measures the forms in
  kernels on the EPYC 7763.
- They time `instant + offset` alone, not a kernel with a field behind it, and they use one zone.
- The table's two rows touch 57 and 574 of its 11,482 buckets, a few kilobytes of its 269 KB, so
  they do not show a batch spread over the whole table.
