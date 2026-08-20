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
import java.util.Random;

import cybele.kernel.Activity;
import cybele.kernel.Cybele;
import cybele.kernel.CybeleEvent;
import cz.vutbr.fit.ags.railway.domain.RoadQueue;
import cz.vutbr.fit.ags.railway.domain.RoadQueueItem;
import cz.vutbr.fit.ags.railway.domain.RoadSchedule;
import cz.vutbr.fit.ags.railway.domain.TravelDelay;


/**
 * Agent representing one single-track segment.
 * <p>
 * Since #28 the timetable and voting rule live in {@link RoadSchedule}, the waiting queue
 * and its ordering in {@link RoadQueue}, and the travel-time expression in
 * {@link TravelDelay} — all framework-free. What is left here is the Cybele glue: the
 * channels, the timer, the direction state the GUI observes, and the clock pause/resume
 * that brackets every queue operation.
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
    private final RoadSchedule schedule;
    private final RoadQueue queue;
    private State state;
    private String traveledTrain;
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
	this.schedule = new RoadSchedule(delay*1000);
	this.queue = new RoadQueue(schedule);
	this.leftStation = leftStation;
	this.rightStation = rightStation;
	this.state = State.FREE;
	// Ties this agent's stream key to the configured track names, which is what Main's
	// stderr stream table is computed from: a road agent named anything else would make
	// that table a fiction.
	assert ScenarioConfig.get().getRoadNames().contains(getName())
		: "road agent name '" + getName() + "' is not a configured track";
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
	// Adding or reordering a draw on this stream re-aligns every later value of it.
	// The draw stays here; what is done with it is TravelDelay.travelMs (#28), which
	// carries the DEF-16 note on why the result is deliberately not clamped at 0.
	final long delay2 = TravelDelay.travelMs(delayInSeconds(), random.nextGaussian());
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

    /**
     * The clock stays paused across the whole heap operation, exactly as before #28. That
     * is also what makes the single {@code getTime} read below equivalent to the per-
     * comparison read {@code OueueItem.compareTo} used to do: the clock cannot advance
     * between them. See {@link RoadQueue} for why the value cancels out anyway.
     */
    private void push(final String train, final String trainPosition) {
	Cybele.pauseClock(RailwayMainAgent.CLOCK_ID);
	queue.offer(train, trainPosition, Cybele.getTime(RailwayMainAgent.CLOCK_ID));
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
	    final RoadQueueItem poll = pop();
	    acceptTrain(poll.getTrain(), poll.getPosition());
	}
	schedule.removeTrain(train);
	sendState();
    }

    private RoadQueueItem pop() {
	Cybele.pauseClock(RailwayMainAgent.CLOCK_ID);
	final RoadQueueItem poll = queue.poll(Cybele.getTime(RailwayMainAgent.CLOCK_ID));
	Cybele.resumeClock(RailwayMainAgent.CLOCK_ID);
	return poll;
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
