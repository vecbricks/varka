# VARKA-558: The buf and UI jobs run only when their files change

## 1. Where this came from

The plan-only pull request #1316 ran "Protobuf breaking change detection and Python CodeGen check"
and "Run Spark UI tests". Row 89 (#89, 3 September 2026) gave both jobs a file gate in the
precondition of `.github/workflows/build_and_test.yml`: `buf=false` unless a `.proto` or `_pb2`
file changes, `ui=false` unless `ui-test/` or a static UI resource does. Neither gate has ever
held.

## 2. The admission check, done

Spark's precondition sets `buf=true` and `ui=true` (with `docs=true` and `java25=true`) at the end
of the same branch, unconditionally (Spark's lines of 2024). #89 put the gates above those lines,
so the defaults ran after the gates and undid them. #89's own version of the file already had the
order wrong, and the jobs ran on #1311, #1313 and #1316 alike. Each Build spends two runners on
them, about two minutes for buf and one for the UI suite, in parallel with the rest, so they cost
slots on the fork's 40, not wall time.

## 3. The design

- **The defaults move above their gates.** `buf=true` and `ui=true` are set just before the buf
  gate, and the later block keeps only `docs=true` and `java25=true`. The other gates (SparkR,
  Kubernetes, OIDC) have no later default and are unchanged.
- **A lint rule keeps the order.** `ci-gates` in `dev/varka_lint.py`, run by the pre-commit hook
  and by the Build's lint self-test, reports a `x=true` at a gate's own depth, later in the same
  branch, after that gate's `x=false`. Against master's file it reports lines 203 and 204; against
  this change it reports nothing. Two self-test cases cover a gate undone and a default set first.

## 4. Done when

The rule and its self-tests pass, and the next pull request that touches neither protobuf nor UI
files skips both jobs in its Build.

## 5. Outcome

Built 3 November 2026 as one pull request.
