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

/**
 * This is one activity of station.
 * Solution of problem with {@link Activity#sendAllBlock(String, Serializable[], long, int)}
 * 
 * @author Bedrich Hovorka
 */
public class PathFinding implements Handler {
    private static final long serialVersionUID = 1L;
    private Station station;

    /**
     * 
     * @param station
     */
    public PathFinding(Station station) {
	super();
	this.station = station;
	Activity.openChannel(Station.PATH_FIND_REPLY+station.getName(), "pathFindReply", this);
    }
    
    /**
     * 
     * @param ev
     */
    public void pathFindReply(CybeleEvent ev) {
	final Serializable[] message = ev.getMessage();
	assert message.length == 2;
	synchronized (station) {
	    station.getPathDirs().put((String) message[0], (String) message[1]);
	    station.notify();
	}
    }
}
