# VARKA-262: reproducers of disagreements between Varka off and on, shrunk.
# status: regression (must agree) or known <row> (must still differ). The varka-386-* file is a regression since the apache/spark sync that brought SPARK-60315 (VARKA-386.md 2.1). The varka-387-* file is a regression (VARKA-387).
# The varka-619-* file is a regression (VARKA-619): IS NOT NULL over a narrow column in a long-lane filter, read through two widenings, found when the Spark-level fuzzer first drew the narrow and boolean columns (VARKA-352.md 10.3).
