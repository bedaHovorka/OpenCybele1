package cz.vutbr.fit.ags.railway.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.NoSuchElementException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L1 for the envelope method ({@code Planning.java:97} before #28).
 */
class VoteEnvelopeTest {

    @Test
    @DisplayName("the election result is the largest vote -- the most constrained member wins")
    void the_envelope_is_the_maximum_vote() {
        assertEquals(4200, VoteEnvelope.max(Arrays.asList(
                Long.valueOf(0), Long.valueOf(4200), Long.valueOf(1416), Long.valueOf(0))));
    }

    @Test
    @DisplayName("a unanimous no-objection is a departure now")
    void all_zero_votes_mean_no_delay() {
        assertEquals(0, VoteEnvelope.max(Arrays.asList(
                Long.valueOf(0), Long.valueOf(0), Long.valueOf(0))));
    }

    @Test
    @DisplayName("negative votes are allowed: the station's -window/3 branch can pull a departure earlier")
    void a_negative_envelope_is_a_legal_result() {
        assertEquals(-100, VoteEnvelope.max(Arrays.asList(
                Long.valueOf(-5667), Long.valueOf(-100), Long.valueOf(-2833))));
    }

    @Test
    @DisplayName("DEF-05: an empty ballot throws rather than defaulting -- a lost vote must be loud")
    void an_empty_ballot_throws() {
        assertThrows(NoSuchElementException.class,
                () -> VoteEnvelope.max(Collections.<Long>emptyList()));
    }

    @Test
    @DisplayName("a single voter is its own envelope -- the boundary the election reaches on a one-hop path")
    void a_single_vote_is_the_whole_envelope() {
        assertEquals(4200L, VoteEnvelope.max(List.of(Long.valueOf(4200))));
    }

    @Test
    @DisplayName("the maximum is found at either end of the ballot, not only in the middle")
    void the_maximum_is_found_at_either_end() {
        // Every pre-#37 case put the winner at index 1 of 3 or 4, so a `max` that skipped the
        // first or the last element passed them all.
        assertEquals(900L, VoteEnvelope.max(List.of(
                Long.valueOf(900), Long.valueOf(0), Long.valueOf(100))), "first position");
        assertEquals(900L, VoteEnvelope.max(List.of(
                Long.valueOf(0), Long.valueOf(100), Long.valueOf(900))), "last position");
    }

}
