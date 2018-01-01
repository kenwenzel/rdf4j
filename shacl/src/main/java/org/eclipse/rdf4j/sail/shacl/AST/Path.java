/*******************************************************************************
 * Copyright (c) 2016 Eclipse RDF4J contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *******************************************************************************/

package org.eclipse.rdf4j.sail.shacl.AST;

import org.eclipse.rdf4j.IsolationLevels;
import org.eclipse.rdf4j.common.iteration.Iterations;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.SHACL;
import org.eclipse.rdf4j.query.Query;
import org.eclipse.rdf4j.query.parser.QueryParserFactory;
import org.eclipse.rdf4j.repository.Repository;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.RepositoryResult;
import org.eclipse.rdf4j.repository.sail.SailRepositoryConnection;
import org.eclipse.rdf4j.repository.sparql.query.SPARQLTupleQuery;

import java.util.stream.Stream;

/**
 * @author Heshan Jayasinghe
 */
public class Path implements RequiresEvalutation, QueryGenerator{

	IRI path;

	Resource id;


	Path(Resource id, SailRepositoryConnection connection) {
		this.id = id;

		try (Stream<Statement> stream = Iterations.stream(connection.getStatements(id, SHACL.PATH, null, true))) {
			path = stream.map(Statement::getObject).map(v -> (IRI) v).findAny().get();
		}

	}

	@Override
	public String toString() {
		return "Path{" + "path=" + path + '}';
	}

	@Override
	public boolean requiresEvalutation(Repository addedStatements, Repository removedStatements) {
		boolean requiresEvalutation;
		try (RepositoryConnection addedStatementsConnection = addedStatements.getConnection()) {
			requiresEvalutation = addedStatementsConnection.hasStatement(null,path, null, false);
		}

		try (RepositoryConnection removedStatementsConnection = removedStatements.getConnection()) {
			requiresEvalutation |= removedStatementsConnection.hasStatement(null,path, null, false);
		}

		return requiresEvalutation;
	}

	@Override
	public String getQuery() {

		return "BIND(<"+path+"> as ?b)\n ?a ?b ?c. ";


	}
}
