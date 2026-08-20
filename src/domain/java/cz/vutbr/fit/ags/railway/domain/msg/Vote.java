/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

import java.util.Arrays;
import java.util.List;

/**
 * CH-14 {@code VOTE} — a voter's answer to the call for proposals.
 * <p>
 * One of the two many-senders-to-one-receiver channels: all fifteen static objects send on the
 * one bare channel name and {@code VoteCollecting} is the only subscriber. That is still not
 * fan-out — it is fan-<em>in</em>, and a single AID receives it.
 * <p>
 * {@code voter} exists because in Cybele the payload is the only place the sender's identity
 * lives. In JADE {@code getSender()} carries it too, and the two must agree; the field stays on
 * the wire regardless, because the trace reads field 4 out of it.
 * <p>
 * {@code diff} is family 4 of the run-varying numeric families.
 *
 * @param voter the station or track casting the vote
 * @param train the train being scheduled
 * @param diff  the extra delay this voter needs, in simulated milliseconds
 */
public record Vote(String voter, String train, long diff) implements RailwayMessage {
    @Override public Channel channel() { return Channel.VOTE; }
    @Override public List<String> values() { return Arrays.asList(voter, train, Long.toString(diff)); }
    @Override public String subjectFromPayload() { return train; }
}
