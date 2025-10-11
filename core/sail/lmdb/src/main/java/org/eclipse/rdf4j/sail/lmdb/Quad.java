package org.eclipse.rdf4j.sail.lmdb;

class Quad {

	final long subj;
	final long pred;
	final long obj;
	final long context;
	final boolean explicit;
	boolean exists;

	Quad(long subj, long pred, long obj, long context, boolean explicit) {
		this.subj = subj;
		this.pred = pred;
		this.obj = obj;
		this.context = context;
		this.explicit = explicit;
	}
}