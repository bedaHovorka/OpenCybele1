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
 * A scheduled departure, and the ordering that decides which one leaves next — extracted
 * from {@code Planning.TrainPlan} (#28).
 *
 * <p>{@link #toString()} is <b>trace-visible</b>: {@code Planning.placeTrainIntoFirstStation}
 * prints it to stdout, so its exact wording is part of every golden and of the normalizer's
 * departure-stream rule. Changing a space here changes the contract.</p>
 */
public final class TrainPlan implements Comparable<TrainPlan>, Serializable {
    private static final long serialVersionUID = 1L;

    private final String train;
    private final String station;
    private final long departure;

    /**
     * @param train train id
     * @param station the origin station the train is placed into
     * @param departure planned departure time
     */
    public TrainPlan(String train, String station, long departure) {
        this.departure = departure;
        this.train = train;
        this.station = station;
    }

    /** @return train id */
    public String getTrain() {
        return train;
    }

    /** @return the origin station */
    public String getStation() {
        return station;
    }

    /** @return the planned departure time */
    public long getDeparture() {
        return departure;
    }

    /**
     * Earliest departure first, ties broken by train id.
     *
     * <p><b>DEF-03, pinned</b> — the same {@code long}-to-{@code int} narrowing as
     * {@link RoadQueueItem#compareTo}: {@code (int)(departure - o.departure)}. Ordering is
     * correct exactly on {@code [-2^31, 2^31-1]} ms, inverts at {@code +2^31} on the
     * positive side and at {@code -(2^31+1)} on the negative one, and collapses to "equal"
     * at {@code +/-2^32}. Unreachable at scenario scale, and therefore untestable through
     * the goldens — which is what the unit test is for.</p>
     *
     * <p>Note the string tie-break is lexicographic, so {@code vl10} sorts before
     * {@code vl9}. That too is 2008 behaviour.</p>
     *
     * @param o the other plan
     * @return the 2008 comparison result
     */
    public int compareTo(TrainPlan o) {
        final int i = (int) (departure - o.departure);
        if (i == 0) {
            return train.compareTo(o.train);
        }
        return i;
    }

    /**
     * <b>Trace-visible.</b> {@code "<train> in <station> at <departure>"} — the departure
     * line every golden carries.
     *
     * @return the departure line
     */
    @Override
    public String toString() {
        return train + " in " + station + " at " + departure;
    }
}
