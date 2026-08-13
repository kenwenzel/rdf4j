package com.example.ha.ratis;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.eclipse.rdf4j.model.IRI;
import org.eclipse.rdf4j.model.Literal;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.Value;
import org.eclipse.rdf4j.model.impl.SimpleValueFactory;

/**
 * Serialize/deserialize batches of Statements (adds/removes).
 *
 * Uses JSON (Jackson) for simplicity. Swap this out for Protobuf if you prefer.
 */
public final class ReplicationSerializer {
    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final SimpleValueFactory VF = SimpleValueFactory.getInstance();

    private ReplicationSerializer() {}

    // DTOs for JSON
    public static final class BatchDTO {
        public final List<StatementDTO> adds;
        public final List<StatementDTO> removes;

        @JsonCreator
        public BatchDTO(@JsonProperty("adds") List<StatementDTO> adds,
                        @JsonProperty("removes") List<StatementDTO> removes) {
            this.adds = adds == null ? List.of() : adds;
            this.removes = removes == null ? List.of() : removes;
        }
    }

    public static final class StatementDTO {
        public final String subject;
        public final String predicate;
        public final String object;
        public final boolean objectIsLiteral;
        public final String datatype;   // nullable for literal
        public final String language;   // nullable for literal
        public final String context;    // nullable

        @JsonCreator
        public StatementDTO(@JsonProperty("subject") String subject,
                            @JsonProperty("predicate") String predicate,
                            @JsonProperty("object") String object,
                            @JsonProperty("objectIsLiteral") boolean objectIsLiteral,
                            @JsonProperty("datatype") String datatype,
                            @JsonProperty("language") String language,
                            @JsonProperty("context") String context) {
            this.subject = subject;
            this.predicate = predicate;
            this.object = object;
            this.objectIsLiteral = objectIsLiteral;
            this.datatype = datatype;
            this.language = language;
            this.context = context;
        }

        static StatementDTO fromStatement(Statement s) {
            Value obj = s.getObject();
            boolean isLit = obj instanceof Literal;
            String datatype = null;
            String language = null;
            String objString = obj.stringValue();
            if (isLit) {
                Literal lit = (Literal) obj;
                if (lit.getDatatype() != null) {
                    datatype = lit.getDatatype().toString();
                }
                if (!lit.getLanguage().isEmpty()) {
                    language = lit.getLanguage().get(); // optional
                }
            }
            String ctx = s.getContext() == null ? null : s.getContext().stringValue();
            return new StatementDTO(s.getSubject().stringValue(),
                    s.getPredicate().stringValue(),
                    objString,
                    isLit,
                    datatype,
                    language,
                    ctx);
        }

        Statement toStatement() {
            IRI subj = VF.createIRI(subject);
            IRI pred = VF.createIRI(predicate);
            Value obj;
            if (objectIsLiteral) {
                if (datatype != null) {
                    obj = VF.createLiteral(object, VF.createIRI(datatype));
                } else if (language != null) {
                    obj = VF.createLiteral(object, language);
                } else {
                    obj = VF.createLiteral(object);
                }
            } else {
                obj = VF.createIRI(object);
            }
            IRI c = context == null ? null : VF.createIRI(context);
            return VF.createStatement(subj, pred, obj, c);
        }
    }

    public static byte[] serialize(List<Statement> adds, List<Statement> removes) throws IOException {
        List<StatementDTO> a = adds == null ? List.of() : adds.stream().map(StatementDTO::fromStatement).collect(Collectors.toList());
        List<StatementDTO> r = removes == null ? List.of() : removes.stream().map(StatementDTO::fromStatement).collect(Collectors.toList());
        BatchDTO dto = new BatchDTO(a, r);
        return MAPPER.writeValueAsBytes(dto);
    }

    public static BatchDTO deserialize(byte[] bytes) throws IOException {
        return MAPPER.readValue(bytes, BatchDTO.class);
    }

    // Helpers if you want Statement lists directly
    public static List<Statement> toStatements(List<StatementDTO> dtos) {
        List<Statement> result = new ArrayList<>(dtos.size());
        for (StatementDTO d : dtos) {
            result.add(d.toStatement());
        }
        return result;
    }
}