/*
 * Projekt AGS 2007/08
 * FIT VUT Brno
 * 
 * Open Cybele 1
 * 
 * Bedrich Hovorka
 * xhovor07@stud.fit.vutbr.cz
 */
package cz.vutbr.fit.ags.xhovor07;

import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.Map.Entry;

import cz.vutbr.fit.ags.xhovor07.util.HashMapGraph;
import cz.vutbr.fit.ags.xhovor07.util.UnorientedGraph;

/**
 * All simulation parameters that used to be hardcoded literals, in one place.
 * <p>
 * Transport is <b>Java system properties</b> ({@code -Dsim.*}), optionally
 * pre-filled from a {@code java.util.Properties} file named by
 * {@code -Dsim.config=&lt;path&gt;}. A system property always wins over the file, so a
 * harness can take a scenario file and override one knob on the command line.
 * Deliberately dependency-free: no YAML, no injection framework.
 * <p>
 * <b>Every default reproduces the pre-#18 literal exactly</b>, so a run with no
 * {@code sim.*} property set behaves as it did before this class existed.
 * <p>
 * <b>Load eagerly, from {@code main}.</b> {@link #load()} is called by
 * {@link Main} on the main thread <em>before</em> the Cybele kernel starts,
 * because a configuration error must be loud. Cybele invokes agent handlers and
 * constructors reflectively and swallows the resulting
 * {@code InvocationTargetException} to stderr without changing the process exit
 * status (see {@code docs/assertion-triage.md} § Result 3) — a validation
 * failure raised inside an agent would be invisible to a scenario runner.
 * {@link #load()} also republishes every resolved value back into the system
 * properties, so any later {@link #get()} — on any thread, under any class
 * loader — resolves the identical values without re-reading the file.
 *
 * <p>
 * <b>Serializable, on purpose.</b> {@code cybele.kernel.Handler} extends
 * {@link Serializable}, and {@link RailwayMainAgent} — which holds one of these — is
 * itself handed to {@code Agent.createActivity} as a {@code Serializable[]} payload.
 * The baseline agent graph was serializable end to end; a non-serializable field here
 * would have quietly broken that. It is masked today by {@code Local;NoSerialization}
 * in {@code cybele.prop}, but this branch exists to prepare a JADE port where agents
 * really are serialized, and #16 may revisit that setting.
 * <p>
 * Serializable rather than {@code transient} plus a {@link #get()} at each use site,
 * because {@code transient} would make a deserialized agent silently re-resolve its
 * configuration from the <em>receiving</em> JVM's system properties — which, in the
 * remote case this would exist to support, are not set at all, so it would fall back to
 * the historical defaults without a word. Carrying the resolved values along with the
 * agent is the behaviour that cannot produce a golden recorded under a configuration
 * nobody chose.
 *
 * @author Bedrich Hovorka
 */
public final class ScenarioConfig implements Serializable {
    private static final long serialVersionUID = 1L;

    /** Property naming a {@code .properties} file to pre-fill the {@code sim.*} keys from. */
    public static final String KEY_CONFIG_FILE = "sim.config";

    /** Mean inter-arrival time of the train generator, in ms. */
    public static final String KEY_ARRIVAL_LAMBDA = "sim.arrival.lambdaMs";
    /** Delay before the generator's first fire, in ms. */
    public static final String KEY_ARRIVAL_FIRST_FIRE = "sim.arrival.firstFireMs";
    /** Origin/destination table, {@code from&gt;to} pairs separated by commas. */
    public static final String KEY_ARRIVAL_PAIRS = "sim.arrival.pairs";

    /**
     * Station voting window and penalty quantum, in ms.
     * Historically this was the very same constant as {@link #KEY_ARRIVAL_LAMBDA};
     * see the class Javadoc of {@link Station} and {@code docs/scenario-config.md}.
     */
    public static final String KEY_STATION_VOTE_WINDOW = "sim.station.voteWindowMs";
    /** Station capacities, {@code stX=n} entries separated by commas. */
    public static final String KEY_STATION_CAPACITIES = "sim.station.capacities";

    /** Network topology, {@code stX-stY:trZ} edges separated by commas. Order is significant. */
    public static final String KEY_TOPOLOGY = "sim.topology";
    /** Travel time per track, in seconds; {@code trZ=n} entries separated by commas. */
    public static final String KEY_ROAD_DELAYS = "sim.road.delaysSec";

    /** Initial value of the global Cybele clock, in ms. */
    public static final String KEY_CLOCK_START = "sim.clock.startMs";
    /** Initial pace of the global Cybele clock. */
    public static final String KEY_CLOCK_PACE = "sim.clock.pace";

    /** Toolbar pace buttons, {@code Label=pace} entries separated by commas. Order is significant. */
    public static final String KEY_GUI_PACES = "sim.gui.paces";
    /** Stations drawn left-to-right on the canvas' main line, separated by commas. */
    public static final String KEY_GUI_MAIN_LINE = "sim.gui.mainLine";
    /** Branch stations drawn above the main line, {@code stX:trZ} entries separated by commas. */
    public static final String KEY_GUI_BRANCHES = "sim.gui.branches";

    // ---- defaults: byte-identical in effect to the pre-#18 hardcoded literals ----

    static final String DEF_ARRIVAL_LAMBDA = "8500";          // Generator.LAMBDA
    static final String DEF_ARRIVAL_FIRST_FIRE = "1000";      // Generator ctor setTimer
    static final String DEF_ARRIVAL_PAIRS =
        "stA>stB,stA>stC,stB>stA,stB>stC,stC>stB,stC>stA";    // Generator.hhh
    static final String DEF_STATION_VOTE_WINDOW = "8500";     // was Generator.LAMBDA too
    static final String DEF_TOPOLOGY =
        "stA-stH:tr1,stH-stG:tr2,stG-stE:tr3,stE-stD:tr4,stD-stB:tr5,stF-stE:tr6,stC-stF:tr7";
    static final String DEF_STATION_CAPACITIES =
        "stA=6,stB=5,stC=2,stD=2,stE=5,stF=2,stG=3,stH=2";
    static final String DEF_ROAD_DELAYS =
        "tr1=1,tr2=1,tr3=5,tr4=2,tr5=3,tr6=4,tr7=3";
    static final String DEF_CLOCK_START = "0";
    static final String DEF_CLOCK_PACE = "1";
    static final String DEF_GUI_PACES = "Fast=8,Normal=1,Slow=0.3";
    static final String DEF_GUI_MAIN_LINE = "stA,stH,stG,stE,stD,stB";
    static final String DEF_GUI_BRANCHES = "stC:tr7,stF:tr6";

    private static final String[][] KEYS_AND_DEFAULTS = {
        {KEY_ARRIVAL_LAMBDA, DEF_ARRIVAL_LAMBDA},
        {KEY_ARRIVAL_FIRST_FIRE, DEF_ARRIVAL_FIRST_FIRE},
        {KEY_ARRIVAL_PAIRS, DEF_ARRIVAL_PAIRS},
        {KEY_STATION_VOTE_WINDOW, DEF_STATION_VOTE_WINDOW},
        {KEY_TOPOLOGY, DEF_TOPOLOGY},
        {KEY_STATION_CAPACITIES, DEF_STATION_CAPACITIES},
        {KEY_ROAD_DELAYS, DEF_ROAD_DELAYS},
        {KEY_CLOCK_START, DEF_CLOCK_START},
        {KEY_CLOCK_PACE, DEF_CLOCK_PACE},
        {KEY_GUI_PACES, DEF_GUI_PACES},
        {KEY_GUI_MAIN_LINE, DEF_GUI_MAIN_LINE},
        {KEY_GUI_BRANCHES, DEF_GUI_BRANCHES},
    };

    private static volatile ScenarioConfig instance;

    /** One {@code stX-stY:trZ} entry of {@link #KEY_TOPOLOGY}, in declaration order. */
    public static final class Edge implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String left;
        private final String right;
        private final String road;

        Edge(String left, String right, String road) {
            this.left = left;
            this.right = right;
            this.road = road;
        }

        /** @return left station */
        public String getLeft() { return left; }
        /** @return right station */
        public String getRight() { return right; }
        /** @return track name */
        public String getRoad() { return road; }
    }

    /** One {@code stX:trZ} entry of {@link #KEY_GUI_BRANCHES}. */
    public static final class Branch implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String station;
        private final String road;

        Branch(String station, String road) {
            this.station = station;
            this.road = road;
        }

        /** @return branch station */
        public String getStation() { return station; }
        /** @return branch track */
        public String getRoad() { return road; }
    }

    private final long arrivalLambdaMs;
    private final long arrivalFirstFireMs;
    private final Serializable[][] trainPairs;
    private final long stationVoteWindowMs;
    private final List<Edge> topology;
    private final Set<String> stationNames;
    private final Set<String> roadNames;
    private final Map<String, Integer> stationCapacities;
    private final Map<String, Long> roadDelaysSec;
    private final long clockStartMs;
    private final double clockPace;
    private final Map<String, Double> guiPaces;
    private final List<String> guiMainLine;
    private final List<Branch> guiBranches;
    private final List<String> guiLayoutWarnings;

    private ScenarioConfig(Properties p) {
        arrivalLambdaMs = positiveLong(p, KEY_ARRIVAL_LAMBDA);
        arrivalFirstFireMs = nonNegativeLong(p, KEY_ARRIVAL_FIRST_FIRE);
        stationVoteWindowMs = positiveLong(p, KEY_STATION_VOTE_WINDOW);
        clockStartMs = nonNegativeLong(p, KEY_CLOCK_START);
        clockPace = positiveDouble(p, KEY_CLOCK_PACE);

        topology = parseTopology(p);
        final Set<String> stations = new LinkedHashSet<String>();
        final Set<String> roads = new LinkedHashSet<String>();
        for (Edge e : topology) {
            stations.add(e.getLeft());
            stations.add(e.getRight());
            roads.add(e.getRoad());
        }

        stationNames = Collections.unmodifiableSet(stations);
        roadNames = Collections.unmodifiableSet(roads);
        stationCapacities = parseIntMap(p, KEY_STATION_CAPACITIES, stations, "station");
        roadDelaysSec = parseLongMap(p, KEY_ROAD_DELAYS, roads, "track");
        trainPairs = parsePairs(p, stations);
        guiPaces = parseDoubleMap(p, KEY_GUI_PACES);
        guiMainLine = parseList(p, KEY_GUI_MAIN_LINE);
        guiBranches = parseBranches(p);
        guiLayoutWarnings = Collections.unmodifiableList(checkGuiLayout(stations, roads));

        checkReachability();
    }

    // ------------------------------------------------------------------ loading

    /**
     * Resolve the configuration eagerly and publish it back into the system
     * properties. Call this from {@code main} before the Cybele kernel starts;
     * see the class Javadoc for why it must not be deferred into an agent.
     *
     * @return the resolved configuration
     * @throws IllegalArgumentException if any value is missing, malformed or inconsistent
     * @throws IllegalStateException if {@code sim.config} names a file that cannot be read
     */
    public static synchronized ScenarioConfig load() {
        final Properties file = new Properties();
        final String path = System.getProperty(KEY_CONFIG_FILE);
        if (path != null && path.length() > 0) {
            InputStream in = null;
            try {
                in = new FileInputStream(path);
                file.load(in);
            } catch (IOException e) {
                throw new IllegalStateException("Cannot read " + KEY_CONFIG_FILE + "=" + path, e);
            } finally {
                if (in != null) try { in.close(); } catch (IOException ignored) { /* EMPTY */ }
            }
            for (Object k : file.keySet()) {
                final String key = (String) k;
                if (KEY_CONFIG_FILE.equals(key)) {
                    throw new IllegalArgumentException(path + ": " + KEY_CONFIG_FILE
                            + " is a recognised key but cannot be set from inside a scenario file"
                            + " (a file cannot name the file it is being read from). Pass it as -D"
                            + KEY_CONFIG_FILE + "=<path>.");
                }
                if (!key.startsWith("sim.")) {
                    throw new IllegalArgumentException(path + ": unknown key '" + key
                            + "' (every scenario key starts with 'sim.')");
                }
                if (!isKnownKey(key)) {
                    throw new IllegalArgumentException(path + ": unknown key '" + key + "'. Known keys: "
                            + knownKeys());
                }
            }
        }
        checkNoUnknownSystemProperties();

        final Properties resolved = new Properties();
        for (String[] kd : KEYS_AND_DEFAULTS) {
            final String key = kd[0];
            String value = System.getProperty(key);          // -D wins
            if (value == null) value = file.getProperty(key); // then the scenario file
            if (value == null) value = kd[1];                 // then the historical literal
            resolved.setProperty(key, value.trim());
        }

        final ScenarioConfig cfg = new ScenarioConfig(resolved);
        // Republish so a later get() needs neither the file nor this class' static state.
        for (String[] kd : KEYS_AND_DEFAULTS) {
            System.setProperty(kd[0], resolved.getProperty(kd[0]));
        }
        instance = cfg;
        return cfg;
    }

    /**
     * The configuration in force. Cheap; safe to call from any agent thread.
     * If {@link #load()} has not run, this resolves from system properties and
     * defaults alone (never from a file) so it can never disagree with a
     * {@link #load()} that already published its values.
     *
     * @return the resolved configuration
     */
    public static ScenarioConfig get() {
        ScenarioConfig local = instance;
        if (local == null) {
            synchronized (ScenarioConfig.class) {
                local = instance;
                if (local == null) {
                    final Properties resolved = new Properties();
                    for (String[] kd : KEYS_AND_DEFAULTS) {
                        resolved.setProperty(kd[0], System.getProperty(kd[0], kd[1]).trim());
                    }
                    local = new ScenarioConfig(resolved);
                    instance = local;
                }
            }
        }
        return local;
    }

    /**
     * A mistyped {@code -Dsim.*} flag must not be silently ignored.
     * <p>
     * The scenario-file path was guarded from the start, but {@code -D} was not — and
     * {@code -D} is the path the parity harness ([#12]/[#13]) drives through
     * {@code ProcessBuilder}, and the path every example in the documentation uses. An
     * ignored typo there produces a golden recorded under a configuration nobody
     * intended, while the run looks perfectly clean: the banner prints the default and
     * nothing complains. Same rule as for files, then — an unrecognised {@code sim.*}
     * key is an error.
     */
    private static void checkNoUnknownSystemProperties() {
        for (Object k : System.getProperties().keySet()) {
            if (!(k instanceof String)) continue;
            final String key = (String) k;
            if (!key.startsWith("sim.")) continue;
            if (KEY_CONFIG_FILE.equals(key) || isKnownKey(key)) continue;
            throw new IllegalArgumentException("unknown system property '-D" + key
                    + "'. Known keys: " + knownKeys() + ", " + KEY_CONFIG_FILE);
        }
    }

    private static boolean isKnownKey(String key) {
        for (String[] kd : KEYS_AND_DEFAULTS) {
            if (kd[0].equals(key)) return true;
        }
        return false;
    }

    private static String knownKeys() {
        final StringBuilder sb = new StringBuilder();
        for (String[] kd : KEYS_AND_DEFAULTS) {
            if (sb.length() > 0) sb.append(", ");
            sb.append(kd[0]);
        }
        return sb.toString();
    }

    // ------------------------------------------------------------------ parsing

    private static String[] split(String value) {
        if (value == null || value.trim().length() == 0) return new String[0];
        final String[] raw = value.split(",");
        final List<String> out = new ArrayList<String>(raw.length);
        for (String s : raw) {
            final String t = s.trim();
            if (t.length() > 0) out.add(t);
        }
        return out.toArray(new String[out.size()]);
    }

    private static String require(Properties p, String key) {
        final String v = p.getProperty(key);
        if (v == null || v.trim().length() == 0) {
            throw new IllegalArgumentException(key + " must not be empty");
        }
        return v.trim();
    }

    private static long positiveLong(Properties p, String key) {
        final long v = parseLong(p, key);
        if (v <= 0) throw new IllegalArgumentException(key + " must be > 0, was " + v);
        return v;
    }

    private static long nonNegativeLong(Properties p, String key) {
        final long v = parseLong(p, key);
        if (v < 0) throw new IllegalArgumentException(key + " must be >= 0, was " + v);
        return v;
    }

    private static long parseLong(Properties p, String key) {
        final String v = require(p, key);
        try {
            return Long.parseLong(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be an integer, was '" + v + "'", e);
        }
    }

    private static double positiveDouble(Properties p, String key) {
        final String v = require(p, key);
        final double d;
        try {
            d = Double.parseDouble(v);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(key + " must be a number, was '" + v + "'", e);
        }
        if (!(d > 0)) throw new IllegalArgumentException(key + " must be > 0, was " + v);
        return d;
    }

    private static List<String> parseList(Properties p, String key) {
        final List<String> out = new ArrayList<String>();
        Collections.addAll(out, split(require(p, key)));
        return Collections.unmodifiableList(out);
    }

    private static List<Edge> parseTopology(Properties p) {
        final String[] entries = split(require(p, KEY_TOPOLOGY));
        if (entries.length == 0) throw new IllegalArgumentException(KEY_TOPOLOGY + " must declare at least one edge");
        final List<Edge> out = new ArrayList<Edge>(entries.length);
        final Set<String> roads = new LinkedHashSet<String>();
        final Set<String> pairs = new LinkedHashSet<String>();
        for (String entry : entries) {
            final int colon = entry.indexOf(':');
            final int dash = entry.indexOf('-');
            if (dash <= 0 || colon <= dash + 1 || colon == entry.length() - 1) {
                throw new IllegalArgumentException(KEY_TOPOLOGY + ": expected 'stX-stY:trZ', got '" + entry + "'");
            }
            final String left = entry.substring(0, dash).trim();
            final String right = entry.substring(dash + 1, colon).trim();
            final String road = entry.substring(colon + 1).trim();
            if (left.equals(right)) {
                throw new IllegalArgumentException(KEY_TOPOLOGY + ": self-loop '" + entry + "'");
            }
            if (!roads.add(road)) {
                throw new IllegalArgumentException(KEY_TOPOLOGY + ": duplicate track name '" + road + "'");
            }
            final String canonical = (left.compareTo(right) < 0) ? left + "|" + right : right + "|" + left;
            if (!pairs.add(canonical)) {
                throw new IllegalArgumentException(KEY_TOPOLOGY + ": duplicate edge between "
                        + left + " and " + right + " (the network is a simple graph)");
            }
            out.add(new Edge(left, right, road));
        }
        return Collections.unmodifiableList(out);
    }

    private static Map<String, String> parseAssignments(Properties p, String key) {
        final Map<String, String> out = new LinkedHashMap<String, String>();
        for (String entry : split(require(p, key))) {
            final int eq = entry.indexOf('=');
            if (eq <= 0 || eq == entry.length() - 1) {
                throw new IllegalArgumentException(key + ": expected 'name=value', got '" + entry + "'");
            }
            final String name = entry.substring(0, eq).trim();
            if (out.put(name, entry.substring(eq + 1).trim()) != null) {
                throw new IllegalArgumentException(key + ": duplicate entry for '" + name + "'");
            }
        }
        return out;
    }

    @SuppressWarnings("boxing")
    private static Map<String, Integer> parseIntMap(Properties p, String key, Set<String> expected, String what) {
        final Map<String, String> raw = parseAssignments(p, key);
        checkKeySet(key, raw.keySet(), expected, what);
        final Map<String, Integer> out = new LinkedHashMap<String, Integer>();
        for (Entry<String, String> e : raw.entrySet()) {
            final int v;
            try {
                v = Integer.parseInt(e.getValue());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(key + ": " + e.getKey() + " must be an integer, was '"
                        + e.getValue() + "'", ex);
            }
            if (v < 1) throw new IllegalArgumentException(key + ": " + e.getKey() + " must be >= 1, was " + v);
            out.put(e.getKey(), v);
        }
        return Collections.unmodifiableMap(out);
    }

    @SuppressWarnings("boxing")
    private static Map<String, Long> parseLongMap(Properties p, String key, Set<String> expected, String what) {
        final Map<String, String> raw = parseAssignments(p, key);
        checkKeySet(key, raw.keySet(), expected, what);
        final Map<String, Long> out = new LinkedHashMap<String, Long>();
        for (Entry<String, String> e : raw.entrySet()) {
            final long v;
            try {
                v = Long.parseLong(e.getValue());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(key + ": " + e.getKey() + " must be an integer, was '"
                        + e.getValue() + "'", ex);
            }
            if (v < 0) throw new IllegalArgumentException(key + ": " + e.getKey() + " must be >= 0, was " + v);
            out.put(e.getKey(), v);
        }
        return Collections.unmodifiableMap(out);
    }

    @SuppressWarnings("boxing")
    private static Map<String, Double> parseDoubleMap(Properties p, String key) {
        final Map<String, String> raw = parseAssignments(p, key);
        final Map<String, Double> out = new LinkedHashMap<String, Double>();
        for (Entry<String, String> e : raw.entrySet()) {
            final double v;
            try {
                v = Double.parseDouble(e.getValue());
            } catch (NumberFormatException ex) {
                throw new IllegalArgumentException(key + ": " + e.getKey() + " must be a number, was '"
                        + e.getValue() + "'", ex);
            }
            if (!(v > 0)) throw new IllegalArgumentException(key + ": " + e.getKey() + " must be > 0, was " + v);
            out.put(e.getKey(), v);
        }
        return Collections.unmodifiableMap(out);
    }

    private static void checkKeySet(String key, Set<String> actual, Set<String> expected, String what) {
        for (String name : expected) {
            if (!actual.contains(name)) {
                throw new IllegalArgumentException(key + ": no entry for " + what + " '" + name
                        + "' declared in " + KEY_TOPOLOGY);
            }
        }
        for (String name : actual) {
            if (!expected.contains(name)) {
                throw new IllegalArgumentException(key + ": '" + name + "' is not a " + what
                        + " in " + KEY_TOPOLOGY + " " + expected);
            }
        }
    }

    private static Serializable[][] parsePairs(Properties p, Set<String> stations) {
        final String[] entries = split(require(p, KEY_ARRIVAL_PAIRS));
        if (entries.length == 0) {
            throw new IllegalArgumentException(KEY_ARRIVAL_PAIRS + " must declare at least one origin/destination pair");
        }
        final Serializable[][] out = new Serializable[entries.length][];
        for (int i = 0; i < entries.length; i++) {
            final int gt = entries[i].indexOf('>');
            if (gt <= 0 || gt == entries[i].length() - 1) {
                throw new IllegalArgumentException(KEY_ARRIVAL_PAIRS + ": expected 'from>to', got '" + entries[i] + "'");
            }
            final String from = entries[i].substring(0, gt).trim();
            final String to = entries[i].substring(gt + 1).trim();
            if (from.equals(to)) {
                throw new IllegalArgumentException(KEY_ARRIVAL_PAIRS + ": '" + entries[i] + "' has the same origin and destination");
            }
            requireStation(KEY_ARRIVAL_PAIRS, from, stations);
            requireStation(KEY_ARRIVAL_PAIRS, to, stations);
            out[i] = new Serializable[]{from, to};
        }
        return out;
    }

    private static void requireStation(String key, String name, Set<String> stations) {
        if (!stations.contains(name)) {
            throw new IllegalArgumentException(key + ": '" + name + "' is not a station in "
                    + KEY_TOPOLOGY + " " + stations);
        }
    }

    private static List<Branch> parseBranches(Properties p) {
        final String value = p.getProperty(KEY_GUI_BRANCHES, "");
        final List<Branch> out = new ArrayList<Branch>();
        for (String entry : split(value)) {
            final int colon = entry.indexOf(':');
            if (colon <= 0 || colon == entry.length() - 1) {
                throw new IllegalArgumentException(KEY_GUI_BRANCHES + ": expected 'stX:trZ', got '" + entry + "'");
            }
            out.add(new Branch(entry.substring(0, colon).trim(), entry.substring(colon + 1).trim()));
        }
        return Collections.unmodifiableList(out);
    }

    // ------------------------------------------------------------------ validation

    /**
     * The canvas can only draw one straight main line plus branch stubs. A
     * configured topology it cannot represent is <b>not</b> an error — the GUI is
     * outside the behavioural contract — but it must never be drawn as though it
     * were correct. Every mismatch found here is printed by {@link Main} and
     * painted on the canvas in red.
     */
    private List<String> checkGuiLayout(Set<String> stations, Set<String> roads) {
        final List<String> warnings = new ArrayList<String>();
        final Set<String> drawnStations = new LinkedHashSet<String>();
        final Set<String> drawnRoads = new LinkedHashSet<String>();

        final UnorientedGraph<String, String> net = buildNet();

        for (String station : guiMainLine) {
            if (!stations.contains(station)) {
                warnings.add(KEY_GUI_MAIN_LINE + " names '" + station + "', absent from " + KEY_TOPOLOGY);
            }
            if (!drawnStations.add(station)) {
                warnings.add(KEY_GUI_MAIN_LINE + " draws '" + station + "' twice");
            }
        }
        for (int i = 0; i < guiMainLine.size() - 1; i++) {
            final String a = guiMainLine.get(i);
            final String b = guiMainLine.get(i + 1);
            final String road = net.get(a, b);
            if (road == null) {
                warnings.add(KEY_GUI_MAIN_LINE + ": " + a + " and " + b
                        + " are adjacent on the canvas but not connected in " + KEY_TOPOLOGY);
            } else {
                drawnRoads.add(road);
            }
        }
        for (Branch b : guiBranches) {
            if (!stations.contains(b.getStation())) {
                warnings.add(KEY_GUI_BRANCHES + " names station '" + b.getStation() + "', absent from " + KEY_TOPOLOGY);
            } else if (!drawnStations.add(b.getStation())) {
                warnings.add(KEY_GUI_BRANCHES + " draws '" + b.getStation() + "' twice");
            }
            if (!roads.contains(b.getRoad())) {
                warnings.add(KEY_GUI_BRANCHES + " names track '" + b.getRoad() + "', absent from " + KEY_TOPOLOGY);
            } else if (!drawnRoads.add(b.getRoad())) {
                warnings.add(KEY_GUI_BRANCHES + " draws '" + b.getRoad() + "' twice");
            }
        }

        final Set<String> missingStations = new LinkedHashSet<String>(stations);
        missingStations.removeAll(drawnStations);
        if (!missingStations.isEmpty()) {
            warnings.add("stations not drawn: " + missingStations);
        }
        final Set<String> missingRoads = new LinkedHashSet<String>(roads);
        missingRoads.removeAll(drawnRoads);
        if (!missingRoads.isEmpty()) {
            warnings.add("tracks not drawn: " + missingRoads);
        }
        return warnings;
    }

    /**
     * Every configured origin/destination pair must actually be routable, otherwise
     * {@code Planning.planTrain} would fail deep inside an agent where the failure is
     * swallowed. Checked here, on the main thread, where it is loud.
     * <p>
     * <b>Deliberately a local BFS rather than {@code Util.path}.</b> {@code Util}'s DFS
     * forbids only the edge it just came down, not the nodes it has already visited, so
     * on a graph with a cycle it never terminates when the target is unreachable — it
     * dies with a {@code StackOverflowError} instead of naming the offending pair. The
     * historical topology is a tree, so that never mattered; {@code sim.topology} now
     * accepts cycles (only self-loops, duplicate track names and duplicate edges are
     * rejected), so a scenario author can reach it. {@code Util} itself is left exactly
     * as it is: it is legacy behaviour under golden, and this check has no business
     * changing it.
     */
    private void checkReachability() {
        final Map<String, Set<String>> adjacency = new LinkedHashMap<String, Set<String>>();
        for (Edge e : topology) {
            neighbours(adjacency, e.getLeft()).add(e.getRight());
            neighbours(adjacency, e.getRight()).add(e.getLeft());
        }
        for (Serializable[] pair : trainPairs) {
            final String from = (String) pair[0];
            final String to = (String) pair[1];
            if (!reachable(adjacency, from, to)) {
                throw new IllegalArgumentException(KEY_ARRIVAL_PAIRS + ": no path from " + from
                        + " to " + to + " in " + KEY_TOPOLOGY);
            }
        }
    }

    private static Set<String> neighbours(Map<String, Set<String>> adjacency, String node) {
        Set<String> out = adjacency.get(node);
        if (out == null) {
            out = new LinkedHashSet<String>();
            adjacency.put(node, out);
        }
        return out;
    }

    private static boolean reachable(Map<String, Set<String>> adjacency, String from, String to) {
        final Set<String> seen = new LinkedHashSet<String>();
        final LinkedList<String> frontier = new LinkedList<String>();
        seen.add(from);
        frontier.add(from);
        while (!frontier.isEmpty()) {
            final String node = frontier.removeFirst();
            if (node.equals(to)) return true;
            for (String next : neighbours(adjacency, node)) {
                if (seen.add(next)) frontier.addLast(next);
            }
        }
        return false;
    }

    /**
     * A fresh graph built from {@link #getTopology()} in declaration order.
     * {@link RailwayMainAgent} uses this so the network exists in exactly one place.
     *
     * @return the configured network
     */
    public UnorientedGraph<String, String> buildNet() {
        final UnorientedGraph<String, String> net = new HashMapGraph<String, String>();
        for (Edge e : topology) {
            net.put(e.getLeft(), e.getRight(), e.getRoad());
        }
        return net;
    }

    // ------------------------------------------------------------------ accessors

    /** @return mean inter-arrival time of the generator, in ms */
    public long getArrivalLambdaMs() { return arrivalLambdaMs; }
    /** @return delay before the generator's first fire, in ms */
    public long getArrivalFirstFireMs() { return arrivalFirstFireMs; }
    /** @return origin/destination table; index order is significant (the RNG indexes it) */
    public Serializable[][] getTrainPairs() {
        final Serializable[][] copy = new Serializable[trainPairs.length][];
        for (int i = 0; i < trainPairs.length; i++) copy[i] = trainPairs[i].clone();
        return copy;
    }
    /** @return station voting window / penalty quantum, in ms */
    public long getStationVoteWindowMs() { return stationVoteWindowMs; }
    /** @return network edges in declaration order */
    public List<Edge> getTopology() { return topology; }
    /** @return every station named by {@link #KEY_TOPOLOGY}, in declaration order */
    public Set<String> getStationNames() { return stationNames; }
    /** @return every track named by {@link #KEY_TOPOLOGY}, in declaration order */
    public Set<String> getRoadNames() { return roadNames; }
    /** @return capacity per station */
    public Map<String, Integer> getStationCapacities() { return stationCapacities; }
    /** @return travel time per track, in seconds */
    public Map<String, Long> getRoadDelaysSec() { return roadDelaysSec; }
    /** @return initial clock value, in ms */
    public long getClockStartMs() { return clockStartMs; }
    /** @return initial clock pace */
    public double getClockPace() { return clockPace; }
    /** @return toolbar pace buttons, in declaration order */
    public Map<String, Double> getGuiPaces() { return guiPaces; }
    /** @return stations on the canvas' main line, left to right */
    public List<String> getGuiMainLine() { return guiMainLine; }
    /** @return branch stubs drawn above the main line */
    public List<Branch> getGuiBranches() { return guiBranches; }
    /** @return human-readable mismatches between the configured topology and what the canvas can draw */
    public List<String> getGuiLayoutWarnings() { return guiLayoutWarnings; }

    /**
     * The resolved configuration, one {@code key = value} line per parameter, in a
     * form that can be pasted back into a scenario file.
     *
     * @return the full resolved configuration
     */
    public String describe() {
        final StringBuilder sb = new StringBuilder();
        for (String[] kd : KEYS_AND_DEFAULTS) {
            final String value = System.getProperty(kd[0], kd[1]);
            sb.append(kd[0]).append(" = ").append(value);
            if (!value.equals(kd[1])) sb.append("    # default: ").append(kd[1]);
            sb.append('\n');
        }
        return sb.toString();
    }
}
