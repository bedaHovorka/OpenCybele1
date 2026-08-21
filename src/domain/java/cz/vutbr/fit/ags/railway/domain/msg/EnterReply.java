/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

import java.util.Arrays;
import java.util.List;

/**
 * CH-06 {@code ENTER_REPLY.<train>} — admission granted.
 * <p>
 * <strong>There is no negative reply anywhere in this protocol.</strong> A train that cannot be
 * admitted is silently queued and hears nothing; the queue <em>is</em> the refusal. No
 * {@code refuse}, no {@code failure}, no {@code not-understood} is ever sent, and a port must
 * not invent one — a template waiting for a rejection would wait forever.
 * <p>
 * The performative is {@code inform}, not {@code agree}: the receiving object has
 * <em>already</em> incremented its occupancy and changed its direction before it replies, so the
 * reply reports a completed fact rather than a commitment to act later.
 * <p>
 * {@code next} is {@code null} when the train has arrived at its destination; that is how a
 * train is told to die.
 *
 * @param object the object that admitted the train
 * @param next   the next object on the route, or {@code null} on arrival
 */
public record EnterReply(String object, String next) implements RailwayMessage {
    @Override public Channel channel() { return Channel.ENTER_REPLY; }
    @Override public List<String> values() { return Arrays.asList(object, next); }
}
