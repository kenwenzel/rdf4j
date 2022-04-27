/*******************************************************************************
 * Copyright (c) 2015 Eclipse RDF4J contributors, Aduna, and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Distribution License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/org/documents/edl-v10.php.
 *******************************************************************************/
package org.eclipse.rdf4j.http.server;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.ServerConnector;
import org.eclipse.jetty.webapp.WebAppContext;
import org.eclipse.rdf4j.http.protocol.Protocol;
import org.eclipse.rdf4j.http.server.TestServer.PropertiesReader;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.eclipse.rdf4j.model.impl.TreeModel;
import org.eclipse.rdf4j.model.util.Models;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.repository.RepositoryException;
import org.eclipse.rdf4j.repository.config.RepositoryConfig;
import org.eclipse.rdf4j.repository.config.RepositoryConfigException;
import org.eclipse.rdf4j.repository.config.RepositoryConfigSchema;
import org.eclipse.rdf4j.repository.manager.RemoteRepositoryManager;
import org.eclipse.rdf4j.repository.sail.config.SailRepositoryConfig;
import org.eclipse.rdf4j.rio.RDFFormat;
import org.eclipse.rdf4j.rio.RDFParser;
import org.eclipse.rdf4j.rio.Rio;
import org.eclipse.rdf4j.rio.helpers.StatementCollector;
import org.eclipse.rdf4j.sail.inferencer.fc.config.SchemaCachingRDFSInferencerConfig;
import org.eclipse.rdf4j.sail.lmdb.config.LmdbStoreConfig;
import org.eclipse.rdf4j.sail.memory.config.MemoryStoreConfig;
import org.eclipse.rdf4j.sail.shacl.config.ShaclSailConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * @author Herko ter Horst
 */
public class TestServerLmdb {

	private static final Logger logger = LoggerFactory.getLogger(TestServerLmdb.class);

	private static final String HOST = "localhost";

	private static final int PORT = 18080;

	public static final String TEST_REPO_ID = "Test";

	private static final String RDF4J_CONTEXT = "/rdf4j";

	public static final String SERVER_URL = "http://" + HOST + ":" + PORT + RDF4J_CONTEXT;
	public static String REPOSITORY_URL = Protocol.getRepositoryLocation(SERVER_URL, TEST_REPO_ID);

	private final RemoteRepositoryManager manager;

	private final Server jetty;

	public TestServerLmdb() throws IOException {
		System.clearProperty("DEBUG");
		PropertiesReader reader = new PropertiesReader("maven-config.properties");
		String webappDir = reader.getProperty("testserver.webapp.dir");
		logger.debug("build path: {}", webappDir);

		jetty = new Server();

		ServerConnector conn = new ServerConnector(jetty);
		conn.setHost(HOST);
		conn.setPort(PORT);
		jetty.addConnector(conn);

		WebAppContext webapp = new WebAppContext();
		webapp.addSystemClass("org.slf4j.");
		webapp.addSystemClass("ch.qos.logback.");
		webapp.setContextPath(RDF4J_CONTEXT);
		// warPath configured in pom.xml maven-war-plugin configuration
		webapp.setWar("./target/rdf4j-server");
		jetty.setHandler(webapp);

		manager = RemoteRepositoryManager.getInstance(SERVER_URL);
	}

	RemoteRepositoryManager getRepositoryManager() {
		return manager;
	}

	public void start() throws Exception {
		File dataDir = new File(System.getProperty("user.dir") + "/target/datadir");
		dataDir.mkdirs();
		System.setProperty("org.eclipse.rdf4j.appdata.basedir", dataDir.getAbsolutePath());

		jetty.start();
		createTestRepositories();
	}

	public void stop() throws Exception {
		try {
			manager.getAllRepositoryInfos().forEach(ri -> manager.removeRepository(ri.getId()));
			manager.shutDown();
		} finally {
			jetty.stop();
			System.clearProperty("org.mortbay.log.class");
		}
	}

	private void createTestRepositories() throws RepositoryException, RepositoryConfigException, IOException {
		TreeModel graph = new TreeModel();

		InputStream config = getClass().getResourceAsStream("/lucene+lmdb.ttl");
		RDFParser rdfParser = Rio.createParser(RDFFormat.TURTLE);
		rdfParser.setRDFHandler(new StatementCollector(graph));
		rdfParser.parse(config, RepositoryConfigSchema.NAMESPACE);
		config.close();

		Resource repositoryNode = Models.subject(graph.filter(null, RDF.TYPE, RepositoryConfigSchema.REPOSITORY))
				.orElse(null);

		graph.add(repositoryNode, RepositoryConfigSchema.REPOSITORYID,
				SimpleValueFactory.getInstance().createLiteral(TEST_REPO_ID));

		/*
		 * LmdbStoreConfig lmdbStoreConfig = new LmdbStoreConfig(); SailRepositoryConfig sailRepConfig = new
		 * SailRepositoryConfig(lmdbStoreConfig); RepositoryConfig repConfig = new RepositoryConfig(TEST_REPO_ID,
		 * sailRepConfig); manager.addRepositoryConfig(repConfig);
		 */

		manager.addRepositoryConfig(RepositoryConfig.create(graph, repositoryNode));
	}

	static class PropertiesReader {
		private final Properties properties;

		public PropertiesReader(String propertyFileName) throws IOException {
			InputStream is = getClass().getClassLoader()
					.getResourceAsStream(propertyFileName);
			this.properties = new Properties();
			this.properties.load(is);
		}

		public String getProperty(String propertyName) {
			return this.properties.getProperty(propertyName);
		}
	}

}
