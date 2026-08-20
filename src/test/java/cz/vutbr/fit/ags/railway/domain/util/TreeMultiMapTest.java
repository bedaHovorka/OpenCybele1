package cz.vutbr.fit.ags.railway.domain.util;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.Arrays;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * L1 for the sorted multimap the station timetable is built on.
 */
class TreeMultiMapTest {

    private static TreeMultiMap<Long, String> timetable() {
        final TreeMultiMap<Long, String> t = new TreeMultiMap<Long, String>();
        t.put(Long.valueOf(300), "vl2");
        t.put(Long.valueOf(100), "vl0");
        t.put(Long.valueOf(200), "vl1");
        t.put(Long.valueOf(100), "vl3");   // two trains in the same slot
        return t;
    }

    @Test
    @DisplayName("values come out in key order, and a slot may hold more than one train")
    void values_are_in_key_order_and_a_key_may_repeat() {
        assertEquals(Arrays.asList("vl0", "vl3", "vl1", "vl2"),
                new ArrayList<String>(timetable().values()));
        assertEquals(Long.valueOf(300), timetable().lastKey());
    }

    @Test
    @DisplayName("subMultiMap is half-open [from, to) and tailSubMultiMap is inclusive [from, ..)")
    void the_range_views_have_the_bounds_the_voting_rule_assumes() {
        assertEquals(Arrays.asList("vl0", "vl3", "vl1"),
                new ArrayList<String>(timetable().subMultiMap(Long.valueOf(100), Long.valueOf(300)).values()));
        assertEquals(Arrays.asList("vl1", "vl2"),
                new ArrayList<String>(timetable().tailSubMultiMap(Long.valueOf(200)).values()));
    }

    @Test
    @DisplayName("removeValue drops the train from every slot, and empties the slot itself")
    void removing_a_value_drops_empty_slots() {
        final TreeMultiMap<Long, String> t = timetable();
        t.removeValue("vl0");
        assertEquals(Arrays.asList("vl3", "vl1", "vl2"), new ArrayList<String>(t.values()));
        t.removeValue("vl3");
        assertEquals(Arrays.asList("vl1", "vl2"), new ArrayList<String>(t.values()));
        assertEquals(0, t.subMultiMap(Long.valueOf(0), Long.valueOf(200)).values().size(),
                "slot 100 is gone entirely, not left behind empty");
    }
}
