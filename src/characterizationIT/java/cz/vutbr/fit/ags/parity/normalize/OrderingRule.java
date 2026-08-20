package cz.vutbr.fit.ags.parity.normalize;

/**
 * The rules that move lines rather than rewrite them. Each is separately switchable so that
 * {@code TraceNormalizerIT} can turn exactly one off and show the suite go red — a rule that cannot
 * fail is not a rule.
 */
public enum OrderingRule implements TraceRule {

    /**
     * Sort the opening block of station/track state lines by agent name.
     *
     * <p>{@code Cybele.createAgent} is asynchronous. The bootstrap loop requests creation in a
     * pinned order (#19), but the constructors <em>run</em> in a scheduling-dependent order —
     * measured six distinct orders in six runs, for stations and for tracks alike
     * ({@code docs/seeded-rng.md}). {@code RoadAgent}'s constructor ends in {@code sendState()} and
     * {@code Station}'s in {@code sendInfo()}, so the opening {@code ROAD_STATE}/{@code
     * STATION_INFO} lines are emitted in constructor-completion order.
     *
     * <p>This is strictly stronger than "sort within a tick": measured on this branch's smoke
     * scenario, the eight opening {@code STATION_INFO} lines land on one tick but the seven
     * {@code ROAD_STATE} lines straddle two (1104 and 1112 in the recorded golden), and <em>which</em>
     * of them lands on which side moves run to run. There is no tick boundary to sort within, so
     * the block is identified structurally — the maximal prefix of canonical lines whose event is
     * {@code STATION_INFO} or {@code ROAD_STATE} — and sorted as a whole.
     */
    STARTUP_BLOCK("startup-block",
            "Cybele.createAgent is asynchronous: six distinct constructor orders in six runs"
                    + " (docs/seeded-rng.md), and the opening state lines straddle two ticks so"
                    + " sorting within a tick cannot reach them"),

    /**
     * Sort lines within a <em>burst</em> — a maximal run of consecutive canonical lines separated
     * by no more than {@link CanonicalTraceNormalizer#segmentGapTicks()} simulated milliseconds.
     *
     * <p>Sorting within an <em>equal</em> tick is what {@code Phase1.md} L36 proposed and it is not
     * enough, because the tick itself jitters: the same event lands on 1336 in one run and 1344 in
     * the next, so two lines that shared a tick when the golden was recorded do not share one on
     * replay and the sort never sees them together. Measured on 13 runs of {@code opencybele-smoke}
     * at a pinned seed: equal-tick sorting left 190–220 of 623 lines displaced between any two
     * full-length runs, all of them pure reorderings of an identical multiset.
     *
     * <p><strong>There is no clean valley to put the width in.</strong> A three-run sample looked
     * like one; pooling 52 runs dissolves it into a continuum with no empty band between 16 ms and
     * 400 ms. The width was therefore chosen by measuring ordering instability directly, and it is
     * a constructor parameter because it is a tuning constant rather than a fact —
     * {@link CanonicalTraceNormalizer#DEFAULT_SEGMENT_GAP_TICKS} carries the sweep.
     *
     * <p><strong>What this deliberately stops asserting:</strong> the order of two lines emitted
     * within the same burst. That order is not a property of the system — the probe is one serial
     * observer of fifteen channels, so even one sender's messages reach it out of send order — and
     * a golden that pinned it would be pinning the scheduler. What survives is which lines are in
     * which burst, and the order of the bursts themselves.
     */
    BURST_ORDER("burst-order",
            "tick jitter of one or two 8 ms quanta moves lines across an equal-tick boundary:"
                    + " 190-220 of 623 lines displaced between full-length runs, identical multiset"),

    /**
     * Split the application's two {@code println} families out of the trace into two independent
     * streams, appended after it.
     *
     * <p>{@code Planning.placeTrainIntoFirstStation} prints {@code "<train> in <station> at <t>"} on
     * the main agent's activity thread and {@code Train.start} prints {@code "<train> started"} on
     * that train's own thread. The two race: at seed {@code 987654321} two trains departing in the
     * same simulated millisecond produced three orderings across six runs, and they also float
     * against the probe's own lines because they carry no tick to sort on.
     *
     * <p>Splitting them into separate streams is the option {@code #21} itself names, and it costs
     * nothing that is not recoverable: the departure <em>order</em> survives in the
     * {@code "in … at"} stream, which has a single serial producer, and independently in the
     * canonical {@code START} lines. The {@code "started"} stream has one producer per train, so
     * its cross-train order is arbitrary and it is sorted by train name instead.
     */
    PRINTLN_STREAMS("println-streams",
            "the two application printlns come from different threads and carry no tick:"
                    + " three orderings across six runs at one seed (issue #21)");

    private final String id;
    private final String because;

    OrderingRule(String id, String because) {
        this.id = id;
        this.because = because;
    }

    @Override
    public String id() {
        return id;
    }

    @Override
    public String because() {
        return because;
    }
}
