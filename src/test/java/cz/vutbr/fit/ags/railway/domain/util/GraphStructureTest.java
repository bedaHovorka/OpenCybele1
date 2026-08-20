package cz.vutbr.fit.ags.railway.domain.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L1 for the graph the topology is held in — the {@link Doubleton} key, and the iteration
 * order every observable ordering in the simulation descends from (#19).
 */
class GraphStructureTest {

    @Test
    @DisplayName("a track's two endpoints are an unordered pair: stA-stB is the same edge as stB-stA")
    void the_edge_key_is_unordered() {
        final Doubleton<String> ab = new Doubleton<String>("stA", "stB");
        final Doubleton<String> ba = new Doubleton<String>("stB", "stA");
        assertEquals(ab, ba);
        assertEquals(ab.hashCode(), ba.hashCode());

        final UnorientedGraph<String, String> g = new HashMapGraph<String, String>();
        g.put("stA", "stB", "tr1");
        assertEquals("tr1", g.get("stB", "stA"));
        assertTrue(g.contains("stB", "stA"));
    }

    @Test
    @DisplayName("the order WITHIN a pair is the put() argument order -- it decides left/right station")
    void the_order_inside_a_pair_follows_the_put_arguments() {
        // RoadAgent's leftStation/rightStation come straight out of allNodesWithEdge, and
        // the direction symbols the GUI and the ROAD_STATE trace show depend on it. #19
        // claim 3; docs/iteration-order.md.
        // docs/iteration-order.md NDT-03: tr1 is (stA, stH) because that is the argument
        // order of put, and RoadAgent reads array[0] as its left station.
        assertEquals(Arrays.asList("stA", "stH"),
                new ArrayList<String>(shippedNet().allNodesWithEdge("tr1")));
        final UnorientedGraph<String, String> reversed = new HashMapGraph<String, String>();
        reversed.put("stH", "stA", "tr1");
        assertEquals(Arrays.asList("stH", "stA"),
                new ArrayList<String>(reversed.allNodesWithEdge("tr1")));
    }

    @Test
    @DisplayName("iteration order is defined by this code, not by HashMap buckets, and is stable")
    void iteration_order_is_deterministic() {
        // The order of values() is the order RoadAgents are created in, and the order of
        // get(node) is the order Util.path tries branches in. Both must not move with the JDK.
        final List<String> first = new ArrayList<String>(shippedNet().values());
        for (int i = 0; i < 20; i++) {
            assertEquals(first, new ArrayList<String>(shippedNet().values()));
        }
        // The exact order recorded in docs/iteration-order.md (NDT-02) for the shipped
        // topology -- it is NOT tr1..tr7, and it is not supposed to be.
        assertEquals(Arrays.asList("tr1", "tr4", "tr7", "tr5", "tr3", "tr6", "tr2"), first);
    }

    @Test
    @DisplayName("nodeSet is the agent-creation order, and it is deterministic too")
    void nodeSet_is_deterministic() {
        final List<String> first = new ArrayList<String>(shippedNet().nodeSet());
        for (int i = 0; i < 20; i++) {
            assertEquals(first, new ArrayList<String>(shippedNet().nodeSet()));
        }
        // docs/iteration-order.md, NDT-01: the order the Station agents are created in.
        assertEquals(Arrays.asList("stB", "stA", "stD", "stC", "stF", "stE", "stH", "stG"), first);
    }

    @Test
    @DisplayName("stableOrder ranks by the HashMap hash spread, and equal ranks keep insertion order")
    void stableOrder_is_a_total_order_over_the_rank() {
        final List<String> ordered = Util.stableOrder(Arrays.asList("stC", "stA", "stB"));
        int previous = Integer.MIN_VALUE;
        for (String s : ordered) {
            assertTrue(Util.orderRank(s) >= previous);
            previous = Util.orderRank(s);
        }
        assertEquals(0, Util.orderRank(null), "null ranks 0 rather than throwing");
    }

    /** The default {@code sim.topology}, in declaration order. */
    static UnorientedGraph<String, String> shippedNet() {
        final UnorientedGraph<String, String> net = new HashMapGraph<String, String>();
        net.put("stA", "stH", "tr1");
        net.put("stH", "stG", "tr2");
        net.put("stG", "stE", "tr3");
        net.put("stE", "stD", "tr4");
        net.put("stD", "stB", "tr5");
        net.put("stF", "stE", "tr6");
        net.put("stC", "stF", "tr7");
        return net;
    }
}
