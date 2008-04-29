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
import java.util.Observable;
import java.util.Observer;

import javax.swing.JComponent;
import javax.swing.Scrollable;
import javax.swing.SwingConstants;

import cybele.kernel.Cybele;
import cz.vutbr.fit.ags.xhovor07.RoadAgent.State;
import cz.vutbr.fit.ags.xhovor07.Station.Info;

/**
 * This is a main GUI component in a program. It show railway elements in a canvas.
 *
 */
public class RailwayCanvas extends JComponent implements Scrollable, MouseMotionListener, Observer {
    private static final long serialVersionUID = 1L;
    private static final int maxUnitIncrement = 35;
    private final String[] mainRoads = new String[]{"stA", "stH", "stG", "stE", "stD", "stB"};
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
	g.drawString(Cybele.getTime(RailwayMainAgent.CLOCK_ID)/1000 + " sec", 20, 20);
	g.setStroke(basicStroke);
	final AffineTransform inicitialPos = g.getTransform();
	g.translate(leftSpace, 250);
	for (int i = 0; i < mainRoads.length; i++) {
	    paintStation(mainRoads[i], g);
	    g.translate(stationWidth, 0);
	    if (i < mainRoads.length-1) {
		final String road = mainAgent.getNet().get(mainRoads[i], mainRoads[i+1]);
		assert road != null;
		paintRoad(road, g);
	    }
	    g.translate(roadWidth, 0);
	}
	
	paintSecondRoad(leftSpace+roadWidth+stationWidth, 50, "stC", "tr7", inicitialPos, g);
	paintSecondRoad(leftSpace+2*(roadWidth+stationWidth), 150, "stF", "tr6", inicitialPos, g);
    }
    
    private void paintSecondRoad(int x, int y, String stationName, String roadName, AffineTransform inicitialPos, Graphics2D g) {
	g.setTransform(inicitialPos);
	g.translate(x, y);
	paintStation(stationName, g);
	g.rotate(Math.PI/4, 0, 100);
	paintRoad(roadName, g);
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
