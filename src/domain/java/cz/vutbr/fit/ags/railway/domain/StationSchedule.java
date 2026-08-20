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

import cz.vutbr.fit.ags.railway.domain.util.TreeMultiMap;

/**
 * A station's timetable and its voting rule — extracted verbatim from
 * {@code Station.computeDifference}/{@code addToPlan} (#28).
 *
 * <p>The rule: look at the trains already planned in the window
 * {@code [time - voteWindow, time + voteWindow)}. If that is more than
 * {@code capacity - 1} — <em>one slot is always held in reserve</em> — the station cannot
 * take the train at {@code time} and votes for a slot next to the last one it has,
 * {@code voteWindow/3} before or after it. Otherwise it agrees, with a soft penalty of
 * {@code plannedTrains * voteWindow / 6}.</p>
 *
 * <p>Both numbers are <em>differences</em>, not absolute times: the caller adds them to
 * the request time. Time is a parameter here and nothing reads a clock.</p>
 */
public final class StationSchedule implements Serializable {
    private static final long serialVersionUID = 1L;

    private final int capacity;
    private final long voteWindow;
    private final TreeMultiMap<Long, String> timetable = new TreeMultiMap<Long, String>();

    /**
     * @param capacity how many trains the station holds
     * @param voteWindow the scheduling quantum, {@code sim.station.voteWindowMs}
     */
    public StationSchedule(int capacity, long voteWindow) {
        this.capacity = capacity;
        this.voteWindow = voteWindow;
    }

    /**
     * The station's vote: how much later than {@code time} it can take {@code train}.
     *
     * <p>Transcribed from {@code Station.java:139-149} without simplification. The
     * {@code capacity-1} is the author's "jedno misto pro rezervu"; the asymmetric
     * {@code -voteWindow/3} branch pulls the train <em>in front of</em> the last planned
     * one when there is room beyond the window.</p>
     *
     * <p>Throws {@link java.util.NoSuchElementException} on an empty timetable via
     * {@code lastKey()} — <b>DEF-18</b>. Unreachable for every {@code capacity >= 1},
     * because the guard {@code plannedTrains > capacity-1} then implies at least one
     * planned train. It <em>is</em> reachable at {@code capacity == 0}, and the only thing
     * that keeps it out of reach is a configuration check elsewhere:
     * {@code ScenarioConfig.parseIntMap} rejects {@code sim.station.capacities} below 1.
     * That is the "new configuration precondition" of {@code docs/defect-triage.md} §4.4,
     * and it is worth knowing that this class does not enforce it itself.</p>
     *
     * @param train the train being voted on; the 2008 rule ignores it, kept for the signature
     * @param time the requested arrival time
     * @return the difference to add to {@code time}
     */
    @SuppressWarnings("boxing")
    public long computeDifference(String train, long time) {
        final long timePlusLambda = time + voteWindow;
        final int plannedTrains = timetable.subMultiMap(time - voteWindow, timePlusLambda).values().size();
        if (plannedTrains > capacity - 1) {//jedno misto pro rezervu
            final int size = timetable.tailSubMultiMap(timePlusLambda).values().size();
            return timetable.lastKey() +
                ((timePlusLambda < timetable.lastKey() && size <= capacity - 1) ?
                    -voteWindow / 3 : voteWindow / 3) - time;
        }
        return plannedTrains * voteWindow / 6;
    }

    /**
     * Records the agreed slot. {@code Station.java:153-155}.
     *
     * @param train train id
     * @param time agreed arrival time
     */
    @SuppressWarnings("boxing")
    public void addToPlan(String train, long time) {
        timetable.put(time, train);
    }

    /**
     * Drops every slot held by {@code train}. {@code Station.leave} does this on every
     * departure, keyed by value rather than by time.
     *
     * @param train train id
     */
    public void removeTrain(String train) {
        timetable.removeValue(train);
    }

    /** @return the configured capacity */
    public int getCapacity() {
        return capacity;
    }

    /** @return the configured voting window, in ms */
    public long getVoteWindow() {
        return voteWindow;
    }

    /** @return how many trains are planned, in total */
    public int size() {
        return timetable.values().size();
    }

    @Override
    public String toString() {
        return timetable.toString();
    }
}
