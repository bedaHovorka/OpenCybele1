/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

import java.util.Arrays;
import java.util.List;

/**
 * CH-12 {@code TRAIN.STATE.<train>} — the string the GUI table shows.
 * <p>
 * <strong>Opaque text, deliberately unparsed.</strong> Every value except one is
 * {@code from + " -> " + to + " : " + message}, built by {@code Train.sendStatusMessage} and
 * recorded verbatim; the exception is the sentinel {@code KILL}, sent from the Cybele
 * destructor. Modelling this as a structured record would be an improvement, and an
 * improvement is exactly what a golden-master port must not make: the string is in the trace
 * character for character.
 * <p>
 * The baseline selects the sentinel with {@code (mess == KILLED)} — reference equality on a
 * {@code String} (DEF-11, class (a)). It happens to behave identically to {@code .equals}
 * because the only caller that passes {@code KILLED} passes that interned literal. A port may
 * use {@code .equals} freely; the trace is byte-identical either way.
 *
 * @param state the state string, or the {@code KILL} sentinel
 */
public record TrainState(String state) implements RailwayMessage {
    /** The destructor sentinel, {@code Train.KILLED}. */
    public static final String KILLED = "KILL";

    @Override public Channel channel() { return Channel.TRAIN_STATE; }
    @Override public List<String> values() { return Arrays.asList(state); }
}
