/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L1 for the tally ({@code Planning}'s {@code CountDownLatch} + {@code votes} pair before #34).
 * <p>
 * The subject is one question: <b>what closes a round?</b> The 2008 answer was "the
 * {@code path.size()}-th <em>arrival</em>", not "the {@code path.size()}-th distinct voter", and
 * the difference between those two sentences is DEF-05. Half of this suite exists to stop a
 * later reader from making them agree.
 */
class VoteRoundTest {

    private static final List<String> PATH = List.of("stA", "tr1", "stB");

    private static VoteRound round() {
        return new VoteRound("vl0", "stA", PATH, 1000L, 42L);
    }

    @Test
    @DisplayName("the round closes on the last arrival, once, and knows what it was told")
    void the_last_arrival_closes_the_round() {
        VoteRound round = round();
        assertEquals(3, round.outstanding());
        assertEquals(PATH, round.path());
        assertEquals(1000L, round.requestTime());
        assertEquals("vl0", round.train());
        assertEquals("stA", round.from());

        assertFalse(round.record("stA", 0L));
        assertFalse(round.record("tr1", 4200L));
        assertEquals(1, round.outstanding());
        assertFalse(round.isClosed());

        assertTrue(round.record("stB", 100L), "the third arrival closes it");
        assertTrue(round.isClosed());
        assertEquals(0, round.outstanding());
        assertEquals(List.of(Long.valueOf(0L), Long.valueOf(4200L), Long.valueOf(100L)),
                round.ballots());
        assertEquals(4200L, VoteEnvelope.max(round.ballots()));
        assertEquals(Collections.emptyList(), round.missingResponders());
    }

    @Test
    @DisplayName("a vote after the round closed is recorded but cannot close it again"
            + " -- countDown() past zero was a no-op too")
    void closing_happens_exactly_once() {
        VoteRound round = round();
        round.record("stA", 0L);
        round.record("tr1", 0L);
        assertTrue(round.record("stB", 0L));

        assertFalse(round.record("stB", 9999L), "no second close");
        assertFalse(round.record("stX", 9999L), "not even from a voter nobody asked");
        assertEquals(0, round.outstanding(), "and the count does not go negative");
    }

    @Test
    @DisplayName("DEF-05: a duplicate vote overwrites its ballot AND still counts down,"
            + " so the round closes with fewer ballots than voters")
    void def05_a_duplicate_vote_closes_the_round_with_a_short_ballot() {
        VoteRound round = round();

        assertFalse(round.record("stA", 0L));
        assertFalse(round.record("tr1", 4200L));
        // tr1 votes a second time. In 2008 this overwrote votes[Doubleton(tr1, vl0)] and still
        // called latch.countDown(), so the latch reached zero with two distinct voters and a
        // path of three. Both halves are reproduced here, deliberately.
        assertTrue(round.record("tr1", 7L), "the third ARRIVAL closes it, not the third voter");

        assertEquals(2, round.ballots().size(), "one ballot was overwritten, not added");
        assertEquals(List.of(Long.valueOf(0L), Long.valueOf(7L)), round.ballots(),
                "and the overwrite kept the voter's original position, with the later value");
        assertEquals(List.of("stB"), round.missingResponders(),
                "stB never voted, and the round closed anyway -- this is the defect, visible");
        // The consequence the 2008 comment cared about: max() over a short list is smaller, so
        // the train departs earlier than the path agreed to.
        assertEquals(7L, VoteEnvelope.max(round.ballots()));
        assertEquals(4200L, VoteEnvelope.max(List.of(
                Long.valueOf(0L), Long.valueOf(4200L), Long.valueOf(0L))),
                "what a complete round would have produced");
    }

    @Test
    @DisplayName("the expected-responder set is explicit and shrinks as votes arrive"
            + " -- the 2008 planner had only a count")
    void the_missing_responders_are_nameable() {
        VoteRound round = round();
        assertEquals(PATH, round.missingResponders());
        round.record("tr1", 1L);
        assertEquals(List.of("stA", "stB"), round.missingResponders(),
                "in path order, not arrival order");
    }

    @Test
    @DisplayName("the watchdog flag latches, so an alarm fires once rather than every tick")
    void reporting_is_once_only() {
        VoteRound round = round();
        assertFalse(round.isReported());
        assertTrue(round.markReported());
        assertTrue(round.isReported());
        assertFalse(round.markReported());
    }

    @Test
    @DisplayName("a round with no expected responders is refused rather than closing before it opens")
    void an_empty_path_is_refused() {
        assertThrows(IllegalArgumentException.class,
                () -> new VoteRound("vl0", "stA", List.<String>of(), 0L, 0L));
        assertThrows(IllegalArgumentException.class,
                () -> new VoteRound(null, "stA", PATH, 0L, 0L));
        assertThrows(IllegalArgumentException.class, () -> round().record(null, 0L));
    }

    @Test
    @DisplayName("the path is a defensive copy: the caller's list cannot change the responder set")
    void the_responder_set_is_immutable() {
        List<String> mutable = new java.util.ArrayList<String>(PATH);
        VoteRound round = new VoteRound("vl0", "stA", mutable, 0L, 0L);
        mutable.add("stZ");
        assertEquals(3, round.path().size());
        assertThrows(UnsupportedOperationException.class, () -> round.path().add("stZ"));
    }
}
