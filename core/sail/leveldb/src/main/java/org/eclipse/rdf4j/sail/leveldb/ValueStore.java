/*******************************************************************************
 * Copyright (c) 2015 Eclipse RDF4J contributors, Aduna, and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *******************************************************************************/
package org.eclipse.rdf4j.sail.leveldb;

import java.io.File;
import java.io.IOException;
import java.nio.ByteOrder;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

import org.eclipse.rdf4j.common.concurrent.locks.Lock;
import org.eclipse.rdf4j.common.concurrent.locks.ReadWriteLockManager;
import org.eclipse.rdf4j.common.concurrent.locks.WritePrefReadWriteLockManager;
import org.eclipse.rdf4j.model.BNode;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.base.AbstractValueFactory;
import org.eclipse.rdf4j.model.util.Literals;
import org.eclipse.rdf4j.model.vocabulary.XSD;
import org.eclipse.rdf4j.sail.leveldb.model.NativeBNode;
import org.eclipse.rdf4j.sail.leveldb.model.NativeIRI;
import org.eclipse.rdf4j.sail.leveldb.model.NativeLiteral;
import org.eclipse.rdf4j.sail.leveldb.model.NativeResource;
import org.eclipse.rdf4j.sail.leveldb.model.NativeValue;

/**
 * File-based indexed storage and retrieval of RDF values. ValueStore maps RDF values to integer IDs and vice-versa.
 *
 * @author Arjohn Kampman
 */
class ValueStore extends AbstractValueFactory {

    /**
     * The default value cache size.
     */
    public static final int VALUE_CACHE_SIZE = 512;

    /**
     * The default value id cache size.
     */
    public static final int VALUE_ID_CACHE_SIZE = 128;

    /**
     * The default namespace cache size.
     */
    public static final int NAMESPACE_CACHE_SIZE = 64;

    /**
     * The default namespace id cache size.
     */
    public static final int NAMESPACE_ID_CACHE_SIZE = 32;

    /**
     * The default byte order for all byte buffers
     */
    private static ByteOrder BYTE_ORDER = ByteOrder.BIG_ENDIAN;
    /**
     * Lock manager used to prevent the removal of values over multiple method calls. Note that values can still be added when read locks
     * are active.
     */
    private final ReadWriteLockManager lockManager = new WritePrefReadWriteLockManager();
    /**
     * A simple cache containing the [VALUE_CACHE_SIZE] most-recently used values stored by their ID.
     */
    private final ConcurrentMap<Long, NativeValue> valueCache;
    /**
     * A simple cache containing the [ID_CACHE_SIZE] most-recently used value-IDs stored by their value.
     */
    private final ConcurrentMap<NativeValue, Long> valueIDCache;
    /**
     * An object that indicates the revision of the value store, which is used to check if cached value IDs are still valid. In order to be
     * valid, the ValueStoreRevision object of a NativeValue needs to be equal to this object.
     */
    private volatile ValueStoreRevision revision;
    /**
     * The next ID that is associated with a stored value
     */
    private long nextId;

    /*--------------*
     * Constructors *
     *--------------*/

    public ValueStore() {
        valueCache = new ConcurrentHashMap<>();
        valueIDCache = new ConcurrentHashMap<>();

        setNewRevision();
    }

    /*---------*
     * Methods *
     *---------*/

    public void startTransaction() throws IOException {
    }

    public void commit() throws IOException {
    }

    private long nextId() {
        long result = nextId;
        nextId++;
        return result;
    }

    /**
     * Creates a new revision object for this value store, invalidating any IDs cached in NativeValue objects that were created by this
     * value store.
     */
    private void setNewRevision() {
        revision = new ValueStoreRevision(this);
    }

    public ValueStoreRevision getRevision() {
        return revision;
    }

    /**
     * Gets a read lock on this value store that can be used to prevent values from being removed while the lock is active.
     */
    public Lock getReadLock() throws InterruptedException {
        return lockManager.getReadLock();
    }

    /**
     * Gets the value for the specified ID.
     *
     * @param id A value ID.
     * @return The value for the ID, or <tt>null</tt> no such value could be found.
     * @throws IOException If an I/O error occurred.
     */
    public NativeValue getValue(long id) throws IOException {
        return valueCache.get(id);
    }

    /**
     * Gets the ID for the specified value.
     *
     * @param value A value.
     * @return The ID for the specified value, or {@link NativeValue#UNKNOWN_ID} if no such ID could be found.
     * @throws IOException If an I/O error occurred.
     */
    public long getID(Value value) throws IOException {
        // Try to get the internal ID from the value itself
        boolean isOwnValue = isOwnValue(value);

        if (isOwnValue) {
            NativeValue nativeValue = (NativeValue) value;

            if (revisionIsCurrent(nativeValue)) {
                long id = nativeValue.getInternalID();

                if (id != NativeValue.UNKNOWN_ID) {
                    return id;
                }
            }
        }

        Long id = valueIDCache.get(value);
        return id != null ? id : NativeValue.UNKNOWN_ID;
    }

    /**
     * Stores the supplied value and returns the ID that has been assigned to it. In case the value was already present, the value will not
     * be stored again and the ID of the existing value is returned.
     *
     * @param value The Value to store.
     * @return The ID that has been assigned to the value.
     * @throws IOException If an I/O error occurred.
     */
    public long storeValue(Value value) throws IOException {
        long id = getID(value);

        if (id == NativeValue.UNKNOWN_ID) {
            id = nextId();

            NativeValue nv = isOwnValue(value) ? (NativeValue) value : getNativeValue(value);
            // Store id in value for fast access in any consecutive calls
            nv.setInternalID(id, revision);

            valueCache.put(id, nv);
            valueIDCache.put(nv, id);
        }

        return id;
    }

    /**
     * Removes all values from the ValueStore.
     *
     * @throws IOException If an I/O error occurred.
     */
    public void clear() throws IOException {
        try {
            Lock writeLock = lockManager.getWriteLock();
            try {
                valueCache.clear();
                valueIDCache.clear();

                setNewRevision();
            } finally {
                writeLock.release();
            }
        } catch (InterruptedException e) {
            throw new IOException("Failed to acquire write lock", e);
        }
    }

    /**
     * Synchronizes any changes that are cached in memory to disk.
     *
     * @throws IOException If an I/O error occurred.
     */
    public void sync() throws IOException {
        // TODO correctly handle sync
        // db.sync();
    }

    /**
     * Closes the ValueStore, releasing any file references, etc. Once closed, the ValueStore can no longer be used.
     *
     * @throws IOException If an I/O error occurred.
     */
    public void close() throws IOException {
    }

    private Value copy(Value value) {
        if (value instanceof IRI) {
            return createIRI(value.stringValue());
        } else if (value instanceof Literal) {
            Literal lit = (Literal) value;
            if (Literals.isLanguageLiteral(lit)) {
                return createLiteral(value.stringValue(), lit.getLanguage().orElse(null));
            } else {
                return createLiteral(value.stringValue(), lit.getDatatype());
            }
        } else {
            return createBNode(value.stringValue());
        }
    }

    /**
     * Checks if the supplied Value object is a NativeValue object that has been created by this ValueStore.
     */
    private boolean isOwnValue(Value value) {
        return value instanceof NativeValue && ((NativeValue) value).getValueStoreRevision().getValueStore() == this;
    }

    /**
     * Checks if the revision of the supplied value object is still current.
     */
    private boolean revisionIsCurrent(NativeValue value) {
        return revision.equals(value.getValueStoreRevision());
    }

    @Override
    public NativeIRI createIRI(String uri) {
        return new NativeIRI(revision, uri);
    }

    @Override
    public NativeIRI createIRI(String namespace, String localName) {
        return new NativeIRI(revision, namespace, localName);
    }

    @Override
    public NativeBNode createBNode(String nodeID) {
        return new NativeBNode(revision, nodeID);
    }

    @Override
    public NativeLiteral createLiteral(String value) {
        return new NativeLiteral(revision, value, XSD.STRING);
    }

    @Override
    public NativeLiteral createLiteral(String value, String language) {
        return new NativeLiteral(revision, value, language);
    }

    /*----------------------------------------------------------------------*
     * Methods for converting model objects to NativeStore-specific objects *
     *----------------------------------------------------------------------*/

    @Override
    public NativeLiteral createLiteral(String value, IRI datatype) {
        return new NativeLiteral(revision, value, datatype);
    }

    public NativeValue getNativeValue(Value value) {
        if (value instanceof Resource) {
            return getNativeResource((Resource) value);
        } else if (value instanceof Literal) {
            return getNativeLiteral((Literal) value);
        } else {
            throw new IllegalArgumentException("Unknown value type: " + value.getClass());
        }
    }

    public NativeResource getNativeResource(Resource resource) {
        if (resource instanceof IRI) {
            return getNativeURI((IRI) resource);
        } else if (resource instanceof BNode) {
            return getNativeBNode((BNode) resource);
        } else {
            throw new IllegalArgumentException("Unknown resource type: " + resource.getClass());
        }
    }

    /**
     * Creates a NativeURI that is equal to the supplied URI. This method returns the supplied URI itself if it is already a NativeURI that
     * has been created by this ValueStore, which prevents unnecessary object creations.
     *
     * @return A NativeURI for the specified URI.
     */
    public NativeIRI getNativeURI(IRI uri) {
        if (isOwnValue(uri)) {
            return (NativeIRI) uri;
        }

        return new NativeIRI(revision, uri.toString());
    }

    /**
     * Creates a NativeBNode that is equal to the supplied bnode. This method returns the supplied bnode itself if it is already a
     * NativeBNode that has been created by this ValueStore, which prevents unnecessary object creations.
     *
     * @return A NativeBNode for the specified bnode.
     */
    public NativeBNode getNativeBNode(BNode bnode) {
        if (isOwnValue(bnode)) {
            return (NativeBNode) bnode;
        }

        return new NativeBNode(revision, bnode.getID());
    }

    /*--------------------*
     * Test/debug methods *
     *--------------------*/

    /**
     * Creates an NativeLiteral that is equal to the supplied literal. This method returns the supplied literal itself if it is already a
     * NativeLiteral that has been created by this ValueStore, which prevents unnecessary object creations.
     *
     * @return A NativeLiteral for the specified literal.
     */
    public NativeLiteral getNativeLiteral(Literal l) {
        if (isOwnValue(l)) {
            return (NativeLiteral) l;
        }

        if (Literals.isLanguageLiteral(l)) {
            return new NativeLiteral(revision, l.getLabel(), l.getLanguage().get());
        } else {
            NativeIRI datatype = getNativeURI(l.getDatatype());
            return new NativeLiteral(revision, l.getLabel(), datatype);
        }
    }
}
