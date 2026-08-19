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

/**
 * Verdicts a canonical parity trace (issue #20). Not part of the simulation — this lives in
 * the {@code tools} source set alongside {@code SeedInterleavingCheck} and is never on the
 * application's classpath.
 *
 * <p>It answers the one question {@code docs/trace-format.md} is a contract for and that no
 * amount of reading the probe's source can answer: <b>did a run actually exercise all
 * fifteen channels, and are the four request/reply round-trips visible as matched pairs?</b>
 * #23's L3 scenarios and #32's "LEAVE-before-ENTER ordering preserved" criterion both rest
 * on the request leg being in the trace, and an earlier draft of #20 tapped six channels and
 * declared them complete.
 *
 * <pre>
 *   ./gradlew installDist
 *   OPENCYBELE_OPTS="-Djava.awt.headless=true \
 *       -Dsim.config=scenarios/short-bounded.properties \
 *       -Dsim.trace.enabled=true" build/install/opencybele/bin/opencybele &gt; trace.txt
 *   java -cp build/classes/java/tools TraceCheck trace.txt
 * </pre>
 *
 * Exit 0 on PASS, 1 on FAIL, 2 on a usage or I/O error. Reads stdin when given no file.
 */
public final class TraceCheck {

    /** The fifteen event tokens, in docs/INVENTORY.md CH-01..CH-15 order. */
    private static final String[] EVENTS = {
        "VOTE_REQUEST", "VOTE_RESULT", "ENTER", "LEAVE", "START", "ENTER_REPLY",
        "TRAVEL_END", "TRAVEL_START", "PATH_FIND_REPLY", "STATION_INFO", "ROAD_STATE",
        "TRAIN_STATE", "PLAN_TRAIN", "VOTE", "PATH_FIND",
    };

    /** Request leg, reply leg, and whether every request must have a reply. */
    private static final String[][] ROUND_TRIPS = {
        {"VOTE_REQUEST", "VOTE",            "exact"},
        {"VOTE",         "VOTE_RESULT",     "exact"},
        {"ENTER",        "ENTER_REPLY",     "atLeast"},   // a queued train is admitted by
                                                          // Station.leave with no new ENTER
        {"PATH_FIND",    "PATH_FIND_REPLY", "exact"},
        {"TRAVEL_START", "TRAVEL_END",      "atMost"},    // trains still travelling at the bound
    };

    private TraceCheck() { }

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
                if (f.length != 7) { malformed++; continue; }
                if (!f[1].matches("-?\\d+")) { malformed++; continue; }
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

        boolean pass = true;
        System.out.println("canonical lines = " + lines.size()
                + (malformed > 0 ? "   (" + malformed + " MALFORMED)" : ""));
        if (malformed > 0) pass = false;
        System.out.println();
        System.out.println("[1] all fifteen channels present");
        final List<String> missing = new ArrayList<String>();
        for (String e : EVENTS) {
            final int n = counts.get(e).intValue();
            if (n == 0) missing.add(e);
            System.out.printf("    %-16s %6d%s%n", e, Integer.valueOf(n), n == 0 ? "   MISSING" : "");
        }
        if (!missing.isEmpty()) {
            pass = false;
            System.out.println("    => MISSING: " + missing);
        }

        System.out.println();
        System.out.println("[2] round-trips");
        for (String[] rt : ROUND_TRIPS) {
            final int a = counts.get(rt[0]).intValue();
            final int b = counts.get(rt[1]).intValue();
            final boolean ok = "exact".equals(rt[2]) ? a == b
                             : "atLeast".equals(rt[2]) ? b >= a
                             : b <= a;
            if (!ok) pass = false;
            System.out.printf("    %-14s -> %-16s %6d %s %-6d %s%n", rt[0], rt[1],
                    Integer.valueOf(a),
                    "exact".equals(rt[2]) ? "==" : "atLeast".equals(rt[2]) ? "<=" : ">=",
                    Integer.valueOf(b), ok ? "ok" : "FAIL");
        }

        System.out.println();
        System.out.println("[3] format invariants");
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

}
