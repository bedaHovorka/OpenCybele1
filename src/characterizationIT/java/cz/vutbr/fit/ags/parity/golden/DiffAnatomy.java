package cz.vutbr.fit.ags.parity.golden;

import cz.vutbr.fit.ags.parity.normalize.CanonicalTraceNormalizer;
import cz.vutbr.fit.ags.parity.spi.TraceNormalizer;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Why a golden comparison failed, in the two terms that decide the triage
 * (<a href="https://github.com/bedaHovorka/OpenCybele1/issues/39">#39</a>).
 *
 * <p>A positional diff answers "line 286 differs" and nothing else, and the first question a
 * triager has to answer is a different one: <em>did the run do something else, or the same things
 * in another order?</em> Those two have opposite resolutions —
 * {@code Phase1.md} L82 (a) fix the port versus (b) fix the harness — and separating them by hand
 * took a day of tooling on #39's first gate run. This class does it in the failure report:
 *
 * <ol>
 *   <li><b>The multiset.</b> Same lines in another order, or genuinely different lines? A
 *       multiset delta is a behaviour difference and is the port's until proven otherwise. An
 *       empty delta rules that out and points at the ordering rules.</li>
 *   <li><b>The segmentation margins.</b> {@link CanonicalTraceNormalizer}'s {@code burst-order}
 *       segments on a single simulated-millisecond gap, and
 *       {@code parity-tests/scenarios/COVERAGE.md} §12.2 already warns that a gap sitting <em>near</em>
 *       the width is a coin toss: when it crosses, two bursts merge, the sort re-orders them, and
 *       the run fails with a diff that looks like a content change and is not. So the report names
 *       the gaps closest to the width, on the RAW capture, with their margins.</li>
 * </ol>
 *
 * <p><strong>This measures; it does not decide, and it never relaxes anything.</strong> Nothing
 * here feeds back into {@link TraceComparator} — a run with a small margin still fails, exactly as
 * before. The one thing that changes is that the failure says which of the two questions it is.
 *
 * <p><strong>The width is read from the pipeline, not from the default</strong>, via
 * {@link CanonicalTraceNormalizer#findIn(TraceNormalizer)}. An adapter running a widened normalizer
 * would otherwise be reported against a constant it is not using — the same trap
 * {@code OpenCybeleStrictIT}'s segment-count floor documents.
 *
 * @param sameMultiset  true when golden and run contain exactly the same lines with the same
 *                      multiplicities, and differ only in position
 * @param onlyInGolden  lines the golden has and the run does not, with multiplicity
 * @param onlyInRun     lines the run has and the golden does not, with multiplicity
 * @param width         the segmentation width actually in use, in simulated ms, or -1 when the
 *                      adapter's normalizer has no {@link CanonicalTraceNormalizer} stage
 * @param margins       the raw inter-line gaps nearest {@code width}, closest first
 */
public record DiffAnatomy(boolean sameMultiset, List<String> onlyInGolden, List<String> onlyInRun,
        long width, List<Margin> margins) {

    /** How many near-width gaps are worth printing before the report stops being readable. */
    private static final int MARGINS_REPORTED = 5;

    /** No {@link CanonicalTraceNormalizer} in the pipeline, so there is no width to measure against. */
    public static final long NO_WIDTH = -1L;

    public DiffAnatomy {
        onlyInGolden = List.copyOf(onlyInGolden);
        onlyInRun = List.copyOf(onlyInRun);
        margins = List.copyOf(margins);
    }

    /**
     * One raw inter-line gap and how far it sits from the segmentation width.
     *
     * @param gap    the gap in simulated ms
     * @param margin {@code gap - width}; negative means the two lines shared a burst, positive
     *               means they were split, and near zero means the next run may decide otherwise
     * @param before the raw line on the earlier side
     * @param after  the raw line on the later side
     */
    public record Margin(long gap, long margin, String before, String after) {}

    /**
     * @param golden the normalized golden
     * @param run    the normalized run
     * @param raw    the run's RAW captured stream — gaps are a property of the capture, and field 2
     *               is erased by the time the trace is normalized
     */
    public static DiffAnatomy of(List<String> golden, List<String> run, List<String> raw,
            TraceNormalizer normalizer) {
        Map<String, Integer> inGolden = counts(golden);
        Map<String, Integer> inRun = counts(run);
        List<String> onlyGolden = excess(inGolden, inRun);
        List<String> onlyRun = excess(inRun, inGolden);
        long width = CanonicalTraceNormalizer.findIn(normalizer)
                .map(CanonicalTraceNormalizer::segmentGapTicks)
                .orElse(NO_WIDTH);
        return new DiffAnatomy(onlyGolden.isEmpty() && onlyRun.isEmpty(),
                onlyGolden, onlyRun, width, width == NO_WIDTH ? List.of() : margins(raw, width));
    }

    /** The report lines, in the shape {@code ScenarioRunner} prints its details in. */
    public List<String> describe() {
        List<String> out = new ArrayList<>();
        if (sameMultiset) {
            out.add("SAME MULTISET: every line of the golden occurs in the run, with the same"
                    + " multiplicity. The run did the same things in a different order, so this is"
                    + " an ORDERING difference — look at the segmentation margins below before"
                    + " looking at the port.");
        } else {
            out.add("DIFFERENT LINES: " + onlyInGolden.size() + " line(s) only in the golden, "
                    + onlyInRun.size() + " only in the run. That is a behaviour difference, not a"
                    + " reordering, and Phase1.md L82 makes it the port's until proven otherwise.");
            for (String line : onlyInGolden.subList(0, Math.min(5, onlyInGolden.size()))) {
                out.add("  only in golden: " + line);
            }
            for (String line : onlyInRun.subList(0, Math.min(5, onlyInRun.size()))) {
                out.add("  only in run   : " + line);
            }
        }
        if (width == NO_WIDTH) {
            out.add("segmentation: this adapter's normalizer has no CanonicalTraceNormalizer stage,"
                    + " so there is no burst width to measure a margin against.");
            return List.copyOf(out);
        }
        out.add("burst width in use: " + width + " simulated ms (burst-order). The gaps nearest it,"
                + " on the RAW capture — a gap within a few ms of the width is a coin toss, and when"
                + " it crosses, two bursts merge or split and the sort re-orders them"
                + " (COVERAGE.md §12.2):");
        for (Margin margin : margins) {
            out.add(String.format("  gap %d ms (margin %+d, %s) between  %s  |  %s",
                    margin.gap(), margin.margin(),
                    margin.margin() > 0 ? "split" : "merged", margin.before(), margin.after()));
        }
        return List.copyOf(out);
    }

    private static Map<String, Integer> counts(List<String> lines) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (String line : lines) {
            counts.merge(line, 1, Integer::sum);
        }
        return counts;
    }

    private static List<String> excess(Map<String, Integer> a, Map<String, Integer> b) {
        List<String> out = new ArrayList<>();
        for (Map.Entry<String, Integer> entry : a.entrySet()) {
            int surplus = entry.getValue() - b.getOrDefault(entry.getKey(), 0);
            for (int i = 0; i < surplus; i++) {
                out.add(entry.getKey());
            }
        }
        return out;
    }

    /**
     * The gaps nearest the width, measured on the raw stream.
     *
     * <p>Only canonical lines carry a tick, and a diagnostic never does, so the raw stream can be
     * read directly rather than filtered first: a line whose field 2 is not a number is skipped by
     * the same parse that would have skipped it after filtering.
     */
    private static List<Margin> margins(List<String> raw, long width) {
        List<String> lines = new ArrayList<>();
        List<Long> ticks = new ArrayList<>();
        for (String line : raw) {
            String[] fields = line.split("\\|", -1);
            if (fields.length != 7) {
                continue;
            }
            try {
                ticks.add(Long.parseLong(fields[1]));
                lines.add(line);
            } catch (NumberFormatException notATick) {
                // an already-projected line; it carries no gap to measure
            }
        }
        List<Margin> all = new ArrayList<>();
        for (int i = 1; i < ticks.size(); i++) {
            long gap = ticks.get(i) - ticks.get(i - 1);
            all.add(new Margin(gap, gap - width, lines.get(i - 1), lines.get(i)));
        }
        all.sort((x, y) -> Long.compare(Math.abs(x.margin()), Math.abs(y.margin())));
        return all.subList(0, Math.min(MARGINS_REPORTED, all.size()));
    }
}
