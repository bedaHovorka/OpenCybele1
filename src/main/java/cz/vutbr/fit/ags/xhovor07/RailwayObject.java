/*
 * Projekt AGS 2007/08
 * FIT VUT Brno
 * 
 * Open Cybele 1
 * 
 * Bedrich Hovorka
 * xhovor07@stud.fit.vutbr.cz
 */
package cz.vutbr.fit.ags.xhovor07;

import cybele.kernel.Agent;
import cybele.kernel.Handler;

/**
 * <b>Vestigial, and kept deliberately by #33.</b>
 * <p>
 * This class carries one method — {@link #getName()}, which recovers an agent's own name from the
 * Cybele agent id. Every ported agent reifies its name instead ({@code getLocalName()}, which
 * INVENTORY DEF-12 calls the more correct form), so nothing in the JADE half reads it.
 * <p>
 * <b>Who still needs it, and when it goes.</b> {@code Train} extends it, and {@code Train} is
 * <b>#32</b>'s to port — so the class outlives #33 by exactly one ticket. #31's note that
 * "#32/#33 delete them" was half right: #33 deleted what #33's own agents freed
 * ({@code Station.Info}, {@code Station.PATH_FIND_REPLY}, {@code RoadAgent.State}) and could not
 * free this, because it has a namer #33 does not own. When #32 inlines {@code name}/{@code emit}
 * into {@code Train}, this file goes with it — and the unit worth extracting afterwards is
 * {@code name}/{@code emit}/{@code Inbox}/{@code Drain}, which belongs in the {@code jadeOntology}
 * source set next to {@code Templates}, not in a resurrected {@code RailwayObject} in the
 * application package.
 *
 * @author Bedrich Hovorka
 *
 */
public abstract class RailwayObject implements Handler {
    private static final long serialVersionUID = 1L;

    /**
     * get name of agent
     * @return agent name
     */
    protected String getName() {
	final String agentId = Agent.getAgentId();
	final int lastIndexOf = agentId.lastIndexOf('.')+1;
	return agentId.substring(lastIndexOf);
    }
}
