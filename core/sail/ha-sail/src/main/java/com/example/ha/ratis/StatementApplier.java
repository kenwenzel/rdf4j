package com.example.ha.ratis;

import java.util.List;
import org.eclipse.rdf4j.model.Statement;

/**
 * Callback used by the state machine adapter to apply a batch of statements to the local store.
 */
@FunctionalInterface
public interface StatementApplier {
    void apply(List<Statement> adds, List<Statement> removes) throws Exception;
}