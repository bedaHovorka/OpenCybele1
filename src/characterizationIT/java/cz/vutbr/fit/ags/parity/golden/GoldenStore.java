package cz.vutbr.fit.ags.parity.golden;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Reads and writes the recorded traces under {@code parity-tests/golden}.
 *
 * <p>Record mode is driven by the system property {@code golden.record} (TESTING.md §3.2). Two
 * properties of this class are deliberate:
 *
 * <ul>
 *   <li><strong>Recording writes the full normalized trace at every contract level.</strong> The
 *       contract level governs comparison only, so a scenario can be tightened from summary to
 *       strict later without re-recording — and {@code Phase1.md} L7 forbids re-recording for any
 *       reason but a harness defect.</li>
 *   <li><strong>Recording is gated on a healthy run.</strong> The runner performs the error scan,
 *       the exit classification and the liveness check <em>before</em> it calls
 *       {@link #record(Path, List)}, so a broken run cannot be frozen into the baseline.</li>
 * </ul>
 */
public final class GoldenStore {

    public static final String RECORD_PROPERTY = "golden.record";

    private final Path directory;

    public GoldenStore(Path directory) {
        this.directory = directory;
    }

    /** True when the suite was started with {@code -Dgolden.record=true}. */
    public static boolean recording() {
        return Boolean.getBoolean(RECORD_PROPERTY);
    }

    public Path fileFor(String goldenName) {
        return directory.resolve(goldenName);
    }

    public boolean exists(String goldenName) {
        return Files.isRegularFile(fileFor(goldenName));
    }

    public List<String> read(String goldenName) {
        Path file = fileFor(goldenName);
        if (!Files.isRegularFile(file)) {
            throw new IllegalStateException("no golden at " + file
                    + " — record one with: ./gradlew characterizationIT -D" + RECORD_PROPERTY + "=true");
        }
        List<String> lines;
        try {
            lines = List.copyOf(Files.readAllLines(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read golden " + file, e);
        }
        if (lines.isEmpty()) {
            throw new IllegalStateException(file + " is empty. An empty golden matches an empty run"
                    + " and nothing else can ever fail against it; it is a lock that cannot fail,"
                    + " not a baseline. Delete it and find out why the normalizer produced nothing.");
        }
        return lines;
    }

    public Path record(String goldenName, List<String> lines) {
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("refusing to record an empty golden for '" + goldenName
                    + "'. A 0-byte golden matches an empty run and nothing else can ever fail"
                    + " against it. The normalizer projected away everything the run printed.");
        }
        Path file = fileFor(goldenName);
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, String.join("\n", lines) + (lines.isEmpty() ? "" : "\n"),
                    StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot write golden " + file, e);
        }
        return file;
    }
}
