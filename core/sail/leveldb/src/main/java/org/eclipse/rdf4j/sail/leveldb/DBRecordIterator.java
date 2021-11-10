package org.eclipse.rdf4j.sail.leveldb;

import java.io.IOException;

import org.iq80.leveldb.DBIterator;

public class DBRecordIterator implements RecordIterator {
    final DBIterator wrapped;

    public DBRecordIterator(DBIterator wrapped) {
        this.wrapped = wrapped;
    }

    @Override
    public byte[] next() throws IOException {
        if (wrapped.hasNext()) {
            return wrapped.next().getKey();
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        wrapped.close();
    }
}
