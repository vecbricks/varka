* task: VARKA-333 (6.1 #3)
* added: 2026-11-01
* predicted: the coverage differential's added test time under 20 s for some seventy rows, 10 s
  doubled by VARKA-243's ratio
* measured: 2.7 s for 43 rows: 1.6 s over their 105 tests in the coverage harness and 1.1 s for the
  two family tests in the differential suite
* ratio: 0.14
* note: since VARKA-509 a row's per-batch checks run in the session-free harness, about 15 ms a
  test, and the differential runs one row a family, so the estimate priced a session query per row
  that no longer exists
