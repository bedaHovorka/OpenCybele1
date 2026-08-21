/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

import java.util.ArrayList;
import java.util.List;

/**
 * Renders a {@link RailwayMessage} into field 7 of the canonical parity trace, and reads one
 * back.
 * <p>
 * The format is fixed by {@code docs/trace-format.md} and this class is its executable copy:
 * {@code key=value,key=value} in trace field-7 order, keys from {@link Channel#payloadKeys()}, a
 * {@code null} slot rendered as the four characters {@code null}, and three characters
 * percent-escaped — {@code |} to {@code %7C}, LF to {@code %0A}, CR to {@code %0D} — with
 * {@code %} to {@code %25} applied <em>first</em> so the transformation is reversible.
 * <p>
 * It lives in {@code domain} because all three implementations need it and none of them may
 * import another's framework: the Cybele probe already renders these lines, #36's JADE probe
 * must render byte-identical ones, and #43's Jason {@code AgArch} must too. A rendering that
 * exists three times in three source trees is three chances to drift.
 * <p>
 * <strong>{@link #parse} is a test and tooling affordance, not part of the contract.</strong>
 * {@code docs/trace-format.md} specifies field 7 as opaque text with a documented shape,
 * deliberately not as a parseable map, because {@code ,} and {@code =} are not escaped. The
 * splitter below is exact for every payload this application can produce — no value contains
 * {@code ,} or the literal {@code ,<key>=} — and it exists so that the round trip can be
 * asserted rather than eyeballed.
 */
public final class Payloads {

    private Payloads() {
    }

    /**
     * Render slot values as trace field 7.
     *
     * @param channel the channel, which supplies the key names and their order
     * @param values  the slot values, positionally aligned with the keys, {@code null} allowed
     * @return the rendered payload
     * @throws IllegalArgumentException if the slot count does not match the key count
     */
    public static String render(Channel channel, List<String> values) {
        List<String> keys = channel.payloadKeys();
        if (values.size() != keys.size()) {
            throw new IllegalArgumentException(channel.event() + " takes " + keys.size()
                    + " slots, got " + values.size());
        }
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < keys.size(); i++) {
            if (i > 0) {
                out.append(',');
            }
            out.append(keys.get(i)).append('=').append(escape(values.get(i)));
        }
        return out.toString();
    }

    /**
     * The inverse of {@link #render}, for round-trip testing.
     *
     * @param channel the channel the payload belongs to
     * @param payload a rendered field 7
     * @return the slot values, unescaped, with {@code null} for a {@code null} slot
     * @throws IllegalArgumentException if the payload does not have this channel's shape
     */
    public static List<String> split(Channel channel, String payload) {
        List<String> keys = channel.payloadKeys();
        String head = keys.get(0) + "=";
        if (!payload.startsWith(head)) {
            throw new IllegalArgumentException("payload for " + channel.event()
                    + " must start with '" + head + "': " + payload);
        }
        List<String> raw = new ArrayList<>(keys.size());
        int cursor = head.length();
        for (int i = 1; i < keys.size(); i++) {
            String separator = "," + keys.get(i) + "=";
            int at = payload.indexOf(separator, cursor);
            if (at < 0) {
                throw new IllegalArgumentException("payload for " + channel.event()
                        + " is missing '" + separator + "': " + payload);
            }
            raw.add(payload.substring(cursor, at));
            cursor = at + separator.length();
        }
        raw.add(payload.substring(cursor));
        List<String> values = new ArrayList<>(raw.size());
        for (String r : raw) {
            values.add(unescape(r));
        }
        return values;
    }

    /**
     * Read a rendered payload back into the message record it came from.
     *
     * @param channel the channel the payload belongs to
     * @param payload a rendered field 7
     * @return the message
     */
    public static RailwayMessage parse(Channel channel, String payload) {
        return of(channel, split(channel, payload));
    }

    /**
     * Build a message record from its slot values.
     *
     * @param channel the channel
     * @param v       the slot values, positionally aligned with {@link Channel#payloadKeys()}
     * @return the message
     * @throws IllegalArgumentException if the slot count is wrong
     */
    public static RailwayMessage of(Channel channel, List<String> v) {
        if (v.size() != channel.payloadKeys().size()) {
            throw new IllegalArgumentException(channel.event() + " takes "
                    + channel.payloadKeys().size() + " slots, got " + v.size());
        }
        return switch (channel) {
            case VOTE_REQUEST -> new VoteRequest(v.get(0), Long.parseLong(v.get(1)));
            case VOTE_RESULT -> new VoteResult(v.get(0), Long.parseLong(v.get(1)));
            case ENTER -> new EnterRequest(v.get(0), v.get(1), v.get(2));
            case LEAVE -> new LeaveNotice(v.get(0));
            case START -> new StartCommand(v.get(0));
            case ENTER_REPLY -> new EnterReply(v.get(0), v.get(1));
            case TRAVEL_END -> new TravelEnd(v.get(0));
            case TRAVEL_START -> new TravelStart(v.get(0));
            case PATH_FIND_REPLY -> new PathFindReply(v.get(0), v.get(1));
            case STATION_INFO -> new StationInfo(Integer.parseInt(v.get(0)), Integer.parseInt(v.get(1)));
            case ROAD_STATE -> new RoadStateReport(v.get(0) == null ? null : RoadDirection.valueOf(v.get(0)));
            case TRAIN_STATE -> new TrainState(v.get(0));
            case PLAN_TRAIN -> new PlanTrain(v.get(0), v.get(1), v.get(2));
            case VOTE -> new Vote(v.get(0), v.get(1), Long.parseLong(v.get(2)));
            case PATH_FIND -> new PathFindRequest(v.get(0), v.get(1));
        };
    }

    /**
     * Percent-escape the three characters that would corrupt a trace line.
     *
     * @param value a slot value, {@code null} allowed
     * @return the escaped text, or the four characters {@code null}
     */
    public static String escape(String value) {
        if (value == null) {
            return "null";
        }
        return value.replace("%", "%25")
                .replace("|", "%7C")
                .replace("\n", "%0A")
                .replace("\r", "%0D");
    }

    /**
     * The inverse of {@link #escape}. The literal {@code null} decodes to a {@code null} slot;
     * no agent in this system is ever named {@code null}, so the ambiguity is unreachable.
     *
     * @param text an escaped slot value
     * @return the original value, or {@code null}
     */
    public static String unescape(String text) {
        if ("null".equals(text)) {
            return null;
        }
        return text.replace("%0D", "\r")
                .replace("%0A", "\n")
                .replace("%7C", "|")
                .replace("%25", "%");
    }
}
