package cz.vutbr.fit.ags.railway.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.PriorityQueue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L1 for the departure ordering ({@code Planning.TrainPlan} before #28).
 */
class TrainPlanTest {

    @Test
    @DisplayName("the earliest departure is dispatched first")
    void plans_are_ordered_by_departure() {
        final PriorityQueue<TrainPlan> q = new PriorityQueue<TrainPlan>();
        q.add(new TrainPlan("vl2", "stC", 30000));
        q.add(new TrainPlan("vl0", "stA", 10000));
        q.add(new TrainPlan("vl1", "stB", 20000));
        assertEquals("vl0", q.poll().getTrain());
        assertEquals("vl1", q.poll().getTrain());
        assertEquals("vl2", q.poll().getTrain());
    }

    @Test
    @DisplayName("a tie in departure is broken by train id, lexicographically -- so vl10 precedes vl9")
    void ties_are_broken_lexicographically_by_train_id() {
        final TrainPlan ten = new TrainPlan("vl10", "stA", 10000);
        final TrainPlan nine = new TrainPlan("vl9", "stB", 10000);
        assertTrue(ten.compareTo(nine) < 0, "'vl10' < 'vl9' as strings, not as numbers");
        assertTrue(nine.compareTo(ten) > 0);
        assertEquals(0, ten.compareTo(new TrainPlan("vl10", "stZ", 10000)),
                "same departure and same id compare equal, whatever the station");
    }

    @Test
    @DisplayName("DEF-03: the (int) narrowing inverts at +2^31, holds at -2^31, and zeroes at +/-2^32")
    void the_long_to_int_narrowing_inverts_past_the_int_range() {
        // The same pattern as RoadQueueItem.compareTo, at the second of DEF-03's two sites.
        // 2^31 ms is 24.855 simulated days, so no golden scenario can reach this -- which is
        // why it needs a unit test rather than a parity run.
        assertTrue(at(1L << 31).compareTo(at(0)) < 0,
                "+2^31 apart: the later departure wrongly sorts FIRST");
        assertTrue(at(-(1L << 31)).compareTo(at(0)) < 0,
                "-2^31 apart: still correct");
        assertTrue(at(-((1L << 31) + 1)).compareTo(at(0)) > 0,
                "-(2^31+1) apart: the negative side inverts one value later");
        // At +/-2^32 the difference reads as 0, and the id tie-break takes over: two
        // departures seven weeks apart get ordered by train NAME.
        assertTrue(at(1L << 32).compareTo(at(0)) < 0,
                "+2^32 apart: reads as a tie, so 'a' sorts before 'b' regardless of departure");
        assertEquals(0, new TrainPlan("x", "stA", 1L << 32).compareTo(new TrainPlan("x", "stA", 0)),
                "+2^32 apart with the same id: fully equal");
        assertTrue(at((1L << 31) - 1).compareTo(at(0)) > 0, "one below 2^31: the last correct value");
    }

    private static TrainPlan at(long departure) {
        return new TrainPlan(departure == 0 ? "b" : "a", "stA", departure);
    }

    @Test
    @DisplayName("toString is trace-visible: it is the departure line every golden carries")
    void toString_is_the_departure_line() {
        // Planning.placeTrainIntoFirstStation prints this to stdout. The normalizer's
        // departure-stream rule matches on it; changing a space changes the contract.
        assertEquals("vl7 in stB at 123456", new TrainPlan("vl7", "stB", 123456).toString());
    }
}
