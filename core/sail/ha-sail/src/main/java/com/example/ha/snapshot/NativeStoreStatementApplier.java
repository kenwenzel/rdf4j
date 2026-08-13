package com.example.ha.snapshot;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.repository.RepositoryConnection;

/**
 * StatementApplier implementation that applies adds/removes into the SailRepository.
 *
 * Applies removes first, then adds, inside a single transaction for deterministic behavior.
 */
public final class NativeStoreStatementApplier implements com.example.ha.ratis.StatementApplier {
    private static final Logger LOG = Logger.getLogger(NativeStoreStatementApplier.class.getName());

    private final SailRepository repository;

    public NativeStoreStatementApplier(SailRepository repository) {
        this.repository = repository;
    }

    @Override
    public void apply(List<Statement> adds, List<Statement> removes) throws Exception {
        try (RepositoryConnection conn = repository.getConnection()) {
            conn.begin();
            try {
                // Remove then add for deterministic final state
                if (removes != null && !removes.isEmpty()) {
                    conn.remove(removes);
                }
                if (adds != null && !adds.isEmpty()) {
                    conn.add(adds);
                }
                conn.commit();
            } catch (Exception e) {
                try { conn.rollback(); } catch (Exception ignored) {}
                LOG.log(Level.SEVERE, "Error applying batch to NativeStore", e);
                throw e;
            }
        }
    }
}