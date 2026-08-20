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

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * Walks a path and works out when the train is expected at each object on it — extracted
 * from the two identical loops in {@code Planning.planTrain} (#28,
 * {@code Planning.java:79-86} and {@code :100-106}).
 *
 * <p>Both loops had the same shape: start from a base time, hand the current value to the
 * object being addressed, then advance by that object's travel time if it is a track.
 * Stations contribute nothing — they are absent from the delay map, and the {@code null}
 * check is what skips them. The first loop starts at the request time and produces the
 * {@code VOTE_REQUEST} estimates; the second starts at the agreed departure and produces
 * the {@code VOTE_RESULT} bookings. One function, called twice.</p>
 */
public final class DispatchTimeline {

    private DispatchTimeline() {
        //EMPTY
    }

    /**
     * The expected time at each element of {@code path}, in path order.
     *
     * <p><b>The arithmetic is the 2008 arithmetic, and it is odd.</b> The source writes
     * {@code disp += 1000*delay.doubleValue()} on a {@code long} accumulator: the right
     * side is a {@code double}, and the compound assignment carries an implicit narrowing
     * cast, so every step is {@code disp = (long)(disp + 1000.0 * delay)}. For the integral
     * second-valued delays the configuration allows this equals the integer computation, but
     * it is floating-point addition on a millisecond magnitude, and it is written out here
     * in the same form rather than "cleaned up" to {@code disp += 1000 * delay} — which
     * would be a different expression whose equality is a property of the current
     * configuration, not of the code.</p>
     *
     * @param <T> element type of the path (stations and tracks are both named by String
     *        in the application, and the 2008 code walked them as {@code Object})
     * @param path the path from {@code Util.path}: station, track, station, ...
     * @param roadDelaysSec travel time per track, in <b>seconds</b>; stations are absent
     * @param start the base time — request time for votes, departure time for bookings
     * @return one time per path element, same size and order as {@code path}
     */
    public static <T> List<Long> accumulate(List<T> path, Map<?, Long> roadDelaysSec, long start) {
        final List<Long> times = new ArrayList<Long>(path.size());
        long disp = start;
        for (T o : path) {//vsem na ceste
            times.add(Long.valueOf(disp));
            final Long delay = roadDelaysSec.get(o);
            if (delay != null) {
                disp += 1000 * delay.doubleValue();
            }
        }
        return Collections.unmodifiableList(times);
    }
}
