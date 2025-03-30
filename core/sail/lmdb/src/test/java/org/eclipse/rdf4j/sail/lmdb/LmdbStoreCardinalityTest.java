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

import java.io.File;
import java.io.IOException;
import java.util.zip.GZIPInputStream;

import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.sail.SailException;
import org.eclipse.rdf4j.sail.lmdb.config.LmdbStoreConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Simple test to improve cardinality estimation of LmdbStore.
 */
public class LmdbStoreCardinalityTest {
	File dataDir = new File("/tmp/lmdb-cardinality-test");

	LmdbStore store;

	@BeforeEach
	void createStore() throws SailException {
		LmdbStoreConfig config = new LmdbStoreConfig("spoc,posc,ospc,cspo,cpos");
		config.setValueDBSize(52428800); // 50 MiB
		config.setTripleDBSize(config.getValueDBSize());
		store = new LmdbStore(dataDir, config);
	}

	@Test
	void testStore() throws IOException {
		boolean exists = dataDir.exists();
		var repo = new SailRepository(store);
		if (!exists) {
			try (var connection = repo.getConnection()) {
				connection.begin(IsolationLevels.NONE);
				connection.add(
						new GZIPInputStream(
								getClass().getResourceAsStream("/lmdbstore-testdata/nanopubs_full_2025_03_29.nq.gz")),
						RDFFormat.NQUADS);
				connection.commit();
			}
		}

		var vf = repo.getValueFactory();

		var valueStore = (ValueStore) store.getBackingStore().getValueFactory();
		var tripleStore = store.getBackingStore().getTripleStore();
		var CREATED = vf.createIRI("http://purl.org/dc/terms/created");
		var CREATED_ID = valueStore.getId(CREATED);

		try (var connection = repo.getConnection()) {
			var count = connection.getStatements(null, CREATED, null).stream().count();
			System.out.println("dct:created = " + count);

			var estimated = tripleStore.cardinality(-1, CREATED_ID, -1,
					-1);
			System.out.println("dct:created ~ " + estimated);

			count = connection.getStatements(null, RDFS.LABEL, null).stream().count();
			System.out.println("rdfs:label = " + count);

			estimated = tripleStore.cardinality(-1, valueStore.getId(RDFS.LABEL), -1,
					-1);
			System.out.println("rdfs:label ~ " + estimated);

			String query = new String(getClass().getResourceAsStream("/lmdbstore-testdata/nanopubs.rq").readAllBytes());
			try (var result = connection.prepareTupleQuery(query).evaluate()) {
				long start = System.currentTimeMillis();
				System.out.println("results: " + result.stream().count());
				System.out.println("duration: " + (System.currentTimeMillis() - start));
			}
		}
		repo.shutDown();
	}
}
