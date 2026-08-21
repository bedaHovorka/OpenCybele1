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

/**
 * Reprezents static railway object, which can vote — <b>reduced to its four channel-name constants
 * by #32</b>.
 * <p>
 * <b>What it used to be.</b> The abstract base of {@code Station} and {@code RoadAgent}: a Cybele
 * {@code Handler} that opened the four channels below in its constructor, implemented the voting
 * half of the protocol ({@code voteRequest}/{@code voteResult}/{@code sendEnterReply}) and left
 * {@code enter}/{@code leave}/{@code addToPlan}/{@code computeDifference} abstract. #30 and #31
 * inlined all of that into two {@code jade.core.Agent}s — for the reasons {@code RoadAgent}'s class
 * comment gives at length — and #33 kept the dead Cybele half on the stated ground that it still
 * compiled and that #32/#36 would delete the file wholesale.
 * <p>
 * <b>Why the dead half is gone now.</b> That ground expired here. Every deleted method called
 * {@code RailwayObject.getName()}, and {@code RailwayObject} is deleted by this commit:
 * {@code Train} was its last subclass, and a ported {@code Train} reifies its name as
 * {@code getLocalName()} instead. So the choice was between resurrecting {@code getName()} inside
 * this class — reinstating the very method #33 called vestigial — and deleting the code that called
 * it. The second is smaller and it is what is done.
 * <p>
 * <b>Why the file survives at all.</b> The four constants have a non-test owner: {@code TraceProbe}
 * opens {@code VOTE_REQUEST.}, {@code VOTE_RESULT.}, {@code ENTER.} and {@code LEAVE.} channels for
 * every static object, and the probe is <b>#36</b>'s to replace with a JADE one. #27's
 * {@code ChannelTableTest} is their second owner, comparing {@code Channel}'s transcription against
 * them — and because the first owner still exists, asserting against the constant still checks
 * something, which is why those four rows were <em>not</em> re-pointed at literals the way #33
 * re-pointed {@code PATH_FIND_REPLY}. When #36 lands, both owners go and so does this file.
 * <p>
 * Nothing in the JADE half reads any of this. The channel identity a ported agent uses is
 * {@code Channel}'s {@code :ontology} slot, not a string prefix.
 *
 * @author Bedrich Hovorka
 *
 */
public abstract class StaticRailwayObject {
    /**
     * Channel for vote request
     */
    public static final String VOTE_REQUEST = "VOTE_REQUEST.";
    /**
     * Channel for vote result
     */
    public static final String VOTE_RESULT = "VOTE_RESULT.";
    // klasicke SHO operace

    /**
     * Channel for enter request from train
     */
    public static final String ENTER = "ENTER.";
    /**
     * Channel for leave notification from train
     */
    public static final String LEAVE = "LEAVE.";
}
