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
import static org.lwjgl.util.lmdb.LMDB.MDB_KEYEXIST;
import static org.lwjgl.util.lmdb.LMDB.MDB_LAST;
import static org.lwjgl.util.lmdb.LMDB.MDB_PREV;
import static org.lwjgl.util.lmdb.LMDB.MDB_SET;
import static org.lwjgl.util.lmdb.LMDB.MDB_SET_RANGE;
import static org.lwjgl.util.lmdb.LMDB.MDB_SUCCESS;
import static org.lwjgl.util.lmdb.LMDB.mdb_cursor_del;
import static org.lwjgl.util.lmdb.LMDB.mdb_cursor_get;
import static org.lwjgl.util.lmdb.LMDB.mdb_cursor_put;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.TreeSet;

import org.eclipse.rdf4j.sail.lmdb.util.ChunkInput;
import org.eclipse.rdf4j.sail.lmdb.util.ChunkOutput;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.lmdb.MDBVal;

/**
 * Buffers tuple insertions for a single LMDB key and merges them with an existing encoded chunk when flushed.
 */
public final class ChunkUpdater {
	ByteBuffer existingAnchorKey, existingNextAnchorKey, anchorKeyScratch, tmpKey;
	long existingAnchorKeyAddress;
	ByteBuffer existingBuffer;
	ByteBuffer targetBuffer;

	boolean sortedInsertion = false;

	final TreeSet<long[]> newTuples = new TreeSet<>(this::compareTuples);
	final List<ChunkOutput> chunks = new ArrayList<>();
	int activeChunkIndex = -1;
	int tupleLength;
	int splitPoint;

	final ChunkInput chunkInput = new ChunkInput();

	ChunkUpdater(MemoryStack stack) {
		this.anchorKeyScratch = stack.malloc(TripleIndex.MAX_KEY_LENGTH);
		this.tmpKey = stack.malloc(TripleIndex.MAX_KEY_LENGTH);
		this.targetBuffer = stack.malloc(4096);
	}

	private int compareTuples(long[] a, long[] b) {
		for (int i = 0; i < a.length; i++) {
			int cmp = Long.compare(a[i], b[i]);
			if (cmp != 0) {
				return cmp;
			}
		}
		return 0;
	}

	/**
	 * Enables or disables the optimization that assumes tuples are added in sorted order.
	 *
	 * @param sortedInsertion {@code true} when added tuples are already sorted
	 */
	void setSortedInsertion(boolean sortedInsertion) {
		this.sortedInsertion = sortedInsertion;
	}

	/**
	 * Adds a tuple to the pending update set for the current key.
	 * <p>
	 * When the key already exists, the tuple is checked against the selected duplicate chunk and merged on the next
	 * {@link #flush(long, MDBVal, MDBVal)} call. If the tuple already exists either in LMDB or among the pending
	 * tuples, {@link org.lwjgl.util.lmdb.LMDB#MDB_KEYEXIST} is returned.
	 *
	 * @param cursor  the LMDB cursor positioned on the target database
	 * @param keyVal  the LMDB key wrapper
	 * @param dataVal the LMDB value wrapper
	 * @param tuple   the tuple to add
	 * @return the LMDB status code for the operation
	 * @throws IOException if tuple encoding fails while flushing buffered state
	 */
	int add(long cursor, MDBVal keyVal, MDBVal dataVal, long[] tuple) throws IOException {
		anchorKeyScratch.clear();
		int keyPrefixLength;
		for (int i = 0; i < splitPoint; i++) {
			Varint.writeUnsigned(anchorKeyScratch, tuple[i]);
		}
		keyPrefixLength = anchorKeyScratch.position();
		for (int i = splitPoint; i < tuple.length; i++) {
			Varint.writeUnsigned(anchorKeyScratch, tuple[i]);
		}
		anchorKeyScratch.flip();

		keyVal.mv_data(anchorKeyScratch);

		int rc = E(mdb_cursor_get(cursor, keyVal, dataVal, MDB_SET));
		if (rc == MDB_SUCCESS) {
			return MDB_KEYEXIST;
		}
		boolean merge = false;
		rc = E(mdb_cursor_get(cursor, keyVal, dataVal, MDB_SET_RANGE));
		if (rc == MDB_SUCCESS) {
			E(mdb_cursor_get(cursor, keyVal, dataVal, MDB_PREV));
		} else {
			rc = E(mdb_cursor_get(cursor, keyVal, dataVal, MDB_LAST));
		}

		ByteBuffer keyBuffer = null;
		if (rc == MDB_SUCCESS) {
			keyBuffer = keyVal.mv_data();
			if (compareRegion(anchorKeyScratch, 0, keyBuffer, 0, keyPrefixLength) == 0) {
				if (compareRegion(anchorKeyScratch, 0, keyBuffer, 0,
						Math.min(anchorKeyScratch.remaining(), keyBuffer.remaining())) > 0) {
					merge = true;
				}
			}
		}

		ByteBuffer dataBuffer = null;
		if (merge) {
			if (existingAnchorKey != null && existingAnchorKeyAddress != MemoryUtil.memAddress(keyBuffer) ||
					existingAnchorKey == null && !newTuples.isEmpty()) {
				tmpKey.clear().put(anchorKeyScratch);
				flush(cursor, keyVal, dataVal);

				keyVal.mv_data(tmpKey.flip());
				rc = E(mdb_cursor_get(cursor, keyVal, dataVal, MDB_SET));
				if (rc == MDB_SUCCESS) {
					return MDB_KEYEXIST;
				}
				rc = E(mdb_cursor_get(cursor, keyVal, dataVal, MDB_SET_RANGE));
				if (rc == MDB_SUCCESS) {
					E(mdb_cursor_get(cursor, keyVal, dataVal, MDB_PREV));
				} else {
					E(mdb_cursor_get(cursor, keyVal, dataVal, MDB_LAST));
				}
				keyBuffer = keyVal.mv_data();
				dataBuffer = dataVal.mv_data();
			} else {
				dataBuffer = dataVal.mv_data();
			}
		}

		if (merge && existingAnchorKey == null) {
			// We are positioned at the first duplicate value < newValueBuf.
			// Find the correct insertion point for newValueBuf in the selected chunk, and check if it already
			// exists.
			chunkInput.reset(keyBuffer, dataBuffer, 4, splitPoint);
			int diff = chunkInput.seek(tuple);
			if (diff == 0) {
				return MDB_KEYEXIST;
			}

			existingAnchorKey = keyBuffer;
			existingAnchorKeyAddress = MemoryUtil.memAddress(existingAnchorKey);
			existingBuffer = dataBuffer;
			chunkInput.reset();
		}

		if (existingBuffer != null) {
			if (!sortedInsertion) {
				chunkInput.reset();
				if (chunkInput.seek(tuple) == 0) {
					return MDB_KEYEXIST;
				}
			} else {
				if (chunkInput.seek(tuple, t -> {
					if (activeChunkIndex < 0 || !chunks.get(activeChunkIndex).addTuple(t)) {
						getNextChunk().addTuple(t);
					}
				}) == 0) {
					return MDB_KEYEXIST;
				}
			}
		}

		if (sortedInsertion) {
			if (activeChunkIndex < 0 || !chunks.get(activeChunkIndex).addTuple(tuple)) {
				getNextChunk().addTuple(tuple);
			}
			return MDB_SUCCESS;
		}

		if (!newTuples.add(tuple.clone())) {
			return MDB_KEYEXIST;
		}
		return MDB_SUCCESS;
	}

	private ChunkOutput getNextChunk() {
		activeChunkIndex++;
		if (activeChunkIndex >= chunks.size()) {
			var chunk = new ChunkOutput(Chunks.MAX_CHUNK_SIZE, tupleLength, splitPoint);
			chunks.add(chunk);
			return chunk;
		}
		var chunk = chunks.get(activeChunkIndex);
		chunk.reset(tupleLength, splitPoint);
		return chunk;
	}

	/**
	 * Flushes all buffered tuples for the current key to LMDB.
	 * <p>
	 * If an existing duplicate chunk is being merged, that chunk is removed and replaced by one or more newly encoded
	 * chunks containing both the existing and pending tuples.
	 *
	 * @param cursor  the LMDB cursor positioned on the target database
	 * @param keyVal  the LMDB key wrapper
	 * @param dataVal the LMDB value wrapper
	 * @throws IOException if tuple encoding fails while writing the updated chunks
	 */
	public void flush(long cursor, MDBVal keyVal, MDBVal dataVal) throws IOException {
		if (!sortedInsertion && newTuples.isEmpty()) {
			return;
		}

		boolean hasExisting = existingBuffer != null;
		long[] existingTuple = null;
		ChunkOutput chunk;
		if (sortedInsertion) {
			if (hasExisting) {
				existingTuple = chunkInput.next();
			}
			chunk = activeChunkIndex >= 0 ? chunks.get(activeChunkIndex) : getNextChunk();
		} else {
			if (hasExisting) {
				chunkInput.reset();
				existingTuple = chunkInput.next();
			}

			chunk = getNextChunk();
			for (var newTuple : newTuples) {
				if (hasExisting) {
					while (existingTuple != null) {
						int cmp = compareTuples(existingTuple, newTuple);
						if (cmp > 0) {
							break;
						}
						if (cmp != 0 && !chunk.addTuple(existingTuple)) {
							chunk = getNextChunk();
							chunk.addTuple(existingTuple);
						}
						existingTuple = chunkInput.next();
					}
				}
				if (!chunk.addTuple(newTuple)) {
					chunk = getNextChunk();
					chunk.addTuple(newTuple);
				}
			}
		}

		if (hasExisting) {
			while (existingTuple != null) {
				if (!chunk.addTuple(existingTuple)) {
					chunk = getNextChunk();
					chunk.addTuple(existingTuple);
				}
				existingTuple = chunkInput.next();
			}
		}

		if (hasExisting) {
			existingAnchorKey.rewind();
			keyVal.mv_data(existingAnchorKey);
			int rc = E(mdb_cursor_get(cursor, keyVal, dataVal, MDB_SET));
			if (rc == MDB_SUCCESS) {
				// System.out.println("delete key " + Varint.readUnsigned(targetKey, 0) + " value " +
				// valueToString(existingBuffer));
				// Replace the selected duplicate value with one or two newly encoded duplicate values.
				E(mdb_cursor_del(cursor, 0));
			} else {
				// should not happen
				throw new IOException("Key not found: " + existingAnchorKey);
			}
		}

		for (int i = 0; i <= activeChunkIndex; i++) {
			ChunkOutput c = chunks.get(i);
			c.write(anchorKeyScratch.clear(), targetBuffer.clear());
			keyVal.mv_data(anchorKeyScratch.flip());
			dataVal.mv_data(targetBuffer.flip());
			E(mdb_cursor_put(cursor, keyVal, dataVal, 0));
		}
		reset(tupleLength, splitPoint);
	}

	/**
	 * Clears all buffered state so the updater can be reused for the next key.
	 */
	public void reset(int tupleLength, int splitPoint) {
		this.tupleLength = tupleLength;
		this.splitPoint = splitPoint;
		existingAnchorKey = null;
		existingBuffer = null;
		newTuples.clear();
		activeChunkIndex = -1;
	}
}
