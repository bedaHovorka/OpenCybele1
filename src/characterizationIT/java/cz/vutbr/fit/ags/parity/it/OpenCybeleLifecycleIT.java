package cz.vutbr.fit.ags.parity.it;

import cz.vutbr.fit.ags.parity.ParityLayout;
import cz.vutbr.fit.ags.parity.golden.GoldenStore;
import cz.vutbr.fit.ags.parity.opencybele.OpenCybeleLauncher;
import cz.vutbr.fit.ags.parity.run.RunReport;
import cz.vutbr.fit.ags.parity.run.ScenarioRunner;
import cz.vutbr.fit.ags.parity.spec.ContractLevel;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpec;
import cz.vutbr.fit.ags.parity.spec.ScenarioSpecParser;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * {@code opencybele-lifecycle} — the startup/shutdown scenario of
 * <a href="https://github.com/bedaHovorka/OpenCybele1/issues/23">#23</a>, and the coverage claim
 * that <em>every one of the fifteen channels</em> is exercised somewhere.
 *
 * <p>The golden already pins all of this positionally. What is asserted here is the part of the
 * claim that would otherwise live only in {@code COVERAGE.md} — and a coverage claim in prose is
 * exactly the kind that quietly stops being true.
 */
class OpenCybeleLifecycleIT {

    private static final String SCENARIO = "opencybele-lifecycle";

    /** INVENTORY §4: all fifteen channels, by the event token {@code docs/trace-format.md} fixes. */
    private static final List<String> ALL_FIFTEEN_CHANNELS = List.of(
            "VOTE_REQUEST", "VOTE_RESULT", "ENTER", "LEAVE", "START", "ENTER_REPLY", "TRAVEL_END",
            "TRAVEL_START", "PATH_FIND_REPLY", "STATION_INFO", "ROAD_STATE", "TRAIN_STATE",
            "PLAN_TRAIN", "VOTE", "PATH_FIND");

    private static final Pattern CANONICAL =
            Pattern.compile("^([^|]+)\\|<T>\\|([A-Z_]+)\\|([^|]*)\\|([^|]*)\\|[^|]*\\|(.*)$");

    @Test
    @DisplayName("the smallest network exercises all fifteen channels and one full train lifecycle")
    void lifecycleScenarioCoversEveryChannelAndTheDestructor() {
        assumeTrue(OpenCybeleLauncher.isAvailable(), OpenCybeleLauncher.unavailableMessage());

        ParityLayout layout = ParityLayout.fromSystemProperties();
        ScenarioSpec spec = ScenarioSpecParser.parse(layout.scenariosDir().resolve(SCENARIO + ".yaml"));
        assertEquals(ContractLevel.STRICT, spec.contract(),
                "this scenario's whole value is that its golden is short enough to read and strong"
                        + " enough to compare line for line; weakening the contract throws that away");

        RunReport report = ScenarioRunner.usingDefaultLayout().run(spec, new OpenCybeleLauncher());
        List<String> raw = report.captured().lines();
        List<String> trace = report.normalized();
        ScenarioAssertions.assertAdapterContract(spec, raw, trace);

        assertEveryChannelAppears(trace);
        assertOnlyTrainsDie(trace);
        assertLeaveFollowsEnterReplyAfterTheFirstHop(raw);

        if (GoldenStore.recording()) {
            System.out.println("parity: recorded " + trace.size() + " line(s) to " + report.goldenFile());
        }
    }

    /**
     * The coverage claim, checked. {@code COVERAGE.md} maps CH-01…CH-15 to this scenario; if a
     * retune ever drops one — a topology with no branch stops producing {@code PATH_FIND}, a bound
     * moved earlier stops producing {@code TRAVEL_END} — the map goes stale silently and the next
     * person to read it is misled.
     */
    private static void assertEveryChannelAppears(List<String> trace) {
        Set<String> seen = new LinkedHashSet<>();
        for (String line : trace) {
            Matcher m = CANONICAL.matcher(line);
            if (m.matches()) {
                seen.add(m.group(2));
            }
        }
        List<String> missing = ALL_FIFTEEN_CHANNELS.stream().filter(ch -> !seen.contains(ch)).toList();
        assertTrue(missing.isEmpty(), "COVERAGE.md maps all fifteen channels to " + SCENARIO
                + ", and this run produced no line for: " + missing + ". Either the scenario was"
                + " retuned or the probe stopped emitting them; fix one or the other, do not"
                + " weaken this assertion.");
    }

    /**
     * INVENTORY AG-01..AG-04: <strong>only {@code Train} ever dies.</strong> {@code Agent.die()}
     * (Train.java:92) reaches the destructor at :123, which emits the final {@code LEAVE} and then
     * {@code TRAIN_STATE state=KILL}; the main agent, the stations and the road are created once
     * and never destroyed.
     *
     * <p>Both halves are asserted, because a port can get either wrong: a JADE port that leaves its
     * train agent alive produces no {@code KILL}, and one that calls {@code doDelete()} on the
     * static agents at the bound produces {@code KILL}s that have no business existing. The second
     * half is checked by there being <em>no</em> such line whose subject is not a train, and the
     * counting is per DISTINCT subject so that one train cannot satisfy a claim about two.
     */
    private static void assertOnlyTrainsDie(List<String> trace) {
        Set<String> died = new LinkedHashSet<>();
        Set<String> trains = new LinkedHashSet<>();
        for (String line : trace) {
            Matcher m = CANONICAL.matcher(line);
            if (!m.matches()) {
                continue;
            }
            if (m.group(1).startsWith("vl")) {
                trains.add(m.group(1));
            }
            if ("TRAIN_STATE".equals(m.group(2)) && "state=KILL".equals(m.group(5))) {
                died.add(m.group(1));
            }
        }
        assertEquals(trains, died, "every train in this scenario runs to its destination and dies,"
                + " and nothing else in the system ever does. Trains seen: " + trains
                + "; subjects that reported state=KILL: " + died);
        assertTrue(died.size() >= 2, "the scenario is sized for two complete lifecycles and only "
                + died.size() + " train(s) died — the bound or the arrival rate has moved");
    }

    /**
     * INVENTORY DEF-07, which {@code docs/defect-triage.md} classes (a) — deterministic, "the port
     * must reproduce it" — and pins by exactly this ordering: {@code Train.entered} sends
     * {@code LEAVE} to the OLD object <em>after</em> the new object has already incremented its
     * occupancy, so for every hop <strong>after the first</strong> the new object's
     * {@code ENTER_REPLY} precedes the old object's {@code LEAVE}.
     *
     * <p><strong>The first hop is the exception and is special-cased here</strong>, exactly as the
     * triage cell warns: {@code leaveObject} is a no-op while {@code position == null}
     * (Train.java:110-114), so a train's first {@code ENTER_REPLY} has no paired {@code LEAVE} and a
     * rule written literally from the cell false-positives on every train.
     *
     * <p>Asserted on the RAW stream. The normalizer sorts within a burst, and these two lines share
     * one, so the projected trace is the wrong place to look for an emission order.
     */
    private static void assertLeaveFollowsEnterReplyAfterTheFirstHop(List<String> raw) {
        Pattern reply = Pattern.compile("^(vl\\d+)\\|\\d+\\|ENTER_REPLY\\|([^|]+)\\|.*$");
        Pattern leave = Pattern.compile("^(vl\\d+)\\|\\d+\\|LEAVE\\|[^|]*\\|([^|]+)\\|.*$");
        Map<String, String> position = new LinkedHashMap<>();
        Map<String, Deque<String>> owed = new LinkedHashMap<>();
        List<String> violations = new ArrayList<>();
        int hopsChecked = 0;

        for (String line : raw) {
            Matcher r = reply.matcher(line);
            if (r.matches()) {
                String train = r.group(1);
                String previous = position.get(train);
                if (previous != null) {
                    // Hop 2 and later: this reply must be followed by a LEAVE for the OLD object.
                    owed.computeIfAbsent(train, k -> new ArrayDeque<>()).add(previous);
                    hopsChecked++;
                }
                position.put(train, r.group(2));
                continue;
            }
            Matcher l = leave.matcher(line);
            if (l.matches()) {
                String train = l.group(1);
                Deque<String> pending = owed.getOrDefault(train, new ArrayDeque<>());
                if (!pending.isEmpty()) {
                    String expected = pending.poll();
                    if (!expected.equals(l.group(2))) {
                        violations.add("expected " + train + " to LEAVE " + expected
                                + " but it left " + l.group(2) + ": " + line);
                    }
                } else if (!l.group(2).equals(position.get(train))) {
                    // The only LEAVE with nothing owed is the destructor's, for the CURRENT
                    // position — docs/defect-triage.md §4.2, which de-claims DEF-08 and records
                    // that this LEAVE is what balances the destination station's occupied++.
                    violations.add("unpaired LEAVE that is not the destructor's: " + line);
                }
            }
        }

        assertTrue(violations.isEmpty(), "DEF-07's ordering invariant is broken. docs/defect-triage.md"
                + " §3.1 classes it (a) — deterministic, the port must reproduce it — and pins it by"
                + " exactly this ordering: " + violations);
        assertTrue(hopsChecked >= 4, "only " + hopsChecked + " hop(s) after a train's first were"
                + " available to check DEF-07 against; the scenario has shrunk below what this"
                + " assertion needs to mean anything");
    }
}
