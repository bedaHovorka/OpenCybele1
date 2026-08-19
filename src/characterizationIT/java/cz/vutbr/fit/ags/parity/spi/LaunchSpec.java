package cz.vutbr.fit.ags.parity.spi;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Everything the runner needs to start one child JVM: a command line, environment additions and
 * a working directory. This is the entire width of the seam between the harness and an
 * implementation — the harness never links against implementation classes, it builds an argv.
 *
 * @param command          the full command line, {@code command.get(0)} being the executable
 * @param environment      variables added to (not replacing) the inherited environment
 * @param workingDirectory directory the child is started in
 */
public record LaunchSpec(List<String> command, Map<String, String> environment, Path workingDirectory) {

    public LaunchSpec {
        if (command == null || command.isEmpty()) {
            throw new IllegalArgumentException("LaunchSpec.command must not be empty");
        }
        command = List.copyOf(command);
        environment = Map.copyOf(environment == null ? Map.of() : environment);
        if (workingDirectory == null) {
            throw new IllegalArgumentException("LaunchSpec.workingDirectory must not be null");
        }
    }

    /** Renders the command line for a failure report. Not shell-quoted; diagnostics only. */
    public String describe() {
        StringBuilder sb = new StringBuilder();
        environment.forEach((k, v) -> sb.append(k).append('=').append(v).append(' '));
        sb.append(String.join(" ", command));
        return sb.toString();
    }
}
