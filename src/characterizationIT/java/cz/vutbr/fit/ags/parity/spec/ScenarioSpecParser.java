package cz.vutbr.fit.ags.parity.spec;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Reads {@code parity-tests/scenarios/*.yaml} into {@link ScenarioSpec}s.
 *
 * <p><strong>Every key is checked.</strong> An unrecognised key is an error, not a value that is
 * quietly ignored — the same rule the simulation itself applies to its {@code sim.*} keys, and for
 * the same reason: a spec that looks complete while silently running on a default is how a golden
 * gets recorded under a configuration nobody chose. Cross-field rules (an entity rule is required
 * unless the contract is strict, summary rules only under a summary contract, and so on) are
 * checked here too, so a malformed scenario fails at authoring time rather than after a two-minute
 * run.
 */
public final class ScenarioSpecParser {

    private static final Set<String> TOP_LEVEL = Set.of(
            "id", "description", "contract", "golden", "launcher", "run", "liveness", "entity",
            "summary", "allowErrorLines");
    private static final Set<String> LAUNCHER_KEYS = Set.of("config", "properties", "env", "args");
    private static final Set<String> RUN_KEYS = Set.of("harnessTimeoutMs", "expect");
    private static final Set<String> LIVENESS_KEYS = Set.of("pattern", "atLeast", "atMost", "distinctGroup");
    private static final Set<String> ENTITY_KEYS = Set.of("pattern", "group", "tolerance");
    private static final Set<String> TOLERANCE_KEYS = Set.of("missing", "extra");
    private static final Set<String> SUMMARY_KEYS = Set.of("label", "pattern", "tolerance");

    private ScenarioSpecParser() {
    }

    /** Parses one scenario file. The file name stem must equal the {@code id} it declares. */
    public static ScenarioSpec parse(Path file) {
        String text;
        try {
            text = Files.readString(file, StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("cannot read scenario " + file, e);
        }
        ScenarioSpec spec = parse(text, file.toString());
        String stem = file.getFileName().toString().replaceFirst("\\.ya?ml$", "");
        if (!stem.equals(spec.id())) {
            throw new IllegalArgumentException(file + ": declares id '" + spec.id()
                    + "' but is named '" + stem + "'. The two must agree — the id is what a"
                    + " failure report and a golden name are keyed on.");
        }
        return spec;
    }

    /** Parses every {@code *.yaml} in a directory, in file-name order, for a stable suite order. */
    public static List<ScenarioSpec> parseAll(Path directory) {
        try (Stream<Path> files = Files.list(directory)) {
            List<ScenarioSpec> specs = new ArrayList<>();
            files.filter(p -> p.getFileName().toString().matches(".*\\.ya?ml"))
                    .sorted()
                    .forEach(p -> specs.add(parse(p)));
            return specs;
        } catch (IOException e) {
            throw new UncheckedIOException("cannot list scenarios in " + directory, e);
        }
    }

    /** Parses scenario YAML from a string. {@code source} names it in error messages. */
    @SuppressWarnings("unchecked")
    public static ScenarioSpec parse(String yaml, String source) {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        Object loaded = new Yaml(new SafeConstructor(options)).load(yaml);
        if (!(loaded instanceof Map)) {
            throw new IllegalArgumentException(source + ": expected a YAML mapping at the top level");
        }
        Map<String, Object> root = (Map<String, Object>) loaded;
        checkKeys(source, "", root, TOP_LEVEL);

        String id = requireString(source, "id", root.get("id"));
        String description = optionalString(root.get("description"), "");
        ContractLevel contract = ContractLevel.fromYaml(requireString(source, "contract", root.get("contract")));
        String golden = optionalString(root.get("golden"), id + ".txt");

        ScenarioSpec.Launcher launcher = parseLauncher(source, optionalMap(source, "launcher", root.get("launcher")));
        ScenarioSpec.Run run = parseRun(source, optionalMap(source, "run", root.get("run")));
        List<LivenessRule> liveness = parseLiveness(source, root.get("liveness"));
        EntityRule entity = parseEntity(source, contract, optionalMap(source, "entity", root.get("entity")));
        List<SummaryRule> summary = parseSummary(source, root.get("summary"));
        List<Pattern> allowErrorLines = parsePatternList(source, "allowErrorLines", root.get("allowErrorLines"));
        for (Pattern allowed : allowErrorLines) {
            rejectIfTooBroad(source, allowed);
        }

        if (liveness.isEmpty()) {
            throw new IllegalArgumentException(source + ": at least one liveness rule is required."
                    + " A scenario that asserts nothing about how much it did cannot tell a healthy"
                    + " run from a simulation that died silently and still exited 0.");
        }
        if (contract != ContractLevel.STRICT && entity == null) {
            throw new IllegalArgumentException(source + ": contract '" + contract.yamlName()
                    + "' compares per entity id, so an 'entity:' rule is required.");
        }
        if (contract == ContractLevel.SUMMARY && summary.isEmpty()) {
            throw new IllegalArgumentException(source + ": contract 'summary' compares declared"
                    + " aggregates, so at least one 'summary:' rule is required.");
        }
        if (contract != ContractLevel.SUMMARY && !summary.isEmpty()) {
            throw new IllegalArgumentException(source + ": 'summary:' rules are only compared under"
                    + " contract 'summary'; under '" + contract.yamlName() + "' they would look"
                    + " like assertions while asserting nothing. Remove them or raise the contract.");
        }
        return new ScenarioSpec(id, description, contract, golden, launcher, run, liveness, entity,
                summary, allowErrorLines);
    }

    @SuppressWarnings("unchecked")
    private static ScenarioSpec.Launcher parseLauncher(String source, Map<String, Object> node) {
        if (node == null) {
            return new ScenarioSpec.Launcher(null, Map.of(), Map.of(), List.of());
        }
        checkKeys(source, "launcher.", node, LAUNCHER_KEYS);
        return new ScenarioSpec.Launcher(
                optionalString(node.get("config"), null),
                stringMap(source, "launcher.properties",
                        optionalMap(source, "launcher.properties", node.get("properties"))),
                stringMap(source, "launcher.env", optionalMap(source, "launcher.env", node.get("env"))),
                stringList(source, "launcher.args", node.get("args")));
    }

    private static ScenarioSpec.Run parseRun(String source, Map<String, Object> node) {
        if (node == null) {
            throw new IllegalArgumentException(source + ": 'run:' is required (harnessTimeoutMs, expect)");
        }
        checkKeys(source, "run.", node, RUN_KEYS);
        long timeout = requireLong(source, "run.harnessTimeoutMs", node.get("harnessTimeoutMs"));
        ExpectedOutcome expect = ExpectedOutcome.fromYaml(
                requireString(source, "run.expect", node.get("expect")));
        return new ScenarioSpec.Run(timeout, expect);
    }

    @SuppressWarnings("unchecked")
    private static List<LivenessRule> parseLiveness(String source, Object node) {
        List<LivenessRule> rules = new ArrayList<>();
        if (node == null) {
            return rules;
        }
        if (!(node instanceof List)) {
            throw new IllegalArgumentException(source + ": 'liveness:' must be a list of rules");
        }
        int index = 0;
        for (Object element : (List<Object>) node) {
            String where = "liveness[" + index++ + "].";
            Map<String, Object> rule = asMap(source, where, element);
            checkKeys(source, where, rule, LIVENESS_KEYS);
            rules.add(new LivenessRule(
                    compile(source, where + "pattern", requireString(source, where + "pattern", rule.get("pattern"))),
                    (int) requireLong(source, where + "atLeast", rule.get("atLeast")),
                    rule.get("atMost") == null ? null : (int) requireLong(source, where + "atMost", rule.get("atMost")),
                    rule.get("distinctGroup") == null ? 0
                            : (int) requireLong(source, where + "distinctGroup", rule.get("distinctGroup"))));
        }
        return rules;
    }

    @SuppressWarnings("unchecked")
    private static EntityRule parseEntity(String source, ContractLevel contract, Map<String, Object> node) {
        if (node == null) {
            return null;
        }
        checkKeys(source, "entity.", node, ENTITY_KEYS);
        if (contract == ContractLevel.STRICT && node.containsKey("tolerance")) {
            throw new IllegalArgumentException(source + ": entity.tolerance is honoured under"
                    + " contracts 'causal' and 'summary'; under 'strict' every line must match, so a"
                    + " tolerance block here reads as a licence it is not — even one whose values are"
                    + " all zero. Remove it, or weaken the contract to the level the scenario"
                    + " actually holds to.");
        }
        Map<String, Object> tolerance = optionalMap(source, "entity.tolerance", node.get("tolerance"));
        int missing = 0;
        int extra = 0;
        if (tolerance != null) {
            checkKeys(source, "entity.tolerance.", tolerance, TOLERANCE_KEYS);
            missing = tolerance.get("missing") == null ? 0
                    : (int) requireLong(source, "entity.tolerance.missing", tolerance.get("missing"));
            extra = tolerance.get("extra") == null ? 0
                    : (int) requireLong(source, "entity.tolerance.extra", tolerance.get("extra"));
        }
        return new EntityRule(
                compile(source, "entity.pattern", requireString(source, "entity.pattern", node.get("pattern"))),
                node.get("group") == null ? 1 : (int) requireLong(source, "entity.group", node.get("group")),
                missing, extra);
    }

    @SuppressWarnings("unchecked")
    private static List<SummaryRule> parseSummary(String source, Object node) {
        List<SummaryRule> rules = new ArrayList<>();
        if (node == null) {
            return rules;
        }
        if (!(node instanceof List)) {
            throw new IllegalArgumentException(source + ": 'summary:' must be a list of rules");
        }
        int index = 0;
        for (Object element : (List<Object>) node) {
            String where = "summary[" + index++ + "].";
            Map<String, Object> rule = asMap(source, where, element);
            checkKeys(source, where, rule, SUMMARY_KEYS);
            rules.add(new SummaryRule(
                    requireString(source, where + "label", rule.get("label")),
                    compile(source, where + "pattern", requireString(source, where + "pattern", rule.get("pattern"))),
                    rule.get("tolerance") == null ? 0
                            : (int) requireLong(source, where + "tolerance", rule.get("tolerance"))));
        }
        return rules;
    }

    private static List<Pattern> parsePatternList(String source, String where, Object node) {
        List<Pattern> patterns = new ArrayList<>();
        for (String text : stringList(source, where, node)) {
            patterns.add(compile(source, where, text));
        }
        return patterns;
    }

    /**
     * An {@code allowErrorLines} entry is a hole in the error scan, so it has to be narrow enough to
     * name the failure it silences. A pattern that also matches ordinary text (the classic being a
     * bare {@code .} or {@code .*}) silences every future throwable too, and a run whose real
     * AssertionError was swallowed this way was reproduced recording a golden with the assertion in
     * it. The test is empirical rather than syntactic: if the pattern matches innocuous text that
     * contains no error signature at all, it is not naming anything.
     */
    private static void rejectIfTooBroad(String source, Pattern allowed) {
        List<String> innocuous = List.of("", "x", "vl1 started", "vl1 in stA at 30280", "stA 0/6");
        for (String probe : innocuous) {
            if (allowed.matcher(probe).find()) {
                throw new IllegalArgumentException(source + ": allowErrorLines pattern /" + allowed
                        + "/ also matches ordinary output (" + (probe.isEmpty() ? "an empty line" : "'" + probe + "'")
                        + "), so it would silence every future throwable, not the one it is meant to"
                        + " exempt. Anchor it and name the specific failure.");
            }
        }
    }

    private static void checkKeys(String source, String prefix, Map<String, Object> node, Set<String> allowed) {
        List<String> unknown = new ArrayList<>();
        // String.valueOf, not a cast: YAML keys need not be strings, and `1: two` used to escape
        // the schema layer as a raw ClassCastException instead of being reported as an unknown key.
        for (Object key : node.keySet()) {
            if (!allowed.contains(String.valueOf(key))) {
                unknown.add(prefix + String.valueOf(key));
            }
        }
        if (!unknown.isEmpty()) {
            unknown.sort(Comparator.naturalOrder());
            List<String> known = new ArrayList<>(allowed);
            known.sort(Comparator.naturalOrder());
            throw new IllegalArgumentException(source + ": unknown key(s) " + unknown
                    + "; allowed under '" + (prefix.isEmpty() ? "<root>" : prefix) + "': " + known
                    + ". Keys are rejected rather than ignored on purpose: a scenario that looks"
                    + " fully specified while running on a default is how a golden gets recorded"
                    + " under a configuration nobody chose.");
        }
    }

    /** As {@link #asMap} but tolerating absence; a present-but-wrong-shaped node still fails loudly. */
    private static Map<String, Object> optionalMap(String source, String where, Object node) {
        return node == null ? null : asMap(source, where, node);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(String source, String where, Object node) {
        if (node instanceof Map) {
            return (Map<String, Object>) node;
        }
        throw new IllegalArgumentException(source + ": " + where + " must be a mapping, got "
                + (node == null ? "nothing" : node.getClass().getSimpleName()));
    }

    private static Map<String, String> stringMap(String source, String where, Map<String, Object> node) {
        Map<String, String> out = new LinkedHashMap<>();
        if (node != null) {
            // Entry<?, ?>, not Entry<String, ?>: a non-string YAML key would otherwise surface as a
            // ClassCastException from inside a lambda rather than as a schema error.
            for (Map.Entry<?, ?> entry : node.entrySet()) {
                if (entry.getValue() == null) {
                    throw new IllegalArgumentException(source + ": " + where + "."
                            + String.valueOf(entry.getKey()) + " has no value");
                }
                out.put(String.valueOf(entry.getKey()), scalarToString(entry.getValue()));
            }
        }
        return out;
    }

    @SuppressWarnings("unchecked")
    private static List<String> stringList(String source, String where, Object node) {
        if (node == null) {
            return List.of();
        }
        if (!(node instanceof List)) {
            throw new IllegalArgumentException(source + ": " + where + " must be a list");
        }
        List<String> out = new ArrayList<>();
        for (Object element : (List<Object>) node) {
            out.add(requireString(source, where, element));
        }
        return out;
    }

    private static String scalarToString(Object value) {
        if (value instanceof Boolean b) {
            return b.toString();
        }
        return String.valueOf(value);
    }

    private static String requireString(String source, String where, Object value) {
        if (value == null) {
            throw new IllegalArgumentException(source + ": '" + where + "' is required");
        }
        if (value instanceof Map || value instanceof List) {
            throw new IllegalArgumentException(source + ": '" + where + "' must be a scalar");
        }
        return scalarToString(value);
    }

    private static long requireLong(String source, String where, Object value) {
        if (value instanceof Number n) {
            return n.longValue();
        }
        if (value instanceof String s) {
            try {
                return Long.parseLong(s.trim());
            } catch (NumberFormatException e) {
                // fall through to the shared message
            }
        }
        throw new IllegalArgumentException(source + ": '" + where + "' must be an integer, got "
                + (value == null ? "nothing" : String.valueOf(value)));
    }

    private static String optionalString(Object value, String fallback) {
        return value == null ? fallback : scalarToString(value);
    }

    private static Pattern compile(String source, String where, String regex) {
        try {
            return Pattern.compile(regex);
        } catch (PatternSyntaxException e) {
            throw new IllegalArgumentException(source + ": " + where + " is not a valid regex: "
                    + e.getMessage(), e);
        }
    }
}
