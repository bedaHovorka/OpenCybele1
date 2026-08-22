#!/usr/bin/env bash
#
# parity-junit.sh — read a lane's JUnit XML and answer the questions the CI
# workflow has to ask about a run. They are asked of the XML rather than of
# Gradle's exit status, because Gradle's exit status cannot express any of them:
#
#   assert-ran      Did the END-TO-END scenario actually execute?
#                   `OpenCybeleSmokeIT` calls `assumeTrue(OpenCybeleLauncher
#                   .isAvailable())`, so without a usable `-Popencybele.dist`
#                   it is SKIPPED and the build is still BUILD SUCCESSFUL.
#                   Measured, on this repository, at 0dd6ab6:
#
#                       ./gradlew characterizationIT            -> BUILD SUCCESSFUL, skipped="1"
#                       ./gradlew characterizationIT -Popen...  -> BUILD SUCCESSFUL, skipped="0"
#
#                   A workflow that only looks at the exit status therefore
#                   reports green for a run in which the only test that starts
#                   the application never ran. That is worse than no workflow,
#                   so it is asserted here by name.
#
#   smoke-failed    Did the failure come from the end-to-end scenario (which
#                   drives a child JVM and is therefore susceptible to the
#                   baseline's measured nondeterminism), or from the harness'
#                   own in-process tests (which are deterministic)? Only the
#                   former is worth re-running. See docs/ci.md.
#
#   assert-suite    The same "a green exit is not a good run" lock, for a plain
#                   test lane (`test`, `integrationTest`). A Gradle Test task
#                   that discovers ZERO tests exits 0, and one whose filter
#                   silently narrowed writes a handful of reports and exits 0
#                   too. Neither is visible in the exit status.
#
#   jade-status     The L3 JADE lane's judge (#40). Its design note is the block
#                   above `jade-status)` below; the short version is that this
#                   lane cannot honestly be judged by "all five green" and is
#                   judged against a RECORDED MEASUREMENT instead.
#
#   jade-retryable  The JADE lane's retry predicate, in the same relation to
#                   `jade-status` that `smoke-failed` has to `assert-ran`.
#
# Usage:
#   parity-junit.sh assert-ran      <test-results-dir>
#   parity-junit.sh smoke-failed    <test-results-dir>                  # exit 0 = yes
#   parity-junit.sh assert-suite    <test-results-dir>
#   parity-junit.sh jade-status     <test-results-dir> <expected-file>
#   parity-junit.sh jade-retryable  <test-results-dir> <expected-file>  # exit 0 = yes
#
# Exit codes: 0 the answer is "yes"/"the assertion holds", 1 otherwise.
# `assert-ran`, `assert-suite` and `jade-status` always print a per-class table
# first, so a red step carries the numbers that produced the verdict.
#
# ---------------------------------------------------------------------------
# PER-JOB CONFIGURATION, and why it is per-job rather than a second hardcoded
# constant.
#
# #78 fixed a stale zero-skip rule that had made this job red on every push in
# the project's history. It fixed it by EXEMPTING ONE CLASS BY NAME AND BY
# COUNT: `ParityGateIT` is allowed to skip only because ALL of it is opt-in, and
# a `ParityGateIT` that skipped 3 of its 5 still fails, because then one of them
# found what it needed and ran.
#
# That bar is kept and the constant is generalised rather than duplicated. Two
# facts forced it:
#
#   * Run 32548100423 (`jade-develop`, 2026-08-22) went red with "FAIL: 5
#     test(s) were skipped outside ParityGateIT". The five were `JadeParityIT` —
#     #36's JADE scenarios, which `assumeTrue` away because the opencybele job
#     supplies no `-Pjade.dist`. The rule was working, not misfiring.
#   * The mirror image is structural rather than incidental: the JADE job
#     supplies no `-Popencybele.dist`, so the six `OpenCybele*` scenario classes
#     skip there. Each job's own scenarios are the other job's expected skips.
#
# So the list of classes-expected-to-skip-ENTIRELY is a per-job input, and #52's
# `@jason` lane adds its own without touching this file:
#
#   PARITY_OPT_IN_CLASSES   space-separated FQCNs. Each, IF PRESENT, must report
#                           skipped == tests; any skip outside the list fails.
#                           Default: ParityGateIT — i.e. #78's behaviour exactly
#                           for a caller that sets nothing.
#   PARITY_REQUIRED_CLASS   the class `assert-ran`/`smoke-failed` require to have
#                           RUN, and the only class `jade-status` tolerates
#                           failures in. Default: OpenCybeleSmokeIT.
#   PARITY_MIN_TESTS        floor on the lane's total test count for
#                           `assert-suite`. A FLOOR, not a pin: adding tests
#                           never breaks it, losing them does. Default 1.
# ---------------------------------------------------------------------------

set -uo pipefail

DEFAULT_REQUIRED_CLASS="cz.vutbr.fit.ags.parity.it.OpenCybeleSmokeIT"
DEFAULT_OPT_IN_CLASSES="cz.vutbr.fit.ags.parity.it.ParityGateIT"

REQUIRED_CLASS="${PARITY_REQUIRED_CLASS:-$DEFAULT_REQUIRED_CLASS}"
OPT_IN_CLASSES="${PARITY_OPT_IN_CLASSES-$DEFAULT_OPT_IN_CLASSES}"
MIN_TESTS="${PARITY_MIN_TESTS:-1}"

OPT_IN_SKIPPED=0
skip_rc=0

MODE="${1:-}"
RESULTS_DIR="${2:-}"
EXPECTED_FILE="${3:-}"

usage() {
    echo "usage: $0 {assert-ran|smoke-failed|assert-suite} <test-results-dir>" >&2
    echo "       $0 {jade-status|jade-retryable} <test-results-dir> <expected-file>" >&2
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

# Is this a non-negative integer? Every count read out of a report goes through
# here before it is compared.
#
# WHY, given that Gradle's XML writer always emits integers: without it, every
# check in `assert-ran` failed OPEN. The checks have the shape
# `if <comparison>; then rc=1; fi`, and `[ one -lt 1 ]` does not evaluate false
# -- it errors and returns 2, so the branch is not taken and an assertion that
# could not be evaluated read as "the assertion holds". Measured: a report with
# tests="one" skipped="zero" printed four `integer expected` errors to stderr
# and still exited 0 with the OK line. This script exists to be a lock that
# fails CLOSED, so an unreadable count is now a failure, not a pass.
num() {
    case "${1:-}" in
        '' | *[!0-9]*) return 1 ;;
        *)             return 0 ;;
    esac
}

# Sum one attribute over every report in the directory. Returns non-zero and
# prints nothing on the first unreadable value, for the reason above -- callers
# must treat "could not total" as a failure rather than as zero.
total() {
    local name="$1" sum=0 file value
    for file in "$RESULTS_DIR"/TEST-*.xml; do
        [ -f "$file" ] || continue
        value="$(attr "$file" "$name")"
        if ! num "$value"; then
            echo "FAIL: $(basename "$file") carries a non-numeric ${name}=\"${value}\"." >&2
            return 1
        fi
        sum=$(( sum + value ))
    done
    echo "$sum"
}

# Sum one attribute over every report EXCEPT the required class's. Used by
# `jade-status`, which tolerates a recorded verdict from exactly one class and
# from no other.
total_excluding_required() {
    local name="$1" sum=0 file value
    for file in "$RESULTS_DIR"/TEST-*.xml; do
        [ -f "$file" ] || continue
        [ "$file" = "$RESULTS_DIR/TEST-$REQUIRED_CLASS.xml" ] && continue
        value="$(attr "$file" "$name")"
        if ! num "$value"; then
            echo "FAIL: $(basename "$file") carries a non-numeric ${name}=\"${value}\"." >&2
            return 1
        fi
        sum=$(( sum + value ))
    done
    echo "$sum"
}

required_report() {
    local file="$RESULTS_DIR/TEST-$REQUIRED_CLASS.xml"
    [ -f "$file" ] && echo "$file"
}

# The per-class table every asserting mode prints before its verdict.
print_table() {
    local file found=0
    if [ ! -d "$RESULTS_DIR" ]; then
        echo "FAIL: no test-results directory at '$RESULTS_DIR' -- the task produced no reports" \
             "at all, so nothing ran." >&2
        return 1
    fi
    printf '%-52s %6s %8s %9s %7s\n' "test class" "tests" "skipped" "failures" "errors"
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
        return 1
    fi
    echo
    return 0
}

# The zero-skip rule -- #78's, over a per-job list instead of one constant.
# Sets $skip_rc and $OPT_IN_SKIPPED.
#
# Each named class, IF PRESENT, must be skipped ENTIRELY. "All of it is opt-in
# in this job" is the only thing that earns an exemption: a class that skipped
# some of its tests and ran the rest found what it needed for some of them,
# which is a different situation and stays a failure.
check_skips() {
    local cls file skipped tests opt_in_total=0 skipped_total unexpected
    skip_rc=0
    for cls in $OPT_IN_CLASSES; do
        file="$RESULTS_DIR/TEST-$cls.xml"
        [ -f "$file" ] || continue
        skipped="$(attr "$file" skipped)"
        tests="$(attr "$file" tests)"
        if ! num "$skipped" || ! num "$tests"; then
            echo "FAIL: $cls carries a non-numeric skipped=\"$skipped\" tests=\"$tests\"." >&2
            skip_rc=1
            continue
        fi
        if [ "$skipped" -ne "$tests" ]; then
            echo "FAIL: $cls is exempt from the zero-skip rule only because ALL of its tests are" \
                 "opt-in in this job. It reports skipped=\"$skipped\" of tests=\"$tests\", so some" \
                 "of it RAN and some did not. Decide which, rather than exempting the class" \
                 "wholesale." >&2
            skip_rc=1
        fi
        opt_in_total=$(( opt_in_total + skipped ))
    done

    if skipped_total="$(total skipped)"; then
        unexpected=$(( skipped_total - opt_in_total ))
        if [ "$unexpected" -ne 0 ]; then
            echo "FAIL: $unexpected test(s) were skipped outside the classes THIS JOB expects to" \
                 "skip entirely ($skipped_total total, $opt_in_total of them accounted for)." \
                 "Expected-to-skip classes here: ${OPT_IN_CLASSES:-<none>}. In this job every" \
                 "other precondition is supposed to be satisfied, so a skip is a silently missing" \
                 "check, not a neutral outcome." >&2
            skip_rc=1
        fi
    else
        echo "FAIL: the suite's skipped counts could not be read (see above)." >&2
        skip_rc=1
    fi
    OPT_IN_SKIPPED="$opt_in_total"
}

# ---------------------------------------------------------------------------
# The per-scenario verdict extractor for the JADE lane.
#
# Reads ONE class report and prints "<scenario><TAB><verdict>" per
# @ParameterizedTest case. Gradle writes each <testcase ...> on a single line and
# folds the ENTIRE failure detail -- ScenarioRunner's report plus DiffAnatomy's
# anatomy -- into the single-line message="..." attribute with &#10; for
# newlines, so this needs no XML parser either. Verified against a real report
# produced at c9a3dd7.
#
# awk, not python: mawk is ubuntu-24.04's /usr/bin/awk, so nothing here uses
# gensub, asort or any other gawk extension.
#
#   MATCH      the scenario passed: byte-identical to its golden.
#   ORDER      failed, and DiffAnatomy says SAME MULTISET -- the run emitted
#              exactly the golden's events in a different burst order.
#   QUANTUM    failed with a multiset difference that is ONLY the T-07 straddle:
#              the surplus lines on the two sides are the same canonical lines
#              differing only in which `diff=<T~N>` bucket VOTE.diff quantised
#              to. Granted narrowly on purpose -- every surplus line must carry
#              a `diff=<T~`, the declared counts must be small enough that
#              DiffAnatomy printed them all, and the two sides must cancel.
#   BEHAVIOUR  failed with any other multiset difference: a dropped or invented
#              event. Permitted by no expectation, anywhere.
#   OTHER      failed for something that is not a golden comparison at all -- an
#              agent died, the run missed its simulated-time bound, the harness
#              threw. Permitted nowhere, and never retried.
#   SKIP       assumed away. Permitted nowhere in this lane; the whole point of
#              the job is that these five actually run.
# ---------------------------------------------------------------------------
verdicts() {
    awk '
    function flush() {
        if (open) { printf "%s\t%s\t%s\t%s\n", scen, verdict, gold_lines, run_lines; open = 0 }
    }
    function msg_of(line,   s, e) {
        s = index(line, "message=\"")
        if (s == 0) return ""
        line = substr(line, s + 9)
        e = index(line, "\"")
        if (e == 0) return line
        return substr(line, 1, e - 1)
    }
    # A golden-comparison failure whose anatomy says DIFFERENT LINES. QUANTUM
    # only if the surplus cancels once the diff bucket is erased.
    function quantum(m,   n, i, part, decl, g, r, key, bad, gc, rc2, gseen, rseen) {
        if (match(m, /DIFFERENT LINES: [0-9]+ line\(s\) only in the golden, [0-9]+ only in the run/) == 0)
            return "BEHAVIOUR"
        decl = substr(m, RSTART, RLENGTH)
        split(decl, part, /[^0-9]+/)
        gc = part[2] + 0; rc2 = part[3] + 0
        # DiffAnatomy prints at most 5 surplus lines per side; more than that is
        # truncated and cannot be adjudicated here, so it stays BEHAVIOUR.
        if (gc == 0 || gc != rc2 || gc > 5) return "BEHAVIOUR"

        gseen = 0; rseen = 0; bad = 0
        delete cnt
        n = split(m, part, "&#10;")
        for (i = 1; i <= n; i++) {
            key = part[i]
            if (index(key, "only in golden: ") > 0) {
                g[++gseen] = substr(key, index(key, "only in golden: ") + 16)
            } else if (index(key, "only in run   : ") > 0) {
                r[++rseen] = substr(key, index(key, "only in run   : ") + 16)
            }
        }
        if (gseen != gc || rseen != rc2) return "BEHAVIOUR"
        for (i = 1; i <= gseen; i++) {
            if (index(g[i], "diff=&lt;T~") == 0) bad = 1
            key = g[i]; gsub(/diff=&lt;T~[0-9]+&gt;/, "DIFF_BUCKET", key); cnt[key]++
        }
        for (i = 1; i <= rseen; i++) {
            if (index(r[i], "diff=&lt;T~") == 0) bad = 1
            key = r[i]; gsub(/diff=&lt;T~[0-9]+&gt;/, "DIFF_BUCKET", key); cnt[key]--
        }
        for (key in cnt) if (cnt[key] != 0) bad = 1
        delete cnt
        if (bad) return "BEHAVIOUR"
        return "QUANTUM"
    }
    function classify(line,   m, part) {
        m = msg_of(line)
        # The two trace lengths, for the truncation hint jade-status prints. Measurement only:
        # nothing here feeds back into the verdict.
        if (match(m, /golden has [0-9]+ lines, run produced [0-9]+/) > 0) {
            split(substr(m, RSTART, RLENGTH), part, /[^0-9]+/)
            gold_lines = part[2] + 0; run_lines = part[3] + 0
        }
        if (index(m, "does not match its golden at contract level") == 0) return "OTHER"
        if (index(m, "SAME MULTISET:") > 0)   return "ORDER"
        if (index(m, "DIFFERENT LINES:") > 0) return quantum(m)
        return "OTHER"
    }
    /<testcase name=/ {
        flush()
        rest = substr($0, index($0, "name=\"") + 6)
        nm = substr(rest, 1, index(rest, "\"") - 1)
        split(nm, w, " ")
        scen = w[1]
        verdict = "MATCH"
        gold_lines = 0; run_lines = 0
        open = 1
        if ($0 ~ /\/>[ \t]*$/) flush()
        next
    }
    /<skipped/            { if (open) verdict = "SKIP"; next }
    /<failure message=/   { if (open && verdict == "MATCH") verdict = classify($0); next }
    /<error[ >]/          { if (open && verdict == "MATCH") verdict = "OTHER"; next }
    /<\/testcase>/        { flush(); next }
    END { flush() }
    ' "$1"
}

# The permitted verdicts recorded for one scenario, or the empty string.
permitted_for() {
    sed -n "s/^[[:space:]]*$1[[:space:]][[:space:]]*//p" "$EXPECTED_FILE" | head -n 1
}

# Is $2 one of the whitespace-separated verdicts in $1?
is_permitted() {
    local allowed
    for allowed in $1; do
        [ "$allowed" = "$2" ] && return 0
    done
    return 1
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
        file="$(required_report)"
        [ -n "$file" ] || exit 1
        failures="$(attr "$file" failures)"
        errors="$(attr "$file" errors)"
        # Fail closed: an unreadable report is not evidence that the end-to-end
        # scenario flaked, so it does not earn a retry.
        num "$failures" && num "$errors" || exit 1
        [ $(( failures + errors )) -gt 0 ]
        ;;

    # -------------------------------------------------------------------
    # assert-ran — the acceptance criterion of #25.
    # -------------------------------------------------------------------
    assert-ran)
        print_table || exit 1

        rc=0

        # 1 -- the end-to-end scenario exists, ran, and was not assumed away.
        file="$(required_report)"
        if [ -z "$file" ]; then
            echo "FAIL: no report for $REQUIRED_CLASS. The end-to-end scenario was not even" \
                 "discovered -- check that the characterizationIT source set compiled." >&2
            rc=1
        else
            tests="$(attr "$file" tests)"
            skipped="$(attr "$file" skipped)"
            failures="$(attr "$file" failures)"
            errors="$(attr "$file" errors)"

            # Every count is validated BEFORE it is compared; see num(). A
            # report whose numbers cannot be read is a failed assertion.
            readable=1
            for attribute in "tests:$tests" "skipped:$skipped" \
                             "failures:$failures" "errors:$errors"; do
                if ! num "${attribute#*:}"; then
                    echo "FAIL: $REQUIRED_CLASS reports a non-numeric" \
                         "${attribute%%:*}=\"${attribute#*:}\". That is not the shape Gradle's" \
                         "XML writer produces, and an assertion that cannot be evaluated is a" \
                         "failed assertion, never a passed one." >&2
                    readable=0
                    rc=1
                fi
            done

            if [ "$readable" -eq 1 ]; then
                if [ "$tests" -lt 1 ]; then
                    echo "FAIL: $REQUIRED_CLASS reports tests=\"$tests\"." >&2
                    rc=1
                fi
                if [ "$skipped" -ne 0 ]; then
                    echo "FAIL: $REQUIRED_CLASS was SKIPPED (skipped=\"$skipped\"), so the" \
                         "application was never started and this run proves nothing about the" \
                         "baseline. The cause is almost always that -Popencybele.dist did not" \
                         "point at a './gradlew installDist' output containing lib/*.jar." >&2
                    rc=1
                fi
                if [ $(( failures + errors )) -ne 0 ]; then
                    echo "FAIL: $REQUIRED_CLASS reports failures=\"$failures\" errors=\"$errors\"." >&2
                    rc=1
                fi
            fi
        fi

        # 2 -- nothing else quietly assumed itself away either. Measured at
        # 0dd6ab6 with a dist supplied: 31 tests, 0 skipped, across 5 classes.
        # Asserting the total keeps a future `assumeTrue` from hiding in a
        # class this script does not name.
        check_skips
        [ "$skip_rc" -eq 0 ] || rc=1

        # 3 -- and nothing anywhere in the suite failed. Gradle's exit status has normally said
        # this already; asserting it here as well means this script's OK line is a statement
        # about the reports it just read rather than about one class in them.
        if failures_total="$(total failures)" && errors_total="$(total errors)"; then
            bad_total=$(( failures_total + errors_total ))
            if [ "$bad_total" -ne 0 ]; then
                echo "FAIL: $bad_total failure(s)/error(s) across the suite." >&2
                rc=1
            fi
        else
            echo "FAIL: the suite's failure/error counts could not be read (see above)." >&2
            rc=1
        fi

        if [ "$rc" -eq 0 ]; then
            # Guarded like everything else: the OK line must not be printable off
            # a number this script could not read.
            if tests_total="$(total tests)"; then
                echo "OK: $tests_total test(s), $OPT_IN_SKIPPED opt-in skipped, 0 failed -- and" \
                     "$REQUIRED_CLASS RAN (the application was started as a child JVM and" \
                     "compared against its golden)."
            else
                echo "FAIL: the suite's test counts could not be read (see above)." >&2
                rc=1
            fi
        fi
        exit "$rc"
        ;;

    # -------------------------------------------------------------------
    # assert-suite — the same lock for a plain lane (`test`, `integrationTest`).
    #
    # A Gradle Test task that discovers nothing exits 0 and writes no reports,
    # and one whose filter silently narrowed writes a few and exits 0.
    # PARITY_MIN_TESTS is a FLOOR against that, not a pin: it is set to the
    # lane's measured tally so that losing tests is red and adding them is not.
    # -------------------------------------------------------------------
    assert-suite)
        print_table || exit 1

        rc=0
        check_skips
        [ "$skip_rc" -eq 0 ] || rc=1

        if failures_total="$(total failures)" && errors_total="$(total errors)"; then
            if [ $(( failures_total + errors_total )) -ne 0 ]; then
                echo "FAIL: $(( failures_total + errors_total )) failure(s)/error(s) across the lane." >&2
                rc=1
            fi
        else
            echo "FAIL: the lane's failure/error counts could not be read (see above)." >&2
            rc=1
        fi

        if tests_total="$(total tests)"; then
            if ! num "$MIN_TESTS"; then
                echo "FAIL: PARITY_MIN_TESTS=\"$MIN_TESTS\" is not a number." >&2
                rc=1
            elif [ "$tests_total" -lt "$MIN_TESTS" ]; then
                echo "FAIL: the lane ran $tests_total test(s), below its floor of $MIN_TESTS. A" \
                     "Test task that discovers fewer tests than it used to still exits 0, so this" \
                     "is the only place it can be caught. If tests were deliberately removed," \
                     "lower the floor in the workflow deliberately." >&2
                rc=1
            fi
        else
            echo "FAIL: the lane's test counts could not be read (see above)." >&2
            rc=1
        fi

        if [ "$rc" -eq 0 ]; then
            echo "OK: $tests_total test(s) (floor $MIN_TESTS), $OPT_IN_SKIPPED opt-in skipped, 0 failed."
        fi
        exit "$rc"
        ;;

    # -------------------------------------------------------------------
    # jade-status — the L3 JADE lane's judge (#40).
    #
    # WHY THIS LANE CANNOT BE JUDGED BY "ALL FIVE GREEN", and why judging it
    # this way is not a weakened check.
    #
    # #39 measured the port against the frozen goldens and classified the
    # residue (b), normalizer gap, not closable (docs/parity-triage-jade.md).
    # The facts a CI job has to be built around:
    #
    #   * The JADE run emits the SAME EVENT MULTISET as the 2008 application in
    #     all five scenarios. Every difference is burst placement.
    #   * Pass rates are LOAD-DEPENDENT, and that is the diagnosis showing
    #     through rather than noise. Three corpora give `capacity` 6/20, 15/20
    #     and 12/12; `congestion` 3/20 and 7/12; `strict` 0/N in every corpus.
    #   * Margins against the 220 ms burst width rank the rates exactly:
    #     `capacity` 118 ms, `congestion` 9 ms, `strict` 0 ms.
    #   * Under concurrent load the BASELINE fails its own `strict` golden 3/20,
    #     at the same line and in the same direction. The instrument does this
    #     to the reference implementation too.
    #
    # A required check demanding five green scenarios would therefore be red
    # forever, and a job that is always red teaches people to ignore CI -- the
    # exact failure this project already paid for once (#78/#79).
    #
    # WHAT IS ASSERTED INSTEAD. Not the pass RATE, which the measurement shows
    # is a property of the machine; the per-scenario VERDICT CLASS, which the
    # measurement shows is stable. The permitted sets live in the expectations
    # file named on the command line.
    #
    # Three properties follow, and they are why this is not a way of hiding a
    # regression:
    #
    #   1. BEHAVIOUR is permitted NOWHERE. A dropped or invented event fails
    #      every scenario, including the three that are allowed to fail. The
    #      claim every corpus supports -- "no run has ever produced an event the
    #      golden did not record, or missed one it did" -- is the thing being
    #      guarded, and it is guarded on all five.
    #   2. A CHANGE IN EITHER DIRECTION IS RED. `strict` does not list MATCH, so
    #      a `strict` that suddenly passes fails this job. That is deliberate: a
    #      scenario that starts passing means the measurement this file pins has
    #      gone stale, and somebody has to re-measure rather than enjoy a green.
    #   3. QUANTUM is granted only for the one straddle #39 named (T-07,
    #      VOTE.diff on `(vl8, stH)`), by requiring every surplus line to carry a
    #      `diff=<T~` and the two sides to cancel exactly.
    #
    # THE COST, stated rather than hidden: this pins a measurement. The
    # measurement lives in docs/parity-triage-jade.md (§1's corpus table, §4.4's
    # margin table) and in PR #80's two measurement comments. Re-measuring is
    # not a CI action -- it is ~20 runs per scenario on a quiet machine through
    # `parityGate`, and those numbers are what an edit to the expectations file
    # has to argue against.
    # -------------------------------------------------------------------
    jade-status)
        [ -n "$EXPECTED_FILE" ] || usage
        if [ ! -f "$EXPECTED_FILE" ]; then
            echo "FAIL: no expectations file at '$EXPECTED_FILE'. This lane is judged against a" \
                 "recorded measurement and refuses to judge itself without one." >&2
            exit 1
        fi

        print_table || exit 1

        rc=0
        check_skips
        [ "$skip_rc" -eq 0 ] || rc=1

        # Everything outside the scenario class is deterministic and must be clean:
        # HarnessSelfCheckIT, JadeLauncherIT, TraceNormalizerIT, DiffAnatomyIT and
        # the rest are in-process tests of the harness, and this lane's tolerance
        # extends to exactly one class.
        if out_f="$(total_excluding_required failures)" && out_e="$(total_excluding_required errors)"; then
            if [ $(( out_f + out_e )) -ne 0 ]; then
                echo "FAIL: $(( out_f + out_e )) failure(s)/error(s) OUTSIDE $REQUIRED_CLASS. This" \
                     "lane tolerates a recorded verdict from the scenario class and from nothing" \
                     "else; every other class here is an in-process harness test and is" \
                     "deterministic." >&2
                rc=1
            fi
        else
            echo "FAIL: the suite's failure/error counts could not be read (see above)." >&2
            rc=1
        fi

        file="$(required_report)"
        if [ -z "$file" ]; then
            echo "FAIL: no report for $REQUIRED_CLASS. The JADE scenarios were not even" \
                 "discovered, so this job reported on nothing at all." >&2
            exit 1
        fi

        seen=""
        printf '%-24s %-10s %-18s %s\n' "scenario" "verdict" "recorded" "status"
        while IFS="$(printf '\t')" read -r scenario verdict gold_lines run_lines; do
            [ -n "$scenario" ] || continue
            seen="$seen $scenario"
            permitted="$(permitted_for "$scenario")"
            if [ -z "$permitted" ]; then
                printf '%-24s %-10s %-18s %s\n' "$scenario" "$verdict" "<unrecorded>" "FAIL"
                echo "FAIL: scenario '$scenario' ran but has no recorded status in" \
                     "$EXPECTED_FILE. A new or renamed scenario has to have its status measured" \
                     "and written down before this job can say anything about it." >&2
                rc=1
                continue
            fi
            if is_permitted "$permitted" "$verdict"; then
                printf '%-24s %-10s %-18s %s\n' "$scenario" "$verdict" "$permitted" "ok"
            else
                printf '%-24s %-10s %-18s %s\n' "$scenario" "$verdict" "$permitted" "CHANGED"
                echo "FAIL: '$scenario' came out $verdict; the recorded status permits" \
                     "[$permitted]. A change in EITHER direction is a signal -- a scenario that" \
                     "starts failing is a regression, and one that starts passing means the" \
                     "measurement in docs/parity-triage-jade.md has gone stale. Re-measure before" \
                     "editing $EXPECTED_FILE." >&2
                # THE TRUNCATION HINT. Measurement only: it changes no verdict, and a short run is
                # still BEHAVIOUR and still red. It exists because there is no automatic
                # fingerprint separating "the port dropped an event" from "the machine could not
                # keep up", #39 established that by hand over two corpora (T-12), and a triager who
                # has to rediscover the distinction from scratch will misread this failure.
                if [ "$verdict" = "BEHAVIOUR" ] && num "${gold_lines:-}" && num "${run_lines:-}" \
                   && [ "$run_lines" -lt "$gold_lines" ]; then
                    echo "      HINT: the run is SHORT -- $run_lines lines against the golden's" \
                         "$gold_lines. Every opencybele-* scenario is bounded by an ABSOLUTE" \
                         "sim.stop.maxClockMs at pace 8, so a machine that cannot keep up with" \
                         "simulated time reaches the bound having emitted fewer events, and the" \
                         "shortfall is indistinguishable from a dropped event by inspection of" \
                         "this trace alone. Measured boundary for that effect on the developer" \
                         "machine (docs/ci.md 10.7): 4 dedicated cores -> 5/5 inside the recorded" \
                         "status; 4 cores at ~50% steal -> 3/3 short, and the BASELINE arm fails" \
                         "its own goldens 3/3 under the same starvation. Check the runner before" \
                         "checking the port." >&2
                fi
                rc=1
            fi
        done <<EOF
$(verdicts "$file")
EOF
        echo

        # Every recorded scenario must have produced a result. Otherwise one
        # could be dropped from the catalogue and this job would keep reporting
        # green on the four that remain.
        while read -r scenario permitted; do
            case "$scenario" in ''|'#'*) continue ;; esac
            [ -n "$permitted" ] || continue
            case " $seen " in
                *" $scenario "*) ;;
                *)
                    echo "FAIL: '$scenario' is recorded in $EXPECTED_FILE but produced no result." \
                         "It did not run, so this job reported on one scenario fewer than it" \
                         "claims to." >&2
                    rc=1
                    ;;
            esac
        done < "$EXPECTED_FILE"

        if [ "$rc" -eq 0 ]; then
            echo "OK: every scenario's verdict is inside its recorded status, no scenario differed" \
                 "from its golden by MULTISET, and $OPT_IN_SKIPPED opt-in test(s) skipped."
        fi
        exit "$rc"
        ;;

    # -------------------------------------------------------------------
    # jade-retryable — the JADE lane's retry predicate, in the same relation
    # to `jade-status` that `smoke-failed` has to `assert-ran`, and narrower.
    #
    # True ONLY when every scenario outside its recorded status came out
    # BEHAVIOUR, and nothing else in the suite failed. That is the T-12 class:
    # a cluster of slow JVM starts shifting the whole schedule against an
    # absolute stop bound, which #39 chased down, could not reproduce in a
    # second corpus, and recorded as a machine transient (§4.8). A loaded,
    # shared CI runner is the worst case for it.
    #
    # Deliberately NOT true for:
    #   * an unexpected MATCH -- `strict` going green is the status change this
    #     job exists to report, and retrying it would be a way of un-hearing it;
    #   * OTHER -- an agent died, the run missed its simulated-time bound, the
    #     harness threw. Deterministic; those fail again;
    #   * any failure outside the scenario class -- in-process harness tests.
    #
    # A second consecutive BEHAVIOUR diff is not a transient, and goes red.
    # -------------------------------------------------------------------
    jade-retryable)
        [ -n "$EXPECTED_FILE" ] || usage
        [ -f "$EXPECTED_FILE" ] || exit 1

        # Fail closed: anything unreadable is not evidence of a transient.
        if out_f="$(total_excluding_required failures)" && out_e="$(total_excluding_required errors)"; then
            [ $(( out_f + out_e )) -eq 0 ] || exit 1
        else
            exit 1
        fi

        file="$(required_report)"
        [ -n "$file" ] || exit 1

        retryable=0
        while IFS="$(printf '\t')" read -r scenario verdict gold_lines run_lines; do
            [ -n "$scenario" ] || continue
            permitted="$(permitted_for "$scenario")"
            is_permitted "$permitted" "$verdict" && continue
            # Outside its recorded status. Exactly one class of that is a transient.
            [ "$verdict" = "BEHAVIOUR" ] || exit 1
            retryable=1
        done <<EOF
$(verdicts "$file")
EOF
        [ "$retryable" -eq 1 ]
        ;;

    *)
        usage
        ;;
esac
