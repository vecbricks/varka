* task: VARKA-334 (6.1 #4)
* added: 2026-10-19
* predicted: the added test time is under 20 s, about sixty coverage rows at 10 s doubled at
  VARKA-243's ratio
* measured: +23 s in the coverage differential (136 to 159 s, 332 to 421 tests) and +3.4 s in the
  small-tree series; the coverage-reading suites' ten rows unmeasured
* ratio: at least 1.3
* note: the estimate priced rows; the time is the differential's sixty CASE tests and a third Varka
  query per partition test
