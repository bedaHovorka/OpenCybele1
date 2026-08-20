#!/usr/bin/env bash
#
# parity-junit.sh — read the characterizationIT JUnit XML and answer the two
# questions the CI workflow has to ask about a run. Both are asked of the XML
# rather than of Gradle's exit status, because Gradle's exit status cannot
# express either of them:
#
#   assert-ran     Did the END-TO-END scenario actually execute?
#                  `OpenCybeleSmokeIT` calls `assumeTrue(OpenCybeleLauncher
#                  .isAvailable())`, so without a usable `-Popencybele.dist`
#                  it is SKIPPED and the build is still BUILD SUCCESSFUL.
#                  Measured, on this repository, at 0dd6ab6:
#
#                      ./gradlew characterizationIT            -> BUILD SUCCESSFUL, skipped="1"
#                      ./gradlew characterizationIT -Popen...  -> BUILD SUCCESSFUL, skipped="0"
#
#                  A workflow that only looks at the exit status therefore
#                  reports green for a run in which the only test that starts
#                  the application never ran. That is worse than no workflow,
#                  so it is asserted here by name.
#
#   smoke-failed   Did the failure come from the end-to-end scenario (which
#                  drives a child JVM and is therefore susceptible to the
#                  baseline's measured nondeterminism), or from the harness'
#                  own in-process tests (which are deterministic)? Only the
#                  former is worth re-running. See docs/ci.md.
#
# Usage:
#   parity-junit.sh assert-ran   <test-results-dir>
#   parity-junit.sh smoke-failed <test-results-dir>   # exit 0 = yes, 1 = no
#
# Exit codes: 0 the answer is "yes"/"the assertion holds", 1 otherwise.
# `assert-ran` always prints a per-class table first, so a red step carries the
# numbers that produced the verdict.

set -uo pipefail

SMOKE_CLASS="cz.vutbr.fit.ags.parity.it.OpenCybeleSmokeIT"

MODE="${1:-}"
RESULTS_DIR="${2:-}"

usage() {
    echo "usage: $0 {assert-ran|smoke-failed} <test-results-dir>" >&2
    exit 2
}

[ -n "$MODE" ] && [ -n "$RESULTS_DIR" ] || usage

# Pull one attribute out of the <testsuite ...> element of one report. Gradle
# writes the whole element on a single line with double-quoted values, so this
# needs no XML parser -- and adding one would mean a dependency this step is
# supposed to be able to run before anything else is installed.
#
# The [[:space:]] before the attribute name is load-bearing: without it,
# `attr <file> name` matched `hostname="..."` and every row of the table below
# printed the runner's hostname instead of the test class.
attr() {
    local file="$1" name="$2"
    sed -n "s/.*<testsuite[^>]*[[:space:]]${name}=\"\([^\"]*\)\".*/\1/p" "$file" | head -n 1
}

suite_name() { attr "$1" name; }

# Sum one attribute over every report in the directory.
total() {
    local name="$1" sum=0 file value
    for file in "$RESULTS_DIR"/TEST-*.xml; do
        [ -f "$file" ] || continue
        value="$(attr "$file" "$name")"
        sum=$(( sum + ${value:-0} ))
    done
    echo "$sum"
}

smoke_report() {
    local file="$RESULTS_DIR/TEST-$SMOKE_CLASS.xml"
    [ -f "$file" ] && echo "$file"
}

case "$MODE" in

    # -------------------------------------------------------------------
    # smoke-failed — the retry predicate. Deliberately narrow: it is true
    # ONLY when the class that starts a child JVM failed. A failure in
    # HarnessSelfCheckIT, ScenarioCatalogIT or SmokeScenarioIT is in-process
    # and deterministic, and re-running it would only burn a runner minute
    # and blur a real regression.
    # -------------------------------------------------------------------
    smoke-failed)
        file="$(smoke_report)"
        [ -n "$file" ] || exit 1
        failures="$(attr "$file" failures)"
        errors="$(attr "$file" errors)"
        [ $(( ${failures:-0} + ${errors:-0} )) -gt 0 ]
        ;;

    # -------------------------------------------------------------------
    # assert-ran — the acceptance criterion of #25.
    # -------------------------------------------------------------------
    assert-ran)
        if [ ! -d "$RESULTS_DIR" ]; then
            echo "FAIL: no test-results directory at '$RESULTS_DIR' -- characterizationIT" \
                 "produced no reports at all, so nothing ran." >&2
            exit 1
        fi

        printf '%-52s %6s %8s %9s %7s\n' "test class" "tests" "skipped" "failures" "errors"
        found=0
        for file in "$RESULTS_DIR"/TEST-*.xml; do
            [ -f "$file" ] || continue
            found=1
            printf '%-52s %6s %8s %9s %7s\n' \
                "$(suite_name "$file")" \
                "$(attr "$file" tests)" "$(attr "$file" skipped)" \
                "$(attr "$file" failures)" "$(attr "$file" errors)"
        done
        if [ "$found" -eq 0 ]; then
            echo "FAIL: '$RESULTS_DIR' contains no TEST-*.xml reports." >&2
            exit 1
        fi
        echo

        rc=0

        # 1 -- the end-to-end scenario exists, ran, and was not assumed away.
        file="$(smoke_report)"
        if [ -z "$file" ]; then
            echo "FAIL: no report for $SMOKE_CLASS. The end-to-end scenario was not even" \
                 "discovered -- check that the characterizationIT source set compiled." >&2
            rc=1
        else
            tests="$(attr "$file" tests)"
            skipped="$(attr "$file" skipped)"
            failures="$(attr "$file" failures)"
            errors="$(attr "$file" errors)"
            if [ "${tests:-0}" -lt 1 ]; then
                echo "FAIL: $SMOKE_CLASS reports tests=\"${tests:-}\"." >&2
                rc=1
            fi
            if [ "${skipped:-0}" -ne 0 ]; then
                echo "FAIL: $SMOKE_CLASS was SKIPPED (skipped=\"$skipped\"), so the application" \
                     "was never started and this run proves nothing about the baseline." \
                     "The cause is almost always that -Popencybele.dist did not point at a" \
                     "'./gradlew installDist' output containing lib/*.jar." >&2
                rc=1
            fi
            if [ $(( ${failures:-0} + ${errors:-0} )) -ne 0 ]; then
                echo "FAIL: $SMOKE_CLASS reports failures=\"$failures\" errors=\"$errors\"." >&2
                rc=1
            fi
        fi

        # 2 -- nothing else quietly assumed itself away either. Measured at
        # 0dd6ab6 with a dist supplied: 31 tests, 0 skipped, across 5 classes.
        # Asserting the total keeps a future `assumeTrue` from hiding in a
        # class this script does not name.
        skipped_total="$(total skipped)"
        if [ "$skipped_total" -ne 0 ]; then
            echo "FAIL: $skipped_total test(s) were skipped across the suite. In this job every" \
                 "precondition is supposed to be satisfied, so a skip is a silently missing" \
                 "check, not a neutral outcome." >&2
            rc=1
        fi

        # 3 -- and nothing anywhere in the suite failed. Gradle's exit status has normally said
        # this already; asserting it here as well means this script's OK line is a statement
        # about the reports it just read rather than about one class in them.
        bad_total=$(( $(total failures) + $(total errors) ))
        if [ "$bad_total" -ne 0 ]; then
            echo "FAIL: $bad_total failure(s)/error(s) across the suite." >&2
            rc=1
        fi

        if [ "$rc" -eq 0 ]; then
            echo "OK: $(total tests) test(s), 0 skipped, 0 failed -- and $SMOKE_CLASS RAN" \
                 "(the application was started as a child JVM and compared against its golden)."
        fi
        exit "$rc"
        ;;

    *)
        usage
        ;;
esac
