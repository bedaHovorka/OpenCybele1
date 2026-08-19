package cz.vutbr.fit.ags.parity.it;

import cz.vutbr.fit.ags.parity.ParityLayout;
import cz.vutbr.fit.ags.parity.golden.GoldenStore;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpec;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpecParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

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
}
