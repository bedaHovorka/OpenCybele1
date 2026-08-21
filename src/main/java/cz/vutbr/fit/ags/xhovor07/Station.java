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
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import cz.vutbr.fit.ags.railway.domain.StationQueue;
import cz.vutbr.fit.ags.railway.domain.StationSchedule;
import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.EnterReply;
import cz.vutbr.fit.ags.railway.domain.msg.EnterRequest;
import cz.vutbr.fit.ags.railway.domain.msg.LeaveNotice;
import cz.vutbr.fit.ags.railway.domain.msg.Party;
import cz.vutbr.fit.ags.railway.domain.msg.PathFindReply;
import cz.vutbr.fit.ags.railway.domain.msg.PathFindRequest;
import cz.vutbr.fit.ags.railway.domain.msg.RailwayMessage;
import cz.vutbr.fit.ags.railway.domain.msg.StationInfo;
import cz.vutbr.fit.ags.railway.domain.msg.Vote;
import cz.vutbr.fit.ags.railway.domain.msg.VoteRequest;
import cz.vutbr.fit.ags.railway.domain.msg.VoteResult;
import cz.vutbr.fit.ags.railway.jade.Messages;
import cz.vutbr.fit.ags.railway.jade.Templates;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.behaviours.TickerBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

/**
 * Agent represents station — <b>ported to JADE by #30</b>.
 * <p>
 * {@link #computeDifference(String, long)} schedules against a <em>voting window</em>
 * ({@code sim.station.voteWindowMs}). Before #18 that window was
 * {@code Generator.LAMBDA} — the very same constant as the generator's mean
 * inter-arrival time — so shortening the arrival rate for a test silently rewrote
 * this station's scheduling policy as well. The two are now independent parameters
 * with the same default (8500 ms). See {@code docs/scenario-config.md}.
 *
 * <p>
 * Since #28 the timetable and the voting rule live in {@link StationSchedule} and the
 * waiting queue in {@link StationQueue}, both framework-free; this agent is the framework
 * glue around them — messages, the {@code STATION_INFO} the GUI hub observes, and the lazy
 * {@code PATH_FIND} round trip.
 *
 * <h2>The blocking {@code wait()} is gone, and what replaced it</h2>
 * The 2008 agent resolved the onward direction inside its own message handler:
 * <pre>
 * Activity.sendAll(RailwayMainAgent.PATH_FIND, new Serializable[]{getName(), target});
 * wait();                       // blocks the station's own handler thread
 * dir = pathDirs.get(target);
 * </pre>
 * and a second Cybele activity ({@code PathFinding}, ACT-04) existed for the sole purpose of
 * owning a second thread that could {@code notify()} it. {@code PathFinding.java:20} says so
 * itself: <em>"Solution of problem with {@code Activity.sendAllBlock}"</em>. Both the wait and
 * that activity are <b>deleted</b> here. A JADE agent runs all of its behaviours on one thread,
 * so a blocking {@code action()} would stall the whole scheduler — not one channel, every
 * channel <em>and</em> every behaviour.
 *
 * <p>
 * <b>What the workaround cost the original, for #41's comparison log.</b> One extra activity class
 * (49 lines) instantiated <em>eight times</em>, once per station — ACT-04 in
 * {@code docs/INVENTORY.md} §3, which counts 11 explicit activities at steady state and calls ACT-03
 * and ACT-04 "pure workarounds ... Both disappear in a framework with non-blocking continuations".
 * With it came a cross-thread monitor protocol between the station and its own activity, and the
 * only two defects at this site: DEF-01 and DEF-23 exist <em>because</em> of the borrowed thread,
 * not despite it. The port replaces all of that with one handler method and one map. The register
 * of what the kernel gave back for the price is {@code docs/CYBELLE_TO_JADE.md}; this is one entry
 * in it.
 *
 * <p>
 * In its place: {@link #resolveDirection(String, Consumer)}, an explicit continuation queue.
 * On a cache hit it calls the continuation <b>synchronously, in-line</b>, so the hit path — which
 * is every path once the cache is warm — emits exactly the messages the 2008 handler emitted, in
 * exactly its order. On a miss it parks the continuation in {@link #pending}, sends
 * {@code PATH_FIND}, and returns; {@link #pathFindReply} resumes it when the answer arrives.
 *
 * <p>
 * <b>Misses coalesce per target.</b> The 2008 station could only ever have one request in flight,
 * because the blocked handler stopped it from starting a second one (INVENTORY SEM-04). This one
 * can be handling a second {@code ENTER} while the first is still pending, so a second miss on the
 * <em>same</em> target must attach to the request already in flight rather than send another. It
 * is not tidiness: {@code PATH_FIND}/{@code PATH_FIND_REPLY} are traced lines, the golden pins the
 * multiset of lines in a burst ({@code docs/trace-normalizer.md} §3), and a duplicate request would
 * be a line the baseline never wrote.
 *
 * <h2>Is the changed interleaving observable? No, and here is the argument</h2>
 * A miss window is now <em>permeable</em>: other {@code ENTER}/{@code LEAVE}/vote traffic on this
 * station is served while a continuation is parked, where Cybele queued it behind the blocked
 * handler (SEM-04). Three things bound what that can do to a trace.
 * <ol>
 *   <li><b>The multiset of lines does not change.</b> Misses coalesce per target, so the run still
 *       emits one {@code PATH_FIND}/{@code PATH_FIND_REPLY} pair per (station, target) — 21 pairs
 *       in the traced 66-train run {@code docs/trace-format.md} measured.</li>
 *   <li><b>The one payload field that <em>does</em> move is erased by the normalizer.</b> The work
 *       deferred past a suspension is {@code sendEnterReply} and {@code sendInfo}. Neither touches
 *       the timetable — the one place where deferred work did is {@link #leave}, and that is what
 *       the hoist above removes — so no vote or booking can see a state 2008 would not have shown
 *       it. {@code sendInfo}, however, reads the {@code occupied} <em>field</em> at resume time,
 *       and a handler running inside the now-permeable window can move it: at capacity 2 with a
 *       cold target, 2008 emits {@code occupied=}{1, 2} where this agent emits {2, 2}. That is a
 *       payload-multiset difference and it is real. It survives nothing: {@code occupied} is one
 *       of {@code docs/trace-normalizer.md} §2.1's eight value projections and is <b>erased</b>,
 *       for DEF-13's reasons, which were measured on the baseline racing against itself. This is
 *       the same projection the DEF-13 bullet below invokes, load-bearing twice.</li>
 *   <li><b>What is left is line order inside one burst, which the golden does not pin.</b> A
 *       {@code PATH_FIND} round trip is one local message pair; the normalizer segments at gaps
 *       over 220 simulated ms and sorts within a segment, and {@code docs/trace-normalizer.md} §3
 *       states the trade in as many words: "Within one burst the golden pins <em>which lines
 *       occurred</em>, not <em>in what order</em>."</li>
 * </ol>
 * <b>Point 3 rests on an unmeasured assumption, and it is named here rather than buried.</b> "One
 * local message pair" is imported from a Cybele measurement; the simulated duration of a JADE
 * {@code PATH_FIND} round trip has not been measured, and the whole of point 3 is void if it can
 * approach 220 ms. #36's first gate run is where that number comes from.
 *
 * <p>
 * The residual, stated rather than hidden: if a miss window straddled a burst boundary the two
 * implementations would order those lines differently and the projection would not carry it. It is
 * not only the {@code PATH_FIND} pair that would move — the window can flip the order in which two
 * <em>different</em> trains get their {@code ENTER_REPLY}, and their {@code LEAVE},
 * {@code TRAVEL_START} and position in a {@code RoadAgent} queue are all causally downstream of
 * that. That cannot be settled from here — it is #36's first gate run and #39's to triage, under
 * the 1-POST rule, and it is a <em>normalizer-gap-or-port-bug</em> question, never accepted drift.
 *
 * <h2>One semantic delta that runs the other way: SEM-06 at the boot edge</h2>
 * Cybele <b>silently drops</b> a {@code sendAll} to a channel nobody has opened yet (SEM-06) — the
 * mechanism behind the author's own {@code //BUG ne vzdy se doruci} and DEF-02. JADE does not: a
 * message arriving before {@code setup()} registers {@link Inbox} waits in the agent's queue and is
 * delivered when the behaviour first runs. So this port is <em>less</em> lossy than the baseline at
 * the boot edge. That is a behaviour difference, it is favourable, and it is inside DEF-02's
 * class-(c) carve-out ({@code docs/defect-triage.md} §3.3: a dropped train is absent, not
 * reordered, and #39 is told not to file it). Recorded because every other 2008→JADE delta at this
 * site is recorded.
 *
 * <h2>The defects at this site, decided rather than inherited</h2>
 * <ul>
 *   <li><b>DEF-01</b> (spurious/mis-targeted wakeup ⇒ {@code null} direction ⇒ a train that dies
 *       mid-route) — class (c), never observed, "do not file"
 *       ({@code docs/defect-triage.md} §3.3). The continuation has no monitor to be woken
 *       spuriously from, and it resumes only on a reply <em>for its own target</em>, so the branch
 *       is structurally unreachable — <em>provided</em> a {@code null} direction can never reach a
 *       continuation. It can be represented: {@link PathFindReply} does not reject one,
 *       {@code Payloads} round-trips {@code null} slots deliberately, and the baseline guards the
 *       value only with {@code assert direction != null} ({@code RailwayMainAgent.java:136}), which
 *       is inert with assertions off. {@link #pathFindReply} therefore <b>refuses</b> such a reply
 *       outright: it is not cached and it resumes nothing. That closes the premise, and it also
 *       keeps {@link #pathDirs} free of {@code null} values — without which
 *       {@link #resolveDirection} would read a cached {@code null} as a miss and re-send
 *       {@code PATH_FIND} for that target forever, which is the duplicate trace line the
 *       "multiset unchanged" argument above forbids. Nothing is lost: no golden records DEF-01, and
 *       §6.1's note withdraws the requirement that a port reproduce a class-(c) hang observably.
 *       The {@code notify()}-vs-{@code notifyAll()} half of the row is vacuous either way — a
 *       station dispatches serially, so there was never more than one waiter on the monitor.</li>
 *   <li><b>DEF-23</b> (the reply never comes ⇒ the station stalls forever) — class (c), PIN. The
 *       <em>untimed</em> half is reproduced exactly: there is no timeout and no fall-through, so a
 *       lost {@code PATH_FIND_REPLY} leaves that continuation parked forever and the train it
 *       belongs to is never admitted. Adding a timeout that resumed with a {@code null} direction
 *       would <em>manufacture</em> DEF-01 out of DEF-23, which §6.1 calls "a loud baseline hang
 *       turned into quiet wrong data". The <em>starvation</em> half — every other {@code ENTER},
 *       {@code LEAVE} and vote on the station starving behind the blocked handler (SEM-04) — is
 *       <b>not</b> reproduced, and cannot be: it is a property of Cybele's serial per-activity
 *       dispatch, not of this application. §6.1 asks the port side of this family for a branch that
 *       is "explicit <em>and logged</em>", so {@link Watchdog} says so out loud: a target parked
 *       past {@link #PATH_FIND_WATCHDOG_MS} is reported on stderr — where #12's {@code ErrorScanner}
 *       already reads — once, and <b>still not resumed</b>. Logging is not resuming; the pin is
 *       untouched. {@link #pendingTargets()} is the passive half, for #36/#38 to assert on.</li>
 *   <li><b>No wait-loop / no predicate re-check</b> — turned into a predicate by construction. A
 *       reply is matched to the continuations parked on <em>its</em> target; a reply nobody is
 *       waiting for updates the cache and resumes nothing.</li>
 *   <li><b>{@code leave} resolved the direction before {@code removeTrain}</b> — the same family.
 *       Deterministic, and <em>unobservable in the baseline</em>, because SEM-04 makes the window
 *       impermeable: nothing else on that station can run between the two. Under a continuation
 *       the window becomes permeable, and a {@code VOTE_REQUEST} landing inside it would vote
 *       against a timetable that still holds the departed train. {@link #leave} therefore
 *       <b>hoists</b> {@code schedule.removeTrain(train)} above the resolution. That reordering is
 *       what preserves the behaviour; transliterating the source order would have changed it.</li>
 *   <li><b>DEF-13</b> (the aliased, still-mutating {@code Info} payload) — class (b), projected
 *       ({@code docs/defect-triage.md} §3.2). {@link #sendInfo} now ships an immutable
 *       {@link StationInfo} record, i.e. snapshot semantics, which that row explicitly places
 *       <em>inside</em> the contract.</li>
 * </ul>
 *
 * <h2>Why there is no {@code synchronized} left</h2>
 * The 2008 handlers were {@code synchronized} and {@code PathFinding} took the station's monitor,
 * for a real reason: ACT-04 was a <em>second thread</em> touching {@code pathDirs}. This ticket
 * deletes that thread. Every field below is now touched only from the behaviours of this agent,
 * and JADE runs one agent on one thread. The monitor is redundant <em>here</em> — which is the
 * per-agent argument #4 asks each ticket to make for itself, not a licence for the other four.
 *
 * <h2>What still runs on Cybele</h2>
 * Nothing in this file does, and the application does not run until #32/#33 land — see #4. The two
 * vestigial members kept below, {@link #PATH_FIND_REPLY} and {@link Info}, exist only so the
 * not-yet-ported {@code RailwayMainAgent}, {@code RailwayCanvas} and {@code TraceProbe} still
 * compile. This comment said "#34's to delete"; #34 landed first and could not, because all three
 * of those namers are #33's. <b>They are #33's to delete</b>, together with {@code RoadAgent.State}.
 *
 * @author Bedrich Hovorka
 *
 */
public class Station extends Agent {
    private static final long serialVersionUID = 1L;
    /**
     * channel for sending path find result
     * <p>
     * <b>This agent no longer uses it.</b> JADE routes {@code PATH_FIND_REPLY} by the station's
     * AID plus the {@code railway.PATH_FIND_REPLY} ontology slot
     * ({@code docs/message-ontology.md} §6). The constant stays for two reasons: the
     * not-yet-ported {@code RailwayMainAgent} and {@code TraceProbe} still name the channel, and
     * #27's {@code ChannelTableTest.cybele_channel_names_are_reproduced_verbatim} compares
     * {@code Channel.PATH_FIND_REPLY} against exactly this literal. The first reason expires with
     * <b>#33</b>, not #34 — {@code RailwayMainAgent} is #33's agent — and the second does not expire
     * at all unless that assertion is re-pointed at the literal string, which is what it should do,
     * since the test's subject is {@code Channel} and not this class. #34 left it alone: it kept
     * {@code Planning.PLAN_TRAIN} and {@code Planning.VOTE} for exactly the same reason, so
     * re-pointing one of the fifteen rows and not the other two would make the test less uniform,
     * not more.
     */
    public static final String PATH_FIND_REPLY = "PATH_FIND_REPLY.";
    /**
     * How long a {@code PATH_FIND} may go unanswered before {@link Watchdog} says so, in wall-clock
     * milliseconds, and also the watchdog's tick period.
     * <p>
     * Wall clock rather than simulated time on purpose: this station reads no clock at all — its
     * two time-taking methods take the instant from the message — so wiring #29's {@code SimClock}
     * in just to run a watchdog would give the agent a clock dependency the 2008 one did not have.
     * The bound is deliberately far larger than any real round trip: it is a "this will never
     * happen" alarm, not a timeout, and it must never fire on a healthy run.
     */
    static final long PATH_FIND_WATCHDOG_MS = 60_000L;
    private Collection<String> roads;//trate vychazejici ze stacice
    private Map<String, String> pathDirs = new HashMap<String, String>();//prubezne vytvarene znalosti o siti <stanice, jakou trati>
    /**
     * Continuations parked on a {@code PATH_FIND} that has not been answered yet, keyed by the
     * target station. Insertion-ordered so that two trains waiting on the same answer resume in
     * arrival order. The key set is exactly the set of requests in flight, which is what makes
     * "one request per target" checkable rather than hoped for.
     * <p>
     * Left non-{@code transient} on a {@code Serializable} agent knowingly. The values hold lambdas
     * that capture this {@code Station}, so serializing one would drag the agent — but the agent is
     * what is being serialized in the only case that could arise (JADE mobility or persistence),
     * and this port uses neither. Making the field {@code transient} would be worse: it is
     * {@code final} and initialized inline, so it would deserialize to {@code null} and turn a
     * situation that cannot occur into an NPE that can.
     */
    private final Map<String, Parked> pending = new LinkedHashMap<String, Parked>();
    private final StationQueue queue = new StationQueue();
    private final long voteWindow = ScenarioConfig.get().getStationVoteWindowMs();
    private StationSchedule schedule;
    private int capacity;
    private int occupied;

    /**
     * Information about state
     * <p>
     * <b>Vestigial</b>, and the reason is worth keeping: this class <em>was</em> the payload of
     * CH-10, shipped by reference under {@code Local;NoSerialization} and mutated after the send
     * — INVENTORY SEM-05, DEF-13. The JADE port sends an immutable {@link StationInfo} record
     * instead, which is the snapshot semantics {@code docs/defect-triage.md} §3.2 places inside
     * the contract. The type survives only because {@code RailwayMainAgent.stationInfos},
     * {@code RailwayCanvas.paintStation} and {@code TraceProbe.onStationInfo} still name it.
     * Delete with #33, which ports all three of those.
     */
    public class Info implements Serializable {
	private static final long serialVersionUID = 1L;
	int capacity;
	int occupied;
	private Info(int occupied, int capacity) {
	    super();
	    this.capacity = capacity;
	    this.occupied = occupied;
	}
    }

    /**
     * The continuations waiting on one target's {@code PATH_FIND}, and what {@link Watchdog} needs
     * to notice that the answer is not coming.
     * <p>
     * {@link #reported} makes the alarm fire <b>once</b> per target rather than on every tick: the
     * watchdog's whole point is that #12's {@code ErrorScanner} reads stderr, and an unbounded spew
     * would drown the scanner rather than inform it.
     */
    private static final class Parked {
	private final List<Consumer<String>> continuations = new ArrayList<Consumer<String>>();
	private final long sinceNanos;
	private boolean reported;

	Parked(long sinceNanos) {
	    this.sinceNanos = sinceNanos;
	}
    }

    /**
     * Reads the two construction arguments {@code RailwayMainAgent} used to pass to the
     * constructor — capacity and the tracks leaving this station — registers the inbound and
     * drain behaviours, and pushes the opening {@code STATION_INFO}.
     * <p>
     * The opening push is the last statement, as it was the last statement of the 2008
     * constructor: it is the first line this station contributes to a trace.
     */
    @Override
    @SuppressWarnings("unchecked")
    protected void setup() {
	final Object[] args = getArguments();
	if (args == null || args.length != 2) {
	    throw new IllegalArgumentException(
		    "Station " + getLocalName() + " needs {Integer capacity, Collection<String> roads}");
	}
	this.capacity = ((Number) args[0]).intValue();
	this.roads = (Collection<String>) args[1];
	this.schedule = new StationSchedule(capacity, voteWindow);
	addBehaviour(new Inbox());
	addBehaviour(new Drain());
	addBehaviour(new Watchdog());
	sendInfo();
    }

    /**
     * The one inbound behaviour. All five of this agent's channels share it, because a JADE agent
     * has one message queue and five competing {@code receive}/{@code block()} loops over
     * overlapping templates is the classic way to lose a message to it
     * ({@code docs/message-ontology.md} §7).
     */
    final class Inbox extends CyclicBehaviour {
	private static final long serialVersionUID = 1L;
	private final MessageTemplate template = Templates.inbound(Party.STATION);

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
     * The drain {@code docs/message-ontology.md} §7 asks every ported agent to register: the exact
     * complement of {@link Inbox}'s template. With a correct port it never fires. When it does it
     * names the bug on the spot rather than presenting it as a hang.
     * <p>
     * <b>Note for #36:</b> {@code not(inbound)} is the complement of <em>everything</em>, not just
     * of the railway ontology, so in a live container it also matches AMS and DF traffic. A station
     * registers with neither today, so nothing arrives; if a later ticket gives one an AMS
     * subscription or a DF registration, this template has to be narrowed first or the drain will
     * report platform housekeeping as a port bug.
     */
    final class Drain extends CyclicBehaviour {
	private static final long serialVersionUID = 1L;
	private final MessageTemplate template = Templates.unexpected(Party.STATION);

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
     * DEF-23's loud half. {@code docs/defect-triage.md} §6.1 sets the port-side criterion for this
     * family of unbounded waits: the branch must be "explicit <em>and logged</em>, never a silent
     * fall-through that proceeds as if the votes arrived".
     * <p>
     * This satisfies both halves and confuses neither. It <b>reports</b> a {@code PATH_FIND} that
     * has gone unanswered for {@link #PATH_FIND_WATCHDOG_MS}, and it <b>does not resume</b> the
     * continuation — resuming with a made-up direction is exactly the "quiet wrong data" §6.1
     * forbids, and would manufacture DEF-01 out of DEF-23. So the pin is untouched: a lost reply
     * still parks that train forever. It simply stops doing so in silence.
     */
    final class Watchdog extends TickerBehaviour {
	private static final long serialVersionUID = 1L;

	Watchdog() {
	    super(null, PATH_FIND_WATCHDOG_MS);
	}

	@Override
	protected void onTick() {
	    reportOverdue(System.nanoTime());
	}
    }

    /**
     * Report every target parked past {@link #PATH_FIND_WATCHDOG_MS}, once each. Resumes nothing.
     * <p>
     * Takes {@code now} as a parameter rather than reading {@link System#nanoTime()} itself, so a
     * test can reach the overdue branch without waiting a minute for it.
     *
     * @param nowNanos the current {@link System#nanoTime()} reading
     */
    void reportOverdue(long nowNanos) {
	for (Map.Entry<String, Parked> entry : pending.entrySet()) {
	    final Parked parked = entry.getValue();
	    if (parked.reported) {
		continue;
	    }
	    final long waitedMs = (nowNanos - parked.sinceNanos) / 1000000L;
	    if (waitedMs < PATH_FIND_WATCHDOG_MS) {
		continue;
	    }
	    parked.reported = true;
	    System.err.println("Station " + name() + ": PATH_FIND for target=" + entry.getKey()
		    + " unanswered after " + waitedMs + " ms, " + parked.continuations.size()
		    + " train(s) parked. DEF-23: not resumed, deliberately.");
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
     * @param acl a message matching {@link Templates#inbound(Party)} for {@link Party#STATION}
     */
    void dispatch(ACLMessage acl) {
	final Channel channel = Messages.channelOf(acl);
	final RailwayMessage message = Messages.contentOf(acl);
	switch (channel) {
	    case ENTER -> enter((EnterRequest) message);
	    case LEAVE -> leave((LeaveNotice) message);
	    case VOTE_REQUEST -> voteRequest((VoteRequest) message);
	    case VOTE_RESULT -> voteResult((VoteResult) message);
	    case PATH_FIND_REPLY -> pathFindReply((PathFindReply) message);
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
	System.err.println("Station " + name() + ": unexpected message, ontology=" + acl.getOntology()
		+ " performative=" + ACLMessage.getPerformative(acl.getPerformative())
		+ " from=" + Messages.senderName(acl));
    }

    private void sendInfo() {
	emit(new StationInfo(occupied, capacity), RailwayMainAgent.MAIN_AGENT_NAME);
    }

    /**
     * getter
     * @return roads which is immediatly connected with station
     */
    public Collection<String> getRoads() {
        return roads;
    }

    /**
     * getter
     * @return path dir
     */
    public Map<String, String> getPathDirs() {
        return pathDirs;
    }

    /**
     * The targets this station has an unanswered {@code PATH_FIND} out for.
     * <p>
     * DEF-23's observable, made checkable. A baseline station that lost a reply simply stopped
     * emitting; this one keeps working and leaves the evidence here.
     *
     * @return the parked targets, in request order
     */
    Collection<String> pendingTargets() {
	return new ArrayList<String>(pending.keySet());
    }

    /**
     * Queueing system operation enter. Admit the train if there is room, otherwise queue it.
     *
     * @param request the train's request
     */
    void enter(EnterRequest request) {
	final String train = request.train();
	if (occupied == capacity) {
	    queue.offer(train, request.endStation());
	    sendInfo();
	} else {
	    occupied++;
	    resolveDirection(request.endStation(), dir -> {
		sendEnterReply(train, dir);
		sendInfo();
	    });
	}
    }

    /**
     * Queueing system operation leave. Release the slot, or hand it straight to the
     * longest-waiting train.
     * <p>
     * {@code schedule.removeTrain(train)} is <b>hoisted</b> above the direction resolution; see
     * the class comment. Emission order on the cache-hit path is unchanged from 2008:
     * {@code ENTER_REPLY} then {@code STATION_INFO}.
     *
     * @param notice the departing train
     */
    void leave(LeaveNotice notice) {
	final String train = notice.train();
	StationQueue.Waiting admitted = null;
	if (queue.size() == 0) {
	    occupied--;
	} else {
	    admitted = queue.poll();
	}
	schedule.removeTrain(train);
	if (admitted == null) {
	    sendInfo();
	    return;
	}
	final StationQueue.Waiting waiting = admitted;
	resolveDirection(waiting.getEndStation(), dir -> {
	    sendEnterReply(waiting.getTrain(), dir);
	    sendInfo();
	});
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
     * The answer to a {@code PATH_FIND}. Caches it, then resumes every continuation parked on
     * <em>this</em> target.
     * <p>
     * A reply for a target nobody is waiting on is not an error and is not a wakeup: the cache
     * takes it and nothing resumes. That is the predicate re-check the 2008 {@code wait()} did not
     * have (DEF-01).
     * <p>
     * <b>A {@code null} direction is refused, not cached.</b> {@code RailwayMainAgent.pathFind}
     * guards its answer with {@code assert direction != null}, which is inert with assertions off,
     * and nothing between there and here rejects one. Accepting it would do two things, the second
     * worse than the first: hand the parked train a {@code null} {@code next=} — DEF-01's exact
     * symptom, a train that reads "arrived" and dies mid-route — and leave {@code target -> null}
     * in {@link #pathDirs}, which {@link #resolveDirection} reads as a <em>miss</em>, so every later
     * train to that target would send a fresh {@code PATH_FIND}, permanently. That second one
     * breaks the "one request per (station, target)" invariant the whole line-multiset argument
     * rests on. Refusing keeps both closed; the train stays parked, which is DEF-23's shape and is
     * the shape this site is pinned to.
     * <p>
     * <b>One continuation's failure does not take its siblings down.</b> {@link #pending} is
     * cleared before the loop — that is what makes a duplicate reply, an unmatched reply and a
     * re-entrant {@link #resolveDirection} all safe — but it also means an exception escaping the
     * loop would strand the untouched entries with their evidence already deleted. It is reachable
     * by construction: {@link #emit} goes through {@code Messages.build}, which throws
     * {@code UncheckedIOException} by design. So each resume is guarded and the loop continues.
     *
     * @param reply the target and the track to leave by
     */
    void pathFindReply(PathFindReply reply) {
	if (reply.direction() == null) {
	    System.err.println("Station " + name() + ": refusing PATH_FIND_REPLY with a null direction"
		    + " for target=" + reply.target() + " -- not cached, nothing resumed"
		    + " (DEF-01 stays unreachable; " + parkedCount(reply.target())
		    + " train(s) stay parked)");
	    return;
	}
	pathDirs.put(reply.target(), reply.direction());
	final Parked parked = pending.remove(reply.target());
	if (parked == null) {
	    return;
	}
	for (Consumer<String> resume : parked.continuations) {
	    try {
		resume.accept(reply.direction());
	    } catch (RuntimeException e) {
		System.err.println("Station " + name() + ": a continuation for target="
			+ reply.target() + " threw; the remaining ones are still resumed");
		e.printStackTrace();
	    }
	}
    }

    private int parkedCount(String target) {
	final Parked parked = pending.get(target);
	return parked == null ? 0 : parked.continuations.size();
    }

    /**
     * Resolve the track to leave by for a train headed to {@code target}, and run {@code resume}
     * with it.
     * <p>
     * <b>Synchronously on a cache hit</b> — the common path, and the one that has to stay
     * byte-identical to 2008. On a miss the continuation is parked and {@code PATH_FIND} is sent;
     * a second miss on a target already in flight is parked behind the first and sends nothing.
     *
     * @param target the train's final destination
     * @param resume what to do once the direction is known; receives {@code null} when the train
     *     is already at its destination, exactly as {@code getPathDirection} returned {@code null}
     */
    private void resolveDirection(String target, Consumer<String> resume) {
	if (target.equals(name())) {
	    resume.accept(null);
	    return;
	}
	final String dir = pathDirs.get(target);
	if (dir != null) {
	    resume.accept(dir);
	    return;
	}
	final Parked parked = pending.get(target);
	if (parked != null) {
	    parked.continuations.add(resume);
	    return;
	}
	final Parked fresh = new Parked(System.nanoTime());
	fresh.continuations.add(resume);
	pending.put(target, fresh);
	emit(new PathFindRequest(name(), target), RailwayMainAgent.MAIN_AGENT_NAME);
    }

    /**
     * Enter reply - accepting train
     *
     * @param train the admitted train
     * @param nextPosition the track it leaves by, or {@code null} when it has arrived
     */
    private void sendEnterReply(String train, String nextPosition) {
	emit(new EnterReply(name(), nextPosition), train);
    }

    /**
     * find free in plan and compute difference between expected time and founded free in plan
     *
     * @param train train id
     * @param time the requested arrival time
     * @return time difference
     */
    long computeDifference(String train, long time) {
	return schedule.computeDifference(train, time);
    }

    /**
     * add train to plan
     *
     * @param train train id
     * @param time agreed arrival time
     */
    void addToPlan(String train, long time) {
	schedule.addToPlan(train, time);
    }

    /**
     * This agent's local name — trace fields 4 and 5, and the {@code from} of a
     * {@code PATH_FIND}.
     * <p>
     * Overridable so the agent can be unit-tested outside a container; in the container it is
     * {@code getLocalName()}, which is what {@code RailwayObject.getName()} reconstructed from
     * the Cybele agent id (INVENTORY DEF-12 notes that reifying it is the more correct form).
     *
     * @return the station name
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
