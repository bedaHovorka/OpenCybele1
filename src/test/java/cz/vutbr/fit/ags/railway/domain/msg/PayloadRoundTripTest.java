/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Rendering a payload into trace field 7 and reading it back. The format is
 * {@code docs/trace-format.md}'s and the expected strings below are lifted from its sample run.
 */
class PayloadRoundTripTest {

    @Test
    @DisplayName("every channel's payload renders and parses back to an equal record")
    void every_channel_round_trips() {
        CanonicalMessages.all().forEach((channel, sample) -> {
            String rendered = sample.message().payload();
            assertEquals(sample.message(), Payloads.parse(channel, rendered), channel.event());
        });
    }

    @Test
    @DisplayName("the rendered payloads are the ones in the documented sample trace")
    void rendered_payloads_match_the_documented_sample() {
        assertEquals("train=vl3,expected=13024", new VoteRequest("vl3", 13024L).payload());
        assertEquals("train=vl3,planned=13024", new VoteResult("vl3", 13024L).payload());
        assertEquals("train=vl3,position=null,target=stB", new EnterRequest("vl3", null, "stB").payload());
        assertEquals("train=vl3,position=stA,target=stB", new EnterRequest("vl3", "stA", "stB").payload());
        assertEquals("train=vl3", new LeaveNotice("vl3").payload());
        assertEquals("station=stA", new StartCommand("stA").payload());
        assertEquals("object=stA,next=tr1", new EnterReply("stA", "tr1").payload());
        assertEquals("object=stB,next=null", new EnterReply("stB", null).payload());
        assertEquals("road=tr1", new TravelEnd("tr1").payload());
        assertEquals("train=vl3", new TravelStart("vl3").payload());
        assertEquals("target=stB,direction=tr1", new PathFindReply("stB", "tr1").payload());
        assertEquals("occupied=0,capacity=6", new StationInfo(0, 6).payload());
        assertEquals("state=TRAVEL_LEFT", new RoadStateReport(RoadDirection.TRAVEL_LEFT).payload());
        assertEquals("state=FREE", new RoadStateReport(RoadDirection.FREE).payload());
        assertEquals("state=KILL", new TrainState(TrainState.KILLED).payload());
        assertEquals("state=stA -> stB : entered to stA",
                new TrainState("stA -> stB : entered to stA").payload());
        assertEquals("train=vl3,from=stA,to=stB", new PlanTrain("vl3", "stA", "stB").payload());
        assertEquals("voter=stB,train=vl3,diff=0", new Vote("stB", "vl3", 0L).payload());
        assertEquals("from=stA,to=stB", new PathFindRequest("stA", "stB").payload());
    }

    @Test
    @DisplayName("a null slot renders as the four characters null and parses back to null")
    void nulls_survive_the_round_trip() {
        // position=null on a train's first ENTER is correct, not a fault: Train.position is
        // still unset when start() calls requestEnterToObject.
        EnterRequest first = new EnterRequest("vl3", null, "stB");
        assertNull(((EnterRequest) Payloads.parse(Channel.ENTER, first.payload())).position());
        // next=null on the last ENTER_REPLY is how a train is told it has arrived.
        EnterReply arrival = new EnterReply("stB", null);
        assertNull(((EnterReply) Payloads.parse(Channel.ENTER_REPLY, arrival.payload())).next());
        assertNull(Payloads.unescape("null"));
        assertEquals("null", Payloads.escape(null));
    }

    @Test
    @DisplayName("percent is escaped first, so the escaping is reversible")
    void escaping_is_reversible() {
        // No value this application produces contains any of these; the escape exists so that a
        // future payload cannot corrupt a golden by splitting one line into two.
        String hostile = "a%7Cb|c\nd\re%f";
        String escaped = Payloads.escape(hostile);
        assertEquals("a%257Cb%7Cc%0Ad%0De%25f", escaped);
        assertEquals(hostile, Payloads.unescape(escaped));
        assertEquals(hostile, ((TrainState) Payloads.parse(Channel.TRAIN_STATE,
                new TrainState(hostile).payload())).state());
    }

    @Test
    @DisplayName("a slot-count mismatch is an error, not a silently short line")
    void arity_is_checked() {
        assertThrows(IllegalArgumentException.class,
                () -> Payloads.render(Channel.ENTER, List.of("vl3", "stA")));
        assertThrows(IllegalArgumentException.class,
                () -> Payloads.of(Channel.ENTER, Arrays.asList("vl3", "stA")));
        assertThrows(IllegalArgumentException.class,
                () -> Payloads.split(Channel.ENTER, "train=vl3,position=stA"));
        assertThrows(IllegalArgumentException.class,
                () -> Payloads.split(Channel.ENTER, "wrong=vl3,position=stA,target=stB"));
    }

    @Test
    @DisplayName("the road state travels as the enum's name, which is what a golden can contain")
    void road_state_wire_form_is_the_enum_name() {
        for (RoadDirection d : RoadDirection.values()) {
            assertEquals("state=" + d.name(), new RoadStateReport(d).payload());
            assertEquals(new RoadStateReport(d),
                    Payloads.parse(Channel.ROAD_STATE, new RoadStateReport(d).payload()));
        }
        assertEquals("", RoadDirection.FREE.getSymbol());
        assertEquals("<", RoadDirection.TRAVEL_LEFT.getSymbol());
        assertEquals(">", RoadDirection.TRAVEL_RIGHT.getSymbol());
    }
}
