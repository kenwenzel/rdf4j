package com.example.ha.ratis;

import java.util.List;
import org.eclipse.rdf4j.model.Statement;

import com.example.ha.Replicator;

/**
 * Replicator implementation that serializes batches (via ReplicationSerializer)
 * and appends them to Raft via RaftClientAdapter.
 */
public class RatisReplicator implements Replicator {

    private final RaftClientAdapter client;

    public RatisReplicator(RaftClientAdapter client) {
        this.client = client;
    }

    @Override
    public void sendBatch(List<Statement> adds, List<Statement> removes) throws Exception {
        byte[] payload = ReplicationSerializer.serialize(adds, removes);
        // Append to Raft — implementation of append handles waiting for commit
        client.append(payload);
    }
}