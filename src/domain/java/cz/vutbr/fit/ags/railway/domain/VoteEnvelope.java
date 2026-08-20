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

import java.util.Collection;
import java.util.Collections;

/**
 * The author's <i>obalkova metoda</i> — the envelope method — extracted from
 * {@code Planning.java:97} (#28).
 *
 * <p>Every station and track on the path votes for how much it would have to delay the
 * train. The election result is the <b>maximum</b> of those votes: the departure slides
 * until the most constrained member of the path is satisfied, and every other member is
 * then satisfied a fortiori. There is no averaging and no negotiation round — one
 * broadcast, one max, one answer.</p>
 */
public final class VoteEnvelope {

    private VoteEnvelope() {
        //EMPTY
    }

    /**
     * @param votes one vote per path member, as collected by the vote-collecting activity
     * @return the largest vote
     * @throws java.util.NoSuchElementException if {@code votes} is empty — the 2008
     *         behaviour of {@link Collections#max}, and the signal that a vote went
     *         missing (DEF-05). The caller asserts the count first; under {@code -ea}
     *         that assertion fires before this does.
     */
    @SuppressWarnings("boxing")
    public static long max(Collection<Long> votes) {
        return Collections.max(votes);
    }
}
