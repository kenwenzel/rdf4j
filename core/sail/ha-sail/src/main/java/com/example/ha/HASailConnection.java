package com.example.ha;

import java.util.ArrayList;
import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.sail.NotifyingSailConnection;
import org.eclipse.rdf4j.sail.helpers.NotifyingSailConnectionWrapper;
import org.eclipse.rdf4j.sail.SailConnectionListener;

/**
 * Per-connection wrapper that captures statementAdded/statementRemoved and transaction boundaries.
 */
public class HASailConnection extends NotifyingSailConnectionWrapper {
    private static final Logger LOGGER = Logger.getLogger(HASailConnection.class.getName());

    private final HASail parent;

    private final List<Statement> txAdds = new ArrayList<>();
    private final List<Statement> txRemoves = new ArrayList<>();

    private final SailConnectionListener listener = new SailConnectionListener() {
        @Override
        public void statementAdded(Statement st) {
            synchronized (txAdds) {
                txAdds.add(st);
            }
        }

        @Override
        public void statementRemoved(Statement st) {
            synchronized (txRemoves) {
                txRemoves.add(st);
            }
        }

        @Override
        public void clear() {
            // For simplicity, we do not handle clear here specifically.
            // A production implementation should emit a REPO_CLEAR command or include removed triples.
        }

        @Override
        public void transactionCommitted() {
            TransactionDelta delta;
            synchronized (txAdds) {
                synchronized (txRemoves) {
                    if (txAdds.isEmpty() && txRemoves.isEmpty()) {
                        txAdds.clear();
                        txRemoves.clear();
                        return;
                    }
                    delta = new TransactionDelta(new ArrayList<>(txAdds), new ArrayList<>(txRemoves));
                    txAdds.clear();
                    txRemoves.clear();
                }
            }
            parent.enqueueCommittedDelta(delta);
        }

        @Override
        public void transactionRolledBack() {
            synchronized (txAdds) {
                txAdds.clear();
            }
            synchronized (txRemoves) {
                txRemoves.clear();
            }
        }

        // Some RDF4J versions may offer transactionStarted/transactionCompleted; leave defaults.
    };

    public HASailConnection(HASail parent, NotifyingSailConnection delegate) {
        super(delegate);
        this.parent = parent;
        delegate.addConnectionListener(listener);
    }

    @Override
    public void close() {
        try {
            try {
                getDelegate().removeConnectionListener(listener);
            } catch (Exception e) {
                LOGGER.log(Level.WARNING, "Error removing listener from connection", e);
            }
        } finally {
            super.close();
        }
    }
}