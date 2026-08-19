package cz.vutbr.fit.ags.parity.golden;

import cz.vutbr.fit.ags.parity.spec.ContractLevel;
import cz.vutbr.fit.ags.parity.spec.EntityRule;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpec;
import cz.vutbr.fit.ags.parity.spec.SummaryRule;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compares a normalized trace against a golden at the strength the scenario declares.
 *
 * <p>See {@link ContractLevel} for why the strength is a scenario property at all. The three
 * levels are implemented here and nowhere else; the runner only picks one.
 */
public final class TraceComparator {

    private static final int CONTEXT_LINES = 3;

    private TraceComparator() {
    }

    public static ComparisonResult compare(ScenarioSpec spec, List<String> golden, List<String> actual) {
        return switch (spec.contract()) {
            case STRICT -> strict(golden, actual);
            case CAUSAL -> causal(spec.entity(), golden, actual);
            case SUMMARY -> summary(spec, golden, actual);
        };
    }

    // --- strict ---------------------------------------------------------------------------

    private static ComparisonResult strict(List<String> golden, List<String> actual) {
        if (golden.equals(actual)) {
            return ComparisonResult.match();
        }
        int firstDiff = 0;
        int limit = Math.min(golden.size(), actual.size());
        while (firstDiff < limit && golden.get(firstDiff).equals(actual.get(firstDiff))) {
            firstDiff++;
        }
        List<String> failures = new ArrayList<>();
        failures.add("strict contract: traces differ at line " + (firstDiff + 1)
                + " (golden has " + golden.size() + " lines, run produced " + actual.size() + ")");
        failures.add("  golden: " + describeAround(golden, firstDiff));
        failures.add("  actual: " + describeAround(actual, firstDiff));
        return ComparisonResult.mismatch(failures);
    }

    private static String describeAround(List<String> lines, int index) {
        StringBuilder sb = new StringBuilder();
        for (int i = Math.max(0, index - CONTEXT_LINES); i <= Math.min(lines.size() - 1, index); i++) {
            sb.append(System.lineSeparator()).append("    ").append(i == index ? "> " : "  ")
                    .append(i + 1).append(": ").append(lines.get(i));
        }
        if (index >= lines.size()) {
            sb.append(System.lineSeparator()).append("    > ").append(index + 1).append(": <end of trace>");
        }
        return sb.toString();
    }

    // --- causal ---------------------------------------------------------------------------

    private static ComparisonResult causal(EntityRule entity, List<String> golden, List<String> actual) {
        Map<String, List<String>> goldenByEntity = groupByEntity(entity, golden);
        Map<String, List<String>> actualByEntity = groupByEntity(entity, actual);

        Set<String> missing = new LinkedHashSet<>(goldenByEntity.keySet());
        missing.removeAll(actualByEntity.keySet());
        Set<String> extra = new LinkedHashSet<>(actualByEntity.keySet());
        extra.removeAll(goldenByEntity.keySet());

        List<String> failures = new ArrayList<>();
        if (missing.size() > entity.missingTolerance()) {
            failures.add("causal contract: " + missing.size() + " entity id(s) in the golden did not"
                    + " appear in this run, tolerance is " + entity.missingTolerance() + ": " + truncate(missing));
        }
        if (extra.size() > entity.extraTolerance()) {
            failures.add("causal contract: " + extra.size() + " entity id(s) appeared that the golden"
                    + " does not have, tolerance is " + entity.extraTolerance() + ": " + truncate(extra));
        }
        for (Map.Entry<String, List<String>> e : goldenByEntity.entrySet()) {
            List<String> actualLines = actualByEntity.get(e.getKey());
            if (actualLines != null && !actualLines.equals(e.getValue())) {
                failures.add("causal contract: entity '" + e.getKey() + "' diverged"
                        + System.lineSeparator() + "    golden: " + e.getValue()
                        + System.lineSeparator() + "    actual: " + actualLines);
            }
        }
        return failures.isEmpty() ? ComparisonResult.match() : ComparisonResult.mismatch(failures);
    }

    /**
     * Groups lines by entity id, preserving each entity's own line order. Lines naming no entity go
     * into one shared bucket under {@link #UNATTRIBUTED}, which is compared in order like any other
     * entity — so a startup block or a summary line still has to match, it just does not have to
     * interleave with the entity lines the same way it did when the golden was recorded.
     */
    private static Map<String, List<String>> groupByEntity(EntityRule entity, List<String> lines) {
        Map<String, List<String>> grouped = new LinkedHashMap<>();
        for (String line : lines) {
            String id = entity.idOf(line).orElse(UNATTRIBUTED);
            grouped.computeIfAbsent(id, k -> new ArrayList<>()).add(line);
        }
        return grouped;
    }

    /** Bucket key for lines that carry no entity id. */
    public static final String UNATTRIBUTED = "<no-entity>";

    private static String truncate(Set<String> ids) {
        List<String> shown = new ArrayList<>(ids);
        if (shown.size() <= 12) {
            return shown.toString();
        }
        return shown.subList(0, 12) + " … and " + (shown.size() - 12) + " more";
    }

    // --- summary --------------------------------------------------------------------------

    private static ComparisonResult summary(ScenarioSpec spec, List<String> golden, List<String> actual) {
        List<String> failures = new ArrayList<>();
        EntityRule entity = spec.entity();
        int goldenEntities = distinctEntities(entity, golden);
        int actualEntities = distinctEntities(entity, actual);
        int entityTolerance = Math.max(entity.missingTolerance(), entity.extraTolerance());
        if (Math.abs(goldenEntities - actualEntities) > entityTolerance) {
            failures.add("summary contract: distinct entity ids " + actualEntities
                    + ", golden has " + goldenEntities + ", tolerance " + entityTolerance);
        }
        for (SummaryRule rule : spec.summary()) {
            int goldenCount = rule.count(golden);
            int actualCount = rule.count(actual);
            if (Math.abs(goldenCount - actualCount) > rule.tolerance()) {
                failures.add("summary contract: '" + rule.label() + "' counted " + actualCount
                        + ", golden has " + goldenCount + ", tolerance " + rule.tolerance());
            }
        }
        return failures.isEmpty() ? ComparisonResult.match() : ComparisonResult.mismatch(failures);
    }

    private static int distinctEntities(EntityRule entity, List<String> lines) {
        Set<String> ids = new LinkedHashSet<>();
        for (String line : lines) {
            entity.idOf(line).ifPresent(ids::add);
        }
        return ids.size();
    }
}
