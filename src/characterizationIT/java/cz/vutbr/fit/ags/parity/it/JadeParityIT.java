package cz.vutbr.fit.ags.parity.it;

import cz.vutbr.fit.ags.parity.ParityLayout;
import cz.vutbr.fit.ags.parity.golden.GoldenStore;
import cz.vutbr.fit.ags.parity.jade.JadeLauncher;
import cz.vutbr.fit.ags.parity.run.RunReport;
import cz.vutbr.fit.ags.parity.run.ScenarioRunner;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpec;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpecParser;
import cz.vutbr.fit.ags.parity.spi.RunDisposition;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * The whole scenario catalogue, run against the <strong>JADE</strong> implementation
 * (<a href="https://github.com/bedaHovorka/OpenCybele1/issues/36">#36</a>).
 *
 * <pre>{@code
 * ./gradlew installDist
 * ./gradlew characterizationIT -Pjade.dist=$PWD/build/install/opencybele
 * }</pre>
 *
 * <p>Without {@code -Pjade.dist} every case here is <strong>skipped</strong>, exactly as the
 * OpenCybele ones are without {@code -Popencybele.dist}: this source set has no compile-time link
 * to any implementation and must stay buildable and runnable with none checked out.
 *
 * <h2>Same scenarios, same goldens, one different adapter — and that is the point</h2>
 *
 * <p>Nothing under {@code parity-tests/} changes and nothing may. {@code Phase1.md} L7 freezes the
 * goldens; #36's job was to make the JADE implementation <em>executable by the harness</em> so that
 * a golden diff means <em>port bug</em> and nothing else. The five files this drives are the five
 * {@code opencybele-*} scenarios, byte for byte, and the golden each one is compared against is the
 * one recorded from the baseline.
 *
 * <h2>What this asserts beyond "it matched"</h2>
 *
 * <p>{@link ScenarioRunner} already does the load-bearing work — bounded {@code waitFor}, exit
 * classification, the error scan on the RAW stream, liveness, then normalize and compare — and it
 * throws with a full report when any of it fails. What is added here is the set of claims that
 * would otherwise fail <em>silently</em> on this target specifically:
 *
 * <ul>
 *   <li><strong>The run reached its simulated-time bound</strong>, not its wall-clock net. That is
 *       <a href="https://github.com/bedaHovorka/OpenCybele1/issues/81">#81</a>, and it is asserted
 *       rather than trusted because the failure it describes is a run that ends in the right
 *       ballpark for the wrong reason.</li>
 *   <li><strong>The probe produced a canonical trace at all.</strong> Topics are a dead letter if
 *       {@code TopicManagementService} is missing from the container profile, and the symptom of
 *       that is an empty trace with a green exit status — not a boot failure.</li>
 *   <li><strong>No agent died.</strong> JADE prints {@code ERROR: Agent … died without being
 *       properly terminated !!!} on <em>stdout</em>; those lines are deliberately not filtered (see
 *       {@link JadeLauncher#diagnosticPrefixes()}), so they would already break a comparison — but
 *       named here they break it with the right message.</li>
 * </ul>
 */
class JadeParityIT {

    /**
     * The scenario whose network is sized to reach every channel. Named rather than repeated, so
     * that {@link #assertEveryChannelWasObserved} cannot quietly start asserting nothing if the
     * catalogue is renamed.
     */
    private static final String ALL_FIFTEEN_CHANNELS = "opencybele-lifecycle";

    @ParameterizedTest(name = "{0} matches its golden through JadeLauncher")
    @ValueSource(strings = {
            "opencybele-strict",
            "opencybele-capacity",
            "opencybele-congestion",
            "opencybele-lifecycle",
            "opencybele-timers"})
    @DisplayName("the JADE implementation runs the whole catalogue against the frozen goldens")
    void scenarioMatchesGolden(String scenario) {
        assumeTrue(JadeLauncher.isAvailable(), JadeLauncher.unavailableMessage());

        ParityLayout layout = ParityLayout.fromSystemProperties();
        ScenarioSpec spec = ScenarioSpecParser.parse(layout.scenariosDir().resolve(scenario + ".yaml"));

        RunReport report = ScenarioRunner.usingDefaultLayout().run(spec, new JadeLauncher());
        List<String> raw = report.captured().lines();
        List<String> trace = report.normalized();

        assertNoAgentDied(raw);
        assertStoppedOnTheSimulatedClock(raw, report);
        assertTheProbeReadTheSimulatedClock(raw);
        if (ALL_FIFTEEN_CHANNELS.equals(scenario)) {
            assertEveryChannelWasObserved(trace);
        }
        ScenarioAssertions.assertAdapterContract(spec, raw, trace);

        if (GoldenStore.recording()) {
            System.out.println("parity: recorded " + trace.size() + " line(s) to " + report.goldenFile());
        }
    }

    /**
     * <strong>#81, asserted.</strong> Every {@code opencybele-*} scenario is bounded by
     * {@code sim.stop.maxClockMs} and carries {@code sim.stop.wallClockMs} only as a safety net.
     * The bound is not a timeout: {@code opencybele-strict}'s 24 000 was chosen to land inside a
     * measured 1 952 ms gap between event bursts, so the run ends with nothing in flight. A run
     * that ends on the wall-clock net instead ends wherever it happens to be — and, because
     * {@code RunControl} exits 3 for that, {@code ScenarioRunner} would already fail it. What this
     * adds is the <em>diagnosis</em>: it names the inert bound instead of leaving a triager to read
     * {@code WALL_CLOCK_TIMEOUT} and go looking for a hang.
     */
    private static void assertStoppedOnTheSimulatedClock(List<String> raw, RunReport report) {
        assertEquals(RunDisposition.BOUND_REACHED, report.captured().disposition(),
                "the run did not reach its declared bound");
        assertTrue(raw.stream().anyMatch(line -> line.contains("sim.stop.maxClockMs reached")),
                "the run exited 0 but not on the simulated-time bound. Every scenario in the"
                        + " catalogue declares sim.stop.maxClockMs, and if RunControl cannot read a"
                        + " clock it falls through to another bound at a point the scenario was"
                        + " never tuned for — issue #81. Stop banner lines: "
                        + raw.stream().filter(l -> l.startsWith("  reason")).toList());
        assertTrue(raw.stream().noneMatch(line ->
                        line.contains("no clock was ever published to RunControl.useClock")),
                "RunControl never received a SimClock, so the simulated-time bound was inert (#81)");
    }

    /**
     * A JADE agent that lets a throwable escape a behaviour is killed, and {@code Agent.clean}
     * announces it on <strong>stdout</strong> — the stream the normalizer parses. Those two lines
     * are in contract and unfiltered, so they already break the comparison; naming them here means
     * the failure says "an agent died" rather than "line 214 differs".
     */
    private static void assertNoAgentDied(List<String> raw) {
        List<String> deaths = raw.stream()
                .filter(line -> line.startsWith("ERROR: Agent ")
                        || line.startsWith("***  Uncaught Exception for agent "))
                .toList();
        assertTrue(deaths.isEmpty(), "an agent died mid-run; every message it owed the rest of the"
                + " run is missing from the trace, so the golden diff below it is a consequence and"
                + " not a cause: " + deaths);
    }

    /**
     * <strong>#36's smoke criterion, made total: all fifteen channels, from one run.</strong>
     *
     * <p>Asserted on {@link #ALL_FIFTEEN_CHANNELS} because that is the scenario written to reach
     * every one of them ({@code opencybele-lifecycle.yaml}: "the smallest network exercises all
     * fifteen channels and one full train lifecycle"). One topic per channel <em>constant</em>
     * means one missing registration hides a whole channel family whatever the topology and however
     * many trains ran — the failure would be a plausible-looking trace with a hole in it.
     *
     * <p>It rides on the scenario run above rather than launching a second child of its own. An
     * earlier revision did launch its own, and it inherited every flake of the run it duplicated
     * while measuring nothing the first run had not already produced.
     */
    private static void assertEveryChannelWasObserved(List<String> trace) {
        List<String> missing = new java.util.ArrayList<>();
        for (String event : List.of("VOTE_REQUEST", "VOTE_RESULT", "ENTER", "LEAVE", "START",
                "ENTER_REPLY", "TRAVEL_END", "TRAVEL_START", "PATH_FIND_REPLY", "STATION_INFO",
                "ROAD_STATE", "TRAIN_STATE", "PLAN_TRAIN", "VOTE", "PATH_FIND")) {
            if (trace.stream().noneMatch(line -> line.contains("|" + event + "|"))) {
                missing.add(event);
            }
        }
        assertTrue(missing.isEmpty(), "the probe observed no message on " + missing
                + ". With per-constant topics that is a missing registration, not a quiet"
                + " scenario: every one of these channels carries traffic in this scenario.");
    }

    /**
     * Field 2 is <strong>simulated</strong> time or the run is structurally incomparable.
     *
     * <p>{@code -1} is the probe's own marker for "the clock did not answer". It has to be checked
     * on the RAW stream, because the {@code tick} projection rewrites it to {@code <T>} like any
     * other number — a trace stamped {@code -1} on every line would normalize to something that
     * matches the golden while meaning nothing at all.
     */
    private static void assertTheProbeReadTheSimulatedClock(List<String> raw) {
        assertFalse(raw.stream().anyMatch(line -> line.matches("^[^|]+\\|-1\\|.*")),
                "the probe read tick -1: it could not reach the simulation clock, so field 2 is a"
                        + " fault marker rather than simulated time");
        assertTrue(raw.stream().anyMatch(line -> line.startsWith("--- trace probe: ")),
                "the probe never announced its registration. Without TopicManagementService in the"
                        + " container profile every topic is a dead letter and the symptom is an"
                        + " empty trace with a green exit status, not a boot failure.");
    }
}
