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

/**
 * How long a train actually takes to cross a track — extracted from
 * {@code RoadAgent.travelStart} (#28).
 *
 * <p>The draw itself stays with the agent: this class takes the already-drawn Gaussian as
 * a parameter, so it needs no {@link java.util.Random}, no seeding policy and no knowledge
 * of the per-agent streams (#15). Which value comes out of which stream is pinned by the
 * seed; what is done with it is pinned here.</p>
 */
public final class TravelDelay {

    /** Standard deviation of the travel-time jitter, in ms. The 2008 literal. */
    public static final int JITTER_MS = 500;

    private TravelDelay() {
        //EMPTY
    }

    /**
     * {@code delayInSeconds() + (long)(500*random.nextGaussian())}, transcribed.
     *
     * <p><b>DEF-16, pinned: the result may be negative</b> and is not clamped. For a
     * one-second track a draw below {@code -2} sigma produces a negative delay, which
     * Cybele fires <em>immediately</em> — an instantaneous traversal, measured at 2.2645 %
     * of draws for {@code tr1}/{@code tr2}. That is correct baseline behaviour and appears
     * in the goldens; {@code docs/defect-triage.md} §3.1 is explicit that "a port that
     * clamps to 0 produces a diff and that is the port bug". Do not add
     * {@code Math.max(0, ...)} here.</p>
     *
     * <p>The cast truncates toward zero, so it is not a floor: {@code (long)(-0.4)} is 0,
     * not −1. That asymmetry about zero is 2008 behaviour too.</p>
     *
     * @param baseMs the track's nominal travel time, in ms
     * @param gaussian one draw from a standard normal distribution
     * @return the delay to arm the travel timer with, possibly negative
     */
    public static long travelMs(long baseMs, double gaussian) {
        return baseMs + (long) (JITTER_MS * gaussian);
    }
}
