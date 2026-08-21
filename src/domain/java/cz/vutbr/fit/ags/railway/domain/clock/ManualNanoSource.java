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

import java.util.concurrent.atomic.AtomicLong;

/**
 * A {@link NanoSource} that only moves when it is told to — the thing that makes every test in
 * this package deterministic instead of a sleep-and-hope.
 *
 * <p>Monotone by construction: {@link #advanceNanos(long)} rejects a negative step, so a test
 * cannot accidentally violate the contract {@link SimClock#nowMs()} depends on.</p>
 */
public final class ManualNanoSource implements NanoSource {

    private final AtomicLong nanos;

    /** Starts at zero. */
    public ManualNanoSource() {
        this(0L);
    }

    /** @param startNanos the initial reading; only differences are ever meaningful */
    public ManualNanoSource(long startNanos) {
        this.nanos = new AtomicLong(startNanos);
    }

    @Override
    public long nanos() {
        return nanos.get();
    }

    /**
     * @param deltaNanos how far to move forward; must not be negative
     * @return the new reading
     */
    public long advanceNanos(long deltaNanos) {
        if (deltaNanos < 0) {
            throw new IllegalArgumentException("a NanoSource is monotone; cannot advance by "
                    + deltaNanos);
        }
        return nanos.addAndGet(deltaNanos);
    }

    /**
     * @param deltaMillis how far to move forward in real milliseconds; must not be negative
     * @return the new reading, in nanoseconds
     */
    public long advanceMillis(long deltaMillis) {
        return advanceNanos(Math.multiplyExact(deltaMillis, 1000000L));
    }
}
