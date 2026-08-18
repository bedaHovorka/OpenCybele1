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
import java.util.List;

import cybele.kernel.Cybele;

/**
 * 
 * @author Bedrich Hovorka
 *
 */
public class Main {

    /**
     * @param args
     */
    public static void main(String[] args) {
	// Resolve and validate the scenario configuration here, on the main thread,
	// BEFORE the kernel starts. Cybele invokes agent constructors and handlers
	// reflectively and swallows the resulting InvocationTargetException to stderr
	// without touching the exit status (docs/assertion-triage.md, Result 3), so a
	// configuration error raised inside an agent would be effectively invisible.
	final ScenarioConfig config = ScenarioConfig.load();
	System.err.println("--- scenario configuration ---");
	System.err.print(config.describe());
	System.err.println("------------------------------");
	if (config.isMasterSeedDrawn()) {
	    // Criterion of #15: a default (drawn) seed is worthless unless the run can be
	    // replayed afterwards, so say it once, loudly, on stderr - stdout belongs to
	    // the golden trace.
	    System.err.println("--- no " + ScenarioConfig.KEY_RANDOM_MASTER_SEED + " given; drew "
		    + config.getMasterSeed() + ". Replay this run's random streams with:");
	    System.err.println("---   -D" + ScenarioConfig.KEY_RANDOM_MASTER_SEED + "="
		    + config.getMasterSeed());
	}
	final List<String> warnings = config.getGuiLayoutWarnings();
	if (!warnings.isEmpty()) {
	    // Not fatal: the canvas is outside the behavioural contract. But it must
	    // not silently draw a network that is not the one being simulated.
	    System.err.println("!!! GUI TOPOLOGY MISMATCH - the canvas cannot draw the configured network !!!");
	    for (String warning : warnings) {
		System.err.println("!!!   " + warning);
	    }
	    System.err.println("!!! The simulation is unaffected; the drawing is incomplete. See docs/scenario-config.md.");
	}

	Cybele.startUp();
	Cybele.createAgent(RailwayMainAgent.MAIN_AGENT_NAME, RailwayMainAgent.class.getName());
    }
}
