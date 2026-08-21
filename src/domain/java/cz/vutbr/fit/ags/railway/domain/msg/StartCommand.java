/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

import java.util.Arrays;
import java.util.List;

/**
 * CH-05 {@code START.<train>} — {@code Planning} releases a train at its planned departure.
 * <p>
 * The subject is the addressed train, which is the channel suffix, not anything in the payload.
 * The baseline asserts {@code station.equals(from)} on receipt, so the field is read and is
 * load-bearing for {@code -ea} runs.
 * <p>
 * This is the send annotated {@code //BUG ne vzdy se doruci} in {@code Planning.java} — DEF-02,
 * class (c). The ontology neither fixes nor reproduces that; a lost {@code START} is a delivery
 * failure, not a payload shape.
 *
 * @param station the station the train starts from
 */
public record StartCommand(String station) implements RailwayMessage {
    @Override public Channel channel() { return Channel.START; }
    @Override public List<String> values() { return Arrays.asList(station); }
}
