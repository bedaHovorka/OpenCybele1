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

import java.awt.BasicStroke;
import java.awt.Color;
import java.awt.Dimension;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.Rectangle;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionListener;
import java.awt.geom.AffineTransform;
import java.util.List;
import java.util.Observable;
import java.util.Observer;
import java.util.Set;

import javax.swing.JComponent;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

import cybele.kernel.Cybele;
import cz.vutbr.fit.ags.xhovor07.RoadAgent.State;
import cz.vutbr.fit.ags.xhovor07.Station.Info;

/**
 * This is a main GUI component in a program. It show railway elements in a canvas.
 * <p>
 * <b>The canvas is a second, independent statement of the topology.</b> It draws one
 * straight main line plus branch stubs, at hand-computed coordinates; it cannot draw an
 * arbitrary graph. The station order and the branches are configurable
 * ({@code sim.gui.mainLine}, {@code sim.gui.branches}), but a configured
 * {@code sim.topology} that this layout cannot represent is <em>not</em> an error -
 * the GUI is outside the behavioural contract, goldens come from the trace rather than
 * from the screen. What is not acceptable is drawing a network that is not the one
 * being simulated, so every mismatch {@link ScenarioConfig#getGuiLayoutWarnings()}
 * finds is printed at startup and painted here in red, and a main-line pair with no
 * track between it is drawn as a red {@code ??} gap instead of quietly omitted.
 * See {@code docs/scenario-config.md}.
 */
public class RailwayCanvas extends JComponent implements Scrollable, MouseMotionListener, Observer {
    private static final long serialVersionUID = 1L;
    private static final int maxUnitIncrement = 35;
    private final String[] mainRoads;
    private final List<ScenarioConfig.Branch> branches;
    private final List<String> layoutWarnings;
    private final Set<String> knownStations;
    private final Set<String> knownRoads;
    private final int leftSpace = 50;
    private final int roadWidth = 75;
    private final int stationWidth = 45;
    private final BasicStroke basicStroke = new BasicStroke(2.5f);
    private final RailwayMainAgent mainAgent;
	
    /**
     * Create object for painting railway Grid on screen
     * @param mainAgent 
     */
    public RailwayCanvas(RailwayMainAgent mainAgent) {
	final ScenarioConfig config = ScenarioConfig.get();
	final List<String> mainLine = config.getGuiMainLine();
	this.mainRoads = mainLine.toArray(new String[mainLine.size()]);
	this.branches = config.getGuiBranches();
	this.layoutWarnings = config.getGuiLayoutWarnings();
	this.knownStations = config.getStationNames();
	this.knownRoads = config.getRoadNames();
	this.mainAgent = mainAgent;
	mainAgent.addObserver(this);
	setPreferredSize(new Dimension(780, 380));
	setBackground(Color.BLACK);
	setAutoscrolls(true);
	addMouseMotionListener(this);
    }
	
    @Override
    public final void paint(Graphics g) {
	assert (g instanceof Graphics2D);
	paint((Graphics2D) g);
    }
    
    private void paintStation(String name, Graphics2D g) {
	g.drawArc(0, 0, stationWidth, stationWidth, 0, 360);
	final Info info = mainAgent.getStationInfos().get(name);
	final String infoStr = (info != null) ? info.occupied+"/"+info.capacity :"N/A";
	g.drawString(infoStr, stationWidth/2-10, stationWidth/2+5);
	g.drawString(name, stationWidth/2-7, stationWidth+13);
    }
    
    private void paintRoad(String name, Graphics2D g) {
	Color color = g.getColor();
	State state = mainAgent.getRoadAgentStates().get(name);
	if (state == null) {
	    g.setColor(Color.BLUE);
	} else if (state != State.FREE) {
	    g.setColor(Color.RED);
	    g.drawString(state.getSymbol(), roadWidth/2-5, stationWidth/2-3);
	}
	g.drawLine(0, stationWidth/2, roadWidth, stationWidth/2);
	g.setColor(color);
	g.drawString(name, roadWidth/2-7, stationWidth/2+20);
    }
	
    /**
     * @see java.awt.Component#paint(java.awt.Graphics)
     * @param g graphics context
     */
    public void paint(Graphics2D g) {
	final Color baseColor = g.getColor();
	g.drawString(Cybele.getTime(RailwayMainAgent.CLOCK_ID)/1000 + " sec", 20, 20);
	paintLayoutWarnings(baseColor, g);
	g.setStroke(basicStroke);
	final AffineTransform inicitialPos = g.getTransform();
	g.translate(leftSpace, 250);
	for (int i = 0; i < mainRoads.length; i++) {
	    paintStationOrGap(mainRoads[i], baseColor, g);
	    g.translate(stationWidth, 0);
	    if (i < mainRoads.length-1) {
		// No `assert road != null` any more: with a configurable topology the
		// existence of a track between two canvas-adjacent stations is a property
		// of the configuration, not an invariant of the program. Draw the gap.
		final String road = mainAgent.getNet().get(mainRoads[i], mainRoads[i+1]);
		if (road == null) {
		    paintMissingRoad(null, baseColor, g);
		} else {
		    paintRoad(road, g);
		}
	    }
	    g.translate(roadWidth, 0);
	}
	
	// Branch stubs, at the same hand-computed coordinates as before: the n-th branch
	// (1-based) sits at x = leftSpace + n*(roadWidth+stationWidth), y = 50 + (n-1)*100.
	for (int n = 1; n <= branches.size(); n++) {
	    final ScenarioConfig.Branch branch = branches.get(n-1);
	    paintSecondRoad(leftSpace+n*(roadWidth+stationWidth), 50+(n-1)*100,
		    branch.getStation(), branch.getRoad(), inicitialPos, baseColor, g);
	}
    }
    
    private void paintLayoutWarnings(Color baseColor, Graphics2D g) {
	if (layoutWarnings.isEmpty()) return;
	g.setColor(Color.RED);
	int y = 40;
	g.drawString("GUI TOPOLOGY MISMATCH - this drawing is incomplete:", 20, y);
	for (String warning : layoutWarnings) {
	    y += 15;
	    g.drawString("  * " + warning, 20, y);
	}
	g.setColor(baseColor);
    }
    
    private void paintMissingRoad(String name, Color baseColor, Graphics2D g) {
	g.setColor(Color.RED);
	g.drawString("??", roadWidth/2-7, stationWidth/2-3);
	g.drawLine(0, stationWidth/2-6, roadWidth, stationWidth/2+6);
	g.drawLine(0, stationWidth/2+6, roadWidth, stationWidth/2-6);
	if (name != null) g.drawString(name, roadWidth/2-7, stationWidth/2+20);
	g.setColor(baseColor);
    }
    
    /**
     * A station the layout names but {@code sim.topology} does not declare would otherwise
     * paint as an ordinary circle reading "N/A" — indistinguishable from a real station whose
     * first state message has not arrived yet. Cross it out in red instead.
     */
    private void paintStationOrGap(String name, Color baseColor, Graphics2D g) {
	if (knownStations.contains(name)) {
	    paintStation(name, g);
	    return;
	}
	g.setColor(Color.RED);
	g.drawArc(0, 0, stationWidth, stationWidth, 0, 360);
	g.drawLine(0, 0, stationWidth, stationWidth);
	g.drawLine(0, stationWidth, stationWidth, 0);
	g.drawString(name, stationWidth/2-7, stationWidth+13);
	g.setColor(baseColor);
    }
    
    private void paintSecondRoad(int x, int y, String stationName, String roadName, AffineTransform inicitialPos, Color baseColor, Graphics2D g) {
	g.setTransform(inicitialPos);
	g.translate(x, y);
	paintStationOrGap(stationName, baseColor, g);
	g.rotate(Math.PI/4, 0, 100);
	if (knownRoads.contains(roadName)) {
	    paintRoad(roadName, g);
	} else {
	    paintMissingRoad(roadName, baseColor, g);
	}
    }

//    private void cancelClip(Graphics2D g) {
//	g.setClip(getVisibleRect());
//    }
	
//nasledujicich 8 metod osetruje spravne rolovani zobrazovani scrollbaru
    public Dimension getPreferredScrollableViewportSize() {
	return getPreferredSize();
    }
    public int getScrollableBlockIncrement(Rectangle visibleRect, int orientation, int direction) {
	if (orientation == SwingConstants.HORIZONTAL) return visibleRect.width - maxUnitIncrement;
	return visibleRect.height - maxUnitIncrement;
    }

    public boolean getScrollableTracksViewportHeight() {
	return false;
    }

    public boolean getScrollableTracksViewportWidth() {
	return false;
    }

    public int getScrollableUnitIncrement(Rectangle visibleRect, int orientation, int direction) {
	int currentPosition = 0;
	if (orientation == SwingConstants.HORIZONTAL) {
	    currentPosition = visibleRect.x;
	} else {
	    currentPosition = visibleRect.y;
	}
	
	if (direction < 0) {
	    int newPosition = currentPosition -
	    (currentPosition / maxUnitIncrement)* maxUnitIncrement;
	        return (newPosition == 0) ? maxUnitIncrement : newPosition;
	} 
	return ((currentPosition / maxUnitIncrement) + 1) * maxUnitIncrement- currentPosition;
    }

    public void mouseDragged(MouseEvent ev) {
	mouseMoveScroll(ev);
    }

    public void mouseMoved(MouseEvent ev) {
	// EMPTY
    }
    private void mouseMoveScroll(MouseEvent ev) {
	Rectangle r = new Rectangle(ev.getX(), ev.getY(), 1, 1);
        scrollRectToVisible(r);
    }

    public void update(Observable o, Object arg) {
	repaint(100);
    }
}
