package com.example.ha;

import java.util.List;
import org.eclipse.rdf4j.model.Statement;

/**
 * Pluggable replication transport abstraction.
 * Implement to serialize and send batches (e.g., via Ratis).
 */
public interface Replicator {
    /**
     * Send a batch of statement additions and removals to remote replicas.
     *
     * @param adds    ordered list of added statements
     * @param removes ordered list of removed statements
     * @throws Exception on failure (caller may requeue for retry)
     */
    void sendBatch(List<Statement> adds, List<Statement> removes) throws Exception;
}