package cz.vutbr.fit.ags.railway.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.NoSuchElementException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L1 for the station voting rule and timetable ({@code Station.java:139-155} before #28).
 *
 * <p>Every expectation here is arithmetic transcribed from the 2008 source, not a judgement
 * about what a station <em>ought</em> to answer.</p>
 */
class StationScheduleTest {

    private static final long W = 8500;   // the shipped sim.station.voteWindowMs

    @Test
    @DisplayName("an empty station agrees immediately: no planned trains, no penalty")
    void empty_station_votes_zero() {
        assertEquals(0, new StationSchedule(3, W).computeDifference("vl0", 100000));
    }

    @Test
    @DisplayName("below the reserve threshold the vote is a soft penalty of plannedTrains*window/6")
    void soft_penalty_scales_with_occupancy_of_the_window() {
        final StationSchedule s = new StationSchedule(3, W);
        s.addToPlan("vl1", 100000);
        assertEquals(W / 6, s.computeDifference("vl0", 100000));
        s.addToPlan("vl2", 100001);
        assertEquals(2 * W / 6, s.computeDifference("vl0", 100000));
    }

    @Test
    @DisplayName("one slot is held in reserve: the hard branch triggers at capacity-1, not at capacity")
    void one_slot_is_always_held_in_reserve() {
        // capacity 3, two trains in the window: 2 > 3-1 is false, still the soft branch.
        final StationSchedule s = new StationSchedule(3, W);
        s.addToPlan("vl1", 100000);
        s.addToPlan("vl2", 100001);
        assertEquals(2 * W / 6, s.computeDifference("vl0", 100000));
        // the third one tips it over: 3 > 2, and the answer stops being a penalty and
        // becomes a slot next to lastKey.
        s.addToPlan("vl3", 100002);
        assertEquals(100002 + W / 3 - 100000, s.computeDifference("vl0", 100000));
    }

    @Test
    @DisplayName("a full station pushes the train past its last slot, by window/3")
    void full_station_schedules_after_its_last_slot() {
        final StationSchedule s = new StationSchedule(1, W);
        s.addToPlan("vl1", 105000);
        // 1 > 1-1, and nothing is planned at or beyond time+window, so the +window/3 branch.
        assertEquals(105000 + W / 3 - 100000, s.computeDifference("vl0", 100000));
    }

    @Test
    @DisplayName("with room beyond the window the train is squeezed in BEFORE the last slot, by -window/3")
    void the_negative_branch_pulls_the_train_in_front_of_the_last_slot() {
        final StationSchedule s = new StationSchedule(2, W);
        final long time = 100000;
        // Two inside [time-W, time+W) tips 2 > 2-1 ...
        s.addToPlan("vl1", 100000);
        s.addToPlan("vl2", 100100);
        // ... and one far in the future makes lastKey > time+W while tailSubMultiMap(time+W)
        // still holds only 1 <= capacity-1 train, which is the -window/3 branch.
        s.addToPlan("vl3", 200000);
        assertEquals(200000 - W / 3 - time, s.computeDifference("vl0", time));
    }

    @Test
    @DisplayName("the voting window is half-open: [time-window, time+window)")
    void the_window_is_half_open() {
        final StationSchedule lower = new StationSchedule(1, W);
        lower.addToPlan("vl1", 100000 - W);
        // included -> 1 > 0 -> hard branch
        assertEquals(100000 - W + W / 3 - 100000, lower.computeDifference("vl0", 100000));

        final StationSchedule upper = new StationSchedule(1, W);
        upper.addToPlan("vl1", 100000 + W);
        // excluded -> 0 planned -> soft branch, and 0*W/6 == 0
        assertEquals(0, upper.computeDifference("vl0", 100000));
    }

    @Test
    @DisplayName("leaving frees the slot: removeTrain drops every entry the train holds")
    void removing_a_train_frees_its_slots() {
        final StationSchedule s = new StationSchedule(1, W);
        s.addToPlan("vl1", 100000);
        s.addToPlan("vl1", 100500);
        assertEquals(2, s.size());
        s.removeTrain("vl1");
        assertEquals(0, s.size());
        assertEquals(0, s.computeDifference("vl0", 100000));
    }

    @Test
    @DisplayName("DEF-18 stays unreachable: the hard branch implies a non-empty timetable")
    void lastKey_on_an_empty_timetable_would_throw_but_cannot_be_reached() {
        // The guard is `plannedTrains > capacity-1`. Reaching lastKey() with an empty
        // timetable needs plannedTrains == 0 > capacity-1, i.e. capacity < 1 -- which
        // ScenarioConfig.parseIntMap rejects ("must be >= 1"). Shown here so the port knows
        // the branch is guarded by the CAPACITY and by a check in another class, not by an
        // emptiness test inside the rule.
        assertEquals(0, new StationSchedule(1, W).computeDifference("vl0", 0));
        assertThrows(NoSuchElementException.class,
                () -> new StationSchedule(0, W).computeDifference("vl0", 0));
    }
}
