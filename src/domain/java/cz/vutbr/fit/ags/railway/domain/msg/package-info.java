/**
 * The railway message ontology (#27): the fifteen Cybele channels as typed, immutable,
 * framework-free records, with the FIPA performative and trace projection each one carries.
 * <p>
 * Nothing in this package imports a framework. That is enforced by the {@code domainPurity}
 * Gradle task and it is the point: the JADE port (#30&ndash;#34, #36) binds these records to
 * {@code jade.lang.acl.ACLMessage} in the separate {@code jade} source set, and branch
 * {@code jason} (#46) binds the same records to AgentSpeak terms. Neither binding is visible
 * from here.
 * <p>
 * The mapping table, the performative justifications and the topic-granularity decision are in
 * {@code docs/message-ontology.md}.
 */
package cz.vutbr.fit.ags.railway.domain.msg;
