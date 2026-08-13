package com.example.ha.ratis;

import java.io.IOException;
import java.util.List;

import com.example.ha.ratis.ReplicationSerializer.BatchDTO;
import org.eclipse.rdf4j.model.Statement;

/**
 * Adapter that knows how to handle an incoming Raft log payload (bytes),
 * deserialize it and call the configured StatementApplier to apply the changes
 * to the local Sail/store.
 *
 * You should call applyPayload(payload) from your actual Ratis StateMachine.applyTransaction(...)
 * implementation after extracting the log bytes for the entry.
 */
public final class RatisStateMachineAdapter {
    private final StatementApplier applier;

    public RatisStateMachineAdapter(StatementApplier applier) {
        this.applier = applier;
    }

    /**
     * Apply a single log entry payload. This deserializes the payload and calls the applier.
     *
     * @param payload the bytes appended to the Raft log
     * @throws Exception if applying to the local store failed
     */
    public void applyPayload(byte[] payload) throws Exception {
        try {
            BatchDTO dto = ReplicationSerializer.deserialize(payload);
            List<Statement> adds = ReplicationSerializer.toStatements(dto.adds);
            List<Statement> removes = ReplicationSerializer.toStatements(dto.removes);
            // Apply in order: removes then adds (adjust ordering to match your expected semantics).
            applier.apply(adds, removes);
        } catch (IOException e) {
            throw new RuntimeException("Failed to deserialize Raft log payload", e);
        }
    }
}