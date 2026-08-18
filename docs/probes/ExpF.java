package probe;

import java.io.Serializable;
import java.util.concurrent.CountDownLatch;

import cybele.kernel.Activity;
import cybele.kernel.Cybele;
import cybele.kernel.CybeleEvent;
import cybele.kernel.Handler;

/** Experiment F (supporting): with cybele.prop:90 = "Local;NoSerialization",
 *  is a Serializable payload passed BY REFERENCE (same object identity at the
 *  receiver)?  Station.java:67 ships the live Station.Info instance to the GUI. */
public class ExpF {
    static final CountDownLatch ready = new CountDownLatch(1);
    static final CountDownLatch got = new CountDownLatch(1);
    static Box sent;
    static Box received;

    public static class Box implements Serializable {
        private static final long serialVersionUID = 1L;
        int value;
    }

    public static class Recv implements Handler {
        private static final long serialVersionUID = 1L;
        public Recv() { Activity.openChannel("BOX", "h", this); ready.countDown(); }
        public void h(CybeleEvent ev) { received = (Box) ev.getMessage()[0]; got.countDown(); }
    }

    public static class Send implements Handler {
        private static final long serialVersionUID = 1L;
        public Send() {
            try { ready.await(); } catch (InterruptedException e) { /* ignore */ }
            sent = new Box();
            sent.value = 1;
            Activity.sendAll("BOX", new Serializable[]{sent});
        }
    }

    public static void main(String[] args) throws Exception {
        Cybele.startUp();
        Cybele.createAgent("recv", Recv.class.getName());
        Cybele.createAgent("send", Send.class.getName());
        got.await();
        System.out.println("  identical object at receiver = " + (sent == received));
        sent.value = 42;   // sender mutates AFTER the send
        Thread.sleep(300);
        System.out.println("  receiver sees post-send mutation: received.value = " + received.value
                + " (42 => live alias)");
        System.exit(0);
    }
}
