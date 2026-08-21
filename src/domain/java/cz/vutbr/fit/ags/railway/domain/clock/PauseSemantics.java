/*
 * Projekt AGS 2007/08
 * FIT VUT Brno
 *
 * Open Cybele 1
 *
 * Bedrich Hovorka
 * xhovor07@stud.fit.vutbr.cz
 */
package cz.vutbr.fit.ags.railway.domain.clock;

/**
 * How {@link SimClock#pause()} composes with itself — the one place the port deliberately
 * diverges from the kernel, with the kernel's behaviour kept available beside it.
 *
 * <p>Measured, not assumed. {@code docs/probes/ExpI.java} runs the application's own shape —
 * two <em>distinct Cybele agents</em>, each bracketing work with {@code pauseClock}/
 * {@code resumeClock} on their own handler threads, brackets overlapping on purpose — against
 * the real {@code CybeleImpl.jar}:</p>
 *
 * <pre>
 * control arm (agent A alone)        0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0 0
 * overlap arm (A, with B inside it)  190 190 190 189 190 190 190 190 190 189 ...
 * VERDICT 1 (early resume): 20/20 overlap trials saw the clock advance inside A's bracket
 * VERDICT 2 (exclusion):    B's body ran while A held the bracket: true
 * </pre>
 *
 * <p>Verdict 1 is #29's hypothesis, confirmed: agent B's {@code resumeClock} restarts the clock
 * while agent A still believes it is frozen. Verdict 2 is the larger finding — pausing a clock
 * stops simulated <em>time</em>, not <em>threads</em>, so the idiom never excluded anything and
 * was never a mutex in the first place. See {@code docs/clock-abstraction.md} §2.</p>
 */
public enum PauseSemantics {

    /**
     * Nested pauses are counted: {@code n} pauses need {@code n} resumes. <b>The default.</b>
     *
     * <p>Chosen because no golden pins the kernel's behaviour — the three bracketed sections
     * ({@code Planning.java:108-112}, {@code RoadAgent.java:132-134} and {@code :167-169}) are
     * a few statements long, measured at ~35 µs, and 1000 brackets of that realistic shape
     * produced 0 early resumes (SEM-02). So the quirk is reachable in principle and was not
     * observed in practice, which makes reproducing it a cost with no contract behind it.</p>
     *
     * <p>Counting is also what makes a bracket <em>safe to nest</em>, which the port needs the
     * moment two ported behaviours on the same agent both want one.</p>
     */
    COUNTED,

    /**
     * The kernel's own behaviour: a single {@code boolean}, no depth. A second
     * {@link SimClock#pause()} is a no-op and one {@link SimClock#resume()} restarts the clock
     * however many pauses are outstanding.
     *
     * <p>Confirmed twice over. Statically: {@code ContinuousClock.setPause()} opens
     * {@code if (paused) return;} and {@code setResume()} opens {@code if (!paused) return;} —
     * one field, no counter. Dynamically: {@code ExpB2} (two pauses, one resume, from one
     * thread) and {@code ExpI} (two agents, overlapping brackets, 60/60 across three runs).</p>
     *
     * <p>Kept so the port can reproduce the quirk rather than merely describe it, as
     * {@code docs/defect-triage.md} requires of every 2008 behaviour that is not repaired. Do
     * not select it to "be faithful": faithfulness here is measured against the goldens, and no
     * golden distinguishes the two.</p>
     */
    BOOLEAN_2008
}
