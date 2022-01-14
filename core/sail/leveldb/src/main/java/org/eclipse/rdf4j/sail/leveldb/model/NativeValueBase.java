package org.eclipse.rdf4j.sail.leveldb.model;

import org.eclipse.rdf4j.sail.leveldb.ValueStoreRevision;

public class NativeValueBase implements NativeValue {
    long id;

    public NativeValueBase(long id) {
        this.id = id;
    }

    @Override
    public void setInternalID(long id, ValueStoreRevision revision) {
        this.id = id;
    }

    @Override
    public long getInternalID() {
        return id;
    }

    @Override
    public ValueStoreRevision getValueStoreRevision() {
        return null;
    }

    @Override
    public String stringValue() {
        return null;
    }
}
