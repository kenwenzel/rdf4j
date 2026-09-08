/*******************************************************************************
 * Copyright (c) 2026 Eclipse RDF4J contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *
 * SPDX-License-Identifier: BSD-3-Clause
 *******************************************************************************/
package org.eclipse.rdf4j.sail.lmdb.util;

import java.nio.ByteBuffer;
import java.util.NoSuchElementException;

import org.eclipse.collections.api.map.primitive.MutableLongIntMap;
import org.eclipse.collections.impl.map.mutable.primitive.LongIntHashMap;
import org.eclipse.rdf4j.sail.lmdb.Varint;

/**
 * Cursor for iterating over consecutive tuples whose elements are encoded as unsigned varints.
 * <p>
 * The encoding supports <em>position reuse</em>: a zero byte acts as a reuse marker, meaning that the element at the
 * same index within the previously decoded tuple must be re-read from its original buffer position. This avoids storing
 * duplicate varint values in the underlying buffer.
 * </p>
 * <p>
 * Typical usage:
 * </p>
 *
 * <pre>
 * {
 * 	&#64;code
 * 	VarintTupleIO input = new VarintTupleIO(4, buffer);
 * 	while (input.hasNext()) {
 * 		for (int i = 0; i < 4; i++) {
 * 			input.next();
 * 			long value = input.readUnsigned();
 * 		}
 * 		input.nextTuple();
 * 	}
 * }
 * </pre>
 * <p>
 * This class operates directly on the supplied {@link ByteBuffer} and mutates its position. It is not thread-safe.
 * </p>
 */
public final class VarintTupleIO {

	public static final class Encoder {
		final int elements;
		final ByteBuffer out;
		final MutableLongIntMap reusePositionsMap = new LongIntHashMap();
		int reuseOffset = 0;
		boolean encodeNextTuple = false;

		Encoder(int elements, ByteBuffer out) {
			this.elements = elements;
			this.out = out;
		}

		public void resetDeltaEncoding() {
			this.reusePositionsMap.clear();
			this.reuseOffset = out.position();
			this.encodeNextTuple = false;
		}

		public void append(ByteBuffer tuple) {
			int otherValuePosition = tuple.position();
			for (int i = 0; i < elements; i++) {
				final int otherLength = Varint.firstToLength(tuple.get(otherValuePosition));

				boolean encode = encodeNextTuple && otherLength > 2;
				if (encode) {
					final long otherValue = Varint.readUnsigned(tuple, otherValuePosition);
					int pos = reusePositionsMap.getIfAbsent(otherValue, -1);
					if (pos != -1) {
						out.put((byte) 0);
						Varint.writeUnsigned(out, pos);
						otherValuePosition += otherLength;
						continue;
					} else {
						reusePositionsMap.put(otherValue, out.position() - reuseOffset);
					}
				}

				int pos = out.position();
				out.put(pos, tuple, otherValuePosition, otherLength);
				out.position(pos + otherLength);
				otherValuePosition += otherLength;
			}
			encodeNextTuple = true;
		}

		public void appendNextTuple(VarintTupleIO input) {
			ByteBuffer buffer = input.getBuffer();
			for (int i = 0; i < elements; i++) {
				if (!input.next()) {
					throw new NoSuchElementException("No element at index " + i);
				}

				final int otherValuePosition = buffer.position();
				final int otherLength = Varint.firstToLength(buffer.get(otherValuePosition));

				boolean encode = encodeNextTuple && otherLength > 2;
				if (encode) {
					final long otherValue = Varint.readUnsigned(buffer, otherValuePosition);
					int pos = reusePositionsMap.getIfAbsent(otherValue, -1);
					if (pos != -1) {
						out.put((byte) 0);
						Varint.writeUnsigned(out, pos);
						continue;
					} else {
						reusePositionsMap.put(otherValue, out.position() - reuseOffset);
					}
				}

				int pos = out.position();
				out.put(pos, buffer, otherValuePosition, otherLength);
				out.position(pos + otherLength);
			}
			input.nextTuple();
			encodeNextTuple = true;
		}
	}

	/** Number of elements in each tuple. */
	private final int elements;

	/** Buffer containing the encoded tuple data. */
	private final ByteBuffer buffer;

	/** Index of the current element within the tuple, or {@code -1} before the first element of a tuple. */
	private int index = -1;

	/**
	 * Buffer position at which iteration continues.
	 */
	private int nextPosition = 0;

	/** Buffer position of the first byte of the current tuple. */
	private int tupleStartPosition;

	/**
	 * Creates a tuple input cursor starting at the current position of the given buffer.
	 *
	 * @param elements the number of elements contained in each tuple, must be positive
	 * @param buffer   the buffer containing varint-encoded tuple data
	 * @throws IllegalArgumentException if {@code elements} is not positive
	 * @throws NullPointerException     if {@code buffer} is {@code null}
	 */
	public VarintTupleIO(int elements, ByteBuffer buffer) {
		if (elements <= 0) {
			throw new IllegalArgumentException("elements must be positive, was: " + elements);
		}
		if (buffer == null) {
			throw new NullPointerException("buffer must not be null");
		}
		this.elements = elements;
		this.buffer = buffer;
		this.tupleStartPosition = buffer.position();
	}

	/**
	 * Returns the underlying buffer. The buffer's position reflects the current cursor state and must only be modified
	 * through this cursor.
	 *
	 * @return the buffer backing this cursor
	 */
	public ByteBuffer getBuffer() {
		return buffer;
	}

	/**
	 * Returns the number of elements per tuple.
	 *
	 * @return the tuple arity
	 */
	public int getElements() {
		return elements;
	}

	/**
	 * Tests whether at least one further element can be decoded.
	 *
	 * @return {@code true} if {@link #next()} would succeed
	 */
	public boolean hasNext() {
		return nextPosition < buffer.limit();
	}

	/**
	 * Positions the buffer at the next tuple element without decoding it.
	 * <p>
	 * A non-zero byte marks the start of a directly encoded unsigned varint, which is then remembered as the reuse
	 * position for the current element index. A zero byte is a reuse marker; in that case the buffer is moved to the
	 * position stored for the current element index and iteration resumes after the marker on the following call.
	 * </p>
	 *
	 * @return {@code true} if an element is available; {@code false} if the buffer has no remaining data
	 */
	public boolean next() {
		buffer.position(nextPosition);
		if (!buffer.hasRemaining()) {
			return false;
		}
		if (++index == elements) {
			index = 0;
		}
		if (index == 0) {
			tupleStartPosition = buffer.position();
		}
		resolveReuse();
		return true;
	}

	/**
	 * Decodes the element the cursor currently points at. Must be called after a successful {@link #next()} and at most
	 * once per element.
	 *
	 * @return the decoded unsigned value
	 */
	public long readUnsigned() {
		return Varint.readUnsigned(buffer);
	}

	/**
	 * Advances past the current value.
	 * <p>
	 * Directly encoded values are skipped using {@link Varint#skipUnsigned(ByteBuffer)}. Reused values do not consume a
	 * varint at the current position, because their bytes reside at an earlier buffer position that is left untouched.
	 * </p>
	 *
	 * @return {@code true} if another value is available after skipping; {@code false} otherwise
	 */
	public boolean skip() {
		if (!hasNext()) {
			throw new NoSuchElementException("Buffer has no more elements.");
		}
		if (next()) {
			return hasNext();
		}
		return false;
	}

	/**
	 * Rewinds the cursor to the beginning of the current tuple, so that its elements can be decoded again.
	 */
	public void resetTuple() {
		buffer.position(tupleStartPosition);
		nextPosition = tupleStartPosition;
		index = -1;
	}

	/**
	 * Marks the current tuple as fully consumed and prepares the cursor for the next tuple.
	 *
	 * @throws IllegalStateException if not all elements of the current tuple have been processed
	 */
	public void nextTuple() {
		if (index != elements - 1 && index != -1) {
			throw new IllegalStateException(
					"Cannot advance to next tuple until all elements of the current tuple have been processed");
		}
		index = -1;
		tupleStartPosition = nextPosition;
	}

	/**
	 * Resolves whether the element at the current buffer position is stored directly or reuses a previous position.
	 */
	private void resolveReuse() {
		byte first = buffer.get(nextPosition);
		if (first == 0) {
			long reusePos = Varint.readUnsigned(buffer, nextPosition + 1); // skip the reuse marker and its varint
			nextPosition += 1 + Varint.calcLengthUnsigned(reusePos); // resume after the reuse marker
			buffer.position((int) reusePos);
		} else {
			nextPosition += Varint.firstToLength(first);
		}
	}

	/**
	 * Compares the current tuple with an independently encoded tuple.
	 * <p>
	 * Reuse markers in this cursor's tuple are resolved through {@code reusePositions}. This method does not change the
	 * cursor state or either buffer's position.
	 * </p>
	 *
	 * @param otherTuple buffer positioned at the first element of the tuple to compare
	 * @return a negative value, zero, or a positive value if this tuple is respectively less than, equal to, or greater
	 *         than {@code otherTuple}
	 */
	public int compareTuple(ByteBuffer otherTuple) {
		if (otherTuple == null) {
			throw new NullPointerException("otherTuple must not be null");
		}

		int rawPosition = tupleStartPosition;
		int otherValuePosition = otherTuple.position();
		for (int i = 0; i < elements; i++) {
			boolean reuse = buffer.get(rawPosition) == 0;
			final int currentValuePosition;
			if (reuse) {
				currentValuePosition = (int) Varint.readUnsigned(buffer, rawPosition + 1);
			} else {
				currentValuePosition = rawPosition;
			}

			final byte currentFirst = buffer.get(currentValuePosition);
			final byte otherFirst = otherTuple.get(otherValuePosition);
			if (currentFirst != otherFirst) {
				return (currentFirst & 0xff) - (otherFirst & 0xff);
			}

			final int length = Varint.firstToLength(currentFirst);
			int result = length == 1 ? 0
					: compareRegion(buffer, currentValuePosition + 1, otherTuple,
							otherValuePosition + 1, length - 1);
			if (result != 0) {
				return result;
			}

			if (reuse) {
				rawPosition += 1 + Varint.calcLengthUnsigned(currentValuePosition);
			} else {
				rawPosition += length;
			}

			otherValuePosition += length;
		}
		return 0;
	}

	private static int compareRegion(ByteBuffer bb1, int startIdx1, ByteBuffer bb2, int startIdx2, int length) {
		int result = 0;
		for (int i = 0; result == 0 && i < length; i++) {
			result = (bb1.get(startIdx1 + i) & 0xff) - (bb2.get(startIdx2 + i) & 0xff);
		}
		return result;
	}

	public Encoder createEncoder(ByteBuffer out) {
		int outStartPosition = out.position();
		var encoder = new Encoder(elements, out);
		if (tupleStartPosition > 0) {
			out.put(outStartPosition, buffer, 0, tupleStartPosition);
			out.position(outStartPosition + tupleStartPosition);

			int pos = 0;
			while (pos < tupleStartPosition) {
				long value = Varint.readUnsigned(buffer, pos);
				if (value == 0) {
					int reusePos = (int) Varint.readUnsigned(buffer, pos + 1);
					pos += 1 + Varint.calcLengthUnsigned(reusePos);
					continue;
				}
				encoder.reusePositionsMap.put(value, pos + outStartPosition);
				pos += Varint.calcLengthUnsigned(value);
			}

			encoder.encodeNextTuple = true;
		}
		return encoder;
	}
}
