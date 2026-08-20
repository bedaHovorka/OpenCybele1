/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One canonical message per channel, with its two endpoints — taken from the real {@code vl3}
 * sample trace in {@code docs/trace-format.md} rather than invented, so that the tests below
 * assert against a run that actually happened.
 */
public final class CanonicalMessages {

    /** A message together with the endpoints that give it its trace fields 4 and 5. */
    public record Sample(RailwayMessage message, String from, String to, String expectedSubject) {
    }

    private CanonicalMessages() {
    }

    public static Map<Channel, Sample> all() {
        Map<Channel, Sample> m = new LinkedHashMap<>();
        m.put(Channel.VOTE_REQUEST, new Sample(new VoteRequest("vl3", 13024L), "Main", "stA", "vl3"));
        m.put(Channel.VOTE_RESULT, new Sample(new VoteResult("vl3", 13024L), "Main", "stA", "vl3"));
        m.put(Channel.ENTER, new Sample(new EnterRequest("vl3", null, "stB"), "vl3", "stA", "vl3"));
        m.put(Channel.LEAVE, new Sample(new LeaveNotice("vl3"), "vl3", "stA", "vl3"));
        m.put(Channel.START, new Sample(new StartCommand("stA"), "Main", "vl3", "vl3"));
        m.put(Channel.ENTER_REPLY, new Sample(new EnterReply("stA", "tr1"), "stA", "vl3", "vl3"));
        m.put(Channel.TRAVEL_END, new Sample(new TravelEnd("tr1"), "tr1", "vl3", "vl3"));
        m.put(Channel.TRAVEL_START, new Sample(new TravelStart("vl3"), "vl3", "tr1", "vl3"));
        m.put(Channel.PATH_FIND_REPLY, new Sample(new PathFindReply("stB", "tr1"), "Main", "stA", "stA"));
        m.put(Channel.STATION_INFO, new Sample(new StationInfo(0, 6), "stA", "Main", "stA"));
        m.put(Channel.ROAD_STATE, new Sample(new RoadStateReport(RoadDirection.TRAVEL_LEFT), "tr6", "Main", "tr6"));
        m.put(Channel.TRAIN_STATE, new Sample(new TrainState("stA -> stB : vl3 generated"), "vl3", "Main", "vl3"));
        m.put(Channel.PLAN_TRAIN, new Sample(new PlanTrain("vl3", "stA", "stB"), "Main", "Main", "vl3"));
        m.put(Channel.VOTE, new Sample(new Vote("stB", "vl3", 0L), "stB", "Main", "vl3"));
        m.put(Channel.PATH_FIND, new Sample(new PathFindRequest("stA", "stB"), "stA", "Main", "stA"));
        return m;
    }
}
