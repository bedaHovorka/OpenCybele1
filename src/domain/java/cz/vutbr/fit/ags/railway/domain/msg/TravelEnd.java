/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

import java.util.Arrays;
import java.util.List;

/**
 * CH-07 {@code TRAVEL_END.<train>} — the track's traversal timer fired.
 * <p>
 * <strong>{@code road} is a field nobody reads.</strong> {@code Train.travelEnd} never touches
 * {@code ev.getMessage()}; it goes straight to {@code nextPosition}. The field is carried anyway,
 * because the probe records it and a golden holds it: dropping it would change the trace even
 * though it cannot change the simulation (issue #27, constraint 4 — reproduce quirks, do not
 * clean them up).
 *
 * @param road the track that finished the traversal
 */
public record TravelEnd(String road) implements RailwayMessage {
    @Override public Channel channel() { return Channel.TRAVEL_END; }
    @Override public List<String> values() { return Arrays.asList(road); }
}
