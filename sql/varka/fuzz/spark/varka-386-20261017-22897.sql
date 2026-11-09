-- VARKA-262 reproducer
-- status: regression
-- note: seed 20261017 iteration 22897, fixed upstream by SPARK-60315 (apache/spark#59573): whole-stage codegen evaluates the conjuncts after a NULL one, so Spark's default raises as Varka does
-- ansi: false
-- kind: only Varka throws: INTERVAL_ARITHMETIC_OVERFLOW.WITH_SUGGESTION
-- select: t + INTERVAL '0 00:00:00' DAY TO SECOND AS c0
-- where: NOT (year(d) = 2021 AND i > 0) AND ((ymy <= (INTERVAL '1' YEAR - ym)))
-- pivot: (DATE'2023-12-27', DATE'2024-02-29', 12, 84, 0, 5, CAST('-42' AS BIGINT), CAST('-1' AS BIGINT), CAST(NULL AS TIME(6)), CAST(NULL AS TIME(6)), CAST(NULL AS INTERVAL DAY TO SECOND), INTERVAL '7 12:00:00' DAY TO SECOND)
-- fixture
SELECT d, d2, i,
       CAST(m AS INTERVAL MONTH) AS ymm,
       CASE WHEN 12L * y BETWEEN -2147483648L AND 2147483647L
            THEN CAST(y AS INTERVAL YEAR) END AS ymy,
       CASE WHEN 12L * y + mm BETWEEN -2147483648L AND 2147483647L
            THEN make_ym_interval(y, mm) END AS ym,
       l, l2, CAST(t AS TIME(6)) AS t, CAST(t2 AS TIME(6)) AS t2, dt, dt2
FROM VALUES (DATE'2021-03-15', CAST(NULL AS DATE), CAST(NULL AS INT), -2147483648, -178956970, 0, CAST('-9223372036854775808' AS BIGINT), CAST('1' AS BIGINT), TIME'18:00:00', CAST(NULL AS TIME(6)), INTERVAL '100000 00:00:00' DAY TO SECOND, INTERVAL '-100000 00:00:00' DAY TO SECOND),
  (DATE'2023-12-27', DATE'2024-02-29', 12, 84, 0, 5, CAST('-42' AS BIGINT), CAST('-1' AS BIGINT), CAST(NULL AS TIME(6)), CAST(NULL AS TIME(6)), CAST(NULL AS INTERVAL DAY TO SECOND), INTERVAL '7 12:00:00' DAY TO SECOND)
AS v(d, d2, i, m, y, mm, l, l2, t, t2, dt, dt2)
-- query
SELECT t + INTERVAL '0 00:00:00' DAY TO SECOND AS c0 FROM fz WHERE NOT (year(d) = 2021 AND i > 0) AND ((ymy <= (INTERVAL '1' YEAR - ym)))
