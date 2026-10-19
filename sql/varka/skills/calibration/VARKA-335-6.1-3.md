* task: VARKA-335 (6.1 #3)
* added: 2026-10-19
* predicted: each unary 2^32 sweep against Spark's own expression under five minutes, about one
  minute estimated
* measured: ~ and bit_count 14.2 s together, about 7 s each; each shift sweep about 16 s, every int
  with one count
* ratio: about 0.12 of the estimate
* note: a parallel stream over the laptop's cores beside other agents' JVMs, about 25 ns of CPU per
  interpreted evaluation; the estimate priced the evaluations, not the cores
