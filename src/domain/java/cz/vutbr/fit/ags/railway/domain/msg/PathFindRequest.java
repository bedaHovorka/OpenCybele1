/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

import java.util.Arrays;
import java.util.List;

/**
 * CH-15 {@code PATH_FIND.} — a station asks {@code Main} which way to send a train.
 * <p>
 * The second many-to-one channel: all eight stations send, {@code RailwayMainAgent} is the only
 * subscriber. The channel name really does end in a dot and really is used bare — the constant
 * is {@code "PATH_FIND."} and nothing is appended. Reproduced, not tidied.
 * <p>
 * {@code query-ref}, because it asks for the referent of an expression ("the track from
 * {@code from} towards {@code to}") and gets a value back. Note what is <em>not</em> there: the
 * FIPA query protocol's {@code agree}/{@code refuse} leg. {@code Main} always answers, and
 * {@code Station.getPathDirection} blocks in {@code wait()} until it does, with no timeout.
 *
 * @param from the asking station — also the trace subject, and the reply address
 * @param to   the station being asked about
 */
public record PathFindRequest(String from, String to) implements RailwayMessage {
    @Override public Channel channel() { return Channel.PATH_FIND; }
    @Override public List<String> values() { return Arrays.asList(from, to); }
    @Override public String subjectFromPayload() { return from; }
}
