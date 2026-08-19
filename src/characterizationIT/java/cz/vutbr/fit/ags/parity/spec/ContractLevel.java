package cz.vutbr.fit.ags.parity.spec;

/**
 * How strongly a scenario's golden is compared.
 *
 * <p>A contract level exists because one residual nondeterminism cannot be normalised away.
 * Above a configuration-dependent load ceiling the START race (INVENTORY DEF-02, the author's own
 * {@code //BUG ne vzdy se doruci}) drops trains, so the <em>set</em> of trains that depart varies
 * run to run: measured at one configuration, 30 s gives 54 departures reproducing exactly across
 * three runs, while 45 s gives 80 whose id sets differ by 2–12 ids. No normalizer can invent a
 * line the run never printed. The honest response is to let a scenario declare how much of its
 * golden it is entitled to pin, rather than to record a golden that will flake and then be blamed
 * on the port.
 *
 * <p><strong>The recorded artefact is identical at every level.</strong> Recording always writes
 * the full normalized trace; the level governs only comparison. So a scenario can be tightened or
 * relaxed later without re-recording — which matters, because {@code Phase1.md} L7 forbids
 * re-recording a golden for anything but a harness defect.
 */
public enum ContractLevel {

    /**
     * The normalized trace must equal the golden line for line.
     *
     * <p>Only for scenarios validated below the load ceiling. Validation is cheap and belongs at
     * authoring time: run the scenario three times at a fixed seed and diff the entity-id sets
     * (#23). A scenario that fails that check gets a weaker level, never a re-recorded golden.
     */
    STRICT,

    /**
     * Compared per entity id, not per line position.
     *
     * <p>For every entity present in both golden and actual, that entity's ordered projection of
     * the trace must be equal. Entities present on only one side are counted and checked against
     * the tolerances declared in {@code entity.tolerance}. Lines carrying no entity id form one
     * additional bucket compared in order among themselves.
     *
     * <p>This is the level that survives both known interleaving residuals: two entities acting in
     * the same simulated millisecond race each other into the stream (three orderings measured
     * across six runs at one seed), and a lost train removes ids rather than reordering lines.
     */
    CAUSAL,

    /**
     * Only aggregates are compared: the number of distinct entity ids, and one count per declared
     * {@code summary} pattern, each within its own tolerance.
     *
     * <p>The weakest useful level, for scenarios whose value is throughput or coverage rather than
     * an exact sequence. It still fails on a port that stops producing a whole class of line.
     */
    SUMMARY;

    public static ContractLevel fromYaml(String text) {
        for (ContractLevel level : values()) {
            if (level.name().equalsIgnoreCase(text)) {
                return level;
            }
        }
        throw new IllegalArgumentException("unknown contract level '" + text
                + "'; expected one of strict, causal, summary");
    }

    public String yamlName() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
