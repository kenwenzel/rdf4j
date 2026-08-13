package com.example.ha.ratis;

import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.times;

import java.util.List;

import com.example.ha.ratis.ReplicationSerializer.StatementDTO;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

public class RatisStateMachineAdapterTest {

    @Test
    public void adapterAppliesDeserializedBatch() throws Exception {
        StatementApplier applier = Mockito.mock(StatementApplier.class);
        RatisStateMachineAdapter adapter = new RatisStateMachineAdapter(applier);

        SimpleValueFactory vf = SimpleValueFactory.getInstance();
        Statement st = vf.createStatement(vf.createIRI("s"), vf.createIRI("p"), vf.createIRI("o"));

        byte[] payload = ReplicationSerializer.serialize(List.of(st), List.of());
        adapter.applyPayload(payload);

        // verify applier was invoked once (adds + removes)
        verify(applier, times(1)).apply(Mockito.anyList(), Mockito.anyList());
    }
}