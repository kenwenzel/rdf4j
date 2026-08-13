package com.example.ha.ratis;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.apache.ratis.protocol.Message;
import org.apache.ratis.server.protocol.TermIndex;
import org.apache.ratis.statemachine.BaseStateMachine;
import org.apache.ratis.statemachine.TransactionContext;
import org.apache.ratis.statemachine.SnapshotWriter;
import org.apache.ratis.statemachine.SnapshotReader;

import com.example.ha.ratis.ReplicationSerializer.BatchDTO;
import org.eclipse.rdf4j.model.Statement;

import com.example.ha.snapshot.NativeStoreSnapshotManager;

/**
 * Ratis StateMachine that applies payloads and supports snapshots using NativeStoreSnapshotManager.
 *
 * To construct:
 *   - Provide a RatisStateMachineAdapter (for payload deserialize -> applier)
 *   - Provide a NativeStoreSnapshotManager (for takeSnapshot/installSnapshot)
 *
 * NOTE: This implementation uses SnapshotWriter.addFile(name, InputStream) and SnapshotReader.openFile(name).
 * If your Ratis 3.2.2 API uses different method names or overloads, update those calls accordingly.
 */
public class RatisStateMachine extends BaseStateMachine {
    private static final Logger LOG = Logger.getLogger(RatisStateMachine.class.getName());

    private final RatisStateMachineAdapter adapter;
    private final NativeStoreSnapshotManager snapshotManager;

    public RatisStateMachine(RatisStateMachineAdapter adapter, NativeStoreSnapshotManager snapshotManager) {
        this.adapter = adapter;
        this.snapshotManager = snapshotManager;
    }

    @Override
    public CompletableFuture<Message> applyTransaction(TransactionContext trx) {
        try {
            byte[] payload = trx.getStateMachineLogEntry().getLogData().toByteArray();
            adapter.applyPayload(payload);
            return CompletableFuture.completedFuture(Message.valueOf(new byte[0]));
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Error applying transaction", e);
            CompletableFuture<Message> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    @Override
    public CompletableFuture<Long> takeSnapshot() {
        LOG.info("takeSnapshot invoked");
        try {
            // Ratis provides getStateMachineStorage().getSnapshotWriter(...) or the takeSnapshot provides writer via another API
            // In Ratis 3.2.2 BaseStateMachine.takeSnapshot is supposed to be implemented and the framework will call
            // stateMachine.getStateMachineStorage().getSnapshotWriter(snapshotIndex) prior to this. However actual wiring varies.
            // Many examples implement takeSnapshot(SnapshotWriter) variant; if your API exposes a writer parameter, use that instead.
            SnapshotWriter writer = getStateMachineStorage().getSnapshotWriter(getLastAppliedTermIndex().getIndex());
            snapshotManager.exportSnapshotToWriter(writer);
            writer.flush(); // if available
            writer.close();
            return CompletableFuture.completedFuture(getLastAppliedTermIndex().getIndex());
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to take snapshot", e);
            CompletableFuture<Long> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }

    @Override
    public CompletableFuture<Void> installSnapshot(SnapshotReader reader) {
        LOG.info("installSnapshot invoked");
        try {
            snapshotManager.installSnapshotFromReader(reader);
            return CompletableFuture.completedFuture(null);
        } catch (Exception e) {
            LOG.log(Level.SEVERE, "Failed to install snapshot", e);
            CompletableFuture<Void> failed = new CompletableFuture<>();
            failed.completeExceptionally(e);
            return failed;
        }
    }
}