# MIGRATION.md — Migrating from OpenCybele to Jason (AgentSpeak/BDI)

> Migration guide for porting the `bedaHovorka/OpenCybele1` project from the OpenCybele/Cybele agent infrastructure to the Jason BDI agent programming platform (https://jason-lang.github.io/).

> **Upstream verification note — verified 2026-08-18.**
> Every upstream claim below was re-checked on **2026-08-18** against the sources cited inline.
> Versions actually observed (not predicted):
> **Jason** — `io.github.jason-lang:jason-interpreter` **3.3.0**, the latest *released* version
> (Maven Central [`maven-metadata.xml`](https://repo1.maven.org/maven2/io/github/jason-lang/jason-interpreter/maven-metadata.xml)
> lists exactly `3.2.0, 3.2.1, 3.3.0` with `<release>3.3.0</release>` and `<lastUpdated>20251009132530</lastUpdated>`);
> prose docs read from branch `main`, whose [`release-notes.adoc`](https://raw.githubusercontent.com/jason-lang/jason/main/doc/release-notes.adoc)
> opens with the **undated** heading `== version 3.3.3` — the in-development line, **not** a release.
> **JADE** — the artifact this project pins, `net.sf.ingenias:jade:4.3`, which self-identifies at
> boot as *"This is JADE 4.3.3 - revision 6726 of 2014/12/09"* (decision [#26](https://github.com/bedaHovorka/OpenCybele1/issues/26));
> TILAB **4.6.0** as the last official release; `de.enflexit.jade:de.enflexit.jade` **4.6.5** on
> Maven Central ([`maven-metadata.xml`](https://repo1.maven.org/maven2/de/enflexit/jade/de.enflexit.jade/maven-metadata.xml),
> `lastUpdated` 2025-03-31).
> **JDK** — all runtime measurements on **OpenJDK 21.0.11** (Red Hat build 21.0.11+10), Fedora, headless.
> **Reachability** — `jade.tilab.com` did **not** respond from the verifying machine (TLS failure:
> *unable to get local issuer certificate*). The JADE Programmer's Guide was therefore read from the
> reachable mirror <https://jade-project.gitlab.io/docs/programmersguide.pdf>, edition
> *"JADE 4.0, last update 08-April-2010"*; cite that URL, not `jade.tilab.com`.

## TL;DR

- **Migration is a re-modeling exercise, not a line-by-line port.** OpenCybele's activity-centric, thread/event-driven Java model maps onto Jason's BDI model as follows: *agent → `.asl` agent*, *activity → plan/intention*, *message/timer/data event → belief- or goal-triggered plans*, *Java fields → beliefs*, *heavy Java logic → internal actions or environment actions*, and the Cybele agent/community descriptor → a Jason `.mas2j` project file. Target **Jason 3.3.0** — the latest *released* version (`io.github.jason-lang:jason-interpreter:3.3.0`; Maven Central [`maven-metadata.xml`](https://repo1.maven.org/maven2/io/github/jason-lang/jason-interpreter/maven-metadata.xml) lists exactly `3.2.0, 3.2.1, 3.3.0` with `<release>3.3.0</release>`) — which runs each agent on a virtual thread to allow thousands of agents, via its Gradle tooling. ⚠️ **Do not pin `3.3.3`**: that is the *undated* top heading of [`release-notes.adoc`](https://raw.githubusercontent.com/jason-lang/jason/main/doc/release-notes.adoc) on `main`, i.e. the in-development line, and it will not resolve. For the 3.3.0 dating question see the Caveats.
- **The concrete contents of `bedaHovorka/OpenCybele1` could not be verified** by the research tooling (the repository was not reachable via search or fetch/GitHub API; only the owner account, GitHub user id 5263405, was confirmed to exist). Every statement describing specific OpenCybele1 classes/files is therefore an explicitly-labeled ASSUMPTION and must be checked against the cloned repo. The conceptual mapping, architecture comparison, and Jason target-side instructions are grounded in verified documentation and are reliable regardless.
- **Recommended path:** clone the repo and generate a real class/activity/message inventory; scaffold a Jason project with `jason app create`; port one agent end-to-end and validate with a behavioral-parity harness and the Jason Mind Inspector; then port the rest, moving computation into internal actions and world-effects into an `Environment`. Use VS Code + the Jason extension (the bundled jEdit IDE was removed in Jason 3.2).

---

## Key Findings

1. **The two frameworks sit at different abstraction levels.** OpenCybele/Cybele is a Java *infrastructure* where you write imperative event handlers and the kernel manages threads; Jason is a *language interpreter* where you declare beliefs, goals, and plans and the reasoning cycle deliberates. This is the single most important thing to internalize before migrating.
2. **Most Cybele event-plumbing disappears in Jason.** Because changing a belief automatically generates a triggering event, the explicit handler-registration and event-dispatch code typical of OpenCybele collapses into `+belief`/`+!goal` plans.
3. **Communication maps cleanly.** Cybele `sendMessage` becomes Jason's `.send` with KQML-style performatives; the receiver needs no explicit handler registration. The **complete** performative set is `tell, untell, achieve, unachieve, askOne, askAll, askHow, tellHow, untellHow, signal` — ten, no more — per <https://raw.githubusercontent.com/jason-lang/jason/main/doc/tech/performatives.adoc>. ⚠️ `broadcast` is **not** a performative: `.broadcast` is an *internal action* (`jason/stdlib/broadcast.class` in `jason-interpreter-3.3.0.jar`) that takes a performative as its argument. For this repo the important member is **`signal`** — see §3.1 below.
4. **Timers need deliberate replacement.** Jason has no first-class periodic activity; use `.wait` loops, `.at`, or (best for shared/deterministic timing) percepts injected by the environment.
5. **Some Cybele features have no clean equivalent** — strong agent migration/mobility, kernel load balancing, arbitrary Java-object message payloads, and fine-grained activity scheduling. These must be isolated behind internal actions or the environment, or handled by moving to the JADE infrastructure for distribution.
6. **Tooling modernizes.** OpenCybele's Ant/Maven-plus-jars build becomes Jason's Gradle setup (`io.github.jason-lang:jason-interpreter`), run via the `jason` CLI, with VS Code as the current IDE.

---

## Details

### Important note on repository access

> **Superseded (issue #9).** The repository has since been read in full and inventoried in
> [`docs/INVENTORY.md`](INVENTORY.md). Wherever this guide labels something an ASSUMPTION about
> OpenCybele1, `INVENTORY.md` is the authority. The paragraph below is kept only to explain why
> the assumptions are there. (A full pass over the remaining sections is tracked separately.)

At the time of writing, the target repository `https://github.com/bedaHovorka/OpenCybele1` (develop branch) **could not be programmatically accessed** by the research tooling — neither web-search indexing nor direct fetch/GitHub API were reachable through the available tools. The GitHub account `bedaHovorka` (numeric user id 5263405) was verified to exist through an unrelated issue thread on `tkohout/OSTRAJava` (two Czech-language issues opened January 2021), but the repository files, class names, build files, configuration, and commit history could not be verified, and it could not be determined whether the repo is private, deleted, or simply unindexed.

**Consequently, every statement in this guide that names specific OpenCybele1 classes, packages, or files is a clearly-labeled ASSUMPTION** based on standard OpenCybele/Cybele conventions and must be checked against the real code before use. To obtain the concrete contents, run locally:
```
git clone -b develop https://github.com/bedaHovorka/OpenCybele1.git
curl https://api.github.com/repos/bedaHovorka/OpenCybele1
curl "https://api.github.com/repos/bedaHovorka/OpenCybele1/contents/?ref=develop"
```
and then replace the ASSUMED sections below with the real class inventory.

### 1. Overview: what the two frameworks are

**OpenCybele / Cybele** is a Java-based agent infrastructure originally developed by **Intelligent Automation, Inc. (IAI)** — a firm NASA records place at 15400 Calhoun Drive, Suite 190, Rockville, MD (IAI is the ACES subcontractor). Cybele was produced with NASA SBIR support and became an enabling technology in NASA's **Airspace Concept Evaluation System (ACES)** for air-traffic-management simulation; it has also been used for distributed robotics and U.S. Air Force crowd-behavior modeling. IAI offers a family of products: the commercial CybelePro and CybelePro Enterprise, and the open-source OpenCybele plus free CybeleLite editions. Cybele agents follow an **activity-centric programming** paradigm. As the NASA Tech Brief "Activity-Centric Approach to Distributed Programming" (Levy, Satapathy & Lang; NTRS 20110020334 / MSC-23239) states verbatim, *"Activity centric programming relieves application programmers of the complex tasks of thread management, concurrency control, and event management,"* and *"Cybele provides support for event handling from multiple sources, multithreading, concurrency control, migration, and load balancing."* The same brief describes the architecture: *"Cybele follows a modular service-based approach … the functionalities … are apportioned … among several groups called services … The activity-centric application-program interface (API) is part of a kernel."* CybelePro is described by IAI as *"an agent infrastructure … a java-based middleware for developing distributed systems using agent-based programming paradigm."*

**Jason** is an open-source (GNU LGPL) interpreter for an extended version of **AgentSpeak(L)**, a BDI (belief–desire–intention) agent-oriented logic programming language. It is implemented in Java, is multi-platform, and supports speech-act-based inter-agent communication, effortless distribution over a network, strong negation, and fully customizable agent architectures. Jason is developed by Jomi F. Hübner and Rafael H. Bordini. The current line is Jason 3.x:

- **Jason 3.2 — `release-notes.adoc` dates the line `== version 3.2 (2023-04-15)`.** ⚠️ This document previously said *"01 Apr 2022"*: the **year was wrong**. The GitHub release object `3.2.0` has `published_at` **2023-04-01**, and the Maven artifact appeared 2023-04-15. All three sources agree on **2023**, none on 2022. Contents (verbatim from the release notes): *"Java17 is used"*, the **JasonCLI**, *"Jason is available in Maven Central"*, *"jEdit is removed"*, *"Ant is replaced by Gradle"*, *"Java Web Start is not used anymore"*. Sources: <https://raw.githubusercontent.com/jason-lang/jason/main/doc/release-notes.adoc>, <https://api.github.com/repos/jason-lang/jason/releases>.
- **Jason 3.3 — `release-notes.adoc` dates the line `== version 3.3 (2024-10-21)`.** Contents: *"Java 21 is used"* and *"Agent's thread is Virtual and not Platform. It allow us to run thousands of agents."* This document's **"3.3.0 = 21 Feb 2024" is not an error** — that is exactly the `published_at` of GitHub release `v3.3.0` (`2024-02-21T19:03:41Z`). Two upstream sources describe two different events; **`release-notes.adoc` is authoritative for the release line**, the GitHub API for the release object. The Maven artifact is a third date again — `<lastUpdated>20251009132530</lastUpdated>`, i.e. 2025-10-09, which is why older tutorials still pin `3.2.1`.
- **`Centralised` was renamed to `Local` in Jason 3.0 (2021-09-07)** — verbatim: *"`Centralised` infrastructure was renamed to `Local`. Classes as `RunCentralisedMAS` was renamed to `RunLocalMAS`."* Confirmed by inspecting `jason-interpreter-3.3.0.jar`: **zero** `jason/infra/centralised/**` entries. Source: <https://raw.githubusercontent.com/jason-lang/jason/main/doc/release-notes.adoc>.

Jason is also available as a library, `io.github.jason-lang:jason-interpreter` (latest release **3.3.0**).

**Why migrate:** paradigm modernization (declarative BDI vs. imperative thread/event code), active maintenance and a published textbook vs. a dormant proprietary stack, a formal operational semantics amenable to verification, and an ecosystem (JaCaMo = Jason + CArtAgO + Moise; JADE interop for FIPA).

**The key conceptual shift:** OpenCybele asks "*what activities run and which events do they handle?*"; Jason asks "*what does the agent believe, what goals does it have, and which plans are applicable?*" You re-express event handlers as **plans triggered by events**, and object state as **beliefs**.

### 2. Concept mapping table

| OpenCybele / Cybele concept | Jason / AgentSpeak equivalent | Notes |
|---|---|---|
| Agent (Java class) | Agent (`.asl` source file) | One `name.asl` per agent type; instances declared in the project file. |
| Activity (unit of behavior scheduled by the kernel) | Plan + intention (`+!goal : context <- body.`) | An activity's "run" logic becomes a plan body; a running activity is an intention. |
| One-shot activity | Plan for an achievement goal `!g`, executed once | Triggered by an initial goal or a `!g` in another plan. |
| Periodic / timer activity | Recursive plan with `.wait(Ms)`, or environment-driven timed percepts | Jason has no built-in periodic activity; emulate (see §Timers). |
| Message event + handler | `.send`; plan triggered by `+belief` (from `tell`) or `+!goal` (from `achieve`) | No handler registration needed. |
| Data event / state-change event | Belief addition/deletion events `+b` / `-b` | Changing a belief automatically generates the event. |
| Timer event | `.wait`, `.at` (cron-like), or percepts injected by the environment | See §Timers. |
| Agent state (Java fields) | Beliefs in the belief base | Numeric/relational state becomes ground literals. |
| Directory / naming / community service | Jason agent names (symbolic, resolved by the infrastructure) | ⚠️ **This repo needs no directory at all** — [`INVENTORY.md`](INVENTORY.md) §4 shows all 15 channels (`CH-01`…`CH-15`) with exactly one subscriber each, addressed by literal name. `.df_register`/`.df_search` (only meaningful under `infrastructure: Jade`) and registry agents are listed for completeness, not as a mapping to apply here. |
| Community (grouping of agents) | MAS defined in `.mas2j`; optionally a Moise organization in JaCaMo | The project file lists all agents; richer org structure needs Moise. |
| Agent configuration (`.def`/config/properties) | `.mas2j` project file (+ per-agent options) | Central declaration of infrastructure, agents, environment, classpath. |
| Kernel scheduling / thread pool | Jason reasoning cycle (per-agent) | ⚠️ **"asynchronous by default" was wrong** — see §11. The default `infrastructure: Local` is *one platform-managed thread per agent* running `sense(); deliberate(); act();` once each per cycle; "asynchronous" is an **opt-in** configuration keyword (`asynch`, `asynch_shared`). Source: <https://raw.githubusercontent.com/jason-lang/jason/main/doc/tech/concurrency.adoc>. |
| Migration / load balancing | Not built into centralized Jason | Distribute via JADE; strong mobility is not native. |
| Java business logic inside an activity | Internal action (`.myLib.action`) or environment action | Pure computation → internal action; world-changing effect → environment `executeAction`. |

### 3. Architecture mapping: activity-centric/event-driven → BDI

**OpenCybele model (as documented).** A Cybele application is a set of **agents**, each a collection of **activities**. The kernel schedules activities onto threads and dispatches **events** (message, timer, data) to activity code, handling concurrency transparently. Programmers write Java that mutates state, sends messages, and schedules further activities in response to events; agents register with a directory/community service for discovery.

**Jason model (verified).** A Jason agent is defined by a **belief base** (ground literals, with `[source(...)]` annotations and strong negation `~`), a **plan library** of `triggering_event : context <- body.` rules, an **event queue**, and a set of **intentions** (stacks of partially executed plans). The interpreter runs a **reasoning cycle**: perceive → update beliefs → select an event → find relevant + applicable plans → select an intended means → execute one step → act. Triggering events: belief add/delete (`+b`, `-b`), achievement-goal add/delete (`+!g`, `-!g`), test-goal (`+?g`). Plan bodies contain environment actions, internal actions (dotted: `.send`, `.print`, `.wait`), subgoals (`!g`, `!!g`), and belief updates (`+b`, `-b`, `-+b`).

**The mapping in practice:**

| OpenCybele activity concern | Becomes in Jason |
|---|---|
| "When a message of type X arrives, do Y" | `+x(...)[source(S)] <- Y.` (from `tell`) or `+!x(...)[source(S)] <- Y.` (from `achieve`). |
| "When my timer fires, do Z" | `+!tick <- Z; .wait(Period); !tick.` or `+timer(T) <- Z.` percept. |
| "When my data field changes, react" | Update a belief (`-+field(V)`) so `+field(V) <- ...` fires. |
| "Perform this long computation" | An **internal action** in Java, called from a plan. |
| "Change something in the simulated world" | An **environment action** in `Environment.executeAction`. |
| "Keep running / maintain a condition" | Recursive maintenance-goal plan. |
| "Coordinate with other agents" | `.send`/`.broadcast` with `tell`/`achieve`/`askOne`/`askAll`/**`signal`** (full list in §3.1). |
| "Do these N things concurrently, continue when all are done" | **`!g1 \|&\| !g2`** (fork-join-and) — the direct analogue of `CountDownLatch.await()`. See §3.2. |
| "Do these N things, continue after the first one finishes" | **`!g1 \|\|\| !g2`** (fork-join-xor). See §3.2. |
| "Start this and don't wait for it" | **`!!g`** — spawns a *new intention*. See §3.2. |
| `synchronized` handler method | **`@[atomic]`** plan annotation. ⚠️ Never on a plan that suspends — see §3.2. |


### 3.1 The performative set — all ten, and why `signal` matters here

Source for this whole subsection: <https://raw.githubusercontent.com/jason-lang/jason/main/doc/tech/performatives.adoc> (read 2026-08-18).

| Performative | Effect on the receiver (upstream wording, condensed) |
|---|---|
| `tell` | Receiver adds the content to its **belief base**, annotated `[source(Sender)]`. `.send(a,tell,v(10))` from `b` ⇒ `v(10)[source(b)]` in `a`. |
| `untell` | Receiver **retracts** `content[source(Sender)]`. |
| `achieve` | Receiver adopts the content as a **new goal**: `.send(a,achieve,g(10))` from `b` ⇒ `!g(10)[source(b)]` in `a`. |
| `unachieve` | Receiver **drops** that goal. |
| `askOne` | Ask whether the receiver believes something matching the content; it answers with one belief or `false`. **With a 4th argument it is synchronous** — "this intention is suspended while it waits for the answer", and the answer unifies with that argument. |
| `askAll` | As `askOne`, but retrieves **all** matching beliefs. |
| `askHow` | Ask for **plans** handling a given event: `.send(a,askHow,{+!g(_)},P)` puts a plan list in `P` (add with `.add_plan`). |
| `tellHow` | Send a plan for the receiver's **plan library**. |
| `untellHow` | Ask the receiver to **delete** a plan from its library. |
| **`signal`** | *"The sender adds an event in the receiver."* **"Different of `tell`, if an agent sends the same signal twice, the receiver will have two events."** |

⚠️ `broadcast` is **not** a performative. `.broadcast(Performative, Content)` is an internal action
(`jason/stdlib/broadcast.class` in `jason-interpreter-3.3.0.jar`) that carries one of the ten above.

**Why `signal` is load-bearing for *this* port.** The §10 pitfall "idempotent belief additions don't
re-trigger" is not a curiosity here, it is the dominant traffic pattern. `Station.sendInfo` re-publishes
`STATION.INFO` on every enter/leave ([`Station.java:67`](../src/main/java/cz/vutbr/fit/ags/xhovor07/Station.java#L67),
called from `:63`, `:96`, `:125` — [`INVENTORY.md`](INVENTORY.md) `CH-10`) and `RoadAgent.sendState`
re-publishes `ROAD.STATE` the same way ([`RoadAgent.java:91`](../src/main/java/cz/vutbr/fit/ags/xhovor07/RoadAgent.java#L91),
called from `:87`, `:128`, `:163` — `CH-11`). The payloads are **frequently identical to the previous
one** (a `RoadAgent.State` enum value; a `Station.Info` snapshot). Under `tell`, re-adding an existing
belief raises **no event**, so the GUI-update traffic that the goldens pin would simply vanish.
Under `signal`, *"if an agent sends the same signal twice, the receiver will have two events."*
That is the fix. (The full channel→performative ontology is owned by
[#48](https://github.com/bedaHovorka/OpenCybele1/issues/48) stage 0, not by this document. Its input is
[`message-ontology.md`](message-ontology.md), which fixes the channel→FIPA-act mapping for the JADE
port — [#46](https://github.com/bedaHovorka/OpenCybele1/issues/46) reuses that document's
framework-free half verbatim, so `signal` vs `tell` is the only thing left to decide here.)

### 3.2 Concurrency in plan bodies — `!!`, `|&|`, `|||`, `@[atomic]`

Source for this whole subsection: <https://raw.githubusercontent.com/jason-lang/jason/main/doc/tech/concurrency.adoc>
(read 2026-08-18), plus <https://raw.githubusercontent.com/jason-lang/jason/main/doc/tech/patterns.adoc>
where noted. These four constructs change the port design and were missing from every planning doc.

**`!!g` — spawn a new intention.** In `+!ga <- ...; !!gb; a1; ...` the action `a1` runs *after the
creation of the intention for `gb`*, not after `gb` is achieved. `gb` becomes a **separate** intention,
not part of `ga`'s. Upstream lists `!!`, `|&|` and `|||` as one of the four ways new intentions are created.

**`|&|` — fork-join-and.** In `+!gb <- ...; !g1 |&| !g2; a1; ...` the two subgoals "will be achieved
concurrently by two sub-intentions; when both are finished, the action `a1` will be executed."
This is the direct analogue of the codebase's `CountDownLatch.await()` over N vote replies
([`INVENTORY.md`](INVENTORY.md) §7; the latch is sized `path.size()`).

⚠️ **Two constraints that decide the design:**

1. **Static arity.** `|&|` is a plan-**body** operator: the operands are written literally in the source,
   so the number of forked subgoals is fixed at parse time. (This is a consequence of the syntax upstream
   documents, not a sentence upstream writes.) The vote set here is **dynamic** — one member per path
   element, varying per train — so a literal `!a |&| !b |&| !c` cannot express it. The workable idiom is
   a recursive helper, `+!ask_all([H|T]) <- !ask_vote(H) |&| !ask_all(T).` / `+!ask_all([]).`
   Design owned by [#49](https://github.com/bedaHovorka/OpenCybele1/issues/49).
2. **Failure drops siblings.** Upstream, on failure while achieving a forked subgoal: if there *is* a
   plan-failure (`-!`) handler for it, "the failure is handled by that concurrent sub-intention without
   interference to the other subgoal"; if there is **no** failure plan, fork-join-and "drops also the
   other subgoal and handles the failure in the achievement of goal `gb` above as usual." So an
   unhandled failure in **one** vote request silently cancels **all** the others and fails the parent.
   `.fail_goal(ga)` and `.succeed_goal(gb)` likewise drop the sub-intentions.

**`|||` — fork-join-xor.** Same fork, but "when one sub-intention is finished, the other is dropped".
On failure with no failure plan it "ignore[s] the failure (discarding that concurrent sub-intention) and
continue[s] to try the other subgoal." Precedence: `|&|` binds tighter than `|||`, which binds tighter than `;`.

**`@[atomic]` — the replacement for `synchronized`.** The codebase's handlers are pervasively
`synchronized`; the Jason equivalent is a plan-label annotation:

```
@alabel[atomic]      // no other intention will run if an intention selects this plan
+!update(X)
   <- -vl(T);
      +vl(T+X).
```

"If an atomic plan runs, all its subgoals will be also atomic." Since Jason 2.6 the label functor may be
omitted: `@[atomic] +!g <- a.`

⚠️ **Never annotate a plan that can suspend.** Upstream's own caution is about *duration*, not
suspension: *"it may constraint too much the agent execution: no other intention (even not related to
`g`) will run until `+!g` is finished. The reactivity of the agent can be compromised, specially in cases
where `dosomething` takes a lot of time to execute"*
(<https://raw.githubusercontent.com/jason-lang/jason/main/doc/tech/patterns.adoc>). The stronger
"never suspend inside `atomic`" rule is this project's **derived** rule, not an upstream quote — but it
follows directly: `atomic` blocks *every* other intention until the plan finishes, and it propagates to
subgoals, so a `.wait`, a `.suspend`, or a synchronous 4-argument `askOne` inside an atomic plan parks
the whole agent for the full wait. In this port that is a real hazard, because the vote protocol is
exactly a "block until replies arrive" shape. Rule for [#48](https://github.com/bedaHovorka/OpenCybele1/issues/48):
`@[atomic]` only around short, non-suspending belief updates.

### 4. Actual structure of OpenCybele1 — see `INVENTORY.md`

> **Superseded.** This section previously carried an ASSUMED layout, written when the repository
> could not be read. The real code has since been inventoried in full.
> **[`docs/INVENTORY.md`](INVENTORY.md) is the authoritative Stage-0 inventory** — 19 files,
> 2 415 lines, covering agents (`AG-*`), activities (`ACT-*`), all 15 channels (`CH-*`), events
> (`EVT-*`), all 4 timer sites (`TMR-*`), mutable state (`ST-*`), hardcoded configuration
> (`CFG-*`), nondeterminism (`NDT-*`), observable output (`OUT-*`), GUI coupling (`GUI-*`),
> empirically-determined Cybele runtime semantics (`SEM-*`) and known defects (`DEF-*`).
> Cite those ids; do not re-derive the facts here.

Thirty-second orientation, all of it detailed in `INVENTORY.md`:

- **Build:** Gradle Kotlin DSL (`build.gradle.kts`), JDK 21 toolchain, the two source-less vendor
  jars resolved from `mavenLocal()` as `com.iai:cybele-api:1.0` / `com.iai:cybele-impl:1.0`, and the
  launch flag `--patch-module java.base=cybelle`. Not Ant, not Maven.
- **Source:** `src/main/java/cz/vutbr/fit/ags/xhovor07/` — **4 agent types** (`RailwayMainAgent` ×1,
  `Station` ×8, `RoadAgent` ×7, `Train` dynamic) and **4 activity types** (`Planning`, `Generator`,
  `VoteCollecting`, `PathFinding`). See `INVENTORY.md` §2–§3.
- **There is no Cybele `Agent` base class to extend.** `cybele.kernel.Handler` is a bare marker
  interface; `Agent` and `Activity` are `final` static-only containers. Agents are instantiated by
  class name via reflection and handler methods are bound by **string method name**, so nothing is
  checked at compile time. See `INVENTORY.md` §1.
- **Config:** none for the application — the topology, capacities, delays, arrival rate and
  origin/destination pairs are all hardcoded Java (`INVENTORY.md` §9, `CFG-01`…`CFG-10`).
  `cybelle/cybele.prop` and `cybelle/ICS.prop` configure the *kernel* only (`CFG-11`…`CFG-14`).
- **Domain logic:** a railway simulation whose one non-trivial interaction is a distributed
  voting/election protocol over the whole path of each train (`INVENTORY.md` §7).
- **Zero test code exists** (`INVENTORY.md` CNT-12), and all 33 `assert` statements are disabled at
  runtime (`CNT-07`, `CNT-08`).

### 5. Step-by-step migration process

**Step 0 — Inventory the OpenCybele project. — DONE, see [`docs/INVENTORY.md`](INVENTORY.md).** Enumerate: (a) every agent class, (b) every activity class and whether one-shot or periodic, (c) every message type sent/received and its handler code, (d) every timer, (e) all mutable state fields, (f) all config files. Build a table *agent → activities → events handled → state → messages*. This drives everything else.

**Step 1 — Create the Jason project.** Install Jason 3.x (needs a JDK; 3.3.0 targets Java 21). For developers:
```
git clone https://github.com/jason-lang/jason.git
cd jason
./gradlew config     # builds JasonCLI and prints JASON_HOME / PATH setup
```
Create a project:
```
jason app create opencybele1
```
Generated structure:
```
opencybele1/
  opencybele1.mas2j        # project/config file
  src/agt/                 # .asl agent sources  (older Jason used src/asl)
  src/env/                 # Java environment
  src/... (internal actions)
```

**Step 2 — Translate the config file (`.def`/config → `.mas2j`).** Each Cybele agent named in the descriptor becomes an entry in the `.mas2j` `agents:` block:
```
MAS opencybele1 {
    infrastructure: Local

    environment: env.SimEnv          // only if you need a shared world

    agents:
        producer  agentProducer;
        consumer  agentConsumer #3;  // '#3' instantiates 3 clones

    aslSourcePath: "src/agt";
}
```
⚠️ **`infrastructure: Centralised` no longer exists.** It was renamed to `Local` in **Jason 3.0 (2021-09-07)** — verbatim: *"`Centralised` infrastructure was renamed to `Local`. Classes as `RunCentralisedMAS` was renamed to `RunLocalMAS`."*
(<https://raw.githubusercontent.com/jason-lang/jason/main/doc/release-notes.adoc>). Confirmed by inspection: `jason-interpreter-3.3.0.jar` contains **zero** `jason/infra/centralised/**` entries, and the Gradle template written by `jason app add-gradle` runs `mainClass = 'jason.infra.local.RunLocalMAS'`. **A `.mas2j` written with `Centralised` will not run.**

`Local` is the default single-JVM runtime; use `Jade` for FIPA/distributed deployment — but note that
the whole `Local(...)` execution-mode family in §9.5 is **`Local`-only** and is forfeited by choosing
`Jade` ([#45](https://github.com/bedaHovorka/OpenCybele1/issues/45)). `#N` clones an agent (equivalent to instantiating N copies of the same Cybele class). Startup parameters become either initial beliefs or `args` passed to the environment/agent.

**Step 3 — Translate each agent class into an `.asl` file.** For `AgentProducer.java` → `src/agt/producer.asl`:
- **Initialization** (`initialize()`/`activate()`/constructor) → initial beliefs + an **initial goal**:
  ```
  rate(1000).            // initial belief
  !start.                // initial goal
  +!start : rate(R) <- .print("producer up"); !produce.
  ```
- **Each activity** → one or more plans (Step 4). **Instance fields** → beliefs (Step 6).

**Step 4 — Translate activities into plans.**
- One-shot activity `doWork()` → `+!work : true <- /* body */ .`, invoked via `!work`.
- Periodic activity (repeat every *P* ms):
  ```
  +!produce <- generate_item; .wait(1000); !produce.
  ```
  `.wait(E,T)` also suspends until event `E` occurs or `T` ms elapse — useful to replace event-plus-timeout activities.

**Step 5 — Map message sending/receiving.** Cybele `sendMessage(target, content)` → Jason `.send`:
```
.send(consumer, tell, item(42)).        // informs consumer of a belief
.send(consumer, achieve, process(42)).  // asks consumer to adopt a goal
.send(consumer, askOne, status(S), S).  // synchronous query
.broadcast(tell, ping).                 // to all agents
```
Receiving side (no handler registration):
```
+item(X)[source(S)]     <- .print("got item ",X," from ",S).   // from tell
+!process(X)[source(S)] <- /* do the work */ .                 // from achieve
```
Under the JADE infrastructure, messages from non-Jason FIPA agents surface as `+!kqml_received(Sender, Performative, Content, MsgId)`; write low-level plans for unsupported performatives.

**Step 6 — Handle state (Java fields → beliefs).** Replace each mutable field with a belief and update with `-+` (retract-then-assert):
```
// counter++ becomes:
+!inc : count(N) <- N1 = N+1; -+count(N1).
```
Read access is unification in a plan context or a test goal `?count(N)`. Keep large/opaque data (matrices, external handles) inside internal actions or the environment; store only a reference/summary as a belief.

**Step 7 — Move Java business logic into internal actions or environment actions.**
- **Pure computation:** a **user-defined internal action** — a Java class extending `jason.asSemantics.DefaultInternalAction`, implementing `execute(TransitionSystem, Unifier, Term[])`, placed in a package (e.g. `myLib`), called as `myLib.compute(In, Out)`.
- **World-changing effect / shared simulation state:** an **environment action** — extend `jason.environment.Environment`, implement `executeAction(String agName, Structure action)`, and use `addPercept(...)` to feed results back as percepts.

**Step 8 — Lifecycle mapping.**

| OpenCybele lifecycle | Jason |
|---|---|
| Construction / `initialize()` | Initial beliefs + initial goal `!start` |
| `activate()` / start | First plan(s) triggered by `!start` |
| Register with community/directory | `.mas2j` membership; optional `.df_register` |
| Deactivate / stop | `.drop_all_intentions` / `Environment.stop()` |
| Migration | Not native; use JADE infra if distribution needed |

**Step 9 — Custom architecture / belief base (only if needed).** If Cybele code overrode low-level perception, message filtering, or scheduling, replicate by: extending `jason.asSemantics.Agent` (override `selectEvent`, `selectOption`, belief-revision `brf`, or the "social" acceptance function); and/or extending `jason.architecture.AgArch` (override `perceive`, `checkMail`, `act`); and/or a custom belief base extending `DefaultBeliefBase` (or `ChainBBAdapter`). Declare these per-agent in `.mas2j` (`agentClass`, `agentArchClass`, `beliefBaseClass`). Most projects do **not** need this — prefer plain `.asl` + internal actions first.

### 6. Timer/scheduling handling in detail

Cybele timer events are first-class; Jason has three idiomatic replacements:
1. **`.wait` loop** (simplest per-agent periodic behavior): `+!tick <- doPeriodic; .wait(500); !tick.`
2. **`.at`** for cron-like scheduling of a future goal.
3. **Environment-driven percepts:** a Java `Timer`/`ScheduledExecutorService` in the `Environment` calls `addPercept(Literal.parseLiteral("tick"))`; the agent reacts with `+tick <- ...`. Use this when many agents must share one clock, mirroring Cybele's kernel-driven timing.

Prefer option 3 for deterministic cross-agent timing; option 1 for simple per-agent periodicity.

### 7. Concrete before/after examples

> **Superseded as a description of this repository.** The "before" snippets below are ILLUSTRATIVE
> reconstructions in the generic OpenCybele/CybelePro idiom, written before the code could be read.
> **They do not resemble OpenCybele1's actual code** and must not be used as a model of it:
> OpenCybele1 has no `Agent` base class to extend, no `initialize()`, no `addActivity`, no
> `PeriodicActivity`, no `registerMessageHandler`, and no `Message` type — see
> [`docs/INVENTORY.md`](INVENTORY.md) §1. The real idiom is
> `Activity.openChannel(name, "methodName", handler)` + `Activity.sendAll(name, Serializable[])`
> with untyped positional payloads (`INVENTORY.md` §4), and one-shot `Activity.setTimer` only —
> there are **zero** repeating timers (`INVENTORY.md` §6, `TMR-01`…`TMR-04`), so `TickerBehaviour`
> / a self-looping `!produce` plan is *not* the default mapping. Keep the snippets below only as
> generic Jason-side examples; take the "before" side from `INVENTORY.md`.

**7.1 Periodic producer activity — OpenCybele (illustrative):**
```java
public class ProducerAgent extends Agent {
    private int count = 0;
    public void initialize() {
        addActivity(new PeriodicActivity(1000) {   // every 1000 ms
            public void run() {
                count++;
                sendMessage("consumer", new Message("item", count));
            }
        });
    }
}
```
**Jason (`src/agt/producer.asl`):**
```
count(0).
!produce.

+!produce : count(N)
   <- N1 = N + 1;
      -+count(N1);
      .send(consumer, tell, item(N1));
      .wait(1000);
      !produce.
```

**7.2 Message-handling consumer — OpenCybele (illustrative):**
```java
public class ConsumerAgent extends Agent {
    public void initialize() {
        registerMessageHandler("item", new MessageHandler() {
            public void handle(Message m) { process((Integer) m.getContent()); }
        });
    }
    private void process(int x) { /* ... */ }
}
```
**Jason (`src/agt/consumer.asl`):**
```
+item(X)[source(S)]
   <- .print("received item ", X, " from ", S);
      !process(X).

+!process(X) : X mod 2 == 0 <- .print(X," is even").
+!process(X)                <- .print(X," is odd").
```

**7.3 Business logic as an internal action — `src/java/myLib/heavyCompute.java`:**
```java
package myLib;
import jason.asSemantics.*;
import jason.asSyntax.*;

public class heavyCompute extends DefaultInternalAction {
    @Override
    public Object execute(TransitionSystem ts, Unifier un, Term[] args) throws Exception {
        int in = (int)((NumberTerm)args[0]).solve();
        int out = expensive(in);                 // ported Java logic
        return un.unifies(args[1], new NumberTermImpl(out));
    }
    private int expensive(int n){ /* ... */ return n*n; }
}
```
**Call site (`.asl`):** `+!work(N) <- myLib.heavyCompute(N, R); .print("result ", R).`

**7.4 World-changing effect as an environment action — `src/env/SimEnv.java`:**
```java
package env;
import jason.asSyntax.*;
import jason.environment.*;

public class SimEnv extends Environment {
    @Override public void init(String[] args) { addPercept(Literal.parseLiteral("clock(0)")); }

    @Override public boolean executeAction(String ag, Structure act) {
        if (act.getFunctor().equals("emit")) {
            addPercept(Literal.parseLiteral("signal(on)"));
            return true;
        }
        return false;
    }
}
```
**`.asl`:** `+!go <- emit. +signal(on) <- .print("world changed").`

### 8. Build and tooling migration

| Concern | OpenCybele1 (assumed) | Jason target |
|---|---|---|
| Build tool | Ant `build.xml` or Maven `pom.xml`, `cybele.jar` on classpath | Gradle (Jason's native tooling) or run via the `jason` CLI |
| Dependency | Local Cybele jars | `implementation 'io.github.jason-lang:jason-interpreter:3.3.0'` |
| Run | Custom `main` starting the community/kernel | `jason opencybele1.mas2j` (uses Gradle), `jason mas start --mas2j=opencybele1.mas2j` (no Gradle), or directly `java -cp … jason.infra.local.RunLocalMAS opencybele1.mas2j` — which is exactly what the generated Gradle `run` task does |
| IDE | Generic Java IDE | **VS Code** Jason extension (current); `jason-cli`; **jEdit IDE is legacy and was removed in Jason 3.2** |
| Distribution | Cybele runtime | `./gradlew release` → zip in `build/distributions` |

Add Gradle to an existing Jason app with `jason app add-gradle`; run under JADE with `./gradlew runJade`. Recommended `build.gradle`:
```groovy
plugins { id 'java'; id 'application' }
repositories { mavenCentral() }
dependencies { implementation 'io.github.jason-lang:jason-interpreter:3.3.0' }
```

### 9. Testing and validation strategy

1. **Behavioral-parity harness.** Before changing anything, capture the OpenCybele system's observable outputs (messages sent, log lines, final state) for a fixed scenario. Reproduce the same scenario in Jason and diff.
2. **Per-agent tests.** Write `.asl` **tester agents** that assert expected beliefs after known message sequences: include `{ include("$jasonJar/test/jason/inc/tester_agent.asl") }`, annotate plan labels `@[test]`, and assert with `!assert_equals(Expected, Actual [, Tolerance])` / `!assert_true` / `!assert_false` from `test_assert.asl`. Also use the **Jason Mind Inspector** (http://localhost:3272) to inspect beliefs/intentions at runtime. ⚠️ There is **no `jason.asunit` package** in Jason 3.3.0 — see [`TESTING.md`](TESTING.md) §5. Source: <https://raw.githubusercontent.com/jason-lang/jason/main/doc/tech/unit-tests.adoc>.
3. **Message-trace validation.** Use the MAS Console and the mind inspector to confirm each `tell`/`achieve` produces the intended belief/goal.
4. **Timing tests.** Verify periodic behavior counts over a fixed window match the Cybele period.
5. **Execution mode is set on the `infrastructure:` line — and upstream claims no determinism from it.**
   ⚠️ The previous wording ("run in synchronous mode for reproducible runs, switch back to asynchronous — the default") was wrong on three counts: there is no global sync/async switch, asynchronous is not the default, and
   [`concurrency.adoc`](https://raw.githubusercontent.com/jason-lang/jason/main/doc/tech/concurrency.adoc) makes **no determinism or reproducibility claim about any configuration**. What it actually documents (verbatim: *"Different concurrency configurations can be set for the **Local** infrastructure in Jason"*):

   | `.mas2j` form | Meaning |
   |---|---|
   | `infrastructure: Local` | Default. **One thread per agent**, `sense(); deliberate(); act();` once each per cycle. |
   | `Local(threaded, S, D, A)` | One thread per agent; `S`/`D`/`A` cycles per stage. Act is capped at `min(A, numberOfIntentions())`. |
   | `Local(pool, N [, C])` | A pool of `N` threads for all agents; `C` = reasoning cycles per turn (default 5). |
   | `Local(pool, N, S, D, A [, C])` | Same, with per-stage cycle counts. |
   | `Local(synch_scheduled, N)` / `(N, C)` / `(N, S, D, A)` | Pool of `N` threads, but **one stage per turn** (sense → requeue → deliberate → requeue → act). `C` is *not* a reasoning-cycle count here. |
   | `Local(asynch_shared, N, S, D, A)` | One shared pool of `N` threads; stages run **concurrently**. |
   | `Local(asynch, ST, DT, AT [, S, D, A])` | **Three** pools, one per stage. |
   | per-agent: `ana [cycles_sense=2, cycles_deliberate=2, cycles_act=10];` / `bob [cycles=10];` | Per-agent cycle budgets, "a kind of priority by giving more CPU for certain agents than others". |

   - ⚠️ **These are `Local`-only.** They do not exist for `infrastructure: Jade` — choosing `Jade` forfeits this lever entirely ([#45](https://github.com/bedaHovorka/OpenCybele1/issues/45)).
   - ⚠️ **Single-threaded execution comes from `N = 1`**, e.g. `Local(pool,1)` — *not* from the word `synch_scheduled`, which is about stage granularity, not thread count ([#43](https://github.com/bedaHovorka/OpenCybele1/issues/43)).
   - Treat "reproducible test runs" as something the **harness** must establish (fixed seeds, a simulated clock, a normalizer), not something an infrastructure keyword grants.

### 10. Common pitfalls

- **Idempotent belief additions don't re-trigger.** Re-adding an existing belief produces no event. Three fixes, in order of preference for this port: (1) **use the `signal` performative instead of `tell`** — *"Different of `tell`, if an agent sends the same signal twice, the receiver will have two events"* (<https://raw.githubusercontent.com/jason-lang/jason/main/doc/tech/performatives.adoc>); (2) `-+` (belief update); (3) distinct terms. ⚠️ **This is not hypothetical here** — `STATION.INFO` and `ROAD.STATE` are re-sent on every enter/leave with frequently identical payloads ([`Station.java:67`](../src/main/java/cz/vutbr/fit/ags/xhovor07/Station.java#L67), [`RoadAgent.java:91`](../src/main/java/cz/vutbr/fit/ags/xhovor07/RoadAgent.java#L91); [`INVENTORY.md`](INVENTORY.md) `CH-10`, `CH-11`), so under `tell` most of the GUI trace would disappear. See §3.1.
- **Treating plans as method calls.** A plan is selected by event + context, not invoked by name; design triggers, not call graphs.
- **Overusing `.wait`.** A long `.wait` blocks its intention (not the whole agent). For shared timing, drive percepts from the environment.
- **Porting thread code literally.** Don't recreate Cybele's thread pool; let the reasoning cycle schedule intentions. Push genuine parallel computation into internal actions / Java threads inside the environment.
- **Losing message content types.** Cybele messages may carry arbitrary Java objects; AgentSpeak content is logical terms. Serialize complex payloads to terms or keep them in the environment and pass identifiers.
- **Directory-service assumptions.** If discovery relied on Cybele's community/directory, replace with `.mas2j` membership or DF actions; don't assume dynamic lookup exists by default. ⚠️ **This repo has no directory usage at all** ([`INVENTORY.md`](INVENTORY.md) §4 — 15 channels, one subscriber each, addressed by literal name), so there is nothing to replace.

### 11. Performance & threading considerations

OpenCybele's kernel explicitly manages threads, concurrency control, and load balancing across activities. Jason instead runs each agent through a **reasoning cycle** of three stages — *sense*, *deliberate*, *act*.

⚠️ **Correction.** This section previously said agents run "asynchronously by default, with a synchronous mode that steps all agents together". Neither half is upstream. What
[`concurrency.adoc`](https://raw.githubusercontent.com/jason-lang/jason/main/doc/tech/concurrency.adoc) says is that the **default** `infrastructure: Local` gives each agent **its own thread** running `sense(); deliberate(); act();` once each per cycle — a *synchronous* (sequential-stage) cycle, "one thread per agent". *Asynchronous* means the three **stages** may overlap, and it is opt-in via the `asynch` / `asynch_shared` keywords (§9.5). Nothing "steps all agents together" unless you build it. Since Jason 3.3 each agent's thread is **virtual, not platform** — verbatim from the release notes, *"It allow us to run thousands of agents"*. Implications:
- CPU-bound work inside a plan stalls that agent's cycle — offload to internal actions / environment threads.
- No automatic agent migration or load balancing in centralized Jason; for scale-out use the JADE infrastructure and distribute agents across containers.
- The single-thread-per-agent reasoning model removes most intra-agent data races, but shared state must live in the (thread-safe) environment.

### 12. Limitations — things that don't map cleanly

- **Strong agent mobility / migration** — native in Cybele; not first-class in Jason.
- **Kernel load balancing** — no direct equivalent.
- **Arbitrary Java-object messages** — must be reduced to logical terms or held in the environment.
- **Fine-grained activity scheduling/priorities** — approximated via custom `selectEvent`/`selectOption`, but not identical semantics.
- **Implicit multi-source event fusion** — becomes explicit percepts/messages in Jason.

Where a feature has no equivalent, isolate it behind an internal action or the environment layer so the AgentSpeak logic stays clean.

---

## Recommendations

**Stage 1 — De-risk (before writing any AgentSpeak).**
1. Clone the repo and replace §4 with the real inventory: `git clone -b develop …`, then list agent classes, activities (one-shot vs. periodic), message types, timers, state fields, and config files.
2. Build the *agent → activities → events → state → messages* table. **Benchmark that changes the plan:** if the repo turns out to rely heavily on agent migration, kernel load balancing, or arbitrary-object message payloads, budget extra time for the §12 limitations and consider the JADE infrastructure from the start.

**Stage 2 — Vertical slice.**
3. Scaffold with `jason app create`; write the `.mas2j` from the Cybele descriptor.
4. Port the *single simplest* agent end-to-end (init → one activity → one message) and validate it against the parity harness before doing more. **Threshold:** do not proceed until this slice reproduces the Cybele output exactly.

**Stage 3 — Breadth.**
5. Port remaining agents; convert fields → beliefs, activities → plans, `sendMessage` → `.send`.
6. Extract heavy Java into internal actions and world-effects into the `Environment`.
7. Replace timers (prefer environment-driven percepts for shared clocks); wire discovery only if the original used it.

**Stage 4 — Harden and ship.**
8. Run the full parity harness; validate message traces in the Mind Inspector; tune execution mode.
9. Switch the build to Gradle (`jason-interpreter:3.3.0`), set up VS Code, and document.

**Decision rule:** keep logic in `.asl` by default; drop to Java (internal actions, then environment, then custom `Agent`/`AgArch`/belief base) only when a specific need forces it — in that order of increasing complexity.

**References/resources.** Jason site & docs: https://jason-lang.github.io/ ; source: https://github.com/jason-lang/jason (see `doc/tutorials/getting-started`, `doc/faq`, and `doc/tutorials/jason-jade`). The Jason book: Bordini, Hübner & Wooldridge, *Programming Multi-Agent Systems in AgentSpeak using Jason* (Wiley). Key API: `.send`, `.wait`, `.broadcast`, `DefaultInternalAction`, `Environment`, `Agent`, `AgArch`, `DefaultBeliefBase`. JaCaMo (Jason + CArtAgO + Moise) for environment artifacts and organizations. OpenCybele/Cybele background: NASA Tech Brief "Activity-Centric Approach to Distributed Programming" (Levy, Satapathy, Lang — NTRS 20110020334 / MSC-23239) and the NASA Spinoff 2010 article on Cybele/ACES.

---

## Caveats

- **~~The single largest caveat: the source could not be inspected.~~ Superseded ([#9](https://github.com/bedaHovorka/OpenCybele1/issues/9)).** The repository has been read in full and inventoried in **[`docs/INVENTORY.md`](INVENTORY.md)**. Any remaining ASSUMPTION label in §7's "before" snippets means *that snippet is illustrative*, not that the repo is unknown. Cite `INVENTORY.md` row ids (`AG-*`, `ACT-*`, `CH-*`, `TMR-*`, `ST-*`, `CFG-*`, `SEM-*`, `DEF-*`) rather than re-deriving facts.
- **OpenCybele API names used here** (`Agent`, `Activity`, `PeriodicActivity`, `sendMessage`, `registerMessageHandler`, `initialize`/`activate`) reflect common Cybele conventions and public descriptions; exact class/method names in this project may differ. Verify against the OpenCybele jars/Javadoc actually referenced by the repo.
- **Jason version drift.** Details verified **2026-08-18** against Jason **3.3.0** (latest release) and the `main`-branch prose docs. Confirm the latest release, dependency coordinate, and CLI commands against the Jason repo before starting, as tooling (e.g., `src/agt` vs. `src/asl`, project-file conventions) has changed across 3.x. Two known traps: `3.3.3` is an *in-development heading*, not a release; and the 3.3.0 "release date" has three defensible answers (see §1).
- **`.mas3j`/JaCaMo.** JaCaMo projects use a `.jcm` (and historically `.mas2j`-family) descriptor rather than plain Jason `.mas2j`; if you adopt CArtAgO/Moise, the project-file syntax and build differ from the plain-Jason path shown here.
- **Distribution semantics.** Moving from `Local` to `Jade` changes messaging, naming, and discovery behavior; re-test communication after any infrastructure switch, especially interop with non-Jason FIPA agents via `kqml_received`.