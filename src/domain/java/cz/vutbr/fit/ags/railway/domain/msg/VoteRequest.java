/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

import java.util.Arrays;
import java.util.List;

/**
 * CH-01 {@code VOTE_REQUEST.<obj>} — {@code Planning} &rarr; one path member.
 * <p>
 * {@code expected} is the running estimate of when the train would reach this object if it left
 * now: {@code Cybele.getTime} at the moment the election opened plus the accumulated road delays
 * ({@code DispatchTimeline.accumulate}). It is family 2 of the five run-varying numeric families
 * in {@code docs/trace-format.md} and the normalizer projects it.
 *
 * @param train    the train being scheduled
 * @param expected the estimated arrival at this object, in simulated milliseconds
 */
public record VoteRequest(String train, long expected) implements RailwayMessage {
    @Override public Channel channel() { return Channel.VOTE_REQUEST; }
    @Override public List<String> values() { return Arrays.asList(train, Long.toString(expected)); }
    @Override public String subjectFromPayload() { return train; }
}
