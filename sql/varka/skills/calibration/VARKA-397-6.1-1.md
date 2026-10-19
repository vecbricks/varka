* task: VARKA-397 (6.1 #1)
* added: 2026-10-19
* predicted: the new check's suite 15 to 45 s for its three tests in a fresh JVM, the spike's 26 s
  of emission plus one counted pass, no new-suite ratio applied
* measured: 27 to 28 s alone in a test JVM, the corpus test nearly all of it; 15 s after another
  Varka suite in the same JVM; 41 s with sbt's start
* ratio: inside the band, 0.6 of its top
* note: the spike had measured the emission, so the band only had to cover the JVM it would run in
