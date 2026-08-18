import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import cz.vutbr.fit.ags.xhovor07.ScenarioConfig;
import cz.vutbr.fit.ags.xhovor07.util.Doubleton;
import cz.vutbr.fit.ags.xhovor07.util.UnorientedGraph;
import cz.vutbr.fit.ags.xhovor07.util.Util;

/**
 * OrderLock - self-verdicting lock for the four iteration orders of issue #19
 * (INVENTORY.md NDT-01..NDT-04).
 *
 * Order.java DUMPS today's orders; OrderLock ASSERTS them. Every expected value
 * below was recorded from the pre-#19 code (HashMap/HashSet iteration order) with
 * Order.java / the all-pairs dump, on OpenJDK 21.0.11. #19 replaced that incidental
 * order with an explicit rule (Util.stableOrder); this probe is the evidence that
 * the rule reproduces the old order exactly rather than merely being deterministic.
 *
 * Claim 3 (leftStation/rightStation) is deliberately NOT a production change: the
 * order inside a Doubleton has always been the put(first, second, value) argument
 * order, never hash order. This probe locks that so a future "cleanup" cannot flip
 * the TRAVEL_LEFT/TRAVEL_RIGHT symbol.
 *
 * Default package, no Cybele dependency: it needs ScenarioConfig and util only.
 * Exit code 0 = every order matches, 1 = at least one drifted.
 */
public class OrderLock {

    private static final int REPEATS = 100;

    private static final String EXPECTED_NODES =
        "[stB, stA, stD, stC, stF, stE, stH, stG]";

    private static final String EXPECTED_ROADS =
        "[tr1, tr4, tr7, tr5, tr3, tr6, tr2]";

    /** road -> [first, second] as allNodesWithEdge returns them; equals the put() argument order. */
    private static final String[] EXPECTED_ENDPOINTS = {
        "tr1 [stA, stH]",
        "tr2 [stH, stG]",
        "tr3 [stG, stE]",
        "tr4 [stE, stD]",
        "tr5 [stD, stB]",
        "tr6 [stF, stE]",
        "tr7 [stC, stF]"
    };

    /** station -> incident roads, in the order Util.privatePath tries them. */
    private static final String[] EXPECTED_ADJACENCY = {
        "stA [tr1]",
        "stB [tr5]",
        "stC [tr7]",
        "stD [tr4, tr5]",
        "stE [tr4, tr3, tr6]",
        "stF [tr7, tr6]",
        "stG [tr3, tr2]",
        "stH [tr1, tr2]"
    };

    /** every ordered origin/destination pair: full route and the first-hop direction. */
    private static final String[] EXPECTED_PATHS = {
        "stA->stB [stA, tr1, stH, tr2, stG, tr3, stE, tr4, stD, tr5, stB] DIR=tr1",
        "stA->stC [stA, tr1, stH, tr2, stG, tr3, stE, tr6, stF, tr7, stC] DIR=tr1",
        "stA->stD [stA, tr1, stH, tr2, stG, tr3, stE, tr4, stD] DIR=tr1",
        "stA->stE [stA, tr1, stH, tr2, stG, tr3, stE] DIR=tr1",
        "stA->stF [stA, tr1, stH, tr2, stG, tr3, stE, tr6, stF] DIR=tr1",
        "stA->stG [stA, tr1, stH, tr2, stG] DIR=tr1",
        "stA->stH [stA, tr1, stH] DIR=tr1",
        "stB->stA [stB, tr5, stD, tr4, stE, tr3, stG, tr2, stH, tr1, stA] DIR=tr5",
        "stB->stC [stB, tr5, stD, tr4, stE, tr6, stF, tr7, stC] DIR=tr5",
        "stB->stD [stB, tr5, stD] DIR=tr5",
        "stB->stE [stB, tr5, stD, tr4, stE] DIR=tr5",
        "stB->stF [stB, tr5, stD, tr4, stE, tr6, stF] DIR=tr5",
        "stB->stG [stB, tr5, stD, tr4, stE, tr3, stG] DIR=tr5",
        "stB->stH [stB, tr5, stD, tr4, stE, tr3, stG, tr2, stH] DIR=tr5",
        "stC->stA [stC, tr7, stF, tr6, stE, tr3, stG, tr2, stH, tr1, stA] DIR=tr7",
        "stC->stB [stC, tr7, stF, tr6, stE, tr4, stD, tr5, stB] DIR=tr7",
        "stC->stD [stC, tr7, stF, tr6, stE, tr4, stD] DIR=tr7",
        "stC->stE [stC, tr7, stF, tr6, stE] DIR=tr7",
        "stC->stF [stC, tr7, stF] DIR=tr7",
        "stC->stG [stC, tr7, stF, tr6, stE, tr3, stG] DIR=tr7",
        "stC->stH [stC, tr7, stF, tr6, stE, tr3, stG, tr2, stH] DIR=tr7",
        "stD->stA [stD, tr4, stE, tr3, stG, tr2, stH, tr1, stA] DIR=tr4",
        "stD->stB [stD, tr5, stB] DIR=tr5",
        "stD->stC [stD, tr4, stE, tr6, stF, tr7, stC] DIR=tr4",
        "stD->stE [stD, tr4, stE] DIR=tr4",
        "stD->stF [stD, tr4, stE, tr6, stF] DIR=tr4",
        "stD->stG [stD, tr4, stE, tr3, stG] DIR=tr4",
        "stD->stH [stD, tr4, stE, tr3, stG, tr2, stH] DIR=tr4",
        "stE->stA [stE, tr3, stG, tr2, stH, tr1, stA] DIR=tr3",
        "stE->stB [stE, tr4, stD, tr5, stB] DIR=tr4",
        "stE->stC [stE, tr6, stF, tr7, stC] DIR=tr6",
        "stE->stD [stE, tr4, stD] DIR=tr4",
        "stE->stF [stE, tr6, stF] DIR=tr6",
        "stE->stG [stE, tr3, stG] DIR=tr3",
        "stE->stH [stE, tr3, stG, tr2, stH] DIR=tr3",
        "stF->stA [stF, tr6, stE, tr3, stG, tr2, stH, tr1, stA] DIR=tr6",
        "stF->stB [stF, tr6, stE, tr4, stD, tr5, stB] DIR=tr6",
        "stF->stC [stF, tr7, stC] DIR=tr7",
        "stF->stD [stF, tr6, stE, tr4, stD] DIR=tr6",
        "stF->stE [stF, tr6, stE] DIR=tr6",
        "stF->stG [stF, tr6, stE, tr3, stG] DIR=tr6",
        "stF->stH [stF, tr6, stE, tr3, stG, tr2, stH] DIR=tr6",
        "stG->stA [stG, tr2, stH, tr1, stA] DIR=tr2",
        "stG->stB [stG, tr3, stE, tr4, stD, tr5, stB] DIR=tr3",
        "stG->stC [stG, tr3, stE, tr6, stF, tr7, stC] DIR=tr3",
        "stG->stD [stG, tr3, stE, tr4, stD] DIR=tr3",
        "stG->stE [stG, tr3, stE] DIR=tr3",
        "stG->stF [stG, tr3, stE, tr6, stF] DIR=tr3",
        "stG->stH [stG, tr2, stH] DIR=tr2",
        "stH->stA [stH, tr1, stA] DIR=tr1",
        "stH->stB [stH, tr2, stG, tr3, stE, tr4, stD, tr5, stB] DIR=tr2",
        "stH->stC [stH, tr2, stG, tr3, stE, tr6, stF, tr7, stC] DIR=tr2",
        "stH->stD [stH, tr2, stG, tr3, stE, tr4, stD] DIR=tr2",
        "stH->stE [stH, tr2, stG, tr3, stE] DIR=tr2",
        "stH->stF [stH, tr2, stG, tr3, stE, tr6, stF] DIR=tr2",
        "stH->stG [stH, tr2, stG] DIR=tr2"
    };

    private static int failures;

    public static void main(String[] args) {
        final ScenarioConfig config = ScenarioConfig.get();

        for (int repeat = 0; repeat < REPEATS; repeat++) {
            final UnorientedGraph<String, String> net = config.buildNet();
            final boolean report = (repeat == 0);

            check(report, "NDT-01 nodeSet (Station creation order)",
                    EXPECTED_NODES, net.nodeSet().toString());

            check(report, "NDT-02 values (RoadAgent creation order)",
                    EXPECTED_ROADS, net.values().toString());

            checkEndpoints(report, config, net);
            checkAdjacency(report, net);
            checkPaths(report, net);
        }

        checkDoubleton();

        if (failures == 0) {
            System.out.println("OrderLock: PASS - all four orders match the pre-#19 baseline,"
                    + " stable over " + REPEATS + " freshly built graphs");
        } else {
            System.out.println("OrderLock: FAIL - " + failures + " mismatch(es);"
                    + " an iteration order drifted from the golden baseline");
            System.exit(1);
        }
    }

    private static void checkEndpoints(boolean report, ScenarioConfig config,
                                       UnorientedGraph<String, String> net) {
        // literal pin for the default topology
        final List<String> roads = new ArrayList<String>(net.values());
        final List<String> sorted = new ArrayList<String>(roads);
        java.util.Collections.sort(sorted);
        final List<String> actual = new ArrayList<String>();
        for (String road : sorted) {
            actual.add(road + " " + new ArrayList<String>(net.allNodesWithEdge(road)));
        }
        checkList(report, "NDT-03 allNodesWithEdge (leftStation, rightStation)",
                EXPECTED_ENDPOINTS, actual);

        // structural form of the same rule: endpoint order == put() argument order,
        // whatever sim.topology happens to be
        for (ScenarioConfig.Edge edge : config.getTopology()) {
            final List<String> ends = new ArrayList<String>(net.allNodesWithEdge(edge.getRoad()));
            final String expected = "[" + edge.getLeft() + ", " + edge.getRight() + "]";
            check(report, "NDT-03 rule: " + edge.getRoad() + " keeps put() argument order",
                    expected, ends.toString());
        }
    }

    private static void checkAdjacency(boolean report, UnorientedGraph<String, String> net) {
        final List<String> stations = new ArrayList<String>(net.nodeSet());
        java.util.Collections.sort(stations);
        final List<String> actual = new ArrayList<String>();
        for (String station : stations) {
            final Collection<String> roads = net.get(station);
            actual.add(station + " " + new ArrayList<String>(roads));
        }
        checkList(report, "NDT-04a get(node) (order privatePath tries candidate edges)",
                EXPECTED_ADJACENCY, actual);
    }

    private static void checkPaths(boolean report, UnorientedGraph<String, String> net) {
        final List<String> stations = new ArrayList<String>(net.nodeSet());
        java.util.Collections.sort(stations);
        final List<String> actual = new ArrayList<String>();
        for (String from : stations) {
            for (String to : stations) {
                if (from.equals(to)) continue;
                actual.add(from + "->" + to + " " + Util.path(net, from, to)
                        + " DIR=" + Util.pathDirection(net, from, to));
            }
        }
        checkList(report, "NDT-04b path/pathDirection for every origin-destination pair",
                EXPECTED_PATHS, actual);
    }

    private static void checkDoubleton() {
        final Doubleton<String> ah = new Doubleton<String>("stA", "stH");
        final Doubleton<String> ha = new Doubleton<String>("stH", "stA");
        check(true, "Doubleton(stA,stH).hashCode()", "228359", String.valueOf(ah.hashCode()));
        check(true, "Doubleton commutativity of hashCode",
                String.valueOf(ah.hashCode()), String.valueOf(ha.hashCode()));
        check(true, "Doubleton iterates first then second", "[stA, stH]",
                new ArrayList<String>(ah).toString());
        check(true, "Doubleton iterates first then second (swapped)", "[stH, stA]",
                new ArrayList<String>(ha).toString());
        check(true, "Util.orderRank is the documented hash spread",
                String.valueOf("stA".hashCode() ^ ("stA".hashCode() >>> 16)),
                String.valueOf(Util.orderRank("stA")));
    }

    private static void checkList(boolean report, String what, String[] expected, List<String> actual) {
        if (expected.length != actual.size()) {
            fail(what, "" + expected.length + " entries", "" + actual.size() + " entries");
            return;
        }
        for (int i = 0; i < expected.length; i++) {
            check(report, what + " [" + i + "]", expected[i], actual.get(i));
        }
    }

    private static void check(boolean report, String what, String expected, String actual) {
        if (expected.equals(actual)) {
            if (report) System.out.println("  ok   " + what + " = " + actual);
        } else {
            fail(what, expected, actual);
        }
    }

    private static void fail(String what, String expected, String actual) {
        failures++;
        System.out.println("  FAIL " + what);
        System.out.println("       expected: " + expected);
        System.out.println("       actual:   " + actual);
    }
}
