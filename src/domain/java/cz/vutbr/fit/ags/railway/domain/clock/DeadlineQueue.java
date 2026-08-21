/*
 * Projekt AGS 2007/08
 * FIT VUT Brno
 *
 * Open Cybele 1
 *
 * Bedrich Hovorka
 * xhovor07@stud.fit.vutbr.cz
 */
package cz.vutbr.fit.ags.railway.domain.clock;

import java.util.HashMap;
import java.util.Map;
import java.util.PriorityQueue;

/**
 * A min-heap of pending wake-ups, keyed on <b>absolute simulated milliseconds</b> — the storage
 * behind {@link AgentClock}, and the replacement for the kernel's per-clock timer list (#29).
 *
 * <h2>Absolute simulated time, and what that buys</h2>
 * {@code Activity.setTimer(CLOCK_ID, delay, …)} takes a <em>relative</em> delay, which the
 * kernel turns into a real-time expiry and has to rescale whenever the pace changes. Here the
 * key is the simulated instant itself. A pace change moves no key, so there is no rescale pass
 * to get wrong and no rounding error to accumulate across repeated pace changes — the same
 * instant simply arrives sooner or later in real time. See the package documentation.
 *
 * <h2>Ordering</h2>
 * Earliest deadline first; ties broken by <b>insertion order</b>. Cybele's agent event queue is
 * FIFO — that is the measured effective default and {@code docs/kernel-config.md} §1 decided to
 * keep it rather than enable the {@code staticpriority_comp} comparator — so two timers due at
 * the same simulated instant firing in the order they were armed is the closest analogue the
 * port has. It is also the only tie-break that is deterministic, which the goldens require.
 *
 * <h2>Deadlines in the past</h2>
 * A deadline at or before {@code now} is due at the next poll, not dropped and not an error.
 * This is deliberate and load-bearing: Cybele fires a negative- or zero-delay timer
 * immediately (SEM-06/TMR-04, measured at 1–5 ms for {@code delay = -1500}), and DEF-16 —
 * {@code RoadAgent}'s Gaussian travel delay going negative on 1 s roads, 2.26 % of draws —
 * depends on exactly that. A port that rejected or clamped a past deadline would still pass
 * every golden ({@code COVERAGE.md} §10.9) but would have quietly dropped a behaviour the
 * defect register pins. {@code DeadlineQueueTest} asserts it.
 *
 * <h2>Threading</h2>
 * Every method is synchronised, because arming can come from a different thread than draining:
 * a message handler on the agent thread arms, the framework's ticker on the same thread drains,
 * but a test or a GUI action may arm from elsewhere. No user code runs under the monitor —
 * {@link #pollDue(long, long)} hands the task back and {@link AgentClock} runs it outside.
 */
public final class DeadlineQueue {

    /** One pending wake-up. Identity-compared on purpose: {@code PriorityQueue.remove} needs it. */
    private static final class Entry implements Comparable<Entry> {
        private final long id;
        private final long dueSimMs;
        private final long seq;
        private final Runnable task;

        private Entry(long id, long dueSimMs, long seq, Runnable task) {
            this.id = id;
            this.dueSimMs = dueSimMs;
            this.seq = seq;
            this.task = task;
        }

        @Override
        public int compareTo(Entry o) {
            // Long.compare, not (int)(a - b). DEF-03 is the 2008 code narrowing a millisecond
            // long difference to int in exactly this position; it is pinned where it is
            // observable (RoadQueueItem, Planning's TrainPlan) and must not be re-introduced
            // anywhere it is not.
            final int byTime = Long.compare(dueSimMs, o.dueSimMs);
            return byTime != 0 ? byTime : Long.compare(seq, o.seq);
        }

        @Override
        public String toString() {
            return "#" + id + "@" + dueSimMs + "ms";
        }
    }

    private final PriorityQueue<Entry> heap = new PriorityQueue<Entry>();
    private final Map<Long, Entry> byId = new HashMap<Long, Entry>();
    private long nextId = 1L;
    private long nextSeq = 0L;

    /**
     * Arms a wake-up.
     *
     * @param dueSimMs the simulated instant to fire at; may be in the past, see the class
     *     documentation
     * @param task what to run; will be run on whichever thread calls
     *     {@link AgentClock#runDue()}, never on a thread this package owns
     * @return a handle for {@link #cancel(long)}, unique within this queue and never reused
     */
    public synchronized long scheduleAt(long dueSimMs, Runnable task) {
        if (task == null) {
            throw new IllegalArgumentException("task must not be null");
        }
        final Entry e = new Entry(nextId++, dueSimMs, nextSeq++, task);
        heap.offer(e);
        byId.put(Long.valueOf(e.id), e);
        return e.id;
    }

    /**
     * Disarms a wake-up. {@code Activity.clearTimer}.
     *
     * @param id a handle from {@link #scheduleAt}
     * @return whether it was still pending; {@code false} for an unknown, already-fired or
     *     already-cancelled handle
     */
    public synchronized boolean cancel(long id) {
        final Entry e = byId.remove(Long.valueOf(id));
        if (e == null) {
            return false;
        }
        heap.remove(e);
        return true;
    }

    /**
     * Pops the earliest wake-up that is due, if there is one.
     *
     * @param nowSimMs the current simulated time
     * @param seqLimit only entries armed <em>before</em> this sequence number are eligible; pass
     *     {@link #sequence()} captured at the start of a drain. This is what stops a task that
     *     arms a past-dated deadline from being re-entered in the same drain and spinning the
     *     agent thread forever — it fires on the next tick instead, which is also what Cybele
     *     does with a zero-delay timer (SEM-06: it fires in 1–5 ms, not synchronously).
     * @return the task to run, or {@code null} if nothing eligible is due
     */
    public synchronized Runnable pollDue(long nowSimMs, long seqLimit) {
        final Entry head = heap.peek();
        if (head == null || head.dueSimMs > nowSimMs) {
            return null;                        // the heap's minimum is not due: nothing is
        }
        if (head.seq < seqLimit) {
            heap.poll();                        // the ordinary case
            byId.remove(Long.valueOf(head.id));
            return head.task;
        }
        // Rare: the earliest due entry was armed DURING this drain, so it is not eligible —
        // but an older, later-dated entry may still be. Checking only the head would skip it
        // and defer it a whole tick, so find the earliest eligible entry properly. The scan is
        // O(n) over a queue that holds a handful of timers per agent, and it only runs when a
        // task armed a past-dated deadline, which is DEF-16's shape.
        Entry best = null;
        for (Entry e : heap) {
            if (e.dueSimMs <= nowSimMs && e.seq < seqLimit
                    && (best == null || e.compareTo(best) < 0)) {
                best = e;
            }
        }
        if (best == null) {
            return null;
        }
        heap.remove(best);
        byId.remove(Long.valueOf(best.id));
        return best.task;
    }

    /**
     * @return the sequence number the next {@link #scheduleAt} will use — the drain bound for
     *     {@link #pollDue(long, long)}
     */
    public synchronized long sequence() {
        return nextSeq;
    }

    /** @return the earliest pending deadline in simulated ms, or {@code null} if none */
    public synchronized Long nextDueMs() {
        final Entry head = heap.peek();
        return head == null ? null : Long.valueOf(head.dueSimMs);
    }

    /** @return how many wake-ups are pending */
    public synchronized int size() {
        return heap.size();
    }

    @Override
    public synchronized String toString() {
        return "DeadlineQueue" + heap;
    }
}
