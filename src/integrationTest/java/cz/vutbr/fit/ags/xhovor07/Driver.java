/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.xhovor07;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.RailwayMessage;
import cz.vutbr.fit.ags.railway.jade.Messages;
import jade.core.AID;
import jade.core.Agent;
import jade.core.behaviours.CyclicBehaviour;
import jade.lang.acl.ACLMessage;
import jade.wrapper.AgentController;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The test's hands and eyes inside the platform: an agent that sends what the test tells it to and
 * hands back everything it receives (#38).
 *
 * <h2>Why it is an agent and not the test thread calling {@code send}</h2>
 *
 * An {@code ACLMessage} has to be sent by an agent — the {@code :sender} slot is the thing the
 * agent under test replies to, and {@code Agent.send} needs the agent's toolkit. So the test needs
 * a real agent with a real local name. Which name matters: {@code Station.voteRequest} replies to
 * the literal {@code RailwayMainAgent.MAIN_AGENT_NAME}, so the driver that collects votes has to be
 * called {@code Main}; {@code Station.enter} replies to the train named in the payload, so the
 * driver that collects an {@code ENTER_REPLY} has to be called {@code vl3}. <b>That is the
 * addressing mechanism under test, not a fixture detail.</b>
 *
 * <h2>Why the outbox is an O2A channel and not a polled queue</h2>
 *
 * {@code Agent.putO2AObject} appends to the agent's object-to-agent queue and then calls
 * {@code activateAllBehaviours()}, so the send happens on the agent's own thread the moment the
 * test asks for it. The obvious alternative — a {@code CyclicBehaviour} doing {@code block(20)} over
 * a shared queue — costs a 20 ms polling loop per driver and makes every timing in the lane a
 * little bit approximate. This one has no timer in it at all.
 * <p>
 * {@code setEnabledO2ACommunication} has to run on the agent thread before the first
 * {@code putO2AObject}, so {@link #awaitReady(long)} is a barrier the fixture crosses before it
 * hands the driver to a test.
 */
final class Driver extends Agent {

    private static final long serialVersionUID = 1L;

    /** Everything this agent has received, oldest first. */
    private final transient BlockingQueue<ACLMessage> inbox = new LinkedBlockingQueue<ACLMessage>();

    /** Crossed once {@code setup()} has enabled the O2A channel. */
    private final transient CountDownLatch ready = new CountDownLatch(1);

    @Override
    protected void setup() {
        // 0 = unbounded. A test that outran the agent would otherwise silently drop a send.
        setEnabledO2ACommunication(true, 0);
        addBehaviour(new CyclicBehaviour(this) {
            private static final long serialVersionUID = 1L;

            @Override
            public void action() {
                final Object outbound = myAgent.getO2AObject();
                if (outbound != null) {
                    myAgent.send((ACLMessage) outbound);
                    return;
                }
                // receive() with no template on purpose: a driver stands in for a whole side of a
                // protocol and must be able to see anything, including an AMS FAILURE.
                final ACLMessage acl = myAgent.receive();
                if (acl == null) {
                    block();
                    return;
                }
                inbox.add(acl);
            }
        });
        ready.countDown();
    }

    /**
     * @param timeoutMs bound
     * @throws InterruptedException if interrupted
     */
    void awaitReady(long timeoutMs) throws InterruptedException {
        assertTrue(ready.await(timeoutMs, TimeUnit.MILLISECONDS),
                "driver " + getLocalName() + " did not finish setup() within " + timeoutMs + " ms");
    }

    // ------------------------------------------------------------------ sending

    /**
     * Send one railway message by direct AID unicast — the addressing #27 chose for all fifteen
     * channels, built by the production {@code Messages.build}.
     * <p>
     * <b>This is the five agents' {@code emit} seam, character for character</b>, including the
     * {@code TraceTopics.of(channel)} third argument: with tracing off that is {@code null} and no
     * second receiver is added, and with it on the channel's topic AID rides along. A driver that
     * used the two-argument overload instead would be a sender the probe can never see, which is
     * not what any agent in this application is — and an addressing test built on one would be
     * measuring the fixture rather than the port.
     *
     * @param message the payload record
     * @param receiverLocalName the addressed agent's local name
     */
    void send(RailwayMessage message, String receiverLocalName) {
        post(Messages.build(message, getLocalName(), receiverLocalName,
                TraceTopics.of(message.channel())));
    }

    /**
     * Send one railway message with the channel's probe topic added as a second receiver.
     *
     * @param message the payload record
     * @param receiverLocalName the addressed agent's local name
     * @param topic the topic AID, from {@code TraceTopics.topic(channel)}
     */
    void send(RailwayMessage message, String receiverLocalName, AID topic) {
        post(Messages.build(message, getLocalName(), receiverLocalName, topic));
    }

    /**
     * Send an already-built message. The escape hatch for the addressing tests, which have to
     * address an AID no agent answers to.
     *
     * @param acl the message
     */
    void post(ACLMessage acl) {
        try {
            putO2AObject(acl, AgentController.ASYNC);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while handing a message to " + getLocalName(), e);
        }
    }

    // ------------------------------------------------------------------ receiving

    /**
     * The next message, or {@code null} if none arrives in time.
     *
     * @param timeoutMs bound
     * @return the message or {@code null}
     * @throws InterruptedException if interrupted
     */
    ACLMessage poll(long timeoutMs) throws InterruptedException {
        return inbox.poll(timeoutMs, TimeUnit.MILLISECONDS);
    }

    /**
     * The next message on a named channel, failing if it is not that channel or does not come.
     *
     * @param expected the channel it must be on
     * @param timeoutMs bound
     * @return the message
     * @throws InterruptedException if interrupted
     */
    ACLMessage expect(Channel expected, long timeoutMs) throws InterruptedException {
        final ACLMessage acl = poll(timeoutMs);
        assertNotNull(acl, () -> getLocalName() + " received no " + expected.event() + " within "
                + timeoutMs + " ms");
        assertEquals(expected.id(), acl.getOntology(), () -> getLocalName() + " expected "
                + expected.event() + " but got ontology=" + acl.getOntology() + " performative="
                + ACLMessage.getPerformative(acl.getPerformative()) + " from="
                + Messages.senderName(acl));
        return acl;
    }

    /**
     * The payload of the next message on a named channel.
     *
     * @param expected the channel
     * @param type the record class
     * @param timeoutMs bound
     * @param <T> the record type
     * @return the payload
     * @throws InterruptedException if interrupted
     */
    <T extends RailwayMessage> T expectContent(Channel expected, Class<T> type, long timeoutMs)
            throws InterruptedException {
        return type.cast(Messages.contentOf(expect(expected, timeoutMs)));
    }

    /**
     * Assert nothing further arrives here. A bounded poll, so it is a real observation window and
     * not an absence argued from a zero-length one.
     *
     * @param timeoutMs how long silence must hold
     * @throws InterruptedException if interrupted
     */
    void expectSilence(long timeoutMs) throws InterruptedException {
        final ACLMessage stray = poll(timeoutMs);
        assertNull(stray, () -> getLocalName() + " received a message it should not have:"
                + " ontology=" + stray.getOntology() + " performative="
                + ACLMessage.getPerformative(stray.getPerformative())
                + " from=" + Messages.senderName(stray));
    }

    /**
     * The channels of the next {@code count} messages, sorted — for the one case where two agents
     * were started together and their opening pushes race.
     *
     * @param count how many messages are expected
     * @param timeoutMs bound per message
     * @return the channels' {@code event()} names, sorted
     * @throws InterruptedException if interrupted
     */
    List<String> expectChannels(int count, long timeoutMs) throws InterruptedException {
        final List<String> events = new ArrayList<String>(count);
        for (int i = 0; i < count; i++) {
            final int seen = i;
            final ACLMessage acl = poll(timeoutMs);
            assertNotNull(acl, () -> getLocalName() + " received only " + seen + " of " + count
                    + " expected messages within " + timeoutMs + " ms; got " + events);
            events.add(Messages.channelOf(acl).event());
        }
        java.util.Collections.sort(events);
        return events;
    }

}
