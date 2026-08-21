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

import java.util.Random;

import cz.vutbr.fit.ags.railway.domain.RoadQueue;
import cz.vutbr.fit.ags.railway.domain.RoadQueueItem;
import cz.vutbr.fit.ags.railway.domain.RoadSchedule;
import cz.vutbr.fit.ags.railway.domain.TravelDelay;
import cz.vutbr.fit.ags.railway.domain.clock.AgentClock;
import cz.vutbr.fit.ags.railway.domain.clock.SimClock;
import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.EnterReply;
import cz.vutbr.fit.ags.railway.domain.msg.EnterRequest;
import cz.vutbr.fit.ags.railway.domain.msg.LeaveNotice;
import cz.vutbr.fit.ags.railway.domain.msg.Party;
import cz.vutbr.fit.ags.railway.domain.msg.RailwayMessage;
import cz.vutbr.fit.ags.railway.domain.msg.RoadDirection;
import cz.vutbr.fit.ags.railway.domain.msg.RoadStateReport;
import cz.vutbr.fit.ags.railway.domain.msg.TravelEnd;
import cz.vutbr.fit.ags.railway.domain.msg.TravelStart;
import cz.vutbr.fit.ags.railway.domain.msg.Vote;
import cz.vutbr.fit.ags.railway.domain.msg.VoteRequest;
import cz.vutbr.fit.ags.railway.domain.msg.VoteResult;
import cz.vutbr.fit.ags.railway.jade.Messages;
import cz.vutbr.fit.ags.railway.jade.Templates;
import cz.vutbr.fit.ags.railway.jade.clock.ClockTickerBehaviour;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;


/**
 * Agent representing one single-track segment — <b>ported to JADE by #31</b>.
 * <p>
 * Since #28 the timetable and voting rule live in {@link RoadSchedule}, the waiting queue and
 * its ordering in {@link RoadQueue}, and the travel-time expression in {@link TravelDelay} —
 * all framework-free. What is left here is the framework glue: five channels, the direction
 * state machine the GUI observes, the travel timer, and the waiting queue.
 *
 * <h2>The direction state machine</h2>
 * Three states — {@code FREE}, {@code TRAVEL_LEFT}, {@code TRAVEL_RIGHT} — and one transition
 * function, unchanged from 2008:
 * <pre>
 *   FREE          --ENTER(from=leftStation)--&gt;  TRAVEL_RIGHT   (reply next=rightStation)
 *   FREE          --ENTER(from=rightStation)--&gt; TRAVEL_LEFT    (reply next=leftStation)
 *   TRAVEL_&#42;      --ENTER--&gt;                    unchanged, the train is queued
 *   TRAVEL_&#42;      --LEAVE, queue empty--&gt;       FREE
 *   TRAVEL_&#42;      --LEAVE, queue non-empty--&gt;   whatever the polled train's side implies
 * </pre>
 * Every one of those five arms ends in exactly one {@link #sendState()}, and {@code setup()}
 * ends in a sixth — so the {@code ROAD.STATE} line count and its transition sequence are the
 * 2008 ones, statement for statement. Note what {@code enter} does <em>not</em> do: the
 * queued arm republishes the <em>unchanged</em> state rather than skipping the publish. That
 * duplicate is 2008 behaviour and it is the trace's only witness to the queue path
 * ({@code docs/trace-normalizer.md} §2.4).
 *
 * <p>
 * <b>How a direction error is caught, given that the normalizer erases the state.</b>
 * {@code trace-normalizer.md} §2.1's {@code road-state} projection erases
 * {@code ROAD_STATE.state} outright, so this agent's headline field is not what pins it.
 * Direction is pinned independently, and #27 established the mechanism:
 * {@link #acceptTrain(String, String)} replies with the <em>other end</em> in both arms, so
 * {@code ENTER_REPLY.next} carries the direction into the golden — as does the
 * {@code ENTER.position} that produced it and the {@code TRAVEL_START}/{@code TRAVEL_END}
 * pair that follows. Swapping the two arms would leave {@code ROAD_STATE} unchanged under the
 * projection and change {@code next=} on every reply. §2.4's guidance for #39 is the other
 * half: a port whose {@code ENTER_REPLY} never follows a {@code LEAVE} on a contended track
 * has lost the queue path, and no {@code ROAD_STATE} diff will say so.
 *
 * <h2>The travel timer goes through the clock service, not a {@code WakerBehaviour}</h2>
 * {@code Activity.setTimer(CLOCK_ID, delay2, this, "travelEnd")} becomes
 * {@link AgentClock#scheduleIn(long, Runnable)} on this agent's own {@link AgentClock}, drained
 * by one {@link ClockTickerBehaviour} on the agent's own thread. A {@code WakerBehaviour} was
 * not an option and the reason is specific rather than stylistic: its deadline is <b>wall
 * clock</b>, fixed when the behaviour is added, so it would ignore {@code Gui}'s runtime pace
 * change and keep arriving across a {@code pause()}. {@code AgentClock} keys on absolute
 * <em>simulated</em> instants, where a pace change moves nothing and a pause stops everything
 * ({@code docs/clock-abstraction.md} §3.2).
 *
 * <p>
 * <b>DEF-16 survives the move intact.</b> {@link TravelDelay#travelMs(long, double)} is
 * unclamped and goes negative on 2.2645 % of draws for a 1 s track; Cybele fires a negative
 * delay immediately (SEM-06) and {@code AgentClock} does the same — an instant already past
 * fires at the next drain rather than being dropped. It does <em>not</em> spin: the drain
 * bounds the queue's arming sequence at entry, so a wake-up that armed another past-dated one
 * would wait for the next tick. This agent arms only from a message handler, never from inside
 * a wake-up, and it never calls {@link AgentClock#runDue()} itself — the ticker is the only
 * drain, which is the rule {@code AgentClock} states in as many words.
 *
 * <p>
 * <b>Resolution, stated rather than assumed.</b> A wake-up fires at the first tick at or after
 * its deadline, so it is late by up to {@link #CLOCK_GRANULARITY_MS} × pace simulated ms. The
 * baseline is late too (a zero-delay Cybele timer fires in 1–5 ms), and the five clock-derived
 * trace families are projected by the normalizer, so the contract this has to meet is ordering,
 * not timestamps.
 *
 * <h2>Which instant the queue comparator is evaluated at</h2>
 * {@link #push} and {@link #pop} read {@link AgentClock#now()} <b>once, at the call, in the
 * handler that is performing the heap operation</b> — the same instant 2008's
 * {@code pauseClock}/{@code resumeClock} bracket froze the clock at, and the same statement
 * position. Nothing is cached from an earlier handler and nothing is deferred, so a train
 * queued at simulated 12 000 is compared at 12 000, exactly as it was.
 *
 * <p>
 * That it <em>matches</em> 2008 is proved twice over, which is why the brackets could go:
 * <ol>
 *   <li>#28 surfaced the read to one call site. {@code RoadQueue.offer}/{@code poll} fix one
 *       {@code comparisonTime} for the whole heap operation, so every comparison inside it sees
 *       one value by construction — which is precisely what the bracket bought by freezing the
 *       clock across it.</li>
 *   <li>The value cancels algebraically anyway.
 *       {@code (plan(a) − t) − (plan(b) − t) == plan(a) − plan(b)}, and
 *       {@link RoadQueueItem#compareTo} does the subtraction in {@code long} <em>before</em>
 *       DEF-03's {@code (int)} narrowing, so the cancellation is exact and survives overflow.
 *       {@code defect-triage.md} §4.1 retracted the "unstable comparator" claim on exactly this
 *       ground.</li>
 * </ol>
 * So the two 2008 brackets ({@code RoadAgent.java:132-134} and {@code :167-169}) protect
 * nothing and are <b>deleted with no replacement</b>, as {@code docs/clock-abstraction.md} §2.6
 * directs. What the deletion does change is real and worth naming: those brackets froze the
 * <em>global</em> clock for ~35 µs each, visible to every other agent's {@code getTime}. Those
 * micro-freezes are gone. They are invisible in the contract because the clock-derived families
 * are projected — the reason it is safe is the projection, not the absence of an effect.
 *
 * <h2>The three defects at this site, decided rather than inherited</h2>
 * <ul>
 *   <li><b>Time-dependent comparator</b> — <em>not a defect</em>, and saying so is load-bearing.
 *       {@code defect-triage.md} §4.1 retracted issue item 3: {@code t} cancels, the ordering is
 *       stable while the elements sit in the heap, and the {@code Comparable} contract is not
 *       violated by the clock read. The port therefore <b>changes nothing</b> about the
 *       comparison — it only decides when the single surviving read happens, above. The real
 *       defects at the site are DEF-03 and DEF-04, and both stay pinned in
 *       {@link RoadQueueItem}: the {@code (int)} narrowing inverts past the {@code int} range
 *       (24.855 simulated days, unreachable at scenario scale, hence an L1 test rather than a
 *       golden), and {@code compareTo(null)} returns −1 where the interface specifies an NPE.
 *       Neither is touched here.</li>
 *   <li><b>DEF-04, the dead direction tie-break</b> — PIN, and deliberately not repaired.
 *       {@code RoadQueue.frequency} compares a {@link RoadQueueItem} to a {@link String} through
 *       {@code Object.equals} and is false for every pair that can be built, so the author's
 *       documented second criterion — <em>"potom podle poctu pozadavku z daneho smeru"</em> —
 *       is dead code returning a constant 0, and equal-{@code diff} items fall through to
 *       whatever order the heap happens to hold them in. Making it work would order trains
 *       differently from every recorded golden; {@code defect-triage.md} calls that "a port
 *       bug". #28 pinned it in
 *       {@code RoadQueueOrderingTest.frequency_is_dead_code_and_always_returns_zero}, and
 *       {@code RoadAgentStateMachineTest.a_direction_tie_is_not_broken_by_direction_frequency}
 *       pins it again <em>through this agent</em>, which is where a well-meaning port would
 *       have "fixed" it.</li>
 *   <li><b>DEF-06, the unguarded unboxing NPE</b> — PIN, kept reachable, <b>not guarded</b>.
 *       {@code RoadSchedule.plannedTime} returns {@code null} for a train the road never
 *       planned and {@link RoadQueueItem#diff(long)} unboxes it inside {@code compareTo},
 *       inside {@code PriorityQueue}'s sift — so a train queued after a lost {@code VOTE_RESULT}
 *       throws from inside the heap and leaves it undefined. {@code defect-triage.md} §3.3 rules
 *       "do not file, and do not fix", naming this ticket: <em>"#31 proposed a port-time null
 *       check; the pin-or-fix call is here and it is pin."</em> §5's carve-out would allow the
 *       check in the port <em>if</em> it also emitted an observable marker; that is declined,
 *       because the ontology has no slot such a marker could travel on and inventing one would
 *       add a trace line no golden holds. So {@link #push} calls straight through and the NPE
 *       escapes the handler.
 *       {@code RoadAgentStateMachineTest.queueing_an_unplanned_train_throws_from_inside_the_heap}
 *       is the lock.</li>
 *   <li><b>DEF-14, the single {@code traveledTrain} slot</b> — carried unchanged. It is
 *       overwritten by every {@code travelStart} and {@link #travelEnd()} notifies whatever is
 *       in it, which is safe only because a track is single-occupancy. That invariant is what
 *       {@code assert state != RoadDirection.FREE} and {@code assert traveledTrain != null}
 *       guard (160 evaluations each, both held); both survive here.</li>
 * </ul>
 *
 * <h2>Why there is no {@code synchronized} left, argued for this agent</h2>
 * The 2008 handlers were {@code synchronized} on the road and the lock was real: it excluded
 * over real shared state ({@code state}, {@code queue}, {@code schedule}, {@code traveledTrain}).
 * {@code docs/clock-abstraction.md} is explicit that "delete the bracket" is not "delete the
 * monitor" and that each agent must make its own argument. This one's has two halves, and the
 * second is the one a station did not have to make:
 * <ol>
 *   <li><b>The channel handlers.</b> All five arrive on one JADE message queue and are pulled
 *       out by one {@link Inbox} behaviour, which JADE runs on the agent's single thread. No
 *       two handlers can overlap.</li>
 *   <li><b>The timer callback.</b> This is the interesting half: a road is the one agent whose
 *       state was touched by something other than a channel handler. {@link #travelEnd()} now
 *       runs from {@link AgentClock#runDue()}, and {@code runDue()} runs its tasks
 *       <em>on the calling thread</em> — the caller being {@link ClockTickerBehaviour}, i.e.
 *       this agent's own thread again. That is not an implementation detail of the clock; it is
 *       the stated reason the clock package starts no thread of its own, because "a clock
 *       service that called back from a timer thread would silently break
 *       one-thread-per-agent for every agent that armed a timer".</li>
 * </ol>
 * With both halves on one thread the monitor excludes nothing that could otherwise interleave,
 * so it is redundant <em>here</em>. Not a licence for {@code Planning.java:97}, which #34 owns.
 *
 * <h2>What happened to {@code StaticRailwayObject} and {@code RailwayObject}</h2>
 * This agent no longer extends them; {@code Station} already stopped. The two base classes are
 * <b>left exactly as they are</b> — they now have no subclass, and that is deliberate rather
 * than an oversight. {@code Train} still names {@code StaticRailwayObject.ENTER}/{@code .LEAVE}
 * and {@code extends RailwayObject}, and {@code TraceProbe} names four of the constants, so
 * deleting either class today is a compile error. #32 takes {@code Train} out of
 * {@code RailwayObject}; #33 takes the probe's and the canvas' channel names; after both, the
 * two classes are dead and can go with them.
 *
 * <p>
 * <b>The alternative — extracting a shared JADE base now — was considered and declined.</b>
 * #30 inlined {@code voteRequest}/{@code voteResult}/{@code sendEnterReply} because its versions
 * still had to satisfy Cybele's abstract methods; that reason expires with this commit, so the
 * decision was made again on its own merits and came out the same way:
 * <ul>
 *   <li>The genuinely common surface is five short methods — {@code name}, {@code emit},
 *       {@code voteRequest}, {@code voteResult}, {@code sendEnterReply} — of which two are
 *       one-liners delegating to a domain object each agent constructs differently
 *       ({@code StationSchedule(capacity, voteWindow)} versus {@code RoadSchedule(delayMs)}).
 *       What is <em>not</em> common is everything a base class would have to abstract over: the
 *       inbound set ({@code PATH_FIND_REPLY} against {@code TRAVEL_START}), the dispatch switch,
 *       the behaviour list (a station has a watchdog and no clock; a road has a clock and no
 *       watchdog), and the {@code setup()} argument shape.</li>
 *   <li>It would mean rewriting {@code Station} — a file that landed two commits ago with its
 *       own review — inside a ticket whose subject is the road. That is exactly the coupling
 *       #4's per-ticket sequencing exists to avoid.</li>
 *   <li>A shared base would make one agent's per-agent arguments (the monitor, the null
 *       refusals, the emission order) into shared ones, when #4 requires each to be made
 *       separately.</li>
 * </ul>
 * <b>What that implies for #32–#34.</b> {@code Train} (#32) is inbound {@code START} /
 * {@code ENTER_REPLY} / {@code TRAVEL_END} and shares none of the five methods except
 * {@code name}/{@code emit}; it should inline too and then delete {@code RailwayObject}.
 * The point at which extraction pays is <em>after</em> #34, when all five agents exist and the
 * repeated shape can be read off four finished files instead of guessed at from two — and the
 * unit of duplication by then is {@code name}/{@code emit}/{@code Inbox}/{@code Drain}, which is
 * a JADE concern and belongs in the {@code jadeOntology} source set next to {@code Templates},
 * not in a resurrected {@code RailwayObject} in the application package.
 *
 * <h2>What still runs on Cybele</h2>
 * Nothing in this file does, and the application does not run until #32-#34 land — see #4. The
 * two vestigial members kept below, {@link #TRAVEL_START} and {@link State}, exist only so the
 * not-yet-ported {@code Train}, {@code RailwayMainAgent}, {@code RailwayCanvas} and
 * {@code TraceProbe} still compile.
 *
 * @author Bedrich Hovorka
 *
 */
public class RoadAgent extends Agent {
    private static final long serialVersionUID = 1L;
    /**
     * channel for notification
     * <p>
     * <b>This agent no longer uses it.</b> JADE routes {@code TRAVEL_START} by the road's AID
     * plus the {@code railway.TRAVEL_START} ontology slot ({@code docs/message-ontology.md} §6).
     * The constant stays for two reasons, the same two {@code Station.PATH_FIND_REPLY} carries:
     * {@code Train.java:96} and {@code TraceProbe.java:193} still name the channel, and #27's
     * {@code ChannelTableTest.cybele_channel_names_are_reproduced_verbatim} compares
     * {@code Channel.TRAVEL_START.cybeleChannelName("tr1")} against exactly this literal. The
     * first reason expires with #32/#33; the second does not, unless that assertion is
     * re-pointed at the literal string — which is what it should do, since the test's subject is
     * {@code Channel} and not this class.
     */
    public static final String TRAVEL_START = "TRAVEL_START.";
    /**
     * The {@link ClockTickerBehaviour} period, in <b>real</b> milliseconds.
     * <p>
     * A wake-up fires at the first tick at or after its deadline, so this is the worst-case
     * lateness in real time and {@code CLOCK_GRANULARITY_MS × pace} in simulated time. The
     * shortest simulated interval this simulation can distinguish is a 1 s track, i.e. 1000
     * simulated ms; at the toolbar's fastest preset (pace 8) 10 ms of granularity is 80
     * simulated ms, comfortably inside it. Cybele's own floor is 1–5 ms for a zero-delay timer
     * (SEM-06), so this is the same order of accuracy the baseline had.
     * <p>
     * A constant rather than a {@code ScenarioConfig} key <em>for now</em>: a road is the first
     * agent to need one, and a granularity that only one agent honours is not a scenario
     * parameter. When #33 and #34 give {@code Generator} and {@code Planning} their tickers the three
     * must agree, and that is the point to promote it to {@code sim.clock.granularityMs} — one
     * key, documented in {@code docs/scenario-config.md}, rather than three constants that can
     * drift apart.
     */
    static final long CLOCK_GRANULARITY_MS = 10L;
    private String rightStation;
    private String leftStation;
    private long delay;
    private RoadSchedule schedule;
    private RoadQueue queue;
    private RoadDirection state = RoadDirection.FREE;
    private String traveledTrain;
    /**
     * This road's own stream, keyed by its own name — every road agent draws its travel
     * jitter from a different sequence, so a road's draws no longer depend on how many
     * trains happened to be crossing the other six roads first. See {@link SimRandom}.
     * <p>
     * <b>The key is the agent name and stays the agent name.</b> {@code SimRandom.seedFor} is a
     * pure function of the master seed and that string, so a JADE {@code tr1} draws the same
     * sequence the Cybele {@code tr1} drew, in the same order, whatever order the seven roads
     * happen to be created in. Seeding by creation order was rejected in #15 for precisely the
     * reason that would have bitten here: constructor order was measured as six distinct orders
     * in six runs, and JADE's is a different six.
     */
    private Random random;
    /**
     * This agent's private scheduler over the simulation's one shared {@link SimClock} (#29).
     * <p>
     * The shared clock arrives as a {@code setup()} argument rather than through a static
     * accessor, and that is the point of the abstraction: {@code Cybele.getTime(CLOCK_ID)} was a
     * global reachable from anywhere, and reintroducing a {@code static SimClock} holder would
     * hand it straight back — along with a second JADE container in phase 2 silently sharing one
     * JVM's clock, and a POJO test unable to own time. The {@link AgentClock} wrapped around it
     * is per-agent and private, exactly as {@code docs/clock-abstraction.md} §3.1 splits them.
     */
    private AgentClock clock;

    /**
     * <b>Vestigial</b>, and superseded by {@link RoadDirection}, which is the same three
     * constants with the same three symbols in the framework-free {@code msg} package where the
     * {@code RoadStateReport} payload can reach them. This agent's state field is a
     * {@code RoadDirection}; nothing here reads the enum below any more.
     * <p>
     * It survives only because {@code RailwayCanvas.java:31} imports it,
     * {@code RailwayMainAgent.roadAgentStates} is a {@code Map<String, RoadAgent.State>} and
     * {@code TraceProbe.onRoadState} casts to it. Delete with #33.
     */
    public enum State {
	/**
	 * road is empty
	 */
	FREE(""),
	/**
	 * train travel from right station to left
	 */
	TRAVEL_LEFT("<"),
	/**
	 * train travel from left station to right
	 */
	TRAVEL_RIGHT(">");
	
	private String symbol;

	private State(String symbol) {
	    this.symbol = symbol;
	}

	/** 
	 * @return symbol of direction
	 */
	public String getSymbol() {	    
	    return symbol;
	}
    }

    /**
     * Reads the three construction arguments {@code RailwayMainAgent} used to pass to the
     * constructor — the travel delay and the two neighbouring stations — plus the shared
     * {@link SimClock}, registers the three behaviours, and pushes the opening
     * {@code ROAD.STATE}.
     * <p>
     * The opening push is the last statement, as it was the last statement of the 2008
     * constructor: it is the first line this road contributes to a trace, and
     * {@code trace-normalizer.md} §2.1's {@code startup-block} rule sorts that opening run by
     * agent name precisely because it exists.
     * <p>
     * <b>A {@code null} neighbour is refused here rather than misrouting a train later.</b>
     * {@link #acceptTrain} decides direction with {@code position.equals(leftStation)}, which is
     * simply {@code false} for a {@code null} {@code leftStation} — so every train would fall
     * into the {@code TRAVEL_LEFT} arm, past an {@code assert} that is inert with {@code -ea}
     * off, and be told to continue to {@code null}. That is a silently wrong {@code next=} on
     * every reply, i.e. the same shape as DEF-01 on a station. Startup is where the value comes
     * from and startup is where it is checked.
     */
    @Override
    protected void setup() {
	final Object[] args = getArguments();
	if (args == null || args.length != 4) {
	    throw new IllegalArgumentException("RoadAgent " + getLocalName()
		    + " needs {Number delaySeconds, String leftStation, String rightStation, SimClock}");
	}
	this.delay = ((Number) args[0]).longValue();
	this.leftStation = neighbour(args[1], "leftStation");
	this.rightStation = neighbour(args[2], "rightStation");
	if (!(args[3] instanceof SimClock)) {
	    throw new IllegalArgumentException("RoadAgent " + getLocalName()
		    + ": argument 4 must be the simulation's shared SimClock, was " + args[3]);
	}
	this.schedule = new RoadSchedule(delayInSeconds());
	this.queue = new RoadQueue(schedule);
	this.state = RoadDirection.FREE;
	// Ties this agent's stream key to the configured track names, which is what Main's
	// stderr stream table is computed from: a road agent named anything else would make
	// that table a fiction.
	assert ScenarioConfig.get().getRoadNames().contains(name())
		: "road agent name '" + name() + "' is not a configured track";
	this.random = SimRandom.forAgent(name());
	this.clock = new AgentClock((SimClock) args[3]);
	addBehaviour(new Inbox());
	addBehaviour(new Drain());
	addBehaviour(new ClockTickerBehaviour(this, CLOCK_GRANULARITY_MS, clock));
	sendState();
    }

    private String neighbour(Object value, String which) {
	if (!(value instanceof String) || ((String) value).isEmpty()) {
	    throw new IllegalArgumentException("RoadAgent " + getLocalName() + ": " + which
		    + " must be a non-empty station name, was " + value);
	}
	return (String) value;
    }

    /**
     * The one inbound behaviour. All five of this agent's channels share it, because a JADE
     * agent has one message queue and five competing {@code receive}/{@code block()} loops over
     * overlapping templates is the classic way to lose a message to it
     * ({@code docs/message-ontology.md} §7).
     * <p>
     * A road is the agent that shows why the template is ontology <em>and</em> performative and
     * not performative alone: its {@code ENTER} and its {@code TRAVEL_START} are both
     * {@code request}, and they are the two halves of one train's traversal. Matching on the act
     * would send a {@code TRAVEL_START} into {@link #enter}.
     */
    final class Inbox extends CyclicBehaviour {
	private static final long serialVersionUID = 1L;
	private final MessageTemplate template = Templates.inbound(Party.ROAD);

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
     * The drain {@code docs/message-ontology.md} §7 asks every ported agent to register: the
     * exact complement of {@link Inbox}'s template. With a correct port it never fires. When it
     * does it names the bug on the spot rather than presenting it as a hang.
     * <p>
     * <b>Note for #36</b>, carried over from {@code Station} because it applies verbatim:
     * {@code not(inbound)} is the complement of <em>everything</em>, not just of the railway
     * ontology, so in a live container it also matches AMS and DF traffic. A road registers with
     * neither today; if a later ticket gives one a DF registration, this template has to be
     * narrowed first or the drain will report platform housekeeping as a port bug.
     */
    final class Drain extends CyclicBehaviour {
	private static final long serialVersionUID = 1L;
	private final MessageTemplate template = Templates.unexpected(Party.ROAD);

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
     * Dispatch one inbound message to the handler for its channel.
     * <p>
     * Named {@code dispatch} rather than {@code handle} because {@code Behaviour.handle} exists
     * and an inner {@code CyclicBehaviour} would resolve the unqualified call to that one.
     * <p>
     * Package-visible and free of any container dependency on purpose: it is the seam
     * {@code docs/TESTING.md} §4.1 asks for, so the whole agent can be driven as a POJO.
     *
     * @param acl a message matching {@link Templates#inbound(Party)} for {@link Party#ROAD}
     */
    void dispatch(ACLMessage acl) {
	final Channel channel = Messages.channelOf(acl);
	final RailwayMessage message = Messages.contentOf(acl);
	switch (channel) {
	    case ENTER -> enter((EnterRequest) message);
	    case LEAVE -> leave((LeaveNotice) message);
	    case VOTE_REQUEST -> voteRequest((VoteRequest) message);
	    case VOTE_RESULT -> voteResult((VoteResult) message);
	    case TRAVEL_START -> travelStart((TravelStart) message);
	    default -> unexpected(acl);
	}
    }

    /**
     * Report a message this agent has no handler for. Loud, and it does not throw: the queue is
     * drained either way, so one stray message cannot wedge the agent.
     *
     * @param acl the message
     */
    void unexpected(ACLMessage acl) {
	System.err.println("RoadAgent " + name() + ": unexpected message, ontology=" + acl.getOntology()
		+ " performative=" + ACLMessage.getPerformative(acl.getPerformative())
		+ " from=" + Messages.senderName(acl));
    }

    private void sendState() {
	emit(new RoadStateReport(state), RailwayMainAgent.MAIN_AGENT_NAME);
    }
    
    /**
     * notification from train — the train has entered the track and is now crossing it.
     * <p>
     * Arms the travel timer. The Gaussian draw stays here, at the same point in the same
     * handler, because adding or reordering a draw on this stream re-aligns every later value of
     * it; what is done with the draw is {@link TravelDelay#travelMs(long, double)} (#28), which
     * carries the DEF-16 note on why the result is deliberately not clamped at 0.
     *
     * @param notice the train that is starting its traversal
     */
    void travelStart(TravelStart notice) {
	traveledTrain = notice.train();
	final long delay2 = TravelDelay.travelMs(delayInSeconds(), random.nextGaussian());
	clock.scheduleIn(delay2, this::travelEnd);
    }

    /**
     * The track's travel time in <b>milliseconds</b>.
     * <p>
     * The name is 2008's and it is a misnomer — it returns {@code delay * 1000}, i.e. the
     * configured seconds already converted to ms. Kept verbatim because it is the name
     * {@code TravelDelay}'s Javadoc and {@code defect-triage.md}'s DEF-16 row both quote when
     * they transcribe the expression, and a rename would break that thread for a reader
     * following it back to the baseline.
     *
     * @return {@code delay * 1000}
     */
    private final long delayInSeconds() {
	return delay*1000;
    }
    
    /**
     * The travel timer's wake-up: the train has reached the far end.
     * <p>
     * Runs on this agent's own thread, from {@link AgentClock#runDue()} via
     * {@link ClockTickerBehaviour} — see the class comment on why that is the whole reason the
     * monitor could go. {@code traveledTrain} is a single slot (DEF-14) and this notifies
     * whatever is in it; safe only because a track is single-occupancy, which the assert states.
     */
    void travelEnd() {
	assert traveledTrain != null;
	emit(new TravelEnd(name()), traveledTrain);
    }

    /**
     * Queueing system operation enter. Admit the train if the track is free, otherwise queue it.
     * <p>
     * Both arms fall through to one {@link #sendState()}, including the queued arm, which
     * therefore republishes the <em>unchanged</em> state. That duplicate is 2008 behaviour and
     * it is load-bearing: {@code docs/trace-normalizer.md} §2.4 identifies the
     * {@code TRAVEL_LEFT, TRAVEL_LEFT} pair as the trace's signature of the queue path.
     *
     * @param request the train, and the station it is entering from
     */
    void enter(EnterRequest request) {
	final String train = request.train();
	final String trainPosition = request.arrivingFrom();
	
	if (state == RoadDirection.FREE) {
	    acceptTrain(train, trainPosition);
	} else {
	    push(train, trainPosition);
	}
	sendState();
    }

    /**
     * Queue a train that could not be admitted.
     * <p>
     * The 2008 {@code pauseClock}/{@code resumeClock} bracket is gone; see the class comment for
     * why it protected nothing and why {@link AgentClock#now()} read here, once, is the same
     * instant it froze. DEF-06 lives on this line: a train the road never planned makes
     * {@code RoadQueueItem.diff} unbox a {@code null} inside {@code PriorityQueue}'s sift, and
     * the resulting {@link NullPointerException} escapes this method and the handler above it.
     * That is the pinned behaviour, not an oversight.
     *
     * @param train the waiting train
     * @param trainPosition the station it wants to enter from
     */
    private void push(final String train, final String trainPosition) {
	queue.offer(train, trainPosition, clock.now());
    }
    
    /**
     * Admit a train and set the direction of travel from the end it entered by.
     * <p>
     * Each arm replies with the <em>other</em> end, which is what puts the direction into
     * {@code ENTER_REPLY.next} and therefore into the golden — the field that catches a
     * direction error, since {@code ROAD_STATE.state} is erased by the normalizer.
     *
     * @param train the admitted train
     * @param position the station it is entering from
     */
    private void acceptTrain(String train, String position) {
	// pokud je to leva stanice jede se doprava
	if (position.equals(leftStation)) {
	    state = RoadDirection.TRAVEL_RIGHT;
	    sendEnterReply(train, rightStation);
	} else {
	    assert position.equals(rightStation);
	    state = RoadDirection.TRAVEL_LEFT;
	    sendEnterReply(train, leftStation);
	}
    }

    /**
     * Queueing system operation leave. Free the track, or hand it straight to the next train in
     * the queue.
     * <p>
     * <b>Statement order is 2008's, deliberately.</b> {@code Station.leave} had to hoist
     * {@code schedule.removeTrain} above its direction resolution, because a continuation made
     * the window between them permeable and a {@code VOTE_REQUEST} landing inside it would vote
     * against a departed train. There is no such window here: {@link #acceptTrain} is
     * straight-line code that sends one message and returns, so nothing of this agent's can run
     * between it and the {@code removeTrain} below. Reordering would have been a change with no
     * reason behind it.
     *
     * @param notice the departing train
     */
    void leave(LeaveNotice notice) {
	assert state != RoadDirection.FREE;
	final String train = notice.train();
	
	if (queue.size() == 0) {
	    state = RoadDirection.FREE;
	} else  {
	    final RoadQueueItem poll = pop();
	    acceptTrain(poll.getTrain(), poll.getPosition());
	}
	schedule.removeTrain(train);
	sendState();
    }

    /**
     * Take the next train to admit. The 2008 bracket is gone for the same reason as
     * {@link #push}'s.
     *
     * @return the polled item; never {@code null}, because the caller has checked the size
     */
    private RoadQueueItem pop() {
	return queue.poll(clock.now());
    }

    /**
     * Process incoming vote request.
     *
     * @param request the train and the time it is expected
     */
    void voteRequest(VoteRequest request) {
	final long diff = computeDifference(request.train(), request.expected());
	emit(new Vote(name(), request.train(), diff), RailwayMainAgent.MAIN_AGENT_NAME);
    }

    /**
     * Process incoming vote result.
     *
     * @param result the agreed slot
     */
    void voteResult(VoteResult result) {
	addToPlan(result.train(), result.planned());
    }

    /**
     * Enter reply - accepting train
     *
     * @param train the admitted train
     * @param nextPosition the station at the far end of this track
     */
    private void sendEnterReply(String train, String nextPosition) {
	emit(new EnterReply(name(), nextPosition), train);
    }

    /**
     * find free in plan and compute difference between expected time and founded free in plan
     *
     * @param train train id
     * @param time the requested entry time
     * @return time difference
     */
    long computeDifference(String train, long time) {
	return schedule.computeDifference(train, time);
    }
    
    /**
     * add train to plan
     *
     * @param train train id
     * @param time agreed entry time
     */
    void addToPlan(String train, long time) {
	schedule.addToPlan(train, time);
    }

    /**
     * The track's current direction of travel — the value {@code ROAD.STATE} carries.
     *
     * @return the state
     */
    RoadDirection roadState() {
	return state;
    }

    /**
     * @return how many trains are waiting for this track
     */
    int queueSize() {
	return queue.size();
    }

    /**
     * This agent's private scheduler, so a POJO test can drain it the way
     * {@link ClockTickerBehaviour} does. Nothing in the application calls this.
     *
     * @return the agent clock
     */
    AgentClock agentClock() {
	return clock;
    }

    /**
     * This agent's local name — trace fields 4 and 5, and the RNG stream key.
     * <p>
     * Overridable so the agent can be unit-tested outside a container; in the container it is
     * {@code getLocalName()}, which is what {@code RailwayObject.getName()} reconstructed from
     * the Cybele agent id (INVENTORY DEF-12 notes that reifying it is the more correct form).
     *
     * @return the track name
     */
    protected String name() {
	return getLocalName();
    }

    /**
     * The single outbound seam. Every message this agent sends goes through here.
     * <p>
     * Overridable for the same reason as {@link #name()}, and it is where #36 adds the probe's
     * topic AID as a second receiver ({@code Messages.build(msg, from, to, topic)}).
     *
     * @param message the payload record
     * @param receiver the addressed agent's local name
     */
    protected void emit(RailwayMessage message, String receiver) {
	send(Messages.build(message, name(), receiver));
    }
}
