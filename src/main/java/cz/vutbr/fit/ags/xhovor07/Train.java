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

import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.EnterReply;
import cz.vutbr.fit.ags.railway.domain.msg.EnterRequest;
import cz.vutbr.fit.ags.railway.domain.msg.LeaveNotice;
import cz.vutbr.fit.ags.railway.domain.msg.Party;
import cz.vutbr.fit.ags.railway.domain.msg.RailwayMessage;
import cz.vutbr.fit.ags.railway.domain.msg.StartCommand;
import cz.vutbr.fit.ags.railway.domain.msg.TrainState;
import cz.vutbr.fit.ags.railway.domain.msg.TravelEnd;
import cz.vutbr.fit.ags.railway.domain.msg.TravelStart;
import cz.vutbr.fit.ags.railway.jade.Messages;
import cz.vutbr.fit.ags.railway.jade.Templates;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

/**
 * Agent representing one train — <b>ported to JADE by #32</b>, the last of the five agent ports.
 * <p>
 * It is the only agent in the application with a real lifecycle: {@code Generator} creates it at
 * run time, it walks station → track → station to its destination, and then it destroys itself.
 * Everything else is created once at boot and outlives the run. {@code OpenCybeleLifecycleIT}
 * asserts both halves of that — every train dies, and nothing that is not a train ever does.
 *
 * <h2>Why this is <em>not</em> an {@code FSMBehaviour}, although #32's issue text suggests one</h2>
 * The issue draws the walk as a state machine and asks for {@code FSMBehaviour}. The shape is real
 * — the walk genuinely is a small linear automaton — but the JADE construct is the wrong container
 * for it, for four reasons, and the first is the one that would have cost a golden:
 * <ol>
 *   <li><b>An {@code FSMBehaviour} state consumes from the same one queue, through a narrower
 *       template.</b> That is exactly the shape {@code docs/message-ontology.md} §7 exists to
 *       forbid: "a message matching no <em>active</em> template sits in the queue forever and
 *       presents as a hang". A train's three inbound channels are <b>all live at all times</b> in
 *       the baseline — Cybele has one handler method per channel and dispatches whichever arrives.
 *       An FSM that only listens for {@code TRAVEL_END} while in the "crossing" state changes that
 *       from "handled" to "deferred", which is a behaviour change dressed as a refactoring.</li>
 *   <li><b>The branch that matters is on the payload, not on the state.</b> {@link #entered}
 *       decides between "walk on", "board the track" and "die" by reading {@code object} and
 *       {@code next} out of one {@code ENTER_REPLY}. In FSM terms that is a transition value
 *       returned from a state, so the states would <em>still</em> contain this switch — and the
 *       registration boilerplate would sit on top of it rather than replace it. Four states and six
 *       {@code registerTransition} calls to express a nine-line method.</li>
 *   <li><b>Nothing terminal.</b> An {@code FSMBehaviour} reaching a final state ends the
 *       <em>behaviour</em>, not the agent; the death still has to be {@link #die()}. So the one
 *       thing an FSM is naturally good at — expressing an end — is the one thing it would not
 *       express here.</li>
 *   <li><b>Consistency has a concrete price attached.</b> {@code Station} (#30), {@code RoadAgent}
 *       (#31), {@code Planning} (#34) and {@code RailwayMainAgent} (#33) all landed as one
 *       {@link Inbox} over {@code Templates.inbound(...)} plus a {@code dispatch} switch plus a
 *       {@link Drain}. #37/#38 assert against that shape, and #46 reuses the read of it for Jason.
 *       A fifth agent shaped differently would have to be argued about twice more.</li>
 * </ol>
 * So: one {@link Inbox} over {@code Templates.inbound(Party.TRAIN)}, one {@link #dispatch} switch,
 * one {@link Drain} on the exact complement. A train is in fact the agent §7's collision table
 * lists third — {@code ENTER_REPLY} and {@code TRAVEL_END} are <b>both {@code inform}</b>, so a
 * performative-only template would feed a {@code TRAVEL_END} into {@link #entered}. The
 * {@code :ontology} slot is what separates them.
 *
 * <h2>{@code leaveObject} runs on <em>entering the next</em> object — DEF-07, and where it shows</h2>
 * {@link #entered} calls {@link #leaveObject(String)} on the <b>old</b> {@code position} before
 * overwriting it, so for the width of the overlap both objects count the train. That order is
 * class (a) — deterministic, and the port must reproduce it — and it is reproduced here verbatim:
 * the {@code LEAVE} is the first statement of the handler and the assignment is the second.
 * <p>
 * <b>Where it is contractual is narrower than it looks, and #72 is the reason.</b> On an
 * uncontended hop the {@code ENTER}, the {@code ENTER_REPLY} and the {@code LEAVE} all land in one
 * burst, and {@code trace-normalizer.md} §2.3's {@code burst-order} rule sorts a burst
 * lexicographically — so the golden lists {@code ENTER} before {@code LEAVE} whatever the
 * application actually did, and {@code opencybele-lifecycle.txt} in fact carries a train's
 * destructor {@code LEAVE} <em>before</em> its hop {@code LEAVE}, the reverse of the emission
 * order. The projection carries the ordering on a <b>queued</b> hop only, where the wait pushes the
 * old object's {@code LEAVE} into a later burst than the new object's {@code ENTER}:
 * {@code opencybele-congestion.txt} lines 112 and 127, asserted by
 * {@code OpenCybeleCongestionIT.assertDef07SurvivesTheProjection}. Everywhere else what is pinned
 * is the <em>pairing</em>, which no sort can perturb, and that is what {@code TrainWalkTest}
 * asserts here.
 * <p>
 * <b>The first hop has no {@code LEAVE} at all</b>, and that is not an oversight either:
 * {@link #leaveObject(String)} is a no-op while {@code position == null}, so a train's route of
 * <i>n</i> objects produces <i>n</i> {@code LEAVE}s and not <i>n</i>+1 (measured: "vl0's route
 * gives 9 objects and 9 LEAVEs"). A checker written from the pre-#72 wording false-positives on
 * every train.
 *
 * <h2>DEF-08 is <b>not</b> a double decrement, and the port must not "fix" it</h2>
 * {@code docs/INVENTORY.md} DEF-08 claims the destructor sends a <em>second</em> {@code LEAVE} for
 * the final station. {@code docs/defect-triage.md} §4.2 <b>de-claims</b> it and supersedes that
 * entry: {@link #entered} leaves the object being <em>departed</em>, {@link #takeDown()} leaves the
 * one being <em>stood in</em>. Measured over a 66-train run: 199 {@code LEAVE} records, zero
 * {@code (train, object)} pairs with more than one. The destructor's {@code LEAVE} is what
 * <b>balances</b> the destination station's {@code occupied++} from {@code Station.enter}, so a
 * port that deleted it would leak occupancy on every single arrival — silently, and forever.
 * <p>
 * The rule this file is held to is therefore: <em>"a completed train must emit a final
 * {@code LEAVE} to its destination immediately before {@code TRAIN_STATE state=KILL}. Its absence
 * is a port bug."</em> That is {@link #takeDown()}, two statements, in that order.
 *
 * <h2>The death sequence, and which thread runs it</h2>
 * {@code Agent.die()} → Cybele's reflective destructor-by-convention {@code destroy()} becomes
 * {@link #die()} → {@code doDelete()} → {@code takeDown()}. The platform half is worth stating
 * because the <em>ordering</em> of the last two messages depends on it, and it was read off
 * {@code jade-4.3.jar} rather than assumed:
 * <ul>
 *   <li>{@code Agent.doDelete()} swaps in {@code DeletedLifeCycle} and returns. It does not run the
 *       destructor, so {@link #entered}'s remaining statements — there are none, {@link #die()} is
 *       its last — would still execute.</li>
 *   <li>{@code Agent$ActiveLifeCycle.execute()} runs <b>exactly one</b> behaviour action per call,
 *       so no further message can be dispatched between {@link #die()} and {@link #takeDown()}. The
 *       next loop iteration lands in {@code DeletedLifeCycle}, whose {@code end()} calls
 *       {@code Agent.clean(...)}.</li>
 *   <li>{@code clean} restores {@code myActiveLifeCycle}, calls {@code takeDown()}, and only
 *       <em>then</em> calls {@code myToolkit.handleEnd(myAID)}. So the two sends below are made
 *       while the agent is still registered and still has a toolkit — the trace does not lose its
 *       last two lines. That ordering is the whole reason {@link #die()} may be the last statement
 *       of {@link #entered} rather than something deferred.</li>
 *   <li>All of it runs on this agent's own thread.</li>
 * </ul>
 * {@link #die()} is a one-line seam rather than a direct {@code doDelete()} call for the same
 * reason {@code Generator.createTrain} is one: {@code doDelete()} needs a container, and the
 * arrival branch — the single most important branch in this file — would otherwise be untestable as
 * a POJO. The seam is what lets {@code TrainWalkTest} assert "the arrival case dies rather than
 * entering" directly.
 *
 * <h2>DEF-11: the {@code ==} on interned strings is <b>dropped</b>, deliberately</h2>
 * 2008 selects the sentinel with {@code (mess == KILLED)}, reference equality on a {@code String}.
 * {@code defect-triage.md} measured it statically — the only caller passing {@code KILLED} passes
 * that same interned literal, every other caller passes a computed concatenation — and rules:
 * <em>"It can never show in a diff: a port using {@code .equals} produces a byte-identical trace.
 * This row exists so the ports know the idiom is a trap, not a behaviour. #32 may drop it
 * freely."</em> So {@link #sendStatusMessage(String)} uses {@code .equals}, which is the
 * <b>behaviour</b> ported and the idiom dropped. {@code TrainStatusTest} pins the mapping in both
 * directions, including with a caller-built {@code new String("KILL")} that {@code ==} would have
 * got wrong — the assertion that fails if a later reader "restores" the idiom.
 *
 * <h2>DEF-02 (the lost {@code START}) cannot be reproduced here, and is not simulated</h2>
 * On Cybele a {@code START} could reach the channel before the constructor had subscribed, and
 * SEM-06 drops such a send silently — the train then waits forever. #34 established that JADE has
 * no analogue: an AID-addressed {@code ACLMessage} is queued by the platform whether or not a
 * behaviour is waiting for it, so the race has nowhere to happen. <b>Recorded for #41, not
 * simulated.</b> Manufacturing a drop would be inventing a defect, and {@code trace-normalizer.md}
 * §5 measures the real one at 1 run in 38 — a class-(c) baseline artefact #39 is told to re-record
 * rather than triage.
 * <p>
 * <b>What <em>is</em> reproduced is the outcome</b>: a train that is never told to start, or whose
 * election never completed, simply never proceeds. {@link Inbox} blocks on an empty queue and the
 * agent idles forever, exactly as the 2008 train sat with three open channels and nothing arriving.
 * No timeout, no fall-through, no synthesised {@code START}.
 *
 * <h2>Why there is no watchdog here, where {@code Station} and {@code Planning} both have one</h2>
 * Both of those report an unbounded wait on stderr, where #12's {@code ErrorScanner} reads, because
 * {@code defect-triage.md} §6.1 requires a port's bounded wait to have "an explicit, logged timeout
 * branch". <b>A train has no bounded wait to instrument</b> — it has no {@code wait()}, no latch
 * and no timer — and adding a "nothing has happened for a while" alarm would be actively harmful:
 * waiting a long time is <em>healthy</em> train behaviour. A train sits in a station queue behind a
 * contended track for as long as the track is busy, and every scenario ends with trains still in
 * flight at {@code sim.stop.maxClockMs}. An alarm on that would write to stderr on every healthy
 * run and void it. What replaces it is state a test can read: {@link #hasStarted()},
 * {@link #position()} and {@link #nextPosition()} let #36/#38 assert where a train stopped rather
 * than watch for a hang — the same job {@code Station.pendingTargets()} does.
 *
 * <h2>DEF-10, the per-train channel leak: no analogue, and nothing "fixed"</h2>
 * 2008 opened three channels per train ({@code START.}, {@code ENTER_REPLY.}, {@code TRAVEL_END.})
 * and a fourth in the generator, and closed none of them, ever — §6.3 decided "bound the scenario,
 * do not fix", precisely because {@code Activity.closeChannel} would introduce a <em>second</em>
 * way for a send to be dropped and so perturb DEF-02 and DEF-22. Under JADE there is nothing to
 * close: the three inbound channels are one {@code :ontology} slot each, matched by one template
 * that is per-<em>constant</em> and not per-train ({@code message-ontology.md} §6). #33 already
 * took the generator's fourth. So the leak has no analogue and nothing was fixed to remove it.
 *
 * <h2>Why no {@code synchronized} survives, argued for this agent</h2>
 * All three 2008 handlers and the destructor were {@code synchronized (this)}, and unlike a road's
 * the lock was <em>not</em> guarding against a timer thread — a train arms no timers. It excluded
 * two things, and both are gone:
 * <ol>
 *   <li><b>The three channel handlers against each other.</b> {@code defect-triage.md} §10 records
 *       that whether Cybele can run two of one agent's handlers simultaneously was never probed —
 *       "a port that serialises them would be making an unverified assumption" — so the monitor was
 *       a defensible hedge. Here the question does not arise, and the serialisation is not an
 *       assumption: the three channels share one JADE message queue, are pulled out by one
 *       {@link Inbox}, and {@code Agent$ActiveLifeCycle.execute()} runs one behaviour action per
 *       call on the agent's single thread. No two can overlap.</li>
 *   <li><b>The destructor against a handler.</b> This is the half a station and a road did not have
 *       to make, and it is the interesting one: {@code destroy()} was invoked <em>reflectively by
 *       the kernel</em>, from a thread this file never named, and INVENTORY §14 records that
 *       "the behaviour of {@code Agent.die()} with respect to the dying agent's open channels" was
 *       never probed either. Its JADE counterpart {@link #takeDown()} is called from
 *       {@code Agent.clean}, which runs inside {@code Agent.run()} — the agent's own thread again —
 *       and, as read off the bytecode above, strictly after the behaviour action that called
 *       {@link #die()} has returned.</li>
 * </ol>
 * With both parties on one thread the monitor excludes nothing that could otherwise interleave. As
 * with #30, #31 and #34, that argument is made for <b>this agent only</b>.
 *
 * <h2>What happened to {@code RailwayObject}</h2>
 * <b>Deleted by this commit</b>, as #31 and #33 said it would be. It carried one method,
 * {@code getName()}, which reconstructed an agent's own name from the Cybele agent id; this agent
 * was its last subclass and reifies the name instead ({@link #name()} → {@code getLocalName()},
 * which INVENTORY DEF-12 calls the more correct form).
 * <p>
 * {@code StaticRailwayObject} <b>stays</b> — its four channel-name constants are still named by
 * {@code TraceProbe}, which is #36's to replace — but it could not stay untouched, and #33's stated
 * reason for leaving it alone ("the dead half compiles") expired the moment {@code RailwayObject}
 * went: its constructor and its two vote handlers all called {@code getName()}. It is therefore
 * <b>reduced to the four constants</b>, which is the change #33 considered and declined as churn on
 * a file that was about to be deleted wholesale. It is not churn now; it is what makes the deletion
 * legal.
 * <p>
 * The four public constants <em>on this class</em> stay for the same reason
 * {@code RoadAgent.TRAVEL_START} did: {@code TraceProbe} opens {@code START.}/
 * {@code ENTER_REPLY.}/{@code TRAVEL_END.} channels and tests {@code KILLED}, and #27's
 * {@code ChannelTableTest} compares {@code Channel}'s transcription against three of them. Nothing
 * in the JADE half reads them. They go with the probe, in #36.
 * <p>
 * The unit that <em>is</em> worth extracting now that all five agents exist —
 * {@code name}/{@code emit}/{@code Inbox}/{@code Drain}, repeated five times — is a JADE concern
 * and belongs in the {@code jadeOntology} source set next to {@code Templates}, not in a
 * resurrected {@code RailwayObject} in the application package. #31 said so from two files; it is
 * confirmed here from five. It is deliberately not done inside this ticket, which would mean
 * rewriting four agents in a commit about the fifth.
 *
 * @author Bedrich Hovorka
 *
 */
public class Train extends Agent {
    private static final long serialVersionUID = 1L;
    /**
     * State of train — the destructor sentinel {@code RailwayMainAgent.recieveTrainState} tests for
     * to drop the train's row from the GUI table.
     * <p>
     * Points at {@link TrainState#KILLED} rather than repeating the literal, so the payload record
     * and the agent cannot drift apart. It is still a compile-time constant, so {@code TraceProbe}'s
     * use of it is unchanged.
     */
    public static final String KILLED = TrainState.KILLED;
    /**
     * channel for start command
     * <p>
     * <b>This agent no longer uses it.</b> JADE routes {@code START} by the train's AID plus the
     * {@code railway.START} ontology slot ({@code docs/message-ontology.md} §6). Kept for
     * {@code TraceProbe} (#36) and for #27's {@code ChannelTableTest}, exactly as
     * {@code RoadAgent.TRAVEL_START} is.
     */
    public static final String START = "START.";
    /**
     * channel for notifiaction
     *
     * @see #START
     */
    public static final String ENTER_REPLY = "ENTER_REPLY.";
    /**
     * channel for notifiaction
     *
     * @see #START
     */
    public static final String TRAVEL_END = "TRAVEL_END.";
    private String to;
    private String from;
    private String position;
    private String nextPosition;
    /**
     * Whether a {@code START} has been received — state 2008 did not keep, and which nothing in the
     * simulation reads.
     * <p>
     * It exists so that a train wedged before its first hop is <b>distinguishable from</b> a train
     * wedged waiting for its first {@code ENTER_REPLY}: both have {@code position == null}, and
     * without this flag #36/#38 could tell them apart only by watching for a hang. See the class
     * comment on why this agent has no watchdog.
     */
    private boolean started;

    /**
     * Reads the two construction arguments {@code Generator} used to pass to the constructor — the
     * origin and destination stations — registers the two behaviours, and pushes the opening
     * {@code TRAIN_STATE}.
     * <p>
     * The opening push is the last statement, as it was the last statement of the 2008 constructor,
     * and it must stay first on the wire: {@code trace-format.md}'s completeness rule is that
     * <em>"the first {@code TRAIN_STATE} of a train must be its {@code generated} line"</em>, and a
     * first line reading {@code entered to …} would mean the opening of that train's story is gone.
     * <p>
     * <b>A missing or empty endpoint is refused here</b> rather than corrupting every later line.
     * {@code from} and {@code to} are not incidental: {@code to} is the {@code target} slot of
     * <em>every</em> {@code ENTER} this train sends, which is the field {@code Station.enter} routes
     * on, and both are concatenated into <em>every</em> {@code TRAIN_STATE} payload — which a golden
     * compares character for character, since {@code trace-normalizer.md} deliberately does not
     * project {@code state}. A {@code null} would not fail; it would produce a run's worth of
     * quietly wrong trace lines. Same reasoning, and the same placement, as
     * {@code RoadAgent.setup()}'s refusal of a {@code null} neighbour.
     */
    @Override
    protected void setup() {
	final Object[] args = getArguments();
	if (args == null || args.length != 2) {
	    throw new IllegalArgumentException(
		    "Train " + getLocalName() + " needs {String from, String to}");
	}
	this.from = endpoint(args[0], "from");
	this.to = endpoint(args[1], "to");
	addBehaviour(new Inbox());
	addBehaviour(new Drain());
	sendStatusMessage(name() + " generated");
    }

    private String endpoint(Object value, String which) {
	if (!(value instanceof String) || ((String) value).isEmpty()) {
	    throw new IllegalArgumentException("Train " + getLocalName() + ": " + which
		    + " must be a non-empty station name, was " + value);
	}
	return (String) value;
    }

    /**
     * The one inbound behaviour. All three of this agent's channels share it, because a JADE agent
     * has one message queue and competing {@code receive}/{@code block()} loops over overlapping
     * templates are the classic way to lose a message to it ({@code docs/message-ontology.md} §7).
     * <p>
     * A train is §7's third collision row: {@code ENTER_REPLY} and {@code TRAVEL_END} are both
     * {@code inform}, so matching on the performative alone would hand a {@code TRAVEL_END} to
     * {@link #entered}. The {@code :ontology} slot resolves it.
     */
    final class Inbox extends CyclicBehaviour {
	private static final long serialVersionUID = 1L;
	private final MessageTemplate template = Templates.inbound(Party.TRAIN);

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
     * <b>Note for #36</b>, carried over from {@code Station} and {@code RoadAgent} because it
     * applies verbatim: {@code not(inbound)} is the complement of <em>everything</em>, not just of
     * the railway ontology, so in a live container it also matches AMS and DF traffic. A train
     * registers with neither today; if a later ticket gives one a DF registration, this template
     * has to be narrowed first or the drain will report platform housekeeping as a port bug.
     */
    final class Drain extends CyclicBehaviour {
	private static final long serialVersionUID = 1L;
	private final MessageTemplate template = Templates.unexpected(Party.TRAIN);

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
     * Named {@code dispatch} rather than {@code handle} because {@code Behaviour.handle} exists and
     * an inner {@code CyclicBehaviour} would resolve the unqualified call to that one.
     * <p>
     * Package-visible and free of any container dependency on purpose: it is the seam
     * {@code docs/TESTING.md} §4.1 asks for, so the whole agent can be driven as a POJO.
     *
     * @param acl a message matching {@link Templates#inbound(Party)} for {@link Party#TRAIN}
     */
    void dispatch(ACLMessage acl) {
	final Channel channel = Messages.channelOf(acl);
	final RailwayMessage message = Messages.contentOf(acl);
	switch (channel) {
	    case START -> start((StartCommand) message);
	    case ENTER_REPLY -> entered((EnterReply) message);
	    case TRAVEL_END -> travelEnd((TravelEnd) message);
	    default -> unexpected(acl);
	}
    }

    /**
     * Report a message this agent has no handler for. Loud, and it does not throw: the queue is
     * drained either way, so one stray message cannot wedge the agent.
     * <p>
     * <b>#36 split this in two.</b> {@code Templates.unexpected} is the complement of everything,
     * so on a live platform it also catches AMS delivery failures — housekeeping the baseline
     * performs silently. See {@link UnexpectedMessage} for the measurement and the two shapes.
     *
     * @param acl the message
     */
    void unexpected(ACLMessage acl) {
	UnexpectedMessage.report("Train", name(), getAMS(), acl);
    }

    /**
     * Push one status line to the hub, on {@code TRAIN_STATE}.
     * <p>
     * <b>DEF-11 dropped, behaviour kept.</b> 2008 read {@code (mess == KILLED)}; this reads
     * {@code KILLED.equals(mess)}. {@code defect-triage.md} measured the two as identical on every
     * caller that exists and grants #32 the carve-out explicitly. What is <em>not</em> changed is
     * the string: {@code message-ontology.md} §5.7 keeps {@code TRAIN_STATE} an opaque string
     * because "the string is in the trace character for character, spaces, {@code >} and {@code :}
     * included", and the normalizer does not project {@code state}.
     *
     * Package-visible, unlike its 2008 {@code private} original, for one reason: it is DEF-11's
     * site, and the difference between {@code ==} and {@code .equals} is observable <em>only</em>
     * to a caller that passes a {@code String} equal to {@code KILL} without being that interned
     * literal. No caller in the application does — which is exactly why the two are
     * indistinguishable in a trace — so the assertion that a later reader has not "restored" the
     * idiom has to be made through this seam or not at all.
     *
     * @param mess the tail of the status line, or {@link #KILLED} for the destructor sentinel
     */
    void sendStatusMessage(String mess) {
	final String message = KILLED.equals(mess) ? KILLED : from + " -> " + to + " : " + mess;
	emit(new TrainState(message), RailwayMainAgent.MAIN_AGENT_NAME);
    }

    /**
     * start command — {@code Planning} has won this train its slot and named the station to enter.
     * <p>
     * The {@code System.out.println} is <b>trace-visible</b> and is kept verbatim: every golden
     * carries a {@code "<train> started"} stream, appended after the canonical trace by
     * {@code trace-normalizer.md} §2.3's {@code println-streams} rule and sorted by train name
     * (three orderings across six runs at one seed, so the sort is doing real work).
     * <p>
     * The {@code assert} is kept unguarded — a contract surface, since goldens are recorded with
     * {@code -ea} and #36 replays with {@code -ea}. §8.3 attributes it to DEF-17, the wrong plan's
     * station arriving here, and §5 records the divergence: with assertions off the train enters the
     * <em>wrong</em> station, with them on it never starts. Its <b>shape</b> diverges under JADE in
     * the way #31 and #33 already recorded for their asserts — {@code Agent.run()}'s
     * {@code catch (Throwable)} kills the whole agent rather than one handler, and writes to
     * <em>stdout</em>. #39 must not be told the two shapes are one.
     *
     * @param command the station to enter, which must be this train's origin
     */
    void start(StartCommand command) {
	System.out.println(name() + " started");
	final String station = command.station();
	assert station.equals(from);
	started = true;
	requestEnterToObject(station);
    }

    /**
     * entry to object accepted notification — the train is now inside {@code object}.
     * <p>
     * <b>The first statement is DEF-07</b> and it is deliberate: the old position is released
     * <em>after</em> the new object has already counted the train, not before. See the class comment
     * for the mechanism, for where the projection can still see it (a queued hop, and only there),
     * and for why the first hop emits no {@code LEAVE} at all.
     * <p>
     * The three-way branch is 2008's, statement for statement. The {@code next == null} arm is the
     * arrival: {@code message-ontology.md} §5.4 lists that {@code null} as one of the two meaningful
     * ones in the protocol — "how a train is told it has arrived; it calls {@code Agent.die()}" —
     * and it is the branch a port most easily gets wrong by entering something instead.
     * {@link #die()} is the last statement, as {@code Agent.die()} was, and the class comment
     * explains why that is safe on JADE.
     * <p>
     * Both {@code assert}s are kept unguarded, for the reason {@link #start} gives. §8.3 attributes
     * both to DEF-01 — a spurious wake-up in a station making {@code next} {@code null} mid-route —
     * and notes that {@code -ea} <em>suppresses</em> DEF-01's classic symptom: the train wedges
     * loudly instead of vanishing quietly.
     *
     * @param reply the object that admitted the train, and where it should go next
     */
    void entered(EnterReply reply) {
	leaveObject(position);
	position = reply.object();
	sendStatusMessage("entered to " + position);
	nextPosition = reply.next();
	if (position.startsWith("st")) {
	    if (nextPosition != null) {
		requestEnterToObject(nextPosition);
	    } else {
		assert position.equals(to);
		die();
	    }
	} else {
	    assert position.startsWith("tr");
	    emit(new TravelStart(name()), position);
	}
    }

    /**
     * travel end on road notification — the track's timer has fired and the far end is reached.
     * <p>
     * The payload is <b>not read</b>, and that is 2008's behaviour rather than an omission:
     * {@code message-ontology.md} §5.3 records that {@code Train.travelEnd} "never touches
     * {@code ev.getMessage()}; it goes straight to {@code nextPosition}", and that the {@code road}
     * field is carried only because the probe records it and the golden holds {@code road=tr1}. The
     * parameter is named and typed so the seam matches every other handler.
     * <p>
     * The {@code null} guard is also 2008's. It cannot be taken by a train on a well-formed route —
     * a track always replies with the station at its far end — so removing it would be invisible; it
     * is kept because the branch it protects is DEF-01's, and a train that has been handed a
     * {@code null} {@code next} on a track stops here rather than sending an {@code ENTER} to
     * {@code null}.
     *
     * @param notice which track finished; unread, see above
     */
    void travelEnd(TravelEnd notice) {
	if (nextPosition != null) {
	    requestEnterToObject(nextPosition);
	}
    }

    /**
     * Release an object the train is no longer in.
     * <p>
     * <b>The guard reads {@code position} and the send addresses {@code object}</b>, which is 2008's
     * shape verbatim. It is not a bug and it is not redundant: both call sites pass {@code position}
     * itself, so the two are always the same reference, and what the guard actually encodes is
     * <em>"a train that has never entered anything releases nothing"</em> — the first-hop no-op that
     * makes a route of <i>n</i> objects produce <i>n</i> {@code LEAVE}s. Rewriting it to
     * {@code if (object != null)} would be the same program today and a different one the moment
     * anything passed a different argument. Mutation M4 does exactly that and <b>survives</b> the
     * suite — which is that sentence demonstrated rather than asserted, and the one survivor in
     * #32's set of 27.
     *
     * @param object the object to release
     */
    private void leaveObject(String object) {
	if (position != null) {
	    emit(new LeaveNotice(name()), object);
	}
    }

    /**
     * Ask an object to admit the train.
     * <p>
     * One shape goes to both kinds of receiver and each reads a different subset of it
     * ({@code message-ontology.md} §5.2): a station reads {@code train} and {@code target}, a track
     * reads {@code train} and {@code position}. Swapping them misroutes silently, which is why the
     * record names the slots rather than indexing them. {@code position} is {@code null} on the very
     * first {@code ENTER} — §5.4's other meaningful {@code null}, and correct: that one always goes
     * to a station, which does not read the slot.
     *
     * @param object the station or track to enter
     */
    private void requestEnterToObject(String object) {
	emit(new EnterRequest(name(), position, to), object);
    }

    /**
     * The Cybele destructor-by-convention, now JADE's teardown callback.
     * <p>
     * <b>Two statements, in this order, and the order is observable.</b> The {@code LEAVE} balances
     * the destination station's {@code occupied++} (DEF-08, de-claimed — see the class comment), and
     * the {@code KILL} is what {@code RailwayMainAgent.recieveTrainState} tests for to drop the
     * train's row. {@code defect-triage.md}: "a completed train must emit a final {@code LEAVE} to
     * its destination immediately before {@code TRAIN_STATE state=KILL}. Its absence is a port bug."
     * <p>
     * Both sends reach the transport: {@code Agent.clean} restores the active lifecycle, calls this
     * method, and only afterwards calls {@code myToolkit.handleEnd(myAID)}.
     */
    @Override
    protected void takeDown() {
	leaveObject(position);
	sendStatusMessage(KILLED);
    }

    /**
     * {@code Agent.die()} — end this agent, which runs {@link #takeDown()}.
     * <p>
     * A seam rather than a bare {@code doDelete()} call, for the reason
     * {@code Generator.createTrain} is one: {@code doDelete()} needs a container, and the arrival
     * branch of {@link #entered} is the single most important branch in this file to be able to
     * drive as a POJO. In the container this is {@code doDelete()} and nothing else.
     */
    protected void die() {
	doDelete();
    }

    /**
     * @return the object the train is currently in, or {@code null} before its first admission
     */
    String position() {
	return position;
    }

    /**
     * @return where the train has been told to go next, or {@code null} at its destination
     */
    String nextPosition() {
	return nextPosition;
    }

    /**
     * @return whether a {@code START} has arrived; see the field comment for why this exists
     */
    boolean hasStarted() {
	return started;
    }

    /**
     * This agent's local name — trace fields 1, 4 and 5, and the {@code train} slot of every payload
     * it sends.
     * <p>
     * Overridable so the agent can be unit-tested outside a container; in the container it is
     * {@code getLocalName()}, which is what {@code RailwayObject.getName()} — deleted by this
     * commit — reconstructed from the Cybele agent id (INVENTORY DEF-12 notes that reifying it is
     * the more correct form).
     *
     * @return the train name, {@code vl<n>}
     */
    protected String name() {
	return getLocalName();
    }

    /**
     * The single outbound seam. Every message this agent sends goes through here.
     * <p>
     * Overridable for the same reason as {@link #name()}, and it is where the probe's topic AID is
     * added as a second receiver ({@code Messages.build(msg, from, to, topic)}).
     * <p>
     * <b>#36 spent the budget this note reserved.</b> {@link TraceTopics#of} returns the
     * channel's probe topic when {@code sim.trace.enabled=true} and {@code null} otherwise, and
     * {@code Messages.build} adds it as a <em>second</em> receiver. With tracing off the
     * {@code :receiver} set is byte-identical to what it was before this line changed.
     *
     * @param message the payload record
     * @param receiver the addressed agent's local name
     */
    protected void emit(RailwayMessage message, String receiver) {
	send(Messages.build(message, name(), receiver, TraceTopics.of(message.channel())));
    }
}
