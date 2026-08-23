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

import java.util.EnumMap;
import java.util.Map;

import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.jade.RailwayOntology;
import jade.core.AID;
import jade.core.messaging.TopicUtility;

/**
 * The fifteen probe topics, minted once and handed to the six {@code emit} seams (#36).
 * <p>
 * This is the JADE half of what {@code TraceProbe} was on Cybele: there, a second
 * {@code Activity.openChannel} on a channel that already had a handler was enough, and no agent
 * had to know the probe existed (INVENTORY {@code SEM-03}). JADE has no such thing — a message
 * reaches exactly the AIDs it is addressed to — so #27 decided the mechanism instead:
 * {@code Messages.build(msg, from, to, topic)} adds the channel's <b>topic AID as a second
 * receiver</b>, and an agent registered to that topic gets a copy of a message it was never
 * addressed to. See {@code docs/message-ontology.md} §6.
 *
 * <h2>Per channel <em>constant</em>, and minted before the platform exists</h2>
 *
 * Fifteen topics for the whole run, whatever the topology and however many trains are generated —
 * that is #27's granularity decision, and it is what lets #36's probe drop the Cybele probe's
 * {@code sim.trace.trainLookahead} guess entirely: {@code START}, {@code ENTER_REPLY},
 * {@code TRAVEL_END} and {@code TRAIN_STATE} are one topic each, not one per train.
 * <p>
 * {@code jade.core.messaging.TopicUtility.createTopic} is a <b>static</b> factory —
 * {@code new AID(name + "@TOPIC_", ISGUID)} — so a topic AID needs neither a container nor a
 * {@code TopicManagementHelper} to exist. Only {@code register} does, and only the probe calls
 * that. The table below is therefore built at class-initialisation time and is correct before
 * {@code jade.core.Runtime} has booted anything, which is what lets every agent's {@code emit}
 * read it without a per-send service lookup.
 *
 * <h2>Off by default, exactly as the Cybele probe was</h2>
 *
 * {@link #of(Channel)} returns {@code null} until {@link #enable()} is called, and
 * {@code Messages.build(..., null)} adds no second receiver at all. So with
 * {@code sim.trace.enabled=false} — the default — a message's {@code :receiver} set is
 * byte-identical to what it would be if this class did not exist. {@code Main} enables it once,
 * on the main thread, before the probe agent is created and therefore before any agent can send.
 */
public final class TraceTopics {

    /**
     * The fifteen topic AIDs, by channel. An {@code EnumMap} built once and never written again,
     * so publication is safe without synchronisation: the array is fully constructed before the
     * class-initialisation barrier every reader crosses.
     */
    private static final Map<Channel, AID> TOPICS = new EnumMap<Channel, AID>(Channel.class);

    static {
        for (Channel channel : Channel.values()) {
            TOPICS.put(channel, TopicUtility.createTopic(RailwayOntology.topicName(channel)));
        }
    }

    /**
     * Whether sends carry the probe copy. {@code volatile} because {@code Main} writes it on the
     * main thread and every agent thread reads it; written exactly once, before any agent exists.
     */
    private static volatile boolean enabled;

    private TraceTopics() { /* no instances */ }

    /**
     * Start copying every send to its channel's topic. Called from {@code Main} when
     * {@code sim.trace.enabled=true}, before the probe agent is created.
     */
    public static void enable() {
        enabled = true;
    }

    /** @return whether {@link #enable()} has been called */
    public static boolean isEnabled() {
        return enabled;
    }

    /**
     * The second receiver an {@code emit} seam should pass to
     * {@code Messages.build(message, from, to, topic)}.
     *
     * @param channel the channel being sent on
     * @return the channel's topic AID, or {@code null} when tracing is off — which
     *         {@code Messages.build} treats as "no second receiver"
     */
    public static AID of(Channel channel) {
        return enabled ? TOPICS.get(channel) : null;
    }

    /**
     * The topic AID regardless of {@link #isEnabled()}. The probe registers with <em>this</em>
     * rather than minting its own, so that the name a sender addresses and the name a subscriber
     * registers cannot drift apart.
     *
     * @param channel the channel
     * @return the topic AID
     */
    public static AID topic(Channel channel) {
        return TOPICS.get(channel);
    }
}
