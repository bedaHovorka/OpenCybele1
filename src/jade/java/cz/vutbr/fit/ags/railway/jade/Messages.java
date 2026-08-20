/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.jade;

import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.RailwayMessage;
import jade.core.AID;
import jade.core.messaging.TopicUtility;
import jade.lang.acl.ACLMessage;
import jade.util.leap.Iterator;

import java.io.IOException;
import java.io.UncheckedIOException;

/**
 * Builds a {@code jade.lang.acl.ACLMessage} from a railway message record, and reads one back.
 * <p>
 * <strong>Addressing is direct AID unicast, for all fifteen channels.</strong>
 * {@code new AID(localName, AID.ISLOCALNAME)}, resolved by the AMS white pages. Every Cybele
 * channel name is opened exactly once, so every channel has exactly one subscriber; the
 * {@code sendAll} in the source is an API name, not a fan-out. There is no DF anywhere in this
 * port — nothing in this application advertises or searches a capability.
 * <p>
 * <strong>Slots that are set, and why.</strong>
 * <ul>
 *   <li>{@code :performative} — #27's assignment, {@link RailwayOntology#performative}.</li>
 *   <li>{@code :ontology} — {@link Channel#id()}, the channel identity. This is what the
 *       templates match on.</li>
 *   <li>{@code :receiver} — the one named agent, plus optionally the channel's topic AID when
 *       the probe is running.</li>
 *   <li>{@code :sender} — set here so that a message is self-describing before it is sent;
 *       {@code Agent.send} sets the same value.</li>
 *   <li>{@code :conversation-id} — the trace <em>subject</em>, field 1. Redundant with what
 *       {@link RailwayMessage#subject} computes, and deliberately so: the probe reads it in one
 *       call, and a test asserts the two agree for every channel. <strong>Agents must never
 *       template on it.</strong> It is probe metadata; making a routing decision from it would
 *       be a behaviour the baseline does not have.</li>
 * </ul>
 * <strong>Slots that stay unset, and why.</strong> {@code :reply-with}/{@code :in-reply-to} —
 * the baseline has no request/reply correlation at all, and a train has at most one outstanding
 * {@code ENTER}; setting them would tempt a port into blocking receives it does not need.
 * {@code :protocol} — see {@code docs/message-ontology.md}: setting it to a FIPA protocol name
 * would invite JADE's {@code ContractNetInitiator}, which adds a deadline and a {@code refuse}
 * branch this election does not have. {@code :language}, {@code :encoding},
 * {@code :reply-by} — no content language, no deadline.
 */
public final class Messages {

    private Messages() {
    }

    /**
     * Build a unicast message.
     *
     * @param message  the payload record
     * @param sender   the sending agent's local name
     * @param receiver the addressed agent's local name — the Cybele channel's {@code <name>}
     *                 suffix, or the single subscriber for the three unsuffixed channels
     * @return the ACL message
     */
    public static ACLMessage build(RailwayMessage message, String sender, String receiver) {
        return build(message, sender, receiver, null);
    }

    /**
     * Build a unicast message, optionally copied to the channel's topic so the probe can see it.
     * <p>
     * The topic AID is a <em>second receiver</em>, not a replacement for the first: the named
     * agent is still addressed directly and still receives exactly one copy. With no agent
     * registered to the topic the extra receiver is a no-op. A port should pass {@code null}
     * when tracing is off, so that a probe-off run's {@code :receiver} set is byte-identical to
     * what it would be if the probe did not exist — the same "additive, and off by default"
     * property the Cybele {@code TraceProbe} has.
     *
     * @param message  the payload record
     * @param sender   the sending agent's local name
     * @param receiver the addressed agent's local name
     * @param topic    the channel's topic AID, or {@code null}
     * @return the ACL message
     */
    public static ACLMessage build(RailwayMessage message, String sender, String receiver, AID topic) {
        Channel channel = message.channel();
        ACLMessage acl = new ACLMessage(RailwayOntology.performative(channel));
        acl.setOntology(RailwayOntology.ontology(channel));
        acl.setSender(new AID(sender, AID.ISLOCALNAME));
        acl.addReceiver(new AID(receiver, AID.ISLOCALNAME));
        acl.setConversationId(message.subject(sender, receiver));
        try {
            acl.setContentObject(message);
        } catch (IOException e) {
            // Every record in the ontology is an immutable carrier of Strings, ints, longs and
            // one enum. There is no reachable failure here; if there is, it is a broken build,
            // not a runtime condition an agent should handle.
            throw new UncheckedIOException("cannot serialize " + channel.event(), e);
        }
        if (topic != null) {
            acl.addReceiver(topic);
        }
        return acl;
    }

    /**
     * The channel an incoming message belongs to.
     *
     * @param acl an incoming message
     * @return the channel
     */
    public static Channel channelOf(ACLMessage acl) {
        return RailwayOntology.channelOf(acl);
    }

    /**
     * The payload record carried by an incoming message.
     *
     * @param acl an incoming message
     * @return the record
     * @throws IllegalStateException if the content is not a railway message
     */
    public static RailwayMessage contentOf(ACLMessage acl) {
        try {
            Object content = acl.getContentObject();
            if (!(content instanceof RailwayMessage railway)) {
                throw new IllegalStateException("content of " + acl.getOntology()
                        + " is not a RailwayMessage: " + content);
            }
            return railway;
        } catch (jade.lang.acl.UnreadableException e) {
            throw new IllegalStateException("cannot read content of " + acl.getOntology(), e);
        }
    }

    /**
     * Trace field 4.
     *
     * @param acl an incoming message
     * @return the sender's local name
     */
    public static String senderName(ACLMessage acl) {
        return acl.getSender() == null ? null : acl.getSender().getLocalName();
    }

    /**
     * Trace field 5 — the <em>named</em> receiver, skipping the topic AID when the probe copy is
     * on. Every channel in this application has exactly one named receiver, so there is never a
     * choice to make.
     *
     * @param acl an incoming message
     * @return the addressed agent's local name, or {@code null} if there is none
     */
    public static String receiverName(ACLMessage acl) {
        Iterator it = acl.getAllReceiver();
        while (it.hasNext()) {
            AID aid = (AID) it.next();
            if (!TopicUtility.isTopic(aid)) {
                return aid.getLocalName();
            }
        }
        return null;
    }

    /**
     * Trace field 1 — the subject, computed from the channel identity and the message, exactly
     * as {@code docs/trace-format.md} fixes it. Equal to {@code acl.getConversationId()} for
     * every message this ontology builds.
     *
     * @param acl an incoming message
     * @return the subject
     */
    public static String subjectOf(ACLMessage acl) {
        return contentOf(acl).subject(senderName(acl), receiverName(acl));
    }
}
