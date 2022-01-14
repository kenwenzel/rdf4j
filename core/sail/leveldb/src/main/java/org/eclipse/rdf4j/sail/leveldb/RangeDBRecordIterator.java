package org.eclipse.rdf4j.sail.leveldb;

import java.io.IOException;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map.Entry;

import org.eclipse.rdf4j.common.io.ByteArrayUtil;
import org.eclipse.rdf4j.sail.leveldb.model.NativeValue;

public class RangeDBRecordIterator implements RecordIterator {

    private final Comparator<NativeValue[]> comparator;

    private final Iterator<Entry<NativeValue[], Boolean>> wrapped;

    private final NativeValue[] searchKey;

    private final boolean[] searchMask;

    private final NativeValue[] minValue;

    private final NativeValue[] maxValue;

    public RangeDBRecordIterator(Comparator<NativeValue[]> comparator, Iterator<Entry<NativeValue[], Boolean>> wrapped,
        NativeValue[] searchKey, boolean[] searchMask, NativeValue[] minValue, NativeValue[] maxValue) {
        this.comparator = comparator;
        this.wrapped = wrapped;
        this.searchKey = searchKey;
        this.searchMask = searchMask;
        this.minValue = minValue;
        this.maxValue = maxValue;
    }

    @Override
    public Record next() throws IOException {
        while (wrapped.hasNext()) {
            Entry<NativeValue[], Boolean> value = wrapped.next();
            NativeValue[] key = value.getKey();
            if (maxValue != null && comparator.compare(maxValue, key) < 0) {
                // Reached maximum value, stop iterating
                close();
                return null;
            } else if (searchKey != null && !matchesPattern(key, searchMask, searchKey)) {
                // Value doesn't match search key/mask
                continue;
            } else {
                // Matching value found
                return new Record(value.getKey(), value.getValue());
            }
        }
        return null;
    }

    static boolean matchesPattern(NativeValue[] value, boolean[] mask, NativeValue[] pattern) {
        for (int i = 0; i < value.length; i++) {
            if (mask[i] && value[i].getInternalID() != pattern[i].getInternalID()) {
                return false;
            }
        }

        return true;
    }

    @Override
    public void close() throws IOException {
        // wrapped.close();
    }
}
