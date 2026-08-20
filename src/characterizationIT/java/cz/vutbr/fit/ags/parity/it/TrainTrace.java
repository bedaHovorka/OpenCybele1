package cz.vutbr.fit.ags.parity.it;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A raw capture re-read as <strong>causal structure</strong> rather than as a sequence of lines
 * (<a href="https://github.com/bedaHovorka/OpenCybele1/issues/72">#72</a>).
 *
 * <h2>Why this class exists</h2>
 *
 * <p>Several property assertions in this package have to read the RAW stream, because the quantity
 * they are about does not survive {@code CanonicalTraceNormalizer}. Three of them then went on to
 * read <em>the order of the raw lines</em>, and that is not a fact about the application at all:
 *
 * <blockquote>{@code docs/trace-format.md}: field 2 is the clock <em>as the probe reads it when it
 * handles the message</em>, and "lines that share a tick are emitted in whatever order the kernel
 * delivered them".</blockquote>
 *
 * <p>Measured on this tree, {@code docs/raw-assertion-audit.md} §2: over 100 standalone captures of
 * {@code opencybele-lifecycle} and {@code opencybele-congestion}, <strong>4 contain a pair of lines
 * printed in the opposite order to the one that caused them</strong> — an {@code ENTER_REPLY}
 * printed before the very {@code ENTER} it answers, or a {@code LEAVE} printed before the
 * {@code ENTER_REPLY} whose handler sent it. Every one of those pairs shares a tick or is 8 ms
 * apart. An assertion that reads a property off that order therefore fails a few percent of the
 * time while every golden comparison passes, which is exactly the shape #72 was opened for.
 *
 * <h2>What is read instead</h2>
 *
 * <p>Everything below is derived from <strong>payload content</strong>, keyed by
 * {@code (train, object)}, and from <strong>tick intervals whose width is seconds</strong>. No
 * method here depends on the position of a line in the list, and none of them compares two ticks
 * that the application emits within one burst.
 *
 * <ul>
 *   <li>{@code ENTER|<train>|<object>|…|position=<P>,target=<D>} gives the edge {@code P -> object}
 *       and the destination. The whole visit sequence is the chain of those edges starting at
 *       {@code position=null} — a reconstruction that would be identical if the lines were shuffled.
 *   <li>{@code ENTER_REPLY|<object>|<train>|…|object=<object>,next=<N>} gives the object's answer.
 *       On a road that {@code next} <em>is</em> the travel direction: {@code RoadAgent.acceptTrain}
 *       replies with {@code rightStation} going right and {@code leftStation} going left.
 *   <li>{@code LEAVE|<train>|<object>} gives the release, counted per {@code (train, object)}.
 * </ul>
 *
 * <p><strong>Not a normalizer.</strong> This class never rewrites a line and is never used to
 * produce anything that is compared against a golden. It exists so that a property assertion can
 * name the causal fact it means instead of a scheduling accident that usually coincides with it.
 */
final class TrainTrace {

    /** {@code agent|tick|ENTER|<train>|<object>|-|train=…,position=<from>,target=<dest>} */
    private static final Pattern ENTER = Pattern.compile(
            "^(vl\\d+)\\|(\\d+)\\|ENTER\\|[^|]*\\|([^|]+)\\|[^|]*\\|train=vl\\d+,position=([^,]*),target=(.*)$");

    /** {@code agent|tick|ENTER_REPLY|<object>|<train>|-|object=<object>,next=<next>} */
    private static final Pattern ENTER_REPLY = Pattern.compile(
            "^(vl\\d+)\\|(\\d+)\\|ENTER_REPLY\\|([^|]+)\\|[^|]*\\|[^|]*\\|object=[^,]*,next=(.*)$");

    /** {@code agent|tick|LEAVE|<train>|<object>|-|train=<train>} */
    private static final Pattern LEAVE = Pattern.compile(
            "^(vl\\d+)\\|(\\d+)\\|LEAVE\\|[^|]*\\|([^|]+)\\|.*$");

    /** {@code agent|tick|TRAIN_STATE|<train>|Main|-|state=KILL} — the destructor ran. */
    private static final Pattern KILLED = Pattern.compile(
            "^(vl\\d+)\\|\\d+\\|TRAIN_STATE\\|[^|]*\\|[^|]*\\|[^|]*\\|state=KILL$");

    /** A train asking one object to admit it. */
    record Request(String train, String object, String from, String target, long at) {
    }

    /** The object's answer, carrying the position it will hand the train on to. */
    record Admission(String train, String object, String next, long at) {
    }

    /**
     * One train's complete stay on one road, assembled from the three lines that bound it.
     *
     * @param from        the station the train came from ({@code ENTER.position})
     * @param to          the station it will be handed on to ({@code ENTER_REPLY.next}) — on a road
     *                    this is the travel direction, and it is the only unerased witness of it
     * @param requestedAt tick of the {@code ENTER}
     * @param admittedAt  tick of the {@code ENTER_REPLY}
     * @param leftAt      tick of the {@code LEAVE}, or {@link Long#MAX_VALUE} if the run's bound
     *                    cut the traversal short
     */
    record Traversal(String train, String road, String from, String to, long requestedAt,
            long admittedAt, long leftAt) {

        /** How long the road made the train wait — 0–32 ms admitted at once, 424–448 ms queued. */
        long waited() {
            return admittedAt - requestedAt;
        }

        /** True when this traversal is the exact reverse of {@code other}'s. */
        boolean opposes(Traversal other) {
            return from.equals(other.to()) && to.equals(other.from());
        }

        @Override
        public String toString() {
            return train + " on " + road + " " + from + "->" + to + " (asked at " + requestedAt
                    + ", admitted at " + admittedAt + ", waited " + waited() + ")";
        }
    }

    private final Map<String, Request> requests = new LinkedHashMap<>();     // train@object
    private final Map<String, Admission> admissions = new LinkedHashMap<>(); // train@object
    private final Map<String, List<Long>> leaves = new LinkedHashMap<>();    // train@object
    private final Set<String> died = new LinkedHashSet<>();
    private final Set<String> trains = new LinkedHashSet<>();
    private final List<String> malformed = new ArrayList<>();

    private TrainTrace(List<String> raw) {
        for (String line : raw) {
            Matcher e = ENTER.matcher(line);
            if (e.matches()) {
                String key = key(e.group(1), e.group(3));
                Request previous = requests.put(key,
                        new Request(e.group(1), e.group(3), e.group(4), e.group(5),
                                Long.parseLong(e.group(2))));
                if (previous != null) {
                    malformed.add(e.group(1) + " asked " + e.group(3) + " to admit it twice, so a"
                            + " (train, object) key no longer identifies one visit: " + line);
                }
                trains.add(e.group(1));
                continue;
            }
            Matcher r = ENTER_REPLY.matcher(line);
            if (r.matches()) {
                String key = key(r.group(1), r.group(3));
                Admission previous = admissions.put(key,
                        new Admission(r.group(1), r.group(3), r.group(4), Long.parseLong(r.group(2))));
                if (previous != null) {
                    malformed.add(r.group(3) + " admitted " + r.group(1) + " twice: " + line);
                }
                trains.add(r.group(1));
                continue;
            }
            Matcher l = LEAVE.matcher(line);
            if (l.matches()) {
                leaves.computeIfAbsent(key(l.group(1), l.group(3)), k -> new ArrayList<>())
                        .add(Long.parseLong(l.group(2)));
                trains.add(l.group(1));
                continue;
            }
            Matcher k = KILLED.matcher(line);
            if (k.matches()) {
                died.add(k.group(1));
            }
        }
    }

    static TrainTrace of(List<String> raw) {
        return new TrainTrace(raw);
    }

    private static String key(String train, String object) {
        return train + "@" + object;
    }

    /** Every train that appears anywhere in the capture, in first-seen order. */
    List<String> trains() {
        return List.copyOf(trains);
    }

    /**
     * True when this train reached {@code Train.destroy} — the only lifecycle callback in the
     * application that is not bound to a channel, and the marker that its route is COMPLETE. A
     * train still in flight at the run's bound has a truncated route and must not be held to the
     * end-of-route claims.
     */
    boolean completed(String train) {
        return died.contains(train);
    }

    /** Reconstruction problems that make the rest of this object meaningless. */
    List<String> malformed() {
        return List.copyOf(malformed);
    }

    /** The train's stated destination, from any of its {@code ENTER} payloads. */
    Optional<String> target(String train) {
        return requests.values().stream().filter(q -> q.train().equals(train))
                .map(Request::target).findFirst();
    }

    /**
     * The objects this train visited, in visit order — <strong>chained from the {@code position}
     * field of its own {@code ENTER} payloads, not from where the lines fell.</strong>
     *
     * <p>The chain starts at the single {@code position=null} request (the first hop, where
     * {@code Train.leaveObject} is still a no-op) and follows {@code position -> object} until it
     * runs out. A route that does not consume every request — an orphan, a fork or a cycle — is
     * reported by {@link #routeProblems(String)} rather than silently truncated.
     */
    List<String> route(String train) {
        Map<String, String> edges = new LinkedHashMap<>();
        for (Request request : requests.values()) {
            if (request.train().equals(train)) {
                edges.put(request.from(), request.object());
            }
        }
        List<String> route = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        String at = "null";
        while (edges.containsKey(at)) {
            String next = edges.get(at);
            if (!seen.add(next)) {
                break;
            }
            route.add(next);
            at = next;
        }
        return List.copyOf(route);
    }

    /** How many of this train's {@code ENTER}s the route chain could not account for. */
    List<String> routeProblems(String train) {
        List<String> problems = new ArrayList<>();
        List<String> route = route(train);
        long own = requests.values().stream().filter(q -> q.train().equals(train)).count();
        if (route.size() != own) {
            problems.add(train + " sent " + own + " ENTER(s) but only " + route.size()
                    + " of them chain into a route from position=null: " + route
                    + ". Either a hop is missing from the capture or the train revisited an object,"
                    + " and (train, object) then stops identifying one visit.");
        }
        return problems;
    }

    Optional<Request> request(String train, String object) {
        return Optional.ofNullable(requests.get(key(train, object)));
    }

    Optional<Admission> admission(String train, String object) {
        return Optional.ofNullable(admissions.get(key(train, object)));
    }

    /** Every {@code LEAVE} tick this train emitted for this object; normally exactly one. */
    List<Long> leaves(String train, String object) {
        return List.copyOf(leaves.getOrDefault(key(train, object), List.of()));
    }

    /** Every {@code (train, object)} pair that produced a {@code LEAVE}, as {@code train@object}. */
    Set<String> leftPairs() {
        return Set.copyOf(leaves.keySet());
    }

    /**
     * Every complete stay on a road, assembled per {@code (train, road)}.
     *
     * <p>A traversal with no {@code ENTER_REPLY} is dropped: the train asked and the run's bound
     * arrived before the road answered, so there is nothing to say about it.
     */
    List<Traversal> roadTraversals() {
        List<Traversal> out = new ArrayList<>();
        for (Request request : requests.values()) {
            if (!request.object().startsWith("tr")) {
                continue;
            }
            Admission admission = admissions.get(key(request.train(), request.object()));
            if (admission == null) {
                continue;
            }
            List<Long> left = leaves(request.train(), request.object());
            out.add(new Traversal(request.train(), request.object(), request.from(),
                    admission.next(), request.at(), admission.at(),
                    left.isEmpty() ? Long.MAX_VALUE : left.get(0)));
        }
        return List.copyOf(out);
    }

    /** Every {@code ENTER} sent to a road, whether or not the road ever answered it. */
    List<Request> roadRequests() {
        return requests.values().stream().filter(q -> q.object().startsWith("tr")).toList();
    }

    /**
     * The tick at which this request was answered, or {@link Long#MAX_VALUE} if it never was.
     *
     * <p>{@code RoadAgent.enter} has exactly two arms: {@code acceptTrain}, which replies inside the
     * handler, and {@code push}, which replies only from a later {@code leave()} — or never, if the
     * run's bound arrives first. So <strong>an unanswered road {@code ENTER} is by construction a
     * queued one</strong>, and that needs no threshold to establish.
     */
    long admittedAt(Request request) {
        Admission admission = admissions.get(key(request.train(), request.object()));
        return admission == null ? Long.MAX_VALUE : admission.at();
    }

    /**
     * The traversal that was <strong>on</strong> {@code road} at {@code tick}, ignoring
     * {@code exceptTrain} — i.e. for a queued request, the train whose {@code LEAVE} is what
     * {@code RoadAgent.leave} answers it from.
     *
     * <p>This is an interval containment on the simulated clock, and the interval is the width of a
     * road traversal — <strong>seconds</strong> (5 s and 8 s in {@code opencybele-congestion}, 3 s
     * in {@code opencybele-lifecycle}) against a measured tick jitter of 8–32 ms. It is not a
     * comparison of two ticks inside one burst, and it does not read line order.
     */
    Optional<Traversal> occupantAt(String road, long tick, String exceptTrain) {
        return roadTraversals().stream()
                .filter(other -> other.road().equals(road))
                .filter(other -> !other.train().equals(exceptTrain))
                .filter(other -> other.admittedAt() <= tick && tick < other.leftAt())
                .findFirst();
    }

    /** {@link #occupantAt} for the moment a known traversal asked to be admitted. */
    Optional<Traversal> occupantWhenRequested(Traversal waiting) {
        return occupantAt(waiting.road(), waiting.requestedAt(), waiting.train());
    }
}
