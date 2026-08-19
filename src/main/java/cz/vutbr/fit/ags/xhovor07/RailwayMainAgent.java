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
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Observable;
import java.util.Observer;
import java.util.Set;
import java.util.Map.Entry;

import javax.swing.table.AbstractTableModel;

import cybele.kernel.Activity;
import cybele.kernel.Agent;
import cybele.kernel.Cybele;
import cybele.kernel.CybeleEvent;
import cybele.kernel.Handler;
import cz.vutbr.fit.ags.xhovor07.util.UnorientedGraph;
import cz.vutbr.fit.ags.xhovor07.util.Util;

/**
 * Managing social knowledge
 * @author Bedrich Hovorka
 *
 */
public class RailwayMainAgent extends Observable implements Handler {
    // spravce socialnich znalosti
    /**
     * global clock identification
     */
    public static final String CLOCK_ID = "myClock";
    /**
     * channel for station info
     */
    public static final String CHANNEL_STATION_INFO = "STATION.INFO.";
    /**
     * channel for road state
     */
    public static final String CHANNEL_ROAD_STATE = "ROAD.STATE.";
    /**
     * channel for path direction find
     */
    public static final String PATH_FIND = "PATH_FIND.";
    private transient TableModel trainTableModel = new TableModel();
    private static final long serialVersionUID = 1L;
    private final ScenarioConfig config = ScenarioConfig.get();
    private UnorientedGraph<String, String> net = config.buildNet();
    private Map<String, Station.Info> stationInfos = Collections.synchronizedMap(new HashMap<String, Station.Info>());
    private Map<String, RoadAgent.State> roadAgentStates = Collections.synchronizedMap(new HashMap<String, RoadAgent.State>());
    private Map<String, String> trainStates = Collections.synchronizedMap(new LinkedHashMap<String, String>());
    private Map<String, Long> roadDelays = new HashMap<String, Long>(config.getRoadDelaysSec());
    private Map<String, Integer> stationCapacities = new HashMap<String, Integer>(config.getStationCapacities());
    static final String CHANNEL_TRAIN_STATE = "TRAIN.STATE.";
    /**
     * identification
     */
    public static final String MAIN_AGENT_NAME = "Main";

    /**
     * Contruct main agent
     */
    @SuppressWarnings("boxing")
    public RailwayMainAgent() {
	addObserver(trainTableModel);
	// Topologie site, kapacity stanic a doby na tratich pochazeji ze ScenarioConfig
	// (sim.topology / sim.station.capacities / sim.road.delaysSec). Vychozi hodnoty
	// jsou totozne s puvodnimi literaly - viz docs/scenario-config.md.
	
	// vytvoreni gui
	// sim.headless=true preskoci konstrukci okna. Pozorovatele se nemeni: tento agent
	// je porad Observable a trainTableModel je porad zaregistrovan vyse - v bezhlavem
	// rezimu se proste zadny pohled neprihlasi. Viz docs/headless-and-stop.md.
	if (!config.isHeadless()) {
	    final Gui gui = new Gui(this);
	    gui.setVisible(true);
	}
	
	Cybele.createClock(CLOCK_ID, Cybele.HOST, config.getClockStartMs(), config.getClockPace());
	// The GUI construction above used to be what kept this line clear of the kernel's
	// clock-registration race (INVENTORY SEM-02) - by accident, and only in GUI mode.
	// The barrier now lives in Main; this is the check that it worked for THIS clock,
	// because the registration is per-clock and its loss is silent and permanent.
	// It pauses the clock and waits for the pause to land, which is inert here: no
	// timer has been set on the clock and no other agent exists yet. The resumeClock
	// below is the other half of the round trip and is unchanged.
	RunControl.verifyClockControl(CLOCK_ID, config.getClockStartMs());
	Cybele.resumeClock(CLOCK_ID);
	
	Activity.openChannel(PATH_FIND, "pathFind", this);
	// inicializace agentu
	for (String station : net.nodeSet()) {
	    assert stationCapacities.containsKey(station);
	    Collection<String> roads = net.get(station);
	    Activity.openChannel(CHANNEL_STATION_INFO+station, "recieveStationInfo", this);
	    Cybele.createAgent(station, Station.class.getName(), new Serializable[]{stationCapacities.get(station), (Serializable)roads});
	}
	
	for (String road : net.values()) {
	    assert roadDelays.containsKey(road);
	    Collection<String> allStationsWithRoad = net.allNodesWithEdge(road);
	    assert allStationsWithRoad.size() == 2;
	    // pozor na prohozeni satnic - zalezi na implemenatci allNodesWithEdge(road)
	    Activity.openChannel(CHANNEL_ROAD_STATE+road, "recieveRoadState", this);
	    final Serializable[] array = allStationsWithRoad.toArray(new Serializable[2]);
	    Cybele.createAgent(road, RoadAgent.class.getName(), new Serializable[]{roadDelays.get(road), array[0], array[1]});
	}
	
	Agent.createActivity("Planning", Planning.class.getName(), new Serializable[]{this});
	Agent.createActivity("Generator", Generator.class.getName(), new Serializable[]{this});
    }
    
    /**
     * path direction finding
     * @param ev
     */
    public void pathFind(CybeleEvent ev) {
	final Serializable[] message = ev.getMessage();
	assert message.length == 2;
	final String from = (String) message[0];
	final String to = (String) message[1];
	final String direction = Util.pathDirection(net, from, to);
	assert direction != null;
	Activity.sendAll(Station.PATH_FIND_REPLY+from, new Serializable[]{to, direction});
    }
    
    /**
     * process icoming station state 
     * @param ev
     */
    public void recieveStationInfo(CybeleEvent ev) {
	final String station = objectName(ev);
	final Serializable[] message = ev.getMessage();
	assert message.length == 1;
	stationInfos.put(station, (Station.Info) message[0]);
	fireChange();
    }
    /**
     * process icoming road state 
     * @param ev
     */
    public void recieveRoadState(CybeleEvent ev) {
	final String road = objectName(ev);
	final Serializable[] message = ev.getMessage();
	assert message.length == 1;
	roadAgentStates.put(road, (RoadAgent.State) message[0]);
	fireChange();
    }
    
    /**
     * process icoming train state 
     * @param ev
     */
    public void recieveTrainState(CybeleEvent ev) {
	final String train = objectName(ev);
	final Serializable[] message = ev.getMessage();
	assert message.length == 1;
	
	final String string = (String) message[0];
	if (string.equals(Train.KILLED)) {
	    trainStates.remove(train);
	} else {
	    trainStates.put(train, string);
	}
	fireChange();
    }

    private String objectName(CybeleEvent ev) {
	final String tag = ev.getTag();
	final int p = tag.lastIndexOf('.')+1;
	return tag.substring(p);
    }
    
    private void fireChange() {
	setChanged();
	notifyObservers();
    }

    /**
     * getter
     * @return train Table Model
     */
    public javax.swing.table.TableModel getTrainTableModel() {
        return trainTableModel;
    }

    /**
     * @return net
     */
    public UnorientedGraph<String, String> getNet() {
        return net;
    }

    /**
     * @return station Infos
     */
    public Map<String, Station.Info> getStationInfos() {
        return stationInfos;
    }

    /**
     * @return road Agent States
     */
    public Map<String, RoadAgent.State> getRoadAgentStates() {
        return roadAgentStates;
    }
    
    /**
     * 
     * @return name of agent
     */
    public String getName() {
	return MAIN_AGENT_NAME;
    }
    
    enum Column {
	/**
	 * 
	 */
	ID,
	/**
	 * 
	 */
	POSITION;
	
	static final Column[] values = values();
    }
    
    private class TableModel extends AbstractTableModel implements Observer {
	private static final long serialVersionUID = 1L;
	private List<Entry<String, String>> data = new ArrayList<Entry<String,String>>();
	
	@Override
	public String getColumnName(int column) {
	    return Column.values[column].name().toLowerCase();
	}
	
	@Override
	public int getColumnCount() {
	    return Column.values.length;
	}

	@Override
	public synchronized int getRowCount() {
	    return data.size();
	}

	@Override
	public synchronized Object getValueAt(int rowIndex, int columnIndex) {
	    Object result = null;
	    if (rowIndex < data.size()) {
		if (columnIndex == 0) {
		    result = data.get(rowIndex).getKey();
		} else if (columnIndex == 1) {
		    result = data.get(rowIndex).getValue();
		} else assert false;
	    }
	    return result;
	}

	@Override
	public synchronized void update(Observable o, Object arg) {
	    final Set<Entry<String, String>> entrySet = trainStates.entrySet();
	    data = new ArrayList<Entry<String, String>>(entrySet);//zacachuje se
//	    Collections.sort(data, new Comparator<Entry<String, String>>() {
//	        @Override
//	        public int compare(Entry<String, String> o1, Entry<String, String> o2) {
//	            return o1.getKey().compareTo(o2.getKey());
//	        }
//	    });
	    fireTableDataChanged();
	}
    }

    /**
     * getter
     * @return road Delays
     */
    public Map<String, Long> getRoadDelays() {
        return roadDelays;
    }

    /**
     * getter
     * @return station Capacities
     */
    public Map<String, Integer> getStationCapacities() {
        return stationCapacities;
    }
}
