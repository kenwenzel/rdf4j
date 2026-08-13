package com.example.ha;

import java.util.Collections;
import java.util.List;
import org.eclipse.rdf4j.model.Statement;

/**
 * Immutable container for per-transaction statement changes.
 */
public final class TransactionDelta {
    private final List<Statement> adds;
    private final List<Statement> removes;

    public TransactionDelta(List<Statement> adds, List<Statement> removes) {
        this.adds = adds == null ? Collections.emptyList() : Collections.unmodifiableList(adds);
        this.removes = removes == null ? Collections.emptyList() : Collections.unmodifiableList(removes);
    }

    public int getAddCount() {
        return adds.size();
    }

    public int getRemoveCount() {
        return removes.size();
    }

    public List<Statement> getAdds() {
        return adds;
    }

    public List<Statement> getRemoves() {
        return removes;
    }

    static TransactionDelta fromCombined(CombinedDelta combined) {
        return new TransactionDelta(combined.getAdds(), combined.getRemoves());
    }
}