/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.xhovor07;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import jade.core.Agent;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.messaging.TopicManagementHelper;
import jade.wrapper.AgentContainer;
import jade.wrapper.ControllerException;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.api.Timeout;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * One real JADE main container, in this JVM, for one test class — the L2 fixture
 * ({@code docs/TESTING.md} §4.2, {@code docs/Phase1.md} 1-POST.2, issue #38).
 *
 * <h2>The lifecycle decision, and why it is this one</h2>
 *
 * <b>One container per test class, one JVM per test class, one class at a time, and the agents
 * killed between methods.</b> The middle two are {@code build.gradle.kts}'s {@code forkEvery = 1} /
 * {@code maxParallelForks = 1} on the {@code integrationTest} task and the block comment there
 * argues them; this class is the other half. Three things force the container-per-class half:
 * <ul>
 * <li>{@code jade.core.Runtime} is a JVM-wide singleton and so is {@code jade.core.AID.platformID},
 *     which {@code AgentContainerImpl} overwrites at every boot. Two live containers in one JVM is
 *     not a configuration this application is written for.</li>
 * <li>Agent local names are unique per platform. A class that starts {@code stA} and a class that
 *     starts a different {@code stA} must not see each other's — with a container per class they
 *     cannot.</li>
 * <li>{@code RunControl}'s clock, {@code TraceProbe.READY} and {@code TraceProbe.FAILURES} are
 *     statics with no reset. A fresh JVM is the only reset there is, and it is what makes #37's
 *     three "cannot be tested at L1" items testable here.</li>
 * </ul>
 *
 * <b>Not one container per method</b>, which was the alternative. Agent local names are unique per
 * platform and this application hard-codes several of them — {@code Station.voteRequest} replies to
 * the literal {@code Main}, so every test needs an agent by that name — so something has to give
 * between two methods of one class. A container reboot per method would cost a JICP bind and an AMS
 * boot per test for no assertion; killing the agents costs neither and leaves the platform, and the
 * platform id, stable for the whole class. {@link #killAgentsStartedByTest} does it, and waits for
 * each name to actually leave the AMS rather than assuming {@code kill()} is synchronous — it is
 * not.
 *
 * <h2>The profile</h2>
 *
 * {@code Main.profile()}'s four parameters plus {@code GUI=false}:
 * <ul>
 * <li>{@code services} — {@code TopicManagementService}. Without it every topic is a silent dead
 *     letter and {@code TraceProbe.setup()} reports and returns; that is the failure this lane
 *     exists to be able to see rather than infer.</li>
 * <li>{@code port} — {@code 0}, ephemeral. A build machine never has a fixed port to free, and two
 *     lanes can never collide on one. It does <em>not</em> make the platform hermetic: the JICP
 *     intra-platform listener still binds a TCP socket, so a box that forbids listening sockets
 *     fails this lane at BOOT rather than at an assertion.</li>
 * <li>{@code mtps} — empty, no external transport.</li>
 * <li>{@code file-dir} — an <b>absolute</b> scratch directory, per class, deleted afterwards.
 *     JADE's AMS concatenates this with {@code APDescription.txt} rather than resolving it; #36
 *     lost a gate run to a relative value that put a 25-line {@code FileNotFoundException} ahead of
 *     line 1 of the trace.</li>
 * </ul>
 *
 * <h2>Waiting: bounded polls, never sleeps</h2>
 *
 * Every wait in this lane is a {@link BlockingQueue#poll(long, TimeUnit)} with a deadline, or a
 * loop that re-reads a condition and fails at a deadline. Nothing asserts after a fixed wait,
 * which is the property that separates a test that fails on a loaded runner from one that passes
 * on a broken build. {@link #assertQueueDrained} is the one loop that has no queue of its own to
 * pace on and it says so at its own comment.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@Timeout(value = 180, unit = TimeUnit.SECONDS)
abstract class ContainerFixture {

    /** How long a message may take to cross an in-process container. Generous on purpose. */
    static final long REPLY_MS = 15_000L;

    /** How long "and nothing else arrived" is observed for. */
    static final long SILENCE_MS = 750L;

    /** How long an agent's message queue may take to reach zero after the last reply. */
    static final long DRAIN_MS = 5_000L;

    private static final long POLL_STEP_MS = 5L;

    private AgentContainer container;
    private Path scratch;
    private final List<String> started = new ArrayList<String>();

    @BeforeAll
    final void bootContainer() throws Exception {
        final jade.core.Runtime runtime = jade.core.Runtime.instance();
        // Without this, JADE calls System.exit(0) when the last container dies and the Gradle
        // worker disappears mid-run rather than reporting a result.
        runtime.setCloseVM(false);
        scratch = Files.createTempDirectory(fileDirRoot(), "jade-it-");
        final Profile profile = new ProfileImpl();
        profile.setParameter(Profile.MAIN_PORT, "0");
        profile.setParameter(Profile.MTPS, "");
        profile.setParameter(Profile.GUI, "false");
        profile.setParameter(Profile.FILE_DIR, scratch.toAbsolutePath() + File.separator);
        profile.setParameter(Profile.SERVICES, TopicManagementHelper.SERVICE_NAME + "Service");
        container = runtime.createMainContainer(profile);
        assertNotNull(container, "the JADE main container did not come up:"
                + " createMainContainer returned null, which means joinPlatform() failed");
    }

    /**
     * Kill everything this test method started, and wait for the platform to forget it.
     * <p>
     * {@code AgentController.kill()} is asynchronous: it hands a request to the container and
     * returns. A next test that started an agent with the same local name before the AMS had
     * dropped the old one would fail with a name clash that has nothing to do with its assertions,
     * so this waits — bounded, and paced the way everything in this lane is paced.
     *
     * @throws InterruptedException if interrupted
     */
    @AfterEach
    final void killAgentsStartedByTest() throws InterruptedException {
        final List<String> names = new ArrayList<String>(started);
        started.clear();
        if (container == null) {
            return;
        }
        for (String name : names) {
            try {
                container.getAgent(name).kill();
            } catch (Exception ignored) {
                // Already gone -- a Train that reached its destination kills itself.
            }
        }
        final BlockingQueue<Object> pacer = new LinkedBlockingQueue<Object>();   // stays empty
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_MS);
        for (String name : names) {
            while (isAlive(name) && System.nanoTime() < deadline) {
                pacer.poll(POLL_STEP_MS, TimeUnit.MILLISECONDS);
            }
            assertFalse(isAlive(name), () -> "agent '" + name + "' is still on the platform "
                    + DRAIN_MS + " ms after kill(); the next test cannot reuse the name");
        }
    }

    @AfterAll
    final void killContainer() {
        if (container != null) {
            try {
                container.kill();
            } catch (Exception ignored) {
                // Teardown of a JVM-wide singleton is noisy and irrelevant to the assertions.
            }
            container = null;
        }
        deleteScratch();
    }

    /**
     * The absolute root the per-class scratch directory is made under. The build passes
     * {@code -Djade.file.dir=<abs>}; a bare {@code ./gradlew} invocation or an IDE run falls back
     * to the JVM temp directory, which is also absolute. There is no relative branch on purpose.
     */
    private static Path fileDirRoot() throws IOException {
        final String configured = System.getProperty(Main.FILE_DIR_PROPERTY);
        final Path root = (configured == null || configured.isBlank())
                ? Path.of(System.getProperty("java.io.tmpdir"))
                : Path.of(configured);
        Files.createDirectories(root);
        return root.toAbsolutePath();
    }

    private void deleteScratch() {
        if (scratch == null) {
            return;
        }
        try (java.util.stream.Stream<Path> entries = Files.walk(scratch)) {
            entries.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // best effort
                }
            });
        } catch (IOException ignored) {
            // best effort
        }
        scratch = null;
    }

    // ------------------------------------------------------------------ the platform

    /**
     * The platform name every {@code new AID(local, ISLOCALNAME)} in this JVM now carries.
     * <p>
     * Read from the container rather than hard-coded. {@code AID.platformID} is a JVM-global static
     * that a container boot overwrites, and {@code JadeDeliverySpikeTest} exists partly to pin that
     * hazard; a GUID assertion in this lane derives the name, never spells it.
     *
     * @return e.g. {@code beda-lp}
     */
    protected final String platformId() {
        return container.getPlatformName();
    }

    /**
     * Start a real agent instance inside the container, with its construction arguments.
     * <p>
     * {@code acceptNewAgent} rather than {@code createNewAgent} deliberately: the test keeps a
     * reference to the very object the platform is running, which is what lets an assertion read
     * {@link Agent#getCurQueueSize()} and the agent's own package-private observables
     * ({@code Station.pendingTargets()}, {@code Station.computeDifference(...)}) instead of
     * inferring them from messages. That is the difference between "the reply looked right" and
     * "the receiver's state changed".
     *
     * @param name the local name, which is also the name every trace field would carry
     * @param agent the agent instance
     * @param args the construction arguments its {@code setup()} reads back, if any
     * @param <A> the agent type
     * @return {@code agent}
     * @throws Exception if the container refuses the agent
     */
    protected final <A extends Agent> A start(String name, A agent, Object... args) throws Exception {
        if (args.length > 0) {
            agent.setArguments(args);
        }
        container.acceptNewAgent(name, agent).start();
        started.add(name);
        return agent;
    }

    /**
     * Start a {@link Driver} and wait for it to be ready to be driven.
     *
     * @param name the local name it answers to — {@code Main}, {@code vl3}, whatever the agent
     *        under test will address
     * @return the driver
     * @throws Exception if the container refuses it, or it is not ready within {@link #REPLY_MS}
     */
    protected final Driver driver(String name) throws Exception {
        final Driver driver = start(name, new Driver());
        driver.awaitReady(REPLY_MS);
        return driver;
    }

    /** @return whether the platform still knows an agent by that local name */
    protected final boolean isAlive(String localName) {
        try {
            container.getAgent(localName);
            return true;
        } catch (ControllerException e) {
            return false;
        }
    }

    /**
     * Assert an agent leaves the platform, within a bound.
     * <p>
     * The only agent in this application that ends itself is {@code Train}, which calls
     * {@code Agent.die()} on the {@code ENTER_REPLY} whose {@code next} is {@code null}. Death is
     * asynchronous — {@code doDelete()} schedules the transition, {@code takeDown()} runs, and only
     * then does the AMS drop the name — so this is a bounded condition poll, not a single read.
     *
     * @param localName the agent's local name
     * @param timeoutMs bound
     * @throws InterruptedException if interrupted
     */
    protected final void assertDiesWithin(String localName, long timeoutMs) throws InterruptedException {
        final BlockingQueue<Object> pacer = new LinkedBlockingQueue<Object>();   // stays empty
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs);
        while (isAlive(localName) && System.nanoTime() < deadline) {
            pacer.poll(POLL_STEP_MS, TimeUnit.MILLISECONDS);
        }
        assertFalse(isAlive(localName), () -> "agent '" + localName + "' is still on the platform "
                + timeoutMs + " ms after it should have died");
    }

    // ------------------------------------------------------------------ bounded waits

    /**
     * <b>The acceptance criterion #38 calls "the most likely JADE-only hang".</b> A JADE agent has
     * one message queue; {@code receive(template)} consumes only what matches, and a message
     * matching no active template sits there for the life of the run. So every interaction test
     * ends here.
     * <p>
     * This is the one wait in the lane with no queue of its own to pace on, so it paces on an empty
     * {@link BlockingQueue} instead. That is a poll and not a sleep in the sense that matters: the
     * loop re-reads the condition every step and can only ever <em>fail</em> at the deadline —
     * waiting longer cannot make it pass.
     *
     * @param agent the agent under test
     * @throws InterruptedException if the test thread is interrupted
     */
    protected static void assertQueueDrained(Agent agent) throws InterruptedException {
        final BlockingQueue<Object> pacer = new LinkedBlockingQueue<Object>();   // stays empty
        final long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(DRAIN_MS);
        while (agent.getCurQueueSize() != 0 && System.nanoTime() < deadline) {
            pacer.poll(POLL_STEP_MS, TimeUnit.MILLISECONDS);
        }
        assertEquals(0, agent.getCurQueueSize(), () -> "agent " + agent.getLocalName()
                + " still holds " + agent.getCurQueueSize() + " message(s) after " + DRAIN_MS
                + " ms. A message that matches no active MessageTemplate is never consumed, and"
                + " Behaviour.block() suspends the whole agent -- this is the template gap"
                + " docs/message-ontology.md section 7 exists to make impossible.");
    }
}
