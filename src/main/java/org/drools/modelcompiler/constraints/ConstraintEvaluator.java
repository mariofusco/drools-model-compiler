package org.drools.modelcompiler.constraints;

import java.util.stream.Stream;

import org.drools.core.common.InternalFactHandle;
import org.drools.core.rule.Declaration;
import org.drools.core.rule.Pattern;
import org.drools.core.spi.Tuple;
import org.drools.model.Index;
import org.drools.model.SingleConstraint;

public class ConstraintEvaluator {

    private final Declaration[] declarations;
    private final Pattern pattern;
    private final SingleConstraint constraint;
    private int[] argsPos;

    public ConstraintEvaluator(Declaration[] declarations, Pattern pattern, SingleConstraint constraint) {
        this.constraint = constraint;
        this.declarations = declarations;
        this.pattern = pattern;
    }

    public boolean evaluate( InternalFactHandle handle ) {
        return constraint.getPredicate().test(handle.getObject());
    }

    public boolean evaluate(InternalFactHandle handle, Tuple tuple) {
        return constraint.getPredicate().test(getInvocationArgs(handle, tuple));
    }

    private Object[] getInvocationArgs(InternalFactHandle handle, Tuple tuple) {
        if (this.argsPos == null) {
            this.argsPos = Stream.of( declarations )
                                 .map( Declaration::getPattern )
                                 .mapToInt( p -> p.getDeclaration().getIdentifier().equals( pattern.getDeclaration().getIdentifier() ) ? -1 : p.getOffset() )
                                 .toArray();
        }
        Object[] params = new Object[argsPos.length];
        for (int i = 0; i < params.length; i++) {
            params[i] = argsPos[i] >= 0 ? tuple.getObject(argsPos[i]) : handle.getObject();
        }
        return params;
    }

    public Index getIndex() {
        return constraint.getIndex();
    }

    public String[] getReactiveProps() {
        return constraint.getReactiveProps();
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (other == null || getClass() != other.getClass()) return false;
        return getId().equals(((ConstraintEvaluator) other).getId());
    }

    @Override
    public int hashCode() {
        return getId().hashCode();
    }

    public String getId() {
        return constraint.getExprId();
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

    public ConstraintEvaluator clone() {
        return new ConstraintEvaluator( Stream.of(declarations)
                                              .map( Declaration::clone )
                                              .toArray(Declaration[]::new),
                                        pattern,
                                        constraint );
    }
}
