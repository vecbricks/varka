* task: VARKA-395 (6.1 #2)
* added: 2026-10-19
* predicted: the warm-up's queue readings cost 20 to 500 us each, 10 to 100 ms summed over a warm-up
* measured: medians 8 to 22 us; summed 6 to 9 ms on four idle cores, 10 to 36 ms on two cores or a
  loaded machine, over 306 to 338 readings, the largest 6.4 ms
* ratio: 0.6 to 0.9 of the sum's floor on four idle cores; inside the band on two cores or under
  load
* note: most readings cost microseconds; a few slow ones, on a small or busy machine, carry the sum
