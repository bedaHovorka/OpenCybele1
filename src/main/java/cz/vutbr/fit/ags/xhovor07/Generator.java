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
import java.util.Random;

import cz.vutbr.fit.ags.railway.domain.clock.AgentClock;
import cz.vutbr.fit.ags.railway.domain.msg.PlanTrain;
import cz.vutbr.fit.ags.railway.domain.msg.RailwayMessage;
import cz.vutbr.fit.ags.railway.jade.Messages;

/**
 * This is one activity of main agent — <b>ported to JADE by #33</b>.
 * Each {@code sim.arrival.lambdaMs} milliseconds with exponencial distribution of
 * probability generate new train.
 * <p>
 * This used to be {@code public static final int LAMBDA = 8500}, read both here
 * <em>and</em> by {@link Station#computeDifference(String, long)}. The two uses are now
 * separate parameters — {@code sim.arrival.lambdaMs} and {@code sim.station.voteWindowMs} —
 * so a scenario can shorten the arrival rate without moving the station scheduling
 * policy under it. Both default to 8500. See {@code docs/scenario-config.md}.
 *
 * <h2>This is not a {@code TickerBehaviour}, and that is the point</h2>
 * {@code docs/CYBELLE_TO_JADE.md} maps "periodic / timer activity → {@code TickerBehaviour},
 * near 1:1", and applying that map here would be wrong: <b>the interval is redrawn on every
 * fire</b>. {@code Activity.setTimer(CLOCK_ID, exp(LAMBDA), this, "generateTrain")} is the
 * <em>last</em> statement of the handler it re-arms, and {@link #exp(double)} takes a fresh
 * {@code round(-mean·ln U)} each time, so the shape is a self-rescheduling <b>one-shot</b>
 * wake-up, not a fixed period. A {@code TickerBehaviour} would turn an exponential arrival
 * process into a deterministic one — a different simulation, not a different implementation of
 * the same one.
 *
 * <p>
 * <b>Worth recording for #41, because it generalises past this class:</b> that row of the mapping
 * table has <b>no instance at all</b> in this codebase. All four timer sites — this one, the first
 * fire below, {@code Planning}'s departure wake-up and {@code RoadAgent}'s traversal — are
 * one-shot; {@code docs/clock-abstraction.md} §1 counts them and says so ("all four timer sites are
 * clock-relative"), and three of the four re-arm themselves. The register of what the kernel gave
 * back for the price is {@code CYBELLE_TO_JADE.md}; this is one correction to it.
 *
 * <p>
 * Nor is it a {@code WakerBehaviour}, for the reason {@code RoadAgent}'s class comment gives at
 * length: a {@code WakerBehaviour} deadline is <b>wall clock</b> and is fixed when the behaviour
 * is added, so it would ignore the toolbar's runtime pace change and keep arriving across a pause.
 * The wake-up goes on the shared {@link AgentClock} instead, and is drained by the one
 * {@code ClockTickerBehaviour} the host agent already runs for {@link Planning} — see
 * {@code RailwayMainAgent}'s "one clock, one ticker".
 *
 * <h2>{@code scheduleIn}, not {@code scheduleAt}</h2>
 * #34 had to arm on an <em>absolute</em> instant, because {@code ExpJ} measured
 * {@code Activity.setTimer} taking its delay from the kernel's own later read — 201 ms of drift
 * against a 200 ms gap — and the 2008 {@code pauseClock} bracket around {@code Planning.java:108}
 * existed to remove exactly that. <b>There is no bracket here</b>, at either site, so the drift
 * <em>is</em> the 2008 behaviour and a relative delay reproduces it.
 * {@code docs/clock-abstraction.md}'s call-site table says the same thing in as many words:
 * {@code Generator.java:50} → {@code clock.scheduleIn(firstFireMs, …)}, {@code :65} →
 * {@code clock.scheduleIn(exp(lambda), …)}. {@link AgentClock#scheduleIn} is drift-free anyway:
 * it reads the clock once, at the call, and stores the absolute result.
 *
 * <h2>When the first fire is armed, and why not in the constructor</h2>
 * The 2008 constructor armed it. Here {@link #start()} is called by
 * {@code RailwayMainAgent.noteStarted} — by the <em>fifteenth</em> child agent's opening state
 * push, not by {@code setup()}. That is the startup-ordering barrier, and it belongs to the hub;
 * its whole justification, its cost and its failure mode are in {@code RailwayMainAgent}'s class
 * comment. The only things this class contributes to it are that arming is a method rather than a
 * constructor side effect, and that {@link #start()} is idempotent, so a second call cannot start
 * a second chain of trains on the same counter.
 *
 * <h2>The two random streams: same key, same draw count, same order</h2>
 * #15's per-agent streams are keyed by a name — {@code Generator.od} and
 * {@code Generator.interarrival} — and {@code SimRandom.seedFor} is a pure function of that name
 * and the master seed. So this activity draws the same sequences the Cybele one drew, whatever
 * order the agents happen to be created in. What a port <em>can</em> break is the other half of
 * the guarantee: {@code docs/seeded-rng.md} states that "a stream is pinned by its own draw count",
 * so the number and order of draws inside {@link #generateTrain()} is contractual. It is
 * unchanged, and the shape is worth reading off explicitly:
 * <ul>
 *   <li>one {@code odRandom.nextInt(pairs.length)} per train generated, taken <b>before</b> the
 *       train agent is created;</li>
 *   <li>one {@code interarrivalRandom.nextDouble()} per <b>re-arm</b> — so the first fire, which is
 *       a fixed {@code sim.arrival.firstFireMs}, draws nothing, and a run that stops at
 *       {@code sim.stop.maxTrains} does not draw for the re-arm it never performs;</li>
 *   <li>nothing else on either stream, ever. Adding or reordering a draw re-aligns every later
 *       value of it.</li>
 * </ul>
 * The one 2008 statement that is gone — {@code Activity.openChannel(TRAIN.STATE.<train>, …)}, the
 * first line of the handler — took no draw, so it cannot move either sequence. It is gone because
 * the hub now matches every {@code TRAIN_STATE} with one template, which also takes DEF-10's
 * per-train channel-ticket leak with it; see {@code RailwayMainAgent}.
 *
 * @author Bedrich Hovorka
 */
public class Generator implements Serializable {
    private static final long serialVersionUID = 1L;

    /**
     * The JADE agent whose thread, mailbox and identity this activity runs on — {@code Main}.
     * Not a {@code RailwayMainAgent}: this activity needs a name, {@code send} and a container to
     * create trains in, which is exactly the surface {@code jade.core.Agent} already has.
     */
    private final jade.core.Agent host;
    /**
     * The host agent's {@link AgentClock}, <b>shared with {@link Planning}</b> rather than a second
     * one of its own — {@code Planning}'s handover note asks for exactly that, so that the one
     * {@code ClockTickerBehaviour} on this agent drains both and there is one granularity budget to
     * keep honest instead of two.
     */
    private final AgentClock clock;
    private final Serializable[][] hhh;
    private final long lambda;
    private final long firstFireMs;

    private int index = 0;
    /** Whether {@link #start()} has already armed the first fire. */
    private boolean armed;
    /**
     * Stream for the origin/destination choice. It used to be a {@code static final Random}
     * shared with every {@link RoadAgent} and published through {@code getRandom()}; see
     * {@link SimRandom} for why that could not be made reproducible by seeding alone.
     */
    private final Random odRandom;
    /** Stream for the exponential inter-arrival time. Separate on purpose — see {@link SimRandom}. */
    private final Random interarrivalRandom;

    /**
     * Install the generator on the {@code Main} agent. Arms nothing; see {@link #start()}.
     *
     * @param host the {@code Main} agent; must already have its AID and its container, i.e. this
     *        must be called from its {@code setup()}
     * @param clock the host agent's {@link AgentClock} — {@code Planning}'s, shared
     */
    public Generator(jade.core.Agent host, AgentClock clock) {
	super();
	if (host == null || clock == null) {
	    throw new IllegalArgumentException("Generator needs {Agent host, AgentClock clock}");
	}
	this.host = host;
	this.clock = clock;
	final ScenarioConfig config = ScenarioConfig.get();
	this.hhh = config.getTrainPairs();
	this.lambda = config.getArrivalLambdaMs();
	this.firstFireMs = config.getArrivalFirstFireMs();
	this.odRandom = SimRandom.forAgent(SimRandom.GENERATOR_OD_STREAM);
	this.interarrivalRandom = SimRandom.forAgent(SimRandom.GENERATOR_INTERARRIVAL_STREAM);
    }

    /**
     * Arm the first fire, at a fixed {@code sim.arrival.firstFireMs}. The 2008 constructor's last
     * statement, moved out so the hub's startup barrier can decide <em>when</em> the run starts
     * producing trains; see the class comment and {@code RailwayMainAgent}'s.
     * <p>
     * Idempotent: the barrier arms exactly once by construction, and a second call from a future
     * caller would otherwise start a second, interleaved chain of trains on one counter.
     */
    void start() {
	if (armed) {
	    return;
	}
	armed = true;
	clock.scheduleIn(firstFireMs, this::generateTrain);
    }

    /**
     * Method called by timer
     * <p>
     * Statement for statement this is the 2008 handler with its two Cybele calls replaced and its
     * {@code openChannel} deleted: draw the origin/destination pair, create the {@code Train}
     * agent, bump the counter, send {@code PLAN_TRAIN}, and re-arm with a fresh exponential draw.
     * The order matters and is contractual on both random streams — see the class comment.
     * <p>
     * {@code PLAN_TRAIN} goes through {@link #emit}, i.e. through {@code Agent.send} to this
     * agent's <em>own</em> AID, and that is deliberate rather than a roundabout way of calling
     * {@link Planning#planTrain}. The planner's seriality gate lives in the template its
     * {@code Inbox} draws with: while an election is open it narrows to {@code VOTE} alone and a
     * {@code PLAN_TRAIN} waits in the mailbox, exactly as it waited in the Cybele channel queue
     * behind the blocked handler. A direct call would bypass the queue, and with it the gate, and
     * silently start a second election.
     */
    void generateTrain() {
	final String train = "vl" + index;
	// Adding or reordering a draw on this stream re-aligns every later value of it.
	final Serializable[] serializables = hhh[odRandom.nextInt(hhh.length)];
	createTrain(train, serializables);
	index++;
	emit(new PlanTrain(train, (String) serializables[0], (String) serializables[1]),
		RailwayMainAgent.MAIN_AGENT_NAME);
	// sim.stop.maxTrains is enforced here rather than by the watchdog, so the bound is
	// exact: the run generates that many trains and not one more. With the key unset
	// this always returns true and the timer re-arms exactly as it always did.
	if (RunControl.trainGenerated(index)) {
	    clock.scheduleIn(exp(lambda), this::generateTrain);
	}
    }

    private long exp(double mean) {
        // Adding or reordering a draw on this stream re-aligns every later value of it.
        return Math.round(-mean * Math.log(interarrivalRandom.nextDouble()));
    }

    /**
     * @return how many trains have been generated so far — the {@code vl<n>} counter, whose
     *         current value names the next train. For tests and for #38.
     */
    int generated() {
	return index;
    }

    /** @return whether {@link #start()} has armed the first fire */
    boolean isArmed() {
	return armed;
    }

    /**
     * {@code Cybele.createAgent(train, Train.class.getName(), od)} — the train factory seam.
     * <p>
     * Separate from {@code RailwayMainAgent.spawn} on purpose: this one is called once per train
     * for the whole run, where that one is called fifteen times at boot, and a test of the
     * generator wants to capture the trains without also standing up a hub. Overridable for the
     * same reason {@link #emit} is.
     * <p>
     * {@code Train} is still a Cybele {@code Handler} — #32 has not landed — so this call compiles
     * and would fail at runtime, which is #4's stated position for the whole of 1-PORT: the tree
     * compiles at every step and runs at none of them until the set is complete.
     *
     * @param train the train's local name, {@code vl<n>}
     * @param originDestination the {@code {from, to}} pair, passed on as the agent's arguments
     */
    protected void createTrain(String train, Serializable[] originDestination) {
	try {
	    host.getContainerController()
		    .createNewAgent(train, Train.class.getName(), originDestination)
		    .start();
	} catch (jade.wrapper.StaleProxyException e) {
	    throw new IllegalStateException("Generator: cannot create train '" + train + "'", e);
	}
    }

    /**
     * The identity this activity writes into trace fields 4 <em>and</em> 5 of {@code PLAN_TRAIN} —
     * the host agent's local name, which is {@code Main}. Overridable so a test can name the
     * activity without a container.
     *
     * @return {@code Main}
     */
    protected String name() {
	return host.getLocalName();
    }

    /**
     * The single outbound seam. {@code Activity.sendAll(Planning.PLAN_TRAIN, payload)} becomes one
     * AID-addressed {@code ACLMessage} to this agent itself; overridable so a test can capture what
     * was sent without a message-transport service.
     *
     * @param message the payload record
     * @param receiver the addressed agent's local name — {@code Main}, for the one channel this
     *        activity sends on
     */
    protected void emit(RailwayMessage message, String receiver) {
	host.send(Messages.build(message, name(), receiver));
    }
}
