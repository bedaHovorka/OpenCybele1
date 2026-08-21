/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

import java.util.Arrays;
import java.util.List;

/**
 * CH-10 {@code STATION.INFO.<st>} — a station pushes its occupancy to the GUI hub.
 * <p>
 * <strong>This is the one payload the baseline does not really send.</strong> With
 * {@code cybele.srv.comm.app.param.iai = Local;NoSerialization} the very same
 * {@code Station.Info} instance crosses the channel on every {@code sendInfo()} and is then
 * mutated in place by {@code Station.enter} and {@code Station.leave}. What {@code Main} holds
 * is a live alias, not a message ({@code docs/INVENTORY.md} SEM-05, DEF-13; the aliasing quirk
 * is class (b) in {@code docs/defect-triage.md} and #22 carries it).
 * <p>
 * #27 pins the wire form as an <strong>immutable value snapshot</strong> — this record. That is
 * a real semantic change and it is the right one: JADE has snapshot semantics anyway, and the
 * observable it removes, {@code occupied}, is already a value the normalizer must project
 * because it is a race even within one binary at one seed ({@code docs/trace-format.md}, "What
 * the payload of CH-10 is and is not"). A port must still snapshot at the same point the Cybele
 * probe does — on receipt, before doing anything else — so that it drifts noisily rather than
 * systematically.
 * <p>
 * {@code Station.Info} is also a <em>non-static inner class</em>, so under real serialization it
 * would drag its enclosing {@code Station} — queue, timetable, subscriber collection — onto the
 * wire with it. This record has no enclosing instance, by construction.
 *
 * @param occupied trains currently in the station
 * @param capacity the station's capacity
 */
public record StationInfo(int occupied, int capacity) implements RailwayMessage {
    @Override public Channel channel() { return Channel.STATION_INFO; }
    @Override public List<String> values() { return Arrays.asList(Integer.toString(occupied), Integer.toString(capacity)); }
}
