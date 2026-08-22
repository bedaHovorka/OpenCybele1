package cz.vutbr.fit.ags.railway.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L1 for the road voting rule and its two timetables ({@code RoadAgent.java:189-202}
 * before #28).
 */
class RoadScheduleTest {

    private static final long D = 3000;   // a 3 s track, in ms

    @Test
    @DisplayName("an empty track raises no objection")
    void empty_road_votes_zero() {
        assertEquals(0, new RoadSchedule(D).computeDifference("vl0", 50000));
    }

    @Test
    @DisplayName("any train in the window pushes the newcomer one full travel time past the last slot")
    void an_occupied_window_defers_by_one_travel_time() {
        final RoadSchedule s = new RoadSchedule(D);
        s.addToPlan("vl1", 50000);
        assertEquals(50000 + D - 49000, s.computeDifference("vl0", 49000));
    }

    @Test
    @DisplayName("the answer is built from the LAST slot in the timetable, not the last one in the window")
    void lastKey_is_global_not_windowed() {
        final RoadSchedule s = new RoadSchedule(D);
        s.addToPlan("vl1", 50000);      // inside the window -- this is what triggers the branch
        s.addToPlan("vl2", 900000);     // far outside it -- and this is what sets the answer
        assertEquals(900000 + D - 49000, s.computeDifference("vl0", 49000));
    }

    @Test
    @DisplayName("the window is half-open: [time-delay, time+delay)")
    void the_window_is_half_open() {
        // #37 NOTE. The lower half of this test used to read
        //     lower.addToPlan("vl1", 50000 - D);
        //     assertEquals(50000 - D + D - 50000, lower.computeDifference("vl0", 50000));
        // whose expected value simplifies to 0 -- which is also what computeDifference returns
        // when the window is EMPTY. An exclusive lower bound would have passed it. The second
        // booking below is what makes the two branches produce different numbers: it is far
        // outside the window, so it cannot trigger the branch, but it is lastKey, so it sets
        // the answer the branch gives.
        final RoadSchedule lower = new RoadSchedule(D);
        lower.addToPlan("vl1", 50000 - D);      // exactly on the inclusive lower edge
        lower.addToPlan("vl2", 900000);         // far outside the window; supplies lastKey
        assertEquals(900000 + D - 50000, lower.computeDifference("vl0", 50000),
                "time-delay is INSIDE the window, so the branch fires and lastKey answers");

        final RoadSchedule upper = new RoadSchedule(D);
        upper.addToPlan("vl1", 50000 + D);      // exactly on the exclusive upper edge
        upper.addToPlan("vl2", 900000);         // same lastKey trick, so 0 can only mean "empty"
        assertEquals(0, upper.computeDifference("vl0", 50000),
                "time+delay is OUTSIDE it, so no train is planned and there is no objection");
    }

    @Test
    @DisplayName("one millisecond either side of the two edges, so the boundary is a step and not a slope")
    void one_step_inside_and_outside_each_edge() {
        assertEquals(900000 + D - 50000, withFarBooking(50000 - D + 1).computeDifference("vl0", 50000),
                "one ms inside the lower edge");
        assertEquals(0, withFarBooking(50000 - D - 1).computeDifference("vl0", 50000),
                "one ms outside the lower edge");
        assertEquals(900000 + D - 50000, withFarBooking(50000 + D - 1).computeDifference("vl0", 50000),
                "one ms inside the upper edge");
        assertEquals(0, withFarBooking(50000 + D + 1).computeDifference("vl0", 50000),
                "one ms outside the upper edge");
    }

    /** A road booked at {@code at}, plus a far-future booking that supplies {@code lastKey}. */
    private static RoadSchedule withFarBooking(long at) {
        final RoadSchedule s = new RoadSchedule(D);
        s.addToPlan("vl1", at);
        s.addToPlan("vl2", 900000);
        return s;
    }

    @Test
    @DisplayName("both structures are kept in sync, and leaving clears both")
    void the_timetable_and_its_inverse_stay_in_sync() {
        final RoadSchedule s = new RoadSchedule(D);
        s.addToPlan("vl1", 50000);
        assertEquals(Long.valueOf(50000), s.plannedTime("vl1"));
        assertEquals(1, s.size());

        s.removeTrain("vl1");
        assertNull(s.plannedTime("vl1"));
        assertEquals(0, s.size());
        assertEquals(0, s.computeDifference("vl0", 50000));
    }

    @Test
    @DisplayName("a train that was never planned has no slot -- the null DEF-06 later unboxes")
    void an_unplanned_train_has_a_null_slot() {
        assertNull(new RoadSchedule(D).plannedTime("ghost"));
    }
}
