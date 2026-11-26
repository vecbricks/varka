# Build and Test

Build and tests can take a long time, so prefer the narrowest relevant build or test command.

For build and test setup, including how to run tests and troubleshoot common
local failures, see `docs/building-spark.md`.

Prefer SBT over Maven for faster incremental compilation. Module names are defined in `project/SparkBuild.scala`.

Compile a single module:

    build/sbt <module>/compile

Compile test code for a single module:

    build/sbt <module>/Test/compile

Run test suites by wildcard or full class name:

    build/sbt '<module>/testOnly *MySuite'
    build/sbt '<module>/testOnly org.apache.spark.sql.MySuite'

Run test cases matching a substring:

    build/sbt '<module>/testOnly *MySuite -- -z "test name"'

Run test cases in an optional module:

    build/sbt -P<maven-profiles> '<module>/testOnly *MySuite'

For faster iteration, keep SBT open in interactive mode. Each one-shot `build/sbt <cmd>`
pays JVM startup and build-definition loading again -- roughly 9 seconds before any work
starts -- so once you expect more than a single command, keep one session open instead:

    build/sbt
    > project <module>
    > testOnly *MySuite
    > ~compile            # recompile on every save

Build the narrowest target that answers the question. `<module>/Test/compile` is much
cheaper than `package`, and compiling one module beats compiling its whole dependency
chain. Most of a Spark build is a single core running scalac, so the way to spend less
time is to compile less, not to add build flags.

## PySpark Tests

PySpark tests require building Spark with Hive support first:

    build/sbt -Phive package

Activate the virtual environment specified by the user, or default to `.venv`:

    source <venv>/bin/activate

If the default venv does not exist, create it:

    python3 -m venv .venv
    source .venv/bin/activate
    pip install --upgrade pip
    pip install --group dev

Run a single test suite:

    python/run-tests --testnames pyspark.sql.tests.arrow.test_arrow

Run a single test case:

    python/run-tests --testnames "pyspark.sql.tests.test_catalog CatalogTests.test_current_database"
