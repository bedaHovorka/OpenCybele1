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
 * Framework-free railway domain logic — the scheduling, voting and ordering rules the
 * simulation is <em>about</em>, separated from the agent kernel that carries them (#28).
 *
 * <p><b>Contract of this package.</b> Nothing here may import an agent framework
 * (Cybele, JADE, Jason), a GUI toolkit, or the parity harness, and nothing here may read
 * a global clock. Simulated time is always a parameter. This is enforced structurally,
 * not by convention: the {@code domain} source set is compiled with an <em>empty</em>
 * dependency configuration, so a {@code cybele.kernel} import cannot compile, and
 * {@code ./gradlew domainPurity} additionally rejects JDK UI/reflection imports that a
 * classpath cannot exclude.</p>
 *
 * <p><b>These classes reproduce the 2008 behaviour, defects included.</b> They are the
 * measured contract the golden traces were recorded from, not a cleaned-up rewrite. Every
 * quirk carried over deliberately is marked {@code DEF-nn} in a comment, classified in
 * {@code docs/defect-triage.md} §3.1, and locked in by a unit test named for what it
 * preserves. Do not "fix" one: under the Phase-1 scope guard
 * ({@code docs/Phase1.md}) any change to observable behaviour is a port bug.</p>
 */
package cz.vutbr.fit.ags.railway.domain;
