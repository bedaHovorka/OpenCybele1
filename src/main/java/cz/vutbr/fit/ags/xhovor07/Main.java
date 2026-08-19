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
	// The per-agent stream seeds, so a run's stderr is a complete manifest of its
	// randomness (#24). Derived here from the configuration alone: RailwayMainAgent's
	// Generator activity takes two streams, and every configured track takes one.
	System.err.println("--- random streams ---");
	System.err.println("  " + SimRandom.GENERATOR_OD_STREAM + " = "
		+ SimRandom.seedFor(config.getMasterSeed(), SimRandom.GENERATOR_OD_STREAM));
	System.err.println("  " + SimRandom.GENERATOR_INTERARRIVAL_STREAM + " = "
		+ SimRandom.seedFor(config.getMasterSeed(), SimRandom.GENERATOR_INTERARRIVAL_STREAM));
	for (String road : config.getRoadNames()) {
	    System.err.println("  " + road + " = " + SimRandom.seedFor(config.getMasterSeed(), road));
	}
	System.err.println("----------------------");

	final List<String> warnings = config.getGuiLayoutWarnings();
	// Headless has no canvas, so "the canvas cannot draw this network" is noise on a
	// stream the harness captures as part of the trace (docs/TESTING.md).
	if (!warnings.isEmpty() && !config.isHeadless()) {
	    // Not fatal: the canvas is outside the behavioural contract. But it must
	    // not silently draw a network that is not the one being simulated.
	    System.err.println("!!! GUI TOPOLOGY MISMATCH - the canvas cannot draw the configured network !!!");
	    for (String warning : warnings) {
		System.err.println("!!!   " + warning);
	    }
	    System.err.println("!!! The simulation is unaffected; the drawing is incomplete. See docs/scenario-config.md.");
	}

	if (!config.isHeadless() && java.awt.GraphicsEnvironment.isHeadless()) {
	    // Eagerly, for the same reason every other check in this method is eager: the
	    // HeadlessException would otherwise be thrown by `new Gui(...)` inside
	    // RailwayMainAgent's constructor, where Cybele swallows it to stderr and leaves
	    // a process running with no main agent, no clock and no exit status to show
	    // for it.
	    throw new IllegalStateException("this JVM is headless (no display, or"
		    + " -Djava.awt.headless=true) but " + ScenarioConfig.KEY_HEADLESS
		    + "=false, so the GUI would be built and fail. Pass -D"
		    + ScenarioConfig.KEY_HEADLESS + "=true to run without it.");
	}
	if (config.isHeadless() && !config.hasStopCondition()) {
	    // Not fatal - an unbounded headless run is a legitimate thing to ask for - but
	    // with no window to close it is also a run with no way to end it.
	    System.err.println("!!! " + ScenarioConfig.KEY_HEADLESS + "=true with no"
		    + " sim.stop.* bound: this run has no GUI to close and no stop condition,"
		    + " so it will run until it is killed. See docs/headless-and-stop.md.");
	}

	Cybele.startUp();
	// Install the exit-code hook and arm the bounds before anything can need them, then
	// wait for the kernel's timer service. Both must happen before the main agent is
	// created, because the main agent creates the simulation clock - see
	// RunControl.awaitTimerService() for what happens to a clock created too early.
	RunControl.install(config);
	RunControl.awaitTimerService();
	if (config.isTraceEnabled()) {
	    // BEFORE the main agent, and with a barrier in between. Cybele.createAgent is
	    // asynchronous, so creating the two in one breath is a race the probe loses:
	    // RailwayMainAgent's constructor creates the stations, and a Station sends its
	    // first STATION.INFO from its own constructor. awaitReady() returns only once
	    // every static channel is subscribed, so no message of the run can predate the
	    // trace. Nothing else about the simulation changes - the probe only subscribes.
	    // See docs/trace-format.md.
	    Cybele.createAgent(TraceProbe.PROBE_AGENT_NAME, TraceProbe.class.getName());
	    TraceProbe.awaitReady();
	}
	Cybele.createAgent(RailwayMainAgent.MAIN_AGENT_NAME, RailwayMainAgent.class.getName());
    }
}
