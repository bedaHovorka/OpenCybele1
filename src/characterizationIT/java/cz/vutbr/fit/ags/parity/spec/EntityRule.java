package cz.vutbr.fit.ags.parity.spec;

import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * How to read an entity id out of a trace line, and how far the entity <em>sets</em> of two runs
 * may drift apart before that counts as a failure.
 *
 * <p>Two measurements make this a first-class part of a scenario rather than a normalizer detail.
 * When two entities act in the same simulated millisecond their lines race into the stream, so
 * comparison has to be possible per entity id rather than per line position. And above the load
 * ceiling entities go missing outright, so a scenario has to be able to say how many.
 *
 * <p>A tolerance above zero is a statement that the scenario sits at or above the load ceiling and
 * has been measured to drift by no more than this. It is not a knob to turn until CI goes green:
 * the cheaper answer is almost always a shorter or less dense scenario.
 *
 * @param pattern          regex applied to each line; the id is a capture group
 * @param group            index of the capturing group holding the id (default 1)
 * @param missingTolerance ids present in the golden but absent from the run
 * @param extraTolerance   ids present in the run but absent from the golden
 */
public record EntityRule(Pattern pattern, int group, int missingTolerance, int extraTolerance) {

    public EntityRule {
        if (pattern == null) {
            throw new IllegalArgumentException("entity rule needs a pattern");
        }
        if (group < 1) {
            throw new IllegalArgumentException("entity.group must be at least 1");
        }
        if (missingTolerance < 0 || extraTolerance < 0) {
            throw new IllegalArgumentException("entity tolerances must not be negative");
        }
    }

    /** @return the entity id carried by {@code line}, or empty when the line names no entity */
    public Optional<String> idOf(String line) {
        Matcher m = pattern.matcher(line);
        if (m.find() && m.groupCount() >= group) {
            return Optional.ofNullable(m.group(group));
        }
        return Optional.empty();
    }
}
