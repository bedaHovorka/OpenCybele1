/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

import java.util.Arrays;
import java.util.List;

/**
 * CH-08 {@code TRAVEL_START.<road>} — the train asks the track to run the traversal.
 * <p>
 * {@code request}, paired with CH-07's {@code inform}: the track starts a timer and reports
 * completion. It is the only request/response pair in the application with a real delay between
 * the two legs.
 *
 * @param train the train starting its traversal
 */
public record TravelStart(String train) implements RailwayMessage {
    @Override public Channel channel() { return Channel.TRAVEL_START; }
    @Override public List<String> values() { return Arrays.asList(train); }
    @Override public String subjectFromPayload() { return train; }
}
