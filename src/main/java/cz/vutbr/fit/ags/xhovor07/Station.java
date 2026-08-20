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
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

import cybele.kernel.Activity;
import cybele.kernel.Agent;
import cybele.kernel.CybeleEvent;
import cz.vutbr.fit.ags.xhovor07.util.TreeMultiMap;

/**
 * Agent represents station
 * <p>
 * {@link #computeDifference(String, long)} schedules against a <em>voting window</em>
 * ({@code sim.station.voteWindowMs}). Before #18 that window was
 * {@code Generator.LAMBDA} — the very same constant as the generator's mean
 * inter-arrival time — so shortening the arrival rate for a test silently rewrote
 * this station's scheduling policy as well. The two are now independent parameters
 * with the same default (8500 ms). See {@code docs/scenario-config.md}.
 *
 * @author Bedrich Hovorka
 *
 */
public class Station extends StaticRailwayObject {
    private static final long serialVersionUID = 1L;
    /**
     * channel for sending path find result
     */
    public static final String PATH_FIND_REPLY = "PATH_FIND_REPLY.";
    private Collection<String> roads;//trate vychazejici ze stacice
    private Map<String, String> pathDirs = new HashMap<String, String>();//prubezne vytvarene znalosti o siti <stanice, jakou trati>
    private final Queue<QueueItem> queue = new LinkedList<QueueItem>();
    private final TreeMultiMap<Long, String> timetable = new TreeMultiMap<Long, String>();
    private final long voteWindow = ScenarioConfig.get().getStationVoteWindowMs();
    private Info info;

    /**
     * Information about state
     */
    public class Info implements Serializable {
	private static final long serialVersionUID = 1L;
	int capacity;
	int occupied;
	private Info(int occupied, int capacity) {
	    super();
	    this.capacity = capacity;
	    this.occupied = occupied;
	}
    }
    
    /**
     * @param capacity
     * @param roads
     */
    public Station(int capacity, Collection<String> roads) {
	this.roads = roads;
	info = new Info(0, capacity);
	Agent.createActivity("pathFindWaiting", PathFinding.class.getName(), new Object[]{this});
	sendInfo();
    }

    private void sendInfo() {
	Activity.sendAll(RailwayMainAgent.CHANNEL_STATION_INFO+getName(), new Serializable[]{info});
    }

    /**
     * getter
     * @return roads which is immediatly connected with station
     */
    public Collection<String> getRoads() {
        return roads;
    }

    /**
     * getter
     * @return path dir
     */
    public Map<String, String> getPathDirs() {
        return pathDirs;
    }

    @Override
    public synchronized void enter(CybeleEvent ev) {
	final Serializable[] message = ev.getMessage();
	final String train = (String) message[0];
	if (info.occupied == info.capacity) {
	    queue.offer(new QueueItem(train, (String) message[2]));
	} else {
	    info.occupied++;
	    sendEnterReply(train, getPathDirection((String) message[2]));
	}
	sendInfo();
    }

    private String getPathDirection(String target) {
	if (target.equals(getName())) return null;
	String dir = pathDirs.get(target);
	if (dir == null) {
	    try {
		Activity.sendAll(RailwayMainAgent.PATH_FIND, new Serializable[]{getName(), target});
		wait();
		dir = pathDirs.get(target);		
	    } catch (InterruptedException e) {
		assert false : e;
	    }
	}
	return dir;
    }

    @Override
    public synchronized void leave(CybeleEvent ev) {
	final Serializable[] message = ev.getMessage();
	final String train = (String) message[0];
	if (queue.size() == 0) {
	    info.occupied--;
	} else {
	    final QueueItem poll = queue.poll();
	    sendEnterReply(poll.train, getPathDirection(poll.endStation));
	}
	timetable.removeValue(train);
	sendInfo();
    }
    
    @SuppressWarnings("boxing")
    @Override
    protected synchronized long computeDifference(String train, long time) {
	final long timePlusLambda = time+voteWindow;
	final int plannedTrains = timetable.subMultiMap(time-voteWindow, timePlusLambda).values().size();
        if (plannedTrains > info.capacity-1) {//jedno misto pro rezervu
            final int size = timetable.tailSubMultiMap(timePlusLambda).values().size();
            return timetable.lastKey() + 
            	((timePlusLambda < timetable.lastKey() && size <= info.capacity-1) ? 
            		-voteWindow/3 : voteWindow/3) - time;
        }
 	return plannedTrains*voteWindow/6;
    }
    
    @SuppressWarnings("boxing")
    @Override
    protected synchronized void addToPlan(String train, long time) {
        timetable.put(time, train);
    }
    
    class QueueItem implements Serializable {
	private static final long serialVersionUID = 1L;
	String train;
	String endStation;
	
	private QueueItem(String train, String direction) {
	    super();
	    this.train = train;
	    this.endStation = direction;
	}
    }
}
