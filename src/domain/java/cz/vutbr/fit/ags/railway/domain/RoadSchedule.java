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
import java.util.HashMap;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;

/**
 * A single-track segment's timetable and its voting rule — extracted verbatim from
 * {@code RoadAgent.computeDifference}/{@code addToPlan} and the two maps they maintain (#28).
 *
 * <p>The rule is far blunter than the station's: a track holds one train, so if
 * <em>anything at all</em> is planned in the window {@code [time - delay, time + delay)}
 * the road votes to be entered one full travel time after the last slot it knows about.
 * Otherwise it votes 0 — no objection.</p>
 *
 * <p>Two structures are kept in sync, exactly as the agent did: {@code timetable}
 * (time&nbsp;&rarr;&nbsp;train, sorted, for the window query) and {@code invertedTimetable}
 * (train&nbsp;&rarr;&nbsp;time, for {@link RoadQueueItem}'s ordering). The inverted map is
 * a {@link HashMap} of {@link Long}, and {@link #plannedTime(String)} returns {@code null}
 * for a train that was never planned — see the DEF-06 note there.</p>
 */
public final class RoadSchedule implements Serializable {
    private static final long serialVersionUID = 1L;

    private final long travelDelayMs;
    private final SortedMap<Long, String> timetable = new TreeMap<Long, String>();
    private final Map<String, Long> invertedTimetable = new HashMap<String, Long>();

    /**
     * @param travelDelayMs the track's travel time in <b>milliseconds</b> — the agent's
     *        {@code delayInSeconds()}, i.e. the configured seconds already multiplied by 1000
     */
    public RoadSchedule(long travelDelayMs) {
        this.travelDelayMs = travelDelayMs;
    }

    /**
     * The road's vote. Transcribed from {@code RoadAgent.java:189-195}.
     *
     * <p>{@code lastKey()} is the last slot in the <em>whole</em> timetable, not the last
     * one inside the window, so a road with a distant future booking votes for a slot after
     * that booking even when the window that triggered the branch was a near one.</p>
     *
     * @param train the train being voted on; the 2008 rule ignores it, kept for the signature
     * @param time the requested entry time
     * @return the difference to add to {@code time}
     */
    @SuppressWarnings("boxing")
    public long computeDifference(String train, long time) {
        final int plannedTrains = timetable.subMap(time - travelDelayMs, time + travelDelayMs).size();
        if (plannedTrains > 0) {
            return timetable.lastKey() + travelDelayMs - time;
        }
        return 0;
    }

    /**
     * Records the agreed slot in both structures. {@code RoadAgent.java:199-202}.
     *
     * @param train train id
     * @param time agreed entry time
     */
    @SuppressWarnings("boxing")
    public void addToPlan(String train, long time) {
        timetable.put(time, train);
        invertedTimetable.put(train, time);
    }

    /**
     * Drops {@code train} from both structures, exactly as {@code RoadAgent.leave} does:
     * by value from the sorted map, by key from the inverted one.
     *
     * @param train train id
     */
    public void removeTrain(String train) {
        timetable.values().remove(train);
        invertedTimetable.remove(train);
    }

    /**
     * The slot {@code train} was given, or {@code null} if it has none.
     *
     * <p><b>DEF-06, pinned.</b> The 2008 code dereferences this result inside
     * {@code compareTo} with no null check, so an unplanned train in the queue throws
     * {@link NullPointerException} from inside the heap. The return type stays {@link Long}
     * and {@link RoadQueueItem#diff(long)} keeps unboxing it, so the port reproduces that
     * rather than silently repairing it — {@code docs/defect-triage.md} §3.3 says
     * "do not fix", because an NPE here voids a golden recording rather than being
     * absorbed by one.</p>
     *
     * @param train train id
     * @return the planned entry time, or {@code null}
     */
    public Long plannedTime(String train) {
        return invertedTimetable.get(train);
    }

    /** @return the track's travel time in ms */
    public long getTravelDelayMs() {
        return travelDelayMs;
    }

    /** @return how many trains are planned */
    public int size() {
        return timetable.size();
    }

    @Override
    public String toString() {
        return timetable.toString();
    }
}
