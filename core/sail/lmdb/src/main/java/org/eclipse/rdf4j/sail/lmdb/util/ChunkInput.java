package org.eclipse.rdf4j.sail.lmdb.util;

import static org.eclipse.rdf4j.sail.lmdb.Chunks.compareTuples;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.NoSuchElementException;
import java.util.function.Consumer;
import java.util.function.Function;

import org.eclipse.rdf4j.sail.lmdb.Varint;

public final class ChunkInput {
	private static final ByteBuffer EMPTY_READ_ONLY_BUFFER = ByteBuffer.allocate(0).asReadOnlyBuffer();
	private static final int MAX_ELEMENTS = 4;

	// Mode flags matching ChunkOutput's footer encoding
	private static final int MODE_VARINT = 0;
	private static final int MODE_PACKED = 1;
	private static final int MODE_CONSTANT = 2;

	private static final int FLAG_PACKED = 0x80;
	private static final int FLAG_HAS_SIGNS = 0x40;
	private static final int BIT_WIDTH_MASK = 0x3F;

	private ByteBuffer keyBuffer;
	private ByteBuffer valueBuffer;
	private int splitPoint;
	private int elements;
	private boolean readKey = true;
	private final long[] tuple;
	private boolean hasNext;
	private int[] signs;
	private boolean[] hasSigns;
	private int[] modes;
	private int[] bufferPositions;
	private int[] signPositions;
	private int[] bitOffsets;
	private int[] bitWidths;
	private int valueElements;
	private int valueTuplesIndex = -1;
	private int valueTuplesCount;

	public ChunkInput(ByteBuffer keyBuffer, ByteBuffer valueBuffer, int elements, int splitPoint) {
		this.keyBuffer = keyBuffer;
		this.valueBuffer = valueBuffer;
		this.elements = elements;
		this.splitPoint = splitPoint;
		this.valueElements = elements - splitPoint;
		this.tuple = new long[4];
	}

	public ChunkInput() {
		this(EMPTY_READ_ONLY_BUFFER, EMPTY_READ_ONLY_BUFFER, 0, 0);
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
			if (valueTuplesIndex == -1) {
				if (!valueBuffer.hasRemaining()) {
					return null;
				}
				if (this.signs == null) {
					this.signs = new int[MAX_ELEMENTS];
					this.hasSigns = new boolean[MAX_ELEMENTS];
					this.modes = new int[MAX_ELEMENTS];
					this.bufferPositions = new int[MAX_ELEMENTS];
					this.signPositions = new int[MAX_ELEMENTS];
					this.bitOffsets = new int[MAX_ELEMENTS];
					this.bitWidths = new int[MAX_ELEMENTS];
				}

				int startPos = valueBuffer.position();
				int footerEnd = startPos + valueBuffer.remaining() - 1;
				int footerLength = valueBuffer.get(footerEnd) & 0xFF;
				int footerStart = footerEnd - footerLength;
				valueBuffer.position(footerStart);

				int[] sectionLengths = new int[valueElements];
				for (int i = 0; i < valueElements; i++) {
					int sectionLength = (int) Varint.readUnsigned(valueBuffer);
					sectionLengths[i] = sectionLength;
					int flagByte = valueBuffer.get() & 0xFF;
					boolean packed = (flagByte & FLAG_PACKED) != 0;
					boolean signsPresent = (flagByte & FLAG_HAS_SIGNS) != 0;
					hasSigns[i] = signsPresent;

					if (sectionLength == 0) {
						// A zero-length section is constant; all-positive varint sections also use flag 0x00.
						modes[i] = MODE_CONSTANT;
						bitWidths[i] = 0;
					} else if (packed) {
						modes[i] = MODE_PACKED;
						bitWidths[i] = flagByte & BIT_WIDTH_MASK;
					} else {
						modes[i] = MODE_VARINT;
						bitWidths[i] = 0;
					}
				}

				valueTuplesCount = (int) Varint.readUnsigned(valueBuffer);
				int signBytes = (valueTuplesCount + 7) >>> 3;
				int sectionPosition = 0;
				for (int i = 0; i < valueElements; i++) {
					int base = startPos + sectionPosition;
					if (modes[i] == MODE_CONSTANT) {
						// No signs, no data for this element
						signPositions[i] = base;
						bufferPositions[i] = base;
					} else if (hasSigns[i]) {
						int dataLength = sectionLengths[i] - signBytes;
						signPositions[i] = base + dataLength;
						bufferPositions[i] = base;
					} else {
						signPositions[i] = -1;
						bufferPositions[i] = base;
					}
					bitOffsets[i] = 0;
					sectionPosition += sectionLengths[i];
				}
				valueBuffer.position(startPos);
				valueTuplesIndex = 0;
			}
			if (valueTuplesIndex >= valueTuplesCount) {
				return null;
			}
			for (int element = 0; element < valueElements; element++) {
				long prevValue = tuple[splitPoint + element];

				if (modes[element] == MODE_CONSTANT) {
					// Delta is always zero, value stays unchanged
					continue;
				}

				int mod8 = valueTuplesIndex % 8;
				boolean negative = false;
				if (hasSigns[element]) {
					if (mod8 == 0) {
						signs[element] = valueBuffer.get(signPositions[element]++) & 0xFF;
					}
					negative = ((signs[element] >> mod8) & 1) == 1;
				}

				long delta;
				if (modes[element] == MODE_VARINT) {
					delta = Varint.readUnsigned(valueBuffer, bufferPositions[element]);
					bufferPositions[element] += Varint.calcLengthUnsigned(delta);
				} else {
					delta = readPackedUnsigned(valueBuffer, bufferPositions, bitOffsets, element,
							bitWidths[element]);
				}
				if (negative) {
					delta = -delta;
				}
				tuple[splitPoint + element] = prevValue + delta;
			}
			valueTuplesIndex++;
		}
		return tuple;
	}

	public int seek(long[] targetTuple) {
		return seek(targetTuple, null);
	}

	public int seek(long[] targetTuple, Consumer<long[]> output) {
		long[] currentTuple;
		while ((currentTuple = next()) != null) {
			int cmp = compareTuples(currentTuple, targetTuple);
			if (cmp >= 0) {
				hasNext = true;
				return cmp;
			}
			if (output != null) {
				output.accept(currentTuple);
			}
		}
		return -1;
	}

	public void reset(ByteBuffer keyBuffer, ByteBuffer valueBuffer, int elements, int splitPoint) {
		this.keyBuffer = keyBuffer;
		this.valueBuffer = valueBuffer;
		this.elements = elements;
		this.splitPoint = splitPoint;
		this.valueElements = elements - splitPoint;
		readKey = true;
		valueTuplesIndex = -1;
		hasNext = false;
	}

	public void reset() {
		keyBuffer.rewind();
		valueBuffer.rewind();
		readKey = true;
		valueTuplesIndex = -1;
		hasNext = false;
	}

	private static long readPackedUnsigned(ByteBuffer buffer, int[] positions, int[] bitOffsets, int element,
			int bitWidth) {
		int bytePos = positions[element];
		int bitOffset = bitOffsets[element];

		// 1. O(1) State Update: Calculate and store final positions immediately using bitwise math.
		// This eliminates the need to simulate position tracking inside the loop.
		int totalBits = bitOffset + bitWidth;
		positions[element] = bytePos + (totalBits >> 3); // equivalent to totalBits / 8
		bitOffsets[element] = totalBits & 7; // equivalent to totalBits % 8

		long value = 0;
		int shift = 0;

		// 2. Tightened Extraction Loop
		while (bitWidth > 0) {
			// Read byte and eagerly increment. (ByteBuffer.get(int) is highly intrinsified by the JVM)
			int currentByte = buffer.get(bytePos++) & 0xFF;

			int availableBits = 8 - bitOffset;
			int bitsToRead = Math.min(bitWidth, availableBits);

			// Extract chunk and accumulate directly into the long value
			long chunk = (currentByte >>> bitOffset) & ((1L << bitsToRead) - 1);
			value |= chunk << shift;

			shift += bitsToRead;
			bitWidth -= bitsToRead;

			// After the first partial byte, subsequent bytes will always start at bit 0.
			// Hardcoding this to 0 removes the need for conditional branches.
			bitOffset = 0;
		}

		return value;
	}
}
