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

/**
 * The one place real time enters the domain — a monotone nanosecond counter, supplied by the
 * caller.
 *
 * <p>{@link PacedClock} needs a real-time reference to derive simulated time from. Reading
 * {@code System.nanoTime()} directly inside it would make every clock test a sleep-and-hope and
 * would put an untestable global back into the domain, which is precisely the shape #28 spent
 * a ticket removing. So the reference is a parameter, exactly as simulated time is a parameter
 * everywhere else in this package.</p>
 *
 * <p>Implementations must be monotone non-decreasing and safe to call from several threads.
 * {@link #system()} is the production one; {@link ManualNanoSource} is the test one and is what
 * {@link VirtualClock} is built on.</p>
 *
 * <p>Nanoseconds rather than milliseconds because {@code System.currentTimeMillis()} is not
 * monotone — an NTP step or a DST change would move simulated time backwards, and
 * {@link SimClock#nowMs()} promises it never does.</p>
 */
public interface NanoSource {

    /** @return a monotone non-decreasing nanosecond count; only differences are meaningful */
    long nanos();

    /**
     * @return the production source, {@code System.nanoTime()}. Monotone by JLS contract and
     *     unaffected by wall-clock adjustments.
     */
    static NanoSource system() {
        return System::nanoTime;
    }
}
