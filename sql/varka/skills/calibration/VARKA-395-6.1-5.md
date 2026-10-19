* task: VARKA-395 (6.1 #5)
* added: 2026-10-19
* predicted: the pacing's waits at two cores, with C2's thread count corrected, 0.2 to 3 s
* measured: 263 to 537 ms over three JVMs, against 95 to 188 ms on master's count
* ratio: 1.3 to 2.7 of the band's floor, inside the band
* note: the end-to-end suite starts its warm-up with C2's queue at 0 to 19; a JVM with a longer
  backlog would wait toward the 2 s budget
