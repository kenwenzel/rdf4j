/*******************************************************************************
 * Copyright (c) 2015 Eclipse RDF4J contributors, Aduna, and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *******************************************************************************/
package org.eclipse.rdf4j.sail.leveldb;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Map.Entry;
import java.util.NavigableMap;
import java.util.Properties;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.TreeMap;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.eclipse.rdf4j.common.io.ByteArrayUtil;
import org.eclipse.rdf4j.sail.SailException;
import org.eclipse.rdf4j.sail.leveldb.TxnStatusFile.TxnStatus;
import org.iq80.leveldb.CompressionType;
import org.iq80.leveldb.DB;
import org.iq80.leveldb.DBComparator;
import org.iq80.leveldb.DBIterator;
import org.iq80.leveldb.Options;
import org.iq80.leveldb.Range;
import org.iq80.leveldb.ReadOptions;
import org.iq80.leveldb.Snapshot;
import org.iq80.leveldb.WriteBatch;
import org.iq80.leveldb.env.Env;
import org.iq80.leveldb.impl.DbImpl;
import org.iq80.leveldb.impl.Iq80DBFactory;
import org.iq80.leveldb.memenv.MemEnv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File-based indexed storage and retrieval of RDF statements. TripleStore stores statements in the form of four integer IDs. Each ID
 * represent an RDF value that is stored in a {@link ValueStore}. The four IDs refer to the statement's subject, predicate, object and
 * context. The ID <tt>0</tt> is used to represent the "null" context and doesn't map to an actual RDF value.
 *
 * @author Arjohn Kampman
 */
@SuppressWarnings("deprecation")
class TripleStore implements Closeable {

    /*-----------*
     * Constants *
     *-----------*/

    // 17 bytes are used to represent a triple:
    // byte 0-3 : subject
    // byte 4-7 : predicate
    // byte 8-11: object
    // byte 12-15: context
    // byte 16: additional flag(s)
    static final int RECORD_LENGTH = 17;
    static final int SUBJ_IDX = 0;
    static final int PRED_IDX = 4;
    static final int OBJ_IDX = 8;
    static final int CONTEXT_IDX = 12;
    static final int FLAG_IDX = 16;
    /**
     * Bit field indicating that a statement has been explicitly added (instead of being inferred).
     */
    static final byte EXPLICIT_FLAG = (byte) 0x1; // 0000 0001
    /**
     * Bit field indicating that a statement has been added in a (currently active) transaction.
     */
    static final byte ADDED_FLAG = (byte) 0x2; // 0000 0010
    /**
     * Bit field indicating that a statement has been removed in a (currently active) transaction.
     */
    static final byte REMOVED_FLAG = (byte) 0x4; // 0000 0100
    /**
     * Bit field indicating that the explicit flag has been toggled (from true to false, or vice versa) in a (currently active)
     * transaction.
     */
    static final byte TOGGLE_EXPLICIT_FLAG = (byte) 0x8; // 0000 1000
    /**
     * The default triple indexes.
     */
    private static final String DEFAULT_INDEXES = "spoc,posc";
    /**
     * The file name for the properties file.
     */
    private static final String PROPERTIES_FILE = "triples.prop";
    /**
     * The key used to store the triple store version in the properties file.
     */
    private static final String VERSION_KEY = "version";
    /**
     * The key used to store the triple indexes specification that specifies which triple indexes exist.
     */
    private static final String INDEXES_KEY = "triple-indexes";
    /**
     * The version number for the current triple store.
     * <ul>
     * <li>version 0: The first version which used a single spo-index. This version did not have a properties file yet.
     * <li>version 1: Introduces configurable triple indexes and the properties file.
     * <li>version 10: Introduces a context field, essentially making this a quad store.
     * <li>version 10a: Introduces transaction flags, this is backwards compatible with version 10.
     * </ul>
     */
    private static final int SCHEME_VERSION = 10;

    /*-----------*
     * Variables *
     *-----------*/
    private static final Logger logger = LoggerFactory.getLogger(TripleStore.class);
    private static final byte[] EMPTY = new byte[0];
    /**
     * The directory that is used to store the index files.
     */
    private final File dir;
    /**
     * Object containing meta-data for the triple store. This includes
     */
    private final Properties properties;
    /**
     * The list of triple indexes that are used to store and retrieve triples.
     */
    private final List<TripleIndex> indexes = new ArrayList<>();
    private final boolean forceSync;
    private volatile List<byte[]> updatedTriplesCache;

    /*--------------*
     * Constructors *
     *--------------*/

    public TripleStore(File dir, String indexSpecStr) throws IOException, SailException {
        this(dir, indexSpecStr, false);
    }

    public TripleStore(File dir, String indexSpecStr, boolean forceSync) throws IOException, SailException {
        this.dir = dir;
        this.forceSync = forceSync;

        File propFile = new File(dir, PROPERTIES_FILE);

        if (!propFile.exists()) {
            // newly created native store
            properties = new Properties();

            Set<String> indexSpecs = parseIndexSpecList(indexSpecStr);

            if (indexSpecs.isEmpty()) {
                logger.debug("No indexes specified, using default indexes: {}", DEFAULT_INDEXES);
                indexSpecStr = DEFAULT_INDEXES;
                indexSpecs = parseIndexSpecList(indexSpecStr);
            }

            initIndexes(indexSpecs);
        } else {
            // Read triple properties file and check format version number
            properties = loadProperties(propFile);
            checkVersion();

            // Initialize existing indexes
            Set<String> indexSpecs = getIndexSpecs();
            initIndexes(indexSpecs);

            // Compare the existing indexes with the requested indexes
            Set<String> reqIndexSpecs = parseIndexSpecList(indexSpecStr);

            if (reqIndexSpecs.isEmpty()) {
                // No indexes specified, use the existing ones
                indexSpecStr = properties.getProperty(INDEXES_KEY);
            } else if (!reqIndexSpecs.equals(indexSpecs)) {
                // Set of indexes needs to be changed
                reindex(indexSpecs, reqIndexSpecs);
            }
        }

        if (!String.valueOf(SCHEME_VERSION).equals(properties.getProperty(VERSION_KEY))
            || !indexSpecStr.equals(properties.getProperty(INDEXES_KEY))) {
            // Store up-to-date properties
            properties.setProperty(VERSION_KEY, String.valueOf(SCHEME_VERSION));
            properties.setProperty(INDEXES_KEY, indexSpecStr);
            storeProperties(propFile);
        }
    }

    /*---------*
     * Methods *
     *---------*/

    private void checkVersion() throws SailException {
        // Check version number
        String versionStr = properties.getProperty(VERSION_KEY);
        if (versionStr == null) {
            logger.warn("{} missing in TripleStore's properties file", VERSION_KEY);
        } else {
            try {
                int version = Integer.parseInt(versionStr);
                if (version < 10) {
                    throw new SailException("Directory contains incompatible triple data");
                } else if (version > SCHEME_VERSION) {
                    throw new SailException("Directory contains data that uses a newer data format");
                }
            } catch (NumberFormatException e) {
                logger.warn("Malformed version number in TripleStore's properties file");
            }
        }
    }

    private Set<String> getIndexSpecs() throws SailException {
        String indexesStr = properties.getProperty(INDEXES_KEY);

        if (indexesStr == null) {
            throw new SailException(INDEXES_KEY + " missing in TripleStore's properties file");
        }

        Set<String> indexSpecs = parseIndexSpecList(indexesStr);

        if (indexSpecs.isEmpty()) {
            throw new SailException("No " + INDEXES_KEY + " found in TripleStore's properties file");
        }

        return indexSpecs;
    }

    /**
     * Parses a comma/whitespace-separated list of index specifications. Index specifications are required to consists of 4 characters: 's',
     * 'p', 'o' and 'c'.
     *
     * @param indexSpecStr A string like "spoc, pocs, cosp".
     * @return A Set containing the parsed index specifications.
     */
    private Set<String> parseIndexSpecList(String indexSpecStr) throws SailException {
        Set<String> indexes = new HashSet<>();

        if (indexSpecStr != null) {
            StringTokenizer tok = new StringTokenizer(indexSpecStr, ", \t");
            while (tok.hasMoreTokens()) {
                String index = tok.nextToken().toLowerCase();

                // sanity checks
                if (index.length() != 4 || index.indexOf('s') == -1 || index.indexOf('p') == -1
                    || index.indexOf('o') == -1 || index.indexOf('c') == -1) {
                    throw new SailException("invalid value '" + index + "' in index specification: " + indexSpecStr);
                }

                indexes.add(index);
            }
        }

        return indexes;
    }

    private void initIndexes(Set<String> indexSpecs) throws IOException {
        for (String fieldSeq : indexSpecs) {
            logger.trace("Initializing index '{}'...", fieldSeq);
            indexes.add(new TripleIndex(fieldSeq));
        }
    }

    private void reindex(Set<String> currentIndexSpecs, Set<String> newIndexSpecs) throws IOException, SailException {
        Map<String, TripleIndex> currentIndexes = new HashMap<>();
        for (TripleIndex index : indexes) {
            currentIndexes.put(new String(index.getFieldSeq()), index);
        }

        // Determine the set of newly added indexes and initialize these using an
        // existing index as source
        Set<String> addedIndexSpecs = new HashSet<>(newIndexSpecs);
        addedIndexSpecs.removeAll(currentIndexSpecs);

        if (!addedIndexSpecs.isEmpty()) {
            TripleIndex sourceIndex = indexes.get(0);

            for (String fieldSeq : addedIndexSpecs) {
                logger.debug("Initializing new index '{}'...", fieldSeq);

                TripleIndex addedIndex = new TripleIndex(fieldSeq);
                DB addedDB = null;
                RecordIterator sourceIter = null;
                try {
                    sourceIter = new DBRecordIterator(sourceIndex.map.keySet().iterator());
                    byte[] value = null;
                    while ((value = sourceIter.next()) != null) {
                        addedIndex.put(value, EMPTY);
                    }
                } finally {
                    try {
                        if (sourceIter != null) {
                            sourceIter.close();
                        }
                    } finally {
                        if (addedDB != null) {
                            //addedDB.sync();
                        }
                    }
                }

                currentIndexes.put(fieldSeq, addedIndex);
            }

            logger.debug("New index(es) initialized");
        }

        // Determine the set of removed indexes
        Set<String> removedIndexSpecs = new HashSet<>(currentIndexSpecs);
        removedIndexSpecs.removeAll(newIndexSpecs);

        List<Throwable> removedIndexExceptions = new ArrayList<>();
        // Delete files for removed indexes
        for (String fieldSeq : removedIndexSpecs) {
            try {
                TripleIndex removedIndex = currentIndexes.remove(fieldSeq);

                removedIndex.destroy();
                logger.debug("Deleted file(s) for removed {} index", fieldSeq);
            } catch (Throwable e) {
                removedIndexExceptions.add(e);
            }
        }

        if (!removedIndexExceptions.isEmpty()) {
            throw new IOException(removedIndexExceptions.get(0));
        }

        // Update the indexes variable, using the specified index order
        indexes.clear();
        for (String fieldSeq : newIndexSpecs) {
            indexes.add(currentIndexes.remove(fieldSeq));
        }
    }

    @Override
    public void close() throws IOException {
        try {
            List<Throwable> caughtExceptions = new ArrayList<>();
            for (TripleIndex index : indexes) {
                try {
                    index.commit();
                } catch (Throwable e) {
                    logger.warn("Failed to close file for {} index", new String(index.getFieldSeq()));
                    caughtExceptions.add(e);
                }
            }
            if (!caughtExceptions.isEmpty()) {
                throw new IOException(caughtExceptions.get(0));
            }
        } finally {
            updatedTriplesCache = null;
        }
    }

    public RecordIterator getTriples(int subj, int pred, int obj, int context) throws IOException {
        // Return all triples except those that were added but not yet committed
        return getTriples(subj, pred, obj, context, 0, ADDED_FLAG);
    }

    public RecordIterator getTriples(int subj, int pred, int obj, int context, boolean readTransaction)
        throws IOException {
        if (readTransaction) {
            // Don't read removed statements
            return getTriples(subj, pred, obj, context, 0, TripleStore.REMOVED_FLAG);
        } else {
            // Don't read added statements
            return getTriples(subj, pred, obj, context, 0, TripleStore.ADDED_FLAG);
        }
    }

    /**
     * If an index exists by context - use it, otherwise return null.
     *
     * @param readTransaction
     * @return All triples sorted by context or null if no context index exists
     * @throws IOException
     */
    public RecordIterator getAllTriplesSortedByContext(boolean readTransaction) throws IOException {
        if (readTransaction) {
            // Don't read removed statements
            return getAllTriplesSortedByContext(0, TripleStore.REMOVED_FLAG);
        } else {
            // Don't read added statements
            return getAllTriplesSortedByContext(0, TripleStore.ADDED_FLAG);
        }
    }

    public RecordIterator getTriples(int subj, int pred, int obj, int context, boolean explicit,
        boolean readTransaction) throws IOException {
        int flags = 0;
        int flagsMask = 0;

        if (readTransaction) {
            flagsMask |= TripleStore.REMOVED_FLAG;
            // 'explicit' is handled through an ExplicitStatementFilter
        } else {
            flagsMask |= TripleStore.ADDED_FLAG;

            if (explicit) {
                flags |= TripleStore.EXPLICIT_FLAG;
                flagsMask |= TripleStore.EXPLICIT_FLAG;
            }
        }

        RecordIterator btreeIter = getTriples(subj, pred, obj, context, flags, flagsMask);

        if (readTransaction && explicit) {
            // Filter implicit statements from the result
            btreeIter = new ExplicitStatementFilter(btreeIter);
        } else if (!explicit) {
            // Filter out explicit statements from the result
            btreeIter = new ImplicitStatementFilter(btreeIter);
        }

        return btreeIter;
    }

    /*-------------------------------------*
     * Inner class ExplicitStatementFilter *
     *-------------------------------------*/

    private RecordIterator getTriples(int subj, int pred, int obj, int context, int flags, int flagsMask)
        throws IOException {
        TripleIndex index = getBestIndex(subj, pred, obj, context);
        boolean doRangeSearch = index.getPatternScore(subj, pred, obj, context) > 0;
        return getTriplesUsingIndex(subj, pred, obj, context, flags, flagsMask, index, doRangeSearch);
    }

    private RecordIterator getAllTriplesSortedByContext(int flags, int flagsMask) throws IOException {
        for (TripleIndex index : indexes) {
            if (index.getFieldSeq()[0] == 'c') {
                // found a context-first index
                return getTriplesUsingIndex(-1, -1, -1, -1, flags, flagsMask, index, false);
            }
        }

        return null;
    }

    private RecordIterator getTriplesUsingIndex(int subj, int pred, int obj, int context, int flags, int flagsMask,
        TripleIndex index, boolean rangeSearch) {
        byte[] searchKey = getSearchKey(subj, pred, obj, context, flags);
        byte[] searchMask = getSearchMask(subj, pred, obj, context, flagsMask);

        if (rangeSearch) {
            // Use ranged search
            byte[] minValue = getMinValue(subj, pred, obj, context);
            byte[] maxValue = getMaxValue(subj, pred, obj, context);

            return new RangeDBRecordIterator(index.tripleComparator, index.iterator(minValue), searchKey, searchMask, minValue, maxValue);
        } else {
            // Use sequential scan
            return new RangeDBRecordIterator(index.tripleComparator, index.iterator(), searchKey, searchMask, null, null);
        }
    }

    protected double cardinality(int subj, int pred, int obj, int context) throws IOException {
        TripleIndex index = getBestIndex(subj, pred, obj, context);

        double rangeSize;

        if (index.getPatternScore(subj, pred, obj, context) == 0) {
            synchronized (index.map) {
                rangeSize = index.map.size();
            }
        } else {
            byte[] minValue = getMinValue(subj, pred, obj, context);
            byte[] maxValue = getMaxValue(subj, pred, obj, context);
            synchronized (index.map) {
                rangeSize = index.map.navigableKeySet().subSet(minValue, maxValue).size();
            }
        }

        return rangeSize;
    }

    protected TripleIndex getBestIndex(int subj, int pred, int obj, int context) {
        int bestScore = -1;
        TripleIndex bestIndex = null;

        for (TripleIndex index : indexes) {
            int score = index.getPatternScore(subj, pred, obj, context);
            if (score > bestScore) {
                bestScore = score;
                bestIndex = index;
            }
        }

        return bestIndex;
    }

    public void clear() throws IOException {
        for (TripleIndex index : indexes) {
            index.clear();
        }
    }

    public boolean storeTriple(int subj, int pred, int obj, int context) throws IOException {
        return storeTriple(subj, pred, obj, context, true);
    }

    public boolean storeTriple(int subj, int pred, int obj, int context, boolean explicit) throws IOException {
        boolean stAdded = false;

        byte[] data = getData(subj, pred, obj, context, 0);
        byte[] storedData = indexes.get(0).get(data);

        if (storedData == null) {
            // Statement does not yet exist
            data[FLAG_IDX] |= ADDED_FLAG;
            if (explicit) {
                data[FLAG_IDX] |= EXPLICIT_FLAG;
            }

            stAdded = true;
        } else {
            // Statement already exists, only modify its flags, see txn-flags.txt
            // for a description of the flag transformations
            byte flags = storedData[FLAG_IDX];
            boolean wasExplicit = (flags & EXPLICIT_FLAG) != 0;
            boolean wasAdded = (flags & ADDED_FLAG) != 0;
            boolean wasRemoved = (flags & REMOVED_FLAG) != 0;
            boolean wasToggled = (flags & TOGGLE_EXPLICIT_FLAG) != 0;

            if (wasAdded) {
                // Statement has been added in the current transaction and is
                // invisible to other connections, we can simply modify its flags
                data[FLAG_IDX] |= ADDED_FLAG;
                if (explicit || wasExplicit) {
                    data[FLAG_IDX] |= EXPLICIT_FLAG;
                }
            } else {
                // Committed statement, must keep explicit flag the same
                if (wasExplicit) {
                    data[FLAG_IDX] |= EXPLICIT_FLAG;
                }

                if (explicit) {
                    if (!wasExplicit) {
                        // Make inferred statement explicit
                        data[FLAG_IDX] |= TOGGLE_EXPLICIT_FLAG;
                    }
                } else {
                    if (wasRemoved) {
                        if (wasExplicit) {
                            // Re-add removed explicit statement as inferred
                            data[FLAG_IDX] |= TOGGLE_EXPLICIT_FLAG;
                        }
                    } else if (wasToggled) {
                        data[FLAG_IDX] |= TOGGLE_EXPLICIT_FLAG;
                    }
                }
            }

            // Statement is new if it was removed before
            stAdded = wasRemoved;
        }

        if (storedData == null || !Arrays.equals(data, storedData)) {
            indexes.get(0).put(data, EMPTY);

            updatedTriplesCache.add(data);
        }

        return stAdded;
    }

    /**
     * Remove triples
     *
     * @param subj    The subject for the pattern, or <tt>-1</tt> for a wildcard.
     * @param pred    The predicate for the pattern, or <tt>-1</tt> for a wildcard.
     * @param obj     The object for the pattern, or <tt>-1</tt> for a wildcard.
     * @param context The context for the pattern, or <tt>-1</tt> for a wildcard.
     * @return The number of triples that were removed.
     * @throws IOException
     * @deprecated since 2.5.3. use {@link #removeTriplesByContext(int, int, int, int)} instead.
     */
    @Deprecated
    public int removeTriples(int subj, int pred, int obj, int context) throws IOException {
        Map<Integer, Long> countPerContext = removeTriplesByContext(subj, pred, obj, context);
        return (int) countPerContext.values().stream().mapToLong(Long::longValue).sum();
    }

    /**
     * Remove triples by context
     *
     * @param subj    The subject for the pattern, or <tt>-1</tt> for a wildcard.
     * @param pred    The predicate for the pattern, or <tt>-1</tt> for a wildcard.
     * @param obj     The object for the pattern, or <tt>-1</tt> for a wildcard.
     * @param context The context for the pattern, or <tt>-1</tt> for a wildcard.
     * @return A mapping of each modified context to the number of statements removed in that context.
     * @throws IOException
     * @since 2.5.3
     */
    public Map<Integer, Long> removeTriplesByContext(int subj, int pred, int obj, int context) throws IOException {
        RecordIterator iter = getTriples(subj, pred, obj, context, 0, 0);
        return removeTriples(iter);
    }

    /**
     * Remove triples
     *
     * @param subj     The subject for the pattern, or <tt>-1</tt> for a wildcard.
     * @param pred     The predicate for the pattern, or <tt>-1</tt> for a wildcard.
     * @param obj      The object for the pattern, or <tt>-1</tt> for a wildcard.
     * @param context  The context for the pattern, or <tt>-1</tt> for a wildcard.
     * @param explicit Flag indicating whether explicit or inferred statements should be removed; <tt>true</tt> removes explicit statements
     *                 that match the pattern, <tt>false</tt> removes inferred statements that match the pattern.
     * @return The number of triples that were removed.
     * @throws IOException
     * @deprecated since 2.5.3. use {@link #removeTriplesByContext(int, int, int, int, boolean)} instead.
     */
    @Deprecated
    public int removeTriples(int subj, int pred, int obj, int context, boolean explicit) throws IOException {
        Map<Integer, Long> countPerContext = removeTriplesByContext(subj, pred, obj, context, explicit);
        return (int) countPerContext.values().stream().mapToLong(Long::longValue).sum();
    }

    /**
     * @param subj     The subject for the pattern, or <tt>-1</tt> for a wildcard.
     * @param pred     The predicate for the pattern, or <tt>-1</tt> for a wildcard.
     * @param obj      The object for the pattern, or <tt>-1</tt> for a wildcard.
     * @param context  The context for the pattern, or <tt>-1</tt> for a wildcard.
     * @param explicit Flag indicating whether explicit or inferred statements should be removed; <tt>true</tt> removes explicit statements
     *                 that match the pattern, <tt>false</tt> removes inferred statements that match the pattern.
     * @return A mapping of each modified context to the number of statements removed in that context.
     * @throws IOException
     */
    public Map<Integer, Long> removeTriplesByContext(int subj, int pred, int obj, int context, boolean explicit)
        throws IOException {
        byte flags = explicit ? EXPLICIT_FLAG : 0;
        RecordIterator iter = getTriples(subj, pred, obj, context, flags, EXPLICIT_FLAG);
        return removeTriples(iter);
    }

    private Map<Integer, Long> removeTriples(RecordIterator iter) throws IOException {
        final Map<Integer, Long> perContextCounts = new HashMap<>();

        try {
            // Set the REMOVED flag by overwriting the affected records
            byte[] data;
            while ((data = iter.next()) != null) {
                if ((data[FLAG_IDX] & REMOVED_FLAG) == 0) {
                    data[FLAG_IDX] |= REMOVED_FLAG;

                    updatedTriplesCache.add(data);

                    for (TripleIndex index : indexes) {
                        index.put(data, EMPTY);
                    }

                    int context = ByteArrayUtil.getInt(data, CONTEXT_IDX);
                    perContextCounts.merge(context, 1L, (c, one) -> c + one);
                }
            }
        } finally {
            iter.close();
        }

        return perContextCounts;
    }

    public void startTransaction() throws IOException {
                 updatedTriplesCache = new ArrayList<>();

        for (TripleIndex index : indexes) {
            index.startTransaction();
        }
    }

    public void commit() throws IOException {
        for (TripleIndex index : indexes) {
            index.commit();
            index.startTransaction();
        }

        Iterator<byte[]> iter = updatedTriplesCache.iterator();
        try {
            while (iter.hasNext()) {
                byte[] data = iter.next();
                byte flags = data[FLAG_IDX];
                boolean wasAdded = (flags & ADDED_FLAG) != 0;
                boolean wasRemoved = (flags & REMOVED_FLAG) != 0;
                boolean wasToggled = (flags & TOGGLE_EXPLICIT_FLAG) != 0;

                if (wasRemoved) {
                    for (TripleIndex index : indexes) {
                        index.remove(data);
                    }
                } else if (wasAdded || wasToggled) {
                    if (wasToggled) {
                        data[FLAG_IDX] ^= EXPLICIT_FLAG;
                    }
                    if (wasAdded) {
                        data[FLAG_IDX] ^= ADDED_FLAG;
                    }
                    for (TripleIndex index : indexes) {
                        index.remove(data);
                        index.put(data, EMPTY);
                    }
                }
            }
        } finally {
            // iter.close();
        }

        for (TripleIndex index : indexes) {
            index.commit();
        }

        if (updatedTriplesCache != null) {
           updatedTriplesCache = null;
        }
        // checkAllCommitted();
    }

    private void checkAllCommitted() throws IOException {
        for (TripleIndex index : indexes) {
            System.out.println("Checking " + index + " index");
            try (RecordIterator iter = new DBRecordIterator(index.iterator())) {
                for (byte[] data = iter.next(); data != null; data = iter.next()) {
                    byte flags = data[FLAG_IDX];
                    boolean wasAdded = (flags & ADDED_FLAG) != 0;
                    boolean wasRemoved = (flags & REMOVED_FLAG) != 0;
                    boolean wasToggled = (flags & TOGGLE_EXPLICIT_FLAG) != 0;
                    if (wasAdded || wasRemoved || wasToggled) {
                        System.out.println("unexpected triple: " + ByteArrayUtil.toHexString(data));
                    }
                }
            }
        }
    }

    private byte[] getData(int subj, int pred, int obj, int context, int flags) {
        byte[] data = new byte[RECORD_LENGTH];

        ByteArrayUtil.putInt(subj, data, SUBJ_IDX);
        ByteArrayUtil.putInt(pred, data, PRED_IDX);
        ByteArrayUtil.putInt(obj, data, OBJ_IDX);
        ByteArrayUtil.putInt(context, data, CONTEXT_IDX);
        data[FLAG_IDX] = (byte) flags;

        return data;
    }

    private byte[] getSearchKey(int subj, int pred, int obj, int context, int flags) {
        return getData(subj, pred, obj, context, flags);
    }

    private byte[] getSearchMask(int subj, int pred, int obj, int context, int flags) {
        byte[] mask = new byte[RECORD_LENGTH];

        if (subj != -1) {
            ByteArrayUtil.putInt(0xffffffff, mask, SUBJ_IDX);
        }
        if (pred != -1) {
            ByteArrayUtil.putInt(0xffffffff, mask, PRED_IDX);
        }
        if (obj != -1) {
            ByteArrayUtil.putInt(0xffffffff, mask, OBJ_IDX);
        }
        if (context != -1) {
            ByteArrayUtil.putInt(0xffffffff, mask, CONTEXT_IDX);
        }
        mask[FLAG_IDX] = (byte) flags;

        return mask;
    }

    private byte[] getMinValue(int subj, int pred, int obj, int context) {
        byte[] minValue = new byte[RECORD_LENGTH];

        ByteArrayUtil.putInt((subj == -1 ? 0x00000000 : subj), minValue, SUBJ_IDX);
        ByteArrayUtil.putInt((pred == -1 ? 0x00000000 : pred), minValue, PRED_IDX);
        ByteArrayUtil.putInt((obj == -1 ? 0x00000000 : obj), minValue, OBJ_IDX);
        ByteArrayUtil.putInt((context == -1 ? 0x00000000 : context), minValue, CONTEXT_IDX);
        minValue[FLAG_IDX] = (byte) 0;

        return minValue;
    }

    private byte[] getMaxValue(int subj, int pred, int obj, int context) {
        byte[] maxValue = new byte[RECORD_LENGTH];

        ByteArrayUtil.putInt((subj == -1 ? 0xffffffff : subj), maxValue, SUBJ_IDX);
        ByteArrayUtil.putInt((pred == -1 ? 0xffffffff : pred), maxValue, PRED_IDX);
        ByteArrayUtil.putInt((obj == -1 ? 0xffffffff : obj), maxValue, OBJ_IDX);
        ByteArrayUtil.putInt((context == -1 ? 0xffffffff : context), maxValue, CONTEXT_IDX);
        maxValue[FLAG_IDX] = (byte) 0xff;

        return maxValue;
    }

    private Properties loadProperties(File propFile) throws IOException {
        try (InputStream in = new FileInputStream(propFile)) {
            Properties properties = new Properties();
            properties.load(in);
            return properties;
        }
    }

    private void storeProperties(File propFile) throws IOException {
        try (OutputStream out = new FileOutputStream(propFile)) {
            properties.store(out, "triple indexes meta-data, DO NOT EDIT!");
        }
    }

    private static class ExplicitStatementFilter implements RecordIterator {

        private final RecordIterator wrappedIter;

        public ExplicitStatementFilter(RecordIterator wrappedIter) {
            this.wrappedIter = wrappedIter;
        }

        @Override
        public byte[] next() throws IOException {
            byte[] result;

            while ((result = wrappedIter.next()) != null) {
                byte flags = result[TripleStore.FLAG_IDX];
                boolean explicit = (flags & TripleStore.EXPLICIT_FLAG) != 0;
                boolean toggled = (flags & TripleStore.TOGGLE_EXPLICIT_FLAG) != 0;

                if (explicit != toggled) {
                    // Statement is either explicit and hasn't been toggled, or vice
                    // versa
                    break;
                }
            }

            return result;
        }

        @Override
        public void close() throws IOException {
            wrappedIter.close();
        }
    } // end inner class ExplicitStatementFilter

    private static class ImplicitStatementFilter implements RecordIterator {

        private final RecordIterator wrappedIter;

        public ImplicitStatementFilter(RecordIterator wrappedIter) {
            this.wrappedIter = wrappedIter;
        }

        @Override
        public byte[] next() throws IOException {
            byte[] result;

            while ((result = wrappedIter.next()) != null) {
                byte flags = result[TripleStore.FLAG_IDX];
                boolean explicit = (flags & TripleStore.EXPLICIT_FLAG) != 0;

                if (!explicit) {
                    // Statement is implicit
                    break;
                }
            }

            return result;
        }

        @Override
        public void close() throws IOException {
            wrappedIter.close();
        }
    } // end inner class ImplicitStatementFilter

    /*-------------------------*
     * Inner class TripleIndex *
     *-------------------------*/

    /**
     * A DBComparator that can be used to create indexes with a configurable order of the subject, predicate, object and context fields.
     */
    private static class TripleComparator implements DBComparator {

        private final char[] fieldSeq;

        public TripleComparator(String fieldSeq) {
            this.fieldSeq = fieldSeq.toCharArray();
        }

        public char[] getFieldSeq() {
            return fieldSeq;
        }

        public int compare(byte[] key1, byte[] key2) {
            for (char field : fieldSeq) {
                int fieldIdx = 0;

                switch (field) {
                    case 's':
                        fieldIdx = SUBJ_IDX;
                        break;
                    case 'p':
                        fieldIdx = PRED_IDX;
                        break;
                    case 'o':
                        fieldIdx = OBJ_IDX;
                        break;
                    case 'c':
                        fieldIdx = CONTEXT_IDX;
                        break;
                    default:
                        throw new IllegalArgumentException(
                            "invalid character '" + field + "' in field sequence: " + new String(fieldSeq));
                }

                int diff = ByteArrayUtil.compareRegion(key1, fieldIdx, key2, fieldIdx, 4);

                if (diff != 0) {
                    return diff;
                }
            }

            return 0;
        }

        public String name() {
            return new String(fieldSeq);
        }

        public byte[] findShortestSeparator(byte[] start, byte[] limit) {
            return start;
        }

        public byte[] findShortSuccessor(byte[] key) {
            return key;
        }
    }

    private Env memEnv = null; //MemEnv.createEnv();

    private class TripleIndex {

        private final TripleComparator tripleComparator;
        private final String fieldSeq;

        private TreeMap<byte[], byte[]> map;
        private WeakHashMap<KeyIterator, Boolean> iterators = new WeakHashMap<>();

        public TripleIndex(String fieldSeq) throws IOException {
            this.fieldSeq = fieldSeq;
            this.tripleComparator = new TripleComparator(fieldSeq);
            open();
        }

        private void open() throws IOException {
            map = new TreeMap<>(tripleComparator);
        }

        private String getFilenamePrefix(String fieldSeq) {
            return "triples-" + fieldSeq;
        }

        public char[] getFieldSeq() {
            return tripleComparator.getFieldSeq();
        }

        void put(byte[] key, byte[] value) {
            synchronized (map) {
                map.put(key, value);
                iterators.keySet().forEach(it -> it.reset());
            }
        }

        void remove(byte[] key) {
            synchronized (map) {
                map.remove(key);
                iterators.keySet().forEach(it -> it.reset());
            }
        }

        /**
         * Returns the stored key for the given key. The stored key may differ in its flags as the comparator only considers the triple
         * elements.
         *
         * @param key A key
         * @return The corresponding stored key
         */
        public byte[] get(byte[] key) {
            Map.Entry<byte[], byte[]> storedEntry = map.ceilingEntry(key);
            if (storedEntry != null && tripleComparator.compare(storedEntry.getKey(), key) == 0) {
                return storedEntry.getKey();
            }
            return null;
        }

        /**
         * Determines the 'score' of this index on the supplied pattern of subject, predicate, object and context IDs. The higher the score,
         * the better the index is suited for matching the pattern. Lowest score is 0, which means that the index will perform a sequential
         * scan.
         */
        public int getPatternScore(int subj, int pred, int obj, int context) {
            int score = 0;

            for (char field : tripleComparator.getFieldSeq()) {
                switch (field) {
                    case 's':
                        if (subj >= 0) {
                            score++;
                        } else {
                            return score;
                        }
                        break;
                    case 'p':
                        if (pred >= 0) {
                            score++;
                        } else {
                            return score;
                        }
                        break;
                    case 'o':
                        if (obj >= 0) {
                            score++;
                        } else {
                            return score;
                        }
                        break;
                    case 'c':
                        if (context >= 0) {
                            score++;
                        } else {
                            return score;
                        }
                        break;
                    default:
                        throw new RuntimeException("invalid character '" + field + "' in field sequence: "
                            + new String(tripleComparator.getFieldSeq()));
                }
            }

            return score;
        }

        @Override
        public String toString() {
            return new String(getFieldSeq());
        }

        public void destroy() throws IOException {
        }

        public void clear() throws IOException {
            destroy();
            open();
        }

        void startTransaction() {
           // do nothing
        }

        void commit() throws IOException {
            // do nothing
        }

        public Iterator<byte[]> iterator(byte[] minKey) {
            synchronized (map) {
                return new KeyIterator(map.navigableKeySet().tailSet(minKey).iterator());
            }
        }

        public Iterator<byte[]> iterator() {
            synchronized (map) {
                return new KeyIterator(map.navigableKeySet().iterator());
            }
        }

        class KeyIterator implements Iterator<byte[]> {
            Iterator<byte[]> wrapped;
            byte[] next;

            public KeyIterator(Iterator<byte[]> wrapped) {
                this.wrapped = wrapped;
                synchronized (map) {
                    this.next = wrapped.hasNext() ? wrapped.next() : null;
                    iterators.put(this, true);
                }
            }

            @Override
            public boolean hasNext() {
               return next != null;
            }

            @Override
            public byte[] next() {
                byte[] current = next;
                synchronized (map) {
                    if (wrapped == null) {
                        wrapped = map.navigableKeySet().tailSet(next, false).iterator();
                    }
                    this.next = wrapped.hasNext() ? wrapped.next() : null;
                }
                return current;
            }

            public void reset() {
                this.wrapped = null;
            }
        }
    }
}
