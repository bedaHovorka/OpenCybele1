package cz.vutbr.fit.ags.parity;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Where the harness' data lives.
 *
 * <p>The root is {@code parity-tests/} beside the build file, overridable with
 * {@code -Dparity.root=…}. Everything goes through here so that the day the harness moves — to an
 * included build or a published test artifact, whichever <a
 * href="https://github.com/bedaHovorka/OpenCybele1/issues/11">#11</a> settles on — no scenario, no
 * golden and no adapter has to change.
 */
public final class ParityLayout {

    public static final String ROOT_PROPERTY = "parity.root";

    private final Path root;

    public ParityLayout(Path root) {
        this.root = root;
    }

    public static ParityLayout fromSystemProperties() {
        return new ParityLayout(Path.of(System.getProperty(ROOT_PROPERTY, "parity-tests")));
    }

    public Path root() {
        return root;
    }

    public Path scenariosDir() {
        return root.resolve("scenarios");
    }

    public Path goldenDir() {
        return root.resolve("golden");
    }

    /** A per-run scratch directory under the build output; created empty. */
    public Path scratchFor(String scenarioId) {
        Path dir = Path.of(System.getProperty("parity.scratch", "build/parity-scratch")).resolve(scenarioId);
        try {
            deleteRecursively(dir);
            Files.createDirectories(dir);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot prepare scratch directory " + dir, e);
        }
        return dir;
    }

    private static void deleteRecursively(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        try (var walk = Files.walk(path)) {
            walk.sorted((a, b) -> b.getNameCount() - a.getNameCount()).forEach(p -> {
                try {
                    Files.deleteIfExists(p);
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            });
        }
    }
}
