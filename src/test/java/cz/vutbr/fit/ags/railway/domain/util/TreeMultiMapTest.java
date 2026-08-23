package cz.vutbr.fit.ags.railway.domain.util;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.NoSuchElementException;

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

    @Test
    @DisplayName("lastKey on an empty map throws NoSuchElementException -- DEF-18 at its own level")
    void lastKey_on_an_empty_map_throws() {
        // StationScheduleTest pins the same throw through StationSchedule.computeDifference,
        // where the guard makes it unreachable. This pins it HERE, where the guard is not, so
        // a port that gave TreeMultiMap an empty-safe lastKey would fail at the source rather
        // than silently change what DEF-18 is about.
        assertThrows(NoSuchElementException.class,
                () -> new TreeMultiMap<Long, String>().lastKey());
    }

    @Test
    @DisplayName("a slot holds a train once however often it is planned -- the slot is a set")
    void a_slot_holds_each_value_only_once() {
        // Load-bearing: StationSchedule.size() counts values(), and StationSchedule.addToPlan
        // puts straight into this map. Re-planning one train into one slot must therefore NOT
        // make the station look one train fuller than it is.
        final TreeMultiMap<Long, String> t = new TreeMultiMap<Long, String>();
        // Equal but NOT identical, because the train names Station.addToPlan feeds in are
        // built at runtime, not interned literals -- so the de-duplication has to be by
        // equals(), and a slot backed by an identity set would not do it.
        t.put(Long.valueOf(100), "vl0");
        t.put(Long.valueOf(100), new String("vl0"));
        assertEquals(List.of("vl0"), List.copyOf(t.values()));

        // ... but the same train at two DIFFERENT slots is two entries, which is what makes
        // removeValue have to sweep every slot.
        t.put(Long.valueOf(200), "vl0");
        assertEquals(2, t.values().size());
    }

    @Test
    @DisplayName("removing a value that was never put changes nothing and drops no slot")
    void removing_an_absent_value_is_a_no_op() {
        final TreeMultiMap<Long, String> t = new TreeMultiMap<Long, String>();
        t.put(Long.valueOf(100), "vl0");
        t.put(Long.valueOf(200), "vl1");

        t.removeValue("vl9");

        assertEquals(List.of("vl0", "vl1"), List.copyOf(t.values()));
        assertEquals(Long.valueOf(200), t.lastKey(), "and the empty-slot sweep left both slots");
    }

}
