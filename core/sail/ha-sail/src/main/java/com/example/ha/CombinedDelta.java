package com.example.ha;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.eclipse.rdf4j.model.Statement;

/**
 * A combined batch merging multiple TransactionDeltas.
 */
public final class CombinedDelta {
    private final List<Statement> adds;
    private final List<Statement> removes;

    public CombinedDelta(List<Statement> adds, List<Statement> removes) {
        this.adds = adds == null ? Collections.emptyList() : Collections.unmodifiableList(adds);
        this.removes = removes == null ? Collections.emptyList() : Collections.unmodifiableList(removes);
    }

    public List<Statement> getAdds() {
        return adds;
    }

    public List<Statement> getRemoves() {
        return removes;
    }

    public static CombinedDelta combine(List<TransactionDelta> deltas) {
        if (deltas == null || deltas.isEmpty()) {
            return new CombinedDelta(Collections.emptyList(), Collections.emptyList());
        }
        List<Statement> adds = new ArrayList<>();
        List<Statement> removes = new ArrayList<>();
        for (TransactionDelta d : deltas) {
            adds.addAll(d.getAdds());
            removes.addAll(d.getRemoves());
        }
        return new CombinedDelta(adds, removes);
    }
}