* task: VARKA-376 (6.1 #5)
* added: 2026-10-20
* predicted: the census suite under 10 minutes per host, forking one JVM per cell and width
* measured: 25 s on the laptop, 85 cells at three widths, parallel forks
* ratio: 0.04
* note: the bound was set from warm-ups of a few seconds a cell; a loop of one operator compiles in
  well under a second and forks run in parallel
