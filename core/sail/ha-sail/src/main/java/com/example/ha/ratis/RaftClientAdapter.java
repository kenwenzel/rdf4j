package com.example.ha.ratis;

/**
 * Adapter abstraction for appending bytes to the Raft log.
 * Implement this with your concrete Apache Ratis client to push entries to the Raft group.
 *
 * The append method should block until the entry is durably committed (or throw on failure)
 * if you want linearizable semantics. Alternatively it may be async — adjust callers accordingly.
 */
public interface RaftClientAdapter {
    /**
     * Append the given payload bytes to the Raft log (to be replicated).
     *
     * @param payload bytes to append (opaque to Raft; here they contain serialized statement batches)
     * @throws Exception on failure to append/replicate
     */
    void append(byte[] payload) throws Exception;
}