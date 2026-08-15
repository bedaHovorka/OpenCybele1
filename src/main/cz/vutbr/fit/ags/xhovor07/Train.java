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
import cybele.kernel.Agent;
import cybele.kernel.CybeleEvent;

/**
 * Agent represeting train
 * @author Bedrich Hovorka
 *
 */
public class Train extends RailwayObject {
    private static final long serialVersionUID = 1L;
    /**
     * State of train
     */
    public static final String KILLED = "KILL";
    /**
     * channel for start command
     */
    public static final String START = "START.";
    /**
     * channel for notifiaction
     */
    public static final String ENTER_REPLY = "ENTER_REPLY.";
    /**
     * channel for notifiaction
     */
    public static final String TRAVEL_END = "TRAVEL_END.";
    private final String to;
    private final String from;
    private String position;
    private String nextPosition;

    /**
     * Creates new Train agent
     * @param from start station
     * @param to target station
     */
    public Train(String from, String to) {
	this.from = from;
	this.to = to;
	Activity.openChannel(START+getName(), "start", this);
	Activity.openChannel(ENTER_REPLY+getName(), "entered", this);
	Activity.openChannel(TRAVEL_END+getName(), "travelEnd", this);
	sendStatusMessage(getName() + " generated");
    }

    private void sendStatusMessage(String mess) {
	String message = (mess==KILLED) ? KILLED : from + " -> " + to + " : " + mess; 
	Activity.sendAll(RailwayMainAgent.CHANNEL_TRAIN_STATE+getName(), new Serializable[]{message});
    }
    
    /**
     * start command
     * @param ev
     */
    public synchronized void start(CybeleEvent ev) {
	System.out.println(getName() + " started");
	final Serializable[] message = ev.getMessage();
	String station = (String) message[0];
	assert station.equals(from);
	requestEnterToObject(station);
    }
    
    /**
     * entry to object accepted notification
     * @param ev
     */
    public synchronized void entered(CybeleEvent ev) {
	leaveObject(position);
	final Serializable[] message = ev.getMessage();
	position = (String) message[0];
	sendStatusMessage("entered to "+ position);
	nextPosition = (String) message[1];
	if (position.startsWith("st")) {
	    if (nextPosition != null) {
		requestEnterToObject(nextPosition);
	    } else {
		assert position.equals(to);
		Agent.die();
	    }
	} else {
	    assert position.startsWith("tr");
	    Activity.sendAll(RoadAgent.TRAVEL_START+position, new Serializable[]{getName()});
	}
    }
    
    /**
     * travel end on road notification
     * @param ev
     */
    public synchronized void travelEnd(CybeleEvent ev) {
	if (nextPosition != null) {
	    requestEnterToObject(nextPosition);
	}
    }
    
    private void leaveObject(String object) {
	if (position != null) {
	    Activity.sendAll(StaticRailwayObject.LEAVE+object, new Serializable[]{getName()});
	}
    }
    
    private void requestEnterToObject(String object) {
	Activity.sendAll(StaticRailwayObject.ENTER+object, new Serializable[]{getName(), position, to});
    }
    
    /**
     * cybele destructor
     */
    public synchronized void destroy() {
	leaveObject(position);
	sendStatusMessage(KILLED);
    }
}
