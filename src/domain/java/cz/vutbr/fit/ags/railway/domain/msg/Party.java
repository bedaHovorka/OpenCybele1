/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

/**
 * The kind of agent at one end of a channel. Used to derive, mechanically, which
 * {@code MessageTemplate}s each ported agent class has to register — see
 * {@code docs/message-ontology.md} §"Template disjointness".
 */
public enum Party {
    /** {@code Main} — {@code RailwayMainAgent} and its {@code Planning}/{@code Generator}/{@code VoteCollecting} activities. */
    MAIN,
    /** A {@code Train} agent. */
    TRAIN,
    /** A {@code Station} agent (including its {@code PathFinding} activity). */
    STATION,
    /** A {@code RoadAgent}. */
    ROAD,
    /** Either a {@code Station} or a {@code RoadAgent} — the {@code StaticRailwayObject} half of the protocol. */
    STATIC_OBJECT;

    /**
     * Whether an agent of kind {@code self} plays this role.
     *
     * @param self a concrete agent kind, never {@link #STATIC_OBJECT}
     * @return {@code true} if a message with this party as an endpoint reaches {@code self}
     */
    public boolean covers(Party self) {
        return this == self || (this == STATIC_OBJECT && (self == STATION || self == ROAD));
    }
}
