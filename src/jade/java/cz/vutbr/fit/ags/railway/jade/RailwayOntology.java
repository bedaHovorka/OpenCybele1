/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.jade;

import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import jade.core.AID;
import jade.core.messaging.TopicManagementHelper;
import jade.lang.acl.ACLMessage;

/**
 * The JADE half of the railway message ontology (#27): the three slot values a
 * {@link Channel} contributes to an {@code ACLMessage}, and the topic the probe listens on.
 * <p>
 * <strong>This is not a {@code jade.content.onto.Ontology}.</strong> The content of every
 * message is a Java object — one of the {@code domain.msg} records — carried by
 * {@code setContentObject}, not an SL expression built through a {@code ContentManager}. Two
 * reasons, both about the port being a port:
 * <ul>
 *   <li>The baseline's payloads are Java objects passed by reference between agents in one JVM.
 *       Java-serialized records are the nearest faithful form; an SL string form would be a
 *       redesign, and a redesign is not measurable against a frozen golden.</li>
 *   <li>A {@code jade.content} ontology needs concept/predicate/agent-action schemas, which
 *       would force a FIPA-shaped reading of protocols this application does not have (see
 *       {@code docs/message-ontology.md} on the missing {@code refuse}, {@code reject-proposal}
 *       and deadline legs). The mapping would stop being reversible.</li>
 * </ul>
 * The word "ontology" is used here in the plain sense the issue uses it: a fixed vocabulary of
 * message types with a pinned schema per type.
 * <p>
 * <strong>The {@code :ontology} slot carries the channel identity.</strong> One string per
 * channel <em>constant</em>, {@link Channel#id()}, e.g. {@code railway.ENTER}. That is what makes
 * the fifteen templates pairwise disjoint — a message has exactly one ontology slot, so
 * {@code MatchOntology(a)} and {@code MatchOntology(b)} cannot both hold for {@code a != b} —
 * and it is the only slot with room for it: {@code :protocol} would collide with FIPA
 * interaction-protocol names and with JADE's protocol behaviours, {@code :conversation-id} is
 * per conversation rather than per type, and a user-defined slot has no first-class
 * {@code MessageTemplate} matcher.
 */
public final class RailwayOntology {

    /** The topic name and the ACL ontology slot are the same string, on purpose. */
    private RailwayOntology() {
    }

    /**
     * The {@code :ontology} slot value for a channel.
     *
     * @param channel the channel
     * @return e.g. {@code railway.ENTER}
     */
    public static String ontology(Channel channel) {
        return channel.id();
    }

    /**
     * The JADE {@code int} performative for a channel, resolved from the framework-free
     * {@link cz.vutbr.fit.ags.railway.domain.msg.Performative#fipaName()} rather than from a
     * hard-coded table, so the two cannot drift.
     *
     * @param channel the channel
     * @return the {@code ACLMessage} performative constant
     */
    public static int performative(Channel channel) {
        return ACLMessage.getInteger(channel.performative().fipaName());
    }

    /**
     * The topic name for a channel. <strong>Per channel constant, never per channel
     * instance</strong> — fifteen topics for the whole run, fixed at boot, whatever the
     * topology and however many trains are generated. See {@code docs/message-ontology.md},
     * "Topic granularity".
     *
     * @param channel the channel
     * @return the topic name, identical to {@link #ontology(Channel)}
     */
    public static String topicName(Channel channel) {
        return channel.id();
    }

    /**
     * Mint the topic AID for a channel.
     *
     * @param helper  the container's {@link TopicManagementHelper}, obtained from
     *                {@code Agent.getHelper(TopicManagementHelper.SERVICE_NAME)} with
     *                {@code jade.core.messaging.TopicManagementService} in the profile's
     *                {@code services} list
     * @param channel the channel
     * @return the topic AID
     */
    public static AID topic(TopicManagementHelper helper, Channel channel) {
        return helper.createTopic(topicName(channel));
    }

    /**
     * Which channel an incoming message belongs to.
     *
     * @param message a message built by {@link Messages#build}
     * @return the channel
     * @throws IllegalArgumentException if the ontology slot is absent or unknown, which means
     *                                  the message did not come from this ontology
     */
    public static Channel channelOf(ACLMessage message) {
        String slot = message.getOntology();
        if (slot == null) {
            throw new IllegalArgumentException("message has no :ontology slot: " + message);
        }
        return Channel.byId(slot);
    }
}
