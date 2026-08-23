package cz.vutbr.fit.ags.railway.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L1 for the station's door queue ({@code Station.QueueItem} plus its {@code LinkedList}
 * before #28).
 */
class StationQueueTest {

    @Test
    @DisplayName("a full station admits waiting trains strictly first-come-first-served")
    void the_station_queue_is_plain_fifo() {
        // Unlike the road queue there is no comparator here at all: no timetable lookup, no
        // tie-break, no priority. A port that "improves" this into a priority queue changes
        // behaviour under the Phase-1 scope guard.
        final StationQueue q = new StationQueue();
        q.offer("vl2", "stH");
        q.offer("vl0", "stA");
        q.offer("vl1", "stC");
        assertEquals("vl2", q.poll().getTrain());
        assertEquals("vl0", q.poll().getTrain());
        assertEquals("vl1", q.poll().getTrain());
    }

    @Test
    @DisplayName("the queued train carries its final destination, not the next hop")
    void the_queued_train_carries_its_destination() {
        // Station.leave resolves the onward direction from this, through the lazy PATH_FIND
        // round trip -- so it has to be the end station, not a neighbour.
        final StationQueue q = new StationQueue();
        q.offer("vl0", "stH");
        final StationQueue.Waiting waiting = q.poll();
        assertEquals("vl0", waiting.getTrain());
        assertEquals("stH", waiting.getEndStation());
    }

    @Test
    @DisplayName("polling an empty queue yields null, which is the state Station.leave tests with size()")
    void an_empty_queue_polls_to_null() {
        final StationQueue q = new StationQueue();
        assertEquals(0, q.size());
        assertNull(q.poll());
    }

    @Test
    @DisplayName("the queue reports its depth at every step -- Station.leave branches on size()")
    void the_depth_is_reported_at_every_step() {
        // Before #37 size() was asserted at 0 and nowhere else, so `return 0;` passed the suite
        // -- and Station.leave decides whether to admit a waiting train by testing exactly this.
        final StationQueue q = new StationQueue();
        assertEquals(0, q.size());
        q.offer("vl0", "stA");
        assertEquals(1, q.size());
        q.offer("vl1", "stC");
        assertEquals(2, q.size());
        q.poll();
        assertEquals(1, q.size());
        q.poll();
        assertEquals(0, q.size());
    }

    @Test
    @DisplayName("polling past exhaustion keeps yielding null instead of throwing")
    void polling_past_exhaustion_stays_null() {
        final StationQueue q = new StationQueue();
        q.offer("vl0", "stA");
        assertEquals("vl0", q.poll().getTrain());
        assertNull(q.poll());
        assertNull(q.poll(), "a second poll on an empty queue must behave like the first");
        assertEquals(0, q.size());
    }

    @Test
    @DisplayName("the same train may sit in the queue twice: this is a list, not a set")
    void the_same_train_may_queue_twice() {
        // Unlike the station TIMETABLE, which is a TreeMultiMap and holds one train per slot
        // however often it is planned, the door queue de-duplicates nothing.
        final StationQueue q = new StationQueue();
        q.offer("vl0", "stA");
        q.offer("vl0", "stA");
        assertEquals(2, q.size());
        assertEquals("vl0", q.poll().getTrain());
        assertEquals("vl0", q.poll().getTrain());
        assertEquals(0, q.size());
    }

}
