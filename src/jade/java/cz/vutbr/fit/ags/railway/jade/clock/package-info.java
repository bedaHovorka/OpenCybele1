/*
 * Projekt AGS 2007/08
 * FIT VUT Brno
 *
 * Open Cybele 1
 *
 * Bedrich Hovorka
 * xhovor07@stud.fit.vutbr.cz
 */
/**
 * The JADE binding of the simulated clock (#29).
 * <p>
 * Everything here that could be framework-free already is, and lives in
 * {@code cz.vutbr.fit.ags.railway.domain.clock} instead — the clock itself, the deadline heap
 * and the per-agent scheduler. What is left is exactly what needs {@code jade.core.behaviours}:
 * a {@code TickerBehaviour} that drains one agent's due wake-ups on that agent's own thread.
 * Branch {@code jason} (#46, #47) reuses the domain half verbatim and replaces this package
 * with an environment step.
 * <p>
 * This is part of the {@code src/jade/java} source set and is <strong>not</strong> on the
 * Cybele application's classpath. The Cybele agents on this branch deliberately still use
 * {@code Cybele.getTime} and {@code Activity.setTimer}: rewiring them is #30&ndash;#34's job,
 * ticket by ticket, each with its own parity gate run.
 */
package cz.vutbr.fit.ags.railway.jade.clock;