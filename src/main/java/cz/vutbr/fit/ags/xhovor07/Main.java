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

import java.io.File;
import java.text.MessageFormat;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogManager;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import jade.core.Profile;
import jade.core.ProfileImpl;
import jade.core.messaging.TopicManagementHelper;
import jade.wrapper.AgentContainer;
import jade.wrapper.StaleProxyException;

/**
 * Boots the JADE platform and starts the two agents that start everything else (#36).
 * <p>
 * Statement for statement this is the Cybele {@code Main} with {@code Cybele.startUp()} replaced
 * by a main container and {@code Cybele.createAgent} by {@code createNewAgent(...).start()}.
 * Everything above the boot — resolving the configuration, printing the resolved-configuration
 * banner and the randomness manifest, and the four eager validations — is <b>unchanged and must
 * stay unchanged</b>: {@code ScenarioAssertions.assertPropertiesReachedTheChild} reads that banner
 * back out of the captured stream, key by key, as its proof that every {@code -D} the harness
 * passed actually arrived.
 *
 * <h2>Why the validations are still eager, when the reason for it has changed</h2>
 *
 * On Cybele they were here because the kernel invoked agent constructors reflectively and swallowed
 * what they threw ({@code docs/assertion-triage.md}, Result 3), so a configuration error raised
 * inside an agent was effectively invisible. JADE does not swallow — but it does not do much better
 * either: a throwable out of {@code setup()} kills that agent, prints a banner and two stdout
 * lines, and leaves the platform running with a hole in it and an exit status of nothing at all.
 * A configuration error still has to be caught on this thread, before the platform exists, where
 * it can end the JVM with a status a harness can read.
 *
 * <h2>The container profile</h2>
 *
 * Four parameters, each of which is a decision recorded in {@code docs/message-ontology.md} §8:
 *
 * <ul>
 * <li>{@code services} — {@link TopicManagementHelper#SERVICE_NAME}{@code + "Service"}. <b>The
 *     load-bearing one.</b> Without it {@code getHelper} throws in the probe and every topic is
 *     silently a dead letter, which shows up as an empty trace with a green exit status rather
 *     than as a boot failure.</li>
 * <li>{@code mtps} — empty, removing the <em>external</em> message transport. It does not make the
 *     platform hermetic: JADE's intra-platform JICP listener still binds a TCP socket on a routable
 *     interface. Worth knowing because it means a locked-down machine fails this application at
 *     <em>boot</em>, and because a JADE recording run opens a socket where the Cybele one did not
 *     — a difference in the environment a run needs, not in the trace it produces.</li>
 * <li>{@code port} — {@code 0}, so that listener's port is ephemeral and a build machine never has
 *     a fixed port to free.</li>
 * <li>{@code file-dir} — where the AMS writes {@code APDescription.txt} on startup. It writes it
 *     into {@code getProperty("file-dir", "")}, i.e. <b>the process working directory</b> unless
 *     told otherwise, so an untold run drops an untracked file into whatever directory it was
 *     launched from — the repository, for {@code ./gradlew run}. Defaults to
 *     {@code java.io.tmpdir} here and is overridable with {@code -D}{@value #FILE_DIR_PROPERTY};
 *     the parity adapter points it at the run's scratch directory.</li>
 * </ul>
 *
 * @author Bedrich Hovorka
 */
public class Main {

    /**
     * Where JADE's AMS may write {@code APDescription.txt}. Not a {@code sim.*} key on purpose:
     * {@code sim.*} is the simulation's configuration surface, every key of it is printed in the
     * resolved-configuration banner and asserted against the scenario, and this is neither
     * configuration nor behaviour — it is where one framework puts one scratch file.
     */
    public static final String FILE_DIR_PROPERTY = "jade.file.dir";

    /**
     * @param args ignored; the run is configured entirely through {@code -Dsim.*} and
     *        {@code -Dsim.config}, see {@code docs/scenario-config.md}
     */
    public static void main(String[] args) {
	// Resolve and validate the scenario configuration here, on the main thread, BEFORE the
	// platform starts -- see the class comment for why that is still the right place.
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
	// randomness (#24). Derived here from the configuration alone: the Generator activity
	// takes two streams, and every configured track takes one.
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
	    // RailwayMainAgent.setup(), where it would kill the main agent and leave a platform
	    // running with no hub, no clock and no exit status to show for it.
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

	// Install the exit-code hook and arm the bounds BEFORE the platform exists, so that a
	// wall-clock bound covers the boot too. The simulated-time bound arms later, when
	// RailwayMainAgent hands its clock over -- see RunControl.useClock (#81).
	RunControl.install(config);

	prefixPlatformLogging();

	final jade.core.Runtime runtime = jade.core.Runtime.instance();
	// The JVM is ended by RunControl.stop, which is the only place that knows the exit code.
	// With closeVM true, JADE would call System.exit(0) when the last container dies and a
	// failed run could report a pass.
	runtime.setCloseVM(false);
	final AgentContainer container = runtime.createMainContainer(profile());
	if (container == null) {
	    // createMainContainer returns null when joinPlatform() fails -- it does not throw.
	    // Left unchecked, the next line would NPE and the failure would be reported as a
	    // programming error rather than as "the platform did not come up".
	    throw new IllegalStateException("the JADE main container did not come up."
		    + " jade.core.Runtime.createMainContainer returned null, which means"
		    + " joinPlatform() failed -- most often because a service named in the profile"
		    + " could not be loaded, or because the JICP listener could not bind.");
	}

	if (config.isTraceEnabled()) {
	    // BEFORE the main agent, and with a barrier in between. Starting the two in one breath
	    // is a race the probe loses: RailwayMainAgent.setup() spawns the stations and a Station
	    // sends its first STATION_INFO from its own setup(), so the opening lines of the run
	    // could be gone before the probe had registered. awaitReady() returns only once all
	    // fifteen topics are registered.
	    //
	    // TraceTopics.enable() comes first because it is what makes a send carry the probe
	    // copy at all; with it off, Messages.build adds no second receiver and the run is
	    // byte-for-byte the run it would have been. Nothing else about the simulation changes.
	    TraceTopics.enable();
	    start(container, TraceProbe.PROBE_AGENT_NAME, TraceProbe.class.getName());
	    TraceProbe.awaitReady();
	}
	start(container, RailwayMainAgent.MAIN_AGENT_NAME, RailwayMainAgent.class.getName());
    }

    /**
     * The container profile. See the class comment for what each parameter buys.
     *
     * @return a profile for {@code createMainContainer}
     */
    static Profile profile() {
	final Profile profile = new ProfileImpl();
	profile.setParameter(Profile.SERVICES, TopicManagementHelper.SERVICE_NAME + "Service");
	profile.setParameter(Profile.MTPS, "");
	profile.setParameter(Profile.MAIN_PORT, "0");
	profile.setParameter(Profile.FILE_DIR, fileDir());
	return profile;
    }

    /**
     * @return the directory JADE's AMS writes {@code APDescription.txt} into, with a trailing
     *         separator (the AMS concatenates rather than resolves)
     */
    private static String fileDir() {
	final String configured = System.getProperty(FILE_DIR_PROPERTY);
	final String dir = configured == null || configured.isBlank()
		? System.getProperty("java.io.tmpdir")
		: configured;
	return dir.endsWith(File.separator) ? dir : dir + File.separator;
    }

    /**
     * Route JADE's boot log through a <b>declared diagnostic prefix</b>, one line at a time.
     * <p>
     * JADE logs through {@code java.util.logging}, whose default {@code ConsoleHandler} writes two
     * lines per record to stderr — and {@code redirectErrorStream(true)} folds stderr into the
     * captured trace. The first of those two lines carries a <b>locale- and timezone-dependent
     * timestamp</b> ({@code "Aug 21, 2026 4:48:30 PM jade.core.Runtime beginContainer"}), and the
     * boot banner's body carries the platform's <b>IP address and an ephemeral port</b>
     * ({@code "- jicp://172.17.0.1:42025"}). Every one of those varies run to run or machine to
     * machine, so none of them can be in a golden — and none of them can be removed by a prefix
     * rule either, because a date is not a prefix anybody can declare.
     * <p>
     * So the shape is fixed here rather than guessed at by the adapter. Each line of each record
     * is re-emitted with {@code "--- "}, which is already one of the harness' default diagnostic
     * prefixes, and the record is still readable by a human running the application by hand.
     * <p>
     * <b>A throwable attached to a record is still printed raw</b>, deliberately: its first line
     * is {@code java.lang.Foo: …} and its frames start with a tab, none of which any diagnostic
     * prefix catches, so a logged failure lands in the trace and the run cannot pass or be
     * recorded. Quiet is for the boot banner, not for errors.
     * <p>
     * This resets {@code LogManager}, so a {@code -Djava.util.logging.config.file} handed to the
     * same JVM is overridden. That is intended: this application's stdout and stderr are a
     * measured artefact.
     */
    private static void prefixPlatformLogging() {
	LogManager.getLogManager().reset();
	final Logger root = Logger.getLogger("");
	root.setLevel(Level.INFO);
	root.addHandler(new Handler() {
	    @Override
	    public void publish(LogRecord record) {
		if (record == null || !isLoggable(record)) {
		    return;
		}
		final StringBuilder head = new StringBuilder();
		head.append(record.getLevel()).append(' ');
		if (record.getLoggerName() != null) {
		    head.append(record.getLoggerName()).append(": ");
		}
		final String body = head + message(record);
		for (String line : body.split("\\R", -1)) {
		    System.err.println("--- " + line);
		}
		if (record.getThrown() != null) {
		    record.getThrown().printStackTrace(System.err);
		}
		System.err.flush();
	    }

	    @Override
	    public void flush() {
		System.err.flush();
	    }

	    @Override
	    public void close() {
		flush();
	    }

	    private String message(LogRecord record) {
		final String raw = record.getMessage();
		if (raw == null) {
		    return "";
		}
		final Object[] parameters = record.getParameters();
		if (parameters == null || parameters.length == 0) {
		    return raw;
		}
		try {
		    return MessageFormat.format(raw, parameters);
		} catch (IllegalArgumentException e) {
		    return raw;   // not a MessageFormat pattern; the raw text is the message
		}
	    }
	});
    }

    /**
     * Create and start one agent by class name — {@code Cybele.createAgent(name, className)}.
     * <p>
     * A refused creation is fatal and says so, for the reason {@code RailwayMainAgent.spawn}
     * gives: Cybele's {@code createAgent} returned nothing and failed silently, JADE throws, and
     * swallowing that would leave a platform running with no simulation in it.
     *
     * @param container the main container
     * @param name the agent's local name, which is also its name in every trace field
     * @param className the agent class, passed as a string exactly as Cybele took it
     */
    private static void start(AgentContainer container, String name, String className) {
	try {
	    container.createNewAgent(name, className, null).start();
	} catch (StaleProxyException e) {
	    throw new IllegalStateException("Main: cannot create agent '" + name + "' of class "
		    + className, e);
	}
    }
}
