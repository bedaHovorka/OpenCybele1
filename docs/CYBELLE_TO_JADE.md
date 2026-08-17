# Should You Migrate OpenCybele to JADE First (Before Jason)?

## TL;DR
- **No — do not do a full OpenCybele→JADE rewrite as a mandatory stepping stone if your end goal is Jason/BDI.** The BDI paradigm shift you are trying to reach is done exactly once whether you go direct or via JADE, so an intermediate JADE rewrite mostly adds a second full migration you will later have to partially throw away.
- **The single most useful insight:** Jason already runs *on top of* JADE. Setting `infrastructure: Jade` in your `.mas2j` (and running `./gradlew runJade`) gives you JADE's FIPA-ACL messaging, DF/AMS, containers and network distribution *without hand-writing a single JADE agent*. You can also mix native JADE agents and Jason agents on one platform, enabling a gradual agent-by-agent migration.
- **JADE-first is only the right call in narrow cases:** (a) your true endpoint is a maintained imperative Java platform and you do *not* actually need BDI (then stop at JADE and skip Jason); or (b) you must interoperate immediately with existing external FIPA agents / heavy object-payload messaging / mobility while you defer the BDI rewrite. Otherwise go **OpenCybele→Jason directly, on the JADE infrastructure**.

## Key Findings

**1. Conceptual distance is highly asymmetric.** OpenCybele/Cybele and JADE are *both* imperative, event/activity-driven Java frameworks, so that port is largely mechanical. Cybele's "Activity-Centric Programming" models an agent as a set of activities driven by message, timer, and internal events — which maps almost one-to-one onto JADE's `Behaviour` classes. The OpenCybele→Jason jump, by contrast, is a genuine paradigm change to declarative BDI (beliefs, goals/desires, plans, intentions) in an AgentSpeak logic language. That paradigm shift is the expensive part, and **it is identical in size whether or not you stop at JADE on the way**.

**2. Jason-on-JADE means you don't need to hand-write JADE to get JADE's benefits.** Jason's JADE infrastructure (`jason.infra.jade.JadeAgArch`) wraps each AgentSpeak agent as a JADE agent, translating between Jason's KQML-style performatives and FIPA-ACL. So the classic reasons to adopt JADE — network distribution, FIPA interoperability, DF yellow-pages, AMS — are all reachable *from Jason directly*. This substantially undercuts the rationale for a separate JADE rewrite.

**3. Both target frameworks are mature but slow-moving; core JADE is effectively dormant.** JADE's last official release is **4.6.0, dated 07/12/2022**, whose own announcement notes "Quite a lot of time has passed since the last official release" (it added message-queue customization and a Kafka-based `MomMessagingService` add-on). The original Telecom Italia/TILAB team stepped back around 2021. Ongoing maintenance now lives in community forks — most actively the **EnFlexIT/JADE** fork, published to Maven Central as `de.enflexit.jade` version **4.6.4** ("contains the original and current JADE code from the SVN of Telecom Italia"), packaged as an OSGi bundle. Jason is more alive: the current release is the **3.3 series**, which "uses Java 21" with virtual-thread agents ("It allow us to run thousands of agents") and actively maintains the JADE bridge. This matters: **spending months porting to JADE means investing heavily in the *less* actively maintained of the two targets.**

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
- **Maintenance status**: last official release **4.6.0 (07/12/2022)**; the original team stepped back around 2021; active maintenance now flows through community forks, notably **EnFlexIT/JADE** (`de.enflexit.jade` 4.6.4 on Maven Central, an OSGi bundle re-packaging the current TILAB SVN code). Treat core JADE as **stable-but-dormant, with a live community fork**.
- **Java 17/21 notes**: JADE predates the Java module system; like most pre-Java-9 code it can hit `--illegal-access`/reflection issues on newer JDKs, but community builds under OpenJDK 17 exist (e.g., third-party `dpsframework` builds), and the EnFlexIT OSGi packaging targets modern toolchains. Because Jason 3.3 requires Java 21, when you use Jason-on-JADE the classpath comes bundled and this is handled for you via Gradle.

### Cybele/OpenCybele side (label: partly general knowledge, not repo-verified)
Cybele/CybelePro is a proprietary Java agent infrastructure from **Intelligent Automation, Inc.** — the mark CYBELEPRO (USPTO serial 77019994, reg. 3257603, filed Oct 12, 2006, status *Registered and Renewed*) is described as "Agent infrastructure software for agent programming for use in distributed computing environments." It has been used for military logistics, simulation, and transportation control. Its programming model is **Activity-Centric Programming (ACP)**, and per the DCF/ACM paper "CybelePro™ is built on top of the Java 2 platform" with services including "concurrency management, event handling, thread-management, internal event services, communication, timer, data sharing, GUI services, sender side fil[tering]," plus migration and load balancing. NASA's Technical Reports Server ("Activity-Centric Approach to Distributed Programming," NTRS 20110020334) states that Cybele "provides support for event handling from multiple sources, multithreading, concurrency control, migration, and load balancing," and that "activity centric programming relieves application programmers of the complex tasks of thread management, concurrency control, and event management." **OpenCybele** is an open reimplementation in that lineage. Because the specified repo (`bedaHovorka/OpenCybele1`, `develop` branch) could not be accessed, the following mapping is based on the general Cybele/ACP model and should be validated against your actual code.

### Mapping table: OpenCybele/Cybele activities → JADE behaviours (for the JADE path)
| Cybele/OpenCybele concept | JADE equivalent | Notes on mechanical-ness |
|---|---|---|
| Periodic / timer activity | `TickerBehaviour` (`onTick()`) | Near 1:1 |
| One-shot activity | `OneShotBehaviour` (`action()`) | Near 1:1 |
| Delayed / wake-after activity | `WakerBehaviour` | Near 1:1 |
| Message-handler activity | `CyclicBehaviour` + `receive()`/`blockingReceive()` with `MessageTemplate` | Direct; you rewrite the dispatch loop |
| Activity-transition / staged logic | `FSMBehaviour` or `SequentialBehaviour` | Moderate; re-express transitions |
| Concurrent activities | `ParallelBehaviour` (or threaded behaviours) | Direct |
| Cybele message | `ACLMessage` + performative | Object payloads → ACL content/ontology; some redesign |
| Directory / community | JADE **DF** (yellow pages) + **AMS** (white pages) | Direct conceptually |
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
1. **Decide the endpoint first (the branch point).** If you genuinely need BDI/declarative reasoning → target Jason. If you only need a maintained imperative Java MAS with messaging/distribution → you do **not** need Jason at all; evaluate JADE-stop (using the EnFlexIT fork) vs. Akka, and skip the rest of this plan. *Benchmark that flips this:* if a survey of your agent logic shows it is mostly reactive event/timer handling with no goal-deliberation, BDI is overkill.
2. **Spike Jason-on-JADE early.** Create a minimal `.mas2j` with `infrastructure: Jade`, run `jason app add-gradle` then `./gradlew runJade`, and port **one** representative OpenCybele agent to AgentSpeak. This validates the paradigm shift and the FIPA/distribution story simultaneously, at low cost.
3. **Migrate gradually via coexistence.** Stand up a single JADE platform; wrap unported OpenCybele/Java logic as native JADE gateway agents where needed (`./gradlew runJadeAgs`), and bring Jason agents online one at a time (`jason.infra.jade.JadeAgArch(...)`, per the jason-jade tutorial). *Benchmark:* once >70–80% of agents are AgentSpeak and gateways are thin, retire the imperative shims.
4. **Only invest in hand-written JADE agents if** you must interoperate *now* with external, third-party FIPA agents, or you rely on JADE-specific features (object-payload ACL content, mobility) that you cannot yet express in AgentSpeak. Even then, write only those specific agents in JADE and keep everything else on the direct Jason path.
5. **Re-evaluate target-framework health before committing.** Core JADE is dormant (last official release Dec 2022); if long-term maintenance is a hard requirement, prefer the actively maintained EnFlexIT fork, or weigh Akka/JaCaMo. *Benchmark that changes the plan:* if the JADE bridge breaks on your JDK, prefer Jason's `Local`/`Centralised` infrastructure for non-distributed deployments and reconsider actors for distributed ones.

**When JADE-first *is* defensible:** only if you are near-certain your endpoint is JADE-the-platform (not BDI), or you have a hard, immediate FIPA-interop deadline and a team that knows Java but not AgentSpeak, and you accept that the BDI rewrite is deferred, not avoided.

## Caveats
- **Repo not inspected.** The OpenCybele `develop` branch could not be accessed; all OpenCybele/Cybele specifics here are inferred from the general Cybele/CybelePro/ACP model and must be validated against your actual code — especially the message payload structure, any load-balancing/mobility reliance, and how "communities/directories" are used.
- **JADE version/date nuance.** 4.6.0 (07/12/2022) is the official last TILAB release; a third-party mirror references a "4.6.1 rev6874 (2023-07-11)" rebuild that is **not** an official TILAB release, and the EnFlexIT community fork is at 4.6.4. Treat 4.6.0 as the official baseline and EnFlexIT as the maintained fork.
- **Jason 3.3.0 release date is ambiguous.** The GitHub tag shows "21 Feb" (year not shown in the scrape) while Maven Central lists the artifact publish date as Oct 9, 2025; the 3.3 series (Java 21, virtual threads) is nonetheless the current line succeeding 3.2 (which introduced JasonCLI and moved from Ant to Gradle).
- **Effort estimates are relative, not absolute.** They depend on codebase size, how much Cybele-specific infrastructure you use, and your team's AgentSpeak familiarity. Do the step-2 spike before committing to any timeline.
- **Forward-looking items** (community forks continuing JADE, future Jason releases) are described as current status, not guarantees.