* task: VARKA-328 (6.1 #2)
* added: 2026-10-19
* predicted: VarkaCastMatrixSuite runs in under 5 s of test time
* measured: 0.15 s for the one test over 169 pairs and every form of each type, on the laptop
* ratio: about 0.03
* note: the 5 s carried the observed ratio of 2 for a new suite's setup, which a JUnit class with no
  session does not have
