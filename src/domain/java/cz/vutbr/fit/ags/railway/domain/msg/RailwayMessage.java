/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

import java.io.Serializable;
import java.util.List;

/**
 * One message of the railway ontology: a <strong>named record</strong> replacing the baseline's
 * untyped positional {@code Serializable[]}.
 * <p>
 * <strong>Why named, and what it fixes.</strong> {@code Train.requestEnterToObject} always sends
 * {@code {name, position, to}} on {@code ENTER.<obj>}, but {@code Station.enter} reads slots
 * {@code [0]} and {@code [2]} while {@code RoadAgent.enter} reads slots {@code [0]} and
 * {@code [1]}. Nothing in the baseline states that; it is discoverable only by reading three
 * files. Here the sender builds one {@link EnterRequest} and each receiver names the fields it
 * wants. Same bytes on the wire, same behaviour, no positional coupling.
 * <p>
 * <strong>Every implementation is an immutable {@code record}, and that is a decision.</strong>
 * The baseline runs {@code Local;NoSerialization}, so a payload crosses a channel <em>by
 * reference</em>: the single {@code Station.Info} instance is shipped and then mutated in place
 * by the station that sent it ({@code docs/INVENTORY.md} SEM-05, DEF-13). JADE gives snapshot
 * semantics instead, and #27 pins the wire form as a <em>value snapshot</em>. Immutability makes
 * that structural rather than a convention someone has to remember. The observable consequence —
 * {@code STATION_INFO}'s {@code occupied} — is already a run-varying value the normalizer must
 * project ({@code docs/trace-format.md}, "What the payload of CH-10 is and is not"), so pinning
 * it costs nothing that was contractual.
 * <p>
 * <strong>Nothing here validates anything.</strong> No null checks, no range checks, no
 * normalisation. A negative {@code expected}, a {@code null} {@code position}, a {@code next}
 * that is {@code null} because the train has arrived, a {@code road} nobody reads — all of them
 * are behaviour the port must reproduce, not input to sanitise (issue #27, constraint 4).
 */
public sealed interface RailwayMessage extends Serializable
        permits VoteRequest, VoteResult, EnterRequest, LeaveNotice, StartCommand, EnterReply,
                TravelEnd, TravelStart, PathFindReply, StationInfo, RoadStateReport, TrainState,
                PlanTrain, Vote, PathFindRequest {

    /** @return the channel this message belongs to */
    Channel channel();

    /**
     * The payload values in <strong>trace field-7 order</strong>, unescaped, {@code null} where
     * the baseline sends a {@code null}. Positionally aligned with
     * {@link Channel#payloadKeys()} — see there for why that is not the same as the baseline's
     * {@code Serializable[]} slot count on CH-10 and CH-11.
     *
     * @return the raw values
     */
    List<String> values();

    /**
     * Field 7 of the canonical parity trace: {@code key=value,key=value} in field-7 key order, with
     * {@code %}, {@code |}, LF and CR percent-escaped. See {@code docs/trace-format.md}.
     *
     * @return the rendered payload
     */
    default String payload() {
        return Payloads.render(channel(), values());
    }

    /**
     * The subject carried inside the payload, for the eight channels whose
     * {@link Channel#subject()} is {@link Subject#FROM_PAYLOAD}.
     *
     * @return the subject, or {@code null} when this channel takes it from an endpoint instead
     */
    default String subjectFromPayload() {
        return null;
    }

    /**
     * Field 1 of the canonical parity trace — the <em>subject</em>, the entity the line is
     * about. Computed from the channel identity and the message, never from where a port
     * happens to hook the send, which is what lets a JADE {@code Behaviour} logging at send
     * time and a Jason {@code AgArch} logging at receive time agree byte for byte.
     *
     * @param from the sending agent's local name
     * @param to   the addressed agent's local name
     * @return the subject
     */
    default String subject(String from, String to) {
        return switch (channel().subject()) {
            case FROM_PAYLOAD -> subjectFromPayload();
            case FROM_SENDER -> from;
            case FROM_RECEIVER -> to;
        };
    }
}
