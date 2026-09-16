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
package org.eclipse.rdf4j.sail.lmdb;

import static org.eclipse.rdf4j.sail.lmdb.LmdbUtil.E;
import static org.eclipse.rdf4j.sail.lmdb.LmdbUtil.compareRegion;
import static org.lwjgl.util.lmdb.LMDB.MDB_CURRENT;
import static org.lwjgl.util.lmdb.LMDB.MDB_GET_BOTH_RANGE;
import static org.lwjgl.util.lmdb.LMDB.MDB_KEYEXIST;
import static org.lwjgl.util.lmdb.LMDB.MDB_LAST_DUP;
import static org.lwjgl.util.lmdb.LMDB.MDB_NOOVERWRITE;
import static org.lwjgl.util.lmdb.LMDB.MDB_PREV;
import static org.lwjgl.util.lmdb.LMDB.MDB_PREV_DUP;
import static org.lwjgl.util.lmdb.LMDB.MDB_SET;
import static org.lwjgl.util.lmdb.LMDB.MDB_SET_RANGE;
import static org.lwjgl.util.lmdb.LMDB.MDB_SUCCESS;
import static org.lwjgl.util.lmdb.LMDB.mdb_cursor_del;
import static org.lwjgl.util.lmdb.LMDB.mdb_cursor_get;
import static org.lwjgl.util.lmdb.LMDB.mdb_cursor_put;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;

import org.eclipse.rdf4j.sail.lmdb.util.VarintTupleIO;
import org.eclipse.rdf4j.sail.lmdb.util.VarintTupleIO.Encoder;
import org.lwjgl.util.lmdb.MDBVal;

/**
 * Utility methods for maintaining sorted LMDB duplicate-value chunks that contain varint-encoded tuples.
 */
public class Chunks {
	/**
	 * Maximum number of bytes to keep in the first encoded output chunk before spilling remaining tuples into a second
	 * chunk.
	 */
	public static final int MAX_CHUNK_SIZE = 511 - TripleIndex.MAX_KEY_LENGTH;

	/**
	 * Inserts a tuple into the sorted duplicate-value chunks for the current key, rewriting the affected chunk when
	 * needed to preserve ordering and split oversized chunks.
	 *
	 * @param cursor       the LMDB cursor positioned on the duplicates for the key
	 * @param elements     the number of tuple elements encoded in each chunk entry
	 * @param keyVal       the key buffer used for cursor operations
	 * @param dataVal      the data buffer used for cursor operations
	 * @param newKeyBuf    the encoded key to insert
	 * @param newValueBuf  the encoded tuple to insert
	 * @param keyScratch   scratch buffer used to encode replacement chunks
	 * @param valueScratch scratch buffer used to encode replacement chunks
	 * @return the LMDB result code, or {@link org.lwjgl.util.lmdb.LMDB#MDB_KEYEXIST} when the tuple already exists
	 * @throws IOException if tuple decoding or encoding fails
	 */
	static int mergeChunk(long cursor, int elements, MDBVal keyVal, MDBVal dataVal,
			ByteBuffer newKeyBuf, ByteBuffer newValueBuf, ByteBuffer keyScratch, ByteBuffer valueScratch)
			throws IOException {
		keyScratch.clear();
		final var keyPrefixLength = newKeyBuf.remaining();
		keyScratch.put(0, newKeyBuf, 0, keyPrefixLength);
		keyScratch.limit(keyPrefixLength + newValueBuf.remaining());
		keyScratch.put(keyPrefixLength, newValueBuf, 0, newValueBuf.remaining());

		keyVal.mv_data(keyScratch);

		// Position cursor at the anchor key.
		int rc = E(mdb_cursor_get(cursor, keyVal, dataVal, MDB_SET));
		if (rc == MDB_SUCCESS) {
			return MDB_KEYEXIST;
		}

		boolean hasExistingChunk = false;
		rc = E(mdb_cursor_get(cursor, keyVal, dataVal, MDB_SET_RANGE));
		if (rc == MDB_SUCCESS) {
			rc = E(mdb_cursor_get(cursor, keyVal, dataVal, MDB_PREV));
			if (rc == MDB_SUCCESS) {
				if (compareRegion(keyScratch, 0, keyVal.mv_data(), 0, keyPrefixLength) == 0) {
					hasExistingChunk = true;
				}
			}
		}

		if (!hasExistingChunk) {
			keyVal.mv_data(keyScratch);
			valueScratch.clear().limit(0);
			dataVal.mv_data(valueScratch);
			E(mdb_cursor_put(cursor, keyVal, dataVal, 0));
			return MDB_SUCCESS;
		}

		// We are positioned at the first duplicate value < newValueBuf.
		// Find the correct insertion point for newValueBuf in the selected chunk, and check if it already exists.
		var existing = new VarintTupleIO(elements, dataVal.mv_data());
		int diff = existing.seek(newValueBuf);
		if (diff == 0) {
			return MDB_KEYEXIST;
		}

		valueScratch.clear();

		// Copy the already-consumed prefix of the selected chunk, then insert newValueBuf, then
		// continue copying tuples from the selected chunk until the first output chunk is full.
		var encoder = existing.createEncoder(valueScratch);
		int firstPos = valueScratch.position();

		boolean addValueToSecondChunk = false;
		boolean addedAll = false;
		if (firstPos < MAX_CHUNK_SIZE) {
			encoder.append(newValueBuf);
			addedAll = encoder.appendAllTuples(existing, MAX_CHUNK_SIZE);

			firstPos = valueScratch.position();
		} else {
			// No room left in the first chunk after copying the prefix; start a second chunk with the new value.
			addValueToSecondChunk = true;
		}

		var firstKey = keyVal.mv_data();
		keyScratch.clear();
		keyScratch.put(0, firstKey, 0, firstKey.remaining());
		int firstKeyEnd = firstKey.remaining();

		int secondKeyEnd = firstKeyEnd;
		boolean createSecondChunk = addValueToSecondChunk || !addedAll && existing.hasNext();
		if (createSecondChunk) {
			encoder.resetDeltaEncoding();

			if (existing.hasNext()) {
				if (!addValueToSecondChunk) {
					keyScratch.put(secondKeyEnd, newKeyBuf, 0, newKeyBuf.remaining());
					secondKeyEnd += newKeyBuf.remaining();
					var buffer = existing.getBuffer();
					for (int i = 0; i < elements; i++) {
						existing.next();
						int length = Varint.firstToLength(buffer.get(buffer.position()));
						keyScratch.put(secondKeyEnd, buffer, buffer.position(), length);
						secondKeyEnd += length;
					}
					existing.nextTuple();
				}
				if (existing.hasNext()) {
					encoder.appendNextTuple(existing);
					encoder.appendAllTuples(existing, Integer.MAX_VALUE);
				}
			}
		}

		if (addValueToSecondChunk) {
			keyScratch.put(secondKeyEnd, newKeyBuf, 0, newKeyBuf.remaining());
			secondKeyEnd += newKeyBuf.remaining();
			keyScratch.put(secondKeyEnd, newValueBuf, 0, newValueBuf.remaining());
			secondKeyEnd += newValueBuf.remaining();
		}

		int secondPos = valueScratch.position();

		valueScratch.position(0);
		valueScratch.limit(firstPos);
		dataVal.mv_data(valueScratch);
		keyScratch.position(0).limit(firstKeyEnd);
		keyVal.mv_data(keyScratch);

		E(mdb_cursor_put(cursor, keyVal, dataVal, 0));

		if (createSecondChunk) {
			keyScratch.position(firstKeyEnd).limit(secondKeyEnd);
			keyVal.mv_data(keyScratch);
			valueScratch.position(firstPos);
			valueScratch.limit(secondPos);
			dataVal.mv_data(valueScratch);
			E(mdb_cursor_put(cursor, keyVal, dataVal, 0));
		}

		return MDB_SUCCESS;
	}

	/**
	 * Removes a tuple from the sorted duplicate-value chunks for the current key.
	 *
	 * @param cursor        the LMDB cursor positioned on the duplicates for the key
	 * @param elements      the number of tuple elements encoded in each chunk entry
	 * @param keyVal        the key buffer used for cursor operations
	 * @param dataVal       the data buffer used for cursor operations
	 * @param keyToDelete   the encoded key to remove
	 * @param valueToDelete the encoded tuple to remove
	 * @param keyScratch    scratch buffer used to encode replacement chunks
	 * @param valueScratch  scratch buffer used to encode replacement chunks
	 * @return {@code true} if the tuple was removed, otherwise {@code false}
	 * @throws IOException if tuple decoding or encoding fails
	 */
	static boolean deleteFromChunk(long cursor, int elements, MDBVal keyVal, MDBVal dataVal,
			ByteBuffer keyToDelete, ByteBuffer valueToDelete, ByteBuffer keyScratch, ByteBuffer valueScratch)
			throws IOException {
		keyScratch.clear();
		final var keyPrefixLength = keyToDelete.remaining();
		keyScratch.put(0, keyToDelete, 0, keyPrefixLength);
		keyScratch.limit(keyPrefixLength + valueToDelete.remaining());
		keyScratch.put(keyPrefixLength, valueToDelete, 0, valueToDelete.remaining());

		keyVal.mv_data(keyScratch);

		boolean isAnchorKey = false;
		// Position cursor at the anchor key. If the data value is empty, delete the key and return true.
		int rc = E(mdb_cursor_get(cursor, keyVal, dataVal, MDB_SET));
		if (rc == MDB_SUCCESS) {
			if (dataVal.mv_data().remaining() == 0) {
				E(mdb_cursor_del(cursor, 0));
				return true;
			}
			isAnchorKey = true;
		}

		if (!isAnchorKey) {
			boolean hasExistingChunk = false;
			rc = E(mdb_cursor_get(cursor, keyVal, dataVal, MDB_SET_RANGE));
			if (rc == MDB_SUCCESS) {
				rc = E(mdb_cursor_get(cursor, keyVal, dataVal, MDB_PREV));
				if (rc == MDB_SUCCESS) {
					if (compareRegion(keyToDelete, 0, keyVal.mv_data(), 0, keyPrefixLength) == 0) {
						hasExistingChunk = true;
					}
				}
			}

			if (!hasExistingChunk) {
				return false;
			}
		}

		if (isAnchorKey && dataVal.mv_data().remaining() == 0) {
			E(mdb_cursor_del(cursor, 0));
			return true;
		}

		var existing = new VarintTupleIO(elements, dataVal.mv_data());
		if (!isAnchorKey) {
			int diff = existing.seek(valueToDelete);
			if (diff != 0) {
				return false;
			}
			existing.resetTuple();
		}

		valueScratch.clear();
		VarintTupleIO.Encoder encoder;
		if (isAnchorKey) {
			keyScratch.limit(keyScratch.capacity()).position(keyPrefixLength);
			var buffer = existing.getBuffer();
			for (int i = 0; i < elements; i++) {
				existing.next();
				int length = Varint.firstToLength(buffer.get(buffer.position()));
				keyScratch.put(keyScratch.position(), buffer, buffer.position(), length);
				keyScratch.position(keyScratch.position() + length);
			}
			keyScratch.flip();
			keyVal.mv_data(keyScratch);
			existing.nextTuple();
			encoder = new VarintTupleIO(elements).createEncoder(valueScratch);
		} else {
			existing.skipTuple();
			encoder = existing.createEncoder(valueScratch);
		}
		while (existing.hasNext()) {
			encoder.appendNextTuple(existing);
		}
		if (isAnchorKey) {
			E(mdb_cursor_del(cursor, 0));
		}
		valueScratch.flip();
		dataVal.mv_data(valueScratch);
		E(mdb_cursor_put(cursor, keyVal, dataVal, 0));
		return true;
	}

	static String dumpChunk(ByteBuffer chunk, int elements) {
		var bb = chunk.duplicate();
		StringBuilder sb = new StringBuilder();
		while (bb.hasRemaining()) {
			if (!sb.isEmpty()) {
				sb.append(" | ");
			}
			for (int i = 0; i < elements; i++) {
				sb.append(Varint.readUnsigned(bb));
				if (i < elements - 1) {
					sb.append(",");
				}
			}
			if (elements == 4 && bb.hasRemaining()) {
				sb.append("key length remaining: ").append(bb.remaining());
				break;
			}
		}
		return sb.toString();
	}
}
