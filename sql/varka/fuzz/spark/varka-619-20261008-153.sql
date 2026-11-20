-- VARKA-262 reproducer
-- status: regression
-- note: seed 20261008 iteration 153 (VARKA-352 10.3, VARKA-619): IS NOT NULL over a TINYINT column in a long-lane filter is compiled as isNotNull(widen:LONG(widen:INT col:byte)); the emitter reads the column's validity word through the whole chain of widenings, so the kernel emits and agrees with the row engine
-- ansi: true
-- kind: unexpected error condition INTERNAL_ERROR from Varka under ANSI
-- select: CASE WHEN l < l2 THEN l ELSE l2 END AS c0
-- where: NOT (coalesce(b, b2)) OR (y1 IS NOT NULL) OR (dt IN (INTERVAL '2 00:00:00' DAY TO SECOND, INTERVAL '1 00:00:00' DAY TO SECOND))
-- fixture
SELECT d, d2, i,
       CAST(m AS INTERVAL MONTH) AS ymm,
       CASE WHEN 12L * y BETWEEN -2147483648L AND 2147483647L
            THEN CAST(y AS INTERVAL YEAR) END AS ymy,
       CASE WHEN 12L * y + mm BETWEEN -2147483648L AND 2147483647L
            THEN make_ym_interval(y, mm) END AS ym,
       l, l2, CAST(t AS TIME(6)) AS t, CAST(t2 AS TIME(6)) AS t2, dt, dt2,
       y1, y2, s1, s2, b, b2,
       CAST(d AS TIMESTAMP_NTZ) + (t - TIME'00:00:00') AS ntz,
       CAST(d2 AS TIMESTAMP_NTZ) + (t2 - TIME'00:00:00') AS ntz2,
       element_at(array(CAST('NaN' AS DOUBLE), -0.0D, 0.0D, CAST('Infinity' AS DOUBLE), CAST('-Infinity' AS DOUBLE), 1.5D, -2.5D, -CAST('NaN' AS DOUBLE), 1.7976931348623157E308D, 4.9E-324D), (CAST(pmod(l, 10) AS INT)) + 1) AS x,
       element_at(array(CAST('NaN' AS DOUBLE), 0.0D, -0.0D, CAST('-Infinity' AS DOUBLE), CAST('NaN' AS DOUBLE), 1.5D, 1.5D, CAST('NaN' AS DOUBLE), -0.0D, -1.7976931348623157E308D), (CAST(pmod(l2, 10) AS INT)) + 1) AS x2,
       element_at(array(CAST('NaN' AS FLOAT), CAST(-0.0D AS FLOAT), CAST(0.0D AS FLOAT), CAST('Infinity' AS FLOAT), CAST('-Infinity' AS FLOAT), CAST(1.5D AS FLOAT), CAST(-2.5D AS FLOAT), -CAST('NaN' AS FLOAT), CAST(3.4028235E38D AS FLOAT), CAST(1.4E-45D AS FLOAT)), (pmod(i, 10)) + 1) AS f,
       element_at(array(CAST('NaN' AS FLOAT), CAST(0.0D AS FLOAT), CAST(-0.0D AS FLOAT), CAST('-Infinity' AS FLOAT), CAST('NaN' AS FLOAT), CAST(1.5D AS FLOAT), CAST(1.5D AS FLOAT), CAST('NaN' AS FLOAT), CAST(-0.0D AS FLOAT), CAST(-3.4028235E38D AS FLOAT)), (pmod(unix_date(d2), 10)) + 1) AS f2
FROM VALUES (DATE'2024-01-31', DATE'2021-01-01', CAST(NULL AS INT), 43, 3, CAST(NULL AS INT), CAST(NULL AS BIGINT), CAST('12345678901234' AS BIGINT), TIME'12:34:56.789', TIME'18:00:00', INTERVAL '-100000 00:00:00' DAY TO SECOND, INTERVAL '-3 00:00:00.000001' DAY TO SECOND, CAST(NULL AS TINYINT), CAST(2 AS TINYINT), CAST(-100 AS SMALLINT), CAST(-42 AS SMALLINT), TRUE, CAST(NULL AS BOOLEAN))
AS v(d, d2, i, m, y, mm, l, l2, t, t2, dt, dt2, y1, y2, s1, s2, b, b2)
-- query
SELECT CASE WHEN l < l2 THEN l ELSE l2 END AS c0 FROM fz AS fz WHERE NOT (coalesce(b, b2)) OR (y1 IS NOT NULL) OR (dt IN (INTERVAL '2 00:00:00' DAY TO SECOND, INTERVAL '1 00:00:00' DAY TO SECOND))
