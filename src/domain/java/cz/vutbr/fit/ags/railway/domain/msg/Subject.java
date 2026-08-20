/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

/**
 * Where field 1 of the canonical parity trace — the <em>subject</em>, "the entity this line is
 * about" ({@code docs/trace-format.md}) — is read from, for a given channel.
 * <p>
 * This enum is the whole reason the topic-granularity decision in {@code docs/message-ontology.md}
 * comes out the way it does. Every one of the three sources below is available on a plain
 * {@code ACLMessage}: the content object, {@code getSender()}, and the non-topic receiver AID.
 * None of them needs the topic to be named after the channel <em>instance</em>, so per-instance
 * topics buy the probe nothing it does not already have.
 */
public enum Subject {
    /**
     * The subject is a field of the payload itself. Eight channels: CH-01, CH-02, CH-03,
     * CH-04, CH-08, CH-13, CH-14, CH-15.
     */
    FROM_PAYLOAD,
    /**
     * The subject is the sending agent. Three channels — CH-10 {@code STATION_INFO},
     * CH-11 {@code ROAD_STATE}, CH-12 {@code TRAIN_STATE} — all of which push their own
     * state to {@code Main}.
     */
    FROM_SENDER,
    /**
     * The subject is the addressed agent, i.e. the {@code <name>} suffix of the Cybele channel.
     * Four channels — CH-05 {@code START}, CH-06 {@code ENTER_REPLY}, CH-07 {@code TRAVEL_END}
     * (all addressed to a train) and CH-09 {@code PATH_FIND_REPLY} (addressed to a station).
     */
    FROM_RECEIVER
}
