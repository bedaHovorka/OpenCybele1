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
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;

import cz.vutbr.fit.ags.railway.domain.DispatchTimeline;
import cz.vutbr.fit.ags.railway.domain.TrainPlan;
import cz.vutbr.fit.ags.railway.domain.VoteEnvelope;
import cz.vutbr.fit.ags.railway.domain.VoteRound;
import cz.vutbr.fit.ags.railway.domain.clock.AgentClock;
import cz.vutbr.fit.ags.railway.domain.clock.SimClock;
import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.PlanTrain;
import cz.vutbr.fit.ags.railway.domain.msg.RailwayMessage;
import cz.vutbr.fit.ags.railway.domain.msg.StartCommand;
import cz.vutbr.fit.ags.railway.domain.msg.Vote;
import cz.vutbr.fit.ags.railway.domain.msg.VoteRequest;
import cz.vutbr.fit.ags.railway.domain.msg.VoteResult;
import cz.vutbr.fit.ags.railway.domain.util.UnorientedGraph;
import cz.vutbr.fit.ags.railway.domain.util.Util;
import cz.vutbr.fit.ags.railway.jade.Messages;
import cz.vutbr.fit.ags.railway.jade.Templates;
import cz.vutbr.fit.ags.railway.jade.clock.ClockTickerBehaviour;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

/**
 * The distributed election that decides when a train may depart — <b>ported to JADE by
 * #34</b>, and the one place in this application where the framework was load-bearing.
 *
 * <h2>What was here, and what its deletion cost</h2>
 * The 2008 planner ran the election with a {@link java.util.concurrent.CountDownLatch} sized to
 * the path, <b>blocked inside the {@code PLAN_TRAIN} message handler</b> on
 * {@code latch.await()}, and was released from a <em>second</em> Cybele activity,
 * {@code VoteCollecting}, whose entire body was "record the vote, call {@code countDown()}".
 * That activity existed for one reason — the kernel's {@code Activity.sendAllBlock} was
 * unusable (the project report says so) — so a thread had to be borrowed to unblock the first
 * one. It is <b>deleted</b> here, exactly as {@code PathFinding} was deleted by #30.
 *
 * <p><b>What the deletion cost the original, stated for #41.</b> Nothing that was wanted, and
 * two things that were not:
 * <ul>
 *   <li>{@code VoteCollecting} was the <em>only</em> reason {@code votes} and
 *       {@code trainCountDowns} needed to be thread-safe. With it gone, the
 *       {@code synchronized(votes)} block, the {@code Collections.synchronizedMap} and the
 *       {@code PriorityBlockingQueue} all lose their second party — see "Why there is no
 *       {@code synchronized} left" below.</li>
 *   <li>It carried <b>DEF-09</b>: {@code getTrainCountDowns().get(train).countDown()} with no
 *       null check, so a vote arriving after {@code Planning.java:89} had removed the latch
 *       dereferenced {@code null}. That site moves into {@link #vote} verbatim rather than
 *       disappearing — see the DEF-09 note there.</li>
 * </ul>
 *
 * <h2>The tally, and why it is a pair of counters rather than a set</h2>
 * {@link VoteRound} (framework-free, {@code domain}) holds one election: the <b>explicit
 * expected-responder set</b> the 2008 code only ever had as a latch <em>count</em>, one ballot
 * per distinct voter, and an outstanding count decremented per <em>arrival</em>. Keeping those
 * two numbers apart is not an implementation detail — it is DEF-05, and it is reproduced
 * deliberately; see {@link VoteRound}'s class comment and
 * {@code PlanningElectionTest.def05_a_duplicate_vote_closes_the_round_with_a_short_ballot}.
 *
 * <p><b>No {@code ContractNetInitiator}, and no {@code :protocol}.</b> The issue text suggested
 * both; #27 supersedes it and {@code docs/message-ontology.md} §4.1 states the reason as a
 * requirement rather than a preference. This election is a <em>degenerate</em> contract net:
 * {@code accept-proposal} goes to <b>every</b> voter and carries a value <b>no</b> voter
 * proposed ({@code VOTE} is a delay <em>difference</em>, {@code VOTE_RESULT} an absolute
 * accumulated instant); there is no {@code reject-proposal}, no {@code refuse}, no
 * {@code not-understood} and no deadline anywhere. {@code ContractNetInitiator} would supply a
 * {@code reply-by} and a refuse branch, which changes behaviour under exactly the load where
 * DEF-22 shows. The tally above is a plain object; nothing in this file is a protocol
 * behaviour.
 *
 * <h2>The seriality is part of the contract, so the mailbox is the backlog</h2>
 * This is the half of the port that is easy to lose. {@code latch.await()} did not merely stall
 * <em>one</em> train: Cybele dispatches one activity's events serially (SEM-04), so a blocked
 * {@code planTrain} head-of-line-blocked the whole {@code PLAN_TRAIN} channel. <b>The 2008
 * application ran exactly one election at a time.</b> Two consequences, both contractual:
 * <ul>
 *   <li>Every voter's {@code computeDifference} sees a timetable that already contains the
 *       previous train's {@code addToPlan}. Overlapping elections would let two trains vote
 *       against the same free slot and both get it — a different departure time on a healthy
 *       run, not merely a different interleaving.</li>
 *   <li>DEF-22's recorded observable <em>is</em> the seriality: "no further train is planned
 *       for the rest of the run while {@code Generator} keeps creating {@code Train} agents
 *       that never start" ({@code docs/defect-triage.md} §3.3). A planner that ran elections
 *       concurrently would have quietly repaired the most severe defect in the codebase.</li>
 * </ul>
 * So the port keeps it, without blocking anything: while an election is in flight the
 * {@link Inbox} narrows its template to {@code VOTE} alone, and a {@code PLAN_TRAIN} simply
 * <b>stays in the agent's message queue</b> — which is what a Cybele channel queue was doing
 * anyway. {@link #template()} is that gate, and it is package-visible because #33 owns the
 * {@code Main} agent's queue and must not widen it. <b>#33 has landed and did not</b>:
 * {@code RailwayMainAgent.hubTemplate()} lists its four channels rather than calling
 * {@code Templates.inbound(Party.MAIN)}, and {@code RailwayMainAgentInboxTest} pins the
 * difference. Arrival order among queued
 * {@code PLAN_TRAIN}s is preserved: {@code receive(t)} scans the queue in order.
 *
 * <h2>Scheduling on an absolute instant, and the bracket that goes with it</h2>
 * {@code Planning.java:108-112} bracketed its arming with {@code pauseClock}/{@code resumeClock},
 * and unlike {@code RoadAgent}'s two brackets <b>that one was real</b>.
 * {@code docs/clock-abstraction.md} §2.5 measured it ({@code docs/probes/ExpJ.java}, 3 runs, 10
 * trials per arm): {@code Activity.setTimer} takes its relative delay from the <em>kernel's
 * own, later</em> clock read, so a 200 ms gap between "read the clock" and "arm the timer"
 * moved the firing by 201 ms, and the bracket removed that drift completely.
 *
 * <p>The replacement is therefore <b>not</b> "delete the bracket". It is
 * {@link AgentClock#scheduleAt(long, Runnable)} on the absolute instant
 * {@code requestTime + timeDiff} — which the planner has already computed for the
 * {@code VOTE_RESULT} broadcast — because {@code scheduleAt} performs <b>no second clock
 * read</b>. With the second read gone the bracket has nothing left to protect, and only then
 * does it go. Deleting it while keeping a relative delay would have reintroduced the
 * 200 ms-class drift {@code ExpJ} measured.
 *
 * <p>2008's {@code t > 0 ? t : 0} clamp disappears with the relative delay it clamped. A
 * departure already in the past fires at the next drain, which is what Cybele does with a
 * zero-delay timer too (SEM-06, 1–5 ms).
 *
 * <h2>Why there is no {@code synchronized} left, and the argument is this activity's own</h2>
 * {@code docs/clock-abstraction.md} §2.6 is explicit that "delete the bracket" is not "delete
 * the monitor": {@code synchronized (this)} at {@code Planning.java:97} was a real lock over
 * real shared state. Here is the state and here is the second party, both named:
 * <ul>
 *   <li>{@code votes} — {@code Planning} drained it, {@code VoteCollecting} filled it;</li>
 *   <li>{@code trainCountDowns} — {@code Planning} put and removed, {@code VoteCollecting}
 *       read;</li>
 *   <li>{@code queue} — {@code Planning} put, the <b>kernel timer service</b> polled, from its
 *       own thread.</li>
 * </ul>
 * All three second parties are gone. {@code VoteCollecting} is deleted and its work happens in
 * {@link #vote}, on the {@link Inbox}'s thread, which is the host agent's. The kernel timer
 * service is replaced by {@link AgentClock}, whose {@code runDue()} runs its wake-ups <b>on the
 * calling thread</b> — and the only caller is this activity's {@link ClockTickerBehaviour},
 * which is also the host agent's thread. JADE runs one agent on one thread, so every field
 * below is single-threaded. The monitors, the {@code synchronizedMap} and the
 * {@code PriorityBlockingQueue} all go. That is the per-agent argument #4 asks each ticket to
 * make for itself; it is not a licence for {@code Train} or the hub, and it does depend on #33
 * keeping this activity's behaviours on the {@code Main} agent rather than handing its state to
 * the Swing thread. #33 landed and did keep them there — and it is worth noting that the hub
 * could <em>not</em> make the same argument for itself: it publishes three maps and a
 * {@code TableModel} that the Swing EDT reads, so its monitors stay. Nothing of this activity's
 * state is published that way.
 *
 * <p>{@code PriorityBlockingQueue} &rarr; {@link PriorityQueue} is observationally inert for a
 * second reason as well as the threading one: {@link TrainPlan#compareTo} breaks a departure
 * tie by train id, ids are unique, so the order is <b>total</b> and {@code poll()} returns the
 * unique minimum whatever the heap layout is.
 *
 * <h2>The defects at this site, decided</h2>
 * <ul>
 *   <li><b>DEF-22, the unbounded wait</b> — the latch count <em>was</em> the protocol: no
 *       conversation ids, no refusals, no timeout, so one lost vote wedged the planner forever
 *       and leaked its {@code trainCountDowns} entry. {@code defect-triage.md} §6.1 requires
 *       the port's bounded wait to have "an explicit, logged timeout branch — never a silent
 *       fall-through that proceeds as if the votes arrived", and #30 refused a timeout that
 *       resumed with a {@code null} because that manufactures a different defect out of this
 *       one. So the branch here is a {@link Watchdog}: it names the train, the missing voters
 *       and the elapsed time on <b>stderr</b>, where #12's {@code ErrorScanner} reads and no
 *       golden holds — and it does <b>not</b> close the round, does <b>not</b> synthesise an
 *       envelope from partial votes, and does <b>not</b> release the {@code PLAN_TRAIN} gate.
 *       The observable outcome is the hang's: that train never departs and no later train is
 *       planned. The leak is preserved too — {@link #inFlight} stays set forever, which is
 *       what the leaked latch entry was.
 *       <p>The round is deliberately <em>not</em> abandoned on the alarm, and that is the
 *       subtle half. In 2008 the latch stayed armed, so a vote that arrived very late still
 *       counted down and could complete the round; abandoning it here would diverge from that
 *       <em>and</em> would open a fresh path into DEF-09 below. Reporting without abandoning
 *       leaves both alone.</li>
 *   <li><b>DEF-05, the duplicate vote</b> — PIN, not repaired. Reproduced by {@link VoteRound}
 *       counting arrivals while the ballots are keyed by voter. The explicit responder set this
 *       port introduces makes deduplication a one-line temptation; it is declined, and
 *       {@code PlanningElectionTest.def05_a_duplicate_vote_closes_the_round_with_a_short_ballot}
 *       is the lock. 2008's {@code assert ballots.size() == path.size()}
 *       ({@code Planning.java:96}) stays at its call site in {@link #close}, which is the
 *       contract surface: goldens were recorded with {@code -ea} and #36 replays with
 *       {@code -ea}.</li>
 *   <li><b>DEF-09, the late vote</b> — PIN. See {@link #vote}.</li>
 *   <li><b>DEF-17</b>, {@code placeTrainIntoFirstStation} polling the queue <em>head</em>
 *       rather than the plan whose wake-up fired — PIN. See that method.</li>
 *   <li><b>DEF-02, the lost {@code START}</b> — the author's own {@code //BUG ne vzdy se
 *       doruci}. <b>JADE cannot reproduce it, and this is a genuine unfixable semantic
 *       divergence for #41's comparison log, not an omission.</b> On Cybele the send raced the
 *       {@code Train} agent's {@code Activity.openChannel(START+…)}; a send to a channel with no
 *       open receiver is silently dropped (SEM-06) and the train never starts. An
 *       AID-addressed {@code ACLMessage} queues in the recipient's mailbox whether or not a
 *       behaviour is waiting for it, so the race has no JADE analogue. It is recorded, not
 *       simulated: manufacturing a random drop would be inventing behaviour, and #24's
 *       recording gate treats a dropped train as a <em>baseline</em> artefact anyway
 *       ({@code defect-triage.md} DEF-02: "do not file"). Expect the JADE run to depart trains
 *       the golden lost.</li>
 * </ul>
 *
 * <h2>Identity: this is still {@code Main}</h2>
 * {@code Planning} is an <em>activity</em> of {@code RailwayMainAgent}, so the frozen goldens
 * carry the literal {@code Main} in trace field 4 of {@code VOTE_REQUEST}, {@code VOTE_RESULT}
 * and {@code START}, field 5 of {@code VOTE}, and <b>both</b> fields of {@code PLAN_TRAIN}.
 * {@code docs/message-ontology.md} §11 leaves open the option of giving the three activities
 * their own AIDs; taking it would diff <b>five</b> channel families, so this port does not take
 * it. Everything here runs as behaviours of the host agent and {@link #name()} is that agent's
 * local name — {@code Main}.
 *
 * <h2>What still runs on Cybele</h2>
 * Nothing in this file does, and the application does not run until #32 lands — see #4. The
 * two public channel-name constants below are kept because {@code StaticRailwayObject},
 * {@code TraceProbe} and #27's
 * {@code ChannelTableTest.cybele_channel_names_are_reproduced_verbatim} still name them.
 * ({@code Generator} dropped off that list with #33: it addresses {@code PLAN_TRAIN} to an AID.)
 *
 * @author Bedrich Hovorka
 */
public class Planning implements Serializable {
    /**
     * channel id for generated trains
     * <p>
     * <b>This activity no longer uses it.</b> JADE routes {@code PLAN_TRAIN} by the
     * {@code railway.PLAN_TRAIN} ontology slot to the {@code Main} AID. The constant stays for
     * the not-yet-ported {@code Generator} and {@code TraceProbe}, and because
     * {@code ChannelTableTest} compares {@code Channel.PLAN_TRAIN} against exactly this literal.
     */
    public static final String PLAN_TRAIN = "PLAN_TRAIN";
    /**
     * channel id for incomming votes
     * <p>
     * Vestigial for the same two reasons as {@link #PLAN_TRAIN}; {@code StaticRailwayObject}
     * still names it.
     */
    public static final String VOTE = "VOTE";
    /**
     * How long an election may stay incomplete before {@link Watchdog} says so, in
     * <b>wall-clock</b> milliseconds, and also the watchdog's tick period.
     * <p>
     * Wall clock rather than simulated time, and for the reason {@code Station}'s
     * {@code PATH_FIND_WATCHDOG_MS} gives: what is being timed is a <em>message round trip</em>,
     * which is a real-time phenomenon, not a simulated interval. Using the simulated clock would
     * make the alarm fire eight times sooner at pace 8 for no reason connected to the fault.
     * <p>
     * The bound is deliberately far larger than any real round trip — a path is 9 or 11 voters
     * and every one of them answers from a message handler with no I/O. This is a "this will
     * never happen" alarm, not a timeout that trades correctness for liveness, and it must never
     * fire on a healthy run.
     */
    static final long VOTE_WATCHDOG_MS = 60_000L;
    private static final long serialVersionUID = 1L;

    /**
     * The JADE agent whose thread, mailbox and identity this activity runs on — {@code Main}.
     * Not a {@code RailwayMainAgent}: this activity needs a name, a queue and {@code send}, and
     * taking the hub type instead would tie #34 to #33's not-yet-done port for nothing.
     */
    private final Agent host;
    private final UnorientedGraph<String, String> net;
    private final Map<String, Long> roadDelays;
    private final AgentClock clock;
    private final long tickPeriodMs;
    /**
     * The one election in flight, or {@code null} when the planner is idle.
     * <p>
     * <b>A single field, not a map, and the 2008 code justifies it.</b>
     * {@code trainCountDowns.put} ran at the top of {@code planTrain} and the matching
     * {@code remove} ran immediately after {@code await()} returned — with the handler blocked
     * in between and the activity's dispatch serial. So that map could never hold more than one
     * live entry. It could hold one <em>dead</em> entry forever, which is DEF-22's leak, and
     * this field reproduces that too: a wedged round is never cleared.
     */
    private VoteRound inFlight;
    /**
     * Departures waiting for their wake-up, ordered by {@link TrainPlan#compareTo}.
     * {@code PriorityBlockingQueue} in 2008; see the class comment for why the plain
     * {@link PriorityQueue} is the same queue here.
     */
    private final Queue<TrainPlan> queue = new PriorityQueue<TrainPlan>();

    /**
     * Installs the planner on the {@code Main} agent: one {@link Inbox}, one {@link Watchdog}
     * and one {@link ClockTickerBehaviour}.
     *
     * <p>The 2008 constructor opened the {@code PLAN_TRAIN} channel and spawned
     * {@code VoteCollecting}; this one registers behaviours instead, which is the same
     * "declare what I listen to, at construction" shape.
     *
     * <p><b>Two obligations this handed to #33</b>, which owns the {@code Main} agent, and both
     * are discharged:
     * <ul>
     *   <li>the <b>drain</b>. {@code docs/message-ontology.md} §7 wants one lowest-priority
     *       {@code CyclicBehaviour} on {@code Templates.unexpected(Party.MAIN)} per agent, and
     *       it belongs to the agent, not to one of its activities — this class must not claim
     *       the complement of a template set it only partly consumes. {@link #unexpected} here
     *       is the {@code default} arm of {@link #dispatch}, not a drain.
     *       {@code RailwayMainAgent.Drain} is that behaviour.</li>
     *   <li>the <b>ticker</b>. This installs its own, because it must have one to drain its own
     *       {@link AgentClock}. If {@code Generator} gets an {@code AgentClock} as well, share
     *       one clock and one ticker rather than adding a second: two tickers on one agent are
     *       harmless but the period is a budget ({@link RoadAgent#granularityMsFor(double)})
     *       and it is easier to keep one honest than two. #33 shares: {@code Generator} is
     *       constructed with {@link #agentClock()}, so this agent has one clock, one ticker and
     *       one budget — now {@code sim.clock.granularityMs}.</li>
     * </ul>
     *
     * @param host the {@code Main} agent; must already have its AID, i.e. this must be called
     *        from its {@code setup()}
     * @param net the railway topology, for {@link Util#path}
     * @param roadDelays travel time per track in seconds, for {@link DispatchTimeline}
     * @param shared the simulation's one {@link SimClock} — passed in rather than fetched from a
     *        static holder, which would hand back the global {@code Cybele.getTime} the clock
     *        abstraction removed
     */
    public Planning(Agent host, UnorientedGraph<String, String> net, Map<String, Long> roadDelays,
	    SimClock shared) {
	super();
	if (host == null || net == null || roadDelays == null || shared == null) {
	    throw new IllegalArgumentException(
		    "Planning needs {Agent host, UnorientedGraph net, Map roadDelays, SimClock}");
	}
	this.host = host;
	this.net = net;
	this.roadDelays = roadDelays;
	this.clock = new AgentClock(shared);
	this.tickPeriodMs = RoadAgent.granularityMsFor(shared.pace());
	host.addBehaviour(new Inbox());
	host.addBehaviour(new Watchdog());
	host.addBehaviour(new ClockTickerBehaviour(host, tickPeriodMs, clock));
    }

    /**
     * Pulls this activity's two channels out of the {@code Main} agent's one message queue.
     * <p>
     * The template is not constant — see {@link Planning#template()}. That is the whole
     * seriality mechanism, and it lives in the template rather than in a hand-rolled backlog
     * list so that queued {@code PLAN_TRAIN}s keep their arrival order for free and so that a
     * wedged election presents as a full mailbox rather than as an unbounded field.
     */
    final class Inbox extends CyclicBehaviour {
	private static final long serialVersionUID = 1L;

	@Override
	public void action() {
	    final ACLMessage acl = myAgent.receive(template());
	    if (acl == null) {
		block();
		return;
	    }
	    dispatch(acl);
	}
    }

    /**
     * DEF-22's explicit, logged branch. See the class comment: it reports and does not resume.
     * <p>
     * A {@code TickerBehaviour} rather than a wake-up on the {@link AgentClock}, because the
     * quantity it bounds is wall-clock (see {@link Planning#VOTE_WATCHDOG_MS}) and because an
     * alarm that stopped ticking when the simulated clock is paused would be an alarm that goes
     * quiet in exactly one of the situations it exists to report.
     */
    final class Watchdog extends TickerBehaviour {
	private static final long serialVersionUID = 1L;

	Watchdog() {
	    super(host, VOTE_WATCHDOG_MS);
	}

	@Override
	protected void onTick() {
	    reportOverdue(System.nanoTime());
	}
    }

    /**
     * The template {@link Inbox} draws with, and the {@code PLAN_TRAIN} gate.
     * <p>
     * {@code VOTE} is always taken. {@code PLAN_TRAIN} is taken only when no election is in
     * flight, so that a new request waits in the agent's mailbox exactly as it waited in the
     * Cybele channel queue behind the blocked handler. #33 calls {@code receive} with
     * <em>this</em> template for the two channels, and its own {@code hubTemplate()} lists the
     * other four explicitly rather than using {@code Templates.inbound(Party.MAIN)} — which
     * would take {@code PLAN_TRAIN} out from under this gate and lose the seriality, and with it
     * DEF-22's observable.
     *
     * @return {@code VOTE} alone while busy, {@code VOTE | PLAN_TRAIN} while idle
     */
    MessageTemplate template() {
	if (inFlight != null) {
	    return Templates.of(Channel.VOTE);
	}
	return Templates.anyOf(List.of(Channel.PLAN_TRAIN, Channel.VOTE));
    }

    /**
     * The POJO seam: one incoming message, one handler.
     *
     * @param acl a message the {@link Inbox} pulled off the queue
     */
    void dispatch(ACLMessage acl) {
	final Channel channel = Messages.channelOf(acl);
	final RailwayMessage message = Messages.contentOf(acl);
	switch (channel) {
	    case PLAN_TRAIN -> planTrain((PlanTrain) message);
	    case VOTE -> vote((Vote) message);
	    default -> unexpected(acl);
	}
    }

    /**
     * Reports a message this activity has no handler for, on stderr, and discards it.
     *
     * @param acl the offending message
     */
    void unexpected(ACLMessage acl) {
	System.err.println("Planning " + name() + ": unexpected message, ontology=" + acl.getOntology()
		+ " performative=" + ACLMessage.getPerformative(acl.getPerformative())
		+ " from=" + Messages.senderName(acl));
    }

    /**
     * Opens the election for one train: read the clock, register the round, broadcast the
     * estimate to every member of the path.
     *
     * <p>Statement order is 2008's, and one bit of it is load-bearing: the round is registered
     * <b>before</b> the first {@code VOTE_REQUEST} goes out, exactly as
     * {@code trainCountDowns.put} preceded the broadcast loop. Nothing can interleave here under
     * one-thread-per-agent dispatch, but the invariant "a vote can never arrive before its
     * round exists" is what keeps DEF-09 confined to the <em>late</em> case it describes, and it
     * should not rest on the scheduler.
     *
     * <p><b>The order is kept even though it is unobservable, and that is measured rather than
     * assumed.</b> Mutation M12 — register the round <em>after</em> the broadcast loop — survives
     * the whole suite, because nothing can run between the two statements on a single-threaded
     * agent and no message this activity sends comes back to it synchronously. It is kept because
     * it is free and because it stops being free the moment anything here becomes re-entrant, the
     * same standing {@code RoadAgent}'s M13 has.
     *
     * <p>{@link Util#path} returns {@code null} for an unreachable pair and this method does not
     * guard against it — 2008 did not either, and {@code ScenarioConfig} validates connectivity
     * at startup precisely so the failure does not surface here.
     *
     * @param request the {@code PLAN_TRAIN} content: train, origin, destination
     */
    @SuppressWarnings("boxing")
    void planTrain(PlanTrain request) {
	final String train = request.train();
	final List<Object> path = Util.path(net, request.from(), request.to());
	final List<String> voters = new ArrayList<String>(path.size());
	for (Object o : path) {
	    // .toString() is what 2008 used to build the channel suffix; on this graph the
	    // elements are already Strings, so this is identity and the receiver names are the
	    // 2008 channel suffixes.
	    voters.add(o.toString());
	}
	//inicializovat hlasovani - odhad casu
	final long requestTime = clock.now();
	inFlight = new VoteRound(train, request.from(), voters, requestTime, System.nanoTime());
	// The accumulation itself is DispatchTimeline (#28); this loop is now only the
	// broadcast. Same values, same order, one per path member.
	final List<Long> expected = DispatchTimeline.accumulate(voters, roadDelays, requestTime);
	for (int i = 0; i < voters.size(); i++) {//vsem na ceste
	    emit(new VoteRequest(train, expected.get(i)), voters.get(i));
	}
    }

    /**
     * Records one arriving vote and, if it was the last one, closes the election.
     *
     * <p>This is {@code VoteCollecting.vote} with its two statements kept and its thread
     * removed. The {@code countDown()} that released a blocked handler becomes
     * {@link VoteRound#record} returning {@code true}, and the continuation that used to run on
     * the blocked thread runs here instead, on the host agent's.
     *
     * <p><b>DEF-09, pinned.</b> {@code VoteCollecting.java:53-54} was
     * {@code assert getTrainCountDowns().containsKey(train);} followed by
     * {@code getTrainCountDowns().get(train).countDown();} — an assert and then an unguarded
     * dereference, so a vote for a train whose round has already been removed aborts the handler
     * either way ({@code AssertionError} with {@code -ea}, {@code NullPointerException}
     * without). Both lines are reproduced below and neither is guarded. It is class (c), "do not
     * file, do not fix" ({@code defect-triage.md} §6.1), and under JADE it is <b>structurally
     * unreachable on a healthy run</b> anyway: it needs a duplicate or late unicast delivery,
     * and JADE does not duplicate a unicast {@code ACLMessage}. Note this port adds no new route
     * to it — the watchdog deliberately does not clear a wedged round, which would have created
     * one.
     *
     * <p><b>What the shape becomes, for #41 rather than for here.</b> On Cybele the fault killed
     * one handler invocation and the agent lived on (which is one of the two documented ways to
     * reach DEF-22). Under JADE an exception escaping a behaviour reaches
     * {@code Agent.run()}'s {@code catch (Throwable)}, which terminates <b>the whole
     * {@code Main} agent</b>. Strictly worse, both void a recording, and #39 must not be told
     * they are one shape — the same divergence {@code RoadAgent} records for DEF-06.
     *
     * @param cast the {@code VOTE} content: voter, train, delay difference
     */
    void vote(Vote cast) {
	final VoteRound round = roundFor(cast.train());
	assert round != null;
	if (round.record(cast.voter(), cast.diff())) {
	    close(round);
	}
    }

    /**
     * The round a vote belongs to, or {@code null} if there is none — the 2008
     * {@code trainCountDowns.get(train)}, with the same {@code null} for the same reason.
     *
     * @param train the train a vote names
     * @return the in-flight round for that train, or {@code null}
     */
    private VoteRound roundFor(String train) {
	if (inFlight != null && inFlight.train().equals(train)) {
	    return inFlight;
	}
	return null;
    }

    /**
     * The envelope method — {@code obalkova metoda} — and the departure it schedules.
     *
     * <p>Statement for statement this is {@code Planning.java:89-113} with the {@code await()}
     * removed from the top and the pause bracket removed from the bottom: clear the round
     * ({@code :89}), take the ballots ({@code :92}), assert the count ({@code :96}), take the
     * maximum ({@code :97}), re-accumulate from the agreed departure ({@code :100-105}),
     * broadcast {@code VOTE_RESULT} to every path member, enqueue the plan and arm the wake-up
     * ({@code :110-111}).
     *
     * <p>Clearing {@link #inFlight} first is 2008's order and it also opens the
     * {@code PLAN_TRAIN} gate — but not until this method returns, because the {@link Inbox}
     * only re-reads {@link #template()} on its next {@code action()}. So the next election
     * cannot start before this one has booked its slots, which is the property the blocked
     * handler gave for free.
     *
     * @param round the closed round
     */
    @SuppressWarnings("boxing")
    private void close(VoteRound round) {
	inFlight = null;
	final List<Long> v = round.ballots();
	final List<String> voters = round.path();
	// obalkova metoda
	assert v.size() == voters.size();
	final long timeDiff = VoteEnvelope.max(v);
	final long departure = round.requestTime() + timeDiff;

	final List<Long> planned = DispatchTimeline.accumulate(voters, roadDelays, departure);
	for (int i = 0; i < voters.size(); i++) {//vsem na ceste
	    emit(new VoteResult(round.train(), planned.get(i)), voters.get(i));
	}

	queue.add(new TrainPlan(round.train(), round.from(), departure));
	clock.scheduleAt(departure, this::placeTrainIntoFirstStation);
    }

    /**
     * tell to train start command
     *
     * <p><b>DEF-17, pinned:</b> this polls the <em>head</em> of the departure queue, not the plan
     * whose wake-up fired. On a departure tie, or on two wake-ups drained in one tick, one
     * expiry consumes whatever is earliest. {@code defect-triage.md} calls it latent and
     * unobservable from outside — "nothing in stdout distinguishes the two cases" — and #21's
     * normalizer canonicalises equal-departure groups anyway. Polling the firing plan instead
     * would be a repair with no evidence behind it.
     *
     * <p>The 2008 {@code assert clockTime >= departure} is kept, with {@code ev.getClockTime()}
     * replaced by {@link AgentClock#now()}. It holds a fortiori: a ticker-drained wake-up fires
     * at the first tick at or <em>after</em> its deadline, never before it.
     *
     * <p>{@code System.out.println(poll)} is <b>trace-visible</b> — {@link TrainPlan#toString()}
     * is the departure line every golden carries — and the {@code START} that follows it is
     * DEF-02's site; see the class comment for why JADE cannot reproduce that drop.
     */
    void placeTrainIntoFirstStation() {
	final TrainPlan poll = queue.poll();
	final long clockTime = clock.now();
	final long departure = poll.getDeparture();
	assert clockTime >= departure;
	System.out.println(poll);
	emit(new StartCommand(poll.getStation()), poll.getTrain());//BUG ne vzdy se doruci
    }

    /**
     * DEF-22's branch: report an election that has not completed, once, and leave it alone.
     *
     * <p>Takes the instant as an argument so a test need not wait a minute for it.
     *
     * @param nowNanos a {@code System.nanoTime()} reading
     */
    void reportOverdue(long nowNanos) {
	final VoteRound round = inFlight;
	if (round == null || round.isReported()) {
	    return;
	}
	final long waitedMs = (nowNanos - round.openedAtNanos()) / 1000000L;
	if (waitedMs < VOTE_WATCHDOG_MS) {
	    return;
	}
	round.markReported();
	System.err.println("Planning " + name() + ": election for train=" + round.train()
		+ " still open after " + waitedMs + " ms -- " + round.outstanding() + " of "
		+ round.path().size() + " vote(s) outstanding, silent voters "
		+ round.missingResponders() + ". DEF-22: the round is NOT closed, no envelope is"
		+ " synthesised from the " + round.ballots().size() + " vote(s) received, and no"
		+ " further train is planned. The train stays dead, deliberately.");
    }

    /**
     * @return the election in flight, or {@code null}. For tests and for #38's assertions —
     *         a wedged planner is a non-{@code null} round that never clears, which is
     *         checkable where a Cybele hang was not.
     */
    VoteRound electionInFlight() {
	return inFlight;
    }

    /** @return how many planned departures are waiting for their wake-up */
    int plannedCount() {
	return queue.size();
    }

    /**
     * @return this activity's {@link AgentClock} — the departure timer, and the seam a test uses
     *         to drain it without a container
     */
    AgentClock agentClock() {
	return clock;
    }

    /**
     * @return the {@link ClockTickerBehaviour} period in real ms, derived from the shared
     *         clock's pace by {@link RoadAgent#granularityMsFor(double)}. The three tickers in
     *         this application must agree on one budget; this is that seam, not a second
     *         constant.
     */
    long tickPeriodMs() {
	return tickPeriodMs;
    }

    /**
     * The identity this activity writes into trace fields 4 and 5 — the host agent's local name,
     * which is {@code Main}. Overridable so a test can name the activity without a container.
     *
     * @return {@code Main}
     */
    protected String name() {
	return host.getLocalName();
    }

    /**
     * The single outbound seam. {@code Activity.sendAll(CHANNEL+name, payload)} becomes one
     * AID-addressed {@code ACLMessage}; overridable so a test can capture what was sent without
     * a message-transport service.
     *
     * @param message the payload record
     * @param receiver the addressed agent's local name — the 2008 channel-name suffix
     */
    protected void emit(RailwayMessage message, String receiver) {
	host.send(Messages.build(message, name(), receiver));
    }
}
