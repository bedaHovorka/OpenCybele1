/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.jade;

import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.Party;
import jade.lang.acl.MessageTemplate;

import java.util.List;

/**
 * The fifteen {@code MessageTemplate}s, one per channel, plus the per-agent sets and the
 * catch-all that proves nothing is left in the queue.
 * <p>
 * <strong>Why this matters more than it looks.</strong> A JADE agent has <em>one</em> message
 * queue. {@code receive(template)} consumes only matching messages and a message matching no
 * active template sits there forever; {@code Behaviour.block()} blocks the whole agent until
 * any message arrives, not just that behaviour. A {@code Station} has five inbound channels and
 * a {@code RoadAgent} five, so an overlapping or incomplete template set is the most likely
 * source of a JADE-only hang — a failure mode the Cybele baseline structurally cannot have,
 * because every channel there has its own handler method.
 * <p>
 * <strong>Disjointness is structural, not argued.</strong> Each template is
 * {@code and(MatchOntology(channel.id()), MatchPerformative(...))}. An {@code ACLMessage} has
 * exactly one {@code :ontology} slot and the fifteen ids are distinct strings, so two templates
 * can never both match. Performative alone would not be enough and it is worth knowing why:
 * <strong>every</strong> collision lands inside a single agent, and there are four of them —
 * three among the seven {@code inform} channels ({@code LEAVE} against {@code PATH_FIND_REPLY}
 * at a station, {@code ENTER_REPLY} against {@code TRAVEL_END} at a train, and a three-way
 * {@code STATION_INFO}/{@code ROAD_STATE}/{@code TRAIN_STATE} at {@code Main}) plus one among
 * the {@code request}s ({@code ENTER} against {@code TRAVEL_START} at a track). The full table
 * is {@code docs/message-ontology.md} §7. The performative is kept in the template
 * anyway, so that a message with the right channel and the wrong act is rejected loudly by
 * {@link #unexpected(Party)} instead of being silently accepted.
 */
public final class Templates {

    private Templates() {
    }

    /**
     * The template for one channel.
     *
     * @param channel the channel
     * @return {@code ontology AND performative}
     */
    public static MessageTemplate of(Channel channel) {
        return MessageTemplate.and(
                MessageTemplate.MatchOntology(RailwayOntology.ontology(channel)),
                MessageTemplate.MatchPerformative(RailwayOntology.performative(channel)));
    }

    /**
     * The union of several channels' templates.
     *
     * @param channels at least one channel
     * @return a template matching exactly those channels
     */
    public static MessageTemplate anyOf(List<Channel> channels) {
        if (channels.isEmpty()) {
            throw new IllegalArgumentException("no channels");
        }
        MessageTemplate result = of(channels.get(0));
        for (int i = 1; i < channels.size(); i++) {
            result = MessageTemplate.or(result, of(channels.get(i)));
        }
        return result;
    }

    /**
     * Everything an agent of this kind is supposed to receive.
     *
     * @param self {@link Party#TRAIN}, {@link Party#STATION}, {@link Party#ROAD} or
     *             {@link Party#MAIN}
     * @return the union of its inbound channels' templates
     */
    public static MessageTemplate inbound(Party self) {
        return anyOf(Channel.inboundFor(self));
    }

    /**
     * The complement of {@link #inbound(Party)} — anything this agent is not expecting.
     * <p>
     * A port should give every agent a lowest-priority {@code CyclicBehaviour} on this template
     * that reports what it caught, rather than leaving an unmatched message to rot in the queue.
     * With a correct port it never fires; when it does, it names the bug immediately instead of
     * presenting it as a hang. #38's "the queue drains" assertion is checkable against exactly
     * this.
     *
     * @param self the agent kind
     * @return a template matching every message the agent has no behaviour for
     */
    public static MessageTemplate unexpected(Party self) {
        return MessageTemplate.not(inbound(self));
    }
}
