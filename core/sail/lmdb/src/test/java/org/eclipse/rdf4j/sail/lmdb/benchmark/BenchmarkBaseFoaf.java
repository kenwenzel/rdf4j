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
import java.util.Locale;
import java.util.Random;

import org.apache.commons.io.FileUtils;
import org.assertj.core.util.Files;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.ValueFactory;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.repository.sail.SailRepositoryConnection;
import org.eclipse.rdf4j.sail.lmdb.LmdbStore;
import org.eclipse.rdf4j.sail.lmdb.config.LmdbStoreConfig;

public class BenchmarkBaseFoaf {

	protected File dir;
	protected boolean deleteDirOnTearDown;

	protected SailRepository repository;
	protected SailRepositoryConnection connection;

	protected Random random = new Random(12345);

	protected LmdbStoreConfig config = new LmdbStoreConfig("spoc,ospc,psoc").setForceSync(false);

	protected long personNr = 1;
	private final String[] countries = Locale.getISOCountries();
	private final String[] languages = Locale.getISOLanguages();
	private final int KNOWS_COUNT = 3;

	public void setup() throws IOException {
		personNr = 1;
		if (connection != null) {
			connection.close();
			connection = null;
		}

		if (dir == null) {
			dir = Files.newTemporaryFolder();
			deleteDirOnTearDown = true;
		}

		LmdbStore sail = new LmdbStore(dir, config);
		repository = new SailRepository(sail);
		connection = repository.getConnection();

		System.gc();
	}

	public void tearDown() throws IOException {
		if (connection != null) {
			connection.close();
			connection = null;
		}
		repository.shutDown();
		if (deleteDirOnTearDown) {
			FileUtils.deleteDirectory(dir);
		}
	}

	void addPersonNameOnly() {
		ValueFactory vf = connection.getValueFactory();

		IRI person = vf.createIRI("http://www.example.org/persons/person_" + personNr);
		// English label
		connection.add(person, vf.createIRI("http://www.w3.org/2000/01/rdf-schema#label"),
				vf.createLiteral("Name @en" + personNr, "en"));

		personNr++;
	}

	void addPerson() {
		ValueFactory vf = connection.getValueFactory();

		IRI person = vf.createIRI("http://www.example.org/persons/person_" + personNr);
		connection.add(person, vf.createIRI("http://www.w3.org/1999/02/22-rdf-syntax-ns#type"),
				vf.createIRI("http://xmlns.com/foaf/0.1/Person"));

		// English label
		connection.add(person, vf.createIRI("http://www.w3.org/2000/01/rdf-schema#label"),
				vf.createLiteral("Name @en" + personNr, "en"));

		// 3 other languages
		random.ints(3, 0, languages.length).distinct().forEach(langIndex -> {
			connection.add(person, vf.createIRI("http://www.w3.org/2000/01/rdf-schema#label"),
					vf.createLiteral("Name " + personNr, languages[langIndex]));
		});

		int countryIndex = random.nextInt(countries.length);
		int organizationNr = countryIndex + 1;
		connection.add(vf.createIRI("http://www.example.org/organizations/org_" + organizationNr),
				vf.createIRI("http://xmlns.com/foaf/0.1/member"), person);

		connection.add(person, vf.createIRI("http://www.example.org/vocab/countryCode"),
				vf.createLiteral(countries[countryIndex]));

		// the worst case here is to use a maximum distance of personNr because this possibly connects
		// persons throughout the whole database
		random.ints(KNOWS_COUNT, 1, 10000).distinct().forEach(distance -> {
			long knowsMemberNr = personNr > distance ? personNr - distance : personNr + distance;
			connection.add(person, vf.createIRI("http://xmlns.com/foaf/0.1/knows"),
					vf.createIRI("http://www.example.org/persons/person_" + knowsMemberNr));
		});

		personNr++;
	}
}