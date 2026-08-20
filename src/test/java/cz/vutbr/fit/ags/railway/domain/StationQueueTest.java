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
}
