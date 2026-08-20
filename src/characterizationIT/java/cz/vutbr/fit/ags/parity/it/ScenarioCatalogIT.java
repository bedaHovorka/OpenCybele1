package cz.vutbr.fit.ags.parity.it;

import cz.vutbr.fit.ags.parity.ParityLayout;
import cz.vutbr.fit.ags.parity.golden.GoldenStore;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpec;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpecParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parses every scenario in the catalogue without running any of them.
 *
 * <p>Cheap and worth having: it catches a malformed or mis-named scenario the moment it is
 * committed, instead of after a two-minute run, and it will keep catching them once #23 adds
 * scenarios this branch has no implementation to execute.
 */
class ScenarioCatalogIT {

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
     * {@code opencybele-strict} exists only because {@code opencybele-smoke}'s stop bound lands 72
     * simulated ms before an event burst, so the two are the same scenario with one different
     * number. That claim is load-bearing — it is the evidence #23 is asked to act on when it folds
     * them — and it is the kind of claim that quietly stops being true the first time somebody
     * tunes one of the files.
     *
     * <p>So it is asserted rather than asserted-in-prose. If a deliberate second difference is ever
     * introduced, this test is where to say so.
     */
    @Test
    @DisplayName("smoke and strict differ in exactly one property: the stop bound")
    void smokeAndStrictDifferOnlyInTheBound() {
        ParityLayout layout = ParityLayout.fromSystemProperties();
        Map<String, ScenarioSpec> byId = new LinkedHashMap<>();
        for (ScenarioSpec spec : ScenarioSpecParser.parseAll(layout.scenariosDir())) {
            byId.put(spec.id(), spec);
        }
        ScenarioSpec smoke = byId.get("opencybele-smoke");
        ScenarioSpec strict = byId.get("opencybele-strict");
        assertTrue(smoke != null && strict != null,
                "both OpenCybele scenarios must be in the catalogue: " + byId.keySet());

        Map<String, String> smokeProps = new LinkedHashMap<>(smoke.launcher().properties());
        Map<String, String> strictProps = new LinkedHashMap<>(strict.launcher().properties());
        assertEquals("25000", smokeProps.remove("sim.stop.maxClockMs"));
        assertEquals("24000", strictProps.remove("sim.stop.maxClockMs"),
                "24000 sits inside the measured 1952 ms gap between event bursts; 25000 does not."
                        + " docs/trace-normalizer.md 5.1.");
        assertEquals(smokeProps, strictProps,
                "the two scenarios have drifted apart in something other than the stop bound. Either"
                        + " fold them (#23) or record here why a second difference is deliberate.");
        assertEquals(smoke.run().expect(), strict.run().expect());
        assertEquals(smoke.launcher().config(), strict.launcher().config());
        assertEquals(smoke.launcher().env(), strict.launcher().env());
        assertEquals(smoke.launcher().args(), strict.launcher().args());
    }
}
