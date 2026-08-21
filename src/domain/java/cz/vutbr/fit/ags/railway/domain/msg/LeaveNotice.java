/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

import java.util.Arrays;
import java.util.List;

/**
 * CH-04 {@code LEAVE.<obj>} — a train tells an object it is no longer there.
 * <p>
 * A statement of fact, not a request: the train does not wait for anything and no reply channel
 * exists. The receiver reacts by freeing capacity and admitting whatever is queued, but that is
 * the receiver's own decision, which is why the performative is {@code inform} and not
 * {@code request}.
 *
 * @param train the departing train
 */
public record LeaveNotice(String train) implements RailwayMessage {
    @Override public Channel channel() { return Channel.LEAVE; }
    @Override public List<String> values() { return Arrays.asList(train); }
    @Override public String subjectFromPayload() { return train; }
}
