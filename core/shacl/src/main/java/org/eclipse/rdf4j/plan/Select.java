package org.eclipse.rdf4j.plan;

import org.eclipse.rdf4j.common.iteration.CloseableIteration;
import org.eclipse.rdf4j.model.Resource;
import org.eclipse.rdf4j.model.Statement;
import org.eclipse.rdf4j.model.vocabulary.RDF;
import org.eclipse.rdf4j.sail.SailException;
import org.eclipse.rdf4j.validation.ShaclSailConnection;

/**
 * Created by heshanjayasinghe on 7/18/17.
 */
public class Select implements PlanNode {

    public ShaclSailConnection shaclSailConnection;
    Resource type;

    public Select(ShaclSailConnection shaclSailConnection, Resource type) {
        this.shaclSailConnection =shaclSailConnection;
        this.type=type;
    }


    @Override
    public CloseableIteration<Tuple,SailException> iterator(){
        CloseableIteration<? extends Statement, SailException> statements = shaclSailConnection.getStatements(null, RDF.TYPE, type, false);
        return new CloseableIteration<Tuple,SailException>() {

            @Override
            public void close() throws SailException {
                statements.close();
            }

            int counter = 0;

            @Override
            public boolean hasNext() {
               return statements.hasNext();
            }

            @Override
            public Tuple next() {

                Statement next = statements.next();
                Tuple tuple = new Tuple();
                tuple.line.add(next.getSubject());

               counter++;

                return tuple;
            }

            @Override
            public void remove() throws SailException {

            }
        };
    }

//    public MinCountPropertyShape getmincount(){
//        List<PropertyShape> propertyShapes = shaclSailConnection.sail.shapes;
//        for (PropertyShape propertyShape : propertyShapes) {
//            shape.getPlan(this,shape);
//        }
//
//
//    }


    @Override
    public boolean validate() {
//        for (PlanNode pnode : planNodes) {
//            System.out.println(pnode);
//            if(!pnode.validate()){//plan validate logic;
//                return false;
//            }
//        }
        return true;
    }
}
