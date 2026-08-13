package com.example.ha;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.doAnswer;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.sail.NotifyingSail;
import org.eclipse.rdf4j.sail.NotifyingSailConnection;
import org.eclipse.rdf4j.sail.SailConnectionListener;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Tests for HASail batching behavior using a mocked Replicator and mocked underlying sail/connection.
 */
public class HASailTest {

    private HASail hasail;

    @AfterEach
    public void tearDown() throws Exception {
        if (hasail != null) {
            hasail.shutDown();
        }
    }

    @Test
    public void commitShouldCauseReplicatorSend() throws Exception {
        // setup mocks
        NotifyingSail mockSail = Mockito.mock(NotifyingSail.class);
        NotifyingSailConnection mockConn = Mockito.mock(NotifyingSailConnection.class);

        when(mockSail.getConnection()).thenReturn(mockConn);

        AtomicReference<SailConnectionListener> capturedListener = new AtomicReference<>();
        doAnswer(invocation -> {
            SailConnectionListener l = invocation.getArgument(0);
            capturedListener.set(l);
            return null;
        }).when(mockConn).addConnectionListener(Mockito.any(SailConnectionListener.class));

        // create a replicator mock
        Replicator replicator = Mockito.mock(Replicator.class);

        // Very small threshold so we trigger immediate send.
        hasail = new HASail(mockSail, replicator, 1, Duration.ofSeconds(10));

        // obtain wrapped connection (this registers the listener on the mocked delegate)
        NotifyingSailConnection conn = hasail.getConnection();

        // create test statement and simulate an add + commit
        Statement st = createStatement("http://example/s", "p", "o");

        // call the captured listener methods to simulate underlying sail events
        SailConnectionListener l = capturedListener.get();
        if (l == null) {
            throw new AssertionError("Listener was not registered on the underlying connection");
        }

        l.statementAdded(st);
        l.transactionCommitted();

        // wait for asynchronous send and verify replicator was called
        verify(replicator, timeout(2000)).sendBatch(anyList(), anyList());

        conn.close();
    }

    @Test
    public void rollbackShouldDiscardChanges() throws Exception {
        NotifyingSail mockSail = Mockito.mock(NotifyingSail.class);
        NotifyingSailConnection mockConn = Mockito.mock(NotifyingSailConnection.class);

        when(mockSail.getConnection()).thenReturn(mockConn);

        AtomicReference<SailConnectionListener> capturedListener = new AtomicReference<>();
        doAnswer(invocation -> {
            SailConnectionListener l = invocation.getArgument(0);
            capturedListener.set(l);
            return null;
        }).when(mockConn).addConnectionListener(Mockito.any(SailConnectionListener.class));

        Replicator replicator = Mockito.mock(Replicator.class);
        hasail = new HASail(mockSail, replicator, 1, Duration.ofSeconds(10));
        NotifyingSailConnection conn = hasail.getConnection();

        Statement st = createStatement("http://example/s2", "p", "o");

        SailConnectionListener l = capturedListener.get();
        if (l == null) {
            throw new AssertionError("Listener was not registered on the underlying connection");
        }

        l.statementAdded(st);
        l.transactionRolledBack();

        // ensure sendBatch is never called
        Mockito.verify(replicator, Mockito.after(500).never()).sendBatch(Mockito.anyList(), Mockito.anyList());

        conn.close();
    }

    private Statement createStatement(String s, String p, String o) {
        SimpleValueFactory vf = SimpleValueFactory.getInstance();
        IRI subj = vf.createIRI(s);
        IRI pred = vf.createIRI(p);
        IRI obj = vf.createIRI(o);
        return vf.createStatement(subj, pred, obj);
    }
}