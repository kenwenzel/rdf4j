package org.eclipse.rdf4j.sail.lmdb.util;


import java.nio.ByteBuffer;
import java.util.NoSuchElementException;

import org.eclipse.rdf4j.sail.lmdb.Varint;
import static org.eclipse.rdf4j.sail.lmdb.Chunks.compareTuples;

public final class ChunkInput {
    private final ByteBuffer keyBuffer;
    private final ByteBuffer valueBuffer;
    private final int splitPoint;
    private final int elements;
    private boolean readKey = true;
    private long[] tuple;
    private boolean hasNext;

    public ChunkInput(ByteBuffer keyBuffer, ByteBuffer valueBuffer, int elements, int splitPoint) {
        this.keyBuffer = keyBuffer;
        this.valueBuffer = valueBuffer;
        this.elements = elements;
        this.splitPoint = splitPoint;
        this.tuple = new long[elements];
    }

    public long[] current() {
        return tuple;
    }

    public long[] next() {
        if (hasNext) {
            hasNext = false;
            return tuple;
        }
        long value;
        if (readKey) {
            if (!keyBuffer.hasRemaining()) {
                return null;
            }
            for (int i = 0; i < elements; i++) {
                if (!keyBuffer.hasRemaining()) {
                    throw new NoSuchElementException("No more elements in key buffer");
                }
                value = Varint.readUnsigned(keyBuffer);
                tuple[i] = value;
            }
            readKey = false;
        } else {
            if (!valueBuffer.hasRemaining()) {
                return null;
            }
            int valueElements = elements - splitPoint;
            for (int i = 0; i < valueElements; i++) {
                if (! valueBuffer.hasRemaining()) {
                    throw new NoSuchElementException("No more elements in value buffer: expected " + valueElements + " elements, but only read " + i);
                }
                long prevValue = tuple[splitPoint + i];
                byte sign = valueBuffer.get();
                long delta = Varint.readUnsigned(valueBuffer);
                if (sign == 1) {
                    delta = -delta;
                }
                value = prevValue + delta;
                tuple[splitPoint + i] = value;
            }
        }
        return tuple;
    }

    public int seek(long[] targetTuple) {
        return seek(targetTuple, null);
    }

    public int seek(long[] targetTuple, ChunkOutput output) {
        long[] currentTuple;
        while ((currentTuple = next()) != null) {
            int cmp = compareTuples(currentTuple, targetTuple);
            if (cmp >= 0) {
                hasNext = true;
                return cmp;
            }
            if (output != null) {
                output.addTuple(currentTuple);
            }
        }
        return -1;
    }

    public void reset() {
        keyBuffer.rewind();
        valueBuffer.rewind();
        readKey = true;
    }
}
