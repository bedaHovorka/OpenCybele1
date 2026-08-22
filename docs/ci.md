# Continuous integration

> Workflow: [`.github/workflows/ci.yml`](../.github/workflows/ci.yml)
> · Issues: [#25](https://github.com/bedaHovorka/OpenCybele1/issues/25) (the drift guard),
> [#75](https://github.com/bedaHovorka/OpenCybele1/issues/75)/[#79](https://github.com/bedaHovorka/OpenCybele1/issues/79)
> (the port guard), [#78](https://github.com/bedaHovorka/OpenCybele1/issues/78) (the zero-skip
> rule), [#40](https://github.com/bedaHovorka/OpenCybele1/issues/40) (the JADE lanes)
> · Requirement: [`Phase1.md`](Phase1.md) 1-PRE.4 and 1-POST

**The file was `characterization-opencybele.yml` until #40 and is now `ci.yml`.** It stopped being
a file about one implementation the moment the JADE lane arrived. Job names did not change.

This started as the repository's first workflow and one narrow job: run the L3 parity suite
([`TESTING.md`](TESTING.md) §6, [`parity-harness.md`](parity-harness.md)) against the frozen
OpenCybele baseline, so that behavioural drift in the baseline is a red build rather than a
mysterious golden diff discovered halfway through the port. It is now four jobs, and each answers a
different question:

| Job | Trigger | The question it answers | Section |
|---|---|---|---|
| **`build@this-ref`** | push / PR | Does the branch under test compile, and do its 321 L1 unit tests and `domainPurity` pass? | §11 |
| **`integrationTest@this-ref`** | push / PR | Do the 17 L2 tests pass, booting real in-process JADE containers? | §12 |
| **`characterizationIT@opencybele`** | push / PR (+ an inert nightly, §7) | Has the **frozen baseline** drifted from the goldens recorded from it? | §§1–9 |
| **`characterizationIT@jade`** | push / PR | Does the **port** still reproduce the measured L3 state against those same goldens? | §10 |

All four run on every push to and pull request against `develop`/`jade-develop`. Sections 1–9 below
describe the `@opencybele` job, which is the oldest and the one whose reasoning the others inherit;
§§10–12 describe what the three later jobs do differently and why.

**Why the port guard exists at all.** #75: from #28 onward, `characterizationIT@opencybele` builds
its application from `opencybele-baseline`, so it never compiles the code being ported and would
have stayed green through the entire migration without once exercising it. From #30 onward `src/main`
is a JADE application that does not run under Cybele at all. `build@this-ref` (#79) closed the
compilation half of that gap; `characterizationIT@jade` (#40) closes the behavioural half.

---

## 1. What it does, in order

| # | Step | Why it is not obvious |
|---|---|---|
| 1 | Check out the harness (the pushed ref) into `harness/` | |
| 2 | Check out `opencybele-baseline` into `opencybele/`, `fetch-depth: 0` | The vendor jars are recovered from a **git tag**, which the default shallow single-branch fetch does not bring. See §2 for what this does and does not buy |
| 3 | JDK 21 (Temurin) + `gradle/actions/setup-gradle` | 21 is what `build.gradle.kts` pins on both branches; providing it keeps Gradle from auto-provisioning a toolchain over the network |
| 4 | Restore `~/.m2/repository/com/iai` from the Actions cache | Only the two vendored artifacts live there; everything else Gradle resolves is in the Gradle caches |
| 5 | `opencybele/scripts/bootstrap-vendor-jars.sh` | The **single call** — no `mvn install:install-file` lines are duplicated in the workflow |
| 6 | `scripts/bootstrap-vendor-jars.sh --verify-only` | A post-condition assertion and a log separator — **not** the stale-cache defence; see §2 |
| 7 | `opencybele/./gradlew installDist` | `installDist`, not `build` — `build/install/opencybele/lib/*.jar` is the layout `OpenCybeleLauncher` drives |
| 8 | Print the two commits, the dist contents, `DISPLAY`, and the three leaky `*JAVA*OPTIONS` variables | Cheap evidence that this was a headless run of the artefacts it claims |
| 9 | `harness/./gradlew characterizationIT -Popencybele.dist=…` (with one narrow retry) | §4 |
| 10 | `.github/scripts/parity-junit.sh assert-ran` | §3 — **the assertion Gradle's exit status cannot make** |
| 11 | Collect and upload evidence, on failure or on a retry | §5 |

---

## 2. Two branches, one job

Decision [#11](https://github.com/bedaHovorka/OpenCybele1/issues/11) put the parity harness on
`jade-develop` and left the OpenCybele implementation on `opencybele-baseline`.
`OpenCybeleLauncher` ([#13](https://github.com/bedaHovorka/OpenCybele1/issues/13)) bridges them
**by path**: it assembles a `java` command line pointing at a built dist and starts a child JVM.
There is no compile-time link in either direction, and `HarnessSelfCheckIT` fails the build if a
harness file ever imports an application class.

So the job checks out both branches side by side in the workspace and wires them together with one
property:

```
-Popencybele.dist=$GITHUB_WORKSPACE/opencybele/build/install/opencybele
```

`opencybele.home` is not passed: the launcher derives it by walking up from the dist until it finds
`cybelle/cybele.prop`, which lands on `$GITHUB_WORKSPACE/opencybele`. That directory supplies the
`cybelle/*.prop` files the child needs for `--patch-module java.base=cybelle`; the launcher stages
them into its own per-run scratch directory, so a CI run cannot write into either checkout.

`workflow_dispatch` takes an `opencybele-ref` input (default `opencybele-baseline`) if you ever need
to run the suite against a specific application commit.

### The vendor jars

`Cybele.jar` / `CybeleImpl.jar` are 2008-era IAI binaries with no public Maven repo. They are
excluded by `.gitignore` and reachable only through the `withoutGradle` tag.
`scripts/bootstrap-vendor-jars.sh` ([#14](https://github.com/bedaHovorka/OpenCybele1/issues/14))
restores them from that tag and installs them as `com.iai:cybele-api:1.0` /
`com.iai:cybele-impl:1.0`. It works with or without `mvn` on `PATH`, and it is idempotent.

That script lives on **`opencybele-baseline`**, not on `jade-develop` — which is fine, because the
job has that branch checked out anyway and the jars are only needed to build the application. The
workflow calls it in the `opencybele/` checkout and duplicates none of its commands.

**What `fetch-depth: 0` is actually for.** Not for making the recovery possible: measured against
`actions/checkout`'s exact default fetch (shallow, single-branch, one object, empty tag list), the
script's own `git fetch --tags` fallback deepened the clone, recovered the tag and restored both
jars, rc 0, on git 2.55.0. It is set because it makes recovery *deterministic* and moves the fetch
into the checkout step, off the critical path of a build step — not because the default would fail.

**What catches a stale or poisoned `~/.m2` cache.** Step 5, not step 6. The script always restores
`cybelle/*.jar` from the tag first and then `cmp`s the installed artifact against them, so a
poisoned cache entry is detected and reinstalled during the *install* step; `--verify-only`
afterwards then passes, because the defence has already fired. Measured. What `--verify-only` does
catch on its own is an installed artifact whose generated `.pom` is missing, and it makes the log
distinguish "installed" from "present and matching".

**Do not reorder steps 5 and 6.** `--verify-only` fails *open* when `cybelle/*.jar` are absent from
the working tree — it treats "no source jar to compare against" as not-a-failure, so with a
corrupted installed artifact and no jars in the tree it exits 0. That is harmless in this ordering,
because step 5 always restores first, and a latent trap for anyone running the script standalone or
swapping the two steps around.

---

## 3. Why a green Gradle run is not enough

`OpenCybeleSmokeIT` opens with

```java
assumeTrue(OpenCybeleLauncher.isAvailable(), OpenCybeleLauncher.unavailableMessage());
```

which is correct for the harness branch — it must stay testable with no implementation checked out
anywhere — and a trap for CI. Measured on this repository at `0dd6ab6`:

| command | Gradle | `OpenCybeleSmokeIT` |
|---|---|---|
| `./gradlew characterizationIT` | **BUILD SUCCESSFUL** | `skipped="1"` |
| `./gradlew characterizationIT -Popencybele.dist=…` | BUILD SUCCESSFUL | `skipped="0"`, passed |

A workflow that trusts the exit status therefore reports green for a run in which the only test
that starts the application never ran — *a workflow that silently skips the only real test is worse
than no workflow*. So the job does not believe the run until
[`.github/scripts/parity-junit.sh`](../.github/scripts/parity-junit.sh) has read
`build/test-results/characterizationIT/TEST-*.xml` and confirmed, by name:

* `OpenCybeleSmokeIT` has a report at all, with `tests >= 1`;
* it is not `skipped`, and has no `failures`/`errors`;
* **nothing else in the suite was skipped either** — measured baseline is 31 tests, 0 skipped,
  across 5 classes, so a future `assumeTrue` cannot hide in a class this script does not name.

The step prints the per-class table before its verdict, so a red step carries the numbers that
produced it.

**It fails closed, including on numbers it cannot read.** Every count goes through a `num()` guard
before it is compared. Without it the script's own shape betrayed it: the checks read
`if <comparison>; then rc=1; fi`, and `[ one -lt 1 ]` does not evaluate false — it errors and
returns 2, so the branch is not taken and an assertion that *could not be evaluated* read as "the
assertion holds". Measured: a report with `tests="one" skipped="zero"` printed four
`integer expected` errors to stderr and still exited 0 with the `OK:` line. `total()` had the same
hole one level down, silently yielding an empty string. Both are now failures. The behaviour is
exercised against crafted reports: happy path 0, and exit 1 for a skipped smoke test, an absent
smoke report, `tests="0"`, a *different* class skipped, a harness class failing while smoke passes,
an empty directory, a missing directory, malformed XML, truncated XML, and non-numeric attributes in
either the smoke report or another class's.

---

## 4. The retry, and why there is exactly one

`DEF-22` ([`defect-triage.md`](defect-triage.md)) is an unbounded `latch.await()` in `Planning`.
When it fires, the election wedges for the rest of the run — but `Generator` keeps generating and
the clock bound still fires, so **the process still exits 0**. Measured rate at the stock
configuration: **2 hangs in 90 runs, roughly 1 in 45**, with zero `AssertionError` and zero
`Exception in thread "` across 146 measurement runs. It is invisible to the exit status, invisible
to an exception grep, and `sim.stop.stallMs` structurally cannot catch it.

What it looks like in this suite is a liveness floor or a summary count coming in low — which is
**exactly** what real behavioural drift looks like. There is no fingerprint that separates them.
Re-running is the only test that does: drift is deterministic and fails again; a wedge is a race
and almost certainly does not.

Hence one bounded retry, and three constraints on it so it cannot become a way of ignoring CI:

1. **Only the end-to-end class is retried.** The retry predicate is
   `parity-junit.sh smoke-failed`, which is true only when `OpenCybeleSmokeIT` itself has
   failures or errors. `HarnessSelfCheckIT`, `ScenarioCatalogIT` and `SmokeScenarioIT` run
   in-process and are deterministic; they fail once and stay failed.
2. **A retry is never silent.** It raises a `::warning`, writes a `:warning:` line into the job
   summary, and the failed attempt's log *and* its reports are kept and uploaded. A green run that
   needed two attempts is a run somebody has to look at.
3. **Two consecutive failures go red.** At the measured rate that is a ~5 × 10⁻⁴ coincidence.

Set `MAX_ATTEMPTS: '1'` in the workflow's `env:` to turn retrying off.

Note that the scenario's own `run.harnessTimeoutMs: 120000` is a separate backstop for the case
where the child hangs and its 60 s wall-clock net does not fire; a kill is reported as
`HARNESS_TIMEOUT` and can never be read as a pass.

### What the retry does NOT protect against, and how to triage the first red run

The scenario's tolerances were measured on a workstation. Emulating a 2-vCPU runner with `taskset`:

| | lines | ENTER | ENTER_REPLY | LEAVE | TRAVEL_START | TRAVEL_END | PATH_FIND |
|---|---|---|---|---|---|---|---|
| unloaded, 3/3 runs | 623 | 38 | 38 | 35 | 18 | 15 | 15 |
| **saturated, 3/3 runs** | 607 | **36** | **36** | **33** | **17** | **14** | **14** |

Two things follow, and the second is the one that matters.

* An **idle** hosted runner should reproduce the golden exactly.
* The saturated deficit is **reproducible, not random** — identical in every run. So a runner
  systematically slower than that produces a *deterministic* count deficit that fails **both**
  attempts. The retry gives no protection against this, and it will look exactly like drift. Five
  of the thirteen summary rules are already down to a margin of one line at the saturated floor.

The election families (`VOTE_REQUEST`/`VOTE`/`VOTE_RESULT`/`PLAN_TRAIN`/`START` = 93/93/93/9/5) are
rock-stable under saturation. Only the movement families move.

**So triage the first red run on the hosted runner this way:** read the counts out of the failure
report and compare them against the saturated floor **36 / 36 / 33 / 17 / 14 / 14**. Counts at or
near that floor are the runner being slow, not the baseline drifting — re-measure the tolerances on
the runner and widen them from the measurement, per the scenario file's own rule that tolerances are
measurements rather than slack. Counts *below* the floor, or any movement in the election families,
is a real signal.

---

## 5. Evidence

This suite's failures are trace diffs and count mismatches, so a red run with nothing to read is
nearly useless. The harness' own failure report already carries the diff, the exit disposition, the
exact child command line and the last 20 captured lines, and it reaches the console log, the JUnit
XML and the HTML report alike. On failure — or on a run that needed a retry — the job uploads
`characterizationIT-opencybele-evidence` (14 days) containing:

```
attempt-<n>.log                 full Gradle output of each attempt
attempt-<n>/test-results/       that attempt's JUnit XML
attempt-<n>/reports/            that attempt's HTML report
parity-tests/golden/            the goldens the run was compared against
parity-tests/scenarios/         the scenario definitions it ran
parity-scratch/                 the directory the child JVM ran in (staged cybelle/*.prop only)
```

Each attempt's reports are copied out **before** the next attempt overwrites them, so a
retry-then-pass run still ships the failing attempt.

`parity-scratch/` is smaller than it sounds: the captured child output lives in the failure report,
not on disk, so what is there is the staged kernel configuration. It answers "did the child get the
`cybelle/*.prop` it needed?" and nothing more.

---

## 6. Headless, and no virtual display

The application is Swing. The scenario passes `sim.headless=true`, which skips window construction
entirely; on a runner with no `DISPLAY` the JVM is headless of its own accord, and `Main`'s eager
check only objects to the opposite combination (`sim.headless=false` in a headless JVM). Verified
locally with `DISPLAY` and `XAUTHORITY` unset: the full sequence is green.

**There is deliberately no Xvfb.** Headless is the supported path
(`docs/headless-and-stop.md` on `opencybele-baseline`, [#17](https://github.com/bedaHovorka/OpenCybele1/issues/17)); standing up a virtual display "to be safe"
would hide a regression in headless mode, and variance measured on a shared X display is
contaminated. The workflow instead *prints* `DISPLAY` and warns if it is set.

It also prints `JAVA_TOOL_OPTIONS`, `_JAVA_OPTIONS` and `JDK_JAVA_OPTIONS`. `OpenCybeleLauncher`
refuses to launch when any of them is set — the JVM would append flags nobody declared and announce
it on stderr, straight into the captured trace and thence into a golden.

---

## 7. Triggers, and one gap that is stated rather than hidden

| Trigger | Branches |
|---|---|
| `push` | `develop`, `jade-develop` |
| `pull_request` | into `develop`, `jade-develop` |
| `schedule` | nightly, `17 3 * * *` |
| `workflow_dispatch` | with an optional `opencybele-ref` |

Feature branches (`phase1/**`) are **not** in the `push` list on purpose: they get the same job
through their pull request, which halves the number of runs for identical coverage.

**The gap.** `opencybele-baseline` is not — and cannot usefully be — in the `push` list. A
push-triggered workflow is read from the tree of the ref that was pushed, and that branch carries
no `.github/` and no harness, so listing it would change nothing. A push straight to the baseline,
the exact event this job exists to catch, does not trigger it. The nightly `schedule` is the cover.

**And two caveats on the cover.**

*The default-branch rule.* GitHub reads `schedule` from the workflow file as it exists on the
**default branch** only, so until this file is on `develop` the nightly does not fire at all.
`workflow_dispatch` is subtly different and the distinction is worth getting right: the *trigger*
must be defined on the default branch for the workflow to be dispatchable, but the run then executes
the file **from the ref you dispatch against**. Until the merge, `push`/`pull_request` are doing all
the work; nothing needs changing when it happens — the other two simply start firing.

*Cherry-picking this file alone would not help.* `origin/develop` today carries no `.github/`, no
`parity-tests/` and no `src/characterizationIT/`, and a scheduled run checks out the triggering ref
(no `ref:` on the first checkout). Dropping just this workflow onto `develop` would therefore
produce a nightly that dies at `characterizationIT` with nothing to run. The nightly becomes
meaningful only when the harness reaches `develop` wholesale.

**The alternatives were checked and are worse.** A `pull_request` trigger targeting
`opencybele-baseline` does not help: for `pull_request` GitHub resolves workflows from the merge
ref, so with a base carrying no `.github/` only a head branch carrying the workflow would trigger
anything. A job on `jade-develop` that inspects the baseline's head SHA still needs a trigger to
run on, which reduces to this same nightly, and would additionally have to persist state to notice
a *change* rather than a state.

**The recommended companion is a repo setting, not a workflow change:** branch protection on
`opencybele-baseline`, which *prevents* the unreviewed push rather than detecting it up to 24 h
later. That is the real fix for this gap; the nightly is the detector of last resort.

**#40 looked at fixing the inert nightly and deliberately left it to #75.** Three reasons:

* **None of #40's three jobs depends on it.** All four jobs are `push`/`pull_request`-triggered on
  the ref under test, which works today. Adding a second `cron:` "for the JADE lane" would be
  *worse* than adding nothing — equally inert, and it would read to the next person as cover that
  exists.
* **The inertness is a property of where this file lives**, not of any job in it. #75's two
  candidate fixes — merge the workflow to `develop`, or drive the baseline from a push-triggered
  job with an explicit `opencybele-ref` — both change the **opencybele** arm, and the first is a
  decision about the frozen 2008 default branch that is nowhere near a CI-jobs ticket.
* **#40 does shrink the gap without touching the trigger, and the residue is worth naming
  exactly.** `characterizationIT@opencybele` already runs on every push and PR to `jade-develop`,
  so the baseline is exercised on every change to the port branch. What remains uncovered is
  precisely one event: *a push made straight to `opencybele-baseline` with no pull request
  anywhere.* That, and only that, is what the inert nightly was the cover for.

`concurrency` is keyed on the workflow, **the event** and the ref, with `cancel-in-progress: true`.
The event is in the group deliberately: without it a scheduled run and a push run on `develop` share
`refs/heads/develop`, so a 03:17 push would cancel the nightly — the one run that is the sole cover
for the gap above. Note that `cancel-in-progress: ${{ github.event_name != 'schedule' }}` does *not*
fix this, although it reads as though it should: GitHub evaluates that property on the run being
*queued*, so the push (whose own value is `true`) would still cancel the running nightly. Separating
the groups is what keeps them from contending. Within pushes, a newer commit still cancels the
superseded run, and two branches still never cancel each other.

---

## 8. Security and hygiene

* **No secrets and no tokens.** Nothing here needs one; `permissions: contents: read`.
* **No machine-specific paths.** Everything is relative to `$GITHUB_WORKSPACE`.
* **Every action is pinned to a full commit SHA**, with the release tag in a trailing comment.
* `setup-gradle` runs with `cache-provider: basic` — the open-source GitHub Actions cache rather
  than the default commercial service — so the job depends on no third party and no credential.
* `validate-wrappers: true` checks both committed `gradle-wrapper.jar`s against Gradle's published
  checksums. Worth having in a repository that ships two of them and has never had CI.
* `cache-read-only` is set explicitly to `github.event_name == 'pull_request'`. The action's own
  default is "read-only unless this is the default branch", which on `jade-develop` would mean the
  cache is never written and every run is cold.
* The `~/.m2` `actions/cache` step deliberately carries **no** matching pull-request read-only
  guard, which is an inconsistency with the line above rather than a hole. Two reasons: GitHub's own
  cache scoping already stops a pull-request branch's entry from being read by the base branch, and
  more importantly the content is re-verified on every run — the bootstrap restores the jars from
  the `withoutGradle` tag and `cmp`s them against whatever the cache produced, so a poisoned entry
  is overwritten rather than used (§2). A guard here would buy nothing that the content check does
  not already buy.

---

## 9. Reproducing the job locally

The workflow is a wrapper around five commands. From two checkouts side by side:

```bash
# 1. vendor jars (recovers cybelle/*.jar from the withoutGradle tag, installs into ~/.m2)
( cd opencybele && scripts/bootstrap-vendor-jars.sh && scripts/bootstrap-vendor-jars.sh --verify-only )

# 2. build the application into the layout the launcher drives
( cd opencybele && ./gradlew --no-daemon --console=plain installDist )

# 3. run the suite against it
( cd harness && ./gradlew --no-daemon --console=plain characterizationIT \
      -Popencybele.dist="$PWD/../opencybele/build/install/opencybele" )

# 4. the assertion the exit status cannot make
( cd harness && .github/scripts/parity-junit.sh assert-ran build/test-results/characterizationIT )
```

`characterizationIT@jade` reproduces from **one** checkout, since the harness and the JADE
implementation are the same tree:

```bash
scripts/bootstrap-vendor-jars.sh
./gradlew --no-daemon --console=plain installDist
./gradlew --no-daemon --console=plain characterizationIT -Pjade.dist="$PWD/build/install/opencybele"
# ^ exits non-zero on essentially every run, BY DESIGN. The verdict is the next command's:
PARITY_REQUIRED_CLASS=cz.vutbr.fit.ags.parity.it.JadeParityIT \
PARITY_OPT_IN_CLASSES="$(sed -n 's/^ *//p' <<'X'
cz.vutbr.fit.ags.parity.it.ParityGateIT cz.vutbr.fit.ags.parity.it.OpenCybeleSmokeIT
cz.vutbr.fit.ags.parity.it.OpenCybeleStrictIT cz.vutbr.fit.ags.parity.it.OpenCybeleCapacityIT
cz.vutbr.fit.ags.parity.it.OpenCybeleCongestionIT cz.vutbr.fit.ags.parity.it.OpenCybeleLifecycleIT
cz.vutbr.fit.ags.parity.it.OpenCybeleTimersIT
X
)" \
  .github/scripts/parity-junit.sh jade-status \
      build/test-results/characterizationIT .github/parity/jade-status.expected
```

Measured on a cold clone with an empty local Maven repository and `DISPLAY` unset: bootstrap
instant, `installDist` ~5–6 s, the suite ~19 s, 31 tests, 0 skipped, `OpenCybeleSmokeIT` passed.
Both installer paths inside the bootstrap were exercised — with Maven on `PATH` (3.9.16, the
`mvn install:install-file` route the hosted runner takes) and without it (the built-in copy route) —
and the full suite is green after either.

---

## 10. `characterizationIT@jade` — the port guard, and what "green" can honestly mean

> Job: **`characterizationIT@jade`** · Issue [#40](https://github.com/bedaHovorka/OpenCybele1/issues/40)
> · Judge: `.github/scripts/parity-junit.sh jade-status`
> · Recorded measurement: [`.github/parity/jade-status.expected`](../.github/parity/jade-status.expected)
> · The measurement behind it: [`parity-triage-jade.md`](parity-triage-jade.md)

### 10.1 One checkout, not two

The `@opencybele` job needs two checkouts because decision #11 put the harness and the baseline on
different branches. The JADE arm does not: the harness and the JADE implementation are the **same
tree**. So this job is one checkout, `installDist`, `-Pjade.dist`. There is no `jade.home` either —
`OpenCybeleLauncher` needs a second directory only because Cybele reads `cybelle/*.prop` through
`--patch-module`, and the JADE implementation reads no such file.

It still runs `scripts/bootstrap-vendor-jars.sh`. `src/main` has had zero `cybele.*` references
since #35, but `build.gradle.kts` still declares `com.iai:cybele-api`/`cybele-impl` on
`implementation`, so both jars resolve and land in `installDist`'s `lib/`. Dropping them is 1-POST
cleanup and was deliberately **not** done in #40: the child JVM's `-cp` is part of the command line
every measurement in `parity-triage-jade.md` was taken under, and this lane's margins are 3–9 ms
wide. That is not a classpath to change as a side effect of a CI ticket.

### 10.2 Why "all five green" is not available

[#39](https://github.com/bedaHovorka/OpenCybele1/issues/39) measured the port against the frozen
goldens, 20 runs per scenario per implementation, and classified the residue **(b) normalizer gap,
not closable**. Four facts decide the shape of this job:

* The JADE run emits the **same event multiset** as the 2008 application in **all five scenarios**.
  Every remaining difference is burst placement.
* Pass rates are **load-dependent**, and that is the diagnosis showing through rather than noise.
  Three corpora read `capacity` 6/20, 15/20 and 12/12; `congestion` 3/20 and 7/12; `strict` **0/N
  in every corpus**; `lifecycle` and `timers` 20/20 and 12/12.
* The margins against the 220 ms burst width rank the rates exactly: `capacity` 118 ms,
  `congestion` 9 ms, `strict` 0 ms.
* **Under concurrent load the baseline fails its own `strict` golden 3/20**, at the same line and
  in the same direction. The instrument does this to the reference implementation too.

So a job demanding five green scenarios would be red forever, and a job that is always red teaches
people to ignore CI — which is the exact failure this project already paid for once (#78/#79: CI was
red on a stale rule for the project's entire history and four PRs merged without anyone reading it).

The check is **not weakened** to escape that. `strict` is not skipped, the contract level is not
lowered, no golden is touched and there is no retry-until-green.

### 10.3 What is asserted instead: the recorded verdict class

Not the pass **rate**, which the measurement shows is a property of the machine. The per-scenario
**verdict class**, which the measurement shows is stable. `parity-junit.sh` derives it from the
failure anatomy `DiffAnatomy` already appends to every golden diff:

| Verdict | Meaning |
|---|---|
| `MATCH` | byte-identical to the golden |
| `ORDER` | failed, **same multiset** — the golden's events in a different burst order (T-05/T-06) |
| `QUANTUM` | failed, and the *only* multiset difference is T-07: `VOTE.diff` straddling the 1 000 ms `TIME_QUANTUM` bucket. Granted only when every surplus line carries a `diff=<T~` and the two sides cancel exactly |
| `BEHAVIOUR` | failed with any **other** multiset difference — a dropped or invented event |
| `OTHER` | not a golden comparison at all: an agent died, the run missed its simulated-time bound, the harness threw |
| `SKIP` | assumed away |

and compares it against `.github/parity/jade-status.expected`:

```
opencybele-lifecycle    MATCH
opencybele-timers       MATCH
opencybele-capacity     MATCH ORDER
opencybele-congestion   MATCH ORDER
opencybele-strict       ORDER QUANTUM
```

Three properties follow, and they are why this is not a way of hiding a regression:

1. **`BEHAVIOUR` is permitted nowhere, and cannot be permitted** — the judge grants no scenario
   `BEHAVIOUR` whatever the file says. A dropped or invented event fails all five, *including the
   three that are allowed to fail*. The claim every corpus supports — "no run has ever produced an
   event the golden did not record, or missed one it did" — is the thing actually being guarded.
2. **A change in either direction is red.** `opencybele-strict` does not list `MATCH`: it is 0/N in
   every corpus ever taken, so a `strict` that suddenly passes fails this job. That is deliberate —
   it means the pinned measurement has gone stale, and it is as much a signal as a `lifecycle` that
   starts failing.
3. **`OTHER` and `SKIP` are permitted nowhere.** Before #40 `JadeParityIT` had been in the tree
   since #36 and had **never run on a runner** — it skipped in the only job that existed, because
   that job supplies no `-Pjade.dist`. A lane that reports on nothing is the same class of defect as
   a test that cannot fail, and `SKIP` being unpermitted is what keeps it from returning.

### 10.4 The cost, stated rather than hidden

**This pins today's measurement.** It lives in [`parity-triage-jade.md`](parity-triage-jade.md) §1
(the three corpora) and §4.4 (the margin table), plus PR #80's two measurement comments, and the
corpus tables are reproduced in the header of `jade-status.expected` itself.

Re-measuring is **not** a CI action. It is ~20 runs per scenario on a quiet machine:

```bash
./gradlew installDist
./gradlew parityGate -Pjade.dist=$PWD/build/install/opencybele \
          -Dparity.gate.runs=20 -Dparity.gate.scenario=opencybele-strict
```

and those numbers are what a proposed edit to the expectations file has to argue against. Editing a
row to make a build green, without a corpus behind it, is the failure the whole mechanism exists to
prevent.

### 10.5 Gradle's exit status is deliberately not the verdict

`opencybele-strict` fails its golden on essentially every run **by design**, so
`./gradlew characterizationIT -Pjade.dist=…` exits non-zero on essentially every run. The run step
records that status into the job summary and does **not** propagate it; the asserting step is the
only thing that can turn this job red. That inverts this file's usual "trust Gradle, then assert
more" shape, and it is the one place in the repository where it is inverted.

### 10.6 The retry, and why it is narrower than §4's

A retry is legitimate only against a failure mode that has been **measured** to be a transient, and
only if it is incapable of laundering the signal the job exists to carry. Both conditions are
enforced by `parity-junit.sh jade-retryable`:

* **The measured transient is T-12** ([`parity-triage-jade.md`](parity-triage-jade.md) §4.8): a
  cluster of slow JVM starts shifting the whole schedule against an *absolute* stop bound, changing
  which events fall inside it. #39 chased it, found the schedule shifted rather than recomputed, and
  could not reproduce it in a second corpus. It is the only difference in that entire campaign that
  was not a reordering, and a loaded shared runner is its worst case. It surfaces as `BEHAVIOUR`.
* **A retry cannot hide a status change.** `jade-retryable` is false for an unexpected `MATCH`
  (`strict` going green is the thing this job is here to report), false for `OTHER` (deterministic),
  and false for any failure outside the scenario class (in-process harness tests). A retry here can
  only ever *discover* a red, never convert one into a green it was not owed.
* **Two consecutive `BEHAVIOUR` diffs is not a transient.** That is red, and it means the port
  dropped or invented an event.

Every retry is loud: a `::warning`, a line in the job summary, and both attempts' reports kept as an
artifact.

### 10.7 The runner, the CPU-throughput floor, and what it does to *both* arms

A CI runner is the worst case for a latency-keyed comparison — #39's own corpora move by 2–4× with
machine load. That is exactly why `capacity` and `congestion` are recorded as permitting `MATCH`
*or* `ORDER`: a single run on a contended runner cannot distinguish those two as a signal, and
pinning either would be pinning the runner rather than the program. **Do not read this job's greens
as evidence of an idle-machine pass rate.** It is not measuring one, and the rate it would measure
is not the number in the triage log.

**The floor was measured rather than assumed**, because "the runner is loaded" is a claim with a
number attached. On the developer machine (24 cores), varying only the CPU budget:

| CPU budget | `characterizationIT@jade` verdict | What the traces look like |
|---|---|---|
| 24 cores, quiet | **green 3/3** | `strict` alternates `ORDER`/`QUANTUM`, `capacity` `MATCH`/`ORDER` — every verdict inside its recorded status |
| **4 dedicated cores** (`taskset -c 0-3`), quiet | **green 5/5** | `strict` `QUANTUM`×3 then `ORDER`×2; everything else `MATCH` |
| 4 cores at ~50 % steal (4 spinners pinned alongside) | **red 3/3** | `strict`/`capacity` `BEHAVIOUR`, `timers` `ORDER` |
| 24 cores at 100 % steal (24 spinners) | **red 4/4** | same, worse |

**The failure mode below the floor is truncation, not misordering.** `opencybele-strict` produced
575 lines against the golden's 598, `opencybele-congestion` 157 against 167. Every scenario is
bounded by an *absolute* `sim.stop.maxClockMs` at pace 8, so a machine that cannot keep up with
simulated time reaches the bound having emitted fewer events. That surfaces as `BEHAVIOUR` — a
multiset difference — which is exactly what a port that dropped an event would look like.

**And it is not a property of the port or of this job.** Under the same 24-spinner starvation, the
existing drift guard `characterizationIT@opencybele` **also fails, 3/3 runs**: the *baseline* stops
matching its own goldens (`OpenCybeleStrictIT`, `OpenCybeleCongestionIT`, `OpenCybeleLifecycleIT`,
`OpenCybeleSmokeIT`, in various combinations). The whole L3 lane has a CPU-throughput floor, both
arms of it, and it predates #40. §4.7 of [`parity-triage-jade.md`](parity-triage-jade.md) is the
same observation from the other end.

**What #40 does about it, and what it deliberately does not.**

* It does **not** try to auto-distinguish "the machine could not keep up" from "the port dropped an
  event". There is no fingerprint that separates them — #39 established that by hand across two
  corpora before it could clear T-12 — and inventing one on a CI ticket, with no corpus behind it,
  is the kind of unmeasured mechanism this project rejects. A short run is still `BEHAVIOUR` and
  still **red**.
* It **does** make that red diagnosable in one step. When a `BEHAVIOUR` verdict comes with a run
  shorter than its golden, `jade-status` prints a `HINT:` line naming the truncation, the absolute
  bound that causes it, and the boundary table above — so a triager checks the runner before
  checking the port. Measurement only; it changes no verdict.
* The narrow retry (§10.6) covers a *transient* starvation spike and nothing more. Sustained
  starvation is deterministic, so it fails both attempts and goes red — which is correct: a CI
  machine that cannot run this lane should be visible, not hidden.

**What is not settled locally.** A GitHub-hosted `ubuntu-24.04` runner is a dedicated VM with a
small, fixed vCPU count and no competing tenant, which is the *4-dedicated-cores* row — the row that
is green 5/5. But that is a model, not a measurement of the actual runner, and only a real run
settles where a hosted runner sits on this table. If the first runs come back red with `HINT:` lines
about short runs, the answer is not to edit `jade-status.expected` — it is that the runner is below
this lane's throughput floor, and that is a fact about the runner worth having explicitly.

---

## 11. `build@this-ref` — the L1 lane

Added by [#79](https://github.com/bedaHovorka/OpenCybele1/issues/79). `./gradlew build` on the
triggering ref: compilation, the 321-test `test` lane, `domainPurity`, and compilation of the
`integrationTest` and `characterizationIT` source sets (both are wired into `check` for compilation
only, so a test that stops compiling still fails the build).

It carries one addition from #40: **`parity-junit.sh assert-suite`, with a floor of 321.** A Gradle
`Test` task that discovers *zero* tests exits 0 and writes no reports, and one whose
`testClassesDirs` stopped matching writes a handful and exits 0 too — the same "a green exit is not
a good run" hazard §3 documents for the parity suite, in a different lane. The floor is a floor, not
a pin: adding tests never breaks it, losing them does.

## 12. `integrationTest@this-ref` — the L2 lane

Added by #40. `./gradlew integrationTest`: 17 tests across 8 classes, each booting a **real
in-process JADE main container** and starting the real `Station`/`RoadAgent`/`Train`/`TraceProbe`
inside it ([`TESTING.md`](TESTING.md) §4.2, #38).

Its own job rather than a step in `build@this-ref`, because it is its own lane with its own fixture
and a red here means something different — and because `check` deliberately compiles this source set
without running it, so that `./gradlew build` stays a build.

**Is it safe on a hosted runner?** Checked rather than assumed:

* **The port is ephemeral, not fixed.** `ContainerFixture` sets `Profile.MAIN_PORT = "0"`, so the
  kernel assigns a free port per container and nothing binds JADE's default 1099. It also sets
  `Profile.MTPS = ""` — no external message transport, so no HTTP MTP on 7778 either — and
  `Profile.GUI = "false"`. A hosted runner is a single-tenant VM, so even a fixed port would have
  been fine; an ephemeral one means it is fine on a self-hosted runner too.
* **`forkEvery = 1` means 8 JVMs for 8 classes, sequentially** (`maxParallelForks = 1`). That is the
  lane's fixture, not a tuning knob: `TraceProbe.READY` is a latch counted down once with no reset
  hook, and `jade.core.AID.platformID` is overwritten by every container boot, so one JVM per class
  is what makes `ProbeReadyTimeoutIT` and `ProbeFailureCapIT` testable at all. Measured locally at
  **26 s** for the whole lane.
* **`jade.file.dir` is absolute**, under `build/integration-test/jade`. JADE's AMS concatenates that
  value with `APDescription.txt` rather than resolving it, and #36 lost a gate run to a relative
  one. Nothing is written outside the workspace.
* **The per-class JUnit timeouts are 180 s** (`ContainerFixture`) **and 60 s** (the two probe
  classes), against a 3 s worst class locally. Those are the numbers to look at first if this lane
  ever goes red on a slow runner rather than on a real failure.

**What was not verified locally**: whether a runner's slower, contended CPU pushes any class near
those timeouts. Everything above is a property of the configuration, read off the code and a local
run; the timing headroom is not, and only a real run settles it.

## 13. The skip accounting is per job

[#78](https://github.com/bedaHovorka/OpenCybele1/issues/78) fixed a stale zero-skip rule by
exempting one class **by name and by count**: `ParityGateIT` is allowed to skip only because *all*
of it is opt-in, and a `ParityGateIT` that skipped 3 of its 5 still fails, because then two of them
found what they needed and ran.

That bar is kept; the constant became a per-job input, because two facts forced it:

* Run `32548100423` on `jade-develop` went red with *"5 test(s) were skipped outside
  ParityGateIT"* — and the rule was **working**. The five were `JadeParityIT`, which assumes itself
  away in the opencybele job because that job supplies no `-Pjade.dist`.
* The mirror image is structural: the JADE job supplies no `-Popencybele.dist`, so the six
  `OpenCybele*` scenario classes skip *there*. **Each arm's scenarios are the other arm's expected
  skips** — which is also the empirical answer to "why can the JADE lane not just be a sixth
  scenario in the existing job".

So the list lives in the workflow's `env:` block, one per job
(`OPENCYBELE_EXPECTED_SKIPS`, `JADE_EXPECTED_SKIPS`), and is passed to the script as
`PARITY_OPT_IN_CLASSES`. The all-or-nothing rule is applied to **every** class in the list.

Three environment variables configure the script per job, and nothing else does:

| Variable | Meaning | Default |
|---|---|---|
| `PARITY_OPT_IN_CLASSES` | classes that must skip **entirely** in this job | `ParityGateIT` (i.e. #78's behaviour) |
| `PARITY_REQUIRED_CLASS` | the class that must have **run** (`assert-ran`), and the only class a failure is tolerated in (`jade-status`) | `OpenCybeleSmokeIT` |
| `PARITY_MIN_TESTS` | floor on a lane's total test count (`assert-suite`) | `1` |

## 14. Cloning this for `@jason`

[#52](https://github.com/bedaHovorka/OpenCybele1/issues/52) wants the same against a third
implementation. After #40 the structure is meant to be copied from **`characterizationIT@jade`**,
not from `@opencybele`, since Jason will also live in one tree. What changes:

* `-Pjade.dist=` → the new adapter's dist property, and `installDist` if the layout differs;
* `PARITY_REQUIRED_CLASS` → the new adapter's scenario class;
* a third `*_EXPECTED_SKIPS` list in `env:` — and the two existing lists each gain the new
  adapter's scenario class, since it will skip in both existing jobs;
* a `jason-status.expected` of its own. **It must be measured, not inherited.** Jason's message
  latency will be a third answer again ([`parity-triage-jade.md`](parity-triage-jade.md) §10.3), so
  its verdict classes are an open empirical question — copying JADE's rows would be pinning a
  measurement nobody took;
* the retry rationale, which is T-12-specific and should be re-derived, not inherited — exactly as
  §4's DEF-22 rationale was not inherited by §10.6.
