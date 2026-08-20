package cz.vutbr.fit.ags.parity.it;

import cz.vutbr.fit.ags.parity.normalize.CanonicalTraceNormalizer;
import cz.vutbr.fit.ags.parity.normalize.FieldProjection;
import cz.vutbr.fit.ags.parity.normalize.TraceRule;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The normalizer's own tests (<a href="https://github.com/bedaHovorka/OpenCybele1/issues/21">#21</a>).
 *
 * <p>Two things are asserted about <em>every</em> rule, and they are the two halves of "a rule must
 * be able to fail":
 *
 * <ol>
 *   <li><strong>It fires.</strong> Given an input carrying the variance it answers, the rule
 *       changes the output.</li>
 *   <li><strong>Removing it breaks something.</strong> {@link CanonicalTraceNormalizer#without}
 *       builds a normalizer missing exactly that rule, and two inputs that differ only in that
 *       variance stop agreeing. A rule whose removal changes nothing is pinning nothing — the
 *       "lock that cannot fail" this project has rejected four times — and
 *       {@link #everyRuleIsLoadBearing()} fails the build if one appears.</li>
 * </ol>
 *
 * <p>The inputs are hand-built pairs rather than captured runs, so each test names the variance it
 * is about and fails for one reason. The captured-run evidence is separate: it is in
 * {@code docs/trace-normalizer.md} and it is what chose the rules in the first place.
 */
class TraceNormalizerIT {

    private static final CanonicalTraceNormalizer NORMALIZER = new CanonicalTraceNormalizer();

    // A short but complete run shape: kernel banner, startup block, one election burst, a second
    // burst 3 s later, and the two application printlns.
    private static final List<String> RUN_A = List.of(
            "Cybele version 1.2 starting ...",
            "... Cybele started",
            "stA|1096|STATION_INFO|stA|Main|-|occupied=0,capacity=6",
            "stB|1096|STATION_INFO|stB|Main|-|occupied=0,capacity=5",
            "tr1|1104|ROAD_STATE|tr1|Main|-|state=FREE",
            "tr2|1112|ROAD_STATE|tr2|Main|-|state=FREE",
            "vl0|1320|PLAN_TRAIN|Main|Main|-|train=vl0,from=stB,to=stC",
            "vl0|1328|VOTE_REQUEST|Main|stB|-|train=vl0,expected=1320",
            "vl0|1336|VOTE|stB|Main|-|voter=stB,train=vl0,diff=0",
            "vl0|1344|VOTE_RESULT|Main|stB|-|train=vl0,planned=1320",
            "vl0 in stB at 1320",
            "vl0|1368|START|Main|vl0|-|station=stB",
            "vl0 started",
            "vl1|4320|PLAN_TRAIN|Main|Main|-|train=vl1,from=stA,to=stC",
            "vl1|4328|VOTE|stA|Main|-|voter=stA,train=vl1,diff=333",
            "vl1 in stA at 4320",
            "vl1 started");

    // --- the rule inventory ----------------------------------------------------------------

    @Test
    @DisplayName("every rule carries the observed variance source that justifies it")
    void everyRuleIsJustified() {
        List<TraceRule> rules = CanonicalTraceNormalizer.allRules();
        assertFalse(rules.isEmpty());
        for (TraceRule rule : rules) {
            assertFalse(rule.id().isBlank(), "a rule with no id cannot be switched off or reported on");
            assertTrue(rule.because().length() > 40,
                    "rule '" + rule.id() + "' has no real justification. TESTING.md §7: a rule that"
                            + " is not justified by an OBSERVED variance source is over-assertion in"
                            + " reverse — it removes evidence for a reason nobody wrote down.");
        }
        assertEquals(Set.of("tick", "expected", "planned", "diff", "occupied", "performative",
                        "road-state", "departure", "startup-block", "burst-order", "println-streams"),
                rules.stream().map(TraceRule::id).collect(Collectors.toSet()),
                "the rule set changed. That is allowed, but docs/trace-normalizer.md is the contract"
                        + " for what a golden pins and it has to change with it.");
    }

    @Test
    @DisplayName("a rule whose removal changes nothing would be a lock that cannot fail")
    void everyRuleIsLoadBearing() {
        for (TraceRule rule : CanonicalTraceNormalizer.allRules()) {
            List<String> withRule = NORMALIZER.normalize(varianceFor(rule.id()).get(0));
            List<String> withRuleB = NORMALIZER.normalize(varianceFor(rule.id()).get(1));
            assertEquals(withRule, withRuleB,
                    "rule '" + rule.id() + "' does not actually absorb the variance it names");

            CanonicalTraceNormalizer broken = NORMALIZER.without(rule.id());
            assertNotEquals(broken.normalize(varianceFor(rule.id()).get(0)),
                    broken.normalize(varianceFor(rule.id()).get(1)),
                    "removing rule '" + rule.id() + "' changed nothing, so the rule is not pinning"
                            + " anything and should be deleted rather than kept for tidiness");
        }
    }

    @Test
    @DisplayName("removing a rule that does not exist is an error, not a silent no-op")
    void unknownRuleIsRejected() {
        assertThrows(IllegalArgumentException.class, () -> NORMALIZER.without("no-such-rule"));
    }

    /**
     * Two runs differing <em>only</em> in the variance the named rule answers. Written out per rule
     * so that a failure names one cause; a single "everything at once" fixture would not.
     */
    private static List<List<String>> varianceFor(String ruleId) {
        return switch (ruleId) {
            case "tick" -> List.of(
                    List.of("vl0|1320|PLAN_TRAIN|Main|Main|-|train=vl0,from=stB,to=stC"),
                    List.of("vl0|1328|PLAN_TRAIN|Main|Main|-|train=vl0,from=stB,to=stC"));
            case "expected" -> List.of(
                    List.of("vl0|1|VOTE_REQUEST|Main|stB|-|train=vl0,expected=1320"),
                    List.of("vl0|1|VOTE_REQUEST|Main|stB|-|train=vl0,expected=1328"));
            case "planned" -> List.of(
                    List.of("vl0|1|VOTE_RESULT|Main|stB|-|train=vl0,planned=13920"),
                    List.of("vl0|1|VOTE_RESULT|Main|stB|-|train=vl0,planned=13888"));
            case "diff" -> List.of(
                    List.of("vl0|1|VOTE|stB|Main|-|voter=stB,train=vl0,diff=3222"),
                    List.of("vl0|1|VOTE|stB|Main|-|voter=stB,train=vl0,diff=3254"));
            case "occupied" -> List.of(
                    List.of("stA|1|STATION_INFO|stA|Main|-|occupied=0,capacity=6"),
                    List.of("stA|1|STATION_INFO|stA|Main|-|occupied=1,capacity=6"));
            case "road-state" -> List.of(
                    List.of("tr6|1|ROAD_STATE|tr6|Main|-|state=FREE"),
                    List.of("tr6|1|ROAD_STATE|tr6|Main|-|state=TRAVEL_LEFT"));
            case "performative" -> List.of(
                    List.of("vl0|1|PLAN_TRAIN|Main|Main|-|train=vl0,from=stB,to=stC"),
                    List.of("vl0|1|PLAN_TRAIN|Main|Main|INFORM|train=vl0,from=stB,to=stC"));
            case "departure" -> List.of(List.of("vl5 in stA at 30280"), List.of("vl5 in stA at 30288"));
            case "startup-block" -> List.of(
                    List.of("stA|1096|STATION_INFO|stA|Main|-|occupied=0,capacity=6",
                            "tr1|1104|ROAD_STATE|tr1|Main|-|state=FREE",
                            "tr2|1112|ROAD_STATE|tr2|Main|-|state=FREE"),
                    // Same three constructors, a different completion order — and note that tr2
                    // moved ACROSS a tick boundary, which is why sorting within a tick cannot fix
                    // this and the block has to be identified structurally.
                    List.of("tr2|1096|ROAD_STATE|tr2|Main|-|state=FREE",
                            "stA|1104|STATION_INFO|stA|Main|-|occupied=0,capacity=6",
                            "tr1|1112|ROAD_STATE|tr1|Main|-|state=FREE"));
            case "burst-order" -> List.of(
                    List.of("vl0|1320|PLAN_TRAIN|Main|Main|-|train=vl0,from=stB,to=stC",
                            "vl0|1328|VOTE|stB|Main|-|voter=stB,train=vl0,diff=0",
                            "vl0|1336|VOTE|stD|Main|-|voter=stD,train=vl0,diff=0"),
                    // Same burst, the two votes arriving the other way round and one tick later:
                    // the ticks themselves differ, so an equal-tick sort would never see them
                    // together.
                    List.of("vl0|1320|PLAN_TRAIN|Main|Main|-|train=vl0,from=stB,to=stC",
                            "vl0|1336|VOTE|stD|Main|-|voter=stD,train=vl0,diff=0",
                            "vl0|1344|VOTE|stB|Main|-|voter=stB,train=vl0,diff=0"));
            case "println-streams" -> List.of(
                    // The two orderings measured at seed 987654321, six runs (issue #21).
                    List.of("vl5 in stA at 30280", "vl5 started",
                            "vl6 in stC at 30280", "vl6 started"),
                    List.of("vl5 in stA at 30279", "vl6 in stC at 30279",
                            "vl5 started", "vl6 started"));
            default -> throw new IllegalStateException("rule '" + ruleId + "' has no variance"
                    + " fixture. Every rule must have one: a rule nobody can demonstrate a run-to-run"
                    + " difference for is a rule nobody measured.");
        };
    }

    // --- the performative rule, against a synthetic three-branch sample ---------------------

    @Nested
    @DisplayName("the performative field")
    class Performative {

        /**
         * The acceptance criterion #21 names explicitly. There is no JADE and no Jason trace to
         * capture yet, so the sample is synthesised — a Cybele line, a JADE-shaped one and a
         * Jason-shaped one that describe the <em>same</em> message — and the claim is exactly the
         * one the criterion makes: after normalization the three are one line.
         *
         * <p><strong>What this does not establish:</strong> that a real JADE or Jason probe will
         * emit these shapes. #27 owns the channel → performative mapping and #48 the Jason side;
         * this test fixes what the normalizer does with whatever they choose, not what they choose.
         */
        @Test
        @DisplayName("erases field 6 so a Cybele, a JADE and a Jason line converge")
        void threeBranchSampleConverges() {
            String cybele = "vl3|13016|PLAN_TRAIN|Main|Main|-|train=vl3,from=stA,to=stB";
            String jade = "vl3|13016|PLAN_TRAIN|Main|Main|INFORM|train=vl3,from=stA,to=stB";
            String jason = "vl3|13016|PLAN_TRAIN|Main|Main|achieve|train=vl3,from=stA,to=stB";

            List<String> expected = List.of("vl3|<T>|PLAN_TRAIN|Main|Main|<P>|train=vl3,from=stA,to=stB");
            assertEquals(expected, NORMALIZER.normalize(List.of(cybele)));
            assertEquals(expected, NORMALIZER.normalize(List.of(jade)));
            assertEquals(expected, NORMALIZER.normalize(List.of(jason)));

            CanonicalTraceNormalizer broken = NORMALIZER.without("performative");
            assertEquals(3, List.of(broken.normalize(List.of(cybele)),
                    broken.normalize(List.of(jade)),
                    broken.normalize(List.of(jason))).stream().distinct().count(),
                    "without the rule the three branches must diverge — otherwise this test would"
                            + " pass whether the rule existed or not");
        }

        /** Field 6 and nothing else: an empty performative must not eat a neighbouring field. */
        @Test
        @DisplayName("touches field 6 only")
        void touchesFieldSixOnly() {
            List<String> out = NORMALIZER.normalize(
                    List.of("vl3|1|ENTER_REPLY|stA|vl3|REQUEST|object=stA,next=tr1"));
            assertEquals(List.of("vl3|<T>|ENTER_REPLY|stA|vl3|<P>|object=stA,next=tr1"), out);
        }
    }

    // --- structural guarantees --------------------------------------------------------------

    @Test
    @DisplayName("normalize is idempotent, which is what lets a golden be compared without re-recording")
    void idempotent() {
        List<String> once = NORMALIZER.normalize(RUN_A);
        assertEquals(once, NORMALIZER.normalize(once));
        assertEquals(once, NORMALIZER.normalize(NORMALIZER.normalize(once)));
    }

    @Test
    @DisplayName("idempotent even when the burst sort leaves a state line at the head of a burst")
    void idempotentWhenABurstStartsWithAStateLine() {
        // THE SHAPE THAT BREAKS IT, and the reason the general fixture above is not enough.
        //
        // Pass one sorts within a burst, and `stA…` sorts before `vl0…`. So a burst whose lines
        // include a STATION_INFO comes out with that STATION_INFO at its head — directly after the
        // startup block. An unguarded second pass then reads it as PART of the startup block and
        // re-sorts, and here stA would migrate above stB.
        //
        // Nothing in today's opencybele-strict has this shape (its first post-startup line is a
        // PATH_FIND_REPLY), which is exactly why it needs a test rather than a run. It matters
        // because ScenarioRunner normalizes the GOLDEN on every comparison: a golden with this
        // shape — a different topology, a different pace, a JADE probe with different emission
        // timing — could never match its own recording, and would be reported as "a port bug until
        // proven a harness defect; re-recording is not a triage option."
        List<String> capture = List.of(
                "stB|1000|STATION_INFO|stB|Main|-|occupied=0,capacity=5",
                "vl0|2000|PLAN_TRAIN|Main|Main|-|train=vl0,from=stA,to=stB",
                "stA|2008|STATION_INFO|stA|Main|-|occupied=1,capacity=6");

        List<String> once = NORMALIZER.normalize(capture);
        assertEquals(List.of(
                        "stB|<T>|STATION_INFO|stB|Main|<P>|occupied=<N>,capacity=5",
                        "stA|<T>|STATION_INFO|stA|Main|<P>|occupied=<N>,capacity=6",
                        "vl0|<T>|PLAN_TRAIN|Main|Main|<P>|train=vl0,from=stA,to=stB"),
                once,
                "pass one: stB is the startup block, and the burst sorts stA ahead of vl0");
        assertEquals(once, NORMALIZER.normalize(once),
                "pass two must not re-read stA as part of the startup block and sort it above stB");
    }

    @Test
    @DisplayName("diff keeps its magnitude at a declared resolution rather than being erased")
    void diffIsQuantisedNotErased() {
        // Erased, `diff` left a port free to return 0 from every vote and still match byte for
        // byte: it is the only payload carrying what computeDifference computed, which #28
        // extracts, #34 restructures and #36/#43 must reimplement.
        assertEquals(List.of("vl8|<T>|VOTE|tr5|Main|<P>|voter=tr5,train=vl8,diff=<T~41000>"),
                NORMALIZER.normalize(List.of("vl8|1|VOTE|tr5|Main|-|voter=tr5,train=vl8,diff=41904")));
        assertEquals(List.of("vl8|<T>|VOTE|tr5|Main|<P>|voter=tr5,train=vl8,diff=<T~41000>"),
                NORMALIZER.normalize(List.of("vl8|1|VOTE|tr5|Main|-|voter=tr5,train=vl8,diff=41920")),
                "the whole measured 8-24 ms jitter band must land in one bucket");

        // The two failures it is there to catch.
        assertNotEquals(NORMALIZER.normalize(List.of("vl8|1|VOTE|tr5|Main|-|voter=tr5,train=vl8,diff=41904")),
                NORMALIZER.normalize(List.of("vl8|1|VOTE|tr5|Main|-|voter=tr5,train=vl8,diff=0")),
                "a port that votes zero must not match a port that demands 41 s");
        assertNotEquals(NORMALIZER.normalize(List.of("vl4|1|VOTE|tr7|Main|-|voter=tr7,train=vl4,diff=5176")),
                NORMALIZER.normalize(List.of("vl4|1|VOTE|tr7|Main|-|voter=tr7,train=vl4,diff=2176")),
                "a missing road delay is 1-5 s in this topology and must not survive the bucket");
    }

    @Test
    @DisplayName("the absolute clock instants are erased, and the measurement says why")
    void absoluteInstantsAreErasedNotQuantised() {
        // expected/planned/departure are base + k*1000 (road delays are whole seconds) on a base
        // that jitters ~200 ms, so any 1000 ms bucket is RESONANT with the data: one base crossing
        // a boundary moves every leg of the election at once. Measured, quantising them took the
        // strict scenario from 14/14 byte-identical to 5 distinct traces in 14.
        assertEquals(NORMALIZER.normalize(List.of("vl2|1|VOTE_REQUEST|Main|stC|-|train=vl2,expected=10032")),
                NORMALIZER.normalize(List.of("vl2|1|VOTE_REQUEST|Main|stC|-|train=vl2,expected=9840")),
                "9840 and 10032 are the same election in two real runs and must normalize alike");
        assertEquals(NORMALIZER.normalize(List.of("vl3 in stA at 13144")),
                NORMALIZER.normalize(List.of("vl3 in stA at 12944")),
                "measured departures of vl3 in two real runs, either side of a 1000 ms boundary");
    }

    @Test
    @DisplayName("no line is added or removed — only rewritten and moved")
    void lineCountIsPreserved() {
        List<String> out = NORMALIZER.normalize(RUN_A);
        assertEquals(RUN_A.size(), out.size(),
                "the projection dropped or invented a line. It may rewrite a value and it may move"
                        + " a line; deleting one would silently absorb a port that stopped emitting"
                        + " it, and inventing one is exactly what DEF-02 must NOT be papered over"
                        + " with.");
    }

    @Test
    @DisplayName("a missing entity is NOT invented: a dropped train is still a diff")
    void aDroppedTrainIsStillADiff() {
        // DEF-02 drops trains. No normalisation may make that look like a match.
        List<String> withoutVl1 = RUN_A.stream().filter(l -> !l.contains("vl1")).toList();
        assertNotEquals(NORMALIZER.normalize(RUN_A), NORMALIZER.normalize(withoutVl1));
    }

    @Test
    @DisplayName("order across bursts is preserved: the golden pins sequence, not a multiset")
    void orderAcrossBurstsSurvives() {
        List<String> out = NORMALIZER.normalize(RUN_A);
        assertTrue(out.indexOf("vl0|<T>|PLAN_TRAIN|Main|Main|<P>|train=vl0,from=stB,to=stC")
                        < out.indexOf("vl1|<T>|PLAN_TRAIN|Main|Main|<P>|train=vl1,from=stA,to=stC"),
                "the two elections are 3 s apart and must stay in that order: " + out);

        List<String> sorted = new ArrayList<>(out);
        Collections.sort(sorted);
        assertNotEquals(sorted, out, "a fully sorted output is a multiset, not a trace");
    }

    @Test
    @DisplayName("a run with distinct bursts segments into more than one")
    void segmentsAreNotDegenerate() {
        assertTrue(NORMALIZER.segments(RUN_A).size() >= 2,
                "3 s apart is two bursts at any sane width; one burst means the golden pins no order");
    }

    @Test
    @DisplayName("the departure stream keeps departure order; the started stream is canonicalised")
    void printlnStreamsAreSplit() {
        List<String> out = NORMALIZER.normalize(RUN_A);
        List<String> tail = out.subList(out.size() - 4, out.size());
        assertEquals(List.of(
                        "vl0 in stB at <T>",
                        "vl1 in stA at <T>",
                        "vl0 started",
                        "vl1 started"),
                tail,
                "the two printlns race each other into the stream, so they are split into two"
                        + " streams: 'in … at' in emission order (one serial producer, so departure"
                        + " ORDER survives) and 'started' sorted by name (one producer per train, so"
                        + " its cross-train order is arbitrary)");
    }

    @Test
    @DisplayName("a non-canonical line is left alone by every canonical-only projection")
    void nonCanonicalLinesAreNotMangled() {
        List<String> banner = List.of(
                "Cybele version 1.2 starting ...",
                "*** Loading timer service (expecting impl. of GSI ver. 1.1) ... ",
                "... Cybele started");
        assertEquals(banner, NORMALIZER.normalize(banner));
        for (FieldProjection projection : CanonicalTraceNormalizer.allProjections()) {
            for (String line : banner) {
                assertEquals(line, projection.apply(line, false),
                        "projection '" + projection.id() + "' rewrote a non-canonical line");
            }
        }
    }

    @Test
    @DisplayName("a payload without the projected key is untouched")
    void projectionsDoNotOverreach() {
        // ENTER's payload is three agent names: nothing here is clock-derived, and a rule that
        // rewrote part of it would be over-assertion in reverse.
        String enter = "vl0|1392|ENTER|vl0|tr5|-|train=vl0,position=stB,target=stC";
        assertEquals(List.of("vl0|<T>|ENTER|vl0|tr5|<P>|train=vl0,position=stB,target=stC"),
                NORMALIZER.normalize(List.of(enter)));
    }

    @Test
    @DisplayName("TRAIN_STATE's state is NOT projected — it is a behaviour string, not a road enum")
    void trainStateSurvives() {
        String line = "vl0|1392|TRAIN_STATE|vl0|Main|-|state=stB -> stC : entered to tr5";
        assertEquals(List.of("vl0|<T>|TRAIN_STATE|vl0|Main|<P>|state=stB -> stC : entered to tr5"),
                NORMALIZER.normalize(List.of(line)));
        assertEquals(List.of("vl0|<T>|TRAIN_STATE|vl0|Main|<P>|state=KILL"),
                NORMALIZER.normalize(List.of("vl0|1392|TRAIN_STATE|vl0|Main|-|state=KILL")));
    }

    @Test
    @DisplayName("capacity survives: it is configuration a port must replicate, not a race")
    void capacitySurvives() {
        assertEquals(List.of("stA|<T>|STATION_INFO|stA|Main|<P>|occupied=<N>,capacity=6"),
                NORMALIZER.normalize(List.of("stA|1|STATION_INFO|stA|Main|-|occupied=3,capacity=6")));
    }

    @Test
    @DisplayName("the burst width is a parameter, and a wider one really does merge bursts")
    void burstWidthIsAParameter() {
        // Two: the banner, the startup block and the first election are within one burst width of
        // each other (1320 - 1112 = 208 <= 220), and the second election is 3 s later. That the
        // answer moved from 3 to 2 when the width went 200 -> 220 is the point of the assertion.
        assertEquals(2, NORMALIZER.segments(RUN_A).size(),
                "banner + startup + first election, then the election 3 s later");
        assertEquals(1, new CanonicalTraceNormalizer(10_000L).segments(RUN_A).size());
    }
}
