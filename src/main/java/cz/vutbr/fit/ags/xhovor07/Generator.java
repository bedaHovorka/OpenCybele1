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
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import cybele.kernel.Activity;
import cybele.kernel.Cybele;
import cybele.kernel.CybeleEvent;
import cybele.kernel.Handler;

/**
 * This is one activity of main agent.
 * Each {@code sim.arrival.lambdaMs} milliseconds with exponencial distribution of
 * probability generate new train.
 * <p>
 * This used to be {@code public static final int LAMBDA = 8500}, read both here
 * <em>and</em> by {@link Station#computeDifference(String, long)}. The two uses are now
 * separate parameters — {@code sim.arrival.lambdaMs} and {@code sim.station.voteWindowMs} —
 * so a scenario can shorten the arrival rate without moving the station scheduling
 * policy under it. Both default to 8500. See {@code docs/scenario-config.md}.
 * 
 * @author Bedrich Hovorka
 */
public class Generator implements Handler {
    private static final long serialVersionUID = 1L;
    
    // EXTENSION jak delat ruseni?
    private Map<String, String> openedChannels = Collections.synchronizedMap(new HashMap<String, String>());
    private final Serializable[][] hhh;
    private final long lambda;
 
    private int index = 0;
    /**
     * Stream for the origin/destination choice. It used to be a {@code static final Random}
     * shared with every {@link RoadAgent} and published through {@code getRandom()}; see
     * {@link SimRandom} for why that could not be made reproducible by seeding alone.
     */
    private final Random odRandom;
    /** Stream for the exponential inter-arrival time. Separate on purpose — see {@link SimRandom}. */
    private final Random interarrivalRandom;
    private RailwayMainAgent mainAgent;
    
    /**
     * @param mainAgent
     */
    public Generator(RailwayMainAgent mainAgent) {
	this.mainAgent = mainAgent;
	final ScenarioConfig config = ScenarioConfig.get();
	this.hhh = config.getTrainPairs();
	this.lambda = config.getArrivalLambdaMs();
	this.odRandom = SimRandom.forAgent(SimRandom.GENERATOR_OD_STREAM);
	this.interarrivalRandom = SimRandom.forAgent(SimRandom.GENERATOR_INTERARRIVAL_STREAM);
	Activity.setTimer(RailwayMainAgent.CLOCK_ID, config.getArrivalFirstFireMs(), this, "generateTrain");
    }
    
    /**
     * Method called by timer
     * @param ev
     */
    public void generateTrain(CybeleEvent ev) {
	final String train = "vl" + index;
	final String channelTicket = Activity.openChannel(RailwayMainAgent.CHANNEL_TRAIN_STATE+train, "recieveTrainState", mainAgent);
	// Adding or reordering a draw on this stream re-aligns every later value of it.
	final Serializable[] serializables = hhh[odRandom.nextInt(hhh.length)];
	Cybele.createAgent(train, Train.class.getName(), serializables);
	openedChannels.put(train, channelTicket);
	index++;
	Activity.sendAll(Planning.PLAN_TRAIN, new Serializable[]{train, serializables[0], serializables[1]});
	// sim.stop.maxTrains is enforced here rather than by the watchdog, so the bound is
	// exact: the run generates that many trains and not one more. With the key unset
	// this always returns true and the timer re-arms exactly as it always did.
	if (RunControl.trainGenerated(index)) {
	    Activity.setTimer(RailwayMainAgent.CLOCK_ID, exp(lambda), this, "generateTrain");
	}
    }
    
    private long exp(double mean) {
        // Adding or reordering a draw on this stream re-aligns every later value of it.
        return Math.round(-mean * Math.log(interarrivalRandom.nextDouble()));
    }
}