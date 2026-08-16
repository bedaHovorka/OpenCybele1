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

/**
 * Reprezents static railway object, which can vote
 * 
 * @author Bedrich Hovorka
 *
 */
public abstract class StaticRailwayObject extends RailwayObject {
    /**
     * Channel for vote request
     */
    public static final String VOTE_REQUEST = "VOTE_REQUEST.";
    /**
     * Channel for vote result
     */
    public static final String VOTE_RESULT = "VOTE_RESULT.";
    // klasicke SHO operace
    
    /**
     * Channel for enter request from train
     */
    public static final String ENTER = "ENTER.";
    /**
     * Channel for leave notification from train
     */
    public static final String LEAVE = "LEAVE.";

    /**
     * 
     */
    public StaticRailwayObject() {
	Activity.openChannel(VOTE_REQUEST+getName(), "voteRequest", this);
	Activity.openChannel(VOTE_RESULT+getName(), "voteResult", this);
	Activity.openChannel(ENTER+getName(), "enter", this);
	Activity.openChannel(LEAVE+getName(), "leave", this);
    }
    
    /**
     * Process incoming vote request
     * @param ev
     */
    @SuppressWarnings("boxing")
    public void voteRequest(CybeleEvent ev) {
	final Serializable[] message = ev.getMessage();
	final String train = (String) message[0];
	final Long time = (Long) message[1];
	final long diff = computeDifference(train, time);
	Activity.sendAll(Planning.VOTE, new Serializable[]{getName(), train, diff});
    }
    
    /**
     * Process incoming vote result
     * @param ev
     */
    @SuppressWarnings("boxing")
    public void voteResult(CybeleEvent ev) {
	final Serializable[] message = ev.getMessage();
	final String train = (String) message[0];
	final Long time = (Long) message[1];
	addToPlan(train, time);
    }
    
    /**
     * Enter reply - accepting train 
     * @param train
     * @param nextPosition
     */
    protected void sendEnterReply(String train, String nextPosition) {
	Activity.sendAll(Train.ENTER_REPLY+train, new Serializable[]{getName(), nextPosition});
    }
    /**
     * add train to plan
     * @param train
     * @param time
     */
    protected abstract void addToPlan(String train, long time);
    /**
     * find free in plan and
     * compute difference between expected time and founded free in plan
     * @param train
     * @param time
     * @return time difference
     */
    protected abstract long computeDifference(String train, long time);
    /**
     * Queueing system operation leave
     * @param ev
     */
    public abstract void leave(CybeleEvent ev);
    /**
     * Queueing system operation enter
     * @param ev
     */
    public abstract void enter(CybeleEvent ev);
}
