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

import java.util.Map;

import cz.vutbr.fit.ags.railway.domain.msg.RoadDirection;
import cz.vutbr.fit.ags.railway.domain.msg.StationInfo;
import cz.vutbr.fit.ags.railway.domain.util.UnorientedGraph;

/**
 * Everything {@link Gui} and {@link RailwayCanvas} need from the simulation, and nothing else —
 * the seam <b>#35</b> asked for when it decided to <em>port</em> the Swing view rather than go
 * headless-only.
 *
 * <h2>Why it exists</h2>
 * #35's decision reads: extract this interface, "have {@code Gui}/{@code RailwayCanvas} bind to
 * it instead of to {@code RailwayMainAgent} directly", then let the Cybele hub implement it with
 * {@code Cybele.setPace} and the JADE hub with {@code SimClock.setPace}, so that
 * <b>both reuse {@code Gui.java} and {@code RailwayCanvas.java} verbatim</b>. That reuse is the
 * evidence, not the convenience: if the 2008 view runs unchanged against a JADE hub, the
 * inventory's claim that the GUI coupling is three files wide is <em>demonstrated</em> rather
 * than asserted, and that is a data point #41's comparison log would otherwise have to take on
 * trust.
 *
 * <p>
 * Only the JADE hub implements it today. The Cybele {@code RailwayMainAgent} it was extracted
 * from was rewritten in place by #33, so there is no second implementation left on this branch
 * to bind — the "both hubs" half of #35's plan is discharged by the git history, where the
 * pre-#33 hub and today's view are one {@code git show} apart.
 *
 * <h2>{@code java.util.Observable} is gone, and this replaces it</h2>
 * The 2008 hub {@code extends java.util.Observable}, with {@link RailwayCanvas} and the hub's own
 * inner {@code TableModel} as {@code java.util.Observer}s. That API has been <b>deprecated since
 * Java 9</b> ("does not support a rich enough model", and its notification order and thread-safety
 * are unspecified where it matters), so #33 replaced all three sites with {@link Listener} below.
 *
 * <p>
 * <b>One behavioural detail of the class that replaced it, recorded rather than silently
 * changed.</b> {@code Observable.notifyObservers} walks a snapshot of the observer array
 * <em>backwards</em>, so the 2008 order was: the canvas first (registered second, by
 * {@code new Gui(this)}), then the table model (registered first, in the constructor). This
 * interface notifies in <b>registration order</b>, which reverses those two. It is not in the
 * contract by any route — the view is never an oracle (#35, "Out of contract"), goldens come from
 * the probe trace, and both listeners only repaint — but a reversal that nobody wrote down is the
 * kind of thing that gets rediscovered as a bug, so it is written down.
 *
 * @author Bedrich Hovorka
 */
public interface RailwayView {

    /**
     * A view that wants to be told when the simulation's published state has changed.
     * {@code java.util.Observer} with the two arguments nobody used removed: the 2008
     * {@code update(Observable, Object)} implementations ignored both parameters and called
     * {@code repaint()} / {@code fireTableDataChanged()}.
     */
    interface Listener {
        /** The published state changed; re-read the getters below. */
        void railwayChanged();
    }

    /**
     * Register a listener. Called once per view, at construction.
     *
     * @param listener the view
     */
    void addListener(Listener listener);

    /** @return the railway topology, for drawing and for {@code get(a, b)} lookups */
    UnorientedGraph<String, String> getNet();

    /**
     * @return the latest {@code STATION_INFO} per station. Since #27 these are immutable
     *         {@link StationInfo} snapshots rather than the live {@code Station.Info} aliases
     *         DEF-13 describes, so the canvas now shows a value that was true when it was sent
     *         instead of one that may have moved since. #35 records that as a known
     *         non-observable difference: GUI freshness only.
     */
    Map<String, StationInfo> getStationInfos();

    /** @return the latest {@code ROAD_STATE} per track */
    Map<String, RoadDirection> getRoadAgentStates();

    /** @return the table model behind the train list; registered whether or not a GUI is built */
    javax.swing.table.TableModel getTrainTableModel();

    /** @return travel time per track, in seconds */
    Map<String, Long> getRoadDelays();

    /** @return capacity per station */
    Map<String, Integer> getStationCapacities();

    /**
     * @return the simulated clock, in milliseconds — {@code Cybele.getTime(CLOCK_ID)} in 2008,
     *         {@code SimClock.nowMs()} since #29
     */
    long getSimTimeMs();

    /**
     * Change the pace of simulated time. The toolbar's three buttons are this method's only
     * callers, and it is the only write the view makes into the simulation
     * ({@code Gui.java:98} in 2008, {@code Cybele.setPace}).
     *
     * @param pace simulated ms per real ms
     */
    void setPace(double pace);
}
