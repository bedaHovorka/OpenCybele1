# Continuous integration

> Workflow: [`.github/workflows/characterization-opencybele.yml`](../.github/workflows/characterization-opencybele.yml)
> · Job: **`characterizationIT@opencybele`**
> · Issue: [#25](https://github.com/bedaHovorka/OpenCybele1/issues/25)
> · Requirement: [`Phase1.md`](Phase1.md) 1-PRE.4

This is the repository's first workflow. It runs the L3 parity suite
([`TESTING.md`](TESTING.md) §6, [`parity-harness.md`](parity-harness.md)) against the frozen
OpenCybele baseline, so that behavioural drift in the baseline is a red build rather than a
mysterious golden diff discovered halfway through the port.

There is exactly one job, and it is deliberately narrow: it does **not** run `./gradlew build` or
`check` on either branch. Those are other issues' business. This job answers one question — *does
the frozen application still behave the way the goldens say it does?*

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

Measured on a cold clone with an empty local Maven repository and `DISPLAY` unset: bootstrap
instant, `installDist` ~5–6 s, the suite ~19 s, 31 tests, 0 skipped, `OpenCybeleSmokeIT` passed.
Both installer paths inside the bootstrap were exercised — with Maven on `PATH` (3.9.16, the
`mvn install:install-file` route the hosted runner takes) and without it (the built-in copy route) —
and the full suite is green after either.

---

## 10. Cloning this for `@jade` and `@jason`

[#40](https://github.com/bedaHovorka/OpenCybele1/issues/40) and
[#52](https://github.com/bedaHovorka/OpenCybele1/issues/52) want the same job against the other two
implementations. The structure is meant to be copied verbatim; what changes is small:

* the second checkout's `ref` (`jade` / `jason` instead of `opencybele-baseline`);
* the vendor-jar steps, which only OpenCybele needs;
* `-Popencybele.dist=` → the new adapter's dist property;
* `SMOKE_CLASS` in `parity-junit.sh` → the new adapter's end-to-end class (or pass it in, if by
  then more than one job shares the script);
* the retry rationale in §4, which is DEF-22-specific and should be re-derived, not inherited.
