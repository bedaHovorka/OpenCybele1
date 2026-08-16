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

import java.io.Serializable;

import cybele.kernel.Activity;
import cybele.kernel.CybeleEvent;
import cybele.kernel.Handler;
import cz.vutbr.fit.ags.xhovor07.util.UnorientedGraph;

/**
 * This is one activity of main agent.
 * Each LAMBDA seconds with exponencial distribution of probability generate new train
 * 
 * @author Bedrich Hovorka
 */
public class VoteCollecting implements Handler {
    private static final long serialVersionUID = 1L;
    private Planning scrutator;

    /**
     * 
     * @param scrutator
     */
    public VoteCollecting(Planning scrutator) {
	this.scrutator = scrutator;
	Activity.openChannel(Planning.VOTE, "vote", this);
    }
    
    /**
     * incoming vote processing
     * @param ev
     */
    public void vote(CybeleEvent ev) {
	// sender, train, voteInt
	final Serializable[] message = ev.getMessage();
	final String voter = (String) message[0];
	final String train = (String) message[1];
	final Long diff = (Long) message[2];
	
	final UnorientedGraph<String, Long> votes = scrutator.getVotes();
	synchronized (votes) {
	    votes.put(voter, train, diff);
	}
	assert scrutator.getTrainCountDowns().containsKey(train);
	scrutator.getTrainCountDowns().get(train).countDown();
    }
}
