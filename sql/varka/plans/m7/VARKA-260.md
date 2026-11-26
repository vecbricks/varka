# VARKA-260: a reference post, Spark's code generator by the numbers

*Row 260 of `m7/PLAN.md`, `m10/SCOPE.md` item 80. Planned and drafted on 16 October 2026 in the
shape of `VARKA-371.md`: the question, the claim, the evidence the record already holds, the
outline, what a reader can reproduce, and done-when. It goes out between rows 258 and 259 and the
next long post. The draft is `POST_CODEGEN_NUMBERS.md`; section 9.1 lists what is owed.*

## 1. The question

The reader runs or tunes Spark and has met one of the numbers below in a log line, a setting or one
of the earlier posts. They want one page that answers, for each number that decides what Spark
compiles and how well: **what is it, what happens past it, how would I see it, and what moves it?**

## 2. The claim, and the five-minute version

Nine numbers decide what Spark's code generator produces and how well the JVM runs it - 100 fields,
1024 characters, 255 parameter slots, 8000 bytes twice, 65535 as a default, 65,535 bytes and
entries, 100 cached classes and 16 bits of fast hash map - and most are internal settings or JVM
limits that the configuration page does not show. The five-minute version is the post's one table;
the sections under it give each number its measured consequence, where the record has one.

## 3. The evidence the post cites

* **Defaults, read from Spark 4.2.0's source at tag `v4.2.0`** on 16 October 2026:
  `spark.sql.codegen.maxFields` 100 (2.0.0), `spark.sql.codegen.methodSplitThreshold` 1024 (3.0.0),
  `spark.sql.codegen.hugeMethodLimit` 65535 (2.3.0), `spark.sql.codegen.cache.maxEntries` 100,
  static (2.4.0), `spark.sql.codegen.aggregate.fastHashMap.capacityBit` 16 in 10 to 30 (2.4.0),
  `spark.sql.codegen.broadcastCleanedSourceThreshold` -1 (4.0.0, not in the post's table);
  `CodeGenerator`'s `DEFAULT_JVM_HUGE_METHOD_LIMIT` 8000, `MAX_JVM_METHOD_PARAMS_LENGTH` 255,
  `MAX_JVM_CONSTANT_POOL_SIZE` 65535 and `calculateParamLength` (two slots for a `long` or a
  `double`, one more for a nullable value); the cache as a Guava-backed `NonFateSharingCache`
  logging `Code generated in ... ms` per compile; `HashAggregateExec`'s fall-through from a failed
  fast-map probe to the regular map.
* **8000, HugeMethodLimit**: `m6/POST_SPARK.md` section 1 (48 to 52 columns, about 1,500 to about
  9,200 ns a row, 6.2 times on JDK 25, stock 4.2.0) and section 4 (`hugeMethodLimit=8000`).
* **8000, DesiredMethodLimit**: `m6/POST_GIVEUPS.md` section 2 (50 cheap columns, 1,861 bytes,
  1.16 to 1.41 times slower in a stage on three machines; `maxFields` lowered to 98) and
  `m6/POST.md` section 2.
* **100 fields**: `m6/POST_SPARK.md` section 5 (the 101-column cached table, five times and two and
  a half times) and `m6/POST_GIVEUPS.md` section 2 (150 columns under `maxFields=200`, 12 to 22
  seconds two and a half to three and a half times slower).
* **1024 characters**: `m6/POST.md` section 1 (about seven characters a byte, 235-byte methods).
* **255 and 65,535**: `m6/POST_GIVEUPS.md` sections 3 to 5 (the INFO line, the two fallbacks, an
  eighth to a fifth, 3.1 to 4.4 times, about thirty places without a fallback) and the census of
  `m6/VARKA-188.md`.
* **100 classes**: `VARKA-258.md` section 2 (the cache keyed by the source, the literals in it).

## 4. The outline

1. **One paragraph and the table**: number, what it bounds, past it, how you see it, what moves it.
2. **The two limits of 8000.**
3. **100 fields, both ways.**
4. **1024 characters.**
5. **255 and 65,535.**
6. **100 classes**, linking row 258's post.
7. **16 bits.**
8. **How these were checked**, and one link to Varka.

## 5. What a reader can reproduce

* Each default: most of these settings are internal, so the post names the source files; on a
  running session `spark.conf.get("<key>")` prints the value in force.
* Each measured consequence: the demo scripts the linked posts publish (`method_size_cliff.scala`,
  `silent-giveups/compile_wait.scala`, `silent-giveups/log_level.scala`).

## 6. Done when

The post is published with every number tracing: each default to 4.2.0's source, each measurement
to a committed results file through `dev/varka_quote_check.py` or the published post that quotes
it; kept short.

## 7. Sequencing

1. This plan and the draft, and the row marked drafted.
2. The owed measurements of 9.1, or the owner's decision to publish with the two rows as the source
   describes them.
3. Publication between rows 258 and 259 and the next long post, then 9.2.

## 8. Explicitly out of this task

* New measurements beyond the two owed rows: the post is a reference to the record.
* Varka's own numbers, apart from the closing link.

## 9. Outcome

### 9.1 The draft, 16 October 2026

`POST_CODEGEN_NUMBERS.md` is the draft, about 1300 words on 4's outline. Seven of its nine rows
cite a measurement the record holds; two do not, and are written from the source with a bracketed
note. Owed before publication:

1. **Past the cache's 100 entries**: how many queries of a second loop of 150 distinct queries
   compile again. This is row 258's measurement 5 (`VARKA-258.md` 3), and the row quotes it when
   it lands.
2. **Past the fast hash map's capacity**: the cost of an aggregate whose distinct keys pass 2^16 and
   fall through to the regular map. Not measured anywhere in the record; a stock run, or the
   owner's decision to print the row from the source alone.
3. **Row 258's published link** for the 100-classes section.
4. **The owner's edit.**

### 9.2 Published, 16 October 2026

The post went live on 16 October 2026 at https://vecbricks.github.io/nine-numbers/, after #896
replaced its two bracketed author notes with plain sentences: the cache section points to row 258's
post, which measures the cache, and the capacity section says the cost of passing the fast hash
map's 16 bits was not measured. The owner then asked for that measurement; it runs on stock Spark
4.2.0 on a GitHub runner (`sql/varka/demo/hashmap/`), and the section gains its result as an
addition to the published post, not as a condition of this row.
