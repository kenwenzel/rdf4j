/*******************************************************************************
 * Copyright (c) 2015 Eclipse RDF4J contributors, Aduna, and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *******************************************************************************/
package org.eclipse.rdf4j.sail.memory;

import static org.junit.Assert.assertEquals;

import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.query.QueryLanguage;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.TupleQueryResult;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.junit.After;
import org.junit.Before;
import org.junit.BeforeClass;
import org.junit.Test;

public class SparqlDistinctOptionalOrderByTest {

	@BeforeClass
	public static void setUpClass() throws Exception {
		System.setProperty("org.eclipse.rdf4j.repository.debug", "true");
	}

	private Repository repository;

	private RepositoryConnection conn;

	@Test
	public void testQueryOptionalOrderBy() throws Exception {
		var queryStr = "select ?o ?nr { ?o a <test:Class> optional { ?o <test:nr> ?nr } } order by ?nr";
		TupleQuery query = conn.prepareTupleQuery(QueryLanguage.SPARQL, queryStr);
		TupleQueryResult result = query.evaluate();
		assertEquals(3, result.stream().count());
		result.close();
	}

	@Test
	public void testQueryDistinctOptionalOnly() throws Exception {
		var queryStr = "select distinct ?o ?nr { ?o a <test:Class> optional { ?o <test:nr> ?nr } }";
		TupleQuery query = conn.prepareTupleQuery(QueryLanguage.SPARQL, queryStr);
		TupleQueryResult result = query.evaluate();
		assertEquals(3, result.stream().count());
		result.close();
	}

	@Test
	public void testQueryDistinctOptionalOrderBy() throws Exception {
		var queryStr = "select distinct ?o ?nr { ?o a <test:Class> optional { ?o <test:nr> ?nr } } order by ?nr";
		TupleQuery query = conn.prepareTupleQuery(QueryLanguage.SPARQL, queryStr);
		TupleQueryResult result = query.evaluate();
		assertEquals(3, result.stream().count());
		result.close();
	}

	@Before
	public void setUp() throws Exception {
		repository = createRepository();
		ValueFactory vf = repository.getValueFactory();

		conn = repository.getConnection();
		conn.add(vf.createIRI("test:a"), RDF.TYPE, vf.createIRI("test:Class"));
		conn.add(vf.createIRI("test:b"), RDF.TYPE, vf.createIRI("test:Class"));
		conn.add(vf.createIRI("test:c"), RDF.TYPE, vf.createIRI("test:Class"));

		conn.add(vf.createIRI("test:a"), vf.createIRI("test:nr"), vf.createLiteral(1));
		conn.add(vf.createIRI("test:b"), vf.createIRI("test:nr"), vf.createLiteral(1));
		// do not add nr to c
		// conn.add(vf.createIRI("test:c"), vf.createIRI("test:nr"), vf.createLiteral(1));
		conn.close();

		conn = repository.getConnection();
	}

	protected Repository createRepository() throws Exception {
		Repository repository = newRepository();
		try (RepositoryConnection con = repository.getConnection()) {
			con.clear();
			con.clearNamespaces();
		}
		return repository;
	}

	protected Repository newRepository() throws Exception {
		return new SailRepository(new MemoryStore());
	}

	@After
	public void tearDown() throws Exception {
		conn.close();
		conn = null;

		repository.shutDown();
		repository = null;
	}
}
