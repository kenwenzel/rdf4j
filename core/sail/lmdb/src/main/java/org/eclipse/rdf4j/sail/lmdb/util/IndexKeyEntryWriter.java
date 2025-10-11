package org.eclipse.rdf4j.sail.lmdb.util;

import java.nio.ByteBuffer;

import org.eclipse.rdf4j.sail.lmdb.Varint;

public final class IndexKeyEntryWriter {
	@FunctionalInterface
	public interface KeyWriter {
		void writeKey(ByteBuffer key, long subj, long pred, long obj, long context);
	}

	public static KeyWriter forFieldSeq(String fieldSeq) {
		switch (fieldSeq) {
		case "spoc":
			return IndexKeyEntryWriter::spocKey;
		case "spco":
			return IndexKeyEntryWriter::spcoKey;
		case "sopc":
			return IndexKeyEntryWriter::sopcKey;
		case "socp":
			return IndexKeyEntryWriter::socpKey;
		case "scpo":
			return IndexKeyEntryWriter::scpoKey;
		case "scop":
			return IndexKeyEntryWriter::scopKey;
		case "psoc":
			return IndexKeyEntryWriter::psocKey;
		case "psco":
			return IndexKeyEntryWriter::pscoKey;
		case "posc":
			return IndexKeyEntryWriter::poscKey;
		case "pocs":
			return IndexKeyEntryWriter::pocsKey;
		case "pcso":
			return IndexKeyEntryWriter::pcsoKey;
		case "pcos":
			return IndexKeyEntryWriter::pcosKey;
		case "ospc":
			return IndexKeyEntryWriter::ospcKey;
		case "oscp":
			return IndexKeyEntryWriter::oscpKey;
		case "opsc":
			return IndexKeyEntryWriter::opscKey;
		case "opcs":
			return IndexKeyEntryWriter::opcsKey;
		case "ocsp":
			return IndexKeyEntryWriter::ocspKey;
		case "ocps":
			return IndexKeyEntryWriter::ocpsKey;
		case "cspo":
			return IndexKeyEntryWriter::cspoKey;
		case "csop":
			return IndexKeyEntryWriter::csopKey;
		case "cpso":
			return IndexKeyEntryWriter::cpsoKey;
		case "cpos":
			return IndexKeyEntryWriter::cposKey;
		case "cosp":
			return IndexKeyEntryWriter::cospKey;
		case "cops":
			return IndexKeyEntryWriter::copsKey;
		default:
			throw new IllegalArgumentException("Unsupported field sequence: " + fieldSeq);
		}
	}

	// Key writing methods for each field sequence
	private static void spocKey(ByteBuffer key, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(key, subj);
		Varint.writeUnsigned(key, pred);
	}

	private static void spcoKey(ByteBuffer key, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(key, subj);
		Varint.writeUnsigned(key, pred);
	}

	private static void sopcKey(ByteBuffer key, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(key, subj);
		Varint.writeUnsigned(key, obj);
	}

	private static void socpKey(ByteBuffer key, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(key, subj);
		Varint.writeUnsigned(key, obj);
	}

	private static void scpoKey(ByteBuffer key, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(key, subj);
		Varint.writeUnsigned(key, context);
	}

	private static void scopKey(ByteBuffer key, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(key, subj);
		Varint.writeUnsigned(key, context);
	}

	private static void psocKey(ByteBuffer key, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(key, pred);
		Varint.writeUnsigned(key, subj);
	}

	private static void pscoKey(ByteBuffer key, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(key, pred);
		Varint.writeUnsigned(key, subj);
	}

	private static void poscKey(ByteBuffer key, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(key, pred);
		Varint.writeUnsigned(key, obj);
	}

	private static void pocsKey(ByteBuffer key, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(key, pred);
		Varint.writeUnsigned(key, obj);
	}

	private static void pcsoKey(ByteBuffer key, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(key, pred);
		Varint.writeUnsigned(key, context);
	}

	private static void pcosKey(ByteBuffer key, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(key, pred);
		Varint.writeUnsigned(key, context);
	}

	private static void ospcKey(ByteBuffer key, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(key, obj);
		Varint.writeUnsigned(key, subj);
	}

	private static void oscpKey(ByteBuffer key, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(key, obj);
		Varint.writeUnsigned(key, subj);
	}

	private static void opscKey(ByteBuffer key, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(key, obj);
		Varint.writeUnsigned(key, pred);
	}

	private static void opcsKey(ByteBuffer key, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(key, obj);
		Varint.writeUnsigned(key, pred);
	}

	private static void ocspKey(ByteBuffer key, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(key, obj);
		Varint.writeUnsigned(key, context);
	}

	private static void ocpsKey(ByteBuffer key, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(key, obj);
		Varint.writeUnsigned(key, context);
	}

	private static void cspoKey(ByteBuffer key, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(key, context);
		Varint.writeUnsigned(key, subj);
	}

	private static void csopKey(ByteBuffer key, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(key, context);
		Varint.writeUnsigned(key, subj);
	}

	private static void cpsoKey(ByteBuffer key, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(key, context);
		Varint.writeUnsigned(key, pred);
	}

	private static void cposKey(ByteBuffer key, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(key, context);
		Varint.writeUnsigned(key, pred);
	}

	private static void cospKey(ByteBuffer key, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(key, context);
		Varint.writeUnsigned(key, obj);
	}

	private static void copsKey(ByteBuffer key, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(key, context);
		Varint.writeUnsigned(key, obj);
	}
}
