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
