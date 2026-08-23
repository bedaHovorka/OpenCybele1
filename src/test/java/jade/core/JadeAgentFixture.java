/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package jade.core;

/**
 * Gives an {@link Agent} a local name outside a running container, so a ported agent can be
 * driven as the POJO {@code docs/TESTING.md} §4.1 says it is.
 * <p>
 * {@code Agent.getLocalName()} is {@code final} and reads {@code myAID}, which only
 * {@code AgentContainerImpl} sets when it starts the agent. Without a name the agent's own
 * {@code Messages.build(msg, name(), receiver)} cannot address anything, so the interesting half
 * of a station — who it writes into trace fields 4 and 5 — would have to be stubbed out rather
 * than tested. {@code Agent.setAID} is package-private, hence this fixture lives in
 * {@code jade.core}; that is the same reason, and the same trick, as {@link JadePlatformFixture}
 * next to it.
 * <p>
 * <b>What this fixture does not give you.</b> No container, no message-transport service and no
 * agent thread. {@code Agent.send} is a silent no-op here (it has no toolkit to hand the message
 * to), which is precisely why a testable agent needs its own outbound seam;
 * {@code Agent.postMessage} and {@code Agent.receive(template)} <em>do</em> work, because the
 * message queue is a plain field. So the shape a test can drive is: post a message, run a
 * behaviour's {@code action()}, assert on what the agent tried to send.
 */
public final class JadeAgentFixture {

    private JadeAgentFixture() {
    }

    /**
     * Name an agent, installing a platform id first if none is set.
     *
     * @param agent the agent
     * @param localName the local name it should answer to
     */
    public static void name(Agent agent, String localName) {
        JadePlatformFixture.install("opencybele-test");
        agent.setAID(new AID(localName, AID.ISLOCALNAME));
    }
}
