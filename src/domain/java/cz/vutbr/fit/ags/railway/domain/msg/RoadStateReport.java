/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

import java.util.Arrays;
import java.util.List;

/**
 * CH-11 {@code ROAD.STATE.<tr>} — a track pushes its direction to the GUI hub.
 * <p>
 * Unlike CH-10 this payload was never aliased: {@code RoadAgent.State} is an immutable enum
 * constant, so reference passing and snapshot passing are the same thing here.
 *
 * @param state the track's direction, or {@link RoadDirection#FREE}
 */
public record RoadStateReport(RoadDirection state) implements RailwayMessage {
    @Override public Channel channel() { return Channel.ROAD_STATE; }
    @Override public List<String> values() { return Arrays.asList(state == null ? null : state.name()); }
}
