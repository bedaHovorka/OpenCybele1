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
 * Each LAMBDA seconds with exponencial distribution of probability generate new train
 * 
 * @author Bedrich Hovorka
 */
public class Generator implements Handler {
    /**
     * 
     */
    public static final int LAMBDA = 8500;
    private static final long serialVersionUID = 1L;
    
    // EXTENSION jak delat ruseni?
    private Map<String, String> openedChannels = Collections.synchronizedMap(new HashMap<String, String>());
    private Serializable[][] hhh = new Serializable[][]{{"stA", "stB"}, {"stA", "stC"}, 
	    {"stB", "stA"}, {"stB", "stC"}, {"stC", "stB"}, {"stC", "stA"}};
 
    private int index = 0;
    private static final Random random = new Random();
    private RailwayMainAgent mainAgent;
    
    /**
     * @param mainAgent
     */
    public Generator(RailwayMainAgent mainAgent) {
	this.mainAgent = mainAgent;
	Activity.setTimer(RailwayMainAgent.CLOCK_ID, 1000, this, "generateTrain");
    }
    
    /**
     * Method called by timer
     * @param ev
     */
    public void generateTrain(CybeleEvent ev) {
	final String train = "vl" + index;
	final String channelTicket = Activity.openChannel(RailwayMainAgent.CHANNEL_TRAIN_STATE+train, "recieveTrainState", mainAgent);
	final Serializable[] serializables = hhh[random.nextInt(hhh.length)];
	Cybele.createAgent(train, Train.class.getName(), serializables);
	openedChannels.put(train, channelTicket);
	index++;
	Activity.sendAll(Planning.PLAN_TRAIN, new Serializable[]{train, serializables[0], serializables[1]});
	Activity.setTimer(RailwayMainAgent.CLOCK_ID, exp(LAMBDA), this, "generateTrain");
    }
    
    private long exp(double lambda) {
        return Math.round(-lambda * Math.log(random.nextDouble()));
    }

    /**
     * get random
     * @return random object
     */
    public static Random getRandom() {
        return random;
    }
}