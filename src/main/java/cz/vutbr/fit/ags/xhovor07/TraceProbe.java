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

import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.RailwayMessage;
import cz.vutbr.fit.ags.railway.domain.msg.TraceLine;
import cz.vutbr.fit.ags.railway.domain.msg.TrainState;
import cz.vutbr.fit.ags.railway.jade.Messages;
import cz.vutbr.fit.ags.railway.jade.Templates;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.messaging.TopicManagementHelper;
import jade.lang.acl.ACLMessage;
import jade.lang.acl.MessageTemplate;

/**
 * The parity trace probe (#20, re-implemented for JADE by #36): a passive agent that registers to
 * all fifteen channel topics and appends one canonical line per observed message to
 * {@code System.out}.
 *
 * <h2>The contract, which did not change</h2>
 *
 * One line per observed message:
 *
 * <pre>agent|tick|event|from|to|performative|payload</pre>
 *
 * The field semantics, the per-channel table and the escaping rules are
 * {@code docs/trace-format.md}. <b>That document, not this class, is the contract</b>, and the
 * Cybele probe on {@code opencybele-baseline} is held to the same one — the two must produce
 * byte-identical lines for equivalent behaviour or the goldens compare nothing.
 * <p>
 * Which is why this class renders <b>no</b> line of its own. {@code TraceLine.render} and
 * {@code Payloads} live in the framework-free {@code domain} source set precisely so that the
 * Cybele probe, this one and #43's Jason {@code AgArch} share one rendering rather than three
 * readings of the specification. Everything below is about <em>what to hand it</em>.
 *
 * <h2>How a passive observer exists at all on JADE</h2>
 *
 * On Cybele it was free: a channel is genuine multicast, so a second {@code openChannel} on a
 * channel that already had a handler cost the first subscriber nothing (INVENTORY {@code SEM-03}),
 * and no agent knew the probe existed. JADE has no such thing — a message reaches exactly the AIDs
 * it is addressed to. #27 decided the replacement and #36 wires it:
 *
 * <ul>
 * <li>every {@code emit} seam passes {@link TraceTopics#of(Channel)} to
 *     {@code Messages.build(msg, from, to, topic)}, which adds the channel's topic AID as a
 *     <b>second receiver</b>. The named agent still gets exactly one copy;</li>
 * <li>this agent registers to those fifteen topics and is addressed by nobody;</li>
 * <li>with {@code sim.trace.enabled=false} — the default — {@link TraceTopics} hands back
 *     {@code null}, no second receiver is added, and the run is the run it would have been.</li>
 * </ul>
 *
 * <h2>Three things the Cybele probe had to solve that this one does not</h2>
 *
 * <ol>
 * <li><b>The aliased payload is gone.</b> Under {@code Local;NoSerialization} a payload crossed a
 *     Cybele channel <em>by reference</em>, so {@code Station.Info} arrived as a live alias the
 *     sending station kept mutating (INVENTORY {@code SEM-05}, DEF-13) and every handler had to
 *     copy its fields out as its first statement. Here the payload is an immutable record that
 *     {@code setContentObject} marshalled at send time. <b>Note that this makes the trace more
 *     faithful and the comparison no easier</b>: {@code STATION_INFO}'s {@code occupied} is still
 *     projected by the normalizer, because the baseline's value is a race and no capture point
 *     removes it.</li>
 * <li><b>The train look-ahead is gone.</b> Four Cybele channels were named after a train that did
 *     not exist yet, so the probe pre-opened {@code sim.trace.trainLookahead} name slots ahead of
 *     the generator and extended the window as it observed trains. With #27's <em>per channel
 *     constant</em> topics those four are one topic each, whatever the train is called. The
 *     configuration key is still read and still printed in the resolved-configuration banner —
 *     the scenario files and {@code ScenarioAssertions} are frozen and declare it — but on this
 *     implementation it selects nothing.</li>
 * <li><b>Handler-name binding is gone.</b> Fifteen {@code openChannel(name, "onFoo", this)} calls
 *     bound a method by string literal; a typo failed silently at runtime (DEF-20). One behaviour
 *     and one {@code switch}-free renderer replace them.</li>
 * </ol>
 *
 * <h2>The one hazard that survived intact: a probe that fails silently</h2>
 *
 * Cybele swallowed a handler throwable and left the exit status alone; JADE kills the agent
 * instead. Both leave a truncated trace that looks exactly like a short, clean run — and in
 * record mode both would freeze it into a golden. So every observation is wrapped, and a fault is
 * reported in the one shape the parity harness scans the <b>raw</b> stream for before it
 * normalizes anything ({@code docs/parity-harness.md} §4). The probe keeps running afterwards:
 * the remaining channels are still worth recording, and the run is already unrecordable.
 *
 * @author Bedrich Hovorka
 */
public class TraceProbe extends Agent {
    private static final long serialVersionUID = 1L;

    /**
     * The probe's local name. Deliberately not a legal station ({@code st*}), track ({@code tr*})
     * or train ({@code vl*}) name, and not {@code Main} — trace field 1 is a name from one of
     * those families, so the probe can never be mistaken for a subject.
     */
    public static final String PROBE_AGENT_NAME = "Probe";

    /**
     * Counted down when every topic is registered. {@code Main} waits on it before it creates
     * {@code RailwayMainAgent}, so no message of the run can predate the subscription. Static
     * because the agent is instantiated by the container and {@code Main} has no reference to it.
     */
    private static final CountDownLatch READY = new CountDownLatch(1);

    /** Non-zero once an observation has failed; see {@link #probeFailures()}. */
    private static final AtomicInteger FAILURES = new AtomicInteger();

    /** Cap on failure reports, so a systematic fault cannot bury the trace it broke. */
    private static final int MAX_FAILURE_REPORTS = 20;

    /** How long {@link #awaitReady()} waits before declaring the probe dead, ms. */
    private static final long READY_TIMEOUT_MS = 60000;

    /** How the first {@code TRAIN_STATE} of every train ends; see {@link #checkFirstState}. */
    static final String GENERATED_SUFFIX = " generated";

    /**
     * How many later trains may be announced before a train with no {@code TRAIN_STATE} is called
     * a gap. See {@link #checkGenerated}.
     */
    static final int GENERATED_GRACE = 8;

    /** Trains for which at least one {@code TRAIN_STATE} has been observed. */
    private final Set<String> firstStateSeen = new HashSet<String>();
    /** Indices announced on {@code PLAN_TRAIN} with no {@code TRAIN_STATE} yet. */
    private final TreeSet<Integer> awaitingGenerated = new TreeSet<Integer>();

    /**
     * Register to the fifteen topics, then start observing.
     * <p>
     * <b>The registration is the barrier and it must not be allowed to half-succeed.</b> Without
     * {@code jade.core.messaging.TopicManagementService} in the container profile the helper
     * lookup throws and every topic is a dead letter — which would show up as a probe that emits
     * nothing rather than as a boot failure, i.e. as an empty trace with a green exit status.
     * {@code Main} never releases the run in that case, because {@link #awaitReady()} times out
     * on a latch this method never reaches.
     */
    @Override
    protected void setup() {
	final TopicManagementHelper helper;
	try {
	    helper = (TopicManagementHelper) getHelper(TopicManagementHelper.SERVICE_NAME);
	} catch (Exception e) {
	    report("the trace probe cannot obtain " + TopicManagementHelper.SERVICE_NAME
		    + ". The container profile must list '" + TopicManagementHelper.SERVICE_NAME
		    + "Service' under " + jade.core.Profile.SERVICES + " (docs/message-ontology.md"
		    + " section 8); without it every topic is silently a dead letter and the run"
		    + " produces an EMPTY trace with a green exit status.", e);
	    return;   // READY is never counted down; Main refuses to start the simulation
	}
	for (Channel channel : Channel.values()) {
	    try {
		// TraceTopics.topic, not helper.createTopic: the name a sender addresses and the
		// name this agent registers are then the same object, and cannot drift.
		helper.register(TraceTopics.topic(channel));
	    } catch (Exception e) {
		report("the trace probe cannot register to topic '" + channel.id()
			+ "'. Every " + channel.event() + " of this run would be missing from the"
			+ " trace.", e);
		return;
	    }
	}

	System.err.println("--- trace probe: " + PROBE_AGENT_NAME + " registered to "
		+ Channel.values().length + " channel topics (one per channel CONSTANT, so no"
		+ " train look-ahead is needed). Format:"
		+ " agent|tick|event|from|to|performative|payload; see docs/trace-format.md.");
	READY.countDown();

	addBehaviour(new Observe());
	addBehaviour(new Drain());
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Block until the probe has registered to every topic.
     * <p>
     * Called from {@code Main} between starting this agent and starting {@code RailwayMainAgent}.
     * Without the barrier the two starts race: {@code RailwayMainAgent.setup()} spawns the
     * stations, and a {@code Station} sends its first {@code STATION_INFO} from its own
     * {@code setup()}, so the opening lines of the run could be gone before the probe subscribed
     * — and a trace missing its first lines diffs against a golden for no behavioural reason.
     *
     * @throws IllegalStateException if the probe never finished registering, which means the run
     *         has no trace and must not be allowed to look like one
     */
    public static void awaitReady() {
	try {
	    if (!READY.await(READY_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
		throw new IllegalStateException("the trace probe did not register within "
			+ READY_TIMEOUT_MS + " ms. Either the container has no"
			+ " TopicManagementService (see setup()) or the agent was never started."
			+ " Refusing to start the simulation: with "
			+ ScenarioConfig.KEY_TRACE_ENABLED + "=true a run without a trace is a"
			+ " failed run, not a quiet one.");
	    }
	} catch (InterruptedException e) {
	    Thread.currentThread().interrupt();
	    throw new IllegalStateException("interrupted while waiting for the trace probe", e);
	}
    }

    /**
     * @return how many observations have failed. Non-zero means the trace has holes in it.
     */
    public static int probeFailures() {
	return FAILURES.get();
    }

    // ---------------------------------------------------------------- observing

    /**
     * The one behaviour. It draws on the union of all fifteen channel templates rather than on
     * {@code receive()} with no template, so that platform traffic — an AMS reply, a
     * {@code failure} bounced back from a dead agent — cannot be decoded as a railway message and
     * rendered as a trace line. Anything else falls through to {@link Drain}.
     */
    final class Observe extends CyclicBehaviour {
	private static final long serialVersionUID = 1L;
	private final MessageTemplate template = observedTemplate();

	@Override
	public void action() {
	    final ACLMessage acl = myAgent.receive(template);
	    if (acl == null) {
		block();
		return;
	    }
	    observe(acl);
	}
    }

    /**
     * The complement of {@link #observedTemplate()}. The probe is addressed by nobody, so on a
     * healthy run this never fires; when it does, the message is <b>reported and consumed</b>
     * rather than left to rot in a queue that would grow for the life of the run.
     * <p>
     * Reported on stderr with the {@code !!! } prefix and <em>not</em> counted as a probe failure:
     * a stray AMS {@code inform} is not a hole in the trace. It is worth seeing, because the
     * probe registering to something it did not mean to is exactly how a trace acquires lines the
     * baseline never had.
     */
    final class Drain extends CyclicBehaviour {
	private static final long serialVersionUID = 1L;
	private final MessageTemplate template = MessageTemplate.not(observedTemplate());

	@Override
	public void action() {
	    final ACLMessage acl = myAgent.receive(template);
	    if (acl == null) {
		block();
		return;
	    }
	    System.err.println("!!! trace probe: unobservable message reached the probe, ontology="
		    + acl.getOntology() + " performative="
		    + ACLMessage.getPerformative(acl.getPerformative())
		    + " from=" + Messages.senderName(acl) + ". Dropped; it is not a trace line.");
	}
    }

    /**
     * Everything this probe renders: the union of all fifteen channels' templates.
     * <p>
     * Package-visible so a test can assert on it. Built from {@link Channel#values()} rather than
     * from a list, so a sixteenth channel cannot be forgotten here.
     *
     * @return a template matching exactly the fifteen railway channels
     */
    static MessageTemplate observedTemplate() {
	return Templates.anyOf(java.util.List.of(Channel.values()));
    }

    /**
     * Render one observed message.
     * <p>
     * <b>One deserialization per line.</b> {@code Messages.subjectOf(acl)} alone would read the
     * subject out of the {@code :conversation-id} slot with no unmarshalling at all, but this
     * probe wants the payload on every single line, so it reads the content once and derives
     * field 1 from it — the <em>normative</em> definition in {@code docs/trace-format.md}, with no
     * reliance on a slot a sender could forget to set. {@code AclBindingTest} asserts the two
     * agree on every channel, so the fast path stays available and this one stays honest.
     * <p>
     * Package-visible and container-free, so a test can drive it with a hand-built
     * {@code ACLMessage}.
     *
     * @param acl a message matching {@link #observedTemplate()}
     */
    void observe(ACLMessage acl) {
	try {
	    final RailwayMessage content = Messages.contentOf(acl);
	    final String from = Messages.senderName(acl);
	    final String to = Messages.receiverName(acl);
	    if (content.channel() == Channel.PLAN_TRAIN) {
		final int index = trainIndex(content.subject(from, to));
		if (index >= 0) checkGenerated(index);
	    } else if (content.channel() == Channel.TRAIN_STATE) {
		checkFirstState(from, ((TrainState) content).state());
	    }
	    emit(TraceLine.render(content, tick(), from, to, TraceLine.JADE));
	} catch (Throwable t) {
	    failed(acl, t);
	}
    }

    /**
     * Append one canonical line. {@code System.out.println(String)} is atomic per line on a
     * {@link java.io.PrintStream}, which is what keeps a probe line from interleaving with the
     * application's own two {@code println}s on the same stream.
     * <p>
     * Overridable so a test can capture what was rendered without capturing {@code System.out}.
     *
     * @param line the rendered trace line, without a terminator
     */
    protected void emit(String line) {
	System.out.println(line);
    }

    /**
     * The simulated clock, never wall time.
     * <p>
     * Read from {@link RunControl#simTimeMs()}, which is the {@link
     * cz.vutbr.fit.ags.railway.domain.clock.SimClock} the main agent created and published (#81).
     * That is the same object every agent schedules on, so field 2 means on this branch exactly
     * what {@code Cybele.getTime("myClock")} meant on the baseline.
     * <p>
     * <b>Read when the probe HANDLES the message, not when the sender sent it</b>, exactly as on
     * Cybele — and deliberately, not for want of a send-time stamp. {@code docs/trace-format.md}:
     * handling-time reads from one serial consumer make field 2 monotonically non-decreasing over
     * the whole trace, which is the only ordering invariant the format has and the thing the
     * normalizer's burst segmentation rests on. Send-time stamping from N concurrent agents does
     * not have that property. The cost is this probe's own dispatch latency, sub-millisecond of
     * simulated time at the paces these scenarios use.
     * <p>
     * {@code -1} means the clock did not exist. It is not a legal simulated time and should be
     * read as a fault; it is unreachable in practice, because {@code Main} starts this agent
     * before {@code RailwayMainAgent}, whose {@code setup()} creates the clock as its first
     * statement and before any child agent exists to send anything.
     *
     * @return simulated milliseconds
     */
    private static long tick() {
	return RunControl.simTimeMs();
    }

    // ---------------------------------------------------------------- completeness

    /**
     * <b>The first {@code TRAIN_STATE} of a train must be its {@code generated} line.</b>
     * <p>
     * {@code Train.setup()} sends that line before the train can do anything else, so if the first
     * one observed is {@code entered to …} or {@code KILL}, this probe was subscribed too late and
     * the opening of that train's story is missing. That is a hole in the trace and the run must
     * not be recordable, however healthy its exit status.
     * <p>
     * <b>It checks something different here than it did on Cybele, and it is kept for what is
     * left.</b> There it caught a look-ahead window that had fallen behind the generator — a
     * failure this implementation cannot have, because the topic is per channel constant. What it
     * still catches is the barrier: a probe whose registration lost a race with the first agents,
     * or a run started without {@link #awaitReady()}.
     *
     * @param train the sending train
     * @param state the state string it sent
     */
    private void checkFirstState(String train, String state) {
	if (!firstStateSeen.add(train)) {
	    return;
	}
	awaitingGenerated.remove(Integer.valueOf(trainIndex(train)));
	if (TrainState.KILLED.equals(state)) {
	    reportGap("the first TRAIN_STATE observed for " + train + " is the KILL sentinel:"
		    + " every earlier message of that train is missing from the trace.");
	} else if (!state.endsWith(GENERATED_SUFFIX)) {
	    reportGap("the first TRAIN_STATE observed for " + train + " is '" + state
		    + "', not its 'generated' line. That train's opening messages were sent before"
		    + " the probe registered, so the trace is INCOMPLETE for it. The registration"
		    + " barrier (TraceProbe.awaitReady) did not hold.");
	}
    }

    /**
     * The other half of the same check, for a train whose {@code TRAIN_STATE} was missed
     * <em>entirely</em> rather than just late — {@link #checkFirstState} never runs for that train,
     * so nothing above would notice.
     * <p>
     * Every generated train is announced on {@code PLAN_TRAIN} exactly once. A train announced
     * there and still without a {@code TRAIN_STATE} once {@value #GENERATED_GRACE} later trains
     * have been announced is a train this probe has lost. The grace exists because the two
     * messages race: the generator creates the {@code Train} agent and then sends
     * {@code PLAN_TRAIN}, while the train's own {@code setup()} runs on another thread.
     *
     * @param announcedIndex the {@code vl<n>} index just announced
     */
    private void checkGenerated(int announcedIndex) {
	if (!firstStateSeen.contains("vl" + announcedIndex)) {
	    awaitingGenerated.add(Integer.valueOf(announcedIndex));
	}
	while (!awaitingGenerated.isEmpty()
		&& awaitingGenerated.first().intValue() <= announcedIndex - GENERATED_GRACE) {
	    final Integer stale = awaitingGenerated.pollFirst();
	    reportGap("vl" + stale + " was announced on PLAN_TRAIN but never sent a TRAIN_STATE"
		    + " the probe saw, " + GENERATED_GRACE + " trains later. The trace is"
		    + " INCOMPLETE for it.");
	}
    }

    /**
     * @param name an agent name
     * @return the index of {@code vl<n>}, or -1 if the name is not one
     */
    private static int trainIndex(String name) {
	if (name == null || !name.startsWith("vl") || name.length() < 3) return -1;
	for (int i = 2; i < name.length(); i++) {
	    if (name.charAt(i) < '0' || name.charAt(i) > '9') return -1;
	}
	try {
	    return Integer.parseInt(name.substring(2));
	} catch (NumberFormatException e) {
	    return -1;                          // an index wider than an int; not ours
	}
    }

    // ---------------------------------------------------------------- failure

    /**
     * A gap in the trace that is nobody's throwable. Surfaced exactly like a rendering fault,
     * because the consequence is identical — the trace is incomplete and the run must not be
     * recordable.
     */
    private static void reportGap(String reason) {
	report("TRACE GAP: " + reason, new IllegalStateException("trace gap: " + reason));
    }

    /** An observation threw. Report it and carry on. */
    private void failed(ACLMessage acl, Throwable t) {
	String ontology;
	try {
	    ontology = acl.getOntology();
	} catch (Throwable ignored) {
	    ontology = "<unknown>";
	}
	report("the trace probe failed while rendering a message with ontology '" + ontology
		+ "'. The canonical trace is INCOMPLETE from this point on.", t);
    }

    /**
     * Surface a probe fault on stderr in the one shape that cannot be mistaken for trace and
     * cannot be silently normalized away.
     * <p>
     * The {@code !!! } line is a diagnostic — the parity harness drops it before comparing, which
     * is exactly right for a human-readable explanation. The stack trace under it is printed with
     * the JVM's own uncaught-exception header, {@code Exception in thread "…"}, which is one of the
     * signatures the harness scans the <b>raw</b> stream for before it normalizes anything
     * ({@code docs/parity-harness.md} §4). That combination is deliberate: the explanation is
     * readable, and the run can never be recorded as a golden or pass a scenario.
     * <p>
     * <b>Rethrowing is not the alternative it was on Cybele.</b> There, a rethrow produced the
     * kernel's own scanned banner and cost only this line. Under JADE it would kill the probe
     * agent outright, so every later message of the run would be missing too — the same failure,
     * escalated from "a hole" to "a truncation".
     */
    private static void report(String reason, Throwable t) {
	final int n = FAILURES.incrementAndGet();
	if (n > MAX_FAILURE_REPORTS) return;
	synchronized (System.err) {
	    // Deliberately NOT preceded by System.out.flush(). Ordering against stdout would be
	    // nice; a report that cannot be written is not. Measured on the Cybele probe: with
	    // stdout blocked -- a full pipe, which is how a probe gets far enough behind to lose a
	    // message in the first place -- the flush blocks inside this method and the report never
	    // reaches stderr, leaving only the header. stderr is unbuffered and the harness scans
	    // raw lines for signatures wherever they land, so the ordering was worth nothing anyway.
	    System.err.println("!!! PROBE FAILURE (" + n + "): " + reason);
	    System.err.print("Exception in thread \"" + Thread.currentThread().getName() + "\" ");
	    t.printStackTrace(System.err);
	    if (n == MAX_FAILURE_REPORTS) {
		System.err.println("!!! PROBE FAILURE: further reports suppressed after "
			+ MAX_FAILURE_REPORTS + ".");
	    }
	    System.err.flush();
	}
    }
}
