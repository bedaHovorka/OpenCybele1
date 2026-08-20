/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

import java.util.Arrays;
import java.util.List;

/**
 * CH-13 {@code PLAN_TRAIN} — {@code Generator} hands a new train to {@code Planning}.
 * <p>
 * Unsuffixed: one channel, one sender, one receiver, and both of them are activities of the
 * <em>same</em> agent. Trace fields 4 and 5 are therefore both {@code Main}, and a port that
 * gives {@code Generator} and {@code Planning} separate agent identities must still write
 * {@code Main} in both, or every {@code PLAN_TRAIN} line diffs.
 * <p>
 * {@code request}: it asks {@code Planning} to run the election, and the election is an action
 * with an effect, not a question with an answer.
 *
 * @param train the newly created train
 * @param from  its origin station
 * @param to    its destination station
 */
public record PlanTrain(String train, String from, String to) implements RailwayMessage {
    @Override public Channel channel() { return Channel.PLAN_TRAIN; }
    @Override public List<String> values() { return Arrays.asList(train, from, to); }
    @Override public String subjectFromPayload() { return train; }
}
