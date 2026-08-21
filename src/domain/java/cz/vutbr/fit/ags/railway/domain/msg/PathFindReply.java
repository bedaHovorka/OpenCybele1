/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

import java.util.Arrays;
import java.util.List;

/**
 * CH-09 {@code PATH_FIND_REPLY.<st>} — the answer to CH-15.
 * <p>
 * {@code inform} rather than {@code inform-ref}: FIPA's {@code inform-ref} is a macro act with
 * no standard content form, and JADE's own query helpers answer a {@code query-ref} with
 * {@code inform}. Choosing the macro act would buy nothing and would make the template harder to
 * write.
 *
 * @param target    the station that was asked about
 * @param direction the track to leave on
 */
public record PathFindReply(String target, String direction) implements RailwayMessage {
    @Override public Channel channel() { return Channel.PATH_FIND_REPLY; }
    @Override public List<String> values() { return Arrays.asList(target, direction); }
}
