package cz.vutbr.fit.ags.parity.it;

import cz.vutbr.fit.ags.parity.ParityLayout;
import cz.vutbr.fit.ags.parity.golden.GoldenStore;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpec;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpecParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parses every scenario in the catalogue without running any of them, and checks the properties a
 * scenario has to have before it is worth running.
 *
 * <p>Cheap and worth having: it catches a malformed, mis-named or under-specified scenario the
 * moment it is committed, instead of after a two-minute run.
 */
class ScenarioCatalogIT {

    /**
     * Every {@code sim.*} key the application accepts, from {@code docs/scenario-config.md} on
     * {@code opencybele-baseline}. A scenario's {@code launcher.properties} must be
     * <strong>total</strong> over this set — see {@link #openCybeleScenariosDeclareEverySimKey}.
     */
    private static final List<String> ALL_SIM_KEYS = List.of(
            "sim.arrival.lambdaMs", "sim.arrival.firstFireMs", "sim.arrival.pairs",
            "sim.station.voteWindowMs", "sim.topology", "sim.station.capacities",
            "sim.road.delaysSec", "sim.clock.startMs", "sim.clock.pace", "sim.gui.paces",
            "sim.gui.mainLine", "sim.gui.branches", "sim.headless", "sim.stop.maxTrains",
            "sim.stop.maxClockMs", "sim.stop.wallClockMs", "sim.stop.stallMs", "sim.trace.enabled",
            "sim.trace.trainLookahead", "sim.random.masterSeed");

    @Test
    @DisplayName("every scenario parses, and every non-recording scenario has a golden")
    void catalogueIsWellFormed() {
        ParityLayout layout = ParityLayout.fromSystemProperties();
        List<ScenarioSpec> specs = ScenarioSpecParser.parseAll(layout.scenariosDir());
        assertFalse(specs.isEmpty(), "no scenarios found under " + layout.scenariosDir());

        GoldenStore goldens = new GoldenStore(layout.goldenDir());
        for (ScenarioSpec spec : specs) {
            assertFalse(spec.liveness().isEmpty(), spec.id() + " has no liveness rule");
            if (!GoldenStore.recording()) {
                assertTrue(goldens.exists(spec.goldenFile()),
                        spec.id() + " has no golden at " + goldens.fileFor(spec.goldenFile())
                                + " — record one with -Dgolden.record=true");
            }
        }
    }

    /**
     * <strong>The totality rule of {@code docs/parity-harness.md}, made mechanical.</strong>
     *
     * <p>Every OpenCybele scenario writes out all twenty {@code sim.*} keys, including the ones
     * sitting at their default, so that its golden depends on the application binary and on nothing
     * else — not on a {@code scenarios/*.properties} file that lives on another branch, and not on
     * an application default that could be changed there.
     *
     * <p>This is asserted rather than left to review because the failure is invisible: dropping
     * {@code sim.station.voteWindowMs} from {@code opencybele-smoke} was measured to move the trace
     * by two lines per affected family, which every tolerance in that file absorbed. A missing key
     * does not look like a missing key; it looks like drift.
     */
    @Test
    @DisplayName("every OpenCybele scenario declares all twenty sim.* keys, none implicit")
    void openCybeleScenariosDeclareEverySimKey() {
        List<ScenarioSpec> specs = openCybeleScenarios();
        assertFalse(specs.isEmpty(), "no opencybele-* scenarios in the catalogue");
        for (ScenarioSpec spec : specs) {
            Set<String> declared = spec.launcher().properties().keySet();
            Set<String> missing = new TreeSet<>(ALL_SIM_KEYS);
            missing.removeAll(declared);
            assertTrue(missing.isEmpty(), spec.id() + " leaves " + missing + " to a default."
                    + " docs/parity-harness.md requires a scenario's launcher.properties to be"
                    + " TOTAL: a golden must not depend on a value nobody wrote down.");
            Set<String> unknown = new TreeSet<>(declared);
            unknown.removeAll(ALL_SIM_KEYS);
            assertTrue(unknown.isEmpty(), spec.id() + " declares " + unknown + ", which the"
                    + " application does not accept — the child would exit 1 at startup. If a new"
                    + " sim.* key has landed on opencybele-baseline, add it to ALL_SIM_KEYS here"
                    + " and to every scenario, in that order.");
            assertEquals("true", spec.launcher().properties().get("sim.headless"),
                    spec.id() + " must run headless. docs/parity-harness.md and #16: a measurement"
                            + " taken with a window on a shared display is not a measurement.");
            assertEquals("true", spec.launcher().properties().get("sim.trace.enabled"),
                    spec.id() + " must have the canonical trace on; without it the golden is two"
                            + " printlns and pins almost nothing.");
            assertFalse("random".equals(spec.launcher().properties().get("sim.random.masterSeed")),
                    spec.id() + " must pin a seed. A golden is recorded against a seed, never"
                            + " against a draw.");
        }
    }

    /**
     * <strong>The smoke/strict fold of #23, asserted so it cannot quietly come undone.</strong>
     *
     * <p>{@code opencybele-smoke.yaml} was {@code opencybele-strict.yaml} with a different stop
     * bound — 25000 against 24000 — and both files carried a comment asking #23 to fold them. The
     * evidence, from {@code docs/trace-normalizer.md} §5.1: at 25000 the last event burst begins 72
     * simulated ms <em>after</em> the bound, so 12 runs in 38 truncated, which forced that file down
     * to a {@code summary} contract; at 24000 the bound sits inside a measured 1952 ms quiet window
     * and 14 of 14 captures were byte-identical.
     *
     * <p>Re-adding a near-duplicate of the strict scenario at a bound inside the shutdown window
     * would reintroduce a scenario that flakes at roughly 1 in 12 and pins counts rather than
     * content. If a second bound is ever genuinely wanted, this is the test to change, and the
     * change should carry the measurement that justifies it.
     */
    @Test
    @DisplayName("the smoke/strict fold holds: no second scenario duplicates strict at another bound")
    void smokeAndStrictStayFolded() {
        ParityLayout layout = ParityLayout.fromSystemProperties();
        List<ScenarioSpec> specs = openCybeleScenarios();
        assertTrue(specs.stream().noneMatch(s -> "opencybele-smoke".equals(s.id())),
                "opencybele-smoke was folded into opencybele-strict by #23 and must not come back;"
                        + " see this test's javadoc for the measurement.");
        assertFalse(Files.exists(layout.goldenDir().resolve("opencybele-smoke.txt")),
                "the folded scenario's golden is still on disk and nothing runs it");

        ScenarioSpec strict = specs.stream().filter(s -> "opencybele-strict".equals(s.id()))
                .findFirst().orElseThrow(() -> new AssertionError("opencybele-strict is gone"));
        assertEquals("24000", strict.launcher().properties().get("sim.stop.maxClockMs"),
                "24000 sits inside the measured 1952 ms gap between event bursts; 25000 does not."
                        + " docs/trace-normalizer.md §5.1.");

        List<String> duplicates = new ArrayList<>();
        for (ScenarioSpec other : specs) {
            if (other.id().equals(strict.id())) {
                continue;
            }
            Set<String> differing = new LinkedHashSet<>();
            for (String key : ALL_SIM_KEYS) {
                if (!java.util.Objects.equals(other.launcher().properties().get(key),
                        strict.launcher().properties().get(key))) {
                    differing.add(key);
                }
            }
            if (differing.size() == 1) {
                duplicates.add(other.id() + " differs from opencybele-strict only in " + differing);
            }
        }
        assertTrue(duplicates.isEmpty(), "these scenarios are opencybele-strict with one number"
                + " changed, which is what the fold removed: " + duplicates);
    }

    /**
     * <strong>Every scenario is in {@code COVERAGE.md}, and {@code COVERAGE.md} names no scenario
     * that does not exist.</strong>
     *
     * <p>The coverage map is the deliverable of #23 and the input to {@code Phase2.md}'s freeze. A
     * map that drifts out of step with the catalogue is worse than none, because it is read as
     * authoritative.
     */
    @Test
    @DisplayName("COVERAGE.md and the scenario catalogue name the same scenarios")
    void coverageMapMatchesTheCatalogue() {
        ParityLayout layout = ParityLayout.fromSystemProperties();
        Path coverage = layout.scenariosDir().resolve("COVERAGE.md");
        assertTrue(Files.isRegularFile(coverage),
                "parity-tests/scenarios/COVERAGE.md is missing; #23's acceptance requires it");
        String text;
        try {
            text = Files.readString(coverage, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        for (ScenarioSpec spec : openCybeleScenarios()) {
            assertTrue(text.contains(spec.id()),
                    "COVERAGE.md does not mention the scenario '" + spec.id() + "'. Every scenario"
                            + " must appear in the map, or the map is not a map.");
        }
        // Naming the folded scenario in prose is right — COVERAGE.md §1 explains the fold. LINKING
        // to it is not: the file is gone, so a link is broken and a table row is a lie.
        assertFalse(text.contains("](opencybele-smoke.yaml)") || text.contains("](opencybele-smoke.txt)"),
                "COVERAGE.md links to opencybele-smoke, which #23 folded away and deleted");
    }

    private static List<ScenarioSpec> openCybeleScenarios() {
        ParityLayout layout = ParityLayout.fromSystemProperties();
        return ScenarioSpecParser.parseAll(layout.scenariosDir()).stream()
                .filter(spec -> spec.id().startsWith("opencybele-"))
                .toList();
    }
}
