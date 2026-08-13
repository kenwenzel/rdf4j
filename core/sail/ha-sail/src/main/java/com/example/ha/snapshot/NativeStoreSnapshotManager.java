package com.example.ha.snapshot;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.time.Instant;
import java.util.logging.Level;
import java.util.logging.Logger;
import java.util.zip.GZIPOutputStream;

import org.apache.ratis.statemachine.SnapshotWriter;
import org.apache.ratis.statemachine.SnapshotReader;

import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.Rio;

/**
 * Snapshot manager for NativeStore-backed SailRepository.
 *
 * Behavior:
 * - exportSnapshot writes the repository contents to a gzip-compressed N-Quads file
 *   and adds that file to the provided SnapshotWriter.
 * - installSnapshot reads the stored snapshot file(s) from the SnapshotReader, clears
 *   the repository, and imports the N-Quads file into the repository.
 *
 * Notes:
 * - exportSnapshot obtains a live RepositoryConnection and calls exportStatements into a file.
 *   This assumes the underlying store provides a consistent view for export; with NativeStore,
 *   the repository connection's snapshot is consistent for the duration of the connection.
 * - installSnapshot clears the repository (removes all statements) and then adds data from
 *   the snapshot file. In a production deployment, you should ensure no concurrent writes are
 *   being applied while installing a snapshot.
 */
public final class NativeStoreSnapshotManager {
    private static final Logger LOG = Logger.getLogger(NativeStoreSnapshotManager.class.getName());

    private final SailRepository repository;

    public NativeStoreSnapshotManager(SailRepository repository) {
        this.repository = repository;
    }

    /**
     * Export repository to a temporary gzipped N-Quads file and add it to the SnapshotWriter.
     *
     * @param writer the Ratis SnapshotWriter provided by the state machine
     * @throws Exception on I/O or repository export errors
     */
    public void exportSnapshotToWriter(SnapshotWriter writer) throws Exception {
        // create temp file
        File tmp = Files.createTempFile("rdf4j-snapshot-" + Instant.now().toEpochMilli(), ".nq.gz").toFile();
        LOG.info(() -> "Writing snapshot to temp file: " + tmp.getAbsolutePath());
        try (OutputStream fos = new FileOutputStream(tmp);
             OutputStream gz = new GZIPOutputStream(fos)) {
            try (RepositoryConnection conn = repository.getConnection()) {
                // export everything as N-Quads (preserves contexts)
                Rio.write(conn.getStatements(null, null, null, false).iterator(), gz, RDFFormat.NQUADS);
            }
        } catch (Exception e) {
            // cleanup tmp on failure
            try { Files.deleteIfExists(tmp.toPath()); } catch (Exception ignored) {}
            throw e;
        }

        // Add the file to the SnapshotWriter.
        // Different Ratis versions expose addFile overloads that accept different param types.
        // Common forms:
        // writer.addFile(tmp.getName(), new FileInputStream(tmp)); // if present
        // writer.addFile(tmp.toPath(), true); // alternate
        // Use the first pattern here; adjust if your Ratis API differs.
        try (InputStream in = new FileInputStream(tmp)) {
            writer.addFile(tmp.getName(), in); // If your Ratis API doesn't have this signature, replace with the appropriate method.
        } finally {
            // schedule temporary file deletion; Ratis may copy it internally, so safe to delete after addFile returns.
            try { Files.deleteIfExists(tmp.toPath()); } catch (Exception ex) {
                LOG.log(Level.WARNING, "Could not delete temporary snapshot: " + tmp, ex);
            }
        }
    }

    /**
     * Install snapshot from a SnapshotReader by locating any N-Quads snapshot file,
     * clearing the repository and importing it.
     *
     * @param reader the Ratis SnapshotReader
     * @throws Exception on I/O or repository errors
     */
    public void installSnapshotFromReader(SnapshotReader reader) throws Exception {
        // find a file that looks like an RDF snapshot (we stored as .nq.gz)
        for (String name : reader.getFiles()) {
            if (name.endsWith(".nq.gz") || name.endsWith(".nq") || name.endsWith(".nt")) {
                LOG.info(() -> "Installing snapshot file from reader: " + name);
                try (InputStream in = reader.openFile(name)) {
                    // import into repository: clear first, then add
                    try (RepositoryConnection conn = repository.getConnection()) {
                        conn.begin();
                        conn.clear(); // clear all contexts
                        // Rio can detect format from extension; specify NQUADS and GZIP will be auto-handled by the InputStream?
                        // We used gzipped N-Quads; Rio.parse requires an InputStream that gives uncompressed bytes.
                        // So wrap in GZIPInputStream if needed (detect by filename).
                        if (name.endsWith(".gz") || name.endsWith(".nq.gz")) {
                            try (java.util.zip.GZIPInputStream gis = new java.util.zip.GZIPInputStream(in)) {
                                conn.add(gis, null, RDFFormat.NQUADS);
                            }
                        } else {
                            conn.add(in, null, RDFFormat.NQUADS);
                        }
                        conn.commit();
                    }
                    return;
                }
            }
        }
        throw new IllegalStateException("No snapshot file (nq.gz/nq/nt) found in SnapshotReader");
    }
}