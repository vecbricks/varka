* task: VARKA-328 (6.1 #3)
* added: 2026-10-19
* predicted: the census's fused pair count moves from 96 by at most 26, fewer by each INT comparison
  that declines
* measured: 96 to 122, +26; every INT comparison fuses
* ratio: 1, at the bound
* note: the bound counted the INT comparisons as possible declines because docs/sql-varka.md said
  so; the documentation was stale since VARKA-122
