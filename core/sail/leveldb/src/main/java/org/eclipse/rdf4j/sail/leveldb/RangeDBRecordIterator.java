package org.eclipse.rdf4j.sail.leveldb;

import java.io.IOException;

import org.eclipse.rdf4j.common.io.ByteArrayUtil;
import org.iq80.leveldb.DBComparator;
import org.iq80.leveldb.DBIterator;

public class RangeDBRecordIterator implements RecordIterator {

    private final DBComparator comparator;

    private final DBIterator wrapped;

    private final byte[] searchKey;

    private final byte[] searchMask;

    private final byte[] minValue;

    private final byte[] maxValue;

    public RangeDBRecordIterator(DBComparator comparator, DBIterator wrapped,
        byte[] searchKey, byte[] searchMask, byte[] minValue, byte[] maxValue) {
        this.comparator = comparator;
        this.wrapped = wrapped;
        this.searchKey = searchKey;
        this.searchMask = searchMask;
        this.minValue = minValue;
        this.maxValue = maxValue;
        if (minValue != null) {
            wrapped.seek(minValue);
        }
    }

    @Override
    public byte[] next() throws IOException {
        while (wrapped.hasNext()) {
            byte[] value = wrapped.next().getKey();
            if (maxValue != null && comparator.compare(maxValue, value) < 0) {
                // Reached maximum value, stop iterating
                close();
                return null;
            } else if (searchKey != null && !ByteArrayUtil.matchesPattern(value, searchMask, searchKey)) {
                // Value doesn't match search key/mask
                continue;
            } else {
                // Matching value found
                return value;
            }
        }
        return null;
    }

    @Override
    public void close() throws IOException {
        wrapped.close();
    }
}
