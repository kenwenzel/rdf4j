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
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Random;

import org.apache.commons.io.IOUtils;
import org.eclipse.rdf4j.common.iteration.IterationWrapper;
import org.eclipse.rdf4j.common.iteration.Iterations;
import org.eclipse.rdf4j.common.iteration.SingletonIteration;
import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.model.util.Values;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Dataset;
import org.eclipse.rdf4j.query.QueryEvaluationException;
import org.eclipse.rdf4j.query.TupleQuery;
import org.eclipse.rdf4j.query.Update;
import org.eclipse.rdf4j.query.algebra.SingletonSet;
import org.eclipse.rdf4j.query.algebra.StatementPattern;
import org.eclipse.rdf4j.query.algebra.evaluation.EvaluationStrategy;
import org.eclipse.rdf4j.query.algebra.evaluation.EvaluationStrategyFactory;
import org.eclipse.rdf4j.query.algebra.evaluation.QueryEvaluationStep;
import org.eclipse.rdf4j.query.algebra.evaluation.TripleSource;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.EvaluationStatistics;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.QueryEvaluationContext;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.StrictEvaluationStrategy;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.StrictEvaluationStrategyFactory;
import org.eclipse.rdf4j.query.algebra.evaluation.impl.evaluationsteps.StatementPatternQueryEvaluationStep;
import org.eclipse.rdf4j.query.impl.ListBindingSet;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.repository.sail.SailRepositoryConnection;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.sail.config.SailConfigException;
import org.eclipse.rdf4j.sail.lmdb.config.LmdbStoreConfig;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.rules.TemporaryFolder;

/**
 *
 */
public class TimeLimitIterationTest {

    public static TemporaryFolder tempDir = new TemporaryFolder();
    private static SailRepository repository;
    private static long id;

    @BeforeAll
    public static void beforeClass() throws IOException {
        tempDir.create();
        File file = tempDir.newFolder();
        LmdbStoreConfig config = new LmdbStoreConfig("spoc,ospc,psoc") {
            @Override
            public EvaluationStrategyFactory getEvaluationStrategyFactory() throws SailConfigException {
                StrictEvaluationStrategyFactory evalStratFactory = new StrictEvaluationStrategyFactory(null) {
                    @Override
                    public EvaluationStrategy createEvaluationStrategy(Dataset dataset, TripleSource tripleSource,
                        EvaluationStatistics evaluationStatistics) {
                        StrictEvaluationStrategy strategy = new StrictEvaluationStrategy(tripleSource, dataset,
                            getFederatedServiceResolver(),
                            getQuerySolutionCacheThreshold(), evaluationStatistics, isTrackResultSize()) {
                            protected QueryEvaluationStep prepare(StatementPattern node, QueryEvaluationContext context)
                                throws QueryEvaluationException {
                                return QueryEvaluationStep.wrap(super.prepare(node, context), a -> {
                                    String predicateName = node.getPredicateVar().getName();
                                    if (predicateName.startsWith("sleep")) {
                                        String suffix = predicateName.substring("sleep".length());
                                        Thread creator = Thread.currentThread();
                                        return new IterationWrapper<>(a) {
                                            @Override
                                            public BindingSet next() throws QueryEvaluationException {
                                                BindingSet next = super.next();
                                                try {
                                                    System.out.println("sleep");
                                                    Thread.sleep(suffix.isEmpty() ? 1000 : Integer.parseInt(suffix));
                                                } catch (InterruptedException e) {
                                                    throw new RuntimeException(e);
                                                }
                                                return next;
                                            }

                                            @Override
                                            protected void handleClose() throws QueryEvaluationException {
                                                if (creator != Thread.currentThread()) {
                                                    System.out.println("interrupt");
                                                }
                                                super.handleClose();
                                            }
                                        };
                                    }
                                    return a;
                                });
                            }
                        };
                        getOptimizerPipeline().ifPresent(strategy::setOptimizerPipeline);
                        return strategy;
                    }
                };
                return evalStratFactory;
            }
        };
        repository = new SailRepository(new LmdbStore(file, config));

        try (SailRepositoryConnection connection = repository.getConnection()) {
            connection.begin(IsolationLevels.NONE);
            for (int i = 0; i < 1000; i++) {
                createPerson(connection);
            }
            connection.commit();
        }
    }

    protected static void createPerson(RepositoryConnection connection) {
        ValueFactory vf = connection.getValueFactory();
        IRI person = vf.createIRI("http://www.example.org/persons/person_" + id++);
        connection.add(person, vf.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
            vf.createIRI("http://xmlns.com/foaf/0.1/Person"));
        connection.add(person, vf.createIRI("person:id"), vf.createLiteral(id));
    }

    protected static void updatePersons(RepositoryConnection connection, boolean timeout) {
        Update update = connection.prepareUpdate(
            "delete { ?person <person:id> ?oldId } "
                + "insert { ?person <person:id> ?newId } "
                + "where { ?person <person:id> ?oldId " + (timeout ? "; ?sleep100 []" : "") + "bind ((?oldId + 1) as ?newId) } ");
        if (timeout) {
            update.setMaxExecutionTime(1);
        }
        update.execute();
    }

    @AfterAll
    public static void afterClass() {
        tempDir.delete();
        repository.shutDown();
        tempDir = null;
        repository = null;
    }

    @Test
    public void groupByQuery() throws InterruptedException {
        Thread reader = new Thread(() -> {
            try (SailRepositoryConnection connection = repository.getConnection()) {
                TupleQuery query = connection
                    .prepareTupleQuery("select * { ?s ?p ?o ; ?sleep1200 ?o2 }");
                query.setMaxExecutionTime(1);
                long count = query
                    .evaluate()
                    .stream()
                    .count();
                System.out.println(count);
            }
        });
        Thread writer = new Thread(() -> {
            try (SailRepositoryConnection connection = repository.getConnection()) {
                updatePersons(connection, false);

                connection.begin();
                updatePersons(connection, false);
                updatePersons(connection, true);
                connection.commit();

                TupleQuery query = connection.prepareTupleQuery("select * { ?s ?p ?o }");
                query.evaluate().stream().count();

                updatePersons(connection, true);

                query = connection
                    .prepareTupleQuery("select * { ?s ?p ?o ; ?sleep2000 ?o2 }");
                query.setMaxExecutionTime(1);
                query.evaluate().stream().count();

                connection.commit();
            }
        });

        reader.start();
        writer.start();

        reader.join();
        writer.join();
    }

}
