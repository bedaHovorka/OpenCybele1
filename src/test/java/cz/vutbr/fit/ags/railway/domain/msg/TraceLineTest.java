/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The seven-field trace line, asserted against the real {@code vl3} sample in
 * {@code docs/trace-format.md}. That document is the contract; this is its executable copy, and
 * the point of the test is that #36's JADE probe and #43's Jason probe render from the same
 * code rather than from their own reading of it.
 */
class TraceLineTest {

    @Test
    @DisplayName("the documented sample lines are reproduced character for character")
    void the_documented_sample_lines_are_reproduced() {
        assertEquals("vl3|13016|PLAN_TRAIN|Main|Main|-|train=vl3,from=stA,to=stB",
                TraceLine.render(new PlanTrain("vl3", "stA", "stB"), 13016, "Main", "Main", TraceLine.NONE));
        assertEquals("vl3|13024|VOTE_REQUEST|Main|stA|-|train=vl3,expected=13024",
                TraceLine.render(new VoteRequest("vl3", 13024L), 13024, "Main", "stA", TraceLine.NONE));
        assertEquals("vl3|13024|TRAIN_STATE|vl3|Main|-|state=stA -> stB : vl3 generated",
                TraceLine.render(new TrainState("stA -> stB : vl3 generated"), 13024, "vl3", "Main", TraceLine.NONE));
        assertEquals("vl3|13040|VOTE|stB|Main|-|voter=stB,train=vl3,diff=0",
                TraceLine.render(new Vote("stB", "vl3", 0L), 13040, "stB", "Main", TraceLine.NONE));
        assertEquals("vl3|13056|VOTE_RESULT|Main|stA|-|train=vl3,planned=13024",
                TraceLine.render(new VoteResult("vl3", 13024L), 13056, "Main", "stA", TraceLine.NONE));
        assertEquals("vl3|13064|START|Main|vl3|-|station=stA",
                TraceLine.render(new StartCommand("stA"), 13064, "Main", "vl3", TraceLine.NONE));
        assertEquals("vl3|13072|ENTER|vl3|stA|-|train=vl3,position=null,target=stB",
                TraceLine.render(new EnterRequest("vl3", null, "stB"), 13072, "vl3", "stA", TraceLine.NONE));
        assertEquals("vl3|13080|ENTER_REPLY|stA|vl3|-|object=stA,next=tr1",
                TraceLine.render(new EnterReply("stA", "tr1"), 13080, "stA", "vl3", TraceLine.NONE));
        assertEquals("vl3|13080|LEAVE|vl3|stA|-|train=vl3",
                TraceLine.render(new LeaveNotice("vl3"), 13080, "vl3", "stA", TraceLine.NONE));
        assertEquals("vl3|13080|TRAVEL_START|vl3|tr1|-|train=vl3",
                TraceLine.render(new TravelStart("vl3"), 13080, "vl3", "tr1", TraceLine.NONE));
        assertEquals("vl3|14112|TRAVEL_END|tr1|vl3|-|road=tr1",
                TraceLine.render(new TravelEnd("tr1"), 14112, "tr1", "vl3", TraceLine.NONE));
        assertEquals("vl3|26576|ENTER_REPLY|stB|vl3|-|object=stB,next=null",
                TraceLine.render(new EnterReply("stB", null), 26576, "stB", "vl3", TraceLine.NONE));
        assertEquals("vl3|26584|TRAIN_STATE|vl3|Main|-|state=KILL",
                TraceLine.render(new TrainState(TrainState.KILLED), 26584, "vl3", "Main", TraceLine.NONE));
        // The four lines that are not about a train: the station, the track, and the two halves
        // of the path lookup.
        assertEquals("stA|13000|STATION_INFO|stA|Main|-|occupied=0,capacity=6",
                TraceLine.render(new StationInfo(0, 6), 13000, "stA", "Main", TraceLine.NONE));
        assertEquals("tr6|13000|ROAD_STATE|tr6|Main|-|state=TRAVEL_LEFT",
                TraceLine.render(new RoadStateReport(RoadDirection.TRAVEL_LEFT), 13000, "tr6", "Main", TraceLine.NONE));
        assertEquals("stA|13000|PATH_FIND|stA|Main|-|from=stA,to=stB",
                TraceLine.render(new PathFindRequest("stA", "stB"), 13000, "stA", "Main", TraceLine.NONE));
        assertEquals("stA|13000|PATH_FIND_REPLY|Main|stA|-|target=stB,direction=tr1",
                TraceLine.render(new PathFindReply("stB", "tr1"), 13000, "Main", "stA", TraceLine.NONE));
    }

    @Test
    @DisplayName("field 6 is the dash on a framework with no performatives and the act name on one that has them")
    void field_six_is_the_only_difference_between_the_two_renderings() {
        String cybele = TraceLine.render(new EnterRequest("vl3", null, "stB"), 13072, "vl3", "stA", TraceLine.NONE);
        String jade = TraceLine.render(new EnterRequest("vl3", null, "stB"), 13072, "vl3", "stA", TraceLine.JADE);
        assertEquals("vl3|13072|ENTER|vl3|stA|REQUEST|train=vl3,position=null,target=stB", jade);
        // #21's normalizer erases field 6 before comparing, which is what makes these two the
        // same line. Every other field must already agree.
        assertEquals(erase(cybele), erase(jade));
    }

    @Test
    @DisplayName("every channel renders seven fields with a non-empty subject")
    void every_channel_renders_seven_fields() {
        CanonicalMessages.all().forEach((channel, sample) -> {
            String line = TraceLine.render(sample.message(), 1234, sample.from(), sample.to(), TraceLine.JADE);
            String[] fields = line.split("\\|", -1);
            assertEquals(7, fields.length, channel.event() + ": " + line);
            assertEquals(sample.expectedSubject(), fields[0], channel.event());
            assertEquals("1234", fields[1]);
            assertEquals(channel.event(), fields[2]);
            assertEquals(sample.from(), fields[3]);
            assertEquals(sample.to(), fields[4]);
            assertEquals(channel.performative().fipaName(), fields[5]);
            assertEquals(sample.message().payload(), fields[6]);
            // The line never starts with a harness diagnostic prefix, so DiagnosticFilter
            // cannot strip it. Trace lines begin with a station, track or train name.
            assertTrue(!line.startsWith("--- ") && !line.startsWith("!!! ") && !line.startsWith("  "),
                    channel.event() + " would be stripped as a diagnostic");
        });
    }

    @Test
    @DisplayName("Main never appears as a subject, so a trace line can never be mistaken for a banner")
    void main_is_never_the_subject() {
        CanonicalMessages.all().forEach((channel, sample) ->
                assertTrue(!"Main".equals(sample.expectedSubject()), channel.event()));
    }

    private static String erase(String line) {
        String[] f = line.split("\\|", -1);
        f[5] = "";
        return String.join("|", f);
    }
}
