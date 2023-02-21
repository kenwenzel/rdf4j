package org.eclipse.rdf4j.sail.lmdb;

import java.nio.ByteBuffer;

public interface Matcher {
    boolean matches(ByteBuffer other);
}
