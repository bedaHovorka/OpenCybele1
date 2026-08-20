/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.jade;

import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.EnterRequest;
import cz.vutbr.fit.ags.railway.domain.msg.RailwayMessage;
import cz.vutbr.fit.ags.railway.domain.msg.StationInfo;
import jade.core.AID;
import jade.core.Agent;
import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.behaviours.CyclicBehaviour;
import jade.core.messaging.TopicManagementHelper;
import jade.lang.acl.ACLMessage;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The two-agent spike #27 owes #30&ndash;#36: <strong>direct AID unicast and topic eavesdropping,
 * both proved on a real JADE platform, before a single agent is ported.</strong>
 * <p>
 * It boots one main container on an ephemeral port with no MTP and
 * {@code jade.core.messaging.TopicManagementService} in the profile, runs two receiver agents and
 * one probe, and sends real {@code ACLMessage}s built by {@link Messages}. What it establishes:
 * <ul>
 *   <li>a message addressed to {@code new AID(localName, AID.ISLOCALNAME)} reaches that agent and
 *       only that agent — no DF, no registration, nothing but the AMS white pages;</li>
 *   <li>an agent registered to a channel's <em>topic</em> receives a copy of a message it was
 *       never addressed to, with the ontology slot and the content record intact;</li>
 *   <li>the extra topic receiver is genuinely additive — with it omitted the probe sees nothing,
 *       so a probe-off run is the run it would have been anyway;</li>
 *   <li><strong>one per-constant topic observes every instance of that channel.</strong> The probe
 *       registers to {@code railway.ENTER} once and sees {@code ENTER.stA} and {@code ENTER.stB}
 *       alike. That is the topic-granularity decision, end to end.</li>
 * </ul>
 * The container is a JVM-wide singleton ({@code jade.core.Runtime}), so this runs once per test
 * JVM and Gradle's {@code test} task is configured with one fork.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class JadeDeliverySpikeTest {

    /** What a receiving agent observed: which agent, which channel, which subject, which payload. */
    record Observed(String observer, String ontology, String subject, String namedReceiver, RailwayMessage content) {
    }

    /** One send the courier agent has to perform. */
    record Outbound(RailwayMessage message, String from, String to, boolean copyToTopic) {
    }

    static final BlockingQueue<Observed> OBSERVED = new LinkedBlockingQueue<>();
    static final BlockingQueue<Outbound> OUTBOX = new LinkedBlockingQueue<>();

    private static jade.wrapper.AgentContainer container;
    private static java.nio.file.Path scratch;

    @BeforeAll
    static void bootPlatform() throws Exception {
        jade.core.Runtime runtime = jade.core.Runtime.instance();
        runtime.setCloseVM(false);
        Profile profile = new ProfileImpl();
        // An ephemeral port and no message transport: this platform talks to nothing outside
        // the JVM, so a build machine never has a port to free and never opens a socket to the
        // world. A real deployment would set neither.
        profile.setParameter(Profile.MAIN_PORT, "0");
        profile.setParameter(Profile.MTPS, "");
        // JADE's AMS writes APDescription.txt on startup, into getProperty("file-dir", "") --
        // i.e. the working directory, which for a Gradle test JVM is the project root. Point it
        // at a scratch directory instead, so a build never leaves an untracked file behind.
        scratch = java.nio.file.Files.createTempDirectory("jade-spike");
        profile.setParameter("file-dir", scratch.toAbsolutePath() + java.io.File.separator);
        // THE activation line #27 owes the port. Without this the TopicManagementHelper lookup
        // below throws ServiceException and every topic is silently a dead letter.
        profile.setParameter(Profile.SERVICES, TopicManagementHelper.SERVICE_NAME + "Service");
        container = runtime.createMainContainer(profile);

        container.createNewAgent("stA", Receiver.class.getName(), null).start();
        container.createNewAgent("stB", Receiver.class.getName(), null).start();
        container.createNewAgent("probe", Probe.class.getName(), null).start();
        container.createNewAgent("courier", Courier.class.getName(), null).start();
        // The probe must be subscribed before the first message of the run, exactly as the
        // Cybele TraceProbe blocks in awaitReady() before RailwayMainAgent is created.
        assertTrue(Probe.READY.await(30, TimeUnit.SECONDS), "probe did not register to its topics");
    }

    @AfterAll
    static void shutdown() {
        if (container != null) {
            try {
                container.kill();
            } catch (Exception ignored) {
                // Teardown of a JVM-wide singleton is noisy and irrelevant to the assertions.
            }
        }
        if (scratch != null) {
            try (var entries = java.nio.file.Files.walk(scratch)) {
                entries.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                    try {
                        java.nio.file.Files.deleteIfExists(path);
                    } catch (java.io.IOException ignored) {
                        // best effort
                    }
                });
            } catch (java.io.IOException ignored) {
                // best effort
            }
        }
    }

    /**
     * The observation queue is shared by every test in this class, and JADE delivery is
     * asynchronous, so a message from a previous test could otherwise still be in flight when
     * the next one starts counting. Wait for the platform to go quiet, then discard.
     */
    @org.junit.jupiter.api.BeforeEach
    void quiesce() throws InterruptedException {
        while (OBSERVED.poll(250, TimeUnit.MILLISECONDS) != null) {
            // drain
        }
    }

    @Test
    @DisplayName("a unicast message reaches the named agent, and only it")
    void unicast_reaches_exactly_the_named_agent() throws Exception {
        EnterRequest enter = new EnterRequest("vl3", null, "stB");
        send(new Outbound(enter, "vl3", "stA", false));

        Observed byStation = awaitFrom("stA");
        assertEquals(Channel.ENTER.id(), byStation.ontology());
        assertEquals("vl3", byStation.subject());
        assertEquals("stA", byStation.namedReceiver());
        assertEquals(enter, byStation.content());
        // Field selection survives the platform: the station reads the destination, and the
        // slot it ignores is still there for the track that would read it.
        assertEquals("stB", ((EnterRequest) byStation.content()).endStation());
        assertNull(((EnterRequest) byStation.content()).arrivingFrom());

        // Nobody else saw it -- neither the other station nor the probe, which was not copied.
        assertNull(OBSERVED.poll(1, TimeUnit.SECONDS));
    }

    @Test
    @DisplayName("the probe sees a message it was never addressed to, via the channel topic")
    void the_probe_eavesdrops_without_being_addressed() throws Exception {
        StationInfo info = new StationInfo(1, 6);
        send(new Outbound(info, "stA", "stB", true));

        List<Observed> both = await(2);
        Observed named = pick(both, "stB");
        Observed probed = pick(both, "probe");

        assertEquals(Channel.STATION_INFO.id(), named.ontology());
        assertEquals(info, named.content());
        // The probe's copy carries the same ontology slot, the same subject and the same record.
        assertEquals(Channel.STATION_INFO.id(), probed.ontology());
        assertEquals(info, probed.content());
        assertEquals("stA", probed.subject());
        // And trace field 5 still names the agent, never the topic.
        assertEquals("stB", probed.namedReceiver());
    }

    @Test
    @DisplayName("one per-constant topic observes every instance of that channel")
    void one_topic_per_constant_covers_every_instance() throws Exception {
        // The probe registered to railway.ENTER once, at boot. It now sees ENTER addressed to
        // two different stations without registering anything further. Under per-instance
        // topics these would be ENTER.stA and ENTER.stB -- two topics, and two more for every
        // station in the topology, and three more for every train ever generated.
        send(new Outbound(new EnterRequest("vl7", "tr1", "stB"), "vl7", "stA", true));
        send(new Outbound(new EnterRequest("vl8", "tr2", "stA"), "vl8", "stB", true));

        List<Observed> all = await(4);
        List<Observed> probed = all.stream().filter(o -> o.observer().equals("probe")).toList();
        assertEquals(2, probed.size(), "the probe must see both instances on the one topic");
        assertEquals(List.of("stA", "stB"),
                probed.stream().map(Observed::namedReceiver).sorted().toList());
        assertEquals(List.of("vl7", "vl8"),
                probed.stream().map(Observed::subject).sorted().toList());
        for (Observed o : probed) {
            assertEquals(Channel.ENTER.id(), o.ontology());
        }
    }

    // ---------------------------------------------------------------------------------------

    private static void send(Outbound outbound) {
        OUTBOX.add(outbound);
    }

    private static Observed awaitFrom(String observer) throws InterruptedException {
        Observed o = OBSERVED.poll(30, TimeUnit.SECONDS);
        assertNotNull(o, "no message observed within 30 s");
        assertEquals(observer, o.observer());
        return o;
    }

    private static List<Observed> await(int count) throws InterruptedException {
        List<Observed> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            Observed o = OBSERVED.poll(30, TimeUnit.SECONDS);
            assertNotNull(o, "only " + out.size() + " of " + count + " messages observed within 30 s");
            out.add(o);
        }
        return out;
    }

    private static Observed pick(List<Observed> observed, String observer) {
        return observed.stream().filter(o -> o.observer().equals(observer)).findFirst()
                .orElseThrow(() -> new AssertionError("nothing observed by " + observer + ": " + observed));
    }

    private static void record(Agent agent, ACLMessage acl) {
        OBSERVED.add(new Observed(agent.getLocalName(), acl.getOntology(),
                Messages.subjectOf(acl), Messages.receiverName(acl), Messages.contentOf(acl)));
    }

    /** An ordinary addressed agent. Consumes everything its kind is supposed to receive. */
    public static final class Receiver extends Agent {
        @Override
        protected void setup() {
            addBehaviour(new CyclicBehaviour(this) {
                @Override
                public void action() {
                    ACLMessage acl = receive();
                    if (acl == null) {
                        block();
                        return;
                    }
                    record(myAgent, acl);
                }
            });
        }
    }

    /**
     * The probe. It registers to all fifteen per-constant topics and is addressed by nobody.
     * This is #36's shape in miniature.
     */
    public static final class Probe extends Agent {
        static final java.util.concurrent.CountDownLatch READY = new java.util.concurrent.CountDownLatch(1);

        @Override
        protected void setup() {
            try {
                TopicManagementHelper helper =
                        (TopicManagementHelper) getHelper(TopicManagementHelper.SERVICE_NAME);
                for (Channel channel : Channel.values()) {
                    helper.register(RailwayOntology.topic(helper, channel));
                }
            } catch (Exception e) {
                throw new IllegalStateException("TopicManagementService is not active", e);
            }
            READY.countDown();
            addBehaviour(new CyclicBehaviour(this) {
                @Override
                public void action() {
                    ACLMessage acl = receive();
                    if (acl == null) {
                        block();
                        return;
                    }
                    record(myAgent, acl);
                }
            });
        }
    }

    /** Sends whatever the test puts in the outbox, from inside the platform. */
    public static final class Courier extends Agent {
        @Override
        protected void setup() {
            addBehaviour(new CyclicBehaviour(this) {
                @Override
                public void action() {
                    Outbound out = OUTBOX.poll();
                    if (out == null) {
                        block(20);
                        return;
                    }
                    AID topic = null;
                    if (out.copyToTopic()) {
                        try {
                            TopicManagementHelper helper = (TopicManagementHelper)
                                    myAgent.getHelper(TopicManagementHelper.SERVICE_NAME);
                            topic = RailwayOntology.topic(helper, out.message().channel());
                        } catch (Exception e) {
                            throw new IllegalStateException(e);
                        }
                    }
                    myAgent.send(Messages.build(out.message(), out.from(), out.to(), topic));
                }
            });
        }
    }
}
