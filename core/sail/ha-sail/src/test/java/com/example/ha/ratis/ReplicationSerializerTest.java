package com.example.ha.ratis;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.Test;

public class ReplicationSerializerTest {

    @Test
    public void roundTripAddsAndRemoves() throws Exception {
        SimpleValueFactory vf = SimpleValueFactory.getInstance();
        IRI s = vf.createIRI("http://example/s");
        IRI p = vf.createIRI("http://example/p");
        IRI o = vf.createIRI("http://example/o");
        Statement st = vf.createStatement(s, p, o);

        byte[] bytes = ReplicationSerializer.serialize(List.of(st), List.of());
        var dto = ReplicationSerializer.deserialize(bytes);
        assertNotNull(dto);
        assertEquals(1, dto.adds.size());
        var restored = dto.adds.get(0).toStatement();
        assertEquals(st.getSubject().stringValue(), restored.getSubject().stringValue());
        assertEquals(st.getPredicate().stringValue(), restored.getPredicate().stringValue());
        assertEquals(st.getObject().stringValue(), restored.getObject().stringValue());
    }
}