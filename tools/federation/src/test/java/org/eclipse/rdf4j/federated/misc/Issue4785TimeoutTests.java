package org.eclipse.rdf4j.federated.misc;

import org.eclipse.rdf4j.federated.FedXFactory;
import org.eclipse.rdf4j.federated.endpoint.Endpoint;
import org.eclipse.rdf4j.federated.endpoint.EndpointClassification;
import org.eclipse.rdf4j.federated.endpoint.EndpointFactory;
import org.eclipse.rdf4j.federated.endpoint.EndpointType;
import org.eclipse.rdf4j.federated.endpoint.RepositoryEndpoint;
import org.eclipse.rdf4j.federated.endpoint.provider.RepositoryInformation;
import org.eclipse.rdf4j.federated.repository.FedXRepository;
import org.eclipse.rdf4j.federated.structures.FedXTupleQuery;
import org.eclipse.rdf4j.query.BindingSet;
import org.eclipse.rdf4j.query.Query;
import org.eclipse.rdf4j.repository.RepositoryConnection;
import org.eclipse.rdf4j.repository.sail.SailRepository;
import org.eclipse.rdf4j.sail.memory.MemoryStore;
import org.junit.Assert;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.stream.Collectors;

public class Issue4785TimeoutTests {

    static private final String queryPrefixes = "prefix fmi: <http://iwu.fraunhofer.de/h2link/fmi/> " +
        "prefix ssp: <http://iwu.fraunhofer.de/h2link/ssp/>" +
        "prefix rdf: <http://www.w3.org/1999/02/22-rdf-syntax-ns#>" +
        "prefix rdfs: <http://www.w3.org/2000/01/rdf-schema#>" +
        "prefix port: <http://iwu.fraunhofer.de/h2link/port/>" +
        "prefix h2link: <http://iwu.fraunhofer.de/h2link/db/>" +
        "prefix dfe: <http://example.org/models/h2link/dfe/>";

    static Endpoint asLocal(Endpoint endpoint) {
        return new RepositoryEndpoint(new RepositoryInformation(endpoint.getId(), endpoint.getName(), "", EndpointType.NativeStore),
            "", EndpointClassification.Local, endpoint.getRepository());
    }

    @Test
    public void testLocal() throws IOException {
        runTest(true);
    }

    @Test
    public void testRemote() throws IOException {
        runTest(false);
    }

    public void runTest(boolean useLocalEndpoints) throws IOException {
        SailRepository repo1 = new SailRepository(new MemoryStore());
        try (RepositoryConnection conn = repo1.getConnection()) {
            conn.add(Issue4785TimeoutTests.class.getResource("/tests/misc/db.ttl"));
        }

        SailRepository repo2 = new SailRepository(new MemoryStore());
        try (RepositoryConnection conn = repo2.getConnection()) {
            conn.add(Issue4785TimeoutTests.class.getResource("/tests/misc/frontendData.ttl"));
        }

        List<Endpoint> endpoints = List.of(
            EndpointFactory.loadEndpoint("repo1", repo1),
            EndpointFactory.loadEndpoint("repo2", repo2)
        );

        if (useLocalEndpoints) {
            endpoints = endpoints.stream().map(ep -> asLocal(ep)).collect(Collectors.toList());
        }

        FedXRepository fedXRepository = FedXFactory.newFederation().withMembers(endpoints).create();
        try (RepositoryConnection fedxConn = fedXRepository.getConnection()) {

            //QueryManager qm = fedXRepository.getQueryManager();

            Query queryComponent = fedxConn.prepareQuery(
                queryPrefixes +
                    "select distinct ?comp" +
                    "{" +
                    "{?interface a port:Port.} UNION {?interface a fmi:ScalarVariable.}. " +
                    "?comp a fmi:FmuModel. " +
                    "?conn a ssp:Connection. " +
                    "?interface  port:isPortOf | fmi:isVariableOf ?comp. " +
                    "?conn ssp:connectsFrom | ssp:connectsTo ?interface." +
                    "}");

            List<BindingSet> componentList = ((FedXTupleQuery) queryComponent).evaluate().stream().collect(Collectors.toList());
            Assert.assertTrue(componentList.size() > 0);

            componentList.forEach(componentListElement -> {
                String component = componentListElement.getBinding("comp").getValue().stringValue();

                Query queryPorts = fedxConn.prepareQuery(
                    queryPrefixes +
                        "select DISTINCT ?port" +
                        "{ ?port a port:Port." +
                        "?comp a fmi:FmuModel." +
                        "?port port:isPortOf <" + component + ">." +
                        "}");
                List<BindingSet> portList = ((FedXTupleQuery) queryPorts).evaluate().stream().collect(Collectors.toList());
                Assert.assertTrue(portList.size() > 0);

                Query queryConnVariables = fedxConn.prepareQuery(
                    queryPrefixes +
                        "select DISTINCT ?variable" +
                        "{ ?variable a fmi:ScalarVariable." +
                        "?comp a fmi:FmuModel." +
                        "?variable  fmi:isVariableOf <" + component + ">." +
                        "?conn ssp:connectsFrom | ssp:connectsTo ?variable." +
                        "}");

                queryConnVariables.setMaxExecutionTime(2);
                // the next line is the one which hangs up, Timeout
                List<BindingSet> variableList = ((FedXTupleQuery) queryConnVariables).evaluate().stream().collect(Collectors.toList());
                Assert.assertTrue(variableList.isEmpty());
            });
        }
        fedXRepository.shutDown();
        repo1.shutDown();
        repo2.shutDown();
    }
}
