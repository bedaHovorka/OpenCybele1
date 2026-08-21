package cz.vutbr.fit.ags.railway.jade.clock;

import cz.vutbr.fit.ags.railway.domain.clock.AgentClock;
import cz.vutbr.fit.ags.railway.domain.clock.SimClock;
import jade.core.Agent;
import jade.core.behaviours.TickerBehaviour;

/**
 * The one piece of the clock that needs JADE: a per-agent granularity ticker that drains that
 * agent's due simulated-time wake-ups <b>on the agent's own thread</b> (#29).
 *
 * <p>Add exactly one to each agent that arms simulated-time timers:</p>
 * <pre>
 * addBehaviour(new ClockTickerBehaviour(this, GRANULARITY_MS, agentClock));
 * </pre>
 *
 * <h2>Why a ticker and not a {@code WakerBehaviour} per timer</h2>
 * A {@code WakerBehaviour} deadline is <b>wall-clock</b> and is fixed when the behaviour is
 * added. The simulation needs three things it cannot give:
 * <ul>
 *   <li><b>Pace.</b> {@code Gui.java:98} changes the pace at runtime and Cybele rescales every
 *       pending timer. A pending {@code WakerBehaviour} would have to be removed and re-added
 *       with a recomputed wall-clock deadline, for every armed timer on every agent, on every
 *       button press — a rescale pass with a rounding error per timer per press.</li>
 *   <li><b>Pause.</b> {@code Cybele.pauseClock} freezes simulated time; a
 *       {@code WakerBehaviour}'s deadline keeps arriving.</li>
 *   <li><b>A shared clock.</b> Two agents' wake-ups must be ordered by one simulated time, not
 *       by two independent readings of {@code System.currentTimeMillis()}.</li>
 * </ul>
 * The deadline heap in {@link AgentClock} is keyed on absolute <em>simulated</em> milliseconds,
 * so a pace change moves nothing and a pause stops everything, and this behaviour only has to
 * ask "what is due now?" at a steady real-time cadence.
 *
 * <h2>Granularity</h2>
 * A wake-up fires at the first tick at or after its deadline, so it is late by up to
 * {@code granularityMs × pace} simulated milliseconds. Cybele is late too — a zero-delay timer
 * fires in 1–5 ms (SEM-06) — and the normalizer projects the clock-derived trace families away
 * (#21, {@code docs/trace-format.md}), so the contract this must meet is <em>ordering</em>, not
 * timestamp equality. Pick a granularity well below the shortest simulated interval the
 * simulation can distinguish; the railway's shortest is a 1 s road, i.e. 1000 simulated ms.
 *
 * <p>JADE's ticker period is real time, so the simulated resolution moves with the pace: at
 * pace 8 a 10 ms granularity is 80 simulated ms. If a scenario runs at a high pace, shorten the
 * granularity rather than assuming it still fits.</p>
 *
 * <h2>What it deliberately does not do</h2>
 * It does not own a thread, does not catch exceptions from the wake-ups it fires, and does not
 * touch {@link SimClock}'s controls. A throwing wake-up propagates into JADE's behaviour
 * scheduler exactly as any other behaviour's would, and the wake-ups the drain had not yet
 * reached stay armed — see {@link AgentClock#runDue()}.
 */
public final class ClockTickerBehaviour extends TickerBehaviour {

    private static final long serialVersionUID = 1L;

    private final AgentClock clock;

    /**
     * @param agent the owning agent; its thread is the one the wake-ups will run on
     * @param granularityMs the ticker period in <b>real</b> milliseconds; must be positive
     * @param clock the agent's own {@link AgentClock}, sharing the simulation's one
     *     {@link SimClock}
     */
    public ClockTickerBehaviour(Agent agent, long granularityMs, AgentClock clock) {
        super(agent, granularityMs);
        if (clock == null) {
            throw new IllegalArgumentException("clock must not be null");
        }
        this.clock = clock;
    }

    /** @return the agent clock this behaviour drains */
    public AgentClock agentClock() {
        return clock;
    }

    @Override
    protected void onTick() {
        clock.runDue();
    }
}
