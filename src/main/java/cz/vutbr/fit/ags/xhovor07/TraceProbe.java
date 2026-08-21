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
import java.util.HashSet;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import cybele.kernel.Activity;
import cybele.kernel.Cybele;
import cybele.kernel.CybeleEvent;
import cybele.kernel.Handler;
import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.RoadDirection;
import cz.vutbr.fit.ags.railway.domain.msg.StationInfo;

/**
 * The parity trace probe (issue #20): a passive Cybele agent that subscribes to all
 * fifteen application channels and appends one canonical line per message to
 * {@code System.out}.
 *
 * <h2>Why it exists</h2>
 *
 * The whole application prints two lines — {@code "<train> in <station> at <departure>"}
 * from {@link Planning} and {@code "<train> started"} from {@link Train}. Everything else
 * it does reaches the Swing canvas through the hub's listener list ({@code java.util.Observable}
 * until #33 replaced it with {@code RailwayView.Listener}) and never touches
 * a stream, so the behaviour that has to be preserved by the JADE (#36) and Jason (#43)
 * ports is, today, unobservable. This probe is what makes it observable.
 *
 * <h2>The contract</h2>
 *
 * One line per observed message:
 *
 * <pre>agent|tick|event|from|to|performative|payload</pre>
 *
 * The full field semantics, the per-channel table and the escaping rules are
 * {@code docs/trace-format.md}. That document, not this class, is the contract the ports
 * are held to. Two rules matter enough to repeat here:
 *
 * <ul>
 * <li>{@code tick} is {@link Cybele#getTime(String)} on {@link RailwayMainAgent#CLOCK_ID},
 *     never wall time, so the field keeps its meaning on a framework with a different
 *     scheduler.</li>
 * <li>{@code performative} is the literal {@code -} here and on every OpenCybele branch:
 *     the baseline has no performatives, no conversation ids and no reply-to — the only
 *     routing information in the system is the channel-name string (INVENTORY §4). The
 *     channel → ACLMessage mapping belongs to #27, and this field is deliberately empty
 *     so that recording it cannot prejudge that decision.</li>
 * </ul>
 *
 * <h2>Additive only</h2>
 *
 * Nothing in this class is reachable from any existing agent, and no existing agent knows
 * it exists. It is created from {@code Main} only when {@code sim.trace.enabled=true}
 * (default {@code false}, so {@code ./gradlew run} is unchanged), and it only ever
 * <em>subscribes</em>: it sends nothing, it opens no timers, it touches no application
 * state. It rests on INVENTORY {@code SEM-03} — a Cybele channel is genuine multicast, so
 * a second subscriber on a channel that already has one costs the first subscriber
 * nothing. The application's one-subscriber-per-channel property is its own choice, not a
 * kernel limit.
 *
 * <h2>Three hazards this class exists to get right</h2>
 *
 * <h3>1. The aliased payload</h3>
 *
 * The kernel runs {@code Local;NoSerialization} ({@code cybele.prop}), so a payload
 * crosses a channel <b>by reference</b> (INVENTORY {@code SEM-05}). {@code Station.Info}
 * was a non-static inner class of the very {@link Station} that kept mutating it — from
 * {@code Station.enter} and {@code Station.leave}, on the station's own thread — so what
 * arrives here is a live alias, not a message. Every handler below therefore reads the
 * mutable fields into locals as its <em>first</em> statement and formats from those. It is
 * the earliest point in this JVM at which the values can be captured; it is not an
 * instant-of-send snapshot, and no probe that does not modify {@link Station} can give
 * one. See {@code docs/trace-format.md}, "What the payload of CH-10 is and is not".
 *
 * <h3>2. Per-train channels, which do not exist yet</h3>
 *
 * Four of the fifteen channels are per train: {@code START.<train>},
 * {@code ENTER_REPLY.<train>}, {@code TRAVEL_END.<train>} and
 * {@code TRAIN.STATE.<train>}. Reacting to a train's first message is already too late —
 * {@link Train}'s constructor opens its channels and immediately sends
 * {@code TRAIN.STATE.<train>} — so this probe cannot subscribe on demand. It does not have
 * to: train names are {@code "vl" + index} with {@code index} starting at 0 and
 * incrementing by one per generated train ({@link Generator}), so the names are known in
 * advance. The probe pre-opens a sliding look-ahead window of {@code
 * sim.trace.trainLookahead} train name slots and extends it every time it observes a
 * train, staying permanently ahead of the generator. Opening a channel name that never
 * carries a message is inert (measured: {@code docs/probes/ExpH.java}, H3), and so is
 * opening it <em>before</em> the owning agent opens the same name (ExpH, H1).
 *
 * <h3>3. A probe that fails silently</h3>
 *
 * A throwable inside a Cybele handler is wrapped in an {@code InvocationTargetException},
 * printed to stderr and swallowed, with the exit status unchanged
 * ({@code docs/assertion-triage.md}). A probe that died that way would leave a truncated
 * trace that looks exactly like a short, clean run — and, in record mode, would be frozen
 * into a golden. So every handler catches {@link Throwable} and reports it in the one
 * shape the parity harness scans for on the <em>raw</em> stream before it normalizes
 * anything ({@code docs/parity-harness.md} §4). A run whose probe failed can therefore
 * never be recorded as a golden, even though its exit status is 0.
 *
 * @author Bedrich Hovorka
 */
public class TraceProbe implements Handler {
    private static final long serialVersionUID = 1L;

    /**
     * The probe's Cybele agent name. Deliberately not a legal station ({@code st*}),
     * track ({@code tr*}) or train ({@code vl*}) name, and not {@code Main}.
     */
    public static final String PROBE_AGENT_NAME = "Probe";

    /** The literal in the {@code performative} field on this branch. See the class Javadoc. */
    public static final String NO_PERFORMATIVE = "-";

    /** The field separator. Any occurrence inside a value is percent-escaped. */
    static final char SEP = '|';

    /** How a {@code null} inside a payload is rendered. No agent is ever called this. */
    static final String NULL = "null";

    /**
     * Counted down when every static channel is subscribed. {@code Main} waits on it
     * before it creates {@link RailwayMainAgent}, so no message can be sent on a channel
     * this probe has not yet opened. Static because the probe is constructed by the kernel
     * on its own thread and {@code Main} has no reference to it — the same mechanism the
     * {@code docs/probes} experiments use.
     */
    private static final CountDownLatch READY = new CountDownLatch(1);

    /** Non-zero once a handler has caught a throwable; see {@link #probeFailures()}. */
    private static final AtomicInteger FAILURES = new AtomicInteger();

    /** Cap on failure reports, so a systematic fault cannot bury the trace it broke. */
    private static final int MAX_FAILURE_REPORTS = 20;

    /** How the first {@code TRAIN_STATE} of every train ends; see {@link #checkFirstState}. */
    static final String GENERATED_SUFFIX = " generated";

    /**
     * How many later trains may be announced before a train with no {@code TRAIN_STATE} is
     * called a gap. See {@link #checkGenerated}.
     */
    static final int GENERATED_GRACE = 8;

    private final int lookahead;
    /** Train-name slots already subscribed. Only ever touched on the probe's own activity. */
    private final Set<String> subscribedTrains = new HashSet<String>();
    /** Trains for which at least one {@code TRAIN_STATE} has been observed. */
    private final Set<String> firstStateSeen = new HashSet<String>();
    /** Indices announced on {@code PLAN_TRAIN} with no {@code TRAIN_STATE} yet. */
    private final TreeSet<Integer> awaitingGenerated = new TreeSet<Integer>();
    /** Highest {@code vl<n>} index whose channels are open. -1 before the first slot. */
    private int windowTop = -1;

    /**
     * Subscribes to every channel that can be named ahead of time. Runs on the probe
     * agent's own thread; {@code Main} blocks in {@link #awaitReady()} until it returns.
     */
    public TraceProbe() {
        final ScenarioConfig config = ScenarioConfig.get();
        this.lookahead = config.getTraceTrainLookahead();

        // CH-13/CH-14/CH-15 — the three bare, global channel names. PATH_FIND's constant
        // carries a trailing dot that nothing is ever appended to (INVENTORY CH-15); the
        // literal is what has to be opened, so the constant is used as-is.
        Activity.openChannel(Planning.PLAN_TRAIN, "onPlanTrain", this);
        Activity.openChannel(Planning.VOTE, "onVote", this);
        Activity.openChannel(RailwayMainAgent.PATH_FIND, "onPathFind", this);

        // CH-01..CH-04 — one name per static object, station and track alike.
        for (String obj : staticObjects(config)) {
            Activity.openChannel(StaticRailwayObject.VOTE_REQUEST + obj, "onVoteRequest", this);
            Activity.openChannel(StaticRailwayObject.VOTE_RESULT + obj, "onVoteResult", this);
            Activity.openChannel(StaticRailwayObject.ENTER + obj, "onEnter", this);
            Activity.openChannel(StaticRailwayObject.LEAVE + obj, "onLeave", this);
        }
        // CH-09, CH-10 — per station.
        for (String station : config.getStationNames()) {
            Activity.openChannel(Channel.PATH_FIND_REPLY.cybeleChannelName(station), "onPathFindReply", this);
            Activity.openChannel(RailwayMainAgent.CHANNEL_STATION_INFO + station, "onStationInfo", this);
        }
        // CH-08, CH-11 — per track.
        for (String road : config.getRoadNames()) {
            Activity.openChannel(RoadAgent.TRAVEL_START + road, "onTravelStart", this);
            Activity.openChannel(RailwayMainAgent.CHANNEL_ROAD_STATE + road, "onRoadState", this);
        }
        // CH-05, CH-06, CH-07, CH-12 — the first look-ahead window of train slots.
        extendWindowTo(lookahead - 1);

        System.err.println("--- trace probe: " + PROBE_AGENT_NAME + " subscribed to 15 channel"
                + " families, " + staticChannelCount(config) + " static names plus "
                + lookahead + " train slots (" + 4 * lookahead + " names). Format:"
                + " agent|tick|event|from|to|performative|payload; see docs/trace-format.md.");
        READY.countDown();
    }

    // ---------------------------------------------------------------- lifecycle

    /**
     * Block until the probe has opened every static channel.
     * <p>
     * Called from {@code Main} between {@code Cybele.createAgent(Probe)} and
     * {@code Cybele.createAgent(Main)}. Without the barrier the two {@code createAgent}
     * calls race: {@code Cybele.createAgent} is asynchronous, so the first
     * {@code STATION.INFO} of the run — sent from {@link Station}'s constructor — can be
     * gone before the probe has subscribed, and a trace missing its first lines is a
     * trace that diffs against a golden for no behavioural reason.
     *
     * @throws IllegalStateException if the probe agent never finished constructing, which
     *         means the run has no trace and must not be allowed to look like one
     */
    public static void awaitReady() {
        final long timeoutMs = 60000;
        try {
            if (!READY.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                throw new IllegalStateException("the trace probe did not subscribe within "
                        + timeoutMs + " ms. Its constructor either threw (Cybele prints"
                        + " agent-construction throwables to stderr and exits 255) or the"
                        + " kernel never created it. Refusing to start the simulation:"
                        + " with " + ScenarioConfig.KEY_TRACE_ENABLED + "=true a run"
                        + " without a trace is a failed run, not a quiet one.");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the trace probe", e);
        }
    }

    /**
     * @return how many times a probe handler has caught a throwable. Non-zero means the
     *         trace has holes in it.
     */
    public static int probeFailures() {
        return FAILURES.get();
    }

    // ---------------------------------------------------------------- handlers
    //
    // One method per channel, rather than one method demultiplexing on ev.getTag(): the
    // method name IS the channel identity, so a payload can never be decoded against the
    // wrong schema. Each body's first statement copies every mutable value it needs out of
    // the payload — see "The aliased payload" in the class Javadoc.

    /** CH-13 {@code PLAN_TRAIN} — {@code {train, from, to}}, Generator to Planning. */
    public void onPlanTrain(CybeleEvent ev) {
        try {
            final Serializable[] m = ev.getMessage();
            final String train = str(m, 0);
            final String from = str(m, 1);
            final String to = str(m, 2);
            noteTrain(train);
            final int index = trainIndex(train);
            if (index >= 0) checkGenerated(index);
            emit(train, "PLAN_TRAIN", RailwayMainAgent.MAIN_AGENT_NAME,
                    RailwayMainAgent.MAIN_AGENT_NAME,
                    "train=" + train + ",from=" + from + ",to=" + to);
        } catch (Throwable t) { failed("PLAN_TRAIN", ev, t); }
    }

    /** CH-14 {@code VOTE} — {@code {voter, train, diff}}, any static object to Planning. */
    public void onVote(CybeleEvent ev) {
        try {
            final Serializable[] m = ev.getMessage();
            final String voter = str(m, 0);
            final String train = str(m, 1);
            final String diff = str(m, 2);
            emit(train, "VOTE", voter, RailwayMainAgent.MAIN_AGENT_NAME,
                    "voter=" + voter + ",train=" + train + ",diff=" + diff);
        } catch (Throwable t) { failed("VOTE", ev, t); }
    }

    /** CH-15 {@code PATH_FIND.} — {@code {from, to}}, any station to the main agent. */
    public void onPathFind(CybeleEvent ev) {
        try {
            final Serializable[] m = ev.getMessage();
            final String from = str(m, 0);
            final String to = str(m, 1);
            emit(from, "PATH_FIND", from, RailwayMainAgent.MAIN_AGENT_NAME,
                    "from=" + from + ",to=" + to);
        } catch (Throwable t) { failed("PATH_FIND", ev, t); }
    }

    /** CH-01 {@code VOTE_REQUEST.<obj>} — {@code {train, expectedTime}}, Planning to a voter. */
    public void onVoteRequest(CybeleEvent ev) {
        try {
            final Serializable[] m = ev.getMessage();
            final String train = str(m, 0);
            final String expected = str(m, 1);
            emit(train, "VOTE_REQUEST", RailwayMainAgent.MAIN_AGENT_NAME, owner(ev),
                    "train=" + train + ",expected=" + expected);
        } catch (Throwable t) { failed("VOTE_REQUEST", ev, t); }
    }

    /** CH-02 {@code VOTE_RESULT.<obj>} — {@code {train, plannedTime}}, Planning to a voter. */
    public void onVoteResult(CybeleEvent ev) {
        try {
            final Serializable[] m = ev.getMessage();
            final String train = str(m, 0);
            final String planned = str(m, 1);
            emit(train, "VOTE_RESULT", RailwayMainAgent.MAIN_AGENT_NAME, owner(ev),
                    "train=" + train + ",planned=" + planned);
        } catch (Throwable t) { failed("VOTE_RESULT", ev, t); }
    }

    /**
     * CH-03 {@code ENTER.<obj>} — {@code {train, currentPosition, finalTarget}}, train to
     * the object it wants to enter. {@code currentPosition} is {@code null} on a train's
     * very first {@code ENTER}. The two receivers read different slots of the same wire
     * format — a station reads {@code [0],[2]}, a track reads {@code [0],[1]} (INVENTORY
     * "Asymmetric payload read") — so all three are recorded and the asymmetry stays
     * visible to #27.
     */
    public void onEnter(CybeleEvent ev) {
        try {
            final Serializable[] m = ev.getMessage();
            final String train = str(m, 0);
            final String position = str(m, 1);
            final String target = str(m, 2);
            emit(train, "ENTER", train, owner(ev),
                    "train=" + train + ",position=" + position + ",target=" + target);
        } catch (Throwable t) { failed("ENTER", ev, t); }
    }

    /** CH-04 {@code LEAVE.<obj>} — {@code {train}}, train to the object it is leaving. */
    public void onLeave(CybeleEvent ev) {
        try {
            final Serializable[] m = ev.getMessage();
            final String train = str(m, 0);
            emit(train, "LEAVE", train, owner(ev), "train=" + train);
        } catch (Throwable t) { failed("LEAVE", ev, t); }
    }

    /** CH-05 {@code START.<train>} — {@code {station}}, Planning to a train. */
    public void onStart(CybeleEvent ev) {
        try {
            final Serializable[] m = ev.getMessage();
            final String station = str(m, 0);
            final String train = owner(ev);
            emit(train, "START", RailwayMainAgent.MAIN_AGENT_NAME, train, "station=" + station);
        } catch (Throwable t) { failed("START", ev, t); }
    }

    /**
     * CH-06 {@code ENTER_REPLY.<train>} — {@code {objectName, nextPosition}}, the admitting
     * object to the train. {@code nextPosition} is {@code null} when the train has arrived.
     */
    public void onEnterReply(CybeleEvent ev) {
        try {
            final Serializable[] m = ev.getMessage();
            final String object = str(m, 0);
            final String next = str(m, 1);
            final String train = owner(ev);
            emit(train, "ENTER_REPLY", object, train, "object=" + object + ",next=" + next);
        } catch (Throwable t) { failed("ENTER_REPLY", ev, t); }
    }

    /** CH-07 {@code TRAVEL_END.<train>} — {@code {road}}, a track to the train on it. */
    public void onTravelEnd(CybeleEvent ev) {
        try {
            final Serializable[] m = ev.getMessage();
            final String road = str(m, 0);
            final String train = owner(ev);
            emit(train, "TRAVEL_END", road, train, "road=" + road);
        } catch (Throwable t) { failed("TRAVEL_END", ev, t); }
    }

    /** CH-08 {@code TRAVEL_START.<road>} — {@code {train}}, train to the track it is on. */
    public void onTravelStart(CybeleEvent ev) {
        try {
            final Serializable[] m = ev.getMessage();
            final String train = str(m, 0);
            emit(train, "TRAVEL_START", train, owner(ev), "train=" + train);
        } catch (Throwable t) { failed("TRAVEL_START", ev, t); }
    }

    /** CH-09 {@code PATH_FIND_REPLY.<st>} — {@code {target, direction}}, main agent to a station. */
    public void onPathFindReply(CybeleEvent ev) {
        try {
            final Serializable[] m = ev.getMessage();
            final String target = str(m, 0);
            final String direction = str(m, 1);
            final String station = owner(ev);
            emit(station, "PATH_FIND_REPLY", RailwayMainAgent.MAIN_AGENT_NAME, station,
                    "target=" + target + ",direction=" + direction);
        } catch (Throwable t) { failed("PATH_FIND_REPLY", ev, t); }
    }

    /**
     * CH-10 {@code STATION.INFO.<st>} — a station to the main agent.
     * <p>
     * <b>The aliasing hazard.</b> Under {@code Local;NoSerialization} {@code message[0]} was not a
     * copy: it was the station's own live {@code Station.Info} instance, and
     * {@code Station.enter}/{@code Station.leave} kept incrementing and decrementing
     * {@code occupied} on it from the station's thread. The two field reads below are the first
     * two statements of this handler for that reason — everything downstream formats from the
     * {@code int} locals, which nothing can mutate. Reading the field later, inside the string
     * concatenation, would record whatever value the station had reached by then.
     * <p>
     * <b>#33 re-pointed the payload type and the hazard is gone with it.</b> {@code Station.Info}
     * was deleted when its last three namers were ported; the type here is now the ontology's
     * immutable {@link StationInfo} record, which is the snapshot semantics
     * {@code docs/defect-triage.md} §3.2 puts inside the contract for DEF-13. The
     * snapshot-first ordering is kept anyway — it costs nothing, and this probe is #36's to
     * rewrite, not to quietly loosen.
     */
    public void onStationInfo(CybeleEvent ev) {
        try {
            final StationInfo info = (StationInfo) ev.getMessage()[0];
            // Snapshot FIRST. Do not move these two lines.
            final int occupied = info.occupied();
            final int capacity = info.capacity();
            final String station = owner(ev);
            emit(station, "STATION_INFO", station, RailwayMainAgent.MAIN_AGENT_NAME,
                    "occupied=" + occupied + ",capacity=" + capacity);
        } catch (Throwable t) { failed("STATION_INFO", ev, t); }
    }

    /**
     * CH-11 {@code ROAD.STATE.<tr>} — a track to the main agent.
     * The payload is an enum constant, so it is immutable and carries no aliasing hazard;
     * the {@code RoadAgent} fields that <em>are</em> mutable (its queue, its timetable, the
     * train in transit) never cross a channel.
     * <p>
     * {@code RoadAgent.State} was deleted by #33 along with its last three namers; this is the
     * same three constants with the same three symbols, in the ontology package where the
     * {@code RoadStateReport} payload can reach them.
     */
    public void onRoadState(CybeleEvent ev) {
        try {
            final RoadDirection state = (RoadDirection) ev.getMessage()[0];
            final String name = (state == null) ? NULL : state.name();
            final String road = owner(ev);
            emit(road, "ROAD_STATE", road, RailwayMainAgent.MAIN_AGENT_NAME, "state=" + name);
        } catch (Throwable t) { failed("ROAD_STATE", ev, t); }
    }

    /**
     * CH-12 {@code TRAIN.STATE.<train>} — {@code {String}}, a train to the main agent.
     * The string is the human-readable state the GUI table shows, or the sentinel
     * {@code KILL} the train sends from its Cybele destructor. It is recorded verbatim
     * (escaped, never reformatted) because it is the only record of the train's own view
     * of where it is.
     */
    public void onTrainState(CybeleEvent ev) {
        try {
            final String state = str(ev.getMessage(), 0);
            final String train = owner(ev);
            noteTrain(train);
            checkFirstState(train, state);
            emit(train, "TRAIN_STATE", train, RailwayMainAgent.MAIN_AGENT_NAME, "state=" + state);
        } catch (Throwable t) { failed("TRAIN_STATE", ev, t); }
    }

    // ---------------------------------------------------------------- emission

    /**
     * Append one canonical line. {@code System.out.println(String)} is atomic per line on a
     * {@link java.io.PrintStream}, which is what keeps a probe line from interleaving with
     * the application's own two {@code println}s on the same stream.
     */
    private void emit(String agent, String event, String from, String to, String payload) {
        final StringBuilder sb = new StringBuilder(96);
        sb.append(esc(agent)).append(SEP);
        sb.append(tick()).append(SEP);
        sb.append(event).append(SEP);
        sb.append(esc(from)).append(SEP);
        sb.append(esc(to)).append(SEP);
        sb.append(NO_PERFORMATIVE).append(SEP);
        sb.append(esc(payload));
        System.out.println(sb.toString());
    }

    /**
     * The simulated clock, never wall time.
     * <p>
     * This is the clock as it reads when the probe <em>handles</em> the message, not when
     * the sender sent it. Cybele offers no send-time clock stamp on a message event:
     * {@code CybeleEvent.getClockTime()} returns {@code -1} for {@code getEventType() ==
     * MESSAGE} and is only populated for timer events (measured, {@code docs/probes/ExpH.java}
     * H2). The difference is the probe's own dispatch latency, sub-millisecond of simulated
     * time at the paces these scenarios use; {@code docs/trace-format.md} states it as a
     * known property of the format rather than hiding it.
     */
    private static long tick() {
        try {
            return Cybele.getTime(RailwayMainAgent.CLOCK_ID);
        } catch (RuntimeException e) {
            // The clock is created by RailwayMainAgent's constructor, so in principle a
            // message could be observed before it exists. Recording an obviously invalid
            // tick beats losing the line or throwing out of a handler.
            return -1;
        }
    }

    /**
     * Percent-escape the three characters that would break the line format: the field
     * separator and the two line terminators. {@code %} itself is escaped first so the
     * transformation is reversible. In practice no value in this application contains any
     * of them — the escape exists so that a future payload cannot silently corrupt a
     * golden by splitting one line into two.
     */
    static String esc(String s) {
        if (s == null) return NULL;
        if (s.indexOf('%') < 0 && s.indexOf(SEP) < 0 && s.indexOf('\n') < 0 && s.indexOf('\r') < 0) {
            return s;
        }
        final StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            final char c = s.charAt(i);
            switch (c) {
                case '%':  sb.append("%25"); break;
                case SEP:  sb.append("%7C"); break;
                case '\n': sb.append("%0A"); break;
                case '\r': sb.append("%0D"); break;
                default:   sb.append(c);
            }
        }
        return sb.toString();
    }

    /** The payload slot as a string, or {@link #NULL}. Never throws on a short array. */
    private static String str(Serializable[] m, int i) {
        if (m == null || i >= m.length || m[i] == null) return NULL;
        return String.valueOf(m[i]);
    }

    /**
     * The agent a suffixed channel name belongs to: {@code ENTER.stA} is {@code stA}. The
     * same {@code lastIndexOf('.')} rule the application itself uses
     * ({@code RailwayMainAgent.objectName}), so the probe and the receiver always agree on
     * who a message was addressed to.
     */
    private static String owner(CybeleEvent ev) {
        final String tag = ev.getTag();
        return tag.substring(tag.lastIndexOf('.') + 1);
    }

    // ---------------------------------------------------------------- train slots

    /**
     * Keep the look-ahead window ahead of the generator. Called from the two handlers that
     * fire at generation time — {@code PLAN_TRAIN} and the train's first
     * {@code TRAIN.STATE} — and only ever on the probe's own activity, which Cybele
     * dispatches serially (INVENTORY {@code SEM-04}), so no locking is needed anywhere in
     * this section.
     * <p>
     * There is deliberately <b>no</b> "is this index past the window top" check here. The
     * first version of this class had one, and it could not fire: this method extends the
     * window to {@code index + lookahead}, redefining the top relative to the index just
     * seen, so for monotonically increasing names {@code index > windowTop} is false by
     * construction. It read as a guard and asserted nothing. The real check — is the trace
     * for this train complete from its first message — is {@link #checkGenerated} and
     * {@link #checkFirstState}, which detect the failure this class can actually suffer.
     */
    private void noteTrain(String train) {
        final int index = trainIndex(train);
        if (index < 0) return;                 // not a generated train name; nothing to do
        extendWindowTo(index + lookahead);
    }

    /**
     * The gap check that works: <b>the first {@code TRAIN_STATE} of a train must be its
     * {@code generated} line.</b>
     * <p>
     * {@link Train}'s constructor sends that line before the train can do anything else, so
     * if the first one this probe sees is {@code entered to …} or {@code KILL}, the
     * subscription for {@code TRAIN.STATE.<train>} was opened too late and the opening of
     * that train's story is missing. That is a hole in the trace and the run must not be
     * recordable, however healthy its exit status.
     * <p>
     * Reproduced by blocking stdout so the probe's own activity falls behind the generator:
     * three trains lost their first {@code TRAIN_STATE} mid-run and the old window check
     * reported nothing. This one reports all three.
     *
     * @return {@code true} if this was the first {@code TRAIN_STATE} seen for the train
     */
    private boolean checkFirstState(String train, String state) {
        if (!firstStateSeen.add(train)) return false;
        awaitingGenerated.remove(Integer.valueOf(trainIndex(train)));
        if (!state.endsWith(GENERATED_SUFFIX) && !Train.KILLED.equals(state)) {
            reportGap("the first " + "TRAIN_STATE" + " observed for " + train + " is '" + state
                    + "', not its 'generated' line. That train's opening messages were sent"
                    + " before the probe subscribed, so the trace is INCOMPLETE for it."
                    + " Raise " + ScenarioConfig.KEY_TRACE_TRAIN_LOOKAHEAD + " (now "
                    + lookahead + ").");
        } else if (Train.KILLED.equals(state)) {
            reportGap("the first TRAIN_STATE observed for " + train + " is the KILL sentinel:"
                    + " every earlier message of that train is missing from the trace.");
        }
        return true;
    }

    /**
     * The second half of the same check, for the train whose {@code TRAIN_STATE} channel was
     * missed <em>entirely</em> rather than just late — {@link #checkFirstState} never runs
     * for that train, so nothing above would notice.
     * <p>
     * {@code PLAN_TRAIN} is a bare, global channel opened in this probe's constructor and can
     * therefore never be missed, so every generated train is announced on it exactly once.
     * A train announced there and still without a {@code TRAIN_STATE} once
     * {@value #GENERATED_GRACE} later trains have been announced is a train whose per-train
     * channels this probe does not have. The grace exists because the two messages race: the
     * generator sends {@code PLAN_TRAIN} after {@code Cybele.createAgent}, and the train's
     * own constructor runs on another thread. Eight trains is ~16 s of simulated time at the
     * default arrival rate and four orders of magnitude more than the observed skew.
     */
    private void checkGenerated(int announcedIndex) {
        if (!firstStateSeen.contains("vl" + announcedIndex)) {
            awaitingGenerated.add(Integer.valueOf(announcedIndex));
        }
        while (!awaitingGenerated.isEmpty()
                && awaitingGenerated.first().intValue() <= announcedIndex - GENERATED_GRACE) {
            final Integer stale = awaitingGenerated.pollFirst();
            reportGap("vl" + stale + " was announced on PLAN_TRAIN but never sent a"
                    + " TRAIN_STATE the probe saw, " + GENERATED_GRACE + " trains later."
                    + " Its START/ENTER_REPLY/TRAVEL_END/TRAIN.STATE channels were opened too"
                    + " late, so the trace is INCOMPLETE for it.");
        }
    }

    /** Open {@code START/ENTER_REPLY/TRAVEL_END/TRAIN.STATE} for every slot up to {@code top}. */
    private void extendWindowTo(int top) {
        for (int i = windowTop + 1; i <= top; i++) {
            final String train = "vl" + i;
            if (!subscribedTrains.add(train)) continue;
            Activity.openChannel(Train.START + train, "onStart", this);
            Activity.openChannel(Train.ENTER_REPLY + train, "onEnterReply", this);
            Activity.openChannel(Train.TRAVEL_END + train, "onTravelEnd", this);
            Activity.openChannel(RailwayMainAgent.CHANNEL_TRAIN_STATE + train, "onTrainState", this);
        }
        if (top > windowTop) windowTop = top;
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

    // ---------------------------------------------------------------- topology

    /**
     * Stations first, then tracks, in {@link ScenarioConfig} declaration order — the same
     * order {@link RailwayMainAgent} creates them in (#19). The probe's subscription order
     * has no behavioural effect; keeping it identical means a reader comparing the two
     * lists is comparing like with like.
     */
    private static Iterable<String> staticObjects(ScenarioConfig config) {
        final java.util.List<String> all = new java.util.ArrayList<String>();
        all.addAll(config.getStationNames());
        all.addAll(config.getRoadNames());
        return all;
    }

    private static int staticChannelCount(ScenarioConfig config) {
        final int stations = config.getStationNames().size();
        final int roads = config.getRoadNames().size();
        return 3 + 4 * (stations + roads) + 2 * stations + 2 * roads;
    }

    // ---------------------------------------------------------------- failure

    /**
     * A gap in the trace that is nobody's throwable: the probe subscribed too late for a
     * train. Surfaced exactly like a handler fault, because the consequence is identical —
     * the trace is incomplete and the run must not be recordable.
     */
    private static void reportGap(String reason) {
        report("TRACE GAP: " + reason, new IllegalStateException("trace gap: " + reason));
    }

    /**
     * A handler caught something. Report it and carry on: the remaining channels are still
     * worth recording, and the run is already marked as unrecordable by the report itself.
     */
    private void failed(String event, CybeleEvent ev, Throwable t) {
        String tag;
        try {
            tag = ev.getTag();
        } catch (Throwable ignored) {
            tag = "<unknown>";
        }
        report("the trace probe failed while handling " + event + " on channel '" + tag
                + "'. The canonical trace is INCOMPLETE from this point on.", t);
    }

    /**
     * Surface a probe fault on stderr in the one shape that cannot be mistaken for trace
     * and cannot be silently normalized away.
     * <p>
     * The {@code !!! } line is a diagnostic — the parity harness drops it before comparing,
     * which is exactly right for a human-readable explanation. The stack trace under it is
     * printed with the JVM's own uncaught-exception header, {@code Exception in thread
     * "..."}, which is one of the signatures the harness scans the <b>raw</b> stream for
     * before it normalizes anything ({@code docs/parity-harness.md} §4). That combination
     * is deliberate: the explanation is readable, and the run can never be recorded as a
     * golden or pass a scenario. Rethrowing would achieve the second half — Cybele's own
     * handler prints a scanned banner too — but it would also lose this line and leave the
     * reader to work out that the probe, and not the simulation, is what broke.
     */
    private static void report(String reason, Throwable t) {
        final int n = FAILURES.incrementAndGet();
        if (n > MAX_FAILURE_REPORTS) return;
        synchronized (System.err) {
            // Deliberately NOT preceded by System.out.flush(). Ordering against stdout would
            // be nice; a report that cannot be written is not. Measured: with stdout blocked
            // (a full pipe — how a probe gets far enough behind to lose a train in the first
            // place) the flush blocks inside this method and the report never reaches stderr,
            // leaving only the header. stderr is unbuffered and the harness scans raw lines
            // for signatures wherever they land, so the ordering was worth nothing anyway.
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
