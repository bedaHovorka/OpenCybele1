/*
 * Projekt AGS 2007/08
 * FIT VUT Brno
 *
 * Open Cybele 1
 *
 * Bedrich Hovorka
 * xhovor07@stud.fit.vutbr.cz
 */
package cz.vutbr.fit.ags.railway.domain;

import java.io.Serializable;
import java.util.LinkedList;
import java.util.Queue;

/**
 * The queue of trains a full station is holding at its door — extracted from
 * {@code Station.QueueItem} plus the {@link LinkedList} it lived in (#28).
 *
 * <p>The ordering rule is <b>plain FIFO</b>, and that is the whole rule: unlike
 * {@link RoadQueue} there is no comparator, no timetable lookup and no tie-break. It is
 * extracted anyway so that a port cannot quietly "improve" it into a priority queue —
 * which would be a behaviour change under the Phase-1 scope guard.</p>
 */
public final class StationQueue implements Serializable {
    private static final long serialVersionUID = 1L;

    /** One waiting train: its id and the destination it is ultimately headed for. */
    public static final class Waiting implements Serializable {
        private static final long serialVersionUID = 1L;
        private final String train;
        private final String endStation;

        Waiting(String train, String endStation) {
            this.train = train;
            this.endStation = endStation;
        }

        /** @return the waiting train's id */
        public String getTrain() {
            return train;
        }

        /** @return the train's final destination, used to resolve the onward direction */
        public String getEndStation() {
            return endStation;
        }
    }

    private final Queue<Waiting> queue = new LinkedList<Waiting>();

    /**
     * @param train train id
     * @param endStation the train's final destination
     */
    public void offer(String train, String endStation) {
        queue.offer(new Waiting(train, endStation));
    }

    /** @return the longest-waiting train, or {@code null} if none is waiting */
    public Waiting poll() {
        return queue.poll();
    }

    /** @return how many trains are waiting */
    public int size() {
        return queue.size();
    }
}
