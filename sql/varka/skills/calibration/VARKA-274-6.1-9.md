* task: VARKA-274 (6.1 #9)
* added: 2026-10-16
* predicted: the nightly's 4,000 kernels over eight JVMs under 30 minutes
* measured: 2,449 s for 2,000 kernels (1,000 draws a lane, the owner's default of 16 October) and
  the 92 coverage rows on the laptop nightly of 18 October, about 82 minutes scaled to 4,000; the
  extrapolation of 16 October had said about 95
* ratio: about 2.7
* note: the 30 assumed whole-machine case times; a JVM held to three cores ran a case about 2.6
  times slower
