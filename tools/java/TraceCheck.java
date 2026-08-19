import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.Reader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Verdicts a canonical parity trace (issue #20). Not part of the simulation — this lives in
 * the {@code tools} source set alongside {@code SeedInterleavingCheck} and is never on the
 * application's classpath.
 *
 * <p>It answers the question {@code docs/trace-format.md} is a contract for and that no
 * amount of reading the probe's source can answer: <b>is this trace complete?</b> #23's L3
 * scenarios and #32's "LEAVE-before-ENTER ordering preserved" criterion both rest on the
 * request leg being present, and an earlier draft of #20 tapped six channels and declared
 * them complete.
 *
 * <pre>
 *   ./gradlew traceCheck                       # runs the simulation and checks its trace
 *   java -cp build/classes/java/tools TraceCheck trace.txt   # or check an existing one
 * </pre>
 *
 * Exit 0 on PASS, 1 on FAIL, 2 on a usage or I/O error. Reads stdin when given no file.
 *
 * <h2>Why the aggregate counts are not the check</h2>
 *
 * The first version of this class was five count relations and nothing else, and every one
 * of them is blind to the failure it was written to catch: an incomplete trace loses a
 * train's lines from <em>both</em> sides of a relation at once, so the relation still holds.
 * Measured on a real 3661-line trace — dropping every line of <b>20 trains</b> passed;
 * dropping the fifteen opening {@code STATION_INFO}/{@code ROAD_STATE} lines, i.e.
 * simulating the subscription barrier failing, passed; dropping the four per-train channels
 * of six trains, i.e. exactly the look-ahead failure the probe exists to avoid, passed.
 * That is the "lock that cannot fail" shape this project has already rejected three times.
 *
 * <p>Section [3] is the answer, and it is structural rather than aggregate:
 *
 * <ul>
 * <li><b>Train ids are contiguous from {@code vl0}.</b> {@code Generator} names trains
 *     {@code "vl" + index} with no gaps, so a missing train is a hole in the sequence — the
 *     one thing a balanced count cannot hide.</li>
 * <li><b>Every announced train has a {@code generated} line.</b> {@code PLAN_TRAIN} is a
 *     bare global channel that cannot be missed; the per-train channels can. The two counts
 *     being equal is what says the probe was subscribed in time.</li>
 * <li><b>Every static object's first state line is its initial state.</b> A station's
 *     constructor sends {@code occupied=0} and a track's sends {@code FREE} before any train
 *     exists, so a first line that says anything else means the opening of the run is
 *     missing — which is exactly what a failed subscription barrier looks like.</li>
 * </ul>
 */
public final class TraceCheck {

    /** The fifteen event tokens, in docs/INVENTORY.md CH-01..CH-15 order. */
    private static final String[] EVENTS = {
        "VOTE_REQUEST", "VOTE_RESULT", "ENTER", "LEAVE", "START", "ENTER_REPLY",
        "TRAVEL_END", "TRAVEL_START", "PATH_FIND_REPLY", "STATION_INFO", "ROAD_STATE",
        "TRAIN_STATE", "PLAN_TRAIN", "VOTE", "PATH_FIND",
    };

    /** Request leg, reply leg, relation. */
    private static final String[][] RELATIONS = {
        {"VOTE_REQUEST", "VOTE",            "=="},
        {"VOTE",         "VOTE_RESULT",     "=="},
        {"ENTER",        "ENTER_REPLY",     "<="},   // a queued train is admitted by
                                                     // Station.leave with no new ENTER
        {"PATH_FIND",    "PATH_FIND_REPLY", "=="},
        {"TRAVEL_START", "TRAVEL_END",      ">="},   // still travelling when the bound fired
        {"LEAVE",        "ENTER_REPLY",     "<="},   // a train leaves only what it entered
    };

    private static boolean pass = true;

    private TraceCheck() { }

    private static void fail(String why) {
        pass = false;
        System.out.println("    => " + why);
    }

    public static void main(String[] args) {
        if (args.length > 1) {
            System.err.println("usage: TraceCheck [trace-file]   (stdin when omitted)");
            System.exit(2);
        }
        final List<String[]> lines = new ArrayList<String[]>();
        int malformed = 0;
        try (Reader in = args.length == 1 ? new FileReader(args[0])
                                          : new InputStreamReader(System.in)) {
            final BufferedReader r = new BufferedReader(in);
            for (String line = r.readLine(); line != null; line = r.readLine()) {
                if (line.indexOf('|') < 0) continue;                 // not a canonical line
                final String[] f = line.split("\\|", -1);
                if (f.length != 7 || !f[1].matches("-?\\d+")) { malformed++; continue; }
                lines.add(f);
            }
        } catch (IOException e) {
            System.err.println("TraceCheck: " + e);
            System.exit(2);
            return;
        }

        final Map<String, Integer> counts = new LinkedHashMap<String, Integer>();
        for (String e : EVENTS) counts.put(e, Integer.valueOf(0));
        final Set<String> unknown = new LinkedHashSet<String>();
        int badPerformative = 0;
        for (String[] f : lines) {
            final Integer c = counts.get(f[2]);
            if (c == null) { unknown.add(f[2]); continue; }
            counts.put(f[2], Integer.valueOf(c.intValue() + 1));
            if (!"-".equals(f[5])) badPerformative++;
        }

        System.out.println("canonical lines = " + lines.size()
                + (malformed > 0 ? "   (" + malformed + " MALFORMED)" : ""));
        if (malformed > 0) fail(malformed + " lines are not seven |-separated fields with a numeric tick");
        if (lines.isEmpty()) fail("no canonical lines at all — was sim.trace.enabled=true?");

        section("[1] all fifteen channels present");
        final List<String> missing = new ArrayList<String>();
        for (String e : EVENTS) {
            final int n = counts.get(e).intValue();
            if (n == 0) missing.add(e);
            System.out.printf("    %-16s %6d%s%n", e, Integer.valueOf(n), n == 0 ? "   MISSING" : "");
        }
        if (!missing.isEmpty()) fail("MISSING: " + missing);

        section("[2] round-trips (aggregate — necessary, nowhere near sufficient; see [3])");
        for (String[] rt : RELATIONS) {
            final int a = counts.get(rt[0]).intValue();
            final int b = counts.get(rt[1]).intValue();
            final boolean ok = "==".equals(rt[2]) ? a == b : "<=".equals(rt[2]) ? a <= b : a >= b;
            System.out.printf("    %-14s %s %-16s %6d %s %-6d %s%n", rt[0], rt[2], rt[1],
                    Integer.valueOf(a), rt[2], Integer.valueOf(b), ok ? "ok" : "FAIL");
            if (!ok) pass = false;
        }

        section("[3] completeness — the checks a balanced count cannot satisfy");
        checkTrains(lines);
        checkInitialStates(lines);

        section("[4] per-train ordering (#32: LEAVE never precedes the entry it follows)");
        checkOrdering(lines);

        section("[5] format invariants");
        System.out.println("    performative always '-'   " + (badPerformative == 0
                ? "ok" : "FAIL (" + badPerformative + " lines)"));
        if (badPerformative > 0) pass = false;
        System.out.println("    no unknown event tokens   " + (unknown.isEmpty()
                ? "ok" : "FAIL " + unknown));
        if (!unknown.isEmpty()) pass = false;

        System.out.println();
        System.out.println("RESULT: " + (pass ? "PASS" : "FAIL"));
        System.exit(pass ? 0 : 1);
    }

    private static void section(String title) {
        System.out.println();
        System.out.println(title);
    }

    /** Contiguous train ids, and one {@code generated} line per announced train. */
    private static void checkTrains(List<String[]> lines) {
        final Set<Integer> announced = new TreeSet<Integer>();
        final Set<Integer> generated = new TreeSet<Integer>();
        for (String[] f : lines) {
            if ("PLAN_TRAIN".equals(f[2])) {
                final int i = trainIndex(f[0]);
                if (i >= 0) announced.add(Integer.valueOf(i));
            } else if ("TRAIN_STATE".equals(f[2]) && f[6].endsWith(" generated")) {
                final int i = trainIndex(f[0]);
                if (i >= 0) generated.add(Integer.valueOf(i));
            }
        }
        System.out.println("    trains announced on PLAN_TRAIN   " + announced.size());
        System.out.println("    trains with a 'generated' line   " + generated.size());

        final List<Integer> gaps = new ArrayList<Integer>();
        int expect = 0;
        for (Integer i : announced) {
            while (expect < i.intValue()) gaps.add(Integer.valueOf(expect++));
            expect++;
        }
        System.out.println("    ids contiguous from vl0          "
                + (gaps.isEmpty() ? "ok" : "FAIL"));
        if (!gaps.isEmpty()) fail("train ids missing from the PLAN_TRAIN sequence: " + trunc(gaps));

        final Set<Integer> noGenerated = new TreeSet<Integer>(announced);
        noGenerated.removeAll(generated);
        final Set<Integer> noAnnounce = new TreeSet<Integer>(generated);
        noAnnounce.removeAll(announced);
        System.out.println("    every train has both             "
                + (noGenerated.isEmpty() && noAnnounce.isEmpty() ? "ok" : "FAIL"));
        if (!noGenerated.isEmpty()) {
            fail("announced but never 'generated' (the probe subscribed too late, or the"
                    + " trace was truncated): " + trunc(noGenerated));
        }
        if (!noAnnounce.isEmpty()) fail("'generated' but never announced: " + trunc(noAnnounce));
    }

    /**
     * Every station and track that appears anywhere in the trace must have a state line, and
     * its <b>first</b> one must be the initial state its constructor sends.
     */
    private static void checkInitialStates(List<String[]> lines) {
        final Set<String> objects = new TreeSet<String>();
        final Map<String, String> firstState = new TreeMap<String, String>();
        for (String[] f : lines) {
            if (f[0].startsWith("st") || f[0].startsWith("tr")) objects.add(f[0]);
            if (f[4].startsWith("st") || f[4].startsWith("tr")) objects.add(f[4]);
            if ("STATION_INFO".equals(f[2]) || "ROAD_STATE".equals(f[2])) {
                if (!firstState.containsKey(f[0])) firstState.put(f[0], f[6]);
            }
        }
        final List<String> noState = new ArrayList<String>();
        final List<String> badFirst = new ArrayList<String>();
        for (String o : objects) {
            final String s = firstState.get(o);
            if (s == null) { noState.add(o); continue; }
            final boolean ok = o.startsWith("st") ? s.startsWith("occupied=0,")
                                                  : "state=FREE".equals(s);
            if (!ok) badFirst.add(o + " -> " + s);
        }
        System.out.println("    static objects seen              " + objects.size());
        System.out.println("    each has a state line            "
                + (noState.isEmpty() ? "ok" : "FAIL"));
        if (!noState.isEmpty()) fail("no STATION_INFO/ROAD_STATE at all for: " + noState);
        System.out.println("    first state line is the initial  "
                + (badFirst.isEmpty() ? "ok" : "FAIL"));
        if (!badFirst.isEmpty()) {
            fail("the opening of the run is missing (subscription barrier failed, or the"
                    + " trace was cut at the front): " + badFirst);
        }
    }

    /**
     * The issue's {@code ENTER -> ENTER_REPLY -> LEAVE} chain, per train. A train's first
     * {@code LEAVE} is sent from {@code Train.entered}, so it can never precede that train's
     * first {@code ENTER_REPLY}, which in turn can never precede its first {@code ENTER}.
     */
    private static void checkOrdering(List<String[]> lines) {
        final Map<String, int[]> firstAt = new LinkedHashMap<String, int[]>();
        for (int i = 0; i < lines.size(); i++) {
            final String[] f = lines.get(i);
            if (trainIndex(f[0]) < 0) continue;
            final int slot = "ENTER".equals(f[2]) ? 0
                           : "ENTER_REPLY".equals(f[2]) ? 1
                           : "LEAVE".equals(f[2]) ? 2 : -1;
            if (slot < 0) continue;
            int[] a = firstAt.get(f[0]);
            if (a == null) { a = new int[] {-1, -1, -1}; firstAt.put(f[0], a); }
            if (a[slot] < 0) a[slot] = i;
        }
        final List<String> bad = new ArrayList<String>();
        int checked = 0;
        for (Map.Entry<String, int[]> e : firstAt.entrySet()) {
            final int[] a = e.getValue();
            if (a[0] < 0 || a[1] < 0) continue;          // never entered anything; nothing to order
            checked++;
            if (a[1] < a[0]) bad.add(e.getKey() + ": ENTER_REPLY before ENTER");
            if (a[2] >= 0 && a[2] < a[1]) bad.add(e.getKey() + ": LEAVE before its first ENTER_REPLY");
        }
        System.out.println("    trains with an entry chain       " + checked);
        System.out.println("    ENTER < ENTER_REPLY <= LEAVE     " + (bad.isEmpty() ? "ok" : "FAIL"));
        if (!bad.isEmpty()) fail("out-of-order entry chain: " + trunc(bad));
    }

    private static int trainIndex(String name) {
        if (name == null || !name.startsWith("vl") || name.length() < 3) return -1;
        for (int i = 2; i < name.length(); i++) {
            if (name.charAt(i) < '0' || name.charAt(i) > '9') return -1;
        }
        try {
            return Integer.parseInt(name.substring(2));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String trunc(java.util.Collection<?> c) {
        final List<Object> l = new ArrayList<Object>(c);
        return l.size() <= 12 ? l.toString()
                : l.subList(0, 12) + " ... and " + (l.size() - 12) + " more";
    }
}
