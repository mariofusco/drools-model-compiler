package org.drools.modelcompiler.constraints;

import java.util.stream.Stream;

import org.drools.core.common.InternalFactHandle;
import org.drools.core.rule.Declaration;
import org.drools.core.rule.Pattern;
import org.drools.core.spi.Tuple;
import org.drools.model.Index;
import org.drools.model.SingleConstraint;
import org.drools.model.functions.PredicateN;

public class ConstraintEvaluator {

    private final String id;
    private final PredicateN predicate;
    private final Index index;
    private final String[] reactiveProps;
    private final Declaration[] declarations;
    private final Pattern pattern;
    private int[] argsPos;

    public ConstraintEvaluator(Declaration[] declarations, Pattern pattern, SingleConstraint constraint) {
        this.id = constraint.getExprId();
        this.predicate = constraint.getPredicate();
        this.index = constraint.getIndex();
        this.reactiveProps = constraint.getReactiveProps();
        this.declarations = declarations;
        this.pattern = pattern;
    }

    public boolean evaluate( InternalFactHandle handle ) {
        return predicate.test(handle.getObject());
    }

    public boolean evaluate(InternalFactHandle handle, Tuple tuple) {
        if (argsPos == null) {
            this.argsPos = Stream.of( declarations ).map( Declaration::getPattern ).mapToInt( p -> p.equals( pattern ) ? -1 : p.getOffset() ).toArray();
        }
        return predicate.test(getInvocationArgs(argsPos, handle, tuple));
    }

    private Object[] getInvocationArgs(int[] argsPos, InternalFactHandle handle, Tuple tuple) {
        Object[] params = new Object[argsPos.length];
        for (int i = 0; i < params.length; i++) {
            params[i] = argsPos[i] >= 0 ? tuple.getObject(argsPos[i]) : handle.getObject();
        }
        return params;
    }

    public Index getIndex() {
        return index;
    }

    public String[] getReactiveProps() {
        return reactiveProps;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (other == null || getClass() != other.getClass()) return false;
        return id.equals(((ConstraintEvaluator) other).id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    public Declaration[] getRequiredDeclarations() {
        return declarations;
    }

    public void replaceDeclaration(Declaration oldDecl, Declaration newDecl) {
        for ( int i = 0; i < declarations.length; i++) {
            if ( declarations[i].equals( oldDecl )) {
                declarations[i] = newDecl;
                break;
            }
        }
    }
}
