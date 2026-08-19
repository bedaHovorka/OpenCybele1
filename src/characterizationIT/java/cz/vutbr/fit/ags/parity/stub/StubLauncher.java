package cz.vutbr.fit.ags.parity.stub;

import cz.vutbr.fit.ags.parity.spec.ScenarioSpec;
import cz.vutbr.fit.ags.parity.spi.LaunchSpec;
import cz.vutbr.fit.ags.parity.spi.LauncherAdapter;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Adapter #0: drives {@link StubSimulation} in a child JVM.
 *
 * <p>It is a complete adapter — command line, environment, exit-code table, diagnostic prefixes —
 * differing from {@code OpenCybeleLauncher} (<a
 * href="https://github.com/bedaHovorka/OpenCybele1/issues/13">#13</a>) only in what it points at.
 * Its job is to prove that the SPI drives a run end to end with <strong>no application source
 * present</strong>, which is exactly the property the harness must keep in order to live on a
 * branch that has no implementation on it.
 *
 * <p>The classpath comes from the running test JVM ({@code java.class.path}), overridable with
 * {@code -Dparity.stub.classpath=…} for a launcher whose test JVM does not carry it.
 */
public final class StubLauncher implements LauncherAdapter {

    @Override
    public String id() {
        return "stub";
    }

    @Override
    public LaunchSpec launch(ScenarioSpec spec, Path scratch) {
        List<String> command = new ArrayList<>();
        command.add(javaBinary());
        command.add("-ea");
        command.add("-cp");
        command.add(classpath());
        for (Map.Entry<String, String> property : spec.launcher().properties().entrySet()) {
            command.add("-D" + property.getKey() + "=" + property.getValue());
        }
        if (spec.launcher().config() != null) {
            command.add("-Dsim.config=" + spec.launcher().config());
        }
        command.add(StubSimulation.class.getName());
        command.addAll(spec.launcher().args());
        return new LaunchSpec(command, spec.launcher().env(), scratch);
    }

    private static String javaBinary() {
        return Path.of(System.getProperty("java.home"), "bin", "java").toString();
    }

    private static String classpath() {
        return System.getProperty("parity.stub.classpath", System.getProperty("java.class.path"));
    }
}
