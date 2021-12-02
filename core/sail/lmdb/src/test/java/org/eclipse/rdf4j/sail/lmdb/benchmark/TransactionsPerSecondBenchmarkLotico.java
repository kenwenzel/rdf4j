/*******************************************************************************
 * Copyright (c) 2020 Eclipse RDF4J contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *******************************************************************************/

package org.eclipse.rdf4j.sail.lmdb.benchmark;

import java.io.File;
import java.io.IOException;
import java.util.Random;
import java.util.concurrent.TimeUnit;

import org.apache.commons.io.FileUtils;
import org.assertj.core.util.Files;
import org.eclipse.rdf4j.common.transaction.IsolationLevels;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.repository.sail.SailRepositoryConnection;
import org.eclipse.rdf4j.sail.lmdb.LmdbStore;
import org.eclipse.rdf4j.sail.lmdb.config.LmdbStoreConfig;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Level;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.runner.Runner;
import org.openjdk.jmh.runner.RunnerException;
import org.openjdk.jmh.runner.options.Options;
import org.openjdk.jmh.runner.options.OptionsBuilder;

/**
 * Benchmarks insertion performance with synthetic lotico data.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 2)
@BenchmarkMode({ Mode.AverageTime })
@Fork(value = 1, jvmArgs = { "-Xms2G", "-Xmx2G", "-XX:+UseG1GC" })
@Measurement(iterations = 5)
@OutputTimeUnit(TimeUnit.SECONDS)
public class TransactionsPerSecondBenchmarkLotico {

	SailRepositoryConnection connection;
	String[] countries = { "DE", "US", "FR", "UK", "ES", "IT", "CA", "CN", "AU", "IN" };
	Random random = new Random();
	private SailRepository repository;
	private File file;

	public static void main(String[] args) throws RunnerException {
		Options opt = new OptionsBuilder()
				.include("TransactionsPerSecondBenchmarkLotico") // adapt to control which benchmark tests to run
				// .addProfiler("stack", "lines=20;period=1;top=20")
				.forks(1)
				.build();

		new Runner(opt).run();
	}

	@Setup(Level.Iteration)
	public void beforeClass() {
		if (connection != null) {
			connection.close();
			connection = null;
		}
		file = Files.newTemporaryFolder();

		LmdbStore sail = new LmdbStore(file, new LmdbStoreConfig("spoc,ospc,psoc").setForceSync(false));
		repository = new SailRepository(sail);
		connection = repository.getConnection();

		System.gc();

	}

	@TearDown(Level.Iteration)
	public void afterClass() throws IOException {
		if (connection != null) {
			connection.close();
			connection = null;
		}
		repository.shutDown();
		FileUtils.deleteDirectory(file);
	}

	@Benchmark
	public void insertSyntheticData() {
		int i = 1;
		ValueFactory vf = connection.getValueFactory();
		for (int block = 0; block < 100; block++) {
			connection.begin(IsolationLevels.NONE);
			for (int k = 0; k < 10000; k++) {
				int chapter = random.nextInt(10) + 1;
				String country = countries[chapter - 1];
				int knowsMember = random.nextInt(i) + 1;

				IRI person = vf.createIRI("http://www.lotico.com/resource/", "person_" + i);
				connection.add(person, vf.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
						vf.createIRI("http://www.lotico.com/ontology/Member"));
				connection.add(person, vf.createIRI("http://www.w3.org/2000/01/rdf-schema#label"),
						vf.createLiteral("Name " + i));
				connection.add(person, vf.createIRI("http://www.lotico.com/ontology/memberOf"),
						vf.createIRI("http://www.lotico.com/resource/chapter_" + chapter));
				connection.add(person, vf.createIRI("http://www.lotico.com/ontology/countryCode"),
						vf.createLiteral(country));
				connection.add(person, vf.createIRI("http://xmlns.com/foaf/0.1/knows"),
						vf.createIRI("http://www.lotico.com/resource/person_" + knowsMember));

				i++;
			}
			connection.commit();
			System.out.println("Wrote block " + block);
		}
	}
}
