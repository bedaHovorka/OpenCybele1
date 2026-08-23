package cz.vutbr.fit.ags.railway.domain.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L1 for {@code Util.path} / {@code Util.pathDirection} — the routing the whole simulation
 * runs on ({@code util/Util.java:146-200}).
 */
class PathfindingTest {

    /**
     * <pre>
     *      A --e1-- B --e4-- E
     *      |                 |
     *      e2                e5
     *      |                 |
     *      C --e3----------- D
     * </pre>
     * A&rarr;D is two hops through C, three hops through B/E.
     */
    private static UnorientedGraph<String, String> diamond() {
        final UnorientedGraph<String, String> g = new HashMapGraph<String, String>();
        g.put("A", "B", "e1");
        g.put("A", "C", "e2");
        g.put("C", "D", "e3");
        g.put("B", "E", "e4");
        g.put("E", "D", "e5");
        return g;
    }

    @Test
    @DisplayName("PINNED: the DFS returns the FIRST path it finds, not the shortest one")
    void the_dfs_returns_the_first_path_not_the_shortest() {
        // A -> D is two hops via C (e2, e3) and three via B and E (e1, e4, e5). The search
        // descends into A's first candidate edge and returns the first complete path, so it
        // answers with the LONGER route. This is 2008 behaviour and the goldens were recorded
        // through it -- a port that runs Dijkstra or BFS routes trains differently and every
        // downstream vote, timetable and departure moves with them.
        //
        // The candidate order is itself pinned: HashMapGraph iterates through
        // Util.stableOrder, not through HashMap bucket order (#19, docs/iteration-order.md).
        final List<Object> path = Util.path(diamond(), "A", "D");
        assertEquals(Arrays.<Object>asList("A", "e1", "B", "e4", "E", "e5", "D"), path);
        assertTrue(path.size() > Arrays.asList("A", "e2", "C", "e3", "D").size(),
                "and it really is longer than the shortest route");
    }

    @Test
    @DisplayName("pathDirection is the first EDGE of that same first path")
    void pathDirection_is_the_first_edge_of_the_first_path() {
        assertEquals("e1", Util.pathDirection(diamond(), "A", "D"));
        assertEquals("e2", Util.pathDirection(diamond(), "A", "C"));
    }

    @Test
    @DisplayName("a directly connected target short-circuits: every edge of the start is tried first")
    void a_neighbour_is_found_without_descending() {
        assertEquals(Arrays.<Object>asList("A", "e1", "B"), Util.path(diamond(), "A", "B"));
    }

    @Test
    @DisplayName("the path alternates station, track, station and starts at the origin")
    void the_path_alternates_nodes_and_edges() {
        final List<Object> path = Util.path(diamond(), "A", "D");
        assertEquals("A", path.get(0));
        assertEquals("D", path.get(path.size() - 1));
        assertEquals(1, path.size() % 2, "node, edge, node, ... always an odd length");
    }

    @Test
    @DisplayName("an unreachable target yields null on a tree -- the caller must not assume a path")
    void an_unreachable_target_yields_null() {
        // Note the standing caveat (ScenarioConfig.checkReachability): privatePath forbids
        // only the edge it came down, not the nodes it has visited, so on a graph WITH a
        // cycle an unreachable target recurses until the stack runs out. This asserts the
        // acyclic case only, which is what the shipped topology is.
        final UnorientedGraph<String, String> g = new HashMapGraph<String, String>();
        g.put("A", "B", "e1");
        g.put("C", "D", "e2");
        assertNull(Util.path(g, "A", "D"));
    }

    @Test
    @DisplayName("the shipped topology is a tree, so its single route is also the shortest")
    void the_shipped_topology_has_exactly_one_route() {
        // The default sim.topology. Being a tree is why the first-path-not-shortest quirk
        // above never bit the original project -- and why a scenario author who adds a cycle
        // changes routing without touching a line of code.
        final UnorientedGraph<String, String> net = GraphStructureTest.shippedNet();
        // The route recorded in docs/iteration-order.md, NDT-04.
        assertEquals(Arrays.<Object>asList(
                "stA", "tr1", "stH", "tr2", "stG", "tr3", "stE", "tr6", "stF", "tr7", "stC"),
                Util.path(net, "stA", "stC"));
        assertEquals("tr1", Util.pathDirection(net, "stA", "stC"));
        assertNotNull(Util.path(net, "stB", "stF"));
    }
    @Test
    @DisplayName("PINNED: start == target -- path walks the whole cycle, pathDirection asserts")
    void the_two_entry_points_disagree_about_a_path_to_oneself() {
        // The two public entry points to the same search behave differently at the one input
        // the caller is most likely to try, and nothing pinned it before #37.
        //
        // Util.pathDirection opens with `assert ... && !start.equals(target)`, so with -ea --
        // which is how this project runs, see README "Assertions (-ea)" -- it is an
        // AssertionError. Util.path has no such guard: privatePath removes the target from
        // each candidate's node list, so A is never matched at depth 0, but it IS matched on
        // the way back round, and the answer is the entire cycle rather than [A] or null.
        assertThrows(AssertionError.class, () -> Util.pathDirection(diamond(), "A", "A"));

        assertEquals(Arrays.asList("A", "e1", "B", "e4", "E", "e5", "D", "e3", "C", "e2", "A"),
                Util.path(diamond(), "A", "A"));
    }

    @Test
    @DisplayName("a node that is not in the graph yields null rather than an empty path")
    void an_absent_start_node_yields_null() {
        assertNull(Util.path(diamond(), "Z", "D"));
    }

}
