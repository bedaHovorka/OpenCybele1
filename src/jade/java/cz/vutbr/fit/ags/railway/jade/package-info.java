/**
 * The JADE binding of the railway message ontology (#27).
 * <p>
 * Everything here that could be framework-free already is, and lives in
 * {@code cz.vutbr.fit.ags.railway.domain.msg} instead. What is left is exactly what needs
 * {@code jade.lang.acl}: the slot assignment on an {@code ACLMessage}, the fifteen
 * {@code MessageTemplate}s, and the topic AIDs the probe registers to. Branch {@code jason}
 * (#46) reuses the domain half verbatim and replaces this package.
 * <p>
 * This is a source set of its own ({@code src/jade/java}) and is <strong>not</strong> on the
 * Cybele application's classpath. The Cybele agents on this branch deliberately do not use the
 * ontology: rewiring them is #30&ndash;#34's job, ticket by ticket, each with its own parity
 * gate run.
 */
package cz.vutbr.fit.ags.railway.jade;
