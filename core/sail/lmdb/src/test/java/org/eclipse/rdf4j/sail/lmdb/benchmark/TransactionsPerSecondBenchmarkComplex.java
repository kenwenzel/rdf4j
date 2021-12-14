/*******************************************************************************
 * Copyright (c) 2021 Eclipse RDF4J contributors.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *******************************************************************************/

package org.eclipse.rdf4j.sail.lmdb.benchmark;

import java.io.IOException;
import java.util.concurrent.TimeUnit;

import org.eclipse.rdf4j.common.transaction.IsolationLevels;
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

/**
 * Benchmarks insertion performance with extended FOAF data.
 */
@State(Scope.Benchmark)
@Warmup(iterations = 2)
@BenchmarkMode({ Mode.Throughput })
@Fork(value = 1, jvmArgs = { "-Xms2G", "-Xmx2G", "-XX:+UseG1GC" })
@Measurement(iterations = 5)
@OutputTimeUnit(TimeUnit.SECONDS)
public class TransactionsPerSecondBenchmarkComplex extends BenchmarkBase {

	@Setup(Level.Iteration)
	public void setup() throws IOException {
		super.setup();
	}

	@TearDown(Level.Iteration)
	public void tearDown() throws IOException {
		super.tearDown();
	}

	@Benchmark
	public void insertSyntheticData() {
		connection.begin(IsolationLevels.NONE);
		for (int k = 0; k < 10000; k++) {
			addPersons();
		}
		connection.commit();
	}
}