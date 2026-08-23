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
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One train's election, as a tally — the framework-free half of {@code Planning}'s
 * {@code CountDownLatch} + {@code votes} multimap pair (#34).
 *
 * <h2>What this replaces, and why the replacement has to be a pair of counters</h2>
 * The 2008 planner held <b>two</b> independent structures for one round:
 * <ul>
 *   <li>a {@code CountDownLatch} sized {@code path.size()}, counted down once per
 *       <em>arriving</em> vote ({@code VoteCollecting.java:54}); and</li>
 *   <li>a {@code votes} multimap keyed by {@code Doubleton(voter, train)}, which stores one
 *       entry per <em>distinct voter</em> ({@code VoteCollecting.java:51}).</li>
 * </ul>
 * Those two counts are not the same number, and the gap between them is <b>DEF-05</b>. This
 * class keeps both — {@link #outstanding()} counts arrivals, {@link #ballots()} counts distinct
 * voters — precisely so the gap survives the port. A tally that closed on
 * {@code ballots.size() == path.size()} would be a repair, and a port that repairs a pinned
 * defect is a port bug ({@code docs/defect-triage.md} §6.1).
 *
 * <h2>DEF-05, reproduced rather than fixed</h2>
 * A second vote from a voter that has already voted <b>overwrites</b> its ballot and
 * <b>still</b> decrements {@link #outstanding()}. The round can therefore close with strictly
 * fewer ballots than expected responders — {@link #missingResponders()} is then non-empty at
 * the moment {@link #record} returns {@code true} — and the envelope
 * ({@link VoteEnvelope#max}) is taken over a short list, yielding a smaller delay and an
 * earlier departure. That is the 2008 behaviour, expression for expression.
 * {@code Planning}'s {@code assert ballots.size() == path.size()} is the 2008 guard and stays
 * at the call site, where it was; this class does not guard.
 *
 * <p><b>Never observed on Cybele</b> — {@code Planning.java:96} was evaluated 36&times; and
 * fired 0 (#14) — and structurally unreachable under JADE, which does not duplicate a
 * unicast {@code ACLMessage}. It is pinned by test rather than by scenario for exactly that
 * reason, the same standing {@code Station}'s null-direction refusal has.
 *
 * <h2>Closing exactly once</h2>
 * {@link #record} returns {@code true} for the arrival that takes {@link #outstanding()} to
 * zero and never again, which is what {@code CountDownLatch.countDown()} past zero already did
 * — a no-op. It is not a repair of anything: a caller that removes the round on close (as
 * {@code Planning} does, reproducing {@code Planning.java:89}) can never present a closed round
 * again, and the flag only makes that structural rather than conventional.
 *
 * <p>Simulated time is a parameter here as everywhere in this package: {@link #requestTime()}
 * is the instant the initiator read <em>before</em> broadcasting, carried so the envelope can
 * be applied to it later, and {@link #openedAtNanos()} is a <em>wall-clock</em> stamp used only
 * by the caller's watchdog. This class reads no clock.
 */
public final class VoteRound implements Serializable {

    private static final long serialVersionUID = 1L;

    private final String train;
    private final String from;
    private final List<String> path;
    private final long requestTime;
    private final long openedAtNanos;
    /** voter -&gt; delay difference. Insertion-ordered; a repeat voter keeps its first position. */
    private final Map<String, Long> ballots = new LinkedHashMap<String, Long>();
    private int outstanding;
    private boolean closed;
    private boolean reported;

    /**
     * Opens a round.
     *
     * @param train the train being planned
     * @param from its origin station — carried only so the closing caller can build the
     *        {@code TrainPlan} without a second lookup, exactly as {@code planTrain}'s local
     *        did
     * @param path the expected responders, in path order: station, track, station, ... This is
     *        the explicit responder set the 2008 code only ever had as a latch <em>count</em>
     * @param requestTime the simulated instant the round was opened at
     *        ({@code Cybele.getTime} at {@code Planning.java:76})
     * @param openedAtNanos a wall-clock {@code System.nanoTime()} stamp for the caller's
     *        watchdog; never used as simulated time
     */
    public VoteRound(String train, String from, List<String> path, long requestTime, long openedAtNanos) {
        if (train == null || from == null || path == null) {
            throw new IllegalArgumentException("train, from and path must not be null");
        }
        if (path.isEmpty()) {
            throw new IllegalArgumentException("a round with no expected responders would close "
                    + "before it opened and then take max() of an empty list");
        }
        this.train = train;
        this.from = from;
        this.path = Collections.unmodifiableList(new ArrayList<String>(path));
        this.requestTime = requestTime;
        this.openedAtNanos = openedAtNanos;
        this.outstanding = this.path.size();
    }

    /**
     * Records one arriving vote.
     *
     * <p><b>Two effects, deliberately not tied to each other</b> — see the DEF-05 note on the
     * class. The ballot is stored under the voter's name, overwriting any previous one; the
     * outstanding count is decremented regardless of whether that was a new voter.
     *
     * @param voter the voting station or track
     * @param diff the delay difference it needs, as {@code computeDifference} returned it
     * @return {@code true} if this arrival closed the round — once, and only once
     */
    public boolean record(String voter, long diff) {
        if (voter == null) {
            throw new IllegalArgumentException("voter must not be null");
        }
        ballots.put(voter, Long.valueOf(diff));
        if (closed) {
            // countDown() past zero is a no-op. The ballot above is still stored, because the
            // 2008 multimap put is unconditional too -- and because a caller that keeps a
            // closed round around should see what arrived late.
            return false;
        }
        outstanding--;
        if (outstanding == 0) {
            closed = true;
            return true;
        }
        return false;
    }

    /** @return the train this round plans */
    public String train() {
        return train;
    }

    /** @return the train's origin station */
    public String from() {
        return from;
    }

    /** @return the expected responders, in path order; unmodifiable */
    public List<String> path() {
        return path;
    }

    /** @return the simulated instant the round opened at */
    public long requestTime() {
        return requestTime;
    }

    /** @return the wall-clock {@code System.nanoTime()} stamp the round opened at */
    public long openedAtNanos() {
        return openedAtNanos;
    }

    /** @return how many more arrivals the round is waiting for; the latch count */
    public int outstanding() {
        return outstanding;
    }

    /** @return whether the round has closed */
    public boolean isClosed() {
        return closed;
    }

    /**
     * The ballots, one per <b>distinct</b> voter, in first-vote order.
     *
     * <p>Shorter than {@link #path()} exactly when DEF-05 struck. The order is the port's
     * choice and is observationally free: the only two things the 2008 caller does with this
     * list are {@code size()} and {@link VoteEnvelope#max}, and neither depends on order.
     *
     * @return the delay differences
     */
    public List<Long> ballots() {
        return Collections.unmodifiableList(new ArrayList<Long>(ballots.values()));
    }

    /**
     * The expected responders that have not voted yet.
     *
     * <p>The 2008 planner could not name these — it had a count and nothing else — which is
     * why one lost vote presented as a silent hang (DEF-22). This is what makes the watchdog's
     * report say <em>who</em> did not answer.
     *
     * <p>Note this is a <em>distinct-voter</em> view, so it can be non-empty on a
     * <b>closed</b> round. That is not an inconsistency; it is DEF-05, visible.
     *
     * @return the silent members of the path, in path order
     */
    public List<String> missingResponders() {
        final List<String> missing = new ArrayList<String>();
        for (String voter : path) {
            if (!ballots.containsKey(voter)) {
                missing.add(voter);
            }
        }
        return Collections.unmodifiableList(missing);
    }

    /**
     * Marks the round as reported by a watchdog, so the alarm fires once rather than on every
     * tick — an unbounded stderr spew would drown #12's {@code ErrorScanner} rather than
     * inform it.
     *
     * @return {@code true} the first time it is called, {@code false} afterwards
     */
    public boolean markReported() {
        if (reported) {
            return false;
        }
        reported = true;
        return true;
    }

    /** @return whether a watchdog has already reported this round */
    public boolean isReported() {
        return reported;
    }

    @Override
    public String toString() {
        return "VoteRound[train=" + train + " path=" + path.size() + " outstanding=" + outstanding
                + " ballots=" + ballots.size() + (closed ? " closed" : " open") + "]";
    }
}
