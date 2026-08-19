import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

import cybele.kernel.Cybele;

/**
 * Experiment G: the three kernel facts RunControl (issue #17) is built on.
 *
 *   G1  Cybele.terminate() calls System.exit(0) and NEVER RETURNS, so an exit code
 *       cannot be set after it. Checked in a child JVM.
 *   G2  A shutdown hook calling Runtime.halt(code) still overrides that 0, which is
 *       how a wall-clock timeout is made to exit non-zero. Checked in a child JVM.
 *   G3  A clock command can be lost in TWO different ways, only one of which is the
 *       startup race the ExpB* probes are about:
 *         (a) the ANNOUNCEMENT is dropped   -> the clock is permanently uncommandable,
 *             and re-sending never recovers it;
 *         (b) the per-clock COMMAND CHANNEL is not open yet -> that one command is
 *             dropped, and a re-send lands. This happens even for a clock created
 *             hundreds of ms after startUp(), where no startup race exists, if the
 *             command follows createClock with no intervening statement.
 *       (b) is measured and reported; it is a race, so it is not a pass/fail item.
 *
 * Self-verdicting: prints PASS or FAIL and exits 0 or 1. See
 * docs/headless-and-stop.md for the numbers these produced and what was built on them.
 *
 * Usage: docs/probes/run.sh ExpG
 * The child-JVM modes ("terminate-plain", "terminate-hook") are internal.
 */
public class ExpG {
    static final int HOOK_CODE = 42;
    static final int UNREACHED_CODE = 7;

    public static void main(String[] args) throws Exception {
        final String mode = args.length > 0 ? args[0] : "parent";
        if (mode.equals("terminate-plain")) { terminatePlain(); return; }
        if (mode.equals("terminate-hook")) { terminateHook(); return; }

        // The child JVMs run FIRST, on purpose: they start their own Cybele kernel, and a
        // second kernel starting while this JVM already has one running does not come up.
        boolean ok = true;
        ok &= g1();
        ok &= g2();
        ok &= g3();          // starts this JVM's own kernel, so it goes last
        System.out.println(ok ? "RESULT: PASS" : "RESULT: FAIL");
        System.exit(ok ? 0 : 1);
    }

    // ---------------------------------------------------------------- G3

    private static boolean g3() throws Exception {
        Cybele.startUp();
        Thread.sleep(400);   // well clear of the ExpB* startup race

        // (b) one command, sent with nothing at all in between
        Cybele.createClock("immediate", Cybele.HOST, 0, 1);
        Cybele.pauseClock("immediate");
        boolean immediateLanded = awaitPaused("immediate", 500);
        System.out.println("G3b single pauseClock immediately after createClock, no race: "
                + (immediateLanded ? "landed" : "LOST")
                + "   (measured lost 6/6 when written this way; see headless-and-stop.md)");

        // (b) again, but re-sending while waiting
        Cybele.createClock("resent", Cybele.HOST, 0, 1);
        boolean resentLanded = false;
        final long deadline = System.currentTimeMillis() + 500;
        while (System.currentTimeMillis() < deadline) {
            Cybele.pauseClock("resent");
            if (Cybele.isPaused("resent")) { resentLanded = true; break; }
            Thread.sleep(5);
        }
        System.out.println("G3  re-sent pauseClock on a clock created 400 ms after startUp: "
                + (resentLanded ? "landed" : "LOST") + (resentLanded ? "" : "   <-- expected to land"));

        // re-sending is safe: pause/resume is a flag, not a counter (SEM-02)
        for (int i = 0; i < 5; i++) { Cybele.pauseClock("resent"); Thread.sleep(5); }
        Cybele.resumeClock("resent");
        boolean running = awaitRunning("resent", 500);
        long t = Cybele.getTime("resent");
        Thread.sleep(600);
        boolean stayedRunning = running && Cybele.getTime("resent") > t;
        System.out.println("G3  5 pauses undone by ONE resume, still running 600 ms later: "
                + stayedRunning + (stayedRunning ? "" : "   <-- expected true"));

        return resentLanded && stayedRunning;
    }

    private static boolean awaitPaused(String id, long budgetMs) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline) {
            if (Cybele.isPaused(id)) return true;
            Thread.sleep(5);
        }
        return Cybele.isPaused(id);
    }

    private static boolean awaitRunning(String id, long budgetMs) throws InterruptedException {
        final long deadline = System.currentTimeMillis() + budgetMs;
        while (System.currentTimeMillis() < deadline) {
            if (!Cybele.isPaused(id)) return true;
            Thread.sleep(5);
        }
        return !Cybele.isPaused(id);
    }

    // ---------------------------------------------------------------- G1 / G2

    private static boolean g1() throws Exception {
        final Result r = child("terminate-plain");
        final boolean ok = r.exit == 0 && !r.out.contains("AFTER-TERMINATE");
        System.out.println("G1  Cybele.terminate(): child exited " + r.exit + ", printed after it: "
                + r.out.contains("AFTER-TERMINATE") + "   (expected 0 / false)");
        return ok;
    }

    private static boolean g2() throws Exception {
        final Result r = child("terminate-hook");
        final boolean ok = r.exit == HOOK_CODE;
        System.out.println("G2  shutdown hook halt(" + HOOK_CODE + ") over terminate()'s exit(0):"
                + " child exited " + r.exit + "   (expected " + HOOK_CODE + ")");
        return ok;
    }

    private static void terminatePlain() throws Exception {
        Cybele.startUp();
        // Clear of the startup race before touching the timer service: createClock at a
        // ~1 ms gap throws TimerAgent.register's NPE, and an uncaught throwable here does
        // not end the JVM - the kernel's threads are not daemons, so the child would hang.
        Thread.sleep(300);
        Cybele.createClock("c", Cybele.HOST, 0, 1);
        System.out.flush();
        Cybele.terminate();
        System.out.println("AFTER-TERMINATE");   // measured: never printed
        System.out.flush();
        System.exit(UNREACHED_CODE);
    }

    private static void terminateHook() throws Exception {
        Runtime.getRuntime().addShutdownHook(new Thread() {
            @Override public void run() {
                System.out.flush();
                Runtime.getRuntime().halt(HOOK_CODE);
            }
        });
        Cybele.startUp();
        // Clear of the startup race before touching the timer service: createClock at a
        // ~1 ms gap throws TimerAgent.register's NPE, and an uncaught throwable here does
        // not end the JVM - the kernel's threads are not daemons, so the child would hang.
        Thread.sleep(300);
        Cybele.createClock("c", Cybele.HOST, 0, 1);
        System.out.flush();
        Cybele.terminate();
        System.exit(UNREACHED_CODE);
    }

    // ---------------------------------------------------------------- child JVMs

    private static final class Result {
        final int exit; final String out;
        Result(int exit, String out) { this.exit = exit; this.out = out; }
    }

    /**
     * Re-launch this class in a child JVM. run.sh runs probes with the working directory
     * set to its scratch dir and java.base patched from ./cybelle, so both are inherited
     * as they are; only the classpath has to be restated.
     */
    private static Result child(String mode) throws Exception {
        final List<String> cmd = new ArrayList<String>();
        cmd.add(System.getProperty("java.home") + "/bin/java");
        cmd.add("--patch-module");
        cmd.add("java.base=cybelle");
        cmd.add("-cp");
        cmd.add(System.getProperty("java.class.path"));
        cmd.add(ExpG.class.getName());
        cmd.add(mode);
        final ProcessBuilder pb = new ProcessBuilder(cmd);
        pb.redirectErrorStream(true);
        final Process p = pb.start();
        final StringBuilder sb = new StringBuilder();
        final BufferedReader in = new BufferedReader(new InputStreamReader(p.getInputStream()));
        for (String line = in.readLine(); line != null; line = in.readLine()) {
            sb.append(line).append('\n');
        }
        if (!p.waitFor(60, java.util.concurrent.TimeUnit.SECONDS)) {
            p.destroyForcibly();
            return new Result(-1, sb.toString() + "\n(child did not exit within 60 s)");
        }
        return new Result(p.exitValue(), sb.toString());
    }
}
