/*
 * Projekt AGS 2007/08
 * FIT VUT Brno
 *
 * Open Cybele 1
 *
 * Bedrich Hovorka
 * xhovor07@stud.fit.vutbr.cz
 */
package cz.vutbr.fit.ags.railway.domain;

import java.io.Serializable;
import java.util.PriorityQueue;

/**
 * The queue of trains waiting for a busy track, and the ordering rule that governs it —
 * extracted from {@code RoadAgent.queue}, {@code push}, {@code pop} and the static
 * {@code frequency} helper (#28). The per-element half of the rule lives in
 * {@link RoadQueueItem}.
 *
 * <h2>Where the clock went</h2>
 * The 2008 comparator read the global simulated clock
 * ({@code Cybele.getTime}) on every single comparison. An extracted class cannot do that,
 * so the time is <b>passed in</b> at {@link #offer} and {@link #poll} and held for the
 * duration of that one heap operation, which is precisely the interval over which the
 * agent held the clock paused around each call.
 *
 * <p>This is observationally inert, and not by luck. {@code docs/defect-triage.md} §4.1
 * expands the comparison:</p>
 * <pre>
 * (plan(a) - t) - (plan(b) - t)  ==  plan(a) - plan(b)
 * </pre>
 * <p>{@code t} cancels — and cancels in two's-complement {@code long} arithmetic too, so
 * the identity survives overflow, not merely the ordinary range.
 * {@code RoadQueueOrderingTest} asserts both. The ordering therefore never depended on
 * <em>when</em> the clock was read, which is why surfacing the read changes no golden.</p>
 */
public final class RoadQueue implements Serializable {
    private static final long serialVersionUID = 1L;

    private final RoadSchedule schedule;
    private final PriorityQueue<RoadQueueItem> queue = new PriorityQueue<RoadQueueItem>();
    private long comparisonTime;

    /**
     * @param schedule the timetable the ordering rule consults; must be the road's own
     */
    public RoadQueue(RoadSchedule schedule) {
        this.schedule = schedule;
    }

    /**
     * Queues a train that could not be admitted. {@code RoadAgent.push}.
     *
     * @param train train id
     * @param position the station the train is entering from — it decides the direction
     * @param now simulated time, for the comparator; see the class comment on why it cancels
     */
    public void offer(String train, String position, long now) {
        this.comparisonTime = now;
        queue.offer(new RoadQueueItem(this, train, position));
    }

    /**
     * Takes the next train to admit. {@code RoadAgent.pop}.
     *
     * @param now simulated time, for the comparator
     * @return the next train, or {@code null} when the queue is empty
     */
    public RoadQueueItem poll(long now) {
        this.comparisonTime = now;
        return queue.poll();
    }

    /** @return how many trains are waiting */
    public int size() {
        return queue.size();
    }

    /**
     * The direction tie-break: how many queued items came from {@code position}.
     *
     * <p><b>DEF-04, pinned — this method always returns 0.</b> {@link RoadQueueItem}
     * overrides neither {@code equals} nor {@code hashCode}, so {@code i.equals(position)}
     * is {@code Object.equals} between a queue item and a {@link String}: reference
     * equality across unrelated types, false for every pair that can ever be built. The
     * author's second-level ordering criterion — "potom podle poctu pozadavku z daneho
     * smeru" — is therefore dead code returning a constant, and equal-{@code diff} items
     * fall through to whatever order the heap happens to hold them in.</p>
     *
     * <p>Reproduced rather than repaired: a port whose road queue really did break ties by
     * direction would order trains differently from every recorded golden. Locked in by
     * {@code RoadQueueOrderingTest.frequency_is_dead_code_and_always_returns_zero}.</p>
     *
     * @param queue the queue to count in
     * @param position the direction to count
     * @return 0, always
     */
    public static int frequency(Iterable<RoadQueueItem> queue, String position) {
        int f = 0;
        for (RoadQueueItem i : queue) {
            // DEF-04: RoadQueueItem vs String — Object.equals, never true. Do not "fix".
            if (i != null && i.equals(position)) f++;
        }
        return f;
    }

    /** @return the items, in heap order — the view {@link #frequency} counts over */
    Iterable<RoadQueueItem> items() {
        return queue;
    }

    /** @return the time handed to the last {@link #offer}/{@link #poll} */
    long getComparisonTime() {
        return comparisonTime;
    }

    /**
     * @param train train id
     * @return the road's planned slot for {@code train}, possibly {@code null} (DEF-06)
     */
    Long plannedTime(String train) {
        return schedule.plannedTime(train);
    }
}
