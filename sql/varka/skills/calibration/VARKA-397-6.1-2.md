* task: VARKA-397 (6.1 #2)
* added: 2026-10-19
* predicted: the warm-up with every kernel method in the per-round blocks within 1.5 times master's
  median, four-core end-to-end suite
* measured: median 336 ms against master's 375, three JVMs an arm alternated at load 5 to 9
* ratio: 0.9, under the bound
* note: the added calls cost microseconds a round; the longer pacing waits (133 to 210 ms against 78
  to 124) did not lift the total beyond the arms' spread
