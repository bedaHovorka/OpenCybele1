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
import java.util.Map;

import cybele.kernel.Activity;
import cybele.kernel.Agent;
import cybele.kernel.CybeleEvent;
import cz.vutbr.fit.ags.railway.domain.StationQueue;
import cz.vutbr.fit.ags.railway.domain.StationSchedule;

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
 * <p>
 * Since #28 the timetable and the voting rule live in {@link StationSchedule} and the
 * waiting queue in {@link StationQueue}, both framework-free; this agent is the Cybele
 * glue around them — channels, the {@code Info} the GUI observes, and the lazy
 * {@code PATH_FIND} round trip.
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
    private final StationQueue queue = new StationQueue();
    private final long voteWindow = ScenarioConfig.get().getStationVoteWindowMs();
    private final StationSchedule schedule;
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
	this.schedule = new StationSchedule(capacity, voteWindow);
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
	    queue.offer(train, (String) message[2]);
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
	    final StationQueue.Waiting poll = queue.poll();
	    sendEnterReply(poll.getTrain(), getPathDirection(poll.getEndStation()));
	}
	schedule.removeTrain(train);
	sendInfo();
    }
    
    @Override
    protected synchronized long computeDifference(String train, long time) {
	return schedule.computeDifference(train, time);
    }
    
    @Override
    protected synchronized void addToPlan(String train, long time) {
	schedule.addToPlan(train, time);
    }
}
