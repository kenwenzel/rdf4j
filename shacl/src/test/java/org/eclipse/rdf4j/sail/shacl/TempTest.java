package org.eclipse.rdf4j.sail.shacl;

import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.model.vocabulary.RDFS;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.repository.sail.SailRepositoryConnection;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.Test;

import static junit.framework.TestCase.assertEquals;

public class TempTest {

	@Test
	public void testAddRemoveAddRemove() {

		SailRepository shaclSail = new SailRepository(new ShaclSail(new MemoryStore(), Utils.getSailRepository("shacl.ttl")));
		shaclSail.initialize();

		try (SailRepositoryConnection connection = shaclSail.getConnection()) {

			connection.begin();
			connection.add(RDFS.RESOURCE, RDF.TYPE, RDFS.RESOURCE);

			connection.add(RDFS.RESOURCE, RDFS.LABEL, connection.getValueFactory().createLiteral("a"));

			connection.add(RDFS.CLASS, RDF.TYPE, RDFS.RESOURCE);

			connection.add(RDFS.CLASS, RDFS.LABEL, connection.getValueFactory().createLiteral("a"));


			connection.commit();


			connection.begin();

			connection.remove(RDFS.RESOURCE, RDFS.LABEL, connection.getValueFactory().createLiteral("a"));
			connection.add(RDFS.RESOURCE, RDFS.LABEL, connection.getValueFactory().createLiteral("b"));
			connection.add(RDFS.RESOURCE, RDFS.LABEL, connection.getValueFactory().createLiteral("c"));
			connection.remove(RDFS.CLASS, RDFS.LABEL, connection.getValueFactory().createLiteral("a"));


			connection.commit();

		}


	}

}
