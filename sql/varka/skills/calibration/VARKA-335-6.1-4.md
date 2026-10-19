* task: VARKA-335 (6.1 #4)
* added: 2026-10-19
* predicted: the two bitmap claims add under 5 s under Z3
* measured: 0.03 s under Z3 and 0.07 s under cvc5 for the file's six checks
* ratio: under 0.01
* note: linear claims by constants; cvc5 needed the ALL logic, under QF_NIA it answered unknown at
  the 10 s limit
