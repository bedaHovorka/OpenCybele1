/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

import java.util.List;

/**
 * The fifteen Cybele channels of this application, as data.
 * <p>
 * One constant per {@code docs/INVENTORY.md} channel id, in that document's order, carrying
 * everything a port needs to reproduce the message faithfully: the Cybele channel-name
 * expression, the trace event token, where the trace's subject field comes from, the payload
 * key names in trace field-7 order, the two endpoint kinds, and the FIPA performative #27 assigns.
 * <p>
 * <strong>Every channel has exactly one <em>application</em> subscriber.</strong> Thirteen are
 * strictly 1&rarr;1; {@link #VOTE} and {@link #PATH_FIND} are many-senders-to-one-receiver.
 * Despite the {@code Activity.sendAll} API name there is no fan-out among the agents, so the
 * faithful JADE mapping for all fifteen is a plain {@code ACLMessage} addressed to
 * {@code new AID(localName, AID.ISLOCALNAME)} — AMS white pages, no DF.
 * <p>
 * The qualifier is not padding: with {@code sim.trace.enabled=true}, {@code TraceProbe} opens a
 * <em>second</em> handler on every one of the fifteen. That is not a counterexample to the
 * unicast mapping — it is the mechanism JADE topics replace, because Cybele lets a second
 * handler attach to a channel and JADE does not. See {@code docs/message-ontology.md} §1 and §6.
 * <p>
 * This class is deliberately in the framework-free {@code domain} source set: it is pure data,
 * it is what branch {@code jason} (#46) needs just as much as the JADE port does, and it must
 * never depend on {@code jade.lang.acl}.
 */
public enum Channel {

    /** CH-01 — {@code Planning} opens the election on one path member. */
    VOTE_REQUEST("CH-01", "VOTE_REQUEST.", true, "VOTE_REQUEST",
            Subject.FROM_PAYLOAD, List.of("train", "expected"),
            Party.MAIN, Party.STATIC_OBJECT, Performative.CFP),

    /** CH-02 — the agreed departure instant, sent to every path member, winner or not. */
    VOTE_RESULT("CH-02", "VOTE_RESULT.", true, "VOTE_RESULT",
            Subject.FROM_PAYLOAD, List.of("train", "planned"),
            Party.MAIN, Party.STATIC_OBJECT, Performative.ACCEPT_PROPOSAL),

    /** CH-03 — a train asks a station or a track to admit it. Read asymmetrically; see {@link EnterRequest}. */
    ENTER("CH-03", "ENTER.", true, "ENTER",
            Subject.FROM_PAYLOAD, List.of("train", "position", "target"),
            Party.TRAIN, Party.STATIC_OBJECT, Performative.REQUEST),

    /** CH-04 — a train tells the object it has left it. */
    LEAVE("CH-04", "LEAVE.", true, "LEAVE",
            Subject.FROM_PAYLOAD, List.of("train"),
            Party.TRAIN, Party.STATIC_OBJECT, Performative.INFORM),

    /** CH-05 — {@code Planning} releases a train at its planned departure. */
    START("CH-05", "START.", true, "START",
            Subject.FROM_RECEIVER, List.of("station"),
            Party.MAIN, Party.TRAIN, Performative.REQUEST),

    /** CH-06 — admission granted; {@code next} is {@code null} when the train has arrived. */
    ENTER_REPLY("CH-06", "ENTER_REPLY.", true, "ENTER_REPLY",
            Subject.FROM_RECEIVER, List.of("object", "next"),
            Party.STATIC_OBJECT, Party.TRAIN, Performative.INFORM),

    /** CH-07 — the track's traversal timer fired. The payload is never read; see {@link TravelEnd}. */
    TRAVEL_END("CH-07", "TRAVEL_END.", true, "TRAVEL_END",
            Subject.FROM_RECEIVER, List.of("road"),
            Party.ROAD, Party.TRAIN, Performative.INFORM),

    /** CH-08 — the train asks the track to run the traversal. */
    TRAVEL_START("CH-08", "TRAVEL_START.", true, "TRAVEL_START",
            Subject.FROM_PAYLOAD, List.of("train"),
            Party.TRAIN, Party.ROAD, Performative.REQUEST),

    /** CH-09 — the answer to CH-15. */
    PATH_FIND_REPLY("CH-09", "PATH_FIND_REPLY.", true, "PATH_FIND_REPLY",
            Subject.FROM_RECEIVER, List.of("target", "direction"),
            Party.MAIN, Party.STATION, Performative.INFORM),

    /** CH-10 — unsolicited occupancy push to the GUI hub. Aliased in the baseline; see {@link StationInfo}. */
    STATION_INFO("CH-10", "STATION.INFO.", true, "STATION_INFO",
            Subject.FROM_SENDER, List.of("occupied", "capacity"),
            Party.STATION, Party.MAIN, Performative.INFORM),

    /** CH-11 — unsolicited direction push to the GUI hub. */
    ROAD_STATE("CH-11", "ROAD.STATE.", true, "ROAD_STATE",
            Subject.FROM_SENDER, List.of("state"),
            Party.ROAD, Party.MAIN, Performative.INFORM),

    /** CH-12 — the human-readable string the GUI table shows, plus the {@code KILL} sentinel. */
    TRAIN_STATE("CH-12", "TRAIN.STATE.", true, "TRAIN_STATE",
            Subject.FROM_SENDER, List.of("state"),
            Party.TRAIN, Party.MAIN, Performative.INFORM),

    /**
     * CH-13 — {@code Generator} hands a freshly created train to {@code Planning}. Both are
     * activities of the <em>same</em> agent, so trace fields 4 and 5 are both {@code Main}
     * even in a port that gives them separate agent identities.
     */
    PLAN_TRAIN("CH-13", "PLAN_TRAIN", false, "PLAN_TRAIN",
            Subject.FROM_PAYLOAD, List.of("train", "from", "to"),
            Party.MAIN, Party.MAIN, Performative.REQUEST),

    /** CH-14 — a voter's answer. Many senders, one receiver. */
    VOTE("CH-14", "VOTE", false, "VOTE",
            Subject.FROM_PAYLOAD, List.of("voter", "train", "diff"),
            Party.STATIC_OBJECT, Party.MAIN, Performative.PROPOSE),

    /**
     * CH-15 — many senders, one receiver. The channel name really does end in a dot and
     * really is used bare: {@code RailwayMainAgent.PATH_FIND = "PATH_FIND."}, opened and sent
     * to without a suffix. Reproduced, not tidied.
     */
    PATH_FIND("CH-15", "PATH_FIND.", false, "PATH_FIND",
            Subject.FROM_PAYLOAD, List.of("from", "to"),
            Party.STATION, Party.MAIN, Performative.QUERY_REF);

    /** Prefix for the per-channel identity string used as the ACL ontology slot and the topic name. */
    public static final String ID_PREFIX = "railway.";

    private final String inventoryId;
    private final String cybeleConstant;
    private final boolean suffixed;
    private final String event;
    private final Subject subject;
    private final List<String> payloadKeys;
    private final Party sender;
    private final Party receiver;
    private final Performative performative;

    Channel(String inventoryId, String cybeleConstant, boolean suffixed, String event,
            Subject subject, List<String> payloadKeys,
            Party sender, Party receiver, Performative performative) {
        this.inventoryId = inventoryId;
        this.cybeleConstant = cybeleConstant;
        this.suffixed = suffixed;
        this.event = event;
        this.subject = subject;
        this.payloadKeys = payloadKeys;
        this.sender = sender;
        this.receiver = receiver;
        this.performative = performative;
    }

    /** @return the {@code docs/INVENTORY.md} channel id, {@code CH-01}..{@code CH-15} */
    public String inventoryId() {
        return inventoryId;
    }

    /** @return the Cybele channel-name constant verbatim, trailing dot and all */
    public String cybeleConstant() {
        return cybeleConstant;
    }

    /** @return {@code true} if the Cybele channel name has an agent name appended to the constant */
    public boolean suffixed() {
        return suffixed;
    }

    /**
     * The Cybele channel name this message would travel on.
     *
     * @param target the agent whose name is the suffix; ignored, and may be {@code null}, for
     *               the three unsuffixed channels
     * @return the channel name
     */
    public String cybeleChannelName(String target) {
        return suffixed ? cybeleConstant + target : cybeleConstant;
    }

    /** @return the trace field-3 event token */
    public String event() {
        return event;
    }

    /**
     * The per-channel-<em>constant</em> identity string. It is used twice, deliberately as one
     * value: as the {@code ACLMessage} ontology slot (which is what makes the fifteen templates
     * pairwise disjoint) and as the JADE topic name the probe registers to.
     *
     * @return e.g. {@code railway.ENTER}
     */
    public String id() {
        return ID_PREFIX + event;
    }

    /** @return where trace field 1 comes from for this channel */
    public Subject subject() {
        return subject;
    }

    /**
     * The payload key names in <strong>trace field-7 order</strong>, exactly as
     * {@code docs/trace-format.md} fixes them.
     * <p>
     * This is not always the baseline's slot count. {@link #STATION_INFO} has two keys but
     * crossed the Cybele channel as <em>one</em> slot (a {@code Station.Info} the probe read two
     * fields out of), and {@link #ROAD_STATE}'s single key is one slot holding an enum. The
     * other thirteen are one key per slot.
     *
     * @return the key names, in order
     */
    public List<String> payloadKeys() {
        return payloadKeys;
    }

    /** @return the kind of agent that sends on this channel */
    public Party sender() {
        return sender;
    }

    /** @return the kind of agent that receives on this channel — always exactly one subscriber */
    public Party receiver() {
        return receiver;
    }

    /** @return the FIPA performative #27 assigns; see {@code docs/message-ontology.md} for the justification */
    public Performative performative() {
        return performative;
    }

    /**
     * Look a channel up by its trace event token.
     *
     * @param event a field-3 token
     * @return the channel
     * @throws IllegalArgumentException if no channel carries that token
     */
    public static Channel byEvent(String event) {
        for (Channel c : values()) {
            if (c.event.equals(event)) {
                return c;
            }
        }
        throw new IllegalArgumentException("no channel with event token '" + event + "'");
    }

    /**
     * Look a channel up by its {@link #id()}.
     *
     * @param id an ontology-slot / topic-name string
     * @return the channel
     * @throws IllegalArgumentException if no channel carries that id
     */
    public static Channel byId(String id) {
        for (Channel c : values()) {
            if (c.id().equals(id)) {
                return c;
            }
        }
        throw new IllegalArgumentException("no channel with id '" + id + "'");
    }

    /**
     * The channels an agent of the given kind must be able to consume.
     *
     * @param self {@link Party#TRAIN}, {@link Party#STATION}, {@link Party#ROAD} or {@link Party#MAIN}
     * @return the inbound channels, in declaration order
     * @throws IllegalArgumentException for {@link Party#STATIC_OBJECT}, which is a channel
     *         endpoint kind and not an agent kind. Passing it used to return the four
     *         {@code StaticRailwayObject} channels — because {@code covers} is trivially true
     *         for itself — rather than the station&cup;road union a caller would expect, which
     *         is a wrong answer wearing the shape of a right one.
     */
    public static List<Channel> inboundFor(Party self) {
        if (self == Party.STATIC_OBJECT) {
            throw new IllegalArgumentException(
                    "STATIC_OBJECT is an endpoint kind, not an agent kind: ask for STATION or ROAD");
        }
        return List.of(values()).stream().filter(c -> c.receiver.covers(self)).toList();
    }
}
