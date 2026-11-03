* task: VARKA-365 (6.1 #2)
* added: 2026-11-01
* predicted: the three-path suite adds under 30 s to the sql/core Varka tests, at VARKA-243's ratio
  of about 2
* measured: nine tests in 5 s, the suite 8.4 s in a warm sql JVM
* ratio: about 0.3
* note: one-row cached views, each cached once; the paths grew from three to nine and still cost
  seconds
