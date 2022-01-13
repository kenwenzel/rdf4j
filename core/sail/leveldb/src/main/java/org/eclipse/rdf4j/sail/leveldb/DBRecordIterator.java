package org.eclipse.rdf4j.sail.leveldb;

import java.io.IOException;
import java.util.Iterator;

public class DBRecordIterator implements RecordIterator {
    final Iterator<byte[]> wrapped;

    public DBRecordIterator(Iterator<byte[]> wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public byte[] next() throws IOException {
        if (wrapped.hasNext()) {
            return wrapped.next();
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        // do nothing
    }
}
