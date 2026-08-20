package cz.vutbr.fit.ags.parity.run;

import cz.vutbr.fit.ags.parity.ParityLayout;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpec;
import cz.vutbr.fit.ags.parity.spi.LauncherAdapter;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * The zero-flake gate of {@code Phase1.md} 1-PRE.2 — <em>"zero flake across ≥10 consecutive runs
 * per scenario"</em> — and an honest account of what that sentence can and cannot establish.
 *
 * <h2>Why the issue's own criterion is not sufficient, and is kept anyway</h2>
 *
 * <p>Reproducibility here has a <strong>session-level</strong> component, and it is measured, not
 * suspected. {@code docs/kernel-config.md} records 114 consecutive runs resolving the DEF-02
 * departure race identically, and a <em>later session</em> resolving it the other way 7 times in 9,
 * with nothing changed. Its own conclusion: <em>"whatever fixes it is a machine-state property that
 * persists for hours, not something that re-rolls per run"</em>, and — under "What this does not
 * answer" — <em>what</em> that property is "is not established here".
 *
 * <p>So there is nothing to query. This gate cannot read a machine state and tell you which mode
 * you are in, and it does not pretend to. {@code docs/defect-triage.md} §8.2 draws the only
 * conclusion available:
 *
 * <blockquote>A golden must be reproduced across separate <em>occasions</em>, not merely separate
 * processes. […] <strong>What proves nothing:</strong> any number of consecutive runs. Three green
 * runs in a row are the <em>expected</em> output of a stable mode.</blockquote>
 *
 * <p>N consecutive runs therefore establishes exactly one thing, and it is worth having:
 * <strong>within one session, this scenario is byte-reproducible</strong>. Everything that is
 * <em>not</em> session-level — thread interleaving, tick jitter, the startup race, the printlns'
 * race, the aliased payloads — re-rolls on every run and is caught by it. What it does not
 * establish is that a golden recorded today matches a run tomorrow.
 *
 * <p>That second claim is made executable rather than dropped. Every gate invocation appends a
 * <strong>ledger entry</strong> — boot id, timestamp, trace digest, entity-id set — and compares
 * against earlier entries for the same scenario. An earlier entry from a different boot, or more
 * than {@link #CROSS_OCCASION_HOURS} hours old, is <em>cross-occasion</em> evidence; one from this
 * boot minutes ago is not, and is reported as not being it. Running the gate twice in a loop
 * therefore cannot manufacture a green cross-occasion claim, which is precisely the failure
 * §8.2 warns #24 about ("a script that loops twice satisfies the letter while delivering exactly
 * the worthless consecutive-run evidence").
 *
 * <h2>What counts as a run at all</h2>
 *
 * <p>Every repetition goes through {@link ScenarioRunner}, so the whole judgement stack applies
 * before a run is allowed to contribute a trace: harness timeout, exit classification, the error
 * scan on the raw stream, the declared disposition, and the liveness floors. That matters here
 * specifically because of DEF-22 — roughly one run in 45 hangs with a clean process and nothing in
 * the stream. Such a run is killed as {@code HARNESS_TIMEOUT} and fails the gate; it can never be
 * counted as one of the N.
 */
public final class ParityGate {

    /** How many runs, {@code -Dparity.gate.runs=N}. Zero means the gate is not being asked for. */
    public static final String RUNS_PROPERTY = "parity.gate.runs";

    /** Where the ledger lives, {@code -Dparity.gate.ledger=…}. */
    public static final String LEDGER_PROPERTY = "parity.gate.ledger";

    /** The issue's number. Kept as the documented default rather than silently lowered. */
    public static final int DEFAULT_RUNS = 10;

    /**
     * Age at which an earlier ledger entry counts as a separate occasion.
     *
     * <p>{@code docs/kernel-config.md} says "two runs minutes apart will usually agree; two runs
     * hours apart may not", and {@code docs/defect-triage.md} §8.2 adds the honest caveat that
     * "hours" is an observation rather than a calibrated threshold — nobody has measured the
     * interval at which the mode flips. Four hours is therefore a convention, not a measurement,
     * and it is written here rather than in prose so that it can be argued with.
     */
    public static final long CROSS_OCCASION_HOURS = 4;

    private final ParityLayout layout;
    private final Path ledger;

    public ParityGate(ParityLayout layout) {
        this.layout = layout;
        String configured = System.getProperty(LEDGER_PROPERTY);
        this.ledger = Path.of(configured == null || configured.isBlank()
                ? "build/parity-gate" : configured);
    }

    /** How many runs the caller asked for; {@code 0} when the gate was not requested. */
    public static int requestedRuns() {
        String value = System.getProperty(RUNS_PROPERTY, "0");
        try {
            return Math.max(0, Integer.parseInt(value.trim()));
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("-D" + RUNS_PROPERTY + "=" + value + " is not a number");
        }
    }

    /** The command that runs this gate, for a skip message that is actionable. */
    public static String invocation(String scenarioId) {
        return "./gradlew parityGate -Popencybele.dist=<abs path to build/install/opencybele>"
                + " -Dparity.gate.runs=" + DEFAULT_RUNS + "   # scenario: " + scenarioId;
    }

    /**
     * Runs {@code spec} {@code runs} times and judges byte-identity.
     *
     * @throws ScenarioFailedException when a run failed, or the runs were not byte-identical
     */
    public Result run(ScenarioSpec spec, LauncherAdapter adapter, int runs) {
        if (runs < 2) {
            throw new IllegalArgumentException("a gate of " + runs + " run(s) establishes nothing;"
                    + " " + DEFAULT_RUNS + " is the figure Phase1.md 1-PRE.2 asks for");
        }
        ScenarioRunner runner = new ScenarioRunner(layout);
        Map<String, List<Integer>> byDigest = new LinkedHashMap<>();
        List<String> traceOfFirst = null;
        List<String> divergent = new ArrayList<>();
        Set<String> entityIds = new TreeSet<>();
        Instant started = Instant.now();

        for (int i = 1; i <= runs; i++) {
            // Any failure here — timeout, throwable, wrong disposition, liveness floor, golden
            // mismatch — propagates. A wedged run is not a data point, it is a red gate.
            RunReport report = runner.run(spec, adapter);
            List<String> trace = report.normalized();
            if (traceOfFirst == null) {
                traceOfFirst = trace;
                entityIds.addAll(entityIdsOf(spec, trace));
            } else if (!trace.equals(traceOfFirst)) {
                divergent.add("run " + i + ": " + firstDifference(traceOfFirst, trace));
            }
            byDigest.computeIfAbsent(digest(trace), k -> new ArrayList<>()).add(i);
        }

        Entry entry = new Entry(bootId(), started, spec.id(), digest(traceOfFirst),
                traceOfFirst.size(), entityIds);
        List<Entry> earlier = readLedger(spec.id());
        append(entry);
        Result result = new Result(spec.id(), runs, byDigest, divergent, entry, earlier);

        if (!divergent.isEmpty()) {
            throw new ScenarioFailedException("zero-flake gate FAILED for '" + spec.id() + "': "
                    + byDigest.size() + " distinct normalized traces across " + runs + " consecutive"
                    + " runs in one session." + System.lineSeparator() + result.describe());
        }
        return result;
    }

    // --- the ledger -------------------------------------------------------------------------

    /**
     * One gate invocation, as one line: {@code <bootId> <instant> <scenario> <digest> <lines> <ids>}.
     *
     * <p>A flat text file, appended to, never rewritten. The point is that a later invocation on a
     * different day can read what an earlier one saw; anything requiring a database or a service
     * would not survive the first machine that does not have one.
     */
    public record Entry(String bootId, Instant at, String scenarioId, String digest, int lines,
            Set<String> entityIds) {

        String toLine() {
            return String.join("\t", bootId, at.toString(), scenarioId, digest,
                    Integer.toString(lines), String.join(",", entityIds));
        }

        static Entry parse(String line) {
            String[] f = line.split("\t", -1);
            if (f.length < 6) {
                return null;
            }
            Set<String> ids = new LinkedHashSet<>();
            if (!f[5].isBlank()) {
                ids.addAll(List.of(f[5].split(",")));
            }
            try {
                return new Entry(f[0], Instant.parse(f[1]), f[2], f[3], Integer.parseInt(f[4]), ids);
            } catch (RuntimeException malformed) {
                return null;
            }
        }
    }

    private Path ledgerFile(String scenarioId) {
        return ledger.resolve(scenarioId + ".tsv");
    }

    private List<Entry> readLedger(String scenarioId) {
        Path file = ledgerFile(scenarioId);
        if (!Files.isRegularFile(file)) {
            return List.of();
        }
        try {
            List<Entry> entries = new ArrayList<>();
            for (String line : Files.readAllLines(file, StandardCharsets.UTF_8)) {
                Entry entry = Entry.parse(line);
                if (entry != null) {
                    entries.add(entry);
                }
            }
            return List.copyOf(entries);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read the gate ledger " + file, e);
        }
    }

    private void append(Entry entry) {
        Path file = ledgerFile(entry.scenarioId());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, entry.toLine() + System.lineSeparator(), StandardCharsets.UTF_8,
                    java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot append to the gate ledger " + file, e);
        }
    }

    /**
     * The Linux boot id, which changes on every reboot and on nothing else — the one machine-state
     * fact available that maps onto §8.2's "across a reboot". Where it cannot be read the gate says
     * so instead of substituting something that looks like it.
     */
    static String bootId() {
        Path path = Path.of("/proc/sys/kernel/random/boot_id");
        try {
            return Files.isReadable(path) ? Files.readString(path).trim() : "unknown-boot";
        } catch (IOException e) {
            return "unknown-boot";
        }
    }

    // --- the verdict ------------------------------------------------------------------------

    /**
     * What the gate saw, and what that does and does not license.
     *
     * @param scenarioId   the scenario
     * @param runs         how many consecutive runs were made in this session
     * @param byDigest     trace digest → the run numbers that produced it
     * @param divergences  human-readable first differences, empty when the runs agreed
     * @param entry        this invocation's ledger entry
     * @param earlier      every earlier entry for this scenario
     */
    public record Result(String scenarioId, int runs, Map<String, List<Integer>> byDigest,
            List<String> divergences, Entry entry, List<Entry> earlier) {

        public boolean withinSessionStable() {
            return divergences.isEmpty();
        }

        /** Earlier entries that qualify as a separate occasion: another boot, or hours ago. */
        public List<Entry> crossOccasionEvidence() {
            List<Entry> qualifying = new ArrayList<>();
            for (Entry other : earlier) {
                boolean differentBoot = !other.bootId().equals(entry.bootId())
                        && !"unknown-boot".equals(other.bootId())
                        && !"unknown-boot".equals(entry.bootId());
                boolean longAgo = Duration.between(other.at(), entry.at()).toHours() >= CROSS_OCCASION_HOURS;
                if (differentBoot || longAgo) {
                    qualifying.add(other);
                }
            }
            return List.copyOf(qualifying);
        }

        /** Qualifying earlier occasions whose entity-id set differs from this one's. */
        public List<Entry> crossOccasionDisagreements() {
            List<Entry> bad = new ArrayList<>();
            for (Entry other : crossOccasionEvidence()) {
                if (!other.entityIds().equals(entry.entityIds())) {
                    bad.add(other);
                }
            }
            return List.copyOf(bad);
        }

        /**
         * The report, written to say what was established and — at the same volume — what was not.
         * A gate that prints only its green half is how "10 consecutive runs" came to be mistaken
         * for reproducibility in the first place.
         */
        public String describe() {
            String nl = System.lineSeparator();
            StringBuilder sb = new StringBuilder();
            sb.append("parity gate — scenario '").append(scenarioId).append("'").append(nl);
            sb.append("  runs (this session) : ").append(runs).append(nl);
            sb.append("  distinct traces     : ").append(byDigest.size()).append(nl);
            for (Map.Entry<String, List<Integer>> e : byDigest.entrySet()) {
                sb.append("      ").append(e.getKey(), 0, 12).append("  runs ").append(e.getValue()).append(nl);
            }
            sb.append("  trace lines         : ").append(entry.lines()).append(nl);
            sb.append("  entity ids          : ").append(entry.entityIds()).append(nl);
            sb.append("  boot id             : ").append(entry.bootId()).append(nl);
            for (String divergence : divergences) {
                sb.append("  DIVERGED            : ").append(divergence).append(nl);
            }
            sb.append(nl).append("  ESTABLISHED: ");
            if (withinSessionStable()) {
                sb.append(runs).append(" consecutive runs in ONE session produced a byte-identical")
                        .append(" normalized trace. Everything that re-rolls per run — thread")
                        .append(" interleaving, tick jitter, the startup race, the printlns' race,")
                        .append(" the aliased payloads — is absorbed.").append(nl);
            } else {
                sb.append("nothing; the runs did not agree with each other.").append(nl);
            }

            sb.append("  NOT ESTABLISHED: that a golden recorded now matches a run later.")
                    .append(" docs/kernel-config.md measured 114 consecutive runs agreeing and a")
                    .append(" LATER session disagreeing 7 times in 9, with nothing changed, and")
                    .append(" could not identify what persists. Consecutive runs sample one mode.")
                    .append(nl);

            List<Entry> evidence = crossOccasionEvidence();
            if (evidence.isEmpty()) {
                sb.append("  CROSS-OCCASION: no qualifying earlier run in the ledger (")
                        .append(earlier.size()).append(" entr").append(earlier.size() == 1 ? "y" : "ies")
                        .append(" total, none from another boot or ").append(CROSS_OCCASION_HOURS)
                        .append("+ hours ago).").append(nl)
                        .append("      To establish it, run this same command again after a reboot,")
                        .append(" or in ").append(CROSS_OCCASION_HOURS).append("+ hours, or on")
                        .append(" another machine sharing this ledger directory. Running it twice")
                        .append(" now would add nothing: docs/defect-triage.md §8.2 — \"a script")
                        .append(" that loops twice satisfies the letter while delivering exactly")
                        .append(" the worthless consecutive-run evidence\".").append(nl);
            } else {
                sb.append("  CROSS-OCCASION: compared against ").append(evidence.size())
                        .append(" qualifying earlier occasion(s).").append(nl);
                for (Entry other : evidence) {
                    sb.append("      ").append(other.at()).append("  boot ")
                            .append(other.bootId(), 0, Math.min(8, other.bootId().length()))
                            .append("  ids=").append(other.entityIds())
                            .append(other.entityIds().equals(entry.entityIds()) ? "  AGREES" : "  DIFFERS")
                            .append(nl);
                }
                if (crossOccasionDisagreements().isEmpty()) {
                    sb.append("      All agree on the entity-id set — the comparison §8.2 asks for.")
                            .append(nl);
                }
            }
            return sb.toString();
        }
    }

    // --- helpers ----------------------------------------------------------------------------

    private static Set<String> entityIdsOf(ScenarioSpec spec, List<String> trace) {
        Set<String> ids = new TreeSet<>();
        if (spec.entity() == null) {
            return ids;
        }
        for (String line : trace) {
            spec.entity().idOf(line).ifPresent(ids::add);
        }
        return ids;
    }

    private static String firstDifference(List<String> a, List<String> b) {
        int limit = Math.min(a.size(), b.size());
        for (int i = 0; i < limit; i++) {
            if (!a.get(i).equals(b.get(i))) {
                return "line " + (i + 1) + ": expected '" + a.get(i) + "' but ran '" + b.get(i) + "'";
            }
        }
        return "identical for " + limit + " line(s), then lengths differ (" + a.size()
                + " vs " + b.size() + ")";
    }

    private static String digest(List<String> lines) {
        try {
            MessageDigest sha = MessageDigest.getInstance("SHA-256");
            sha.update(String.join("\n", lines).getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : sha.digest()) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is mandatory in every JRE", e);
        }
    }
}
