* task: VARKA-274 (6.1 #6)
* added: 2026-10-19
* predicted: between 2% and 15% of draws unsettled at 10 s, every one trapping at
  `profile_predicate`
* measured: 1.6% on the int lane (13 of 790, 11 at `profile_predicate`) and 0.1% on the long lane (1
  of 790) on the laptop nightly of 18 October
* ratio: under 1, below the band
* note: the band came from 3 of 40 draws on a loaded machine; the nightly runs each JVM on its own
  cores
