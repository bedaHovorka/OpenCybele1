/*
 * Projekt AGS 2007/08 - FIT VUT Brno - Open Cybele 1
 */
package jade.core;

/**
 * Gives {@link AID} a platform name outside a running container.
 * <p>
 * {@code new AID(localName, AID.ISLOCALNAME)} — the addressing mode #27 chose for all fifteen
 * channels — appends {@code '@' + AID.getPlatformID()}, and that static is only set when a
 * container boots. Without it the constructor throws {@code Unknown Platform Name}, so a unit
 * test of the binding could not use the very call the port will use. {@code AID.setPlatformID}
 * is package-private, hence this two-line fixture in {@code jade.core} rather than reflection.
 * <p>
 * The alternative — building AIDs with {@code AID.createGUID(name, platform)} and
 * {@code AID.ISGUID} — would have tested a call the production code does not make.
 */
public final class JadePlatformFixture {

    private JadePlatformFixture() {
    }

    /**
     * Install a platform name if none is set. Idempotent, so every test class can call it.
     *
     * @param platformName the name to use, e.g. {@code opencybele-test}
     */
    public static void install(String platformName) {
        if (AID.getPlatformID() == null) {
            AID.setPlatformID(platformName);
        }
    }
}
