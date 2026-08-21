/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

/**
 * The wire form of {@code RoadAgent.State}, pinned by #27.
 * <p>
 * A framework-free copy rather than a reference: {@code RoadAgent.State} is a nested enum of a
 * Cybele agent class, so the {@code domain} source set cannot see it and neither can branch
 * {@code jason}. The constant names are identical, which is what matters — the trace renders
 * {@code state=} plus the enum's {@code name()}, so {@code FREE}, {@code TRAVEL_LEFT} and
 * {@code TRAVEL_RIGHT} are the three values a golden can contain.
 * <p>
 * The symbols are the GUI's, carried so that a port's canvas can be the same canvas.
 */
public enum RoadDirection {
    /** road is empty */
    FREE(""),
    /** train travels from the right station to the left one */
    TRAVEL_LEFT("<"),
    /** train travels from the left station to the right one */
    TRAVEL_RIGHT(">");

    private final String symbol;

    RoadDirection(String symbol) {
        this.symbol = symbol;
    }

    /** @return the one-character symbol the GUI draws, empty for {@link #FREE} */
    public String getSymbol() {
        return symbol;
    }
}
