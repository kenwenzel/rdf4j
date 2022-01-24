/*******************************************************************************
 * Copyright (c) 2022 Eclipse RDF4J contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *******************************************************************************/

package org.eclipse.rdf4j.sail.lmdb.benchmark;

import java.io.File;
import java.io.IOException;

import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQueryResult;

/**
 * Simple commandline tool to test insertion performance with extended FOAF data.
 */
public class BenchmarkToolFoaf extends BenchmarkBaseFoaf {

	int millions;

	BenchmarkToolFoaf(int millions, File dir) {
		this.millions = millions;
		this.dir = dir;
	}

	public static void main(String[] args) throws Exception {
		int millions = 5;
		File dir = null;
		if (args.length > 0) {
			// first argument is the number of triples in millions
			millions = Integer.parseInt(args[0]);
		}
		if (args.length > 1) {
			// second argument is the location of the store
			// by default a temporary folder will be used
			dir = new File(args[1]);
		}
		new BenchmarkToolFoaf(millions, dir).run();
	}

	void run() throws IOException {
		// set max db sizes to 1 TiB each
		config.setValueDBSize(1_099_511_627_776L);
		config.setTripleDBSize(1_099_511_627_776L);

		// initialize store
		setup();

		// initialize next person nr from existing db
		TupleQueryResult r = connection.prepareTupleQuery(QueryLanguage.SPARQL,
				"select (count(?p) AS ?count) { ?p a <http://xmlns.com/foaf/0.1/Person> }").evaluate();
		if (r.hasNext()) {
			personNr = ((Literal) r.next().getValue("count")).intValue() + 1;
			System.out.println("starting with person nr " + personNr);
		}
		r.close();

		long runStart = System.currentTimeMillis();
		for (int m = 0; m < millions; m++) {
			long start = System.currentTimeMillis();
			// insert 1,000,000 million triples
			for (int i = 0; i < 10; i++) {
				connection.begin(IsolationLevels.NONE);
				for (int j = 0; j < 10000; j++) {
					addPerson();
				}
				connection.commit();
			}
			long duration = System.currentTimeMillis() - start;
			System.out.println("inserted " + (m + 1) + " M triples ("
					+ String.format("%.02f", (1000000 / (duration / 1000.0))) + " TPS)");
		}
		System.out.println(
				"duration: " + String.format("%.02f", (System.currentTimeMillis() - runStart) / 1000.0) + " seconds");

		connection.close();

		tearDown();
	}
}
