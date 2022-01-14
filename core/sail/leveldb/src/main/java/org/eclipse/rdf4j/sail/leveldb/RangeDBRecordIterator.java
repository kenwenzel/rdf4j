package org.eclipse.rdf4j.sail.leveldb;

import java.io.IOException;
import java.util.Comparator;
import java.util.Iterator;
import java.util.Map.Entry;

import org.eclipse.rdf4j.common.io.ByteArrayUtil;

public class RangeDBRecordIterator implements RecordIterator {

    private final Comparator<long[]> comparator;

    private final Iterator<Entry<long[], Boolean>> wrapped;

    private final long[] searchKey;

    private final long[] searchMask;

    private final long[] minValue;

    private final long[] maxValue;

    public RangeDBRecordIterator(Comparator<long[]> comparator, Iterator<Entry<long[], Boolean>> wrapped,
        long[] searchKey, long[] searchMask, long[] minValue, long[] maxValue) {
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
            Entry<long[], Boolean> value = wrapped.next();
            long[] key = value.getKey();
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

    static boolean matchesPattern(long[] value, long[] mask, long[] pattern) {
        for (int i = 0; i < value.length; i++) {
            if (((value[i] ^ pattern[i]) & mask[i]) != 0) {
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
