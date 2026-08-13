package com.example.ha;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.rdf4j.sail.NotifyingSail;
import org.eclipse.rdf4j.sail.helpers.NotifyingSailWrapper;
import org.eclipse.rdf4j.sail.NotifyingSailConnection;

/**
 * HASail wraps a NotifyingSail and batches per-transaction deltas to a Replicator.
 */
public class HASail extends NotifyingSailWrapper {

    private static final Logger LOGGER = Logger.getLogger(HASail.class.getName());

    private final Replicator replicator;
    private final int batchFlushThreshold;
    private final Duration periodicFlushInterval;

    // queue of committed per-transaction TransactionDelta waiting to be sent
    private final BlockingQueue<TransactionDelta> outgoingQueue = new LinkedBlockingQueue<>();

    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "HASail-PeriodicFlusher");
        t.setDaemon(true);
        return t;
    });

    private final ExecutorService senderExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "HASail-Sender");
        t.setDaemon(true);
        return t;
    });

    private final ScheduledFuture<?> periodicFlushTask;

    private volatile boolean closed = false;

    /**
     * Construct HASail
     *
     * @param delegate             must be a NotifyingSail
     * @param replicator           transport implementation
     * @param batchFlushThreshold  flush when approx pending statements >= threshold
     * @param periodicFlushInterval periodic flush interval
     */
    public HASail(NotifyingSail delegate,
                  Replicator replicator,
                  int batchFlushThreshold,
                  Duration periodicFlushInterval) {
        super(delegate);
        this.replicator = Objects.requireNonNull(replicator, "replicator");
        this.batchFlushThreshold = Math.max(1, batchFlushThreshold);
        this.periodicFlushInterval = Objects.requireNonNull(periodicFlushInterval, "periodicFlushInterval");

        this.periodicFlushTask = scheduler.scheduleWithFixedDelay(this::flushIfNeeded,
                periodicFlushInterval.toMillis(),
                periodicFlushInterval.toMillis(),
                TimeUnit.MILLISECONDS);
    }

    @Override
    public NotifyingSailConnection getConnection() {
        NotifyingSailConnection delegateConn = super.getConnection();
        return new HASailConnection(this, delegateConn);
    }

    /**
     * Package-private so HASailConnection can enqueue committed deltas.
     */
    void enqueueCommittedDelta(TransactionDelta delta) {
        if (closed) {
            LOGGER.log(Level.WARNING, "HASail is closed; dropping delta: {0}", delta);
            return;
        }
        outgoingQueue.add(delta);
        if (approxPendingStatementCount() >= batchFlushThreshold) {
            triggerAsyncFlush();
        }
    }

    private int approxPendingStatementCount() {
        int total = 0;
        for (TransactionDelta d : outgoingQueue) {
            total += d.getAddCount() + d.getRemoveCount();
            if (total < 0) {
                // overflow guard
                return Integer.MAX_VALUE;
            }
        }
        return total;
    }

    private void triggerAsyncFlush() {
        senderExecutor.submit(this::flushAndSend);
    }

    private void flushIfNeeded() {
        try {
            if (!outgoingQueue.isEmpty()) {
                triggerAsyncFlush();
            }
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Exception while periodic flush", e);
        }
    }

    private void flushAllBlocking() {
        try {
            flushAndSend();
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Error while flushing remaining batches", e);
        }
    }

    private void flushAndSend() {
        List<TransactionDelta> drained = new ArrayList<>();
        outgoingQueue.drainTo(drained);

        if (drained.isEmpty()) {
            return;
        }

        CombinedDelta combined = CombinedDelta.combine(drained);
        try {
            replicator.sendBatch(combined.getAdds(), combined.getRemoves());
        } catch (Exception e) {
            LOGGER.log(Level.SEVERE, "Failed to send batch to replicator. Re-queueing combined batch.", e);
            outgoingQueue.add(TransactionDelta.fromCombined(combined));
        }
    }

    @Override
    public void shutDown() throws Exception {
        closed = true;
        if (periodicFlushTask != null) {
            periodicFlushTask.cancel(true);
        }
        scheduler.shutdownNow();

        // flush outstanding batches synchronously
        flushAllBlocking();

        senderExecutor.shutdown();
        senderExecutor.awaitTermination(10, TimeUnit.SECONDS);

        super.shutDown();
    }
}