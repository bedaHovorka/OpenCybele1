# Should You Migrate OpenCybele to JADE First (Before Jason)?

<a id="upstream-verification"></a>

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
> *"JADE 4.0, last update 08-April-2010"*; cite that URL, not `jade.tilab.com`. ⚠️ Note the **edition
> lags the pinned artifact** (guide 4.0/2010 vs. JADE 4.3.3/2014). It is cited here only for
> behaviour-class semantics and the AMS/DF/topics model, all of which predate 4.0 and are unchanged in
> 4.3.3 (topics exist "since version 3.5"); do not rely on it for anything introduced after 4.0.

## TL;DR
- **No — do not do a full OpenCybele→JADE rewrite as a mandatory stepping stone if your end goal is Jason/BDI.** The BDI paradigm shift you are trying to reach is done exactly once whether you go direct or via JADE, so an intermediate JADE rewrite mostly adds a second full migration you will later have to partially throw away.
- **The single most useful insight:** Jason already runs *on top of* JADE. Setting `infrastructure: Jade` in your `.mas2j` (and running `./gradlew runJade`) gives you JADE's FIPA-ACL messaging, AMS/DF, containers and network distribution *without hand-writing a single JADE agent*. (**This app needs none of the DF half** — see the mapping-table note on "Directory / community" below and [`INVENTORY.md`](INVENTORY.md) §4.) You can also mix native JADE agents and Jason agents on one platform, enabling a gradual agent-by-agent migration.
- **JADE-first is only the right call in narrow cases:** (a) your true endpoint is a maintained imperative Java platform and you do *not* actually need BDI (then stop at JADE and skip Jason); or (b) you must interoperate immediately with existing external FIPA agents / heavy object-payload messaging / mobility while you defer the BDI rewrite. Otherwise go **OpenCybele→Jason directly, on the JADE infrastructure**.

## Key Findings

**1. Conceptual distance is highly asymmetric.** OpenCybele/Cybele and JADE are *both* imperative, event/activity-driven Java frameworks, so that port is largely mechanical. Cybele's "Activity-Centric Programming" models an agent as a set of activities driven by message, timer, and internal events — which maps almost one-to-one onto JADE's `Behaviour` classes. The OpenCybele→Jason jump, by contrast, is a genuine paradigm change to declarative BDI (beliefs, goals/desires, plans, intentions) in an AgentSpeak logic language. That paradigm shift is the expensive part, and **it is identical in size whether or not you stop at JADE on the way**.

**2. Jason-on-JADE means you don't need to hand-write JADE to get JADE's benefits.** Jason's JADE infrastructure (`jason.infra.jade.JadeAgArch`) wraps each AgentSpeak agent as a JADE agent, translating between Jason's KQML-style performatives and FIPA-ACL. So the classic reasons to adopt JADE — network distribution, FIPA interoperability, DF yellow-pages, AMS — are all reachable *from Jason directly*. This substantially undercuts the rationale for a separate JADE rewrite.

**3. Both target frameworks are mature but slow-moving; core JADE is effectively dormant.** JADE's last official release is **4.6.0, dated 07/12/2022**, whose own announcement notes "Quite a lot of time has passed since the last official release" (it added message-queue customization and a Kafka-based `MomMessagingService` add-on). The original Telecom Italia/TILAB team stepped back around 2021. Ongoing maintenance now lives in community forks — most actively the **EnFlexIT/JADE** fork, published to Maven Central under the GAV `de.enflexit.jade:de.enflexit.jade` (group *and* artifact are both `de.enflexit.jade`), at **4.6.5** as of 2025-03-31 — versions `4.6.2, 4.6.3, 4.6.4, 4.6.5`, `<release>4.6.5</release>` per <https://repo1.maven.org/maven2/de/enflexit/jade/de.enflexit.jade/maven-metadata.xml> (verified 2026-08-18) — packaged as an OSGi bundle. Jason is more alive: the latest **released** version is **3.3.0**; the 3.3 line "uses Java 21" with virtual-thread agents ("It allow us to run thousands of agents") and actively maintains the JADE bridge. Sources: <https://raw.githubusercontent.com/jason-lang/jason/main/doc/release-notes.adoc> (`== version 3.3 (2024-10-21)`), <https://repo1.maven.org/maven2/io/github/jason-lang/jason-interpreter/maven-metadata.xml>. This matters: **spending months porting to JADE means investing heavily in the *less* actively maintained of the two targets.**

**4. Double migration cost is the killer argument against JADE-first.** OpenCybele→JADE→Jason means (i) a full imperative-to-imperative rewrite, then (ii) the BDI rewrite anyway, on top of a JADE codebase that must itself be maintained in the interim. You pay for two migrations and still face the BDI rewrite at the end.

## Details

### JADE fundamentals relevant to the decision
JADE (Java Agent DEvelopment Framework) is a FIPA-compliant, fully-Java multi-agent middleware originally built by Telecom Italia Lab. Its core model is imperative and object-oriented:

- **`Agent` class**: you subclass `jade.core.Agent`, override `setup()` (and `takeDown()`), and register behaviours with `addBehaviour()`. Agents pass through a FIPA lifecycle (initiated, active, waiting, suspended, transit, deleted).
- **Behaviours** (units of execution, added/removed dynamically):
  - `OneShotBehaviour` — runs its `action()` once and terminates.
  - `CyclicBehaviour` — `action()` runs repeatedly for the agent's life; the canonical pattern for message reception (`receive()`/`blockingReceive()` in a loop).
  - `TickerBehaviour` — periodic `onTick()` at a fixed interval.
  - `WakerBehaviour` — runs once after a specified delay.
  - `FSMBehaviour` — finite-state-machine composition of sub-behaviours.
  - `ParallelBehaviour` / `SequentialBehaviour` — run children concurrently (terminating when ALL/N/ANY complete) or in sequence.
- **Messaging**: asynchronous `ACLMessage` objects carrying FIPA performatives (REQUEST, INFORM, CFP, PROPOSE, ACCEPT_PROPOSAL, REFUSE, FAILURE, etc.), with pattern-matching on the message queue and support for object content, ontologies and content languages (FIPA-SL).
- **Infrastructure services**: the **AMS** (Agent Management System — white pages, lifecycle) and **DF** (Directory Facilitator — yellow-pages service discovery, via `jade.domain.DFService`).
- **Containers/platforms/distribution**: a platform is a set of *containers*, each a JVM, possibly on different machines/OSes; the topology can be reconfigured at runtime via a GUI. JADE supports **agent mobility** (moving/cloning agents between containers) and interaction protocols (Contract Net, AchieveRE, etc.).
- **License / stewardship**: distributed by Telecom Italia under the LGPL (the TILAB site states LGPL Version 2, © Telecom Italia S.p.A.; the community forks are labelled LGPL v2.1). The official site states the "minimal software requirement to run it is Java 5."
- **Maintenance status**: last official release **4.6.0 (07/12/2022)**; the original team stepped back around 2021; active maintenance now flows through community forks, notably **EnFlexIT/JADE** (`de.enflexit.jade:de.enflexit.jade` **4.6.5** on Maven Central, an OSGi bundle re-packaging the current TILAB SVN code). Treat core JADE as **stable-but-dormant, with a live community fork**.
- **Distribution reality check** — three candidate coordinates, three different answers, all re-checked **2026-08-18**:
  - **TILAB `com.tilab:jade` — absent.** <https://repo1.maven.org/maven2/com/tilab/jade/> returns **404**.
  - **`de.enflexit.jade:de.enflexit.jade` — present**, versions `4.6.2, 4.6.3, 4.6.4, 4.6.5`. ⚠️ The *group* directory is <https://repo1.maven.org/maven2/de/enflexit/jade/> and the *artifact* directory is one level deeper (`…/de/enflexit/jade/de.enflexit.jade/`), because the artifactId repeats the groupId. An earlier probe that stopped at the group directory concluded "not on Maven Central" — that conclusion was **wrong**; see the Caveats section.
  - **`net.sf.ingenias:jade:4.3` — present**: a complete JADE **4.3.3 rev 6726 of 2014/12/09** distribution jar (2.6 MB). **This is what this project pins**, per decision [#26](https://github.com/bedaHovorka/OpenCybele1/issues/26).
- **Which one this project uses, and why (decision [#26](https://github.com/bedaHovorka/OpenCybele1/issues/26), closed):** `net.sf.ingenias:jade:4.3`. The deciding factor is **Phase-2 alignment, not maintenance**: Jason 3.3.0's POM declares exactly **three** dependencies, and its **only** JADE dependency is `net.sf.ingenias:jade:4.3` — verified at <https://repo1.maven.org/maven2/io/github/jason-lang/jason-interpreter/3.3.0/jason-interpreter-3.3.0.pom>. Pinning the same artifact in Phase 1 means `infrastructure: Jade` in Phase 2 cannot produce a JADE version conflict. This document's earlier recommendation of the EnFlexIT fork is therefore **superseded for this repo**; the fork remains the right answer for a long-lived standalone JADE product, and is a one-line coordinate change if a later phase needs 4.6.x.
- **Java 17/21 notes — measured, not inferred.** JADE predates the Java module system, and `--illegal-access` no longer works: on OpenJDK 21.0.11 the JVM answers `Ignoring option --illegal-access=permit; support was removed in 17.0`. That makes `--add-opens`/`--add-exports` a *plausible* need for pre-Java-9 libraries — but for **the artifact this project actually uses it is not needed**. Measured 2026-08-18 on **OpenJDK 21.0.11** (Red Hat build 21.0.11+10), Fedora, headless, with a real agent:

  ```
  # Smoke.java: setup() -> send an INFORM to itself -> blockingReceive(5000) -> doDelete(); takeDown() prints
  javac -cp jade-4.3.jar -d out Smoke.java
  java  -cp jade-4.3.jar:out jade.Boot -nomtp smoke:Smoke
  ```

  Observed: `This is JADE 4.3.3 - revision 6726 of 2014/12/09`, then `setup()` on
  `smoke@…/JADE`, the self-addressed `INFORM` received with content intact,
  `doDelete()` accepted and `takeDown()` executed. A programmatic variant
  (`Runtime.instance().createMainContainer(...)` → `createNewAgent(...).start()` → `container.kill()`)
  also completed. **No `--add-opens`, no `--add-exports`, no reflection warnings, no exceptions.**
  So this exercises **container boot, service initialisation, agent creation, ACL send/receive, and the
  `doDelete()`/`takeDown()` teardown path** — the set [#26](https://github.com/bedaHovorka/OpenCybele1/issues/26) asked for.
  `jade.core.messaging.TopicManagementService`, `jade.core.messaging.TopicManagementHelper`,
  `jade.domain.DFService` and `jade.domain.AMSService` are all present in the jar.

  ⚠️ **A malformed command line was recorded here in an earlier revision** and is corrected above:
  `jade.Boot -gui:false -nomtp -container:false smoke:jade.core.Agent`. In JADE 4.3.3 `-gui`, `-nomtp`
  and `-container` are **valueless flags**, so `-container:false` parses as a *property* named
  `container:false` which then swallows `smoke:jade.core.Agent` as its value. Measured consequences:
  **no agent named `smoke` is ever created** (0 occurrences in the run log) and `-nomtp` is silently
  not honoured (the HTTP MTP still starts and logs `MTP addresses: http://…:7778/acc`); dropping the
  trailing agent spec aborts with `IllegalArgumentException: No value specified for property
  "container:false"`. That run therefore proved container boot and service init **only** — the earlier
  claim of "boot and messaging" over-read it. The corrected run proves strictly more.

  ⚠️ **Correction: the `Thread.stop()` / `SecurityManager` caveat is empirically false for this
  artifact.** An earlier revision of this file restated [#26](https://github.com/bedaHovorka/OpenCybele1/issues/26)'s
  warning that `Thread.stop()` (throws `UnsupportedOperationException` since JDK 20) and
  `SecurityManager` (deprecated for removal) "sit on" the agent-kill and container-shutdown paths.
  Measured by disassembling **all 1 815 classes** in `jade-4.3.jar` (`javap -p -c`, 360 855 lines):
  **zero** call sites for `Thread.stop()`, `Thread.suspend()` or `Thread.resume()`, and **zero**
  references to `SecurityManager` anywhere in the jar. That caveat **over-states** the risk — it points
  testers at a hazard that is not present — and is withdrawn.

  The real residual risks for [#13](https://github.com/bedaHovorka/OpenCybele1/issues/13)/[#36](https://github.com/bedaHovorka/OpenCybele1/issues/36)/[#38](https://github.com/bedaHovorka/OpenCybele1/issues/38), which the smoke test does **not** clear, are:
  - **`System.exit` inside the library — 28 call sites**, including `jade.Boot` and `jade.core.Runtime$1`
    (the `"JADE is closing down now."` → `System.exit(0)` hook). An in-process test JVM must never let
    that path fire; keep `Runtime.setCloseVM` at its default and never launch via `jade.Boot` from a test.
  - **The JVM does not exit by itself after `container.kill()`** — measured: `main` returned in ~6 s and
    the JVM was still alive at 25 s. JADE leaves non-daemon threads behind, so an `integrationTest`
    source set needs `forkEvery`/timeouts, not a reliance on natural termination.
  - **Container shutdown ordering and JICP socket teardown** between test classes — the
    `jade.core.Runtime` singleton is JVM-wide, so class-to-class reuse is the thing to test, not `Thread.stop()`.

  Because Jason 3.3 requires Java 21, when you use Jason-on-JADE the classpath comes bundled and this is handled for you via Gradle.

### Cybele/OpenCybele side (general background; repo specifics now in `INVENTORY.md`)
Cybele/CybelePro is a proprietary Java agent infrastructure from **Intelligent Automation, Inc.** — the mark CYBELEPRO (USPTO serial 77019994, reg. 3257603, filed Oct 12, 2006, status *Registered and Renewed*) is described as "Agent infrastructure software for agent programming for use in distributed computing environments." It has been used for military logistics, simulation, and transportation control. Its programming model is **Activity-Centric Programming (ACP)**, and per the DCF/ACM paper "CybelePro™ is built on top of the Java 2 platform" with services including "concurrency management, event handling, thread-management, internal event services, communication, timer, data sharing, GUI services, sender side fil[tering]," plus migration and load balancing. NASA's Technical Reports Server ("Activity-Centric Approach to Distributed Programming," NTRS 20110020334) states that Cybele "provides support for event handling from multiple sources, multithreading, concurrency control, migration, and load balancing," and that "activity centric programming relieves application programmers of the complex tasks of thread management, concurrency control, and event management." **OpenCybele** is an open reimplementation in that lineage. Everything above is general Cybele/CybelePro background. **The repo itself has since been read in full and inventoried in [`docs/INVENTORY.md`](INVENTORY.md)** (issue [#9](https://github.com/bedaHovorka/OpenCybele1/issues/9)); wherever this document generalises about "Cybele applications", `INVENTORY.md` is the authority for *this* application.

### Mapping table: OpenCybele/Cybele activities → JADE behaviours (for the JADE path)
| Cybele/OpenCybele concept | JADE equivalent | Notes on mechanical-ness |
|---|---|---|
| Periodic / timer activity | `TickerBehaviour` (`onTick()`) | ⚠️ **Not the mapping this repo needs.** `TickerBehaviour` is "a cyclic task that must be executed periodically" (Programmer's Guide §3.4.10, <https://jade-project.gitlab.io/docs/programmersguide.pdf>) — but **this codebase has zero repeating timers**. All four timer sites ([`INVENTORY.md`](INVENTORY.md) §6, `TMR-01`…`TMR-04`) are one-shot or **self-rearming with a freshly drawn interval**: `TMR-02` redraws an exponential delay on every fire, `TMR-03`/`TMR-04` are per-train/per-traversal one-shots, `TMR-01` is a bootstrap. The faithful shape is `WakerBehaviour` ("a one-shot task … executed only once just after a given timeout is elapsed", §3.4.9) that re-arms itself. See [#33](https://github.com/bedaHovorka/OpenCybele1/issues/33). |
| One-shot activity | `OneShotBehaviour` (`action()`) | Near 1:1 |
| Delayed / wake-after activity | `WakerBehaviour` | Near 1:1 |
| Message-handler activity | `CyclicBehaviour` + `receive()`/`blockingReceive()` with `MessageTemplate` | Direct; you rewrite the dispatch loop |
| Activity-transition / staged logic | `FSMBehaviour` or `SequentialBehaviour` | Moderate; re-express transitions |
| Concurrent activities | `ParallelBehaviour` (or threaded behaviours) | Direct |
| Cybele message | `ACLMessage` + performative | Object payloads → ACL content/ontology; some redesign |
| Directory / community | **AMS** (white pages) — applies. **DF** (yellow pages) — **does not apply to this repo.** | The AMS half is right: JADE addresses agents by `AID`, and the AMS "provides white-page and life-cycle service, maintaining a directory of agent identifiers (AID) and agent state" (Programmer's Guide §3.1 "The Agent Platform", <https://jade-project.gitlab.io/docs/programmersguide.pdf>). The DF half is wrong here: the DF "provides the default yellow page service" (same source), and **nothing in this application advertises or searches a capability** — [`INVENTORY.md`](INVENTORY.md) §4 shows all 15 channels (`CH-01`…`CH-15`) with **exactly one subscriber each**, addressed by literal name. The faithful mapping is therefore **direct AID unicast**, not DF lookup. JADE **topics** (`jade.core.messaging.TopicManagementHelper.createTopic()`/`register()`, `MessageTemplate.MatchTopic()`, with `TopicManagementService` activated on every container; available "since version 3.5" — Programmer's Guide §3.3.5) are the right tool for the *probe*, which needs to observe traffic it is not the named receiver of — not for normal delivery. The channel→`ACLMessage` design itself is owned by [#27](https://github.com/bedaHovorka/OpenCybele1/issues/27). |
| Node / container / distribution | JADE **container**/**platform** | Direct conceptually |
| Agent migration / load balancing | JADE agent **mobility** service | Direct concept, different API; Cybele load-balancing has no exact JADE analogue |

This is why the OpenCybele→JADE port is best described as **mechanical but not free** — the control structures map cleanly, but object-rich Cybele messaging must be re-expressed as ACL content, and any Cybele-specific services (sender-side filtering, load balancing) have no exact JADE counterpart.

### What a later JADE→Jason step still requires
Even after a clean JADE port you have **not** started the BDI work. Jason→JADE only reuses JADE as *transport/infrastructure*; the agent *logic* must still be rewritten from imperative Java behaviours into AgentSpeak: beliefs (belief base), triggering events, plans with context guards (`+!goal : context <- actions.`), and intentions. Nothing about writing JADE `Behaviour` classes advances that rewrite — arguably it entrenches imperative control flow you must later dismantle. The BDI model is a fundamentally different way of thinking (declarative "what to achieve" plus commitment/intention-revision, rather than imperative "how to run threads").

### The three paths compared
| Path | Relative effort | What you get at the end | Main risk |
|---|---|---|---|
| **OpenCybele → Jason (direct, on `infrastructure: Jade`)** | Medium (one paradigm shift) | BDI agents + FIPA/distribution via bundled JADE | AgentSpeak learning curve; must re-express logic declaratively |
| **OpenCybele → JADE → Jason** | High (two rewrites) | Same BDI endpoint, later | Pays double; interim JADE codebase to maintain; BDI still pending |
| **OpenCybele → JADE (stop)** | Medium (one mechanical port) | Maintained-via-fork imperative FIPA platform, **no BDI** | Core JADE dormant; you never get BDI benefits |

### Alternatives worth noting
- **Stay imperative on a modern actor framework (e.g., Akka).** If what you actually value is concurrency, messaging and distribution — not symbolic reasoning — an actor model is a livelier, better-maintained endpoint than either JADE or Jason. (A public Akka-user discussion explicitly compares CybelePro to Akka/Scala actors.) This is a legitimate "don't adopt BDI at all" branch.
- **JaCaMo.** If you want the full multi-agent-oriented programming stack, JaCaMo bundles Jason (agents) + CArtAgO (environment artifacts) + Moise (organisations). Sensible if organisation/environment modelling matters; heavier to learn.
- **Hybrid / gradual coexistence (the pragmatic middle path).** Run one JADE platform hosting *both* native JADE agents and Jason agents. The official "Interoperation between Jason and JADE" tutorial demonstrates this: "We will develop a Jason book-seller agent that joins the system of the traditional example of book trading that comes with JADE. The JADE code will remain as in the example, it will not be changed to interoperate with Jason." The Jason agent registers with the DF via `.df_register("JADE-book-trading","book-selling")` and answers incoming FIPA CFPs through `+!kqml_received(Sender, cfp, Content, MsgId)` plans. This lets you migrate **agent-by-agent**: keep or gateway some agents in imperative style, port the rest to AgentSpeak, all on the same FIPA platform.

## Recommendations

**Default recommendation: Go OpenCybele → Jason directly, using `infrastructure: Jade` for distribution/FIPA. Do NOT do a standalone full JADE rewrite first.**

Staged plan:
1. **Decide the endpoint first (the branch point).** If you genuinely need BDI/declarative reasoning → target Jason. If you only need a maintained imperative Java MAS with messaging/distribution → you do **not** need Jason at all; evaluate JADE-stop (using the EnFlexIT fork, GAV `de.enflexit.jade:de.enflexit.jade`, 4.6.5) vs. Akka, and skip the rest of this plan. **Note this repo went the other way**: [#26](https://github.com/bedaHovorka/OpenCybele1/issues/26) deliberately picked `net.sf.ingenias:jade:4.3` for Phase-2 alignment, because the endpoint here *is* Jason. *Benchmark that flips this:* if a survey of your agent logic shows it is mostly reactive event/timer handling with no goal-deliberation, BDI is overkill.
2. **Spike Jason-on-JADE early.** Create a minimal `.mas2j` with `infrastructure: Jade`, run `jason app add-gradle` then `./gradlew runJade`, and port **one** representative OpenCybele agent to AgentSpeak. This validates the paradigm shift and the FIPA/distribution story simultaneously, at low cost.
3. **Migrate gradually via coexistence.** Stand up a single JADE platform; wrap unported OpenCybele/Java logic as native JADE gateway agents where needed (`./gradlew runJadeAgs`), and bring Jason agents online one at a time (`jason.infra.jade.JadeAgArch(...)`, per the jason-jade tutorial). *Benchmark:* once >70–80% of agents are AgentSpeak and gateways are thin, retire the imperative shims.
4. **Only invest in hand-written JADE agents if** you must interoperate *now* with external, third-party FIPA agents, or you rely on JADE-specific features (object-payload ACL content, mobility) that you cannot yet express in AgentSpeak. Even then, write only those specific agents in JADE and keep everything else on the direct Jason path.
5. **Re-evaluate target-framework health before committing.** Core JADE is dormant (last official release Dec 2022); if long-term maintenance is a hard requirement for a *standalone JADE product*, prefer the actively maintained EnFlexIT fork, or weigh Akka/JaCaMo. For **this** repo the maintenance argument was consciously overridden by the Jason-alignment argument ([#26](https://github.com/bedaHovorka/OpenCybele1/issues/26)). *Benchmark that changes the plan:* if the JADE bridge breaks on your JDK, prefer Jason's **`Local`** infrastructure for non-distributed deployments and reconsider actors for distributed ones. ⚠️ **`Centralised` is not an option** — it was renamed to `Local` in Jason 3.0 (`RunCentralisedMAS` → `RunLocalMAS`); source: <https://raw.githubusercontent.com/jason-lang/jason/main/doc/release-notes.adoc>, `== version 3.0 (2021-09-07)`. Confirmed by inspection of `jason-interpreter-3.3.0.jar`: **zero** `jason/infra/centralised/**` entries; the class is `jason.infra.local.RunLocalMAS`.

**When JADE-first *is* defensible:** only if you are near-certain your endpoint is JADE-the-platform (not BDI), or you have a hard, immediate FIPA-interop deadline and a team that knows Java but not AgentSpeak, and you accept that the BDI rewrite is deferred, not avoided.

## Caveats
- **~~Repo not inspected.~~ Superseded.** The repository has been read in full and inventoried in **[`docs/INVENTORY.md`](INVENTORY.md)** ([#9](https://github.com/bedaHovorka/OpenCybele1/issues/9)). Use it — not this document's generalisations — for message payload structure (`CH-01`…`CH-15`), timers (`TMR-01`…`TMR-04`), mobility/load-balancing reliance (**none**), and directory usage (**none**). Where this document still generalises about "Cybele applications", `INVENTORY.md` wins.
- **JADE version/date nuance.** 4.6.0 (07/12/2022) is the official last TILAB release; a third-party mirror references a "4.6.1 rev6874 (2023-07-11)" rebuild that is **not** an official TILAB release; the EnFlexIT community fork's latest Maven Central version is **4.6.5** (2025-03-31). Treat 4.6.0 as the official baseline, EnFlexIT as the maintained fork, and `net.sf.ingenias:jade:4.3` (= JADE 4.3.3 rev 6726, 2014/12/09) as **what this repo pins** ([#26](https://github.com/bedaHovorka/OpenCybele1/issues/26)).
- **⚠️ Correction to a previously recorded finding.** [#26](https://github.com/bedaHovorka/OpenCybele1/issues/26)'s comparison table states that `de.enflexit.jade:4.6.4` is *"Also not on Maven Central — `de/enflexit/jade/` 404s"*. Re-checked 2026-08-18: <https://repo1.maven.org/maven2/de/enflexit/jade/> returns **200** and contains a single subdirectory `de.enflexit.jade/`, whose `maven-metadata.xml` lists `4.6.2, 4.6.3, 4.6.4, 4.6.5`. The artifactId repeats the groupId, so the resolvable GAV is `de.enflexit.jade:de.enflexit.jade:4.6.5` and the earlier probe simply stopped one directory too high. **#26's decision still stands** — its *deciding* factor was Jason 3.3.0's POM pinning `net.sf.ingenias:jade:4.3`, which is independently verified and unaffected — but its stated reason for rejecting the fork was factually wrong and should not be repeated.
- **Jason 3.3.0 release date — two upstream sources disagree; record both.** (a) GitHub release `v3.3.0` has `published_at` **2024-02-21** (and `created_at` 2024-10-16) — <https://api.github.com/repos/jason-lang/jason/releases/latest>. (b) [`release-notes.adoc`](https://raw.githubusercontent.com/jason-lang/jason/main/doc/release-notes.adoc) heads the line `== version 3.3 (2024-10-21)`. (c) The Maven Central artifact only appeared **2025-10-09** (`<lastUpdated>20251009132530</lastUpdated>`). These describe three different events (GitHub release object, release-notes line date, artifact publication) and none is an error. **`release-notes.adoc` is the authoritative statement of the release line's date**; the GitHub `published_at` is authoritative for the release *object*. Do not "fix" one into the other.
- **The latest released Jason version is `3.3.0`, not `3.3.3`.** Maven Central lists exactly `3.2.0, 3.2.1, 3.3.0` with `<release>3.3.0</release>`; GitHub's latest release is `v3.3.0`. `== version 3.3.3` is the **undated top heading** of `release-notes.adoc` on `main` — the in-development line. Pinning `3.3.3` will not resolve.
- **Effort estimates are relative, not absolute.** They depend on codebase size, how much Cybele-specific infrastructure you use, and your team's AgentSpeak familiarity. Do the step-2 spike before committing to any timeline.
- **Forward-looking items** (community forks continuing JADE, future Jason releases) are described as current status, not guarantees.
