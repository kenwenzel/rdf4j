/*******************************************************************************
 * Copyright (c) 2021 Eclipse RDF4J contributors.
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
import static org.lwjgl.util.lmdb.LMDB.MDB_CURRENT;
import static org.lwjgl.util.lmdb.LMDB.MDB_FIRST;
import static org.lwjgl.util.lmdb.LMDB.MDB_LAST;
import static org.lwjgl.util.lmdb.LMDB.MDB_NEXT;
import static org.lwjgl.util.lmdb.LMDB.MDB_PREV;
import static org.lwjgl.util.lmdb.LMDB.MDB_SET_KEY;
import static org.lwjgl.util.lmdb.LMDB.MDB_SET_RANGE;
import static org.lwjgl.util.lmdb.LMDB.MDB_SUCCESS;
import static org.lwjgl.util.lmdb.LMDB.mdb_cmp;
import static org.lwjgl.util.lmdb.LMDB.mdb_cursor_close;
import static org.lwjgl.util.lmdb.LMDB.mdb_cursor_del;
import static org.lwjgl.util.lmdb.LMDB.mdb_cursor_get;
import static org.lwjgl.util.lmdb.LMDB.mdb_cursor_put;
import static org.lwjgl.util.lmdb.LMDB.mdb_cursor_renew;

import java.io.IOException;
import java.nio.ByteBuffer;

import org.eclipse.rdf4j.common.concurrent.locks.StampedLongAdderLockManager;
import org.eclipse.rdf4j.sail.SailException;
import org.eclipse.rdf4j.sail.lmdb.TxnManager.Txn;
import org.eclipse.rdf4j.sail.lmdb.util.ChunkInput;
import org.eclipse.rdf4j.sail.lmdb.util.ChunkOutput;
import org.eclipse.rdf4j.sail.lmdb.util.EntryMatcher;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.lmdb.MDBVal;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * A record iterator that wraps a native LMDB iterator.
 */
class LmdbRecordIterator implements RecordIterator {

	private static final Logger log = LoggerFactory.getLogger(LmdbRecordIterator.class);

	static class State {

		private long cursor;

		private final MDBVal maxKey = MDBVal.malloc();
		private final MDBVal maxValue = MDBVal.malloc();

		private boolean matchValues;
		private EntryMatcher matcher;

		private Txn txnRef;

		private long txnRefVersion;

		private long txn;

		private int dbi;

		private final MDBVal keyData = MDBVal.malloc();

		private final MDBVal valueData = MDBVal.malloc();

		private final ChunkInput chunkInput = new ChunkInput();

		private final ByteBuffer minKeyBuf = MemoryUtil.memAlloc((Long.BYTES + 1) * 4);

		private final long[] minTuple = new long[4];

		private final ByteBuffer maxKeyBuf = MemoryUtil.memAlloc((Long.BYTES + 1) * 4);

		private final long[] maxTuple = new long[4];

		private final long[] quad = new long[4];
		private final long[] patternQuad = new long[4];
		private final long[] patternTuple = new long[4];

		private StampedLongAdderLockManager txnLockManager;

		private int indexScore;

		void close() {
			if (cursor != 0) {
				mdb_cursor_close(cursor);
				cursor = 0;
			}
			keyData.close();
			valueData.close();
			MemoryUtil.memFree(minKeyBuf);
			MemoryUtil.memFree(maxKeyBuf);
			maxKey.close();
			maxValue.close();
		}
	}

	private final Thread ownerThread = Thread.currentThread();
	TripleIndex index;
	private final State state;
	private volatile boolean closed = false;
	private boolean fetchNext = false;
	private ChunkOutput chunkOutput;
	private ByteBuffer chunkBuffer;

	private long sourceRowsScannedActual;
	private long sourceRowsMatchedActual;
	private long sourceRowsFilteredActual;

	LmdbRecordIterator(TripleIndex index, int indexScore, long subj, long pred, long obj,
			long context, boolean explicit, Txn txnRef) throws IOException {
		this.state = txnRef.getValuePool().getState();
		this.state.patternQuad[0] = subj;
		this.state.patternQuad[1] = pred;
		this.state.patternQuad[2] = obj;
		this.state.patternQuad[3] = context;
		this.state.quad[0] = subj;
		this.state.quad[1] = pred;
		this.state.quad[2] = obj;
		this.state.quad[3] = context;

		index.toEntry(this.state.patternTuple, subj, pred, obj, context);

		this.index = index;
		this.state.indexScore = indexScore;

		// prepare min and max keys if index can be used
		// otherwise, leave as null to indicate full scan
		if (indexScore > 0) {
			state.minKeyBuf.clear();
			index.getMinEntry(state.minTuple, subj, pred, obj, context);
			for (long v : state.minTuple) {
				Varint.writeUnsigned(state.minKeyBuf, v);
			}
			state.minKeyBuf.flip();

			state.maxKeyBuf.clear();
			index.getMaxEntry(state.maxTuple, subj, pred, obj, context);
			for (long v : state.maxTuple) {
				Varint.writeUnsigned(state.maxKeyBuf, v);
			}
			state.maxKeyBuf.flip();
			state.maxKey.mv_data(state.maxKeyBuf);
		}

		state.matchValues = subj > 0 || pred > 0 || obj > 0 || context >= 0;
		state.matcher = null;

		var dbi = index.getDB(explicit);

		long readStamp;
		try {
			readStamp = txnRef.lockManager().readLock();
		} catch (InterruptedException e) {
			throw new SailException(e);
		}
		try {
			state.dbi = dbi;
			state.txnRef = txnRef;
			state.txnLockManager = txnRef.lockManager();
			state.txnRefVersion = txnRef.version();
			state.txn = txnRef.get();
			state.cursor = txnRef.getCursor(dbi);
		} finally {
			txnRef.lockManager().unlockRead(readStamp);
		}
	}

	@Override
	public long[] next() {
		long readStamp;
		try {
			readStamp = state.txnLockManager.readLock();
		} catch (InterruptedException e) {
			throw new SailException(e);
		}
		try {
			if (closed) {
				log.debug("Calling next() on an LmdbRecordIterator that is already closed, returning null");
				return null;
			}

			int lastResult;
			if (state.txnRefVersion != state.txnRef.version()) {
				// TODO: None of the tests in the LMDB Store cover this case!
				// cursor must be renewed
				E(mdb_cursor_renew(state.txn, state.cursor));
				if (fetchNext) {
					// cursor must be positioned on last item, reuse minKeyBuf if available
					state.minKeyBuf.clear();
					index.toEntry(state.minTuple, state.quad[0], state.quad[1], state.quad[2],
							state.quad[3]);
					for (long v : state.minTuple) {
						Varint.writeUnsigned(state.minKeyBuf, v);
					}
					state.minKeyBuf.flip();
					state.keyData.mv_data(state.minKeyBuf);
					// use set range if entry was deleted
					lastResult = E(mdb_cursor_get(state.cursor, state.keyData, state.valueData, MDB_SET_KEY));
					if (lastResult != MDB_SUCCESS) {
						lastResult = E(mdb_cursor_get(state.cursor, state.keyData, state.valueData, MDB_SET_RANGE));
						E(mdb_cursor_get(state.cursor, state.keyData, state.valueData, MDB_PREV));
					}
					if (lastResult != MDB_SUCCESS) {
						closeInternal(false);
						return null;
					}
				}
				// update version of txn ref
				state.txnRefVersion = state.txnRef.version();
			}

			boolean isChunkValue = fetchNext;
			long[] current = null;
			if (fetchNext) {
				if (chunkOutput != null) {
					if (remove) {
						remove = false;
					} else {
						chunkOutput.addTuple(current);
					}
				}
				if ((current = state.chunkInput.next()) == null) {
					try {
						flush();
					} catch (IOException e) {
						throw new SailException(e);
					}
					// no more values in chunk, move to next key
					lastResult = mdb_cursor_get(state.cursor, state.keyData, state.valueData, MDB_NEXT);
					if (lastResult == MDB_SUCCESS) {
						state.chunkInput.reset(state.keyData.mv_data(), state.valueData.mv_data(),
								4, index.getIndexSplitPosition());
					}
					isChunkValue = false;
				} else {
					lastResult = MDB_SUCCESS;
				}
				fetchNext = false;
			} else {
				if (state.indexScore > 0) {
					// set cursor to min key
					state.keyData.mv_data(state.minKeyBuf);

					lastResult = E(mdb_cursor_get(state.cursor, state.keyData, state.valueData, MDB_SET_KEY));
					if (lastResult != MDB_SUCCESS) {
						lastResult = E(mdb_cursor_get(state.cursor, state.keyData, state.valueData, MDB_SET_RANGE));
						if (lastResult == MDB_SUCCESS) {
							E(mdb_cursor_get(state.cursor, state.keyData, state.valueData, MDB_PREV));
						} else {
							lastResult = E(mdb_cursor_get(state.cursor, state.keyData, state.valueData, MDB_LAST));
						}

					}
				} else {
					// set cursor to first item
					lastResult = E(mdb_cursor_get(state.cursor, state.keyData, state.valueData, MDB_FIRST));
				}
				if (lastResult == MDB_SUCCESS) {
					state.chunkInput.reset(state.keyData.mv_data(), state.valueData.mv_data(),
							4, index.getIndexSplitPosition());
					if (state.indexScore > 0) {
						state.chunkInput.seek(state.minTuple);
					}
				}
			}

			while (lastResult == MDB_SUCCESS) {
				sourceRowsScannedActual++;

				// fetch next value if there are no more values in chunk
				if (current == null && (current = state.chunkInput.next()) == null) {
					lastResult = mdb_cursor_get(state.cursor, state.keyData, state.valueData, MDB_NEXT);
					if (lastResult == MDB_SUCCESS) {
						state.chunkInput.reset(state.keyData.mv_data(), state.valueData.mv_data(),
								4, index.getIndexSplitPosition());
						current = state.chunkInput.next();
						isChunkValue = false;
					} else {
						break;
					}
				}

				if (state.indexScore > 0) {
					int keyDiff = isChunkValue ? 0 : mdb_cmp(state.txn, state.dbi, state.keyData, state.maxKey);
					if (keyDiff > 0) {
						break;
					}
					if (isChunkValue) {
						int valueDiff = Chunks.compareTuples(current, state.maxTuple);
						if (valueDiff > 0) {
							break;
						}
					}
				}

				if (state.matchValues && !Chunks.matches(state.patternTuple, current)) {
					isChunkValue = true;
					current = null;
					continue;
				}

				// Matching value found
				index.entryToQuad(current, state.quad);

				// fetch next value
				fetchNext = true;
				return state.quad;
			}
			closeInternal(false);
			return null;
		} catch (IOException e) {
			closeInternal(false);
			throw new SailException(e);
		} finally {
			state.txnLockManager.unlockRead(readStamp);
		}
	}

	private void closeInternal(boolean maybeCalledAsync) {
		if (!closed) {
			try {
				flush();
			} catch (IOException e) {
				throw new SailException(e);
			}
			long writeStamp = 0L;
			boolean writeLocked = false;
			if (maybeCalledAsync && ownerThread != Thread.currentThread()) {
				try {
					writeStamp = state.txnLockManager.writeLock();
					writeLocked = true;
				} catch (InterruptedException e) {
					throw new SailException(e);
				}
			}
			try {
				if (!closed) {
					if (chunkBuffer != null) {
						MemoryUtil.memFree(chunkBuffer);
						chunkBuffer = null;
					}
					state.txnRef.returnCursor(state.dbi, state.cursor);
					state.cursor = 0;
					state.txnRef.getValuePool().free(state);
				}
			} finally {
				closed = true;
				if (writeLocked) {
					state.txnLockManager.unlockWrite(writeStamp);
				}
			}
		}
	}

	private void flush() throws IOException {
		if (chunkOutput != null) {
			while (state.chunkInput.next() != null) {
				chunkOutput.addTuple(state.chunkInput.current());
			}
			if (chunkBuffer.position() > 0) {
				state.valueData.mv_data(chunkBuffer.flip());
				// directly replace value instead of deleting it first (LMDB manages deletion)
				E(mdb_cursor_put(state.cursor, state.keyData, state.valueData, MDB_CURRENT));
			} else {
				E(mdb_cursor_del(state.cursor, 0));
			}
			chunkOutput = null;
		}
	}

	boolean remove = false;

	@Override
	public void remove() throws IOException {
		if (chunkBuffer == null) {
			chunkBuffer = MemoryUtil.memAlloc(512);
		}

		if (chunkOutput == null) {
			chunkBuffer.clear();
			chunkOutput = new ChunkOutput(Chunks.MAX_CHUNK_SIZE, 4, index.getIndexSplitPosition());
			// TODO add previous tuples to chunkOutput if they exist
		}
		remove = true;
	}

	@Override
	public void close() {
		closeInternal(true);
	}

	@Override
	public String getIndexName() {
		return index.toString();
	}

	@Override
	public long getSourceRowsScannedActual() {
		return sourceRowsScannedActual;
	}

	@Override
	public long getSourceRowsMatchedActual() {
		return sourceRowsMatchedActual;
	}

	@Override
	public long getSourceRowsFilteredActual() {
		return sourceRowsFilteredActual;
	}
}
