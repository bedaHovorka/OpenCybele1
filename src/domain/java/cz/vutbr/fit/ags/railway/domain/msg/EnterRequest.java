/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

import java.util.Arrays;
import java.util.List;

/**
 * CH-03 {@code ENTER.<obj>} — a train asks a station or a track to admit it.
 * <p>
 * <strong>This is the message the ontology exists for.</strong> One sender
 * ({@code Train.requestEnterToObject}) builds one shape, {@code {train, position, target}}, and
 * the two receiver classes read <em>different subsets</em> of it:
 * <ul>
 *   <li>{@code Station.enter} reads slot 0 and slot 2 — the train, and the station it is
 *       ultimately heading for, which the station turns into a direction via {@code PATH_FIND}.
 *       It never looks at {@code position}.</li>
 *   <li>{@code RoadAgent.enter} reads slot 0 and slot 1 — the train, and the station it is
 *       arriving from, which the track turns into a travel direction. It never looks at
 *       {@code target}.</li>
 * </ul>
 * Both fields stay on the wire in both directions. {@link #endStation()} and
 * {@link #arrivingFrom()} name the two reads in the receiver's own vocabulary so a port cannot
 * silently swap them the way an index can.
 * <p>
 * {@code position} is {@code null} on a train's very first {@code ENTER}: {@code Train.position}
 * is still unset when {@code start} calls {@code requestEnterToObject}. That is correct, not a
 * fault, and it must survive to the trace as the four characters {@code null}.
 *
 * @param train    the train asking for admission
 * @param position the object the train is coming from, {@code null} on its first hop
 * @param target   the train's destination station
 */
public record EnterRequest(String train, String position, String target) implements RailwayMessage {
    @Override public Channel channel() { return Channel.ENTER; }
    @Override public List<String> values() { return Arrays.asList(train, position, target); }
    @Override public String subjectFromPayload() { return train; }

    /**
     * The field a {@code Station} reads (baseline slot {@code [2]}): where the train is
     * ultimately going, which decides which track it is sent out on.
     *
     * @return the destination station
     */
    public String endStation() { return target; }

    /**
     * The field a {@code RoadAgent} reads (baseline slot {@code [1]}): which of its two
     * neighbouring stations the train is entering from, which decides the direction of travel.
     *
     * @return the station the train is arriving from, {@code null} on a first hop
     */
    public String arrivingFrom() { return position; }
}
