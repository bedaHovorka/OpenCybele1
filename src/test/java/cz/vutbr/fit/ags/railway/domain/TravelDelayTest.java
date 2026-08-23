package cz.vutbr.fit.ags.railway.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L1 for the travel-time expression ({@code RoadAgent.travelStart} before #28).
 */
class TravelDelayTest {

    @Test
    @DisplayName("the nominal travel time is jittered by 500 ms per sigma")
    void the_jitter_is_500ms_per_sigma() {
        assertEquals(3000, TravelDelay.travelMs(3000, 0.0));
        assertEquals(3500, TravelDelay.travelMs(3000, 1.0));
        assertEquals(2500, TravelDelay.travelMs(3000, -1.0));
    }

    @Test
    @DisplayName("DEF-16: a one-second track goes NEGATIVE past -2 sigma, and is not clamped")
    void a_short_track_can_produce_a_negative_delay() {
        // Cybele fires a negative-delay timer immediately -- an instantaneous traversal,
        // measured at 2.2645 % of draws for tr1/tr2 and present in the goldens. A port that
        // clamps to 0 produces a diff, and docs/defect-triage.md 3.1 is explicit that the
        // clamp is the port bug, not this.
        assertEquals(-500, TravelDelay.travelMs(1000, -3.0));
        assertTrue(TravelDelay.travelMs(1000, -2.1) < 0, "past -2 sigma on a 1 s track");
        assertEquals(0, TravelDelay.travelMs(1000, -2.0), "exactly -2 sigma is the boundary");
    }

    @Test
    @DisplayName("the cast truncates toward zero, so it is not a floor -- and that is asymmetric")
    void the_cast_truncates_toward_zero() {
        assertEquals(1000, TravelDelay.travelMs(1000, -0.001), "(long)(-0.5) is 0, not -1");
        assertEquals(1000, TravelDelay.travelMs(1000, 0.001));
        assertEquals(999, TravelDelay.travelMs(1000, -0.002), "-1.0 truncates to -1");
    }
    @Test
    @DisplayName("a zero-length track is the jitter alone, sign included -- the base is what bounds DEF-16")
    void a_zero_base_leaves_only_the_jitter() {
        // DEF-16 is "1 s roads go negative". The reason is entirely the base: it is what the
        // draw has to overcome. At base 0 every negative draw survives and every positive one
        // does too, which is the boundary the shipped 1 s track sits just above.
        assertEquals(0, TravelDelay.travelMs(0, 0.0));
        assertEquals(-500, TravelDelay.travelMs(0, -1.0));
        assertEquals(500, TravelDelay.travelMs(0, 1.0));
    }

}
