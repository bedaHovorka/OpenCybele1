package cz.vutbr.fit.ags.railway.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L1 for the dispatch-time accumulation ({@code Planning.java:79-86} and {@code :100-106}
 * before #28).
 */
class DispatchTimelineTest {

    private static Map<String, Long> delays() {
        final Map<String, Long> d = new LinkedHashMap<String, Long>();
        d.put("tr1", Long.valueOf(1));
        d.put("tr2", Long.valueOf(4));
        return d;
    }

    @Test
    @DisplayName("stations cost nothing; each track advances the estimate by its travel time")
    void only_tracks_advance_the_estimate() {
        final List<String> path = Arrays.asList("stA", "tr1", "stB", "tr2", "stC");
        assertEquals(Arrays.asList(
                Long.valueOf(100000),    // stA -- at the request time
                Long.valueOf(100000),    // tr1 -- entered at the same instant
                Long.valueOf(101000),    // stB -- one second later
                Long.valueOf(101000),    // tr2
                Long.valueOf(105000)),   // stC -- four more
                DispatchTimeline.accumulate(path, delays(), 100000));
    }

    @Test
    @DisplayName("one time per path member, in path order -- the loop that broadcasts depends on it")
    void the_result_lines_up_with_the_path() {
        final List<String> path = Arrays.asList("stA", "tr1", "stB");
        assertEquals(path.size(), DispatchTimeline.accumulate(path, delays(), 0).size());
        assertEquals(0, DispatchTimeline.accumulate(Arrays.asList(), delays(), 7).size());
    }

    @Test
    @DisplayName("the second call is the first with a different base: same shape, agreed departure in")
    void the_booking_pass_is_the_same_function_from_a_later_base() {
        final List<String> path = Arrays.asList("stA", "tr1", "stB");
        final List<Long> requests = DispatchTimeline.accumulate(path, delays(), 100000);
        final List<Long> bookings = DispatchTimeline.accumulate(path, delays(), 100000 + 4200);
        for (int i = 0; i < path.size(); i++) {
            assertEquals(requests.get(i).longValue() + 4200, bookings.get(i).longValue());
        }
    }

    @Test
    @DisplayName("a station missing from the delay map is skipped, not treated as zero-and-null-checked")
    void an_unknown_path_member_costs_nothing() {
        final List<String> path = Arrays.asList("mystery", "tr1", "mystery");
        assertEquals(Arrays.asList(Long.valueOf(0), Long.valueOf(0), Long.valueOf(1000)),
                DispatchTimeline.accumulate(path, delays(), 0));
    }

    @Test
    @DisplayName("the accumulation is the 2008 double-valued compound assignment, and it truncates")
    void the_accumulation_goes_through_double_arithmetic() {
        // `disp += 1000*delay.doubleValue()` on a long accumulator is
        // `disp = (long)(disp + 1000.0*delay)`. For the integral second delays the
        // configuration allows this equals the integer computation -- shown here so a port
        // that writes `disp += 1000*delay` knows what it is claiming.
        final Map<String, Long> big = new LinkedHashMap<String, Long>();
        big.put("tr1", Long.valueOf(7));
        assertEquals(Arrays.asList(Long.valueOf(1234567), Long.valueOf(1241567)),
                DispatchTimeline.accumulate(Arrays.asList("tr1", "stB"), big, 1234567));
    }

    @Test
    @DisplayName("past 2^53 the double accumulator loses the low bit, which an integer one would keep")
    void the_double_accumulator_loses_the_low_bit_past_two_to_the_53() {
        // #37 NOTE. The test above SHOWS the double form and the integer form agreeing; it does
        // not DISTINGUISH them, and its own comment says so -- `disp += 1000 * delay.longValue()`
        // passes it. This one cannot be passed by an integer accumulator.
        //
        // 2^53 + 1 is the first long a double cannot hold. `disp += 1000 * delay.doubleValue()`
        // is `disp = (long)(disp + 1000.0*delay)`: the widening rounds 2^53+1 down to 2^53
        // before the addition, so the low bit is gone before anything is added to it.
        final long start = 9007199254740993L;   // 2^53 + 1
        final Map<String, Long> delays = new LinkedHashMap<String, Long>();
        delays.put("tr1", Long.valueOf(1));

        assertEquals(Arrays.asList(Long.valueOf(start), Long.valueOf(9007199254741992L)),
                DispatchTimeline.accumulate(Arrays.asList("tr1", "stB"), delays, start),
                "the integer computation would give 9007199254741993");
    }
}
