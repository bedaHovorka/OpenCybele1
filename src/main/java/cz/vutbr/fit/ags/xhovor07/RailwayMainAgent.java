/*
 * Projekt AGS 2007/08
 * FIT VUT Brno
 *
 * Open Cybele 1
 *
 * Bedrich Hovorka
 * xhovor07@stud.fit.vutbr.cz
 */
package cz.vutbr.fit.ags.xhovor07;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Map.Entry;

import javax.swing.table.AbstractTableModel;

import cz.vutbr.fit.ags.railway.domain.clock.PacedClock;
import cz.vutbr.fit.ags.railway.domain.clock.SimClock;
import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.PathFindReply;
import cz.vutbr.fit.ags.railway.domain.msg.PathFindRequest;
import cz.vutbr.fit.ags.railway.domain.msg.Party;
import cz.vutbr.fit.ags.railway.domain.msg.RailwayMessage;
import cz.vutbr.fit.ags.railway.domain.msg.RoadDirection;
import cz.vutbr.fit.ags.railway.domain.msg.RoadStateReport;
import cz.vutbr.fit.ags.railway.domain.msg.StationInfo;
import cz.vutbr.fit.ags.railway.domain.msg.TrainState;
import cz.vutbr.fit.ags.railway.domain.util.UnorientedGraph;
import cz.vutbr.fit.ags.railway.domain.util.Util;
import cz.vutbr.fit.ags.railway.jade.Messages;
import cz.vutbr.fit.ags.railway.jade.Templates;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;
import jade.wrapper.StaleProxyException;

/**
 * Managing social knowledge — <b>ported to JADE by #33</b>, and split into the three parts the
 * issue asked for: <b>boot/config</b> ({@link #setup()}), an <b>agent factory</b>
 * ({@link #spawnStations()}/{@link #spawnRoads()}/{@link #spawn}) and <b>social-knowledge
 * state</b> (the three maps and the handlers that fill them).
 *
 * <p>
 * The 2008 constructor did all of that plus the GUI, the clock, five channel subscriptions and
 * two activity registrations, in one 58-line block. What follows is the same statements in the
 * same order, redistributed; the only deliberate reordering is called out below.
 *
 * <h2>The hard part: JADE gives no agent-startup ordering guarantee</h2>
 * {@code createNewAgent(...).start()} returns immediately and when each child's {@code setup()}
 * runs is up to the platform. {@code Station} and {@code RoadAgent} push their first
 * {@code STATION_INFO}/{@code ROAD_STATE} as the last statement of {@code setup()}, so those
 * fifteen lines are the opening of every trace and the goldens carry them in a fixed order.
 *
 * <p>
 * <b>The order itself is already handled, and not here.</b> {@code docs/trace-normalizer.md}
 * §2.1's {@code startup-block} rule sorts "the maximal opening run of {@code STATION_INFO} /
 * {@code ROAD_STATE} lines by agent name", and it exists for precisely this reason on the
 * <em>Cybele</em> side: {@code Cybele.createAgent} is asynchronous too, and #15 measured <b>six
 * distinct constructor orders in six runs</b>. The goldens are the sorted result —
 * {@code opencybele-strict.txt:10-24} is {@code stA…stH} then {@code tr1…tr7}, which is not any
 * order a platform produced. So a readiness handshake would buy a total order that the normalizer
 * then throws away by sorting it, and #33 does <b>not</b> add one.
 *
 * <p>
 * <b>But the rule holds only under one assumption, and that assumption is what this class
 * implements.</b> The rule takes the <em>maximal opening run</em>: it stops at the first line that
 * is not a constructor state push. If one straggler's opener lands <em>after</em> the run's first
 * other traced line, that opener is not in the startup block at all — it falls into the first
 * burst, gets sorted there, and the golden diffs <b>positionally</b>. The assumption is therefore:
 *
 * <blockquote><b>all fifteen openers are emitted before the first non-constructor trace line of
 * the run.</b></blockquote>
 *
 * The first non-constructor line is {@code Generator}'s first {@code PLAN_TRAIN}, at
 * {@code sim.arrival.firstFireMs} after the generator arms. That budget is <b>not</b> generous:
 * {@code opencybele-strict.yaml} sets {@code firstFireMs=200} at {@code pace=8}, i.e. <b>25 real
 * milliseconds</b> for fifteen JADE agents to be created, given a thread each, registered with the
 * AMS and run to the end of {@code setup()}. Cybele fits in it — the goldens prove that much — but
 * JADE's per-agent boot is heavier than a kernel thread-pool dispatch, and "it will probably fit"
 * is exactly the platform-dependence the issue forbids.
 *
 * <p>
 * <b>So the precondition is made true rather than hoped for: the generator is armed by the
 * fifteenth opener, not by {@code setup()}.</b> {@link #noteStarted} strikes each name off
 * {@link #pendingStartup} as its {@code STATION_INFO}/{@code ROAD_STATE} arrives, and the one that
 * empties the set calls {@link Generator#start()}. The barrier is free of everything a handshake
 * would have cost:
 * <ul>
 *   <li><b>no new message and no new channel.</b> Those openers are already addressed to this
 *       agent — CH-10 and CH-11 — and this agent already handles them. #27's fifteen-channel table
 *       is untouched, so nothing new appears in a trace and nothing new has to be given a
 *       performative, a template or a topic;</li>
 *   <li><b>no serialization of the boot.</b> All fifteen are still created in one loop and boot
 *       concurrently; only the <em>generator</em> waits;</li>
 *   <li><b>precedent.</b> {@code Main} already blocks on {@code TraceProbe.awaitReady()} for the
 *       same class of race — "{@code Cybele.createAgent} is asynchronous, so creating the two in
 *       one breath is a race the probe loses … so no message of the run can predate the trace" —
 *       and {@code RunControl.awaitTimerService()} is a second instance. This is the third.</li>
 * </ul>
 *
 * <p>
 * <b>What it costs, stated rather than buried.</b> The first train now departs at
 * {@code (arrival of the last opener) + firstFireMs} instead of
 * {@code (end of the spawn loop) + firstFireMs}, so the whole run's simulated origin shifts later
 * by the boot-completion latency. Two consequences:
 * <ul>
 *   <li>on the trace, <b>none</b>: every clock-derived family is projected away
 *       ({@code trace-normalizer.md} §2.1 erases {@code tick}, {@code expected}, {@code planned}
 *       and {@code departure}; {@code diff} is a difference and survives the shift unchanged), and
 *       the shift moves no line relative to any other;</li>
 *   <li>on the <b>run length</b>, possibly one train. Every {@code opencybele-*} scenario is
 *       bounded by {@code sim.stop.maxClockMs} in simulated ms, so a later origin can push the
 *       last arrival past the bound. That is the same jitter the baseline had — the strict
 *       scenario's own comment records moving the bound from 25000 to 24000 for it — but it is a
 *       quantity, it is not measured here, and it is <b>#36's first gate run</b> that measures it.
 *       If a JADE run comes up exactly one train short of its golden, this is the first thing to
 *       check and it is not a port bug in the agents.</li>
 * </ul>
 *
 * <p>
 * The liveness risk the barrier introduces — an agent that never boots means a generator that
 * never arms, i.e. an empty run — is answered the way #30, #31 and #34 answer theirs
 * ({@code docs/defect-triage.md} §6.1): {@link StartupWatchdog} names the missing agents on stderr,
 * where #12's {@code ErrorScanner} reads, and <b>does not arm the generator anyway</b>. Reporting
 * is not arming.
 *
 * <h2>Creation order is #19's, reproduced and not re-derived</h2>
 * {@link #spawnStations()} iterates {@code net.nodeSet()} and {@link #spawnRoads()} iterates
 * {@code net.values()}, which is what the 2008 constructor did. Since #19 both go through
 * {@code HashMapGraph.orderedKeys()}, so they are {@code [stB, stA, stD, stC, stF, stE, stH, stG]}
 * and {@code [tr1, tr4, tr7, tr5, tr3, tr6, tr2]} — deterministic, and byte-identical to what the
 * pre-#19 tree produced. Left/right endpoint assignment is likewise already deterministic and is
 * <b>reproduced verbatim</b>: {@code net.allNodesWithEdge(road)} finds the one matching entry and
 * {@code DoubletonIterator} walks it {@code first} then {@code second}, so {@code array[0]} and
 * {@code array[1]} are the {@code sim.topology} declaration order on every JVM
 * ({@code docs/iteration-order.md}, claim 3). Imposing a lexicographic rule would flip five of the
 * seven roads and invert the {@code TRAVEL_LEFT}/{@code TRAVEL_RIGHT} symbol in every trace; the
 * Czech warning the 2008 author left on that line is kept for the same reason.
 *
 * <h2>Which agents get a clock, and why this is the place that knows</h2>
 * A {@code Station} takes {@code {capacity, roads}} and reads no clock at all — #30 says so, and
 * its two time-taking methods take the instant from the message. A {@code RoadAgent} takes
 * {@code {delaySeconds, left, right, SimClock}}, because it arms the traversal timer. Somewhere has
 * to know that asymmetry, and it is here: this agent creates the one shared {@link SimClock} that
 * replaced {@code myClock} and hands it only to the agents that schedule on it. The alternative — a
 * static holder every agent could reach — is exactly the global {@code Cybele.getTime(CLOCK_ID)}
 * that #29 removed, and {@code RoadAgent} already refuses it by taking the clock as an argument.
 *
 * <h2>Where {@code Planning} and {@code Generator} live</h2>
 * <b>Inside this agent's identity</b>, as behaviours and a plain object, not as agents of their
 * own. {@code docs/message-ontology.md} §11 leaves the split open, but taking the separate-AID
 * option diffs <b>five</b> channel families, not one: {@code PLAN_TRAIN} in trace fields 4
 * <em>and</em> 5, {@code VOTE_REQUEST}/{@code VOTE_RESULT}/{@code START} in field 4, and
 * {@code VOTE} in field 5, all of which the frozen goldens carry as the literal {@code Main}. #34
 * made that call for the planner; #33 makes the same one for the generator, and the two share this
 * agent's name, thread and mailbox.
 *
 * <p>
 * <b>One clock, one ticker.</b> {@code Planning}'s handover note asks that if {@code Generator}
 * needs an {@link cz.vutbr.fit.ags.railway.domain.clock.AgentClock} too, the two <em>share</em>
 * one rather than register a second {@code ClockTickerBehaviour} on the same agent — "the period
 * is a budget and it is easier to keep one honest than two". So {@link Generator} is handed
 * {@code planning.agentClock()}. The application therefore has exactly <b>two</b> tick sites, one
 * per agent that schedules ({@code RoadAgent} and this one), both budgeted by
 * {@link RoadAgent#granularityMsFor(double)}, which since #33 reads
 * {@code sim.clock.granularityMs} — a <em>simulated</em>-ms budget divided by the pace, never a raw
 * real-ms period. That is #31's note discharged.
 *
 * <h2>The channels, and the one template this agent must not widen</h2>
 * {@code Party.MAIN} has six inbound channels. Four are this agent's — {@code STATION_INFO},
 * {@code ROAD_STATE}, {@code TRAIN_STATE} and {@code PATH_FIND} — and two,
 * {@code PLAN_TRAIN} and {@code VOTE}, belong to {@link Planning}, whose {@code Inbox} narrows to
 * {@code VOTE} alone while an election is open so that a {@code PLAN_TRAIN} waits in this agent's
 * mailbox. That is not tidiness: it reproduces Cybele's serial dispatch, under which the
 * application ran <b>exactly one election at a time</b>, which is contractual twice over (every
 * voter's {@code computeDifference} sees the previous train's booking, and DEF-22's recorded
 * observable <em>is</em> the seriality). {@link Inbox} below therefore lists its four channels
 * explicitly and <b>must never become {@code Templates.inbound(Party.MAIN)}</b>: that would take
 * {@code PLAN_TRAIN} out from under the planner's gate and change every departure time in the
 * trace. {@link Drain} is the agent-level complement, {@code Templates.unexpected(Party.MAIN)},
 * which is disjoint from both inboxes and belongs to the agent rather than to one of its
 * activities — {@code message-ontology.md} §7, and {@code Planning}'s second handover obligation.
 *
 * <h2>{@code java.util.Observable} is replaced</h2>
 * Deprecated since Java 9 and used in three places — this class, {@code RailwayCanvas} and the
 * inner {@code TableModel}. All three now use {@link RailwayView.Listener}; see
 * {@link RailwayView} for the interface, for #35's reason it exists at all, and for the one
 * behavioural detail of {@code Observable} that is not reproduced (it notified <em>backwards</em>).
 *
 * <h2>The defects at this site, decided rather than inherited</h2>
 * <ul>
 *   <li><b>DEF-24</b> ({@code TableModel.update} iterating a {@code synchronizedMap} off-lock and
 *       caching live {@code Map.Entry} views) — class (c), "do not file". Its <em>first</em> half is
 *       structurally gone: under Cybele the map was mutated by several agent threads at once
 *       (EVT-04), and here every mutation and every {@code railwayChanged()} happens on this one
 *       agent's thread. Its <em>second</em> half is not: #35 ported the view, so a Swing EDT still
 *       reads the model. {@code defect-triage.md}'s note that "JADE ports have no Swing
 *       {@code TableModel} at all, so this can never legitimately appear as a port-side diff" is
 *       therefore <b>true for a different reason than it gives</b> — there is a table model, the
 *       race that fed it is what is gone — and #39 should not quote the stated reason. The
 *       {@code synchronized} on the model's three methods is kept for that surviving reader; see
 *       "why there is still a monitor here" below.</li>
 *   <li><b>DEF-20</b> (the misspelled {@code recieve*} handlers, bound reflectively by string
 *       literal at {@code :119}, {@code :128} and {@code Generator.java:59}, so a rename fails
 *       <em>silently at runtime</em>) — <b>structurally closed</b>. Dispatch is now a {@code switch}
 *       over {@link Channel} and the compiler binds it. The misspellings are kept: they are the
 *       2008 names, the file is rewritten in place, and renaming them buys nothing now that
 *       nothing binds them by string.</li>
 *   <li><b>DEF-10</b> (one {@code TRAIN.STATE.<train>} channel ticket leaked per train forever;
 *       {@code Activity.closeChannel} is called nowhere) — <b>cannot arise</b>. There is no
 *       per-train subscription to leak: one {@code TRAIN_STATE} template matches every train, for
 *       the whole run. §6.3's disposition was "bound the scenario, do not fix", so nothing was
 *       fixed here either; the leak simply has no analogue. #41's register, not #39's.</li>
 *   <li><b>DEF-15</b> (the {@code Gui} construction was the only thing keeping {@code createClock}
 *       clear of the kernel's ~3.4 ms clock-registration race) — <b>cannot arise</b>. There is no
 *       kernel registration: {@link PacedClock} is a plain object and is usable the instant it is
 *       constructed. {@code RunControl.verifyClockControl}/{@code awaitTimerService} are Cybele-only
 *       and are no longer called from here; {@code Main} still installs {@code RunControl} for its
 *       bounds and its exit codes.</li>
 *   <li><b>SEM-06 at the boot edge, running the other way</b> — the same favourable difference #30
 *       records. Cybele <em>silently drops</em> a {@code sendAll} to a channel nobody has opened
 *       yet, which is why the 2008 constructor had to {@code openChannel(STATION.INFO.<st>)}
 *       <em>before</em> {@code createAgent(<st>)} and why {@code Generator} had to open
 *       {@code TRAIN.STATE.<train>} before creating the train. JADE queues a message for an agent
 *       whose behaviour is not registered yet, so neither ordering is load-bearing any more and
 *       both {@code openChannel} loops are gone. Inside DEF-02's class-(c) carve-out; recorded
 *       because every other 2008→JADE delta here is recorded.</li>
 * </ul>
 *
 * <h2>Why there is still a monitor here, when the other four ports deleted theirs</h2>
 * #30, #31 and #34 each argued their {@code synchronized} away by naming the second party and
 * showing it gone. This agent cannot make that argument, and the difference is worth stating
 * rather than papering over: <b>the Swing EDT is a real second thread and #35 kept it</b>. It reads
 * {@link #getStationInfos()} and {@link #getRoadAgentStates()} from {@code RailwayCanvas.paint},
 * and the table model from {@code JTable}, while this agent's thread writes them. So the three
 * published maps stay wrapped in {@code Collections.synchronizedMap} and the model's three methods
 * stay {@code synchronized}, exactly as in 2008. What <em>did</em> go is the multi-writer half:
 * one thread writes now, where EVT-04 had several.
 *
 * <h2>What still runs on Cybele</h2>
 * Nothing in this file does. The five constants below are vestigial and say so one by one; they
 * outlive #33 because {@code TraceProbe} — #36's to replace — and #27's {@code ChannelTableTest}
 * still name them.
 *
 * <p>
 * <b>One concrete handover to #36, found here and worth stating before it costs a debugging
 * session.</b> {@code RunControl}'s watchdog enforces {@code sim.stop.maxClockMs} by reading
 * {@code Cybele.getTime(clockId)}, guarded by a {@code clockReady} flag that only
 * {@code RunControl.verifyClockControl} sets — and this agent no longer calls it, because there is
 * no kernel clock registration to verify. So <b>{@code sim.stop.maxClockMs} is inert on the JADE
 * side</b>, and <em>every</em> {@code opencybele-*} scenario is bounded by exactly that key. The
 * failure is not silent — each of them also sets {@code sim.stop.wallClockMs}, so such a run ends
 * on the wall-clock bound with {@code EXIT_WALL_CLOCK_TIMEOUT} — but the <em>diagnosis</em> would
 * be wrong, and a run that stops for the wrong reason is not the run the golden records. #36 (or
 * #17's follow-up) has to point that bound at the {@link SimClock} this agent creates. Not fixed
 * here: {@code RunControl} is installed by {@code Main}, which is still the Cybele launcher and is
 * #36's to replace, so a hook added now would be a guess at that design.
 *
 * @author Bedrich Hovorka
 *
 */
public class RailwayMainAgent extends Agent implements RailwayView {
    // spravce socialnich znalosti
    /**
     * global clock identification
     * <p>
     * <b>Vestigial.</b> This agent's clock is a {@link SimClock} object (#29), which has no id
     * because it has no registry — the whole point of the abstraction is that there is no
     * {@code Cybele.getTime(CLOCK_ID)} global left to reach. The constant survives only because
     * {@code TraceProbe.tick()} still calls {@code Cybele.getTime(RailwayMainAgent.CLOCK_ID)} and
     * {@code RunControl} still names a clock id. Delete with #36, which replaces the Cybele probe.
     */
    public static final String CLOCK_ID = "myClock";
    /**
     * channel for station info
     * <p>
     * <b>Vestigial.</b> CH-10 is routed by this agent's AID plus the {@code railway.STATION_INFO}
     * ontology slot; nothing here opens a channel any more. Kept for {@code TraceProbe} (#36) and
     * for #27's {@code ChannelTableTest}, which compares {@link Channel#STATION_INFO} against it —
     * the same standing #34 gave {@code Planning.PLAN_TRAIN} and {@code Planning.VOTE}.
     */
    public static final String CHANNEL_STATION_INFO = "STATION.INFO.";
    /**
     * channel for road state
     * <p>
     * <b>Vestigial</b>, for exactly the reasons {@link #CHANNEL_STATION_INFO} gives.
     */
    public static final String CHANNEL_ROAD_STATE = "ROAD.STATE.";
    /**
     * channel for path direction find
     * <p>
     * <b>Vestigial</b>, same reasons. Note the trailing dot really is part of the name and really
     * is used bare — INVENTORY CH-15 — which is why {@code ChannelTableTest} pins it.
     */
    public static final String PATH_FIND = "PATH_FIND.";
    private transient TableModel trainTableModel = new TableModel();
    private static final long serialVersionUID = 1L;
    /**
     * How long the fifteen children have to finish booting before {@link StartupWatchdog} says
     * they have not, in wall-clock milliseconds, and also that watchdog's tick period.
     * <p>
     * Wall clock rather than simulated time, for the reason {@code Station.PATH_FIND_WATCHDOG_MS}
     * and {@code Planning.VOTE_WATCHDOG_MS} give: what is being timed is agent creation, which is a
     * real-time phenomenon. The bound is deliberately far larger than any real boot — this is a
     * "this will never happen" alarm, not a timeout, and it must never fire on a healthy run.
     */
    static final long STARTUP_WATCHDOG_MS = 60_000L;
    private final ScenarioConfig config = ScenarioConfig.get();
    private UnorientedGraph<String, String> net = config.buildNet();
    private Map<String, StationInfo> stationInfos = Collections.synchronizedMap(new HashMap<String, StationInfo>());
    private Map<String, RoadDirection> roadAgentStates = Collections.synchronizedMap(new HashMap<String, RoadDirection>());
    private Map<String, String> trainStates = Collections.synchronizedMap(new LinkedHashMap<String, String>());
    private Map<String, Long> roadDelays = new HashMap<String, Long>(config.getRoadDelaysSec());
    private Map<String, Integer> stationCapacities = new HashMap<String, Integer>(config.getStationCapacities());
    /**
     * The views registered on this hub. A plain list because {@code java.util.Observable} is
     * deprecated; see {@link RailwayView}.
     * <p>
     * {@code transient} for the same reason {@link #trainTableModel} is: a listener is a Swing
     * component, or the model in front of one, and serializing this agent must not drag a window
     * across the wire. Read defensively by {@link #fireChange()} so that a {@code null} after
     * deserialization is a no-op rather than an NPE inside a message handler.
     */
    private transient List<Listener> listeners = new ArrayList<Listener>();
    /**
     * The children whose opening {@code STATION_INFO}/{@code ROAD_STATE} has not arrived yet.
     * Insertion-ordered so the watchdog's report reads in creation order. Empty means the startup
     * block is complete; see the class comment.
     */
    private final Set<String> pendingStartup = new LinkedHashSet<String>();
    private long startupBeganNanos;
    private boolean startupReported;
    /**
     * The one shared simulated clock — {@code myClock} in 2008, and the only instance in the
     * process. Handed to every {@code RoadAgent} and to {@link Planning}; see the class comment.
     */
    private SimClock clock;
    private Planning planning;
    private Generator generator;
    static final String CHANNEL_TRAIN_STATE = "TRAIN.STATE.";
    /**
     * identification
     */
    public static final String MAIN_AGENT_NAME = "Main";

    /**
     * Boot and configuration — the 2008 constructor, minus the factory and the state.
     *
     * <p>Statement order is 2008's with <b>one</b> deliberate change: the clock is created
     * <em>before</em> the GUI rather than after it. Two reasons, and neither is style. The canvas
     * paints {@link #getSimTimeMs()} on its very first repaint, which {@code gui.setVisible(true)}
     * triggers, so a clock created afterwards would be read before it exists. And the reason the
     * 2008 order was the way round it was has expired: DEF-15 records that those two GUI lines were
     * the only thing keeping {@code Cybele.createClock} clear of the kernel's clock-registration
     * race, by accident and only in GUI mode — a race a plain {@link PacedClock} does not have, and
     * one that {@code RunControl}'s barrier had already closed on the baseline anyway.
     *
     * <p>The clock is <b>not</b> paused and resumed around anything here. {@code createClock} +
     * {@code verifyClockControl} + {@code resumeClock} were a Cybele round trip that proved the
     * kernel had registered the clock at all; there is no registration to prove.
     */
    @Override
    protected void setup() {
	// Topologie site, kapacity stanic a doby na tratich pochazeji ze ScenarioConfig
	// (sim.topology / sim.station.capacities / sim.road.delaysSec). Vychozi hodnoty
	// jsou totozne s puvodnimi literaly - viz docs/scenario-config.md.
	this.clock = new PacedClock(config.getClockStartMs(), config.getClockPace());
	addListener(trainTableModel);

	// vytvoreni gui
	openView();

	addBehaviour(new Inbox());
	addBehaviour(new Drain());
	addBehaviour(new StartupWatchdog());

	// inicializace agentu
	this.startupBeganNanos = System.nanoTime();
	spawnStations();
	spawnRoads();

	this.planning = new Planning(this, net, roadDelays, clock);
	// One AgentClock and one ClockTickerBehaviour for this agent, shared with the planner --
	// see the class comment. The generator is NOT armed here; the fifteenth opener arms it.
	this.generator = new Generator(this, planning.agentClock());
    }

    /**
     * Build the Swing window, unless {@code sim.headless} says not to — <b>the only statement in
     * this agent that touches AWT</b>, isolated into a method of its own for two reasons.
     * <p>
     * One: it keeps the headless decision at a single point, which is what
     * {@code docs/headless-and-stop.md} asks for and what {@code Main} pre-validates so that a
     * {@code HeadlessException} cannot be raised from inside an agent, where Cybele used to
     * swallow it. Two: it is the seam that lets a POJO test call {@link #setup()} for real — the
     * factory, the barrier and the behaviours are the interesting half, and standing up a
     * {@code JFrame} to reach them would make the suite depend on a display server.
     * <p>
     * The listeners do not move with it: {@code trainTableModel} is registered above,
     * unconditionally, exactly as 2008 registered it above its headless guard. In headless mode
     * simply nothing else subscribes.
     */
    protected void openView() {
	// sim.headless=true preskoci konstrukci okna. Viz docs/headless-and-stop.md.
	if (config.isHeadless()) {
	    return;
	}
	final Gui gui = new Gui(this);
	gui.setVisible(true);
    }

    /**
     * Create one {@code Station} agent per node, in {@code net.nodeSet()} order (#19, NDT-01).
     * <p>
     * A station takes two arguments and no clock. The 2008 {@code openChannel(STATION.INFO.<st>)}
     * that preceded each {@code createAgent} is gone: one template covers every station for the
     * whole run, and JADE does not drop a message that arrives before a behaviour is registered.
     */
    @SuppressWarnings("boxing")
    private void spawnStations() {
	for (String station : net.nodeSet()) {
	    assert stationCapacities.containsKey(station);
	    Collection<String> roads = net.get(station);
	    pendingStartup.add(station);
	    spawn(station, Station.class.getName(),
		    new Object[]{stationCapacities.get(station), (Serializable) roads});
	}
    }

    /**
     * Create one {@code RoadAgent} per edge, in {@code net.values()} order (#19, NDT-02).
     * <p>
     * A road takes four arguments, the fourth being the shared {@link SimClock}; see the class
     * comment for why this is the place that knows which agents get one. The endpoint order is
     * {@code sim.topology} declaration order and is reproduced, not re-derived — the Czech warning
     * below is the 2008 author's and is kept because it is still the right warning.
     */
    @SuppressWarnings("boxing")
    private void spawnRoads() {
	for (String road : net.values()) {
	    assert roadDelays.containsKey(road);
	    Collection<String> allStationsWithRoad = net.allNodesWithEdge(road);
	    assert allStationsWithRoad.size() == 2;
	    // pozor na prohozeni satnic - zalezi na implemenatci allNodesWithEdge(road)
	    final Object[] array = allStationsWithRoad.toArray(new Object[2]);
	    pendingStartup.add(road);
	    spawn(road, RoadAgent.class.getName(),
		    new Object[]{roadDelays.get(road), array[0], array[1], clock});
	}
    }

    /**
     * Create and start one child agent — {@code Cybele.createAgent(name, className, args)}.
     * <p>
     * The single spawning seam, and overridable for the same reason {@link #emit} is: a POJO test
     * has no container, and the interesting half of a factory is <em>what it asked for</em>, not
     * whether JADE granted it.
     * <p>
     * A refused creation is fatal and says so. Cybele's {@code createAgent} returned nothing and
     * failed silently; JADE throws {@code StaleProxyException}, and swallowing it would leave a
     * simulation permanently one agent short, with the startup barrier above wedged and no
     * explanation for it.
     *
     * @param name the agent's local name, which is also its name in every trace field
     * @param className the agent class, passed as a string exactly as Cybele took it
     * @param args the construction arguments, read back by the child's {@code setup()}
     */
    protected void spawn(String name, String className, Object[] args) {
	try {
	    getContainerController().createNewAgent(name, className, args).start();
	} catch (StaleProxyException e) {
	    throw new IllegalStateException("RailwayMainAgent: cannot create agent '" + name
		    + "' of class " + className, e);
	}
    }

    /**
     * The four channels this agent handles itself. <b>Not</b>
     * {@code Templates.inbound(Party.MAIN)} — see the class comment; the missing two belong to
     * {@link Planning}, whose gate on {@code PLAN_TRAIN} is what reproduces Cybele's
     * one-election-at-a-time dispatch.
     */
    final class Inbox extends CyclicBehaviour {
	private static final long serialVersionUID = 1L;
	private final MessageTemplate template = hubTemplate();

	@Override
	public void action() {
	    final ACLMessage acl = myAgent.receive(template);
	    if (acl == null) {
		block();
		return;
	    }
	    dispatch(acl);
	}
    }

    /**
     * The agent-level drain {@code docs/message-ontology.md} §7 asks for, and {@link Planning}'s
     * first handover obligation: it belongs to the agent, not to one of its activities, because
     * only the agent knows the whole inbound set. {@code Templates.unexpected(Party.MAIN)} is the
     * complement of all six {@code Main} channels, so it never competes with either inbox.
     * <p>
     * <b>Note for #36:</b> {@code not(inbound)} is the complement of <em>everything</em>, so in a
     * live container it also matches AMS and DF traffic. This agent registers with neither today;
     * if a later ticket gives it an AMS subscription, narrow this template first or the drain will
     * report platform housekeeping as a port bug. Same warning {@code Station} carries.
     */
    final class Drain extends CyclicBehaviour {
	private static final long serialVersionUID = 1L;
	private final MessageTemplate template = Templates.unexpected(Party.MAIN);

	@Override
	public void action() {
	    final ACLMessage acl = myAgent.receive(template);
	    if (acl == null) {
		block();
		return;
	    }
	    unexpected(acl);
	}
    }

    /**
     * The startup barrier's loud branch. See the class comment: it <b>reports</b> children whose
     * opening state push has not arrived, once, and it does <b>not</b> arm the generator anyway.
     * <p>
     * Arming on a timeout would be the "silent fall-through that proceeds as if it had arrived"
     * {@code docs/defect-triage.md} §6.1 forbids for this whole family of unbounded waits: the
     * generator would start emitting into a trace whose startup block is provably incomplete, and
     * the resulting golden diff would point at the agents rather than at the missing boot.
     */
    final class StartupWatchdog extends TickerBehaviour {
	private static final long serialVersionUID = 1L;

	StartupWatchdog() {
	    super(RailwayMainAgent.this, STARTUP_WATCHDOG_MS);
	}

	@Override
	protected void onTick() {
	    reportStartupOverdue(System.nanoTime());
	}
    }

    /**
     * Report children that have not announced themselves within {@link #STARTUP_WATCHDOG_MS},
     * once. Arms nothing.
     * <p>
     * Takes {@code now} as a parameter rather than reading {@link System#nanoTime()} itself, so a
     * test can reach the overdue branch without waiting a minute for it.
     *
     * @param nowNanos the current {@link System#nanoTime()} reading
     */
    void reportStartupOverdue(long nowNanos) {
	if (startupReported || pendingStartup.isEmpty()) {
	    return;
	}
	final long waitedMs = (nowNanos - startupBeganNanos) / 1000000L;
	if (waitedMs < STARTUP_WATCHDOG_MS) {
	    return;
	}
	startupReported = true;
	System.err.println("RailwayMainAgent " + name() + ": " + pendingStartup.size()
		+ " child agent(s) have not sent their opening state after " + waitedMs
		+ " ms: " + new ArrayList<String>(pendingStartup) + ". The generator is NOT armed"
		+ " -- a run whose startup block is incomplete would diff positionally against"
		+ " every golden. See RailwayMainAgent's class comment.");
    }

    /**
     * Dispatch one inbound message to the handler for its channel.
     * <p>
     * Named {@code dispatch} rather than {@code handle} for the reason {@code Station} gives:
     * {@code Behaviour.handle} exists and an inner {@code CyclicBehaviour} would resolve the
     * unqualified call to that one. Package-visible and container-free on purpose — the POJO seam
     * {@code docs/TESTING.md} §4.1 asks for.
     * <p>
     * This is also where <b>DEF-20 dies</b>: the 2008 handlers were bound by string literal
     * ({@code "recieveStationInfo"} at {@code :119}), so a rename failed silently at runtime. The
     * compiler binds this.
     *
     * @param acl a message matching {@link #hubTemplate()}
     */
    void dispatch(ACLMessage acl) {
	final Channel channel = Messages.channelOf(acl);
	final RailwayMessage message = Messages.contentOf(acl);
	final String sender = Messages.senderName(acl);
	switch (channel) {
	    case PATH_FIND -> pathFind((PathFindRequest) message);
	    case STATION_INFO -> recieveStationInfo(sender, (StationInfo) message);
	    case ROAD_STATE -> recieveRoadState(sender, (RoadStateReport) message);
	    case TRAIN_STATE -> recieveTrainState(sender, (TrainState) message);
	    default -> unexpected(acl);
	}
    }

    /**
     * The template {@link Inbox} draws with: this agent's four channels, listed rather than
     * derived.
     * <p>
     * Package-visible so a test can assert on it, and <b>listed</b> so that widening it is an edit
     * somebody has to make on purpose. {@code Templates.inbound(Party.MAIN)} is the tempting
     * one-liner and it is wrong: it also matches {@code PLAN_TRAIN} and {@code VOTE}, which would
     * pull a {@code PLAN_TRAIN} out of the mailbox while {@link Planning} has an election open and
     * silently start a second one. Overlapping elections change every departure time on a healthy
     * run, and DEF-22's recorded observable is the seriality itself.
     *
     * @return {@code STATION_INFO | ROAD_STATE | TRAIN_STATE | PATH_FIND}
     */
    static MessageTemplate hubTemplate() {
	return Templates.anyOf(List.of(Channel.STATION_INFO, Channel.ROAD_STATE,
		Channel.TRAIN_STATE, Channel.PATH_FIND));
    }

    /**
     * Report a message this agent has no handler for. Loud, and it does not throw: the queue is
     * drained either way, so one stray message cannot wedge the agent.
     *
     * @param acl the message
     */
    void unexpected(ACLMessage acl) {
	System.err.println("RailwayMainAgent " + name() + ": unexpected message, ontology="
		+ acl.getOntology() + " performative="
		+ ACLMessage.getPerformative(acl.getPerformative())
		+ " from=" + Messages.senderName(acl));
    }

    /**
     * path direction finding
     * <p>
     * The 2008 body, with {@code Util.pathDirection} now an extracted domain class (#28) and the
     * reply addressed to the asking station's AID instead of to {@code PATH_FIND_REPLY.<st>}.
     * <p>
     * The {@code assert direction != null} is <b>kept, unguarded</b>. It is a contract surface:
     * goldens were recorded with {@code -ea} and #36 replays with it, and
     * {@code defect-triage.md}'s per-site table is explicit about what the two settings do here —
     * with assertions off this ships a {@code null} direction, which is DEF-01 (a train that reads
     * "arrived" and dies mid-route); with them on nothing is sent, which is DEF-23 (that station
     * stalls). Both are class (c), "do not file", and the port must not quietly pick a third
     * behaviour. #30 closes the DEF-01 half from the receiving end anyway: a station now
     * <em>refuses</em> a {@code null} direction rather than caching it.
     * <p>
     * <b>The {@code -ea} branch's <em>shape</em> diverges, and #39 must not be told the two are
     * one.</b> On Cybele an {@code AssertionError} here killed one handler invocation and the main
     * agent lived on. Under JADE it escapes {@link Inbox#action()} into {@code Agent.run()}'s
     * {@code catch (Throwable)}, which terminates <b>the whole {@code Main} agent</b> — so the hub,
     * the planner and the generator all stop at once. Strictly worse; both void a recording. Same
     * divergence {@code RoadAgent} records for DEF-06 and {@code Planning} for DEF-09.
     *
     * @param request the asking station and its target
     */
    void pathFind(PathFindRequest request) {
	final String from = request.from();
	final String to = request.to();
	final String direction = Util.pathDirection(net, from, to);
	assert direction != null;
	emit(new PathFindReply(to, direction), from);
    }

    /**
     * process icoming station state
     * <p>
     * The station's name comes from the message's {@code :sender} AID, where 2008 recovered it
     * from the channel tag's suffix ({@code objectName}). INVENTORY DEF-12 notes that reifying the
     * name is the more correct form; this is that.
     *
     * @param station the sending station
     * @param info the snapshot
     */
    void recieveStationInfo(String station, StationInfo info) {
	stationInfos.put(station, info);
	fireChange();
	noteStarted(station);
    }

    /**
     * process icoming road state
     *
     * @param road the sending track
     * @param report its direction
     */
    void recieveRoadState(String road, RoadStateReport report) {
	roadAgentStates.put(road, report.state());
	fireChange();
	noteStarted(road);
    }

    /**
     * process icoming train state
     * <p>
     * {@code Train.KILLED} is now {@link TrainState#KILLED} — the same {@code "KILL"} literal, in
     * the ontology package where the payload lives, so this handler no longer has to name a class
     * #32 is about to rewrite.
     *
     * @param train the sending train
     * @param state its state, or the destructor sentinel
     */
    void recieveTrainState(String train, TrainState state) {
	final String string = state.state();
	if (TrainState.KILLED.equals(string)) {
	    trainStates.remove(train);
	} else {
	    trainStates.put(train, string);
	}
	fireChange();
    }

    /**
     * Strike one child off the startup barrier and, if it was the last, arm the generator.
     * <p>
     * The whole barrier is these few lines; the class comment is why they are here. Idempotent by
     * construction: {@code Set.remove} returns {@code false} for every later state push from the
     * same agent, so the generator is armed exactly once, by the message that empties the set.
     *
     * @param child the child that has just announced itself
     */
    private void noteStarted(String child) {
	if (!pendingStartup.remove(child) || !pendingStartup.isEmpty()) {
	    return;
	}
	if (generator == null) {
	    // Unreachable: a behaviour cannot run before setup() returns, and setup() creates the
	    // generator. Reported rather than dereferenced, because the alternative to a message
	    // is an NPE inside a message handler.
	    System.err.println("RailwayMainAgent " + name() + ": startup complete but no generator"
		    + " exists to arm. No train will ever be generated.");
	    return;
	}
	generator.start();
    }

    /**
     * The children this agent is still waiting for, in creation order. The startup barrier's
     * observable, for tests and for #38's assertions — a wedged boot is a non-empty set that never
     * empties, which is checkable where "the trace just stops" was not.
     *
     * @return the pending child names
     */
    Collection<String> pendingStartup() {
	return new ArrayList<String>(pendingStartup);
    }

    /** @return the planner installed on this agent, for tests and for #38 */
    Planning planning() {
	return planning;
    }

    /** @return the generator installed on this agent, for tests and for #38 */
    Generator generator() {
	return generator;
    }

    /**
     * This agent's local name — trace fields 4 and 5, and the {@code Main} that five channel
     * families carry.
     * <p>
     * Overridable so the agent can be unit-tested outside a container, exactly as {@code Station}
     * and {@code RoadAgent} do it.
     *
     * @return {@code Main}
     */
    protected String name() {
	return getLocalName();
    }

    /**
     * The single outbound seam. This agent sends exactly one kind of message —
     * {@code PATH_FIND_REPLY} — but the seam is the shape every ported agent has, and it is where
     * #36 adds the probe's topic AID as a second receiver.
     *
     * @param message the payload record
     * @param receiver the addressed agent's local name
     */
    protected void emit(RailwayMessage message, String receiver) {
	send(Messages.build(message, name(), receiver));
    }

    private void fireChange() {
	final List<Listener> current = listeners;
	if (current == null) {
	    return;
	}
	for (Listener listener : current) {
	    listener.railwayChanged();
	}
    }

    @Override
    public void addListener(Listener listener) {
	if (listeners == null) {
	    listeners = new ArrayList<Listener>();
	}
	listeners.add(listener);
    }

    @Override
    public long getSimTimeMs() {
        return clock == null ? config.getClockStartMs() : clock.nowMs();
    }

    @Override
    public void setPace(double pace) {
        if (clock != null) {
            clock.setPace(pace);
        }
    }

    /**
     * @return the one shared simulated clock, or {@code null} before {@code setup()} has run.
     *         Package-visible: the children get it as a construction argument, never through an
     *         accessor, and this exists for tests and for #38.
     */
    SimClock simClock() {
        return clock;
    }

    /**
     * getter
     * @return train Table Model
     */
    @Override
    public javax.swing.table.TableModel getTrainTableModel() {
        return trainTableModel;
    }

    /**
     * @return net
     */
    @Override
    public UnorientedGraph<String, String> getNet() {
        return net;
    }

    /**
     * @return station Infos
     */
    @Override
    public Map<String, StationInfo> getStationInfos() {
        return stationInfos;
    }

    /**
     * @return road Agent States
     */
    @Override
    public Map<String, RoadDirection> getRoadAgentStates() {
        return roadAgentStates;
    }

    enum Column {
	/**
	 *
	 */
	ID,
	/**
	 *
	 */
	POSITION;

	static final Column[] values = values();
    }

    /**
     * The train list behind the {@code JTable}, and DEF-24's site.
     * <p>
     * Registered unconditionally, GUI or not, exactly as 2008 registered it above the headless
     * guard — {@code defect-triage.md} §4.5 makes a point of that and it is reproduced. The three
     * {@code synchronized} methods are kept: this is the one class in the port whose second thread
     * survives, because #35 kept the Swing EDT that calls {@link #getRowCount()} and
     * {@link #getValueAt(int, int)} while the agent thread runs {@link #railwayChanged()}.
     */
    private class TableModel extends AbstractTableModel implements Listener {
	private static final long serialVersionUID = 1L;
	private List<Entry<String, String>> data = new ArrayList<Entry<String,String>>();

	@Override
	public String getColumnName(int column) {
	    return Column.values[column].name().toLowerCase();
	}

	@Override
	public int getColumnCount() {
	    return Column.values.length;
	}

	@Override
	public synchronized int getRowCount() {
	    return data.size();
	}

	@Override
	public synchronized Object getValueAt(int rowIndex, int columnIndex) {
	    Object result = null;
	    if (rowIndex < data.size()) {
		if (columnIndex == 0) {
		    result = data.get(rowIndex).getKey();
		} else if (columnIndex == 1) {
		    result = data.get(rowIndex).getValue();
		} else assert false;
	    }
	    return result;
	}

	@Override
	public synchronized void railwayChanged() {
	    final Set<Entry<String, String>> entrySet = trainStates.entrySet();
	    data = new ArrayList<Entry<String, String>>(entrySet);//zacachuje se
//	    Collections.sort(data, new Comparator<Entry<String, String>>() {
//	        @Override
//	        public int compare(Entry<String, String> o1, Entry<String, String> o2) {
//	            return o1.getKey().compareTo(o2.getKey());
//	        }
//	    });
	    fireTableDataChanged();
	}
    }

    /**
     * getter
     * @return road Delays
     */
    @Override
    public Map<String, Long> getRoadDelays() {
        return roadDelays;
    }

    /**
     * getter
     * @return station Capacities
     */
    @Override
    public Map<String, Integer> getStationCapacities() {
        return stationCapacities;
    }
}
