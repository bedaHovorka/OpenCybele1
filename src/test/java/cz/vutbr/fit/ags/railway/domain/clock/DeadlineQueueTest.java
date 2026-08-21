/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.clock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** L1 for the wake-up heap (#29) — ordering, tie-breaks, past deadlines and cancellation. */
class DeadlineQueueTest {

    private final DeadlineQueue queue = new DeadlineQueue();
    private final List<String> fired = new ArrayList<String>();

    private long at(long dueMs, String label) {
        return queue.scheduleAt(dueMs, () -> fired.add(label));
    }

    /** Drain exactly as {@link AgentClock#runDue()} does. */
    private void drain(long nowMs) {
        long limit = queue.sequence();
        Runnable r;
        while ((r = queue.pollDue(nowMs, limit)) != null) {
            r.run();
        }
    }

    @Test
    @DisplayName("earliest deadline first, regardless of the order they were armed in")
    void deadlines_fire_in_time_order() {
        at(3000, "c");
        at(1000, "a");
        at(2000, "b");
        drain(5000);
        assertEquals(List.of("a", "b", "c"), fired);
    }

    @Test
    @DisplayName("ties break by arming order -- FIFO, the kernel's effective event-queue discipline")
    void equal_deadlines_fire_in_arming_order() {
        // docs/kernel-config.md 1 measured Cybele's agent queue as FIFO and DECIDED to keep it
        // rather than enable staticpriority_comp. Insertion order is the closest analogue the
        // port has, and it is the only tie-break that is deterministic -- which the goldens
        // require whether or not they can currently distinguish it.
        at(1000, "first");
        at(1000, "second");
        at(1000, "third");
        drain(1000);
        assertEquals(List.of("first", "second", "third"), fired);
    }

    @Test
    @DisplayName("a deadline in the past is due now, not dropped -- DEF-16 depends on it")
    void a_past_deadline_fires_at_the_next_drain() {
        // Cybele fires a negative-delay timer immediately: measured 1-5 ms for delay = -1500
        // (SEM-06/TMR-04). RoadAgent's Gaussian travel time is negative on 2.26 % of draws for
        // a 1 s road, and the resulting instantaneous traversal is IN the goldens. A queue that
        // rejected or clamped a past deadline would still pass every golden (COVERAGE.md 10.9)
        // and would have silently dropped the behaviour.
        at(-1500, "negative");
        at(0, "zero");
        drain(0);
        assertEquals(List.of("negative", "zero"), fired);
    }

    @Test
    @DisplayName("nothing fires before its deadline")
    void a_future_deadline_waits() {
        at(1000, "later");
        drain(999);
        assertEquals(List.of(), fired);
        drain(1000);
        assertEquals(List.of("later"), fired);
    }

    @Test
    @DisplayName("cancel disarms exactly one wake-up, and reports whether it was still pending")
    void cancel_disarms_one_wakeup() {
        long a = at(1000, "a");
        at(1000, "b");
        assertTrue(queue.cancel(a));
        assertFalse(queue.cancel(a), "already cancelled");
        assertFalse(queue.cancel(4242L), "never existed");
        drain(5000);
        assertEquals(List.of("b"), fired);
        assertFalse(queue.cancel(a), "a fired handle is not pending either");
    }

    @Test
    @DisplayName("nextDueMs reports the earliest pending deadline, or null")
    void next_due_reports_the_head() {
        assertNull(queue.nextDueMs());
        at(3000, "c");
        at(1000, "a");
        assertEquals(Long.valueOf(1000), queue.nextDueMs());
        assertEquals(2, queue.size());
        drain(1000);
        assertEquals(Long.valueOf(3000), queue.nextDueMs());
        assertEquals(1, queue.size());
    }

    @Test
    @DisplayName("a wake-up armed during a drain waits for the next one -- no self-re-entry")
    void a_wakeup_armed_during_a_drain_is_not_re_entered() {
        // Without the sequence bound, a task arming a past-dated deadline would be popped again
        // inside the same drain and would spin the agent thread forever.
        queue.scheduleAt(0, () -> {
            fired.add("outer");
            queue.scheduleAt(-1, () -> fired.add("armed-during-drain"));
        });
        drain(0);
        assertEquals(List.of("outer"), fired);
        assertEquals(1, queue.size(), "the new one is armed, just not eligible this drain");
        drain(0);
        assertEquals(List.of("outer", "armed-during-drain"), fired);
    }

    @Test
    @DisplayName("an older, later-dated wake-up still fires when a drain-armed one sorts ahead of it")
    void an_older_entry_is_not_skipped_by_a_drain_armed_head() {
        // The case that makes pollDue scan instead of only checking the head: after the first
        // task runs, the heap's minimum is the one armed DURING this drain (due 100), which is
        // not eligible -- but the older due-200 entry is, and it must not be deferred a whole
        // tick behind it.
        at(200, "older-later");
        queue.scheduleAt(50, () -> {
            fired.add("first");
            queue.scheduleAt(100, () -> fired.add("drain-armed"));
        });
        drain(250);
        assertEquals(List.of("first", "older-later"), fired);
        assertEquals(1, queue.size());
        drain(250);
        assertEquals(List.of("first", "older-later", "drain-armed"), fired);
    }

    @Test
    @DisplayName("the ordering is a long compare, not the (int) narrowing DEF-03 pins elsewhere")
    void ordering_does_not_narrow_to_int() {
        // DEF-03 is the 2008 code doing (int)(a - b) on millisecond longs; it is pinned where
        // it is observable (RoadQueueItem at RoadAgent.java:211, TrainPlan at Planning.java:148)
        // and must not be reintroduced where it is not. These two differ by exactly 2^32, which
        // a narrowing comparator reads as ZERO -- it would call them equal and fall through to
        // the arming-order tie-break, firing "far" first.
        final long twoToThe32 = 4294967296L;
        at(twoToThe32, "far");
        at(0, "near");
        drain(twoToThe32);
        assertEquals(List.of("near", "far"), fired);
    }

    @Test
    @DisplayName("a null task is rejected at arming, not at firing")
    void a_null_task_is_rejected_immediately() {
        assertThrows(IllegalArgumentException.class, () -> queue.scheduleAt(1000, null));
        assertEquals(0, queue.size());
    }

    @Test
    @DisplayName("pollDue hands back one task at a time and runs none of them itself")
    void polling_yields_one_task_at_a_time_and_runs_nothing() {
        // This is why a wake-up that throws cannot take its siblings down with it: the drain
        // pops one, runs it OUTSIDE the queue's monitor, and only then asks for the next. A
        // drainAll-style API would have popped both before running either.
        at(1000, "a");
        at(1000, "b");
        Runnable first = queue.pollDue(1000, queue.sequence());
        assertNotNull(first);
        assertEquals(1, queue.size(), "the second is still armed while the first runs");
        assertEquals(List.of(), fired, "the queue does not invoke the task");
        first.run();
        assertEquals(List.of("a"), fired);
    }
}
