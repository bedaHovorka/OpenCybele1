# Defect triage — pin, project, or carve out, before the goldens are recorded

> **Issue #22.** Input to **#24** (golden recording) and **#39** (parity-diff triage).
> Branch: `jade-develop`. **No application source is changed by this document.**
>
> The [scope guard](Phase1.md) freezes behaviour and declares that *every golden diff is a port
> bug*. That is only a usable rule if the baseline's own defects are classified **before** the
> goldens exist. Otherwise #39 spends the port budget rediscovering 2008.
>
> Companion evidence, all on `origin/opencybele-baseline` unless noted:
> `docs/INVENTORY.md` (**this branch** — DEF-01..DEF-24, SEM-01..SEM-06, NDT-01..NDT-05),
> `seeded-rng.md` (#15), `headless-and-stop.md` (#17), `kernel-config.md` (#16),
> `trace-format.md` (#20), `assertion-triage.md` (#14), `iteration-order.md` (#19),
> `scenario-config.md` (#18).
>
> **Supersedes `docs/INVENTORY.md` in two places.** **DEF-08 is de-claimed** — it is not a defect, and "fixing" it leaks station occupancy on every arrival (§4.2). **DEF-18**'s capacity threshold is >= 1, not >= 2, and is now enforced at startup (§4.4). Everything else in `INVENTORY.md` stands.

---

## 1. The classification scheme

Three classes. Every row below carries exactly one.

| | Class | Meaning | How it is handled |
|---|---|---|---|
| **(a)** | **deterministic** | Same inputs, same result, every time. The port **must** reproduce it. | Pinned. Preferably by an **L1 unit test on extracted domain logic (#28)** — not by hoping a scenario happens to exercise it. A scenario that exercises it *today* may stop doing so after any retune. |
| **(b)** | **nondeterministic, but bounded** | Varies run to run in a way that is **projectable** (a value that can be normalised away) or **avoidable** (a region the scenario need not enter). | Projected by **#21's normalizer**, or excluded by scenario choice. Named explicitly so #21 knows its full job. |
| **(c)** | **unstable / carve-out** | Cannot be pinned at all, and cannot be projected away either — typically because the failure **removes** content rather than perturbing it, or because it never reproduces on demand. | Declared **out-of-contract**. #39 must not triage it as a port bug; the prescribed response is *re-run the baseline*, not *blame the port*. |

**Why the (b)/(c) line is drawn where it is.** Reproducibility here has **two scales**. Within one
JVM session, runs mostly agree; across sessions the outcome shifts to a *different stable mode*.
Measured: 114 consecutive headless runs at one seed gave **one** departure-id sequence, and a later
session gave a second sequence in **7 of 9** runs — same binary, same files
(`kernel-config.md:99-118`). The same shape was reproduced independently as three runs uniformly on
one branch and three later runs uniformly on the other (`trace-format.md:577-584`), and again on a
third axis: **two sessions of three runs each gave 4 distinct id-sets over 6 runs**, with
within-session pairwise differences of **0–1 ids** against across-session differences of **10–13**,
and a marker id departing in every run of one session and no run of the other (coordinator,
during this triage session — raw data and protocol in §7.4(i)).

> **Consequence, and it governs this whole document: consecutive runs cannot validate a scenario.**
> Three green runs in a row are the *expected* output of a stable mode, not evidence of
> determinism. A defect that is invisible within a session and switches between sessions is **(c)**,
> however clean the session looked.

### Evidence standing

Each row says **measured** or **inferred**, and by whom. `#N` credits the issue whose work produced
it. **`[this issue]`** marks something re-measured or newly established while writing this document;
those are listed in full in §7.

---

## 2. Merge map — the issue's eleven items against `INVENTORY.md`'s DEF ids

The issue body and `docs/INVENTORY.md` were written independently and overlap. This is the
reconciliation; **the DEF id is canonical** from here on.

| Issue item | DEF id | Note on the merge |
|---|---|---|
| 1 · Lost `START` | **DEF-02** | Same defect, same line. |
| 2 · No timeout on the vote latch | **DEF-22** (+ **DEF-09**, **SEM-06** as its two triggers) | The issue names the latch; DEF-22 adds the measured consequence and rate. |
| 3 · Time-dependent `PriorityQueue` comparator | **— retracted —** | **Not a defect.** See §4.1. The real defect at that site is DEF-03. |
| 4 · Dead tie-break | **DEF-04** | Same. |
| 5 · `long`→`int` truncation at `Planning.java:148` | **DEF-03** | *Merged.* DEF-03 already covers both sites (`RoadAgent.java:211` and `Planning.java:148`) as one pattern. One row, two sites. |
| 6 · Unbounded per-train leak | **DEF-10** | Same. |
| 7 · Unbounded negative jitter | **DEF-16** | Same. |
| 8 · NPE on unboxing inside the comparator | **DEF-06** | Same. |
| 9 · Reference equality on a `String` | **DEF-11** | Same. |
| 10 · `lastKey()` on a possibly-empty timetable | **DEF-18** | Both agree it is an **invariant, not a defect**. Refined in §4.4. |
| 11 · Aliased, mutating message payload | **DEF-13** | Same. This is also the source of the `STATION_INFO.occupied` residual (§4.3). |

DEF ids with **no** corresponding issue item, carried in anyway: DEF-01, DEF-05, DEF-07, DEF-08,
DEF-12, DEF-14, DEF-15, DEF-17, DEF-19, DEF-20, DEF-21, DEF-23, DEF-24.

### Line numbers

Rows cite **`jade-develop`** line numbers, matching `docs/INVENTORY.md` and this branch's source.
The `opencybele-baseline` branch has since inserted `ScenarioConfig`, `RunControl`, `SimRandom` and
`TraceProbe`, so its lines have drifted (`assertion-triage.md` warns about this twice and asks to be
cited **by content, not by line**). Where a row matters, the *expression* is quoted so it can be
found on either branch.

---

## 3. The triage table

### 3.1 Class (a) — deterministic; the port must reproduce it

| id | Site (`jade-develop`) | What it does | Evidence | How it is pinned | If it shows in a diff (#39) |
|---|---|---|---|---|---|
| **DEF-03** | `RoadAgent.java:211` `(int)(diff(time)-o.diff(time))`; same pattern `Planning.java:148` `(int)(departure-o.departure)` | Narrows a millisecond `long` difference to `int`. Ordering inverts, or collapses to "equal", past the `int` range. | **Measured [this issue].** Sign survives to \|Δ\| = 2³¹ ms exactly; the first **inversion** is at 2³¹+1 ms; the result **zeroes** at 2³² ms. 2³¹ ms = **24.855 simulated days**. | **L1 unit test on the extracted comparator (#28)** — assert the inversion at 2³¹+1 and the zeroing at 2³². **Unreachable in any golden scenario**: departure and timetable deltas are seconds to minutes of simulated time. | It **cannot** appear in a golden diff at scenario scale. If a port's ordering differs on a small delta, the port changed the comparator — not this defect. |
| **DEF-04** | `RoadAgent.java:222` (in `frequency`, `:219-225`), used at `:213` | `i.equals(position)` compares an `OueueItem` to a `String`. `OueueItem` overrides neither `equals` nor `hashCode`, so this is `Object.equals` — reference equality across unrelated types. Always `false`; `frequency` always returns `0`; the documented "then by number of requests from that direction" tie-break at `:213` is **dead code returning a constant 0**. | **Measured statically [this issue]:** `grep equals\|hashCode RoadAgent.java` shows no override in `OueueItem`; a non-overriding `Object.equals(String)` returns `false` (executed). | **L1 unit test (#28)**: `frequency(q, p) == 0` for a non-empty `q` containing an item whose `position` is `p`. | If a port's road queue breaks a `diff` tie by direction frequency, **that is a port bug** — the baseline's tie-break is inert, and equal-`compareTo` items fall through to `PriorityQueue`'s heap order. |
| **DEF-07** | `Train.java:82-84` | `leaveObject(position)` is sent from `entered`, i.e. **after** the new object already incremented its occupancy in `Station.enter` (`Station.java:93`). For the width of the overlap both objects count the train, and admission decisions run against the inflated number. | **Measured [this issue]** in a `sim.trace.enabled=true` run: `ENTER_REPLY` from the new object and `LEAVE` to the old object carry the **same tick**, `ENTER_REPLY` first. The *mechanism* is deterministic — it happens on every hop. | Pin the **order**, which is deterministic and trace-visible: for every hop, `ENTER_REPLY|<new>` precedes `LEAVE|<old>`. Do **not** pin the resulting `occupied` number — that half is **(b)**, see DEF-13. | An `ENTER_REPLY`/`LEAVE` pair in the other order is a port bug. A different `occupied` count is **not** — it is projected. |
| **DEF-11** | `Train.java:61` `(mess == KILLED)` | Reference comparison of `String`s. | **Measured statically [this issue]:** `KILLED = "KILL"` is a compile-time constant; the sole caller passing it (`destroy()`, `Train.java:125`) passes that same interned literal, so `==` is `true`; every other caller passes a computed concatenation, so `==` is `false`. Behaviour is **identical to `.equals`** today. | **L1 unit test (#28)** on the extracted `sendStatusMessage` mapping. | It can **never** show in a diff: a port using `.equals` produces a byte-identical trace. This row exists so the ports know the idiom is a **trap**, not a behaviour. #32 may drop it freely. |
| **DEF-12** | `RailwayObject.java:27-31`, called from `PathFinding.java:35` | `getName()` reads the **thread-context** `Agent.getAgentId()`, so it returns the *caller's* agent name, not the receiver's. | **Inferred** (#9), from the call graph: correct today only because ACT-04 (`PathFinding`) is an activity of the *same* agent as the station it reads. | Record as a **load-bearing invariant**: any cross-agent `getName()` call silently returns the wrong name. Nothing to pin in a trace — it is correct today. | N/A today. A port that reifies `getName()` as a field is *more* correct and still trace-identical. |
| **DEF-14** | `RoadAgent.java:100` | `traveledTrain` is a single slot overwritten by every `travelStart`; `travelEnd` (`:113-116`) notifies whatever is in it. | **Measured** (#14): safe today because the road is single-occupancy — the guard `assert state != State.FREE` (`RoadAgent.java:151`) was evaluated **160×** and held, as was `assert traveledTrain != null` (`:114`), **160×**. | Record as an invariant: **one train per road segment**. Pinned indirectly by the `ROAD_STATE` stream. | A port that permits two trains on one road is a port bug regardless of what the golden says. |
| **DEF-16** | `RoadAgent.java:102` (TMR-04): `delayInSeconds() + (long)(500*nextGaussian())` | Travel delay goes **negative** for 1 s roads. Cybele fires a negative-delay timer **immediately** ⇒ an *instantaneous traversal*. Not a lost train, not an error. | **Measured** (#9): `delay=-1500` → callback in **1–5 ms**; control `delay=2000` → **2001–2010 ms**. Rate **measured** (#15): 0.02263 over the seeded streams vs analytic 0.0228. **Re-measured [this issue]**, 2×10⁶ draws: **2.2645 %** for `tr1`/`tr2`. | Pinned by the seed — `sim.random.masterSeed` reproduces the same negative draws at the same positions in each road's stream (#15). Also L1-testable (#28) on the extracted delay expression. | An instantaneous traversal in the golden is **correct**. A port that clamps to 0 produces a diff and **that is the port bug**. |
| **DEF-08** | `Train.java:123-126` | **DE-CLAIMED — `INVENTORY` DEF-08 is SUPERSEDED, see §4.2.** `INVENTORY` records a "second `LEAVE` for the final station". It is not one: `entered` leaves the **old** position, `destroy` leaves the **current** one. | **Measured [this issue]**, one 66-train traced run: **199 `LEAVE` records, zero `(train, object)` pairs with more than one.** | Nothing to pin as a defect. Pin the **shape**: the `destroy()` `LEAVE` is what *balances* the destination station's `occupied++`, and a port that drops it leaks occupancy forever. | A completed train **must** emit a final `LEAVE` to its destination immediately before `TRAIN_STATE state=KILL`. Its absence is a port bug. |
| **DEF-19** | `RoadAgent.java:190` | Class-name typo `OueueItem`. | Static, trivial. | Nothing. Load-bearing only for grep-based refactoring. | N/A. |
| **DEF-20** | `RailwayMainAgent.java:155,166,178`, bound by string literal at `:119`, `:128`, `Generator.java:59` | Handler names misspelled (`recieve…`) and bound reflectively **by string**. Renaming the method without the literal fails **silently at runtime**. | Static (#9). | Record as an invariant. Nothing observable today. | N/A. A port that renames them correctly is trace-identical. |

### 3.2 Class (b) — nondeterministic, projected or excluded

| id | Site | What varies | Evidence | How it is projected / excluded | If it shows in a diff (#39) |
|---|---|---|---|---|---|
| **DEF-13** | `Station.java:39,44,67` vs `:93`,`:119`; `RailwayMainAgent.java:62`; SEM-05 | `Station.Info` is a non-static inner class shipped **by reference** under `Local;NoSerialization`. What arrives at a subscriber is a **live alias**, mutated afterwards by the station's own thread. The observable consequence is `STATION_INFO.occupied`. **This is issue item 11.** | **Measured** (#20), three probe-on runs at one pinned seed: after projecting every clock-derived family away, the **only** remaining content differences are `STATION_INFO` lines whose `occupied` differs — **10, 12 and 6 lines** across the three pairings — and *the counts trade in lockstep between adjacent values on the same station* (e.g. `stA occupied=0` seen 14/13/13 against `occupied=1` seen 11/12/12). Verdict quoted: "**the value is a race, not a timestamp**". | **`occupied` is a sixth run-varying family for #21 and must be projected**, or busy stations kept out of a strict contract. `capacity` stays — it is config. | **Never** a port bug. Answer to the issue's explicit question — *is the aliasing observable, and if so how is it reproduced?* — **it is observable, and it is not reproducible; therefore it is normalized away, not pinned.** JADE/Jason snapshot semantics are consequently *inside* the contract. |
| **NDT-05 / clock** | five families, per `trace-format.md:239-245` | `tick` (field 2, every line); `VOTE_REQUEST.expected`; `VOTE_RESULT.planned`; `VOTE.diff`; and the application's own `"<train> in <station> at <n>"` `println`. All derive from a wall-clock-driven simulated clock. | **Measured** (#20): projecting all five takes positional line differences between two runs from **3601 to 1185** — an order of magnitude, **and not to zero**. | #21's core job. The residual after projection is order-within-a-shared-tick (**57–63 of 66** per-train projections still differ by line order) plus `occupied` above. | A different timestamp is never a port bug. A different **order** at a shared tick is #21's problem, not the port's. |
| **agent init order** | `Cybele.createAgent` (async) | The order in which agent **constructors** actually run — hence the order of the opening `STATION_INFO`/`ROAD_STATE` block. | **Measured** (#15, `seeded-rng.md:79-87`): "**six distinct orders in six runs**". #19 pinned only the order in which create *requests* are issued (`iteration-order.md`); `trace-format.md:111-112` independently records that "the eight opening `STATION_INFO` lines are emitted in a different order every run". | **Requirement levied on #21 by this document:** the normalizer must **sort the opening state block by agent name**. This is *not yet done* — `trace-format.md` names the problem and hands it on; grep confirms no sorting of the startup block exists today. | A different startup order is never a port bug. If #21 has not sorted it, the diff is a **harness** bug. |
| **DEF-02 tail** | `Planning.java:126` at the `sim.stop.maxClockMs` boundary | The **last** departure or two are ragged: a train planned but not yet departed when the bound hits simply never prints. | **Measured** (#20): an independent replication at n=8 per arm saw two id sets differing **only in the 21st and last departure** (`vl19 in stB` against `vl50 in stC`), split 3-of-8 / 5-of-8, arm-independent. | **Excluded by scenario choice**: the golden must stop short of the boundary, or drop the tail. The contract compares **departure ids**, never line counts. | A missing *last* departure is out-of-contract. A missing *interior* departure is DEF-02 proper — class (c) below. |
| **DEF-24** | `RailwayMainAgent.java:287-288` | `TableModel.update` iterates `trainStates.entrySet()` **off-lock** on the EDT while agent threads mutate the `synchronizedMap`, then caches live `Map.Entry` views whose behaviour after `remove` is undefined. | **Inferred** (#9) from the code; **never observed** — 0 `Exception in thread "` across **146** measurement runs (`kernel-config.md:87-88`). | **Excluded by scenario choice**: goldens are recorded with `sim.headless=true`, where `RailwayCanvas` is never constructed and the EDT does almost nothing. The `TableModel` still updates, so the hazard is reduced, not removed. | A `ConcurrentModificationException` on the EDT is out-of-contract — discard the recording. It is not a port bug, and JADE ports will not have a Swing `TableModel` at all. |

### 3.3 Class (c) — unstable; out-of-contract carve-outs

> These are the rows #39 must read before filing anything. **Every one of them exits 0.**

| id | Site | What it does | Evidence | Why it cannot be pinned | What #39 must do |
|---|---|---|---|---|---|
| **DEF-02** | `Planning.java:126` — the author's own `//BUG ne vzdy se doruci` | `Activity.sendAll(START+train, …)` may reach the channel **before** the `Train` agent has executed `Activity.openChannel(START+…)` at `Train.java:54`. Per **SEM-06** such a send is **silently dropped**, and the train never starts. **Issue item 1.** | **Measured** (#15), `short.properties` at 45 s, three runs at one seed: 80 departures each, **departure-id sets differing by 12, 10 and 2 ids**, with the generator stream identical (same highest id `vl663`, origins agreeing for all 74 shared ids). **Measured** (coordinator, `kernel-config.md:174-176`), three headless runs at ~69 departures: pairwise id-set differences of **0, 1 and 1**, the single difference being **`vl550` missing from one run entirely**. **Measured** (coordinator, during this triage session — §7.4(i)), two sessions of three runs each over a common simulated window: within-session pairwise id-set differences **0, 0, 1**; across-session **10 and 13**; **4 distinct id-sets over 6 runs**, the mode constant within a session and flipping between them. | **A dropped train is ABSENT, not reordered.** No normalisation recovers a line the run never printed. And it is **not a density cliff** — the model is a *stochastic drop with no threshold*: "Density raises the rate; it does not switch anything on." (`kernel-config.md:179-181`). Scenario choice suppresses the rate; **no arrival rate removes the hazard**. | **Do not file.** Re-run the baseline recording. Compare **departure-id sets**, across **separate sessions**. A train present in the port and absent in the golden (or the reverse) is a baseline artefact until a cross-session re-record says otherwise. |
| **DEF-22** | `Planning.java:88` `latch.await()` — no timeout | The latch is sized `path.size()` at `:74` and counted down **only** from `VoteCollecting.java:54`. Lose one `countDown()` and ACT-01 hangs **forever**; no further train is planned for the rest of the run while `Generator` keeps creating `Train` agents that never start. **Issue item 2. The most severe latent failure in the codebase.** | **Measured** (#16): **2 hangs in 90 runs (~2 %, roughly 1 in 45)**, *at the stock configuration* — precision explicitly disclaimed ("2 in 90 is an estimate from a sample that was not designed to measure it"). **Silent**: clean exit `0`, and **zero** `AssertionError` / `Exception in thread "` across **146** measurement runs. Two proven triggers: a `VOTE_REQUEST` dropped by SEM-06, and DEF-09's late-vote NPE. | It is invisible to every cheap detector. **`sim.stop.stallMs` structurally cannot catch it**: `lastTrainNanos` is stamped from `RunControl.trainGenerated` (`RunControl.java:443`), whose sole caller is `Generator.java:84` — and `Generator` is fire-and-forget, so it keeps running while `Planning` is wedged. The two activities do **not** head-of-line-block each other: `Agent.createActivity` passes `ConcurManagement.CONCURRENT`, and `IAIConcurManagement.getRunnable` **list-iterates** and returns the first *runnable* node, skipping a blocked one (**proved in bytecode**, #16 — explicitly *not* an appeal to SEM-04, which covers only seriality within one activity). | **Do not file.** But this row's real addressee is **#24**: see the recording gate in §6. If a diff shows the golden's departure stream truncating mid-run with exit 0, the *recording* was wedged. Re-record. |
| **DEF-01** | `Station.java:105` + `PathFinding.java:47` | Untimed `wait()` with **no predicate loop**, woken by `notify()` (not `notifyAll`) from a different activity. On a spurious or mis-targeted wakeup, `pathDirs.get(target)` returns `null`; that `null` travels as `nextPosition`, and `Train.entered` (`Train.java:87-93`) reads a `null` `nextPosition` as *arrived* and calls `Agent.die()` **mid-route**. | **Never observed.** `assert direction != null` (`RailwayMainAgent.java:147`) evaluated **21×**, held; `Util.java:85`'s matching precondition likewise 21×, held (#14). Spurious wakeup is a JLS-sanctioned hazard: the code *permits* it, nothing makes it *happen*. | Occurrence is unbounded in time and unreproducible on demand. | **Do not file.** A train that vanishes without reaching its `to`, or a `null` `next=` on an `ENTER_REPLY`, is out-of-contract. Re-run the baseline. **Note the `-ea` interaction** (§5): with assertions **on**, `assert position.equals(to)` (`Train.java:91`) fires *before* `Agent.die()`, so the symptom changes from *train vanishes* to *train wedged, loudly*. |
| **DEF-23** | `Station.java:105` | The **no-wakeup** branch of the same line (DEF-01 covers the spurious-wakeup branch). A `PATH_FIND` dropped on the way out, or a `PATH_FIND_REPLY` lost on the way back, stalls **that station permanently** — every `enter`, `leave`, `voteRequest` and `voteResult` on it starves behind the blocked handler (SEM-04), which can then hang the next election on DEF-22. | **Never observed.** 21 `PATH_FIND` / 21 `PATH_FIND_REPLY` pairs in the traced runs; the guarding assertions held. | Same as DEF-22, one level down; same silence. | **Do not file.** A station that stops emitting `STATION_INFO` mid-run is a wedged baseline. Re-record. |
| **DEF-05** | `Planning.java:48`, `:92`, `:96`; key built at `VoteCollecting.java:51` | `votes` is keyed by the **unordered** `Doubleton(voter, train)`. A duplicate vote from one voter **overwrites** the map entry while `latch.countDown()` still fires, so `assert v.size() == path.size()` at `:96` can fail and `Collections.max` at `:97` then runs over a short list — yielding a **smaller** `timeDiff` and an earlier departure. | **Never observed.** `Planning.java:96` evaluated **36×**, fired **0** (#14). Quoted: "36 complete voting rounds, no partial vote collection. The suspicion was that the `CountDownLatch` / `votes` multimap could desynchronise; **it did not.**" | Requires a duplicate delivery the kernel has never been shown to produce. Latent. | **Do not file** a departure-time difference on this basis. Under `-ea` this **aborts the run** (§5) — if that happens during recording, discard and report here. |
| **DEF-06** | `RoadAgent.java:204` `invertedTimetable.get(train) - time` | Unboxing NPE with no null check, **inside `compareTo`, inside `PriorityQueue.offer`** — a train queued without a timetable entry throws from inside the heap, leaving the heap in an undefined state. **Issue item 8.** | **Never observed** — 0 `Exception in thread "` and 0 handler faults across 146 runs. Reachable in principle after a lost `VOTE_RESULT` (SEM-06), i.e. downstream of DEF-02. | Occurrence requires DEF-02 to land on exactly the wrong message; unreproducible on demand. | **Do not file. And do not fix** — see §6. #31 proposed a port-time null check; the pin-or-fix call is here and it is **pin**. An NPE from `OueueItem.diff` on the baseline means the recording is void. |
| **DEF-09** | `VoteCollecting.java:53-54` | `getTrainCountDowns().get(train).countDown()` — a vote arriving after `Planning.java:89` removed the latch dereferences `null`. The NPE kills the `vote` handler **before** `countDown()`, which is one of the two ways to reach DEF-22. | **Never observed.** `VoteCollecting.java:53` evaluated **372×**, held (#14). | Same family as DEF-05/DEF-22. | **Do not file.** Under `-ea` this is the one live site where the flag is **behaviourally neutral** (§5) — both settings abort the handler before `countDown()`. |
| **DEF-17** | `Planning.java:121` vs `:111` | `placeTrainIntoFirstStation` polls the **globally earliest** `TrainPlan` rather than the plan whose timer fired — one timer expiry consumes whatever is at the head of the queue. | **Latent, not demonstrated.** `assert clockTime >= departure` (`Planning.java:124`) evaluated **35×**, held (#14). `INVENTORY` de-claimed the original sighting: the transcript offered as evidence is what a *correct* implementation prints. Reachable only on a **tie** in `departure` or a same-tick burst. **[this issue]** I chased a candidate sighting — a `vl39` departing amid an otherwise `vl0..vl19` set — and it is **not** DEF-17: see §7.3. | `INVENTORY`'s own verdict stands: "Nothing in stdout distinguishes the two cases, so **no observation can confirm or refute this from the outside.**" | **Nothing to look for.** Triage as a latent tie-race, never as a sighting. #21 must canonicalise order within an equal-departure group regardless, which incidentally masks this. |
| **DEF-10** | `Generator.java:37`, `:62`; TODO at `:36` | One `TRAIN.STATE.<train>` channel ticket accumulated per train, forever; `Activity.closeChannel` is called **nowhere** in the codebase. Latches leak the same way on wedged trains. **Issue item 6.** | Mechanism **static and certain**. **Degradation is UNMEASURED** — see §7.4. There is **no** measured run-length cap anywhere in `docs/`, and no statement that one was sought. | Not a per-message behaviour, so it cannot be pinned or projected. It bounds **how long a golden run may be**, and that bound is presently unknown. | **Do not file.** If a long run degrades, suspect the harness first. The decision is **bound the scenario, do not fix** — §6.3. |

### 3.4 Not defects — invariants to record so a rewrite does not drop them

| id | Site | Statement | Standing |
|---|---|---|---|
| **item 3** | `RoadAgent.java:208-214` | **Retracted.** `OueueItem.compareTo` reads the live clock at `:210`, but `diff(t) - o.diff(t)` expands to `invertedTimetable.get(train) - invertedTimetable.get(o.train)` — **`t` cancels algebraically**, so the ordering is *stable* and the `Comparable` contract is **not** violated by the clock read. The real defects at this site are DEF-03 and DEF-04. | Proved algebraically (#9); re-derived [this issue] from the source at `:203-214`. |
| **DEF-18** | `Station.java:135-136`, `RoadAgent.java:178` | `timetable.lastKey()` on an empty `TreeMultiMap` throws `NoSuchElementException`. **No bug today** — both call sites are guarded by a preceding `plannedTrains` test that implies non-empty. **Issue item 10.** | See §4.4 for a refinement to `INVENTORY`'s stated threshold, and a **new** configuration precondition. |
| **DEF-15** | `RailwayMainAgent.java:108-109` (GUI-03) | The `Gui` was constructed unconditionally, and those two lines were the only thing keeping `createClock` clear of the clock-registration race — buying **159–384 ms** against a **~3.4 ms** window. | **Resolved on `opencybele-baseline` by #17**: `RunControl.awaitTimerService()` (barrier) plus `RunControl.verifyClockControl()` (regression check, exit **5** on failure), both unconditional. Measured: dead clock **0/12** and **0/9** with the barrier, against **8/8** on an early build without it. **Carve-out for #39: exit 5 means the run is void — discard, never triage.** |
| **DEF-21** | CNT-08 | All assertions disabled. **Verified [this issue] on `jade-develop`: 33 sites** (34 `grep 'assert '` hits minus one Javadoc line at `util/Util.java:49`). | Resolved by **Decision 1** (§5). |

---

## 4. The four rows that need more than a table cell

### 4.1 Issue item 3 is not a defect — and saying so is load-bearing

The issue calls `OueueItem.compareTo` a `Comparable`-contract violation because it reads
`Cybele.getTime` at `RoadAgent.java:210`. It is not. Expanding `diff`:

```java
private long diff(long time) { return invertedTimetable.get(train) - time; }          // :203-205
int d = (int) (diff(time) - o.diff(time));                                            // :211
      = (int) ((invertedTimetable.get(train) - time) - (invertedTimetable.get(o.train) - time))
      = (int) (invertedTimetable.get(train) - invertedTimetable.get(o.train));
```

`time` cancels. The ordering depends only on the two timetable entries, so it is **stable while the
elements sit in the heap**. It becomes unstable only if `addToPlan` (`:185-188`) rewrites an entry
for a train already in the queue — a different defect, not this one, and not observed.

This matters because #34 and #31 would otherwise "fix" a comparator that is correct, and any change
to it *is* a behaviour change under the scope guard.

### 4.2 DEF-08 is DE-CLAIMED — `docs/INVENTORY.md` DEF-08 is SUPERSEDED by this section

> **Read this before acting on `docs/INVENTORY.md` DEF-08.** That entry describes a defect that does
> not exist, and "fixing" it breaks the simulation. #39 and the port authors will read one document
> or the other; this section is the one that is right.

`INVENTORY` DEF-08 asserts that `destroy()`'s `leaveObject(position)` sends a **second** `LEAVE` for
the final station, so `Station.leave` "decrements `occupied` twice or wrongly admits a queued train".
Reading `Train.java` shows why that is wrong:

```java
public synchronized void entered(CybeleEvent ev) {
    leaveObject(position);          // :82  — leaves the OLD position
    position = (String) message[0]; // :84  — only NOW becomes the new one
    ...
}
public synchronized void destroy() {
    leaveObject(position);          // :124 — leaves the CURRENT (final) one
```

`entered` always leaves the object the train is *departing*; `destroy` leaves the one it is
*standing in*. Each object on a route receives exactly one `LEAVE`.

**Measured [this issue]**, one traced 66-train run of `scenarios/short-bounded.properties`:

```
total LEAVE records            : 199
(train, object) pairs with >1  : 0        <- no duplicate anywhere
vl0 route stB..stC             : 9 objects, 9 LEAVEs, all distinct
```

Far from being a double decrement, the `destroy()` `LEAVE` is what **balances** the destination
station's `occupied++` from `Station.enter`. A port that "fixes" DEF-08 by deleting it leaks
occupancy on every arrival. Reclassified: **(a), and the behaviour is required, not tolerated.**

### 4.3 DEF-13 / `STATION_INFO.occupied` — the one field no capture point saves

This answers issue item 11's explicit question. Under `Local;NoSerialization` a payload crosses a
channel **by reference** (SEM-05), and `Station.Info` is a non-static inner class of the very object
that keeps mutating it. What a subscriber receives is a live alias.

The tempting fix — *snapshot on receipt* — does not work, and this was measured rather than argued.
After projecting every clock-derived family away, three runs at one pinned seed still differ, and
they differ **only** in `occupied`, by 10, 12 and 6 lines across the three pairings, with the counts
trading in lockstep between adjacent values on the same station. `trace-format.md`'s verdict:

> "Same physical send is recorded with a different occupancy depending on whether the station's own
> thread got to its `occupied++` first. No capture point removes that — **the value is a race, not a
> timestamp.**"

Decision: **projected, not pinned.** `occupied` is a sixth run-varying family for #21. `capacity`
stays in the contract — it is configuration. Snapshot-on-receipt should still be done (it removes
*post-hoc* drift), but it is a hygiene measure, not a determinism measure.

Consequence for the ports, and it is a favourable one: JADE and Jason both give snapshot message
semantics, which would otherwise be a guaranteed diff on every `STATION_INFO` line. Because the
field is projected, **the semantic difference is inside the contract**.

### 4.4 DEF-18 — the invariant is tighter than recorded, and it is now config-dependent

`INVENTORY` DEF-18 says `Station.java:135`'s `lastKey()` is "unreachable only because every capacity
in CFG-02 is ≥ 2". The actual threshold is **≥ 1**:

```java
final int plannedTrains = timetable.subMultiMap(time-W, time+W).values().size();  // :132
if (plannedTrains > info.capacity-1) {                                            // :133
    ... timetable.lastKey() ...                                                   // :135
```

With `capacity ≥ 1`, entering the branch requires `plannedTrains ≥ 1`, which requires a non-empty
window, which requires a non-empty `timetable`. The guard holds for any capacity ≥ 1. At
**capacity 0** it admits `plannedTrains == 0` on an empty map and `lastKey()` throws. The minimum in
the shipped topology is 2 (`stC`, `stD`, `stF`, `stH`), so there is margin either way.

`RoadAgent.java:178` is guarded by `plannedTrains > 0` and needs no capacity assumption at all.

**New, and it matters because #18 made capacities configurable [this issue]:** the invariant is now
enforced by configuration validation, not only by luck. Verified:

```
$ OPENCYBELE_OPTS="... -Dsim.station.capacities=stA=0,..." bin/opencybele
Exception in thread "main" java.lang.IllegalArgumentException:
    sim.station.capacities: stA must be >= 1, was 0
$? = 1
```

So DEF-18 is closed by a startup check on the baseline branch. **Requirement on the ports:** any
port that re-implements `computeDifference` must keep the `capacity ≥ 1` precondition — it is the
kind of implicit guard a rewrite drops silently, and there is no test that would catch it because the
shipped topology never approaches the boundary.

---

## 5. Decision 1 — `-ea` for golden recording

### The decision

> **Goldens are recorded with assertions ENABLED (`-ea`).**
>
> **An `AssertionError` during a golden run ABORTS the recording.** The run is discarded, never
> normalised, never compared. The condition is triaged back into this document as a new defect. It
> is **never** pinned into a golden.
>
> **`-ea` goes into #24's environment manifest as a first-class field.** A recording made with
> assertions on must never be compared against a replay made without them, or the reverse.

### Why enabled

1. **It matches the runtime the parity runs will use.** #13 bakes `-ea` into the launcher and #14
   already enabled it for `run`. Recording *off* and replaying *on* would turn every future
   assertion firing into a spurious golden diff attributed to the port.
2. **The measured cost today is zero.** 32 of 33 sites triaged (`assertion-triage.md`), **25
   exercised**, ~**20 000** evaluations in the instrumented run, **0 fires**; and 0 `AssertionError`
   across **146** measurement runs on the baseline branch. **Re-verified [this issue]**: three
   headless `-ea` runs of `short-bounded` — exit 0, 21 departures each, identical departure-id sets,
   **0 `AssertionError`, 0 `Exception in thread "`**.
3. **Where an assertion *can* fire, the alternative is not "same behaviour" — it is silent
   corruption.** With assertions off, `Planning.java:96` proceeds to run `Collections.max` over a
   short vote list and departs the train too early; `RailwayMainAgent.java:147` ships a `null`
   direction that makes a train die mid-route (DEF-01). Freezing *that* into a golden would oblige
   the JADE port to reproduce corruption it cannot detect. `-ea` converts it into a loud abort at a
   named line.
4. **It costs the harness nothing.** The failure text is on **stderr** (stdout has 0 occurrences),
   in a shape #12's `ErrorScanner` already matches, and **the exit status is unchanged either way** —
   so nothing downstream has to be rebuilt for this choice.

### The honest cost — `-ea` is *not* behaviour-neutral

`assertion-triage.md` says so at whole-program level ("a path that previously continued past a broken
invariant now throws instead, and that throw mutates the surviving agent state") but does not work it
out per site. **[this issue]** here is the per-site reading, from the source:

| Site | Assertions **off** | Assertions **on** | Divergent? |
|---|---|---|---|
| `VoteCollecting.java:53` | `get` returns `null` → **NPE at `:54`** → handler dies **before** `countDown()` | `AssertionError` at `:53` → handler dies **before** `countDown()` | **No.** Both lose the vote, both feed DEF-22. Diagnostics only. |
| `Planning.java:96` | continues; `Collections.max` over a short list ⇒ **smaller `timeDiff`, earlier departure** | aborts `planTrain` inside `synchronized(this)`; **the train is never planned, no timer armed** | **Yes** |
| `Planning.java:124` | sends `START` with **another train's** station (DEF-17) | `START` never sent; the polled plan is consumed anyway | **Yes** |
| `Train.java:73` | `requestEnterToObject` on the **wrong** station | the train never starts | **Yes** |
| `Train.java:91` | `Agent.die()` **mid-route** — DEF-01's visible symptom | `die()` never reached; the train **wedges** at that station, loudly | **Yes** — and note it *suppresses* DEF-01's classic symptom |
| `Train.java:95` | `TRAVEL_START` to a bogus channel (dropped, SEM-06) | handler aborts | **Yes** |
| `RailwayMainAgent.java:147` | ships a `null` direction ⇒ DEF-01 | no `PATH_FIND_REPLY` ⇒ **DEF-23**, permanent station stall | **Yes** — trades a bad answer for no answer |

So the flag changes the trajectory at six of the seven live sites. It changes **nothing** on the
measured path, because none of them fires. Two consequences follow, and both are decisions, not
observations:

- **`-ea` must be in the manifest.** It is not a debug flag; it selects between two different
  behavioural contracts.
- **`-ea` changes which defects are observable.** DEF-01's headline symptom (a train vanishing
  mid-route) is *masked* by `-ea`, replaced by a wedged train and a stack trace. #39 should look for
  the wedge, not the vanishing. This is a good trade — the wedge is detectable and the vanishing is
  not — but it must be written down, and it was not.

### Why *abort* rather than *pin*

Pinning an `AssertionError` into a golden asserts that the invariant violation is **reproducible**.
Every live assertion site sits downstream of DEF-02, DEF-09 or DEF-22 — all class (c). Pinning a
class-(c) event as required behaviour would make the port chase a race. Abort, discard, classify.

---

## 6. Decision 2 — pin or fix, per defect

The scope guard means **fix is almost never allowed**: a fix changes the goldens, and any fix landed
here re-triggers #21's ≥10-run flake gate before #24 (issue acceptance criterion). The bar is
therefore *"the defect prevents a golden from existing at all"*, not *"the defect is bad"*.

### 6.1 Pinned as-is — everything except DEF-10

| Group | Decision | Rationale |
|---|---|---|
| DEF-01, DEF-02, DEF-05, DEF-06, DEF-09, DEF-17, DEF-22, DEF-23 | **PIN** (as class-(c) carve-outs) | These are observable *baseline behaviour*. The JADE port must reproduce the deadlock **observably** even though #34 will implement it with a timeout — a bounded wait that logs a timeout branch is the correct port; a bounded wait that silently proceeds is not. |
| DEF-03, DEF-04, DEF-07, DEF-11, DEF-12, DEF-14, DEF-16, DEF-19, DEF-20 | **PIN** | Deterministic. Pinned by L1 unit tests (#28), not by scenario luck. |
| DEF-13, DEF-24, agent-init order, the five clock families | **PIN the mechanism, PROJECT the value** | #21's job. Nothing changes in the application. |
| DEF-16 specifically | **PIN — do not clamp** | Re-affirming #15's decision. `Math.max(0, delay2)` is a behaviour change. The instantaneous traversal is part of the frozen baseline; a port must be *checked* against the measured Cybele timer semantics, not assumed to match. JADE's `WakerBehaviour` also fires at once for a past deadline — that coincidence is exactly what a golden should pin rather than a port assume. |
| DEF-06 specifically | **PIN — reject #31's port-time null check as a baseline change** | #31 may add the null check **in the JADE port** if it also emits an observable marker; it must not be added to `develop`, and a port that silently swallows the condition would hide a real divergence. |
| DEF-18, DEF-15, issue item 3 | **No decision needed** — invariant, resolved, and retracted respectively | See §4.1, §4.4, and DEF-15's row. |

### 6.2 The one defect where a fix was seriously considered — and rejected

**DEF-06** (unboxing NPE inside `PriorityQueue.offer`). The case for fixing is real: an NPE thrown
from inside a heap leaves the heap in an undefined state, so the *consequences* of DEF-06 are not
merely "a train is lost" but "the road's queue is arbitrary from then on" — genuinely unpinnable.

Rejected anyway, on two grounds. It has **never been observed** in 146 measurement runs, so the fix
buys nothing measurable; and a null check has to *do* something — return 0, skip the item, throw a
different exception — and every choice is a scheduling-policy decision invented in 2026 and frozen
into a 2008 contract. Pinned, with the carve-out in §3.3.

### 6.3 DEF-10 — bound the scenario, do **not** fix

The issue offers the choice explicitly: *"Either fix it (close channels on train death) or bound
scenario length so it never manifests, and say which."*

> **Decision: bound the scenario length. Do not close the channels.**

**Why not fix.** Closing a `TRAIN.STATE.<train>` channel on train death changes **message-delivery
behaviour**, and the delivery behaviour it changes is precisely the one the goldens exist to
preserve. SEM-06 says a send to a channel nobody has opened is **silently dropped** — that single
semantic is the mechanism behind DEF-02 *and* one of the two triggers for DEF-22. Introducing channel
closure introduces a *second* way for a send to be dropped, on a schedule nobody has characterised,
and `INVENTORY` §14 records that **"the behaviour of `Agent.die()` with respect to the dying agent's
open channels" was never probed**. Fixing DEF-10 would perturb the two most important defects in this
document in order to remove a leak that has never been observed to matter. That is a bad trade under
any scope guard, and an indefensible one under this one.

**What the cap is: UNMEASURED. Say so, do not guess.** No measured run-length cap attributable to
DEF-10 exists anywhere in `docs/` on either branch — confirmed by search across `seeded-rng.md`,
`kernel-config.md`, `scenario-config.md`, `headless-and-stop.md`, `trace-format.md` and
`assertion-triage.md`; neither "channel leak" nor any degradation-versus-length figure appears. The
run-length effects that *are* measured (30 s reproducible vs 45 s not, `seeded-rng.md:494-503`) are
attributed to **DEF-02**, not to the leak, and the attribution is supported (generator streams
identical, only departure sets differing).

**The operational bound, stated as what it is — evidence of absence, not a measurement:**

| | Value | Standing |
|---|---|---|
| Longest run with evidence of **no** leak-attributable degradation | `short-bounded`: **66 channels leaked**, 115 s simulated / ~14.4 s wall | Measured [this issue] and by #17/#20, many runs |
| Longest run observed at all | `short.properties` at 45 s wall: highest id **`vl663`** ⇒ ~664 leaked channels | Measured (#15) — and it *did* diverge, but by DEF-02, not by the leak |
| Leak-attributable cap | **UNKNOWN** | Never measured. No one has looked. |

**Recommendation to #24:** keep golden scenarios at or below the `short-bounded` scale (~10²
channels), and treat a *monotonic* degradation with run length — as opposed to DEF-02's stochastic,
non-monotonic drops — as the signature that would finally make this worth measuring. If a longer
scenario is ever wanted, measure the leak first; do not extrapolate from here.

---

## 7. What was re-measured or newly established for this document

All on `/home/beda/work/wt/opencybele-ref` at `opencybele-baseline` (`f4c233c`), built with
`./gradlew installDist`, run headless via `OPENCYBELE_OPTS` — **never on display `:0`**, whose
variance is documented as contaminated (`kernel-config.md:63-70`: `:0` produced departure-id sets
differing by 5–10 ids between runs of the same config; an isolated display collapsed that to zero).

### 7.1 `-ea` costs nothing on the recording scenario — confirmed

Three headless runs, `scenarios/short-bounded.properties`, `-ea`:

```
run1 exit=0 departures=21 AssertionError=0 ExcInThread=0
run2 exit=0 departures=21 AssertionError=0 ExcInThread=0
run3 exit=0 departures=21 AssertionError=0 ExcInThread=0
departure-id set, all three: vl0 vl1 vl3 vl2 vl4 vl9 vl5 vl6 vl7 vl10 vl8 vl11 vl39 vl12 vl13 vl14 vl15 vl16 vl17 vl18 vl19
```

Identical id sets, identical order. The only variance is the documented equal-departure-instant tie:
`vl12`/`vl13` (both at 78096) and `vl14`/`vl15` (both at 90096) transpose between runs. **This is one
session and therefore proves nothing about cross-session stability** — see §1.

### 7.2 DEF-08 de-claimed, DEF-18 refined, DEF-03/04/11/16 re-derived

Covered in §4.2, §4.4 and the table rows. Summary of what is new:

- **DEF-08 is not a defect** — 199 `LEAVE` records, 0 duplicate `(train, object)` pairs.
- **DEF-18**'s threshold is capacity **≥ 1**, not ≥ 2, and is now enforced by a startup check
  (exit 1) rather than by the shipped topology alone.
- **DEF-03**'s reachability bound is quantified: sign survives to 2³¹ ms; first inversion at 2³¹+1 ms
  = **24.855 simulated days**; zeroed at 2³². Unreachable at scenario scale — so it is L1-only.
- **DEF-16** affects more than `tr1`/`tr2`. `INVENTORY` names only the 1 s roads. `tr4` (2 s) needs a
  −4σ draw: measured **0.0033 %** over 2×10⁶ draws — vanishing, but not zero, and a port that
  special-cases "only the 1 s roads" is wrong.
- **DEF-21**: **33** assert sites on `jade-develop` (34 `grep` hits minus one Javadoc line),
  reconciling with `assertion-triage.md`'s 32-on-the-post-#18-tree.

### 7.3 A candidate DEF-17 sighting, chased and dismissed

The `short-bounded` departure set is `vl0..vl19` **plus `vl39`** — an id 20 places out of sequence,
exactly the shape DEF-17 would produce. It is not DEF-17. From a `sim.trace.enabled=true` run:

```
vl20|40944|PLAN_TRAIN|...  first VOTE_RESULT planned=116088   > the 115000 bound -> never departs
vl21|42584|PLAN_TRAIN|...  planned=130088
vl39|70472|PLAN_TRAIN|...  planned=70472                      (timeDiff = 0, uncongested route)
66 PLAN_TRAIN records, 21 departures
```

All 66 trains are planned, in order, promptly. `vl20`'s route was congested and drew a `timeDiff` of
~75 s, pushing its departure past the bound; `vl39`'s route was clear and drew `timeDiff = 0`.
Ordinary congestion scheduling, correctly ordered by departure instant. `assert clockTime >=
departure` held throughout, under `-ea`.

Recorded because the shape is a **false positive generator**: #39 will see out-of-sequence ids in
every golden and must not read them as evidence of anything. Departure order is by *planned
departure*, never by id.

### 7.4 Provenance of the figures handed to this issue

Three figures reached this issue without a citation. Chasing them found that **two were real but
had never been written into a committed doc** — they are the coordinator's own measurements from
this triage session. They are recorded here in full, with attribution, because an uncited number is
exactly the failure mode this document exists to prevent. The third was a genuine conflation.

#### (i) Session-level mode-switching — 4 distinct id-sets over 6 runs

**Measured by the coordinator during #22's triage session; headless; not present in any prior
committed doc.** `short.properties`, seed `20080415`, bounded `sim.stop.maxClockMs = 350000`, two
groups of three runs, compared over a **common simulated window `t <= 300000`** so the ragged tail at
the bound is not counted:

```
session 1:  n = 69, 69, 68      pairwise id-set diffs:  1v2 = 0,   1v3 = 1
session 2:  n = 68, 70, 70      pairwise id-set diffs:  2v3 = 0,   1v2 = 10
across sessions (run 1 vs run 1):                             13 differing ids
md5 of the sorted id-sets, all six runs:  4 distinct values, two appearing twice
```

The structure is visible in marker ids: **every run in session 1 departs `vl112` and `vl319`; every
run in session 2 departs `vl107` and `vl315`.** So the finding is not "runs differ" — the **mode is
constant within a session and flips between sessions**. Within-session pairwise differences are 0–1
ids; across-session differences are 10–13.

This is the same phenomenon `kernel-config.md:99-118` records as one sequence in 114 consecutive runs
against a second sequence in 7 of 9 later runs, observed on a different axis and at a different run
length. **It is the direct evidence for §1's rule that consecutive runs cannot validate a scenario**,
and it is why DEF-02 is class (c) rather than (b): a scenario validated three times inside one
session has demonstrated only that the mode did not flip while you were watching.

*Standing: reported to this issue as raw data and recorded verbatim. **Not independently re-run by
this document** — the six-run, two-session protocol is the one thing that cannot be verified cheaply,
by construction.*

#### (ii) Display `:0` contamination — record the two raw figures, not the ratio

**Measured by the coordinator during #22's triage session; not present in any prior committed doc.**
Same scenario family, two run conditions:

| Condition | Run length | Pairwise departure-id differences | Out of |
|---|---|---|---|
| Display `:0` | `short.properties`, 45 s | **12, 10, 2** | 80 departures |
| Headless | as (i) above | **0, 1, 1** | ~69 departures |

The "~10×" that reached this issue is the ratio of the worst `:0` figure to the worst headless one
(12 vs 1). It is a ratio between two of the coordinator's own measurements **at different run
lengths**, not a documented constant, and it is recorded here as the **two raw figures with no
ratio** — which is what it supports.

The contamination itself is real and was **independently reproduced by #16** (`kernel-config.md:63-70`:
`:0` id-set differences of 5–10 collapsing to **zero** on a private display; `:170-172`: 12 distinct
normalised traces over 26 runs on `:0` against 2 distinct id-sequences over 123 runs headless).
**What was retracted is the *density-cliff interpretation*, not the observation.**

*Operational rule, unchanged and now doubly sourced: **never record or validate a golden on display
`:0`.** `scenarios/short-bounded.properties`'s header still describes the load ceiling as a real
density threshold and should be updated to match the retraction.*

#### (iii) "Six different orders in six runs" — a genuine conflation

This figure is `seeded-rng.md:79-87` and `:357`, and it measures **agent constructor order**, not
departure sequences. The brief that reached this issue attached it to run-to-run departure
variation. The reading in §3.2's *agent init order* row is the correct one; the figure does not bear
on DEF-02.

#### (iv) `vl550` — confirmed, and distinct from the boundary observation

**Confirmed** (`kernel-config.md:174-176`): three headless runs at ~69 departures, pairwise id-set
differences of 0, 1 and 1, the single difference being `vl550` **missing from one run entirely**,
with the generator stream identical (same maximum id `vl663`). Note that `trace-format.md:577-584`
reports a *different* pair — `vl19`/`vl50`, at the `maxClockMs` boundary. **Two distinct
observations, not one garbled one:** `vl550` is an interior drop (DEF-02 proper, class (c));
`vl19`/`vl50` is the ragged tail (class (b), excluded by scenario choice).

---

## 8. What #24 must record, and what #39 must do

### 8.1 Manifest fields this document adds to #24

| Field | Value | Why it is behaviourally significant |
|---|---|---|
| `-ea` | **on** | §5 — changes the trajectory at six of seven live assertion sites. |
| `sim.random.masterSeed` | pinned, never `random` | The default draws a seed and prints it; a positional `-D` is **silently ignored** by the Gradle start script (`OPENCYBELE_OPTS` only). |
| `sim.headless` | `true` | Excludes DEF-24 and the GUI pace toolbar. |
| `sim.clock.pace` | recorded | Not measured to perturb simulated-time throughput (Welch t = 0.671, n=3/arm), but "not measured to differ" is not "proven identical". |
| `sim.arrival.lambdaMs` **and** `sim.station.voteWindowMs` | both, separately | #18 split one constant into two levers; a recording at one pair must never be replayed at another. |
| `sim.stop.*` | all four | The bound decides where the ragged tail falls. |
| **expected departure count** | **new — required** | See §8.2. |

### 8.2 The recording gate — exit 0 is necessary but **not sufficient**

DEF-22 wedges the run and still exits **0**, with a clean stderr, at roughly **1 recording in 45**.
`sim.stop.stallMs` cannot see it (§3.3). Therefore:

> **A golden recording is accepted only if it reaches its expected departure count *and* its expected
> final tick — not merely if it exits 0.**

And, from §1's two-scale finding:

> **A golden must be reproduced across separate JVM sessions, not in a loop.** Repeat the recording
> in at least two sessions and accept only if the **departure-id sets** agree. Three consecutive
> green runs are the expected output of a stable mode and are worth nothing here.

### 8.3 #39's triage rule, in one paragraph

Before filing a parity diff as a port bug, check it against §3.3. If the diff is a **missing or extra
train id**, a **truncated departure stream with exit 0**, a **train that vanished mid-route or wedged
at a station**, an **NPE from `OueueItem.diff`**, a **`ConcurrentModificationException` on the EDT**,
or **exit 5**, then the *baseline recording* is the suspect: re-run it, in a fresh session, and
compare departure-id sets. If the diff is a **timestamp**, a **`STATION_INFO.occupied` count**, an
**ordering within one tick**, or the **startup state block's order**, it is #21's normalizer, not the
port. Everything else — an `ENTER_REPLY`/`LEAVE` pair in the wrong order, a missing final `LEAVE`
before `KILL`, a clamped travel delay, a road tie broken by direction frequency, a `capacity` value —
**is a port bug, and this document is the reason you can say so.**

> **One `-ea` consequence #39 must know before reading a stack trace.** Because goldens are recorded
> with assertions **on** (§5), DEF-01's classic symptom is **masked**: `assert position.equals(to)`
> (`Train.java:91`) fires *before* `Agent.die()`, so a mid-route death appears as a **wedged train
> plus an `AssertionError` on stderr**, not as a train silently vanishing. That stack trace is a
> **class-(c) baseline artefact, not a port bug** — discard the recording and re-run in a fresh
> session. The trade is deliberate and favourable: the wedge is detectable and the vanishing was not.
> The same applies to an `AssertionError` at `Planning.java:96`, `Planning.java:124`,
> `VoteCollecting.java:53`, `Train.java:73`, `Train.java:95` or `RailwayMainAgent.java:147` — all six
> sit downstream of DEF-02, DEF-09 or DEF-22, all class (c). **No `AssertionError` is ever a port
> bug on its own**; it aborts the recording and comes back here for classification.

---

## 9. Acceptance criteria — status

| Criterion | Status |
|---|---|
| Each of the 11 items has a deterministic reproduction or a documented reason it cannot be reproduced | **Done.** 5 have deterministic reproductions (items 4, 5, 7, 9, and item 3 which is retracted); 5 have documented reasons they cannot (items 1, 2, 6, 8, 11); item 10 is an invariant with a startup check. |
| Each has an explicit pin/fix decision with rationale, in `docs/` | **Done** — §6. All pin except DEF-10, which is bounded rather than fixed. |
| Anything pinned is described precisely enough for a port author to reproduce deliberately | **Done** for class (a) — expression-level, with L1 test targets for #28. Class (c) rows are explicitly *not* reproducible and say so. |
| Anything fixed is fixed before goldens are recorded | **Vacuous — nothing is fixed.** No application source changed by this issue. |
| Any fix re-triggers #21's ≥10-run flake gate before #24 | **Not triggered.** No fix landed. |
| The `-ea` decision recorded and handed to #24's manifest | **Done** — §5, §8.1. |

## 10. What could not be classified

- **DEF-17.** Genuinely unclassifiable by observation — `INVENTORY`'s own verdict, upheld here after
  chasing a candidate sighting (§7.3). Filed as (c) on the grounds that nothing else is available,
  not because (c) was demonstrated.
- **DEF-10's run-length cap.** Unmeasured, and §6.3 says so rather than guessing.
- **Whether two activities of one agent can execute handlers simultaneously.** `INVENTORY` §14 leaves
  this open; #16 settled the *scheduling* half in bytecode (`CONCURRENT`, `getRunnable` list-iterates)
  but not the *simultaneity* half. It does not change any row here, but a port that serialises them
  would be making an unverified assumption.
- **`Agent.die()`'s effect on a dying agent's open channels.** Never probed (`INVENTORY` §14). This is
  the single fact that would let DEF-10 be fixed safely, and it is the reason §6.3 declines.
