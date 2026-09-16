package org.eclipse.rdf4j.sail.lmdb.util;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.eclipse.rdf4j.sail.lmdb.Varint;

public class ChunkOutput {
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
            System.arraycopy(tuple, splitPoint, valueTuples, valueTuplesIndex * valueElements, valueElements);
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
        if (valueTuplesIndex > 0) {
            for (int j = 0; j < valueElements; j++) {
                long delta = valueTuples[j] - keyTuple[splitPoint + j];
                byte sign = (byte) (delta < 0 ? 1 : 0);
                valueBuffer.put(sign);
                Varint.writeUnsigned(valueBuffer, Math.abs(delta));
            }
        }
        for (int i = 1; i < valueTuplesIndex; i++) {
            for (int j = 0; j < valueElements; j++) {
                long delta = valueTuples[i * valueElements + j] - valueTuples[(i - 1) * valueElements + j];
                byte sign = (byte) (delta < 0 ? 1 : 0);
                valueBuffer.put(sign);
                Varint.writeUnsigned(valueBuffer, Math.abs(delta));
            }
        }
    }
}
