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
    private int[] signs;
    private int[] bufferPositions;
    private final int valueElements;
    private int valueTuplesIndex = -1;
    private int valueTuplesCount;

    public ChunkInput(ByteBuffer keyBuffer, ByteBuffer valueBuffer, int elements, int splitPoint) {
        this.keyBuffer = keyBuffer;
        this.valueBuffer = valueBuffer;
        this.elements = elements;
        this.splitPoint = splitPoint;
        this.tuple = new long[elements];
        this.valueElements = elements - splitPoint;
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
            if (valueTuplesIndex == -1) {
                this.signs = new int[valueElements];
                this.bufferPositions = new int[valueElements];

                int startPos = valueBuffer.position();
                int footerLength = valueBuffer.get(startPos + valueBuffer.remaining() - 1) & 0xFF;
                valueBuffer.position(startPos + valueBuffer.remaining() - 1 - footerLength);

                bufferPositions[0] = 0;
                for (int i = 1; i < valueElements; i++) {
                    bufferPositions[i] = bufferPositions[i - 1] + (int) Varint.readUnsigned(valueBuffer);
                }
                Varint.skipUnsigned(valueBuffer); // skip last length
                valueTuplesCount = (int) Varint.readUnsigned(valueBuffer);

                valueBuffer.position(startPos);
                valueTuplesIndex = 0;
            }
            if (valueTuplesIndex >= valueTuplesCount) {
                return null;
            }
            for (int element = 0; element < valueElements; element++) {
                valueBuffer.position(bufferPositions[element]);

                int mod8 = valueTuplesIndex % 8;
                if (mod8 == 0) {
                    signs[element] = valueBuffer.get() & 0xFF;
                }

                long prevValue = tuple[splitPoint + element];
                long delta = Varint.readUnsigned(valueBuffer);
                if (((signs[element] >> mod8) & 1) == 1) {
                    delta = -delta;
                }
                value = prevValue + delta;
                tuple[splitPoint + element] = value;

                bufferPositions[element] = valueBuffer.position();
            }
            valueTuplesIndex++;
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
        valueTuplesIndex = -1;
    }
}
