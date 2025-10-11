package org.eclipse.rdf4j.sail.lmdb.util;

import java.nio.ByteBuffer;

import org.eclipse.rdf4j.sail.lmdb.Varint;

public final class IndexValueEntryWriter {
	@FunctionalInterface
	public interface ValueWriter {
		void writeValue(ByteBuffer value, long subj, long pred, long obj, long context);
	}

	public static ValueWriter forFieldSeq(String fieldSeq) {
		switch (fieldSeq) {
		case "spoc":
			return IndexValueEntryWriter::spocValue;
		case "spco":
			return IndexValueEntryWriter::spcoValue;
		case "sopc":
			return IndexValueEntryWriter::sopcValue;
		case "socp":
			return IndexValueEntryWriter::socpValue;
		case "scpo":
			return IndexValueEntryWriter::scpoValue;
		case "scop":
			return IndexValueEntryWriter::scopValue;
		case "psoc":
			return IndexValueEntryWriter::psocValue;
		case "psco":
			return IndexValueEntryWriter::pscoValue;
		case "posc":
			return IndexValueEntryWriter::poscValue;
		case "pocs":
			return IndexValueEntryWriter::pocsValue;
		case "pcso":
			return IndexValueEntryWriter::pcsoValue;
		case "pcos":
			return IndexValueEntryWriter::pcosValue;
		case "ospc":
			return IndexValueEntryWriter::ospcValue;
		case "oscp":
			return IndexValueEntryWriter::oscpValue;
		case "opsc":
			return IndexValueEntryWriter::opscValue;
		case "opcs":
			return IndexValueEntryWriter::opcsValue;
		case "ocsp":
			return IndexValueEntryWriter::ocspValue;
		case "ocps":
			return IndexValueEntryWriter::ocpsValue;
		case "cspo":
			return IndexValueEntryWriter::cspoValue;
		case "csop":
			return IndexValueEntryWriter::csopValue;
		case "cpso":
			return IndexValueEntryWriter::cpsoValue;
		case "cpos":
			return IndexValueEntryWriter::cposValue;
		case "cosp":
			return IndexValueEntryWriter::cospValue;
		case "cops":
			return IndexValueEntryWriter::copsValue;
		default:
			throw new IllegalArgumentException("Unsupported field sequence: " + fieldSeq);
		}
	}

	// Value writing methods for each field sequence
	private static void spocValue(ByteBuffer value, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(value, obj);
		Varint.writeUnsigned(value, context);
	}

	private static void spcoValue(ByteBuffer value, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(value, context);
		Varint.writeUnsigned(value, obj);
	}

	private static void sopcValue(ByteBuffer value, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(value, pred);
		Varint.writeUnsigned(value, context);
	}

	private static void socpValue(ByteBuffer value, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(value, context);
		Varint.writeUnsigned(value, pred);
	}

	private static void scpoValue(ByteBuffer value, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(value, pred);
		Varint.writeUnsigned(value, obj);
	}

	private static void scopValue(ByteBuffer value, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(value, obj);
		Varint.writeUnsigned(value, pred);
	}

	private static void psocValue(ByteBuffer value, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(value, obj);
		Varint.writeUnsigned(value, context);
	}

	private static void pscoValue(ByteBuffer value, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(value, context);
		Varint.writeUnsigned(value, obj);
	}

	private static void poscValue(ByteBuffer value, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(value, subj);
		Varint.writeUnsigned(value, context);
	}

	private static void pocsValue(ByteBuffer value, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(value, context);
		Varint.writeUnsigned(value, subj);
	}

	private static void pcsoValue(ByteBuffer value, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(value, subj);
		Varint.writeUnsigned(value, obj);
	}

	private static void pcosValue(ByteBuffer value, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(value, obj);
		Varint.writeUnsigned(value, subj);
	}

	private static void ospcValue(ByteBuffer value, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(value, pred);
		Varint.writeUnsigned(value, context);
	}

	private static void oscpValue(ByteBuffer value, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(value, context);
		Varint.writeUnsigned(value, pred);
	}

	private static void opscValue(ByteBuffer value, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(value, subj);
		Varint.writeUnsigned(value, context);
	}

	private static void opcsValue(ByteBuffer value, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(value, context);
		Varint.writeUnsigned(value, subj);
	}

	private static void ocspValue(ByteBuffer value, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(value, subj);
		Varint.writeUnsigned(value, pred);
	}

	private static void ocpsValue(ByteBuffer value, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(value, pred);
		Varint.writeUnsigned(value, subj);
	}

	private static void cspoValue(ByteBuffer value, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(value, pred);
		Varint.writeUnsigned(value, obj);
	}

	private static void csopValue(ByteBuffer value, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(value, obj);
		Varint.writeUnsigned(value, pred);
	}

	private static void cpsoValue(ByteBuffer value, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(value, subj);
		Varint.writeUnsigned(value, obj);
	}

	private static void cposValue(ByteBuffer value, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(value, obj);
		Varint.writeUnsigned(value, subj);
	}

	private static void cospValue(ByteBuffer value, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(value, subj);
		Varint.writeUnsigned(value, pred);
	}

	private static void copsValue(ByteBuffer value, long subj, long pred, long obj, long context) {
		Varint.writeUnsigned(value, pred);
		Varint.writeUnsigned(value, subj);
	}
}
