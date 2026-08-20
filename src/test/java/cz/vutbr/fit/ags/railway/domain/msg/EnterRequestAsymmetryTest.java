/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * The payload asymmetry the ontology exists to fix.
 * <p>
 * {@code Train.requestEnterToObject} sends {@code {getName(), position, to}} on
 * {@code ENTER.<obj>} — one shape, always. {@code Station.enter} then reads
 * {@code message[0]} and {@code message[2]}; {@code RoadAgent.enter} reads {@code message[0]}
 * and {@code message[1]}. Nothing in the baseline says so, and the indices are three files
 * apart. These tests are what "same behaviour, no positional coupling" means concretely.
 */
class EnterRequestAsymmetryTest {

    /** Mid-route: vl3 is leaving stA, heading for tr1, ultimately for stB. */
    private final EnterRequest midRoute = new EnterRequest("vl3", "stA", "stB");

    @Test
    @DisplayName("a Station reads the train and the destination -- baseline slots 0 and 2")
    void station_reads_train_and_destination() {
        assertEquals("vl3", midRoute.train());
        assertEquals("stB", midRoute.endStation());
        assertEquals(midRoute.target(), midRoute.endStation());
    }

    @Test
    @DisplayName("a RoadAgent reads the train and where it is coming from -- baseline slots 0 and 1")
    void road_reads_train_and_arrival_side() {
        assertEquals("vl3", midRoute.train());
        assertEquals("stA", midRoute.arrivingFrom());
        assertEquals(midRoute.position(), midRoute.arrivingFrom());
    }

    @Test
    @DisplayName("the two reads are different fields, and swapping them would be visible")
    void the_two_reads_are_genuinely_different_fields() {
        // If a port confuses them the simulation silently misroutes: a station would send the
        // train back the way it came, and a track would compute the wrong direction of travel.
        // The whole point of naming the fields is that the confusion becomes a compile error
        // rather than an index typo.
        assertEquals("stB", midRoute.endStation());
        assertEquals("stA", midRoute.arrivingFrom());
    }

    @Test
    @DisplayName("one record is sent to both receivers; neither field is dropped for either")
    void one_record_carries_both_reads() {
        // Same object, same three slots, whichever receiver it goes to. The field the station
        // ignores and the field the track ignores are both still on the wire, because the probe
        // records all three and a golden holds them.
        assertEquals(List.of("train", "position", "target"), Channel.ENTER.payloadKeys());
        assertEquals("train=vl3,position=stA,target=stB", midRoute.payload());
        assertEquals(midRoute, Payloads.parse(Channel.ENTER, midRoute.payload()));
    }

    @Test
    @DisplayName("on the first hop the field a track would read is null, and that is correct")
    void the_first_hop_has_no_arrival_side() {
        // Train.position is still unset when start() calls requestEnterToObject, so the very
        // first ENTER of every train carries position=null. It is always addressed to a
        // station, which does not read that slot -- so the null is never dereferenced.
        EnterRequest firstHop = new EnterRequest("vl3", null, "stB");
        assertNull(firstHop.arrivingFrom());
        assertEquals("stB", firstHop.endStation());
        assertEquals("train=vl3,position=null,target=stB", firstHop.payload());
    }

    @Test
    @DisplayName("a train already at its destination sends target equal to the station it enters")
    void arrival_at_the_destination_is_expressible() {
        // Station.getPathDirection returns null when target.equals(getName()), which is what
        // produces next=null in the reply and kills the train. The record must be able to carry
        // that shape; it is not a degenerate case to reject.
        EnterRequest arrival = new EnterRequest("vl3", "tr5", "stB");
        assertEquals("stB", arrival.endStation());
        assertEquals("tr5", arrival.arrivingFrom());
    }
}
