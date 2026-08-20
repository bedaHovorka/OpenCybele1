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
        final RoadSchedule lower = new RoadSchedule(D);
        lower.addToPlan("vl1", 50000 - D);
        assertEquals(50000 - D + D - 50000, lower.computeDifference("vl0", 50000));

        final RoadSchedule upper = new RoadSchedule(D);
        upper.addToPlan("vl1", 50000 + D);
        assertEquals(0, upper.computeDifference("vl0", 50000));
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
