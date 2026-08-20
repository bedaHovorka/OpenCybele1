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

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
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

    /** Below this many hops after a train's first, the hop assertions stop meaning anything. */
    private static final int MIN_HOPS = 4;

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
        assertEveryHopIsPairedAndEveryObjectReleasedOnce(raw);

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
     * Every hop of every train, checked against the route the train's own payloads describe — and
     * every object it visited released exactly once, the destination by the destructor.
     *
     * <h2>What this replaced, and why (#72)</h2>
     *
     * <p>This method used to assert DEF-07's <em>emission order</em> directly: "for every hop after
     * the first, the new object's {@code ENTER_REPLY} precedes the old object's {@code LEAVE}", read
     * off the raw stream. {@code docs/defect-triage.md} §3.1's classification of DEF-07 as (a) —
     * deterministic, the port must reproduce it — is <strong>correct and unchanged</strong>: the
     * <em>send</em> order inside {@code Train.entered} really is fixed. The observable was the
     * problem.
     *
     * <ul>
     *   <li>The probe stamps and prints when it <em>handles</em> a message, not when the sender sent
     *       it ({@code docs/trace-format.md}, "{@code tick} — simulated, and read at handling
     *       time"), and lines sharing a tick "are emitted in whatever order the kernel delivered
     *       them". On an uncontended hop the reply comes back in the same tick, so DEF-07's two
     *       lines are exactly such a pair. Measured over 60 captures of this scenario, 2 print an
     *       {@code ENTER_REPLY} before the {@code ENTER} it answers, which throws the old pairing
     *       queue permanently out of step and produces two violations from one inverted line.</li>
     *   <li>The projected trace cannot carry it either. {@code burst-order} sorts within a burst, so
     *       {@code parity-tests/golden/opencybele-lifecycle.txt} lists {@code LEAVE|vl0|stB}
     *       <em>before</em> {@code LEAVE|vl0|tr1} — the opposite of the order the application
     *       emitted them in. The claim that the ordering "is in every golden" was false.</li>
     * </ul>
     *
     * <p>DEF-07 is therefore pinned where the wait separates its two lines into different bursts and
     * the golden can hold it: {@code OpenCybeleCongestionIT}'s queued hop. What is asserted here
     * instead is <strong>strictly stronger than the surviving half of the old check</strong>, and
     * costs no order at all:
     *
     * <ol>
     *   <li>the route reconstructed from {@code ENTER.position} is a single unbroken chain from
     *       {@code position=null} to {@code target} — the old version never checked the route;</li>
     *   <li>every visited object answered exactly once, and its {@code ENTER_REPLY.next} <em>agrees
     *       with that route</em>, {@code null} only at the destination. That is the direction and
     *       routing contract, on the one field no projection rule touches, and it was previously
     *       captured by the regex and thrown away;</li>
     *   <li>every visited object was released exactly once and nothing else was — the conservation
     *       {@code docs/defect-triage.md} §4.2 measured when it de-claimed DEF-08 ("199 {@code LEAVE}
     *       records, zero {@code (train, object)} pairs with more than one"), which nothing in the
     *       suite asserted until now. The destructor's {@code LEAVE} is identified <strong>positively
     *       </strong>, as the one for the destination of a train that reached {@code state=KILL},
     *       rather than by the old "the position happens to match" fallback that silently absorbed
     *       a mis-paired line.</li>
     * </ol>
     *
     * <p>A train still in flight at the run's bound is held only to (1) and to the part of (2) its
     * route reaches; the end-of-route claims apply to trains that completed.
     */
    private static void assertEveryHopIsPairedAndEveryObjectReleasedOnce(List<String> raw) {
        TrainTrace causal = TrainTrace.of(raw);
        assertTrue(causal.malformed().isEmpty(), "the causal reconstruction is unsound for this"
                + " capture, so nothing below means what it says: " + causal.malformed());

        List<String> violations = new ArrayList<>();
        int hops = 0;

        for (String train : causal.trains()) {
            violations.addAll(causal.routeProblems(train));
            List<String> route = causal.route(train);
            if (route.isEmpty()) {
                violations.add(train + " never entered anything");
                continue;
            }
            hops += route.size() - 1;
            boolean completed = causal.completed(train);
            Optional<String> target = causal.target(train);

            for (int i = 0; i < route.size(); i++) {
                String object = route.get(i);
                boolean lastVisited = i + 1 == route.size();
                String expected = lastVisited ? "null" : route.get(i + 1);
                Optional<TrainTrace.Admission> admission = causal.admission(train, object);
                if (admission.isEmpty()) {
                    if (!lastVisited || completed) {
                        violations.add(train + " left " + object + " behind, but that object never"
                                + " sent it an ENTER_REPLY");
                    }
                    continue;
                }
                if ((!lastVisited || completed) && !expected.equals(admission.get().next())) {
                    violations.add(train + "'s ENTER_REPLY from " + object + " says next="
                            + admission.get().next() + ", but its own ENTER payloads route it to "
                            + expected + " (route " + route + ")");
                }
            }

            if (completed) {
                String arrivedAt = route.get(route.size() - 1);
                if (target.isPresent() && !target.get().equals(arrivedAt)) {
                    violations.add(train + " died at " + arrivedAt + " but its ENTER payloads name "
                            + target.get() + " as the target");
                }
                for (String object : route) {
                    int released = causal.leaves(train, object).size();
                    if (released != 1) {
                        violations.add(train + " released " + object + " " + released
                                + " time(s); every visited object is released exactly once — the"
                                + " hops by Train.entered, the destination by the destructor"
                                + " (docs/defect-triage.md §4.2)");
                    }
                }
            }
            String prefix = train + "@";
            for (String pair : causal.leftPairs()) {
                if (pair.startsWith(prefix) && !route.contains(pair.substring(prefix.length()))) {
                    violations.add(train + " sent a LEAVE for " + pair.substring(prefix.length())
                            + ", which is not on its route " + route);
                }
            }
        }

        assertTrue(violations.isEmpty(), "a train's hops do not match the route its own ENTER and"
                + " ENTER_REPLY payloads describe, or an object was released the wrong number of"
                + " times. DEF-07's ordering is pinned by OpenCybeleCongestionIT, where the queued"
                + " wait separates the two lines into different bursts; what is checked here is the"
                + " pairing itself, which no ordering can perturb: " + violations);
        assertTrue(hops >= MIN_HOPS, "only " + hops + " hop(s) after a train's first were available"
                + " to check; the scenario has shrunk below what this assertion needs to mean"
                + " anything");
    }
}
