package org.eclipse.rdf4j.sail.lmdb.util;

import java.nio.ByteBuffer;
import java.util.Arrays;

import org.eclipse.rdf4j.sail.lmdb.Varint;

public final class ChunkOutput {

	private static final boolean CHECK_SORTED = false;

	// Mode flags for the per-element footer byte
	private static final int MODE_VARINT = 0; // varint-encoded deltas, signs present
	private static final int MODE_PACKED = 1; // bit-packed deltas, signs present
	private static final int MODE_CONSTANT = 2; // all deltas are zero, no signs, no data

	final long[] keyTuple = new long[4];
	final long[] valueTuples;
	final long[] deltaScratch;
	int valueTuplesIndex;
	int tupleLength;
	int valueElements;
	int splitPoint;
	boolean writeKey = true;
	final int capacity;
	int size;

	public ChunkOutput(int capacity, int tupleLength, int splitPoint) {
		this.capacity = capacity;
		this.tupleLength = tupleLength;
		this.splitPoint = splitPoint;
		this.valueElements = tupleLength - splitPoint;
		this.valueTuples = new long[capacity * 4];
		this.deltaScratch = new long[capacity * 4];
	}

	public boolean addTuple(long[] tuple) {
		if (size == capacity) {
			return false;
		}

		if (writeKey) {
			System.arraycopy(tuple, 0, keyTuple, 0, tupleLength);
			writeKey = false;
		} else {
			if (CHECK_SORTED) {
				for (int element = 0; element < valueElements; element++) {
					if (valueTuplesIndex == 0) {
						int diff = Long.compare(tuple[splitPoint + element], keyTuple[splitPoint + element]);
						if (diff < 0) {
							throw new IllegalArgumentException("Tuples must be added in sorted order");
						} else if (diff > 0) {
							break;
						}
					} else {
						long prevValue = valueTuples[element * capacity + valueTuplesIndex - 1];
						int diff = Long.compare(tuple[splitPoint + element], prevValue);
						if (diff < 0) {
							throw new IllegalArgumentException("Tuples must be added in sorted order");
						} else if (diff > 0) {
							break;
						}
					}
				}
			}
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
		for (int i = 0; i < tupleLength; i++) {
			Varint.writeUnsigned(keyBuffer, keyTuple[i]);
		}
		if (size < 2) {
			return;
		}

		int valueCount = valueTuplesIndex;
		int signBytes = (valueCount + 7) >>> 3;

		int[] lengths = new int[valueElements];
		int[] modes = new int[valueElements];
		int[] bitWidths = new int[valueElements];
		boolean[] allPositive = new boolean[valueElements];

		// Analysis pass: compute deltas once per element, cache in deltaScratch,
		// and decide the cheapest encoding mode.
		for (int element = 0; element < valueElements; element++) {
			long prevValue = keyTuple[splitPoint + element];
			long maxAbsDelta = 0;
			long negativeMask = 0; // accumulates sign of all deltas (OR of "delta < 0")
			int varintLength = 0;
			int base = element * capacity;
			for (int tupleIndex = 0; tupleIndex < valueCount; tupleIndex++) {
				long value = valueTuples[base + tupleIndex];
				long delta = value - prevValue;
				long absDelta = delta < 0 ? -delta : delta;
				deltaScratch[base + tupleIndex] = absDelta;
				if (delta < 0) {
					negativeMask = 1;
				}
				if (absDelta > maxAbsDelta) {
					maxAbsDelta = absDelta;
				}
				varintLength += Varint.calcLengthUnsigned(absDelta);
				prevValue = value;
			}

			boolean isAllPositive = negativeMask == 0;
			allPositive[element] = isAllPositive;
			int usedSignBytes = isAllPositive ? 0 : signBytes;

			if (maxAbsDelta == 0) {
				modes[element] = MODE_CONSTANT;
				bitWidths[element] = 0;
				lengths[element] = 0;
				continue;
			}

			int bitWidth = bitWidth(maxAbsDelta);
			int packedLength = (bitWidth * valueCount + 7) >>> 3;
			int varintTotal = usedSignBytes + varintLength;
			int packedTotal = usedSignBytes + packedLength;

			if (packedTotal < varintTotal) {
				modes[element] = MODE_PACKED;
				bitWidths[element] = bitWidth;
				lengths[element] = packedTotal;
			} else {
				modes[element] = MODE_VARINT;
				bitWidths[element] = 0;
				lengths[element] = varintTotal;
			}
		}

		for (int element = 0; element < valueElements; element++) {
			int mode = modes[element];
			if (mode == MODE_CONSTANT) {
				continue;
			}

			int base = element * capacity;
			boolean isAllPositive = allPositive[element];

			byte[] signBuf = null;
			if (!isAllPositive) {
				signBuf = new byte[signBytes];
			}

			long prevValue = keyTuple[splitPoint + element];
			int bitWidth = bitWidths[element];
			int packedByte = 0;
			int packedBits = 0;

			for (int tupleIndex = 0; tupleIndex < valueCount; tupleIndex++) {
				long value = valueTuples[base + tupleIndex];
				long absDelta = deltaScratch[base + tupleIndex];

				if (signBuf != null && value < prevValue) {
					signBuf[tupleIndex >>> 3] |= (byte) (1 << (tupleIndex & 7));
				}

				if (mode == MODE_VARINT) {
					Varint.writeUnsigned(valueBuffer, absDelta);
				} else {
					int remainingBits = bitWidth;
					long remainingValue = absDelta;
					while (remainingBits > 0) {
						int availableBits = 8 - packedBits;
						int bitsToWrite = Math.min(remainingBits, availableBits);
						int mask = (1 << bitsToWrite) - 1;
						packedByte |= ((int) remainingValue & mask) << packedBits;
						remainingValue >>>= bitsToWrite;
						remainingBits -= bitsToWrite;
						packedBits += bitsToWrite;
						if (packedBits == 8) {
							valueBuffer.put((byte) packedByte);
							packedByte = 0;
							packedBits = 0;
						}
					}
				}
				prevValue = value;
			}

			if (mode == MODE_PACKED && packedBits > 0) {
				valueBuffer.put((byte) packedByte);
			}
			if (signBuf != null) {
				valueBuffer.put(signBuf);
			}
		}

		// Write footer
		int footerStart = valueBuffer.position();
		for (int element = 0; element < valueElements; element++) {
			Varint.writeUnsigned(valueBuffer, lengths[element]);
			int mode = modes[element];
			int flagByte;
			if (mode == MODE_CONSTANT) {
				flagByte = 0x00;
			} else {
				int signFlag = allPositive[element] ? 0x00 : 0x40;
				if (mode == MODE_PACKED) {
					flagByte = 0x80 | signFlag | bitWidths[element];
				} else {
					flagByte = signFlag; // MODE_VARINT, no packed-bit flag
				}
			}
			valueBuffer.put((byte) flagByte);
		}
		Varint.writeUnsigned(valueBuffer, valueTuplesIndex);
		int footerLength = valueBuffer.position() - footerStart;
		valueBuffer.put((byte) footerLength);
	}

	private static int bitWidth(long value) {
		if (value == 0) {
			return 0;
		}
		return 64 - Long.numberOfLeadingZeros(value);
	}

	public void reset(int tupleLength, int splitPoint) {
		this.tupleLength = tupleLength;
		this.splitPoint = splitPoint;
		this.valueElements = tupleLength - splitPoint;
		this.writeKey = true;
		this.size = 0;
		this.valueTuplesIndex = 0;
	}
}
