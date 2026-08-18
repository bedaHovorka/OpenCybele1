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
import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Random;
import java.util.SortedMap;
import java.util.TreeMap;

import cybele.kernel.Activity;
import cybele.kernel.Cybele;
import cybele.kernel.CybeleEvent;


/**
 * 
 * @author Bedrich Hovorka
 *
 */
public class RoadAgent extends StaticRailwayObject {
    private static final long serialVersionUID = 1L;
    /**
     * channel for notification
     */
    public static final String TRAVEL_START = "TRAVEL_START.";
    private final String rightStation;
    private final String leftStation;
    private final long delay;
    private PriorityQueue<OueueItem> queue = new PriorityQueue<OueueItem>();
    private State state;
    private String traveledTrain;
    private Map<String, Long> invertedTimetable = new HashMap<String, Long>();
    private final SortedMap<Long, String> timetable = new TreeMap<Long, String>();
    /**
     * This road's own stream, keyed by its own name — every road agent draws its travel
     * jitter from a different sequence, so a road's draws no longer depend on how many
     * trains happened to be crossing the other six roads first. See {@link SimRandom}.
     */
    private final Random random;

    /**
     * 
     */
    public enum State {
	/**
	 * road is empty
	 */
	FREE(""),
	/**
	 * train travel from right station to left
	 */
	TRAVEL_LEFT("<"),
	/**
	 * train travel from left station to right
	 */
	TRAVEL_RIGHT(">");
	
	private String symbol;

	private State(String symbol) {
	    this.symbol = symbol;
	}

	/** 
	 * @return symbol of direction
	 */
	public String getSymbol() {	    
	    return symbol;
	}
    }
    
    /**
     * Constructs new road agent
     * @param delay of train travel
     * @param leftStation agent id of left neighbour
     * @param rightStation agent id of right neighbour
     */
    public RoadAgent(long delay, String leftStation, String rightStation) {
	this.delay = delay;
	this.leftStation = leftStation;
	this.rightStation = rightStation;
	this.state = State.FREE;
	this.random = SimRandom.forAgent(getName());
	Activity.openChannel(TRAVEL_START+getName(), "travelStart", this);
	sendState();
    }

    private void sendState() {
	Activity.sendAll(RailwayMainAgent.CHANNEL_ROAD_STATE+getName(), new Serializable[]{state});
    }
    
    /**
     * notification from train
     * @param ev
     */
    public synchronized void travelStart(CybeleEvent ev) {
	final Serializable[] message = ev.getMessage();
	traveledTrain = (String) message[0];
	final long delay2 = delayInSeconds() + (long)(500*random.nextGaussian());
	Activity.setTimer(RailwayMainAgent.CLOCK_ID, delay2, this, "travelEnd");
    }

    private final long delayInSeconds() {
	return delay*1000;
    }
    
    /**
     * timer schedule
     * @param ev
     */
    public synchronized void travelEnd(CybeleEvent ev) {
	assert traveledTrain != null;
	Activity.sendAll(Train.TRAVEL_END+traveledTrain, new Serializable[]{getName()});
    }
    @Override
    public synchronized void enter(CybeleEvent ev) {
	final Serializable[] message = ev.getMessage();
	final String train = (String) message[0];
	final String trainPosition = (String) message[1];
	
	if (state == State.FREE) {
	    acceptTrain(train, trainPosition);
	} else {
	    push(train, trainPosition);
	}
	sendState();
    }

    private void push(final String train, final String trainPosition) {
	Cybele.pauseClock(RailwayMainAgent.CLOCK_ID);
	queue.offer(new OueueItem(train, trainPosition));
	Cybele.resumeClock(RailwayMainAgent.CLOCK_ID);
    }
    
    private void acceptTrain(String train, String position) {
	// pokud je to leva stanice jede se doprava
	if (position.equals(leftStation)) {
	    state = State.TRAVEL_RIGHT;
	    sendEnterReply(train, rightStation);
	} else {
	    assert position.equals(rightStation);
	    state = State.TRAVEL_LEFT;
	    sendEnterReply(train, leftStation);
	}
    }

    @Override
    public synchronized void leave(CybeleEvent ev) {
	assert state != State.FREE;
	final Serializable[] message = ev.getMessage();
	final String train = (String) message[0];
	
	if (queue.size() == 0) {
	    state = State.FREE;
	} else  {
	    final OueueItem poll = pop();
	    acceptTrain(poll.train, poll.position);
	}
	timetable.values().remove(train);
	invertedTimetable.remove(train);
	sendState();
    }

    private OueueItem pop() {
	Cybele.pauseClock(RailwayMainAgent.CLOCK_ID);
	final OueueItem poll = queue.poll();
	Cybele.resumeClock(RailwayMainAgent.CLOCK_ID);
	return poll;
    }
    
    @SuppressWarnings("boxing")
    @Override
    protected synchronized long computeDifference(String train, long time) {
	final int plannedTrains = timetable.subMap(time-delayInSeconds(), time+delayInSeconds()).size();
        if (plannedTrains > 0) {
            return timetable.lastKey()+delayInSeconds()-time;
        }
 	return 0;
    }
    
    @SuppressWarnings("boxing")
    @Override
    protected synchronized void addToPlan(String train, long time) {
	timetable.put(time, train);
        invertedTimetable.put(train, time);
    }
    
    class OueueItem implements Comparable<OueueItem>, Serializable {
	private static final long serialVersionUID = 1L;
	String train;
	String position;
	
	private OueueItem(String train, String position) {
	    super();
	    this.train = train;
	    this.position = position;
	}
	
	//rozdil od planu (jizdniho radu)
	@SuppressWarnings("boxing")
	private long diff(long time) {
	    return invertedTimetable.get(train) - time;
	}
 
	@Override
	public int compareTo(OueueItem o) {
	    if (o == null) return -1;
	    final long time = Cybele.getTime(RailwayMainAgent.CLOCK_ID);
	    int d = (int) (diff(time)-o.diff(time));//napred podle planu
	    if (d != 0) return d;
	    return frequency(queue, o.position) - frequency(queue, position);//potom podle poctu pozadavku z daneho smeru
	}
	
	
    }
    
    static int frequency(PriorityQueue<OueueItem> q, String position) {
	int f = 0;
	for (OueueItem i : q) {
	    if (i != null && i.equals(position)) f++;
	}
	return f;
    }
}
