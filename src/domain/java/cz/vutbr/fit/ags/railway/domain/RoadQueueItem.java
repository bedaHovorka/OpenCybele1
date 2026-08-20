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

/**
 * One train waiting for a track, and the comparison that orders it — extracted from
 * {@code RoadAgent.OueueItem} (#28; the class-name typo is DEF-19, carried by the 2008
 * source and not reproduced here because it has no behaviour).
 *
 * <p>Trains are ordered by how far each one's planned slot is from the current time,
 * earliest first; ties are meant to be broken by direction and never are — see
 * {@link RoadQueue#frequency}.</p>
 *
 * <p><b>{@code equals}/{@code hashCode} are deliberately not overridden.</b> That is not
 * an oversight to tidy up: {@link RoadQueue#frequency} depends on identity semantics to
 * stay inert (DEF-04). Adding a value-based {@code equals} would resurrect a tie-break the
 * goldens were recorded without.</p>
 */
public final class RoadQueueItem implements Comparable<RoadQueueItem>, Serializable {
    private static final long serialVersionUID = 1L;

    private final RoadQueue queue;
    private final String train;
    private final String position;

    RoadQueueItem(RoadQueue queue, String train, String position) {
        this.queue = queue;
        this.train = train;
        this.position = position;
    }

    /** @return the waiting train's id */
    public String getTrain() {
        return train;
    }

    /** @return the station the train is entering from */
    public String getPosition() {
        return position;
    }

    /**
     * Distance from the plan (jizdni rad).
     *
     * <p>Unboxes {@link RoadQueue#plannedTime}, which is {@code null} for a train the road
     * never planned — <b>DEF-06</b>, pinned: the {@link NullPointerException} escapes from
     * inside {@code PriorityQueue}'s sift and leaves the heap undefined. Not guarded, on
     * purpose; {@code docs/defect-triage.md} §3.3 rules "pin, do not fix".</p>
     *
     * @param time simulated time
     * @return planned slot minus {@code time}
     */
    @SuppressWarnings("boxing")
    long diff(long time) {
        return queue.plannedTime(train) - time;
    }

    /**
     * {@code RoadAgent.java:222-228}, transcribed.
     *
     * <p>Three things here are 2008 behaviour that a reader will want to correct, and all
     * three are pinned:</p>
     * <ul>
     * <li><b>DEF-03</b> — {@code (int)} narrows a millisecond {@code long} difference.
     *     Correct exactly on {@code [-2^31, 2^31-1]}; the positive side inverts at
     *     {@code +2^31}, the negative side not until {@code -(2^31+1)}, and both read as
     *     equal at {@code +/-2^32}. Unreachable at scenario scale (2^31 ms is 24.9
     *     simulated days), so it can never show in a golden diff — which is exactly why it
     *     needs a unit test instead.</li>
     * <li><b>DEF-04</b> — the {@link RoadQueue#frequency} tie-break is dead.</li>
     * <li><b>A genuine {@code Comparable} contract deviation</b> — {@code compareTo(null)}
     *     returns {@code -1} where the interface specifies {@link NullPointerException}.
     *     Inert today ({@code PriorityQueue} never passes null), and dangerous precisely
     *     because it is inert: it reads as an obvious tidy-up.
     *     {@code docs/defect-triage.md} §4.1 classifies it (a), pin as-is.</li>
     * </ul>
     *
     * @param o the other item
     * @return the 2008 comparison result
     */
    public int compareTo(RoadQueueItem o) {
        if (o == null) return -1;   // contract deviation, pinned — see the Javadoc
        final long time = queue.getComparisonTime();
        final int d = (int) (diff(time) - o.diff(time));//napred podle planu
        if (d != 0) return d;
        //potom podle poctu pozadavku z daneho smeru — DEF-04: always 0 - 0
        return RoadQueue.frequency(queue.items(), o.position) - RoadQueue.frequency(queue.items(), position);
    }

    @Override
    public String toString() {
        return train + "@" + position;
    }
}
