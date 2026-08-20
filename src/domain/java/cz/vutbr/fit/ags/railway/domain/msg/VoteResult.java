/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package cz.vutbr.fit.ags.railway.domain.msg;

import java.util.Arrays;
import java.util.List;

/**
 * CH-02 {@code VOTE_RESULT.<obj>} — the agreed departure instant.
 * <p>
 * <strong>Sent to every member of the path, not only to the voter whose proposal won.</strong>
 * There is no {@code reject-proposal} anywhere in this application: a voter that asked for a
 * smaller delay than the maximum still receives an {@code accept-proposal} carrying a value it
 * did not propose. That deviation from FIPA's contract net is deliberate and is recorded in
 * {@code docs/message-ontology.md}; it is the reason the port must not use JADE's
 * {@code ContractNetInitiator}.
 * <p>
 * {@code planned} is family 3 of the run-varying numeric families.
 *
 * @param train   the train being scheduled
 * @param planned the agreed instant at this object, in simulated milliseconds
 */
public record VoteResult(String train, long planned) implements RailwayMessage {
    @Override public Channel channel() { return Channel.VOTE_RESULT; }
    @Override public List<String> values() { return Arrays.asList(train, Long.toString(planned)); }
    @Override public String subjectFromPayload() { return train; }
}
