package cz.vutbr.fit.ags.parity.normalize;

import cz.vutbr.fit.ags.parity.spi.TraceNormalizer;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parity normalizer (<a href="https://github.com/bedaHovorka/OpenCybele1/issues/21">#21</a>):
 * projects a captured run down to the part of it that is reproducible, so that a golden pins
 * behaviour rather than a scheduler and a clock.
 *
 * <p>It consumes the canonical trace of {@code docs/trace-format.md} —
 * {@code agent|tick|event|from|to|performative|payload} — and it is written against
 * <strong>that contract</strong>, not against any implementation: nothing here names Cybele, JADE
 * or Jason, and the same instance is meant to normalize all three branches. Rules that look
 * application-shaped ({@code STATION_INFO}'s {@code occupied}, the two {@code println} families)
 * are contract-level: the fifteen events and the two {@code println}s are fixed by
 * {@code docs/trace-format.md} and every port must reproduce them.
 *
 * <p>Diagnostics are <em>not</em> this class' job. {@link DiagnosticFilter} removes them, declared
 * per adapter, and this normalizer is chained after it
 * ({@code new DiagnosticFilter(prefixes).andThen(new CanonicalTraceNormalizer())}).
 *
 * <h2>The rules, and the variance source each one answers</h2>
 *
 * <p>Every rule below is justified by an <em>observed</em> variance source, carries that
 * justification in {@link TraceRule#because()}, and can be switched off individually with
 * {@link #without(String)} so that {@code TraceNormalizerIT} can show the suite go red without it.
 * The full write-up, with the measurements, is {@code docs/trace-normalizer.md}.
 *
 * <ol>
 *   <li><b>{@code tick}</b> — field 2, on every line. Erased. The simulated clock advances with the
 *       wall clock, so the same event lands either side of a tick boundary run to run.</li>
 *   <li><b>{@code expected}</b> — {@code VOTE_REQUEST}'s payload. <em>Quantised.</em></li>
 *   <li><b>{@code planned}</b> — {@code VOTE_RESULT}'s payload. <em>Quantised.</em></li>
 *   <li><b>{@code diff}</b> — {@code VOTE}'s payload, the delay each voter demands.
 *       <em>Quantised</em>, and this one matters most: it is the only payload carrying the
 *       election's arithmetic, and erasing it left a port free to return zero from every vote and
 *       still match byte for byte.</li>
 *   <li><b>{@code departure}</b> — the application's own {@code "<train> in <station> at <n>"}
 *       {@code println}, which is in the captured stream and predates the probe.
 *       <em>Quantised.</em></li>
 *   <li><b>{@code occupied}</b> — {@code STATION_INFO}'s payload. Erased. Not a clock value at
 *       all: the payload crosses the channel by reference under {@code Local;NoSerialization} and
 *       the station keeps mutating it, so the same physical send is recorded with a different
 *       occupancy depending on which thread got there first (INVENTORY DEF-13). No capture point
 *       removes it.</li>
 *   <li><b>{@code road-state}</b> — {@code ROAD_STATE}'s payload. Erased. Not a race on the value:
 *       {@code RoadAgent.enter} and {@code leave} are both {@code synchronized} and both mutate
 *       {@code state} and publish it inside the same lock. What varies is <em>which code path the
 *       road took</em> — see {@code docs/trace-normalizer.md} §2.4.</li>
 *   <li><b>{@code performative}</b> — field 6. Erased. Constant {@code -} on the baseline; JADE
 *       (#27) and Jason (#48) will populate it, and without erasing it every line diffs on every
 *       branch.</li>
 *   <li><b>{@code startup-block}</b>, <b>{@code burst-order}</b>, <b>{@code println-streams}</b> —
 *       see {@link OrderingRule}.</li>
 * </ol>
 *
 * <p><strong>Erasing is the last resort, not the default.</strong> A value whose jitter is small
 * against its signal is quantised instead: {@code diff=41904} becomes {@code diff=<T~41000>}, which
 * still fails a port that votes zero, omits a road delay (1–5 s in this topology) or routes a train
 * the wrong way. The cost is stated rather than hidden: values below one quantum are
 * indistinguishable from zero.
 * </ol>
 *
 * <h2>Idempotence, and why it matters here</h2>
 *
 * <p>{@code normalize(normalize(x))} equals {@code normalize(x)}. That is not decoration: the
 * runner normalizes the <em>golden</em> as well as the run before comparing them, which is what
 * lets a golden recorded before this class existed be compared under it with
 * <strong>no re-recording</strong> — the thing {@code Phase1.md} L7 forbids. A trace whose canonical
 * lines already carry {@code <T>} in field 2 is recognised as projected and its order is left
 * alone; the projections themselves cannot match their own output.
 *
 * <h2>What is deliberately <em>not</em> normalized</h2>
 *
 * <ul>
 *   <li><b>A missing entity.</b> DEF-02 drops trains, so the departure <em>set</em> varies. A
 *       dropped train is absent, not reordered, and no normalisation invents a line a run never
 *       printed. That is a scenario-selection problem (#23).</li>
 *   <li><b>Lines emitted after a run's stop bound.</b> They are a shutdown race, not a value, and
 *       the normalizer has no way to know what the bound was.</li>
 * </ul>
 */
public final class CanonicalTraceNormalizer implements TraceNormalizer {

    /** Replaces any value derived from the simulated clock. */
    public static final String TIME = "<T>";

    /** Replaces {@code STATION_INFO}'s aliased occupancy count. */
    public static final String COUNT = "<N>";

    /** Replaces field 6, the performative. */
    public static final String PERFORMATIVE = "<P>";

    /** Replaces {@code ROAD_STATE}'s path-dependent state token. */
    public static final String STATE = "<S>";

    /**
     * Resolution kept for a quantised time value, in simulated milliseconds.
     *
     * <p>Measured across 8 runs, aligning all 93 votes by {@code (train, voter)}: 78 values are
     * identical in every run and 15 vary — and every varying value moves inside a band of 8–24 ms
     * on a magnitude of up to 41 896. Two orders of magnitude separate the jitter from the signal,
     * so a 1 000 ms bucket has ~40× headroom and every one of the 15 bands sits inside a single
     * bucket (verified over 52 captures, {@code docs/trace-normalizer.md} §2.2).
     *
     * <p>Unlike {@link #DEFAULT_SEGMENT_GAP_TICKS} this constant sits in a real gap in the data
     * rather than in a continuum — which is the only reason it is defensible, and it is why the two
     * are documented as different kinds of number rather than as two tuning knobs.
     */
    public static final long TIME_QUANTUM = 1000L;

    /**
     * Default burst width, in simulated milliseconds.
     *
     * <p><strong>There is no clean valley to put this in, and pretending otherwise would be the lie
     * that makes it look principled.</strong> Pooled over 52 captured runs the inter-line gap
     * distribution is continuous: 26 848 gaps of 0 ms, 2 497 of 8 ms, then a thin tail with no empty
     * band anywhere between 16 ms and 400 ms. So this is a tuning constant chosen by measurement,
     * and it is exposed rather than hidden for that reason. Contrast {@link #TIME_QUANTUM}, which
     * does sit in a real gap in the data.
     *
     * <p>What was measured is how often two runs with <em>identical content</em> still come out in a
     * different order — i.e. how often the segmentation itself moved. Over all 52 captures:
     *
     * <pre>
     *   gap (ms)      100  120  150  180  200  220  250  300  400  500  1000  5000
     *   extra orders    2    2    1    1    0    0    3    4    5    1     0     0
     *   segment count  23+  23+  23+  22+  22+  22+  20+  18+  15+  12+   8+     1
     * </pre>
     *
     * <p>200 and 220 are the only narrow widths at zero. 220 is taken because an independent
     * 8-capture sweep found the <em>segment count</em> stable at 220–250 and jittering at 200, and
     * because it is no worse anywhere here.
     *
     * <p><strong>The segment count jitters even at 220.</strong> Across 14 captures of
     * {@code opencybele-strict} it is 22 or 23 at 200 and at 220 alike. That is not a contradiction:
     * a boundary can move without changing the output, because the two lines either side of it sort
     * the same way whether they share a burst or not. It does mean the count is a health check, not
     * an invariant, and {@link #segments(List)} should be asserted with a floor rather than an
     * equality.
     *
     * <p>Widening further buys the last ordering back — and 5 000 ms collapses the run into one
     * burst, at which point the golden pins a <strong>multiset and no order at all</strong>. That is
     * the "lock that cannot fail" shape this project has rejected four times, which is why
     * {@code OpenCybeleStrictIT} asserts a floor on the segment count of the normalizer
     * <em>the adapter is actually using</em>.
     */
    public static final long DEFAULT_SEGMENT_GAP_TICKS = 220L;

    /** The canonical line has exactly seven {@code |}-separated fields. */
    private static final int FIELDS = 7;

    private static final int FIELD_TICK = 1;
    private static final int FIELD_EVENT = 2;
    private static final int FIELD_PERFORMATIVE = 5;

    /**
     * The two events emitted from a static object's constructor, per {@code docs/trace-format.md}
     * CH-10 and CH-11. Their opening run is the startup block.
     */
    private static final Set<String> CONSTRUCTOR_STATE_EVENTS = Set.of("STATION_INFO", "ROAD_STATE");

    /** {@code <train> in <station> at <departure>} — the application's own departure println. */
    private static final Pattern DEPARTURE_PRINTLN =
            Pattern.compile("^(\\S+) in (\\S+) at (-?\\d+|"
                    + Pattern.quote(FieldProjection.TIME_OPEN) + "[^>]*>)$");

    /** {@code <train> started} — the other application println; carries no varying value. */
    private static final Pattern STARTED_PRINTLN = Pattern.compile("^(\\S+) started$");

    private static final List<FieldProjection> ALL_PROJECTIONS = List.of(
            FieldProjection.erase("tick",
                    "field 2 is Cybele.getTime read at handling time; the clock advances with the"
                            + " wall clock, so every line's tick moves run to run"
                            + " (docs/trace-format.md, family 1). Erased rather than quantised"
                            + " because it is on EVERY line and every payload time it could pin is"
                            + " already pinned, at resolution, by the quantised rules below",
                    Pattern.compile("^([^|]*)\\|-?\\d+\\|"), "$1|" + TIME + "|", true),
            FieldProjection.erase("expected",
                    "VOTE_REQUEST.expected is Cybele.getTime at the moment the election opened"
                            + " (docs/trace-format.md, family 2). ERASED rather than quantised, and"
                            + " that was measured rather than assumed: it is an absolute clock"
                            + " instant whose base jitters ~200 ms (arrival of vl2 measured at"
                            + " 9840/9928/9944/10032 across four runs), and every leg of one"
                            + " election is base + k*1000 because road delays are whole seconds --"
                            + " so ANY 1000 ms bucket is resonant with the data and one base"
                            + " crossing a boundary flips all eleven legs at once. Quantising it"
                            + " took the strict scenario from 14/14 byte-identical to 5 distinct"
                            + " traces in 14. See docs/trace-normalizer.md 2.2",
                    Pattern.compile("\\bexpected=-?\\d+"), "expected=" + TIME, true),
            FieldProjection.erase("planned",
                    "VOTE_RESULT.planned is the agreed departure instant, from the same reading"
                            + " (docs/trace-format.md, family 3), and it fails to quantise for"
                            + " exactly the reason expected does -- same base, same whole-second"
                            + " legs. What the election COMPUTES is pinned instead, by diff below",
                    Pattern.compile("\\bplanned=-?\\d+"), "planned=" + TIME, true),
            FieldProjection.quantise("diff",
                    "VOTE.diff is the delay each voter demands, computed against its timetable in"
                            + " absolute simulated time (docs/trace-format.md, family 4). Measured"
                            + " over 8 runs aligned by (train, voter): 78 of 93 values identical in"
                            + " every run, 15 varying inside an 8-24 ms band on magnitudes up to"
                            + " 41896. Erased outright, rewriting every diff in a real capture to 0"
                            + " passed the strict golden byte-identically -- and diff is the only"
                            + " payload carrying the arithmetic that #28 extracts, #34 restructures"
                            + " and #36/#43 must reimplement",
                    Pattern.compile("\\bdiff=(-?\\d+)"), "diff={Q}", true, TIME_QUANTUM, 1),
            FieldProjection.erase("departure",
                    "the application's own \"<train> in <station> at <n>\" println carries the"
                            + " departure instant, same clock origin (docs/trace-format.md,"
                            + " family 5) -- and the same ~200 ms base jitter: vl3's departure"
                            + " measured at 12944/13032/13056/13144 across four runs, which spans"
                            + " a 1000 ms bucket boundary on its own",
                    Pattern.compile("^(\\S+ in \\S+ at )-?\\d+$"), "$1" + TIME, false),
            FieldProjection.erase("occupied",
                    "STATION_INFO.occupied is a live alias the station keeps mutating under"
                            + " Local;NoSerialization (INVENTORY DEF-13): measured 6-12 lines"
                            + " differing across three runs at one pinned seed, counts trading in"
                            + " lockstep between adjacent values on the same station. Erased, not"
                            + " quantised -- the values are 0..capacity, so no resolution below the"
                            + " jitter exists",
                    Pattern.compile("\\boccupied=-?\\d+"), "occupied=" + COUNT, true),
            FieldProjection.erase("road-state",
                    "ROAD_STATE.state records which code path RoadAgent took, not a raced value:"
                            + " enter() and leave() are both synchronized and both mutate state and"
                            + " publish it inside the same lock. When ENTER arrives before LEAVE the"
                            + " road is still busy, the train goes down push()/queue.offer, and"
                            + " leave() hands it over directly via pop()/acceptTrain -- so the pair"
                            + " reads TRAVEL_LEFT,TRAVEL_LEFT instead of FREE,TRAVEL_LEFT. Measured"
                            + " on tr6: 1 run in 8, and without the rule strict flakes at that rate."
                            + " The baseline cannot pin which path it takes; the path is real all"
                            + " the same, and its other signature -- ENTER_REPLY moving across"
                            + " LEAVE -- is what #39 should check before dismissing a diff here",
                    Pattern.compile("^([^|]*\\|[^|]*\\|ROAD_STATE\\|(?:[^|]*\\|){3})state=[^|]*$"),
                    "$1state=" + STATE, true),
            FieldProjection.erase("performative",
                    "field 6 is the constant '-' on the baseline and is populated on JADE (#27) and"
                            + " Jason (#48); without erasing it every line diffs on every branch"
                            + " (decided in #20)",
                    Pattern.compile("^((?:[^|]*\\|){" + FIELD_PERFORMATIVE + "})[^|]*\\|"),
                    "$1" + PERFORMATIVE + "|", true));

    private final long segmentGapTicks;
    private final List<FieldProjection> projections;
    private final Set<OrderingRule> ordering;

    public CanonicalTraceNormalizer() {
        this(DEFAULT_SEGMENT_GAP_TICKS, ALL_PROJECTIONS, EnumSet.allOf(OrderingRule.class));
    }

    public CanonicalTraceNormalizer(long segmentGapTicks) {
        this(segmentGapTicks, ALL_PROJECTIONS, EnumSet.allOf(OrderingRule.class));
    }

    private CanonicalTraceNormalizer(long segmentGapTicks, List<FieldProjection> projections,
            Set<OrderingRule> ordering) {
        if (segmentGapTicks < 0) {
            throw new IllegalArgumentException("segmentGapTicks must not be negative");
        }
        this.segmentGapTicks = segmentGapTicks;
        this.projections = List.copyOf(projections);
        this.ordering = ordering.isEmpty() ? EnumSet.noneOf(OrderingRule.class) : EnumSet.copyOf(ordering);
    }

    /**
     * Finds the projection stage inside an adapter's normalizer, however it was chained.
     *
     * <p>Exists so a scenario's health checks measure <strong>the normalizer actually in use</strong>
     * rather than a fresh default. Building {@code new CanonicalTraceNormalizer()} to check a burst
     * count is a check on the constant, not on the pipeline: widen the adapter's width and the
     * check keeps measuring the default and keeps passing, while the comparison it was guarding has
     * collapsed to one segment.
     */
    public static java.util.Optional<CanonicalTraceNormalizer> findIn(TraceNormalizer normalizer) {
        if (normalizer instanceof CanonicalTraceNormalizer canonical) {
            return java.util.Optional.of(canonical);
        }
        if (normalizer instanceof ChainedNormalizer chained) {
            java.util.Optional<CanonicalTraceNormalizer> inNext = findIn(chained.next());
            return inNext.isPresent() ? inNext : findIn(chained.first());
        }
        return java.util.Optional.empty();
    }

    /** Every rule this normalizer applies, projections and ordering rules alike. */
    public static List<TraceRule> allRules() {
        List<TraceRule> rules = new ArrayList<>(ALL_PROJECTIONS);
        rules.addAll(EnumSet.allOf(OrderingRule.class));
        return List.copyOf(rules);
    }

    public static List<FieldProjection> allProjections() {
        return ALL_PROJECTIONS;
    }

    public long segmentGapTicks() {
        return segmentGapTicks;
    }

    /**
     * A copy with one rule removed, by {@link TraceRule#id()}.
     *
     * <p>This is how each rule is shown to be load-bearing: the mutation tests build a normalizer
     * without rule X, re-normalize two real captures that differ only in the variance X answers,
     * and assert they now disagree. A rule whose removal changes nothing is not pinning anything
     * and should be deleted rather than kept for tidiness.
     */
    public CanonicalTraceNormalizer without(String ruleId) {
        List<FieldProjection> kept = new ArrayList<>();
        for (FieldProjection projection : projections) {
            if (!projection.id().equals(ruleId)) {
                kept.add(projection);
            }
        }
        Set<OrderingRule> keptOrdering = EnumSet.noneOf(OrderingRule.class);
        for (OrderingRule rule : ordering) {
            if (!rule.id().equals(ruleId)) {
                keptOrdering.add(rule);
            }
        }
        if (kept.size() == projections.size() && keptOrdering.size() == ordering.size()) {
            throw new IllegalArgumentException("no such rule '" + ruleId + "'; known rules are "
                    + allRules().stream().map(TraceRule::id).toList());
        }
        return new CanonicalTraceNormalizer(segmentGapTicks, kept, keptOrdering);
    }

    // --- the pipeline -----------------------------------------------------------------------

    @Override
    public List<String> normalize(List<String> rawLines) {
        List<Entry> entries = new ArrayList<>(rawLines.size());
        for (String line : rawLines) {
            entries.add(Entry.of(line, projections));
        }

        List<Entry> departures = new ArrayList<>();
        List<Entry> started = new ArrayList<>();
        List<Entry> body = new ArrayList<>();
        for (Entry entry : entries) {
            if (ordering.contains(OrderingRule.PRINTLN_STREAMS) && entry.isDeparturePrintln()) {
                departures.add(entry);
            } else if (ordering.contains(OrderingRule.PRINTLN_STREAMS) && entry.isStartedPrintln()) {
                started.add(entry);
            } else {
                body.add(entry);
            }
        }

        // Already-projected input (a golden read back): field 2 carries no number to segment on, so
        // re-ordering is skipped and the pass is the identity. See "Idempotence" in the class doc.
        boolean projected = body.stream().anyMatch(Entry::canonical)
                && body.stream().filter(Entry::canonical).noneMatch(Entry::hasTick);

        List<String> out = new ArrayList<>(entries.size());
        int i = 0;
        while (i < body.size() && !body.get(i).canonical()) {
            out.add(body.get(i).projected());   // the kernel banner, before anything with a tick
            i++;
        }
        // The startup-block scan is guarded by !projected for the same reason the burst sort is,
        // and getting it wrong is worse. On a second pass the block is DETECTED differently: pass
        // one's burst sort can leave a STATION_INFO or ROAD_STATE line at the head of the first
        // burst, and pass two would then absorb it into the startup block and re-sort. That breaks
        // idempotence — and because ScenarioRunner normalizes the GOLDEN on every comparison, a
        // golden with that shape could never match its own recording. It would surface as a
        // positional strict diff and be reported as "a port bug until proven a harness defect".
        int startupEnd = i;
        while (!projected && startupEnd < body.size() && body.get(startupEnd).isConstructorState()) {
            startupEnd++;
        }
        List<String> startup = new ArrayList<>();
        for (int k = i; k < startupEnd; k++) {
            startup.add(body.get(k).projected());
        }
        if (ordering.contains(OrderingRule.STARTUP_BLOCK) && !projected) {
            startup.sort(NATURAL_ORDER);
        }
        out.addAll(startup);

        List<Entry> rest = body.subList(startupEnd, body.size());
        for (List<Entry> segment : segment(rest, projected)) {
            List<String> lines = new ArrayList<>(segment.size());
            for (Entry entry : segment) {
                lines.add(entry.projected());
            }
            if (ordering.contains(OrderingRule.BURST_ORDER) && !projected) {
                lines.sort(NATURAL_ORDER);
            }
            out.addAll(lines);
        }

        for (Entry entry : departures) {
            out.add(entry.projected());
        }
        List<String> startedLines = new ArrayList<>(started.size());
        for (Entry entry : started) {
            startedLines.add(entry.projected());
        }
        startedLines.sort(NATURAL_ORDER);
        out.addAll(startedLines);
        return List.copyOf(out);
    }

    /**
     * The bursts this normalizer would sort within, for a scenario that wants to check it still has
     * more than one.
     *
     * <p>This segments the whole stream, including the opening banner and startup block, so the
     * count is one higher than the number of bursts the body was sorted in. It is a health check,
     * not an index into the output.
     *
     * <p><strong>Hand it a RAW capture, not a normalized trace or a golden.</strong> An
     * already-projected trace has no parseable tick to segment on and this returns {@code 1} — which
     * is indistinguishable from the degeneracy the method exists to detect. It fails safe (a floor
     * assertion goes red rather than green) but it is the wrong measurement, and #23/#24 will copy
     * whatever pattern they find here.
     *
     * <p>A normalizer that collapses a whole run into a single burst has quietly stopped asserting
     * any order at all, which is the "lock that cannot fail" shape this project has rejected four
     * times. {@code OpenCybeleStrictIT} asserts a floor on this count rather than trusting the
     * default width to stay appropriate.
     */
    public List<List<String>> segments(List<String> rawLines) {
        List<Entry> body = new ArrayList<>();
        for (String line : rawLines) {
            Entry entry = Entry.of(line, projections);
            if (!entry.isDeparturePrintln() && !entry.isStartedPrintln()) {
                body.add(entry);
            }
        }
        List<List<String>> out = new ArrayList<>();
        for (List<Entry> segment : segment(body, false)) {
            List<String> lines = new ArrayList<>(segment.size());
            for (Entry entry : segment) {
                lines.add(entry.projected());
            }
            out.add(List.copyOf(lines));
        }
        return List.copyOf(out);
    }

    private List<List<Entry>> segment(List<Entry> entries, boolean projected) {
        List<List<Entry>> segments = new ArrayList<>();
        List<Entry> current = new ArrayList<>();
        long last = Long.MIN_VALUE;
        for (Entry entry : entries) {
            if (!projected && entry.hasTick()) {
                if (last != Long.MIN_VALUE && entry.tick() - last > segmentGapTicks) {
                    segments.add(current);
                    current = new ArrayList<>();
                }
                last = entry.tick();
            }
            current.add(entry);
        }
        if (!current.isEmpty()) {
            segments.add(current);
        }
        return segments;
    }

    // --- helpers ----------------------------------------------------------------------------

    /**
     * Digit-aware ordering, so {@code vl2} sorts before {@code vl10}. Any total order would do for
     * correctness — what a canonical sort needs is determinism, not meaning — but a golden is read
     * by people, and {@code vl10} between {@code vl1} and {@code vl2} reads as a bug.
     */
    public static final Comparator<String> NATURAL_ORDER = CanonicalTraceNormalizer::compareNatural;

    private static int compareNatural(String a, String b) {
        int i = 0;
        int j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i);
            char cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i;
                int sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) {
                    i++;
                }
                while (j < b.length() && Character.isDigit(b.charAt(j))) {
                    j++;
                }
                String da = a.substring(si, i).replaceFirst("^0+(?=.)", "");
                String db = b.substring(sj, j).replaceFirst("^0+(?=.)", "");
                if (da.length() != db.length()) {
                    return da.length() - db.length();
                }
                int cmp = da.compareTo(db);
                if (cmp != 0) {
                    return cmp;
                }
            } else {
                if (ca != cb) {
                    return Character.compare(ca, cb);
                }
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    /** One captured line, parsed once: the projection, and the raw tick the ordering needs. */
    private record Entry(String projected, boolean canonical, boolean hasTick, long tick,
            String event, boolean isDeparturePrintln, boolean isStartedPrintln) {

        static Entry of(String line, List<FieldProjection> projections) {
            String[] fields = line.split("\\|", -1);
            boolean canonical = fields.length == FIELDS;
            boolean hasTick = false;
            long tick = 0L;
            String event = null;
            if (canonical) {
                event = fields[FIELD_EVENT];
                try {
                    tick = Long.parseLong(fields[FIELD_TICK]);
                    hasTick = true;
                } catch (NumberFormatException alreadyProjected) {
                    hasTick = false;
                }
            }
            String projected = line;
            for (FieldProjection projection : projections) {
                projected = projection.apply(projected, canonical);
            }
            Matcher departure = DEPARTURE_PRINTLN.matcher(line);
            Matcher started = STARTED_PRINTLN.matcher(line);
            return new Entry(projected, canonical, hasTick, tick, event,
                    !canonical && departure.matches(), !canonical && started.matches());
        }

        boolean isConstructorState() {
            return canonical && CONSTRUCTOR_STATE_EVENTS.contains(event);
        }
    }

    /** The rule ids, for a failure report that wants to name what was applied. */
    public Set<String> ruleIds() {
        Set<String> ids = new LinkedHashSet<>();
        projections.forEach(p -> ids.add(p.id()));
        ordering.forEach(r -> ids.add(r.id()));
        return ids;
    }

    @Override
    public String toString() {
        return "CanonicalTraceNormalizer[gap=" + segmentGapTicks + ", rules=" + ruleIds() + "]";
    }
}
