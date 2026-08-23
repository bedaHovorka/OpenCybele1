/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.xhovor07;

import cz.vutbr.fit.ags.railway.domain.msg.Channel;
import cz.vutbr.fit.ags.railway.domain.msg.EnterReply;
import cz.vutbr.fit.ags.railway.domain.msg.EnterRequest;
import cz.vutbr.fit.ags.railway.domain.msg.LeaveNotice;
import cz.vutbr.fit.ags.railway.domain.msg.PathFindReply;
import cz.vutbr.fit.ags.railway.domain.msg.PathFindRequest;
import cz.vutbr.fit.ags.railway.domain.msg.PlanTrain;
import cz.vutbr.fit.ags.railway.domain.msg.RailwayMessage;
import cz.vutbr.fit.ags.railway.domain.msg.RoadDirection;
import cz.vutbr.fit.ags.railway.domain.msg.RoadStateReport;
import cz.vutbr.fit.ags.railway.domain.msg.StartCommand;
import cz.vutbr.fit.ags.railway.domain.msg.StationInfo;
import cz.vutbr.fit.ags.railway.domain.msg.TrainState;
import cz.vutbr.fit.ags.railway.domain.msg.TravelEnd;
import cz.vutbr.fit.ags.railway.domain.msg.TravelStart;
import cz.vutbr.fit.ags.railway.domain.msg.Vote;
import cz.vutbr.fit.ags.railway.domain.msg.VoteRequest;
import cz.vutbr.fit.ags.railway.domain.msg.VoteResult;

/**
 * One legal payload per channel, so that a test can put traffic on all fifteen topics at once
 * (#38).
 * <p>
 * <b>Not a second copy of the canonical table.</b> {@code CanonicalMessages} in the {@code test}
 * source set is that — its samples come from the real {@code vl3} trace in
 * {@code docs/trace-format.md} and it exists so L1 assertions are made against a run that actually
 * happened. This one asserts nothing about values; it exists only so
 * {@code LifecycleIT.all_fifteen_topics_are_registered} can name a message for each channel, and
 * the lanes stay independent of one another's sources — the same reason {@code characterizationIT}
 * has no compile-time link to {@code main}.
 */
final class ChannelSamples {

    private ChannelSamples() {
    }

    /**
     * @param channel the channel
     * @return a well-formed payload record for it
     */
    static RailwayMessage of(Channel channel) {
        return switch (channel) {
            case VOTE_REQUEST -> new VoteRequest("vl3", 1000L);
            case VOTE_RESULT -> new VoteResult("vl3", 1000L);
            case ENTER -> new EnterRequest("vl3", null, "stB");
            case LEAVE -> new LeaveNotice("vl3");
            case START -> new StartCommand("stA");
            case ENTER_REPLY -> new EnterReply("stA", "tr1");
            case TRAVEL_END -> new TravelEnd("tr1");
            case TRAVEL_START -> new TravelStart("vl3");
            case PATH_FIND_REPLY -> new PathFindReply("stB", "tr1");
            case STATION_INFO -> new StationInfo(0, 6);
            case ROAD_STATE -> new RoadStateReport(RoadDirection.FREE);
            case TRAIN_STATE -> new TrainState("stA -> stB : vl3 generated");
            case PLAN_TRAIN -> new PlanTrain("vl3", "stA", "stB");
            case VOTE -> new Vote("stA", "vl3", 0L);
            case PATH_FIND -> new PathFindRequest("stA", "stB");
        };
    }
}
