/*******************************************************************************
 * Copyright (c) 2015 Eclipse RDF4J contributors, Aduna, and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *******************************************************************************/
package org.eclipse.rdf4j.sail.leveldb;

import java.io.Closeable;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.StringTokenizer;
import java.util.concurrent.ConcurrentSkipListMap;

import org.eclipse.rdf4j.sail.SailException;
import org.eclipse.rdf4j.sail.leveldb.model.NativeValue;
import org.eclipse.rdf4j.sail.leveldb.model.NativeValueBase;
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
    static final int RECORD_LENGTH = 4;
    static final int SUBJ_IDX = 0;
    static final int PRED_IDX = 1;
    static final int OBJ_IDX = 2;
    static final int CONTEXT_IDX = 3;
    /**
     * The default triple indexes.
     */
    private static final String DEFAULT_INDEXES = "spoc,posc";

    /*-----------*
     * Variables *
     *-----------*/
    private static final Logger logger = LoggerFactory.getLogger(TripleStore.class);
    /**
     * The list of triple indexes that are used to store and retrieve triples.
     */
    private final List<TripleIndex> indexes = new ArrayList<>();

    /*--------------*
     * Constructors *
     *--------------*/

    public TripleStore(String indexSpecStr) throws IOException, SailException {
        Set<String> indexSpecs = parseIndexSpecList(indexSpecStr);

        if (indexSpecs.isEmpty()) {
            logger.debug("No indexes specified, using default indexes: {}", DEFAULT_INDEXES);
            indexSpecStr = DEFAULT_INDEXES;
            indexSpecs = parseIndexSpecList(indexSpecStr);
        }

        initIndexes(indexSpecs);
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

    @Override
    public void close() throws IOException {
    }

    public RecordIterator getTriples(NativeValue subj, NativeValue pred, NativeValue obj, NativeValue context, boolean explicit)  {
        TripleIndex index = getBestIndex(subj, pred, obj, context);
        boolean doRangeSearch = index.getPatternScore(subj, pred, obj, context) > 0;
        return getTriplesUsingIndex(subj, pred, obj, context, index, doRangeSearch, explicit);
    }

    RecordIterator getAllTriplesSortedByContext() {
        for (TripleIndex index : indexes) {
            if (index.getFieldSeq()[0] == 'c') {
                // found a context-first index
                return getTriplesUsingIndex(null, null, null, null, index, false, true);
            }
        }

        return null;
    }

    private RecordIterator getTriplesUsingIndex(NativeValue subj, NativeValue pred, NativeValue obj, NativeValue context, TripleIndex index, boolean rangeSearch, boolean explicit) {
        NativeValue[] searchKey = getSearchKey(subj, pred, obj, context);
        boolean[] searchMask = getSearchMask(subj, pred, obj, context);

        if (rangeSearch) {
            // Use ranged search
            NativeValue[] minValue = getMinValue(subj, pred, obj, context);
            NativeValue[] maxValue = getMaxValue(subj, pred, obj, context);

            return new RangeDBRecordIterator(index.tripleComparator,
                index.getMap(explicit).tailMap(minValue).entrySet().iterator(),
                searchKey, searchMask, minValue, maxValue);
        } else {
            // Use sequential scan
            return new RangeDBRecordIterator(index.tripleComparator,
                index.getMap(explicit).entrySet().iterator(),
                searchKey, searchMask, null, null);
        }
    }

    protected double cardinality(NativeValue subj, NativeValue pred, NativeValue obj, NativeValue context) throws IOException {
        TripleIndex index = getBestIndex(subj, pred, obj, context);

        double cardinality = 0;
        int score = index.getPatternScore(subj, pred, obj, context);
        for (boolean explicit : new boolean[] { true, false }) {
            if (score == 0) {
                cardinality += index.getMap(explicit).size();
            } else {
                NativeValue[] minValue = getMinValue(subj, pred, obj, context);
                NativeValue[] maxValue = getMaxValue(subj, pred, obj, context);
                cardinality += index.getMap(explicit).subMap(minValue, maxValue).size();
            }
        }
        return cardinality;
    }

    protected TripleIndex getBestIndex(NativeValue subj, NativeValue pred, NativeValue obj, NativeValue context) {
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

    public boolean storeTriple(NativeValue subj, NativeValue pred, NativeValue obj, NativeValue context, boolean explicit) throws IOException {
        NativeValue[] data = getData(subj, pred, obj, context);
        boolean foundExplicit = indexes.get(0).getMap(true).get(data) != null;
        boolean foundImplicit = !foundExplicit && indexes.get(0).getMap(false).get(data) != null;

        boolean stAdded = !(foundExplicit || foundImplicit);
        if (stAdded || explicit && foundImplicit) {
            for (TripleIndex index : indexes) {
                if (explicit && foundImplicit) {
                    index.getMap(false).remove(data);
                }
                index.getMap(explicit).put(data, true);
            }
        }
        return stAdded;
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
    public Map<NativeValue, Long> removeTriplesByContext(NativeValue subj, NativeValue pred, NativeValue obj, NativeValue context, boolean explicit)
        throws IOException {
        RecordIterator iter = getTriples(subj, pred, obj, context, explicit);
        return removeTriples(iter, explicit);
    }

    private Map<NativeValue, Long> removeTriples(RecordIterator iter, boolean explicit) throws IOException {
        final Map<NativeValue, Long> perContextCounts = new HashMap<>();

        try {
            Record r;
            while ((r = iter.next()) != null) {
                for (TripleIndex index : indexes) {
                    index.getMap(explicit).remove(r.key);
                }
                NativeValue context = r.key[CONTEXT_IDX];
                perContextCounts.merge(context, 1L, (c, one) -> c + one);
            }
        } finally {
            iter.close();
        }

        return perContextCounts;
    }

    public void startTransaction() throws IOException {
        for (TripleIndex index : indexes) {
            index.startTransaction();
        }
    }

    public void commit() throws IOException {
        for (TripleIndex index : indexes) {
            index.commit();
        }
    }
    private NativeValue[] getData(NativeValue subj, NativeValue pred, NativeValue obj, NativeValue context) {
        return new NativeValue[] {subj, pred, obj, context};
    }

    private NativeValue[] getSearchKey(NativeValue subj, NativeValue pred, NativeValue obj, NativeValue context) {
        return getData(subj, pred, obj, context);
    }

    private boolean[] getSearchMask(NativeValue subj, NativeValue pred, NativeValue obj, NativeValue context) {
        return new boolean[] {subj != null, pred != null, obj != null, context != null};
    }

    private final NativeValue MIN_VALUE = new NativeValueBase(0);
    private final NativeValue MAX_VALUE = new NativeValueBase(Long.MAX_VALUE);

    private NativeValue[] getMinValue(NativeValue subj, NativeValue pred, NativeValue obj, NativeValue context) {
        NativeValue[] minValue = new NativeValue[RECORD_LENGTH];

        minValue[SUBJ_IDX] = subj == null ? MIN_VALUE : subj;
        minValue[PRED_IDX] = pred == null ? MIN_VALUE : pred;
        minValue[OBJ_IDX] = obj == null ? MIN_VALUE : obj;
        minValue[CONTEXT_IDX] = context == null ? MIN_VALUE : context;

        return minValue;
    }

    private NativeValue[] getMaxValue(NativeValue subj, NativeValue pred, NativeValue obj, NativeValue context) {
        NativeValue[] maxValue = new NativeValue[RECORD_LENGTH];

        maxValue[SUBJ_IDX] = subj == null ? MAX_VALUE : subj;
        maxValue[PRED_IDX] = pred == null ? MAX_VALUE : pred;
        maxValue[OBJ_IDX] = obj == null ? MAX_VALUE : obj;
        maxValue[CONTEXT_IDX] = context == null ? MAX_VALUE : context;

        return maxValue;
    }

    /**
     * A DBComparator that can be used to create indexes with a configurable order of the subject, predicate, object and context fields.
     */
    private static class TripleComparator implements Comparator<NativeValue[]> {

        private final char[] fieldSeq;

        public TripleComparator(String fieldSeq) {
            this.fieldSeq = fieldSeq.toCharArray();
        }

        public char[] getFieldSeq() {
            return fieldSeq;
        }

        public int compare(NativeValue[] key1, NativeValue[] key2) {
            if (key1 == null || key2 == null) {
                return 0;
            }

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

                int diff = Long.compare(key1[fieldIdx].getInternalID(), key2[fieldIdx].getInternalID());

                if (diff != 0) {
                    return diff;
                }
            }

            return 0;
        }

        public String name() {
            return new String(fieldSeq);
        }
    }

    private class TripleIndex {

        private final TripleComparator tripleComparator;
        private final String fieldSeq;

        private ConcurrentSkipListMap<NativeValue[], Boolean> explicit, implicit;

        public TripleIndex(String fieldSeq) {
            this.fieldSeq = fieldSeq;
            this.tripleComparator = new TripleComparator(fieldSeq);
            open();
        }

        private void open() {
            explicit = new ConcurrentSkipListMap<>(tripleComparator);
            implicit = new ConcurrentSkipListMap<>(tripleComparator);
        }

        public char[] getFieldSeq() {
            return tripleComparator.getFieldSeq();
        }

        /**
         * Determines the 'score' of this index on the supplied pattern of subject, predicate, object and context IDs. The higher the score,
         * the better the index is suited for matching the pattern. Lowest score is 0, which means that the index will perform a sequential
         * scan.
         */
        public int getPatternScore(NativeValue subj, NativeValue pred, NativeValue obj, NativeValue context) {
            int score = 0;

            for (char field : tripleComparator.getFieldSeq()) {
                switch (field) {
                    case 's':
                        if (subj != null) {
                            score++;
                        } else {
                            return score;
                        }
                        break;
                    case 'p':
                        if (pred != null) {
                            score++;
                        } else {
                            return score;
                        }
                        break;
                    case 'o':
                        if (obj != null) {
                            score++;
                        } else {
                            return score;
                        }
                        break;
                    case 'c':
                        if (context != null) {
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

        public void clear() throws IOException {
            explicit.clear();
            implicit.clear();
        }

        void startTransaction() {
           // do nothing
        }

        void commit() {
        }

        ConcurrentSkipListMap<NativeValue[], Boolean> getMap(boolean explicit) {
            return explicit ? this.explicit : this.implicit;
        }
    }
}
