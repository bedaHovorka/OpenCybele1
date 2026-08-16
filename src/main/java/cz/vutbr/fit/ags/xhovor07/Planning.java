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
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.PriorityBlockingQueue;

import cybele.kernel.Activity;
import cybele.kernel.Agent;
import cybele.kernel.Cybele;
import cybele.kernel.CybeleEvent;
import cybele.kernel.Handler;
import cz.vutbr.fit.ags.xhovor07.util.HashMapGraph;
import cz.vutbr.fit.ags.xhovor07.util.UnorientedGraph;
import cz.vutbr.fit.ags.xhovor07.util.Util;

/**
 * This is one activity of main agent.
 * For each input train iniciates election to determine time of
 * 
 * @author Bedrich Hovorka
 */
public class Planning implements Handler {
    /**
     * channel id for generated trains
     */
    public static final String PLAN_TRAIN = "PLAN_TRAIN";
    /**
     * channel id for incomming votes
     */
    public static final String VOTE = "VOTE";
    private static final long serialVersionUID = 1L;
    private RailwayMainAgent mainAgent;
    private PriorityBlockingQueue<TrainPlan> queue = new PriorityBlockingQueue<TrainPlan>();
    private UnorientedGraph<String, Long> votes = new HashMapGraph<String, Long>();//staticky objekt, vlak -> hlas
    private Map<String, CountDownLatch> trainCountDowns = Collections.synchronizedMap(new HashMap<String, CountDownLatch>());
    
    /**
     * 
     * @param mainAgent
     */
    public Planning(RailwayMainAgent mainAgent) {
	super();
	this.mainAgent = mainAgent;
	Activity.openChannel(PLAN_TRAIN, "planTrain", this);
	Agent.createActivity("collecting", VoteCollecting.class.getName(), new Serializable[]{this});
    }

    /**
     * election
     * @param ev
     * @throws InterruptedException
     */
    @SuppressWarnings("boxing")
    public void planTrain(CybeleEvent ev) throws InterruptedException {
	final Serializable[] message = ev.getMessage();
	final String train = (String) message[0];
	final String from = (String) message[1];
	final String to = (String) message[2];
	final List<Object> path = Util.path(mainAgent.getNet(), from, to);
	final CountDownLatch latch = new CountDownLatch(path.size());
	
	trainCountDowns.put(train, latch);
	//inicializovat hlasovani - odhad casu
	final long requestTime = Cybele.getTime(RailwayMainAgent.CLOCK_ID);
	long disp = requestTime;
	for (Object o : path) {//vsem na ceste
	    Activity.sendAll(StaticRailwayObject.VOTE_REQUEST+o.toString(), new Serializable[]{train, disp});
	    final Long delay = mainAgent.getRoadDelays().get(o);
	    if (delay != null) {
		disp += 1000*delay.doubleValue();
	    }
	}
	
	latch.await();
	trainCountDowns.remove(train);
	final List<Long> v = new ArrayList<Long>();
	synchronized (votes) {
	    v.addAll(votes.removeAll(train));
	}
	    // obalkova metoda
	synchronized (this) {
	    assert v.size() == path.size();
	    final long timeDiff = Collections.max(v);
	
	    long plannedTime = requestTime+timeDiff;
	    for (Object o : path) {//vsem na ceste
		Activity.sendAll(StaticRailwayObject.VOTE_RESULT+o.toString(), new Serializable[]{train, plannedTime});
		final Long delay = mainAgent.getRoadDelays().get(o);
		if (delay != null) {
		    plannedTime += 1000*delay.doubleValue();
		}
	    }
	
	    Cybele.pauseClock(RailwayMainAgent.CLOCK_ID);
	    final long t = (requestTime+timeDiff)-Cybele.getTime(RailwayMainAgent.CLOCK_ID);
	    queue.put(new TrainPlan(train, from, requestTime+timeDiff));
	    Activity.setTimer(RailwayMainAgent.CLOCK_ID, t > 0 ? t : 0, this, "placeTrainIntoFirstStation");
	    Cybele.resumeClock(RailwayMainAgent.CLOCK_ID);
	}
    }
    
    /**
     * tell to train start command
     * @param ev
     */
    public synchronized void placeTrainIntoFirstStation(CybeleEvent ev) {
	final TrainPlan poll = queue.poll();
	final long clockTime = ev.getClockTime();
	final long departure = poll.departure;
	assert clockTime >= departure;
	System.out.println(poll);
	Activity.sendAll(Train.START+poll.train, new Serializable[]{poll.station});//BUG ne vzdy se doruci
    }
    
    /**
     * 
     *
     */
    class TrainPlan implements Comparable<TrainPlan>, Serializable {
	private static final long serialVersionUID = 1L;
	final String train;
	final String station;
	long departure;
	
	private TrainPlan(String train, String station, long departure) {
	    super();
	    this.departure = departure;
	    this.train = train;
	    this.station = station;
	}

	@Override
	public int compareTo(TrainPlan o) {
	    final int i = (int) (departure - o.departure);
	    if (i == 0) {
		return train.compareTo(o.train);
	    }
	    return i;
	}
	
	@Override
	public String toString() {
	    return train + " in " + station + " at " + departure;
	}
    }

    /**
     * getter
     * @return votes
     */
    public UnorientedGraph<String, Long> getVotes() {
        return votes;
    }

    /**
     * getter
     * @return trainCountDowns
     */
    public Map<String, CountDownLatch> getTrainCountDowns() {
        return trainCountDowns;
    }
}
