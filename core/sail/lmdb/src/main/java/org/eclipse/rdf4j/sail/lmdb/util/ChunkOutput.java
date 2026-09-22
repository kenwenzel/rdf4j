package org.eclipse.rdf4j.sail.lmdb.util;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.eclipse.rdf4j.sail.lmdb.Varint;

public final class ChunkOutput {
    final long[] keyTuple = new long[4];
    final long[] valueTuples;
    int valueTuplesIndex;
    final int valueElements;
    final int splitPoint;
    boolean writeKey = true;
    final int capacity;
    int size;

    public ChunkOutput(int capacity, int splitPoint) {
        this.capacity = capacity;
        this.valueElements = 4 - splitPoint;
        valueTuples = new long[capacity * valueElements];
        this.splitPoint = splitPoint;
    }

    public boolean addTuple(long[] tuple) {
        if (size == capacity) {
            return false;
        }
        if (writeKey) {
            System.arraycopy(tuple, 0, keyTuple, 0, 4);
            writeKey = false;
        } else {
            for (int element = 0; element < valueElements; element++) {
                valueTuples[element * capacity + valueTuplesIndex] = tuple[splitPoint + element];
            }
            valueTuplesIndex++;
        }
        size++;
        return true;
    }

    public int size() {
        return size;
    }

    public void write(ByteBuffer keyBuffer, ByteBuffer valueBuffer) {
        for (int i = 0; i < 4; i++) {
            Varint.writeUnsigned(keyBuffer, keyTuple[i]);
        }
        if (size < 2) {
            return;
        }
        int[] lengths = new int[valueElements];
        for (int element = 0; element < valueElements; element++) {
            int start = valueBuffer.position();
            long prevValue = keyTuple[splitPoint + element];
            for (int tupleIndex = 0; tupleIndex < valueTuplesIndex; tupleIndex++ ) {
                long value = valueTuples[element * capacity + tupleIndex];
                long delta = value - prevValue;
                prevValue = value;
            }
            prevValue = keyTuple[splitPoint + element];
            for (int tupleIndex = 0; tupleIndex < valueTuplesIndex; ) {
                int signs = 0;
                int signPos = valueBuffer.position();
                valueBuffer.put((byte) 0); // placeholder for signs
                for (int j = 0; j < 8 && tupleIndex < valueTuplesIndex; j++, tupleIndex++) {
                    long value = valueTuples[element * capacity + tupleIndex];
                    long delta = value - prevValue;
                    signs |= (delta < 0 ? 1 : 0) << j;
                    Varint.writeUnsigned(valueBuffer, Math.abs(delta));
                    prevValue = value;
                }
                valueBuffer.put(signPos, (byte) signs);
            }
            lengths[element] = valueBuffer.position() - start;
        }
        // write footer
        int footerStart = valueBuffer.position();
        for (int element = 0; element < valueElements; element++) {
            Varint.writeUnsigned(valueBuffer, lengths[element]);
        }
        Varint.writeUnsigned(valueBuffer, valueTuplesIndex);
        valueBuffer.put((byte)(valueBuffer.position() - footerStart));
    }
}
