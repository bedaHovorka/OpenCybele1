package cz.vutbr.fit.ags.railway.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L1 for the road queue's ordering rule ({@code RoadAgent.OueueItem} before #28).
 *
 * <p>Four of these tests exist to <b>lock in defects</b> rather than to check correctness.
 * If one of them starts failing, read {@code docs/defect-triage.md} §3.1 before "fixing"
 * anything: under the Phase-1 scope guard the baseline's behaviour is the specification,
 * and every one of these is unreachable at scenario scale, so no golden would catch a
 * port that quietly repaired it.</p>
 */
class RoadQueueOrderingTest {

    private static RoadQueue queueWith(long... plannedTimes) {
        final RoadSchedule schedule = new RoadSchedule(3000);
        for (int i = 0; i < plannedTimes.length; i++) {
            schedule.addToPlan("vl" + i, plannedTimes[i]);
        }
        return new RoadQueue(schedule);
    }

    // ---------------------------------------------------------------- the rule itself

    @Test
    @DisplayName("the earliest planned slot is admitted first, whatever order the trains queued in")
    void trains_are_ordered_by_their_planned_slot() {
        final RoadQueue q = queueWith(30000, 10000, 20000);
        q.offer("vl0", "stA", 0);
        q.offer("vl1", "stB", 0);
        q.offer("vl2", "stA", 0);
        assertEquals(3, q.size());
        assertEquals("vl1", q.poll(0).getTrain());
        assertEquals("vl2", q.poll(0).getTrain());
        assertEquals("vl0", q.poll(0).getTrain());
    }

    @Test
    @DisplayName("the item carries the direction it came from, which is what the road admits it into")
    void the_item_remembers_where_the_train_came_from() {
        final RoadQueue q = queueWith(10000);
        q.offer("vl0", "stA", 0);
        final RoadQueueItem item = q.poll(0);
        assertNotNull(item);
        assertEquals("vl0", item.getTrain());
        assertEquals("stA", item.getPosition());
    }

    // ---------------------------------------------------------------- the surfaced clock

    @Test
    @DisplayName("the comparison time cancels: any 'now' yields the same order, overflow included")
    void the_surfaced_time_cancels_out_of_the_comparison() {
        // This is what makes #28's "pass the time in" observationally inert. It is not an
        // approximation: (a-t)-(b-t) == a-b exactly in two's-complement long arithmetic, so
        // it holds at the extremes too, not merely in the ordinary range.
        final long[] instants = {0, 1, -1, 123456789L, Long.MIN_VALUE, Long.MAX_VALUE};
        for (long now : instants) {
            final RoadQueue q = queueWith(30000, 10000, 20000);
            q.offer("vl0", "stA", now);
            q.offer("vl1", "stB", now);
            q.offer("vl2", "stA", now);
            assertEquals("vl1", q.poll(now).getTrain(), "at now=" + now);
            assertEquals("vl2", q.poll(now).getTrain(), "at now=" + now);
            assertEquals("vl0", q.poll(now).getTrain(), "at now=" + now);
        }
    }

    @Test
    @DisplayName("the pairwise comparison is identical at every clock value, sign included")
    void pairwise_comparison_is_independent_of_the_clock() {
        final RoadSchedule schedule = new RoadSchedule(3000);
        schedule.addToPlan("early", 10000);
        schedule.addToPlan("late", 20000);
        final RoadQueue q = new RoadQueue(schedule);
        final RoadQueueItem a = new RoadQueueItem(q, "early", "stA");
        final RoadQueueItem b = new RoadQueueItem(q, "late", "stB");

        for (long now : new long[] {0, 1, -1, 999999999L, Long.MIN_VALUE, Long.MAX_VALUE}) {
            q.poll(now);   // publishes the comparison time; the queue is empty, so nothing moves
            assertTrue(a.compareTo(b) < 0, "at now=" + now);
            assertTrue(b.compareTo(a) > 0, "at now=" + now);
        }
    }

    // ---------------------------------------------------------------- pinned defects

    @Test
    @DisplayName("DEF-04: the direction tie-break is dead code and always returns zero")
    void frequency_is_dead_code_and_always_returns_zero() {
        // frequency() compares a RoadQueueItem to a String with Object.equals, so it can
        // never match -- not even here, where three of the four queued items really did
        // come from "stA". A port whose road queue breaks ties by direction is a port bug.
        final RoadQueue q = queueWith(10000, 20000, 30000, 40000);
        q.offer("vl0", "stA", 0);
        q.offer("vl1", "stA", 0);
        q.offer("vl2", "stB", 0);
        q.offer("vl3", "stA", 0);
        assertEquals(0, RoadQueue.frequency(q.items(), "stA"));
        assertEquals(0, RoadQueue.frequency(q.items(), "stB"));
        assertEquals(0, RoadQueue.frequency(q.items(), "nowhere"));
    }

    @Test
    @DisplayName("DEF-04: two items with the same planned slot compare EQUAL, because the tie-break is inert")
    void a_tie_is_not_broken_by_direction() {
        final RoadSchedule schedule = new RoadSchedule(3000);
        schedule.addToPlan("vl0", 10000);
        schedule.addToPlan("vl1", 10000);
        final RoadQueue q = new RoadQueue(schedule);
        q.offer("vl0", "stA", 0);
        q.offer("vl1", "stB", 0);
        final RoadQueueItem a = new RoadQueueItem(q, "vl0", "stA");
        final RoadQueueItem b = new RoadQueueItem(q, "vl1", "stB");
        assertEquals(0, a.compareTo(b));
        assertEquals(0, b.compareTo(a));
    }

    @Test
    @DisplayName("DEF-04: equals/hashCode are NOT overridden, which is what keeps the tie-break inert")
    void the_item_keeps_identity_equality() {
        final RoadQueue q = queueWith(10000);
        final RoadQueueItem a = new RoadQueueItem(q, "vl0", "stA");
        final RoadQueueItem b = new RoadQueueItem(q, "vl0", "stA");
        assertTrue(a.equals(a));
        assertTrue(!a.equals(b), "value equality would resurrect the DEF-04 tie-break");
        assertTrue(!a.equals("stA"));
    }

    @Test
    @DisplayName("DEF-03: the (int) narrowing inverts at +2^31, holds at -2^31, and zeroes at +/-2^32")
    void the_long_to_int_narrowing_inverts_past_the_int_range() {
        // The two branches are NOT symmetric: (int) 2147483648L == -2147483648, so the
        // positive side inverts exactly AT +2^31 while the negative side still holds there.
        // a is 2^31 ms LATER than b, so a correct comparator answers "positive".
        assertTrue(compare(1L << 31, 0L) < 0,
                "+2^31 apart: the later train wrongly compares FIRST -- the positive side inverts AT 2^31");
        // a is 2^31 ms EARLIER than b: still correct, the negative side has one more value.
        assertTrue(compare(-(1L << 31), 0L) < 0,
                "-2^31 apart: still correct");
        assertTrue(compare(-((1L << 31) + 1), 0L) > 0,
                "-(2^31+1) apart: now the negative side inverts too");
        assertEquals(0, compare(1L << 32, 0L), "+2^32 apart reads as EQUAL");
        assertEquals(0, compare(-(1L << 32), 0L), "-2^32 apart reads as EQUAL");
        // ... and it is correct across the whole int range, as it must be.
        assertTrue(compare((1L << 31) - 1, 0L) > 0, "one below 2^31: the last correct value");
        assertTrue(compare(-60L * 60 * 1000, 0L) < 0, "an hour apart -- scenario scale, fine");
    }

    /**
     * @param aPlan the receiver's planned slot
     * @param bPlan the argument's planned slot
     * @return {@code itemAt(aPlan).compareTo(itemAt(bPlan))}
     */
    private static int compare(long aPlan, long bPlan) {
        final RoadSchedule schedule = new RoadSchedule(3000);
        schedule.addToPlan("a", aPlan);
        schedule.addToPlan("b", bPlan);
        final RoadQueue q = new RoadQueue(schedule);
        q.poll(0);   // publishes comparison time 0 on an empty queue
        return new RoadQueueItem(q, "a", "stA").compareTo(new RoadQueueItem(q, "b", "stB"));
    }

    @Test
    @DisplayName("Comparable deviation, pinned: compareTo(null) returns -1 instead of throwing")
    void compareTo_null_returns_minus_one_instead_of_throwing() {
        // Comparable specifies NullPointerException here. The 2008 line is `if (o == null)
        // return -1;`, it is inert (PriorityQueue never passes null), and it is exactly the
        // kind of line a port author corrects without noticing. docs/defect-triage.md 4.1
        // classifies it (a): pin as-is.
        final RoadQueue q = queueWith(10000);
        assertEquals(-1, new RoadQueueItem(q, "vl0", "stA").compareTo(null));
    }

    @Test
    @DisplayName("DEF-06: an unplanned train throws NPE from inside the heap, and is not guarded")
    void an_unplanned_train_throws_from_inside_the_comparison() {
        final RoadQueue q = queueWith(10000);
        q.offer("vl0", "stA", 0);                       // no comparison yet: heap of one
        assertThrows(NullPointerException.class, () -> q.offer("ghost", "stB", 0));
    }
}
