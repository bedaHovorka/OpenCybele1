/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.xhovor07;

import cz.vutbr.fit.ags.railway.domain.clock.AgentClock;
import cz.vutbr.fit.ags.railway.domain.clock.VirtualClock;
import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.PlanTrain;
import cz.vutbr.fit.ags.railway.domain.msg.RailwayMessage;
import jade.core.Agent;
import jade.core.JadeAgentFixture;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * L1 for #33: the train source, as a POJO. A real {@link Generator} installed on a real (unstarted)
 * JADE agent, with its two seams — {@code createTrain} and {@code emit} — redirected into lists,
 * and simulated time a {@link VirtualClock} the test owns ({@code docs/TESTING.md} §4.1). The drain
 * a {@code ClockTickerBehaviour} performs on the agent thread is performed here by
 * {@code AgentClock.runDue()}: same call, same thread rules, no ticker.
 * <p>
 * What each group holds down:
 * <ul>
 *   <li><b>the re-arm shape.</b> This is not a {@code TickerBehaviour} and must not become one: the
 *       interval is redrawn from an exponential distribution on <em>every</em> fire, so the
 *       inter-arrival gaps are not equal and a fixed period would be a different simulation. The
 *       tests below assert the gaps are the ones the seeded stream predicts, one at a time, rather
 *       than merely "more than one train appeared";</li>
 *   <li><b>the RNG equivalence.</b> {@code docs/seeded-rng.md}: a stream is pinned by its own draw
 *       count and order. The expectations are computed by replaying {@code SimRandom.forAgent} in
 *       the test, so a port that reordered the two draws, shared one stream between them, or drew
 *       for the first fire would fail rather than merely produce different numbers;</li>
 *   <li><b>the barrier's half of the contract.</b> {@code start()} arms, is idempotent, and is what
 *       the hub calls; the constructor arms nothing.</li>
 * </ul>
 */
class GeneratorTest {

    private record Sent(RailwayMessage message, String receiver) {
    }

    private record Created(String train, Serializable[] args) {
    }

    /** A real generator with both container-touching seams redirected. */
    private static final class RecordingGenerator extends Generator {
        private static final long serialVersionUID = 1L;
        private final transient List<Sent> sent = new ArrayList<Sent>();
        private final transient List<Created> created = new ArrayList<Created>();

        RecordingGenerator(Agent host, AgentClock clock) {
            super(host, clock);
        }

        @Override
        protected void createTrain(String train, Serializable[] originDestination) {
            created.add(new Created(train, originDestination));
        }

        @Override
        protected void emit(RailwayMessage message, String receiver) {
            sent.add(new Sent(message, receiver));
        }

        List<String> createdNames() {
            List<String> names = new ArrayList<String>();
            for (Created c : created) {
                names.add(c.train());
            }
            return names;
        }
    }

    /** The generator, its host agent and the clock they share. */
    private record Fixture(RecordingGenerator generator, Agent host, VirtualClock clock,
            AgentClock agentClock) {
    }

    private static Fixture fixture() {
        Agent host = new Agent();
        JadeAgentFixture.name(host, RailwayMainAgent.MAIN_AGENT_NAME);
        VirtualClock clock = new VirtualClock(0L, 1.0);
        AgentClock agentClock = new AgentClock(clock);
        return new Fixture(new RecordingGenerator(host, agentClock), host, clock, agentClock);
    }

    /** The inter-arrival draws the seeded stream will produce, in order — the port's own formula. */
    private static long[] expectedGaps(int count) {
        Random stream = SimRandom.forAgent(SimRandom.GENERATOR_INTERARRIVAL_STREAM);
        long lambda = ScenarioConfig.get().getArrivalLambdaMs();
        long[] gaps = new long[count];
        for (int i = 0; i < count; i++) {
            gaps[i] = Math.round(-((double) lambda) * Math.log(stream.nextDouble()));
        }
        return gaps;
    }

    /** The origin/destination pairs the seeded stream will pick, in order. */
    private static List<List<String>> expectedPairs(int count) {
        Random stream = SimRandom.forAgent(SimRandom.GENERATOR_OD_STREAM);
        Serializable[][] pairs = ScenarioConfig.get().getTrainPairs();
        List<List<String>> out = new ArrayList<List<String>>();
        for (int i = 0; i < count; i++) {
            Serializable[] pair = pairs[stream.nextInt(pairs.length)];
            out.add(List.of((String) pair[0], (String) pair[1]));
        }
        return out;
    }

    // ------------------------------------------------------------------ arming

    @Test
    @DisplayName("the constructor arms nothing; start() arms the fixed first fire")
    void the_constructor_does_not_arm() {
        Fixture f = fixture();
        assertFalse(f.generator().isArmed());
        assertEquals(0, f.agentClock().pending(),
                "the 2008 constructor armed the timer; here the hub's startup barrier decides when");

        f.generator().start();

        assertTrue(f.generator().isArmed());
        assertEquals(1, f.agentClock().pending());
        assertEquals(Long.valueOf(ScenarioConfig.get().getArrivalFirstFireMs()),
                f.agentClock().nextDueMs(),
                "the first fire is a FIXED sim.arrival.firstFireMs, not an exponential draw");
    }

    @Test
    @DisplayName("start() is idempotent, so a second call cannot open a second chain of trains")
    void starting_twice_arms_once() {
        Fixture f = fixture();
        f.generator().start();
        f.generator().start();
        f.generator().start();

        assertEquals(1, f.agentClock().pending());

        f.clock().advanceSimTo(ScenarioConfig.get().getArrivalFirstFireMs());
        f.agentClock().runDue();

        assertEquals(List.of("vl0"), f.generator().createdNames(),
                "three starts must not produce three vl0s on one counter");
    }

    @Test
    @DisplayName("a generator needs a host and a clock, and says which is missing")
    void the_construction_arguments_are_checked() {
        Agent host = new Agent();
        JadeAgentFixture.name(host, RailwayMainAgent.MAIN_AGENT_NAME);
        assertThrows(IllegalArgumentException.class,
                () -> new Generator(null, new AgentClock(new VirtualClock(0L, 1.0))));
        assertThrows(IllegalArgumentException.class, () -> new Generator(host, null));
    }

    // ------------------------------------------------------------------ the re-arm

    @Test
    @DisplayName("each fire creates one train, announces it, and re-arms with a FRESH exponential draw")
    void the_wake_up_reschedules_itself_one_shot_at_a_time() {
        Fixture f = fixture();
        long first = ScenarioConfig.get().getArrivalFirstFireMs();
        long[] gaps = expectedGaps(4);

        f.generator().start();

        long due = first;
        for (int i = 0; i < 4; i++) {
            assertEquals(Long.valueOf(due), f.agentClock().nextDueMs(), "fire " + i);
            f.clock().advanceSimTo(due);
            assertEquals(1, f.agentClock().runDue(), "exactly one wake-up is due at " + due);
            assertEquals(i + 1, f.generator().created.size());
            assertEquals(1, f.agentClock().pending(),
                    "one armed wake-up at a time -- a self-rescheduling ONE-SHOT, not a ticker");
            due += gaps[i];
        }

        assertEquals(List.of("vl0", "vl1", "vl2", "vl3"), f.generator().createdNames());
        // The four gaps are the exponential stream's, and they are not all equal -- which is the
        // whole reason CYBELLE_TO_JADE.md's "periodic activity -> TickerBehaviour" row does not
        // apply to this site.
        assertTrue(gaps[0] != gaps[1] || gaps[1] != gaps[2] || gaps[2] != gaps[3],
                "an exponential inter-arrival process is not a fixed period: " + gaps[0] + ", "
                        + gaps[1] + ", " + gaps[2] + ", " + gaps[3]);
    }

    @Test
    @DisplayName("PLAN_TRAIN carries the drawn pair and is addressed Main -> Main")
    void the_announcement_is_a_self_addressed_PLAN_TRAIN() {
        Fixture f = fixture();
        List<List<String>> pairs = expectedPairs(3);
        f.generator().start();
        fire(f, 3);

        assertEquals(3, f.generator().sent.size());
        for (int i = 0; i < 3; i++) {
            Sent sent = f.generator().sent.get(i);
            assertEquals(Channel.PLAN_TRAIN, sent.message().channel());
            assertEquals(RailwayMainAgent.MAIN_AGENT_NAME, sent.receiver(),
                    "CH-13 is Main -> Main, in trace fields 4 AND 5");
            PlanTrain plan = (PlanTrain) sent.message();
            assertEquals("vl" + i, plan.train());
            assertEquals(pairs.get(i), List.of(plan.from(), plan.to()));
            // The Train agent got the same pair, as its two construction arguments.
            assertEquals(pairs.get(i), List.of((String) f.generator().created.get(i).args()[0],
                    (String) f.generator().created.get(i).args()[1]));
        }
    }

    @Test
    @DisplayName("the train is created BEFORE its PLAN_TRAIN goes out, as in 2008")
    void the_agent_exists_before_it_is_announced() {
        Fixture f = fixture();
        List<String> order = new ArrayList<String>();
        Agent host = new Agent();
        JadeAgentFixture.name(host, RailwayMainAgent.MAIN_AGENT_NAME);
        VirtualClock clock = new VirtualClock(0L, 1.0);
        AgentClock agentClock = new AgentClock(clock);
        Generator generator = new Generator(host, agentClock) {
            private static final long serialVersionUID = 1L;

            @Override
            protected void createTrain(String train, Serializable[] originDestination) {
                order.add("create " + train);
            }

            @Override
            protected void emit(RailwayMessage message, String receiver) {
                order.add("emit " + ((PlanTrain) message).train());
            }
        };
        generator.start();
        clock.advanceSimTo(ScenarioConfig.get().getArrivalFirstFireMs());
        agentClock.runDue();

        // Under Cybele this ordering was load-bearing: SEM-06 drops a sendAll to a channel nobody
        // has opened, so the train had to exist first. JADE queues instead, so nothing depends on
        // it any more -- but the statement order is 2008's and there is no reason to move it.
        assertEquals(List.of("create vl0", "emit vl0"), order);
    }

    // ------------------------------------------------------------------ the two streams

    @Test
    @DisplayName("one od draw per train and one interarrival draw per RE-ARM, in that order")
    void the_draw_counts_and_order_are_the_2008_ones() {
        Fixture f = fixture();
        // Replayed from the same seeds, ahead of the generator, so an extra or reordered draw
        // inside generateTrain() shows up as a mismatch rather than as "different numbers".
        List<List<String>> pairs = expectedPairs(5);
        long[] gaps = expectedGaps(5);

        f.generator().start();
        long due = ScenarioConfig.get().getArrivalFirstFireMs();
        for (int i = 0; i < 5; i++) {
            f.clock().advanceSimTo(due);
            f.agentClock().runDue();
            PlanTrain plan = (PlanTrain) f.generator().sent.get(i).message();
            assertEquals(pairs.get(i), List.of(plan.from(), plan.to()), "od draw " + i);
            due += gaps[i];
            assertEquals(Long.valueOf(due), f.agentClock().nextDueMs(), "interarrival draw " + i);
        }
        assertEquals(5, f.generator().generated());
    }

    @Test
    @DisplayName("the two streams are separate: the od choice does not consume interarrival values")
    void the_streams_do_not_share() {
        // The seeds are a pure function of the master seed and the stream NAME (#15), so two
        // generators built from the same configuration draw the same two sequences. A port that
        // collapsed them into one shared Random -- which is what 2008 had before #15 -- would make
        // the second generator's values depend on the first's interleaving.
        Fixture a = fixture();
        Fixture b = fixture();
        a.generator().start();
        b.generator().start();
        fire(a, 4);
        fire(b, 4);

        assertEquals(a.generator().createdNames(), b.generator().createdNames());
        for (int i = 0; i < 4; i++) {
            assertEquals(a.generator().sent.get(i).message(), b.generator().sent.get(i).message(),
                    "same seed, same stream key, same draw " + i);
        }
        assertEquals(a.agentClock().nextDueMs(), b.agentClock().nextDueMs());
    }

    @Test
    @DisplayName("the first fire draws nothing on the interarrival stream")
    void the_fixed_first_fire_is_not_a_draw() {
        Fixture f = fixture();
        long[] gaps = expectedGaps(1);

        f.generator().start();
        f.clock().advanceSimTo(ScenarioConfig.get().getArrivalFirstFireMs());
        f.agentClock().runDue();

        // If start() had drawn, the gap the FIRST re-arm uses would be the stream's SECOND value
        // and this would fail. That is the alignment seeded-rng.md warns about: "a stream is
        // pinned by its own draw count".
        assertEquals(Long.valueOf(ScenarioConfig.get().getArrivalFirstFireMs() + gaps[0]),
                f.agentClock().nextDueMs());
    }

    private static void fire(Fixture f, int times) {
        for (int i = 0; i < times; i++) {
            Long due = f.agentClock().nextDueMs();
            f.clock().advanceSimTo(due.longValue());
            f.agentClock().runDue();
        }
    }
}
