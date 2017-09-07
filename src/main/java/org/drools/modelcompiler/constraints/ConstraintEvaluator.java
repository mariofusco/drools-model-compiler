package org.drools.modelcompiler.constraints;

import java.util.stream.Stream;

import org.drools.core.common.InternalFactHandle;
import org.drools.core.common.InternalWorkingMemory;
import org.drools.core.rule.Declaration;
import org.drools.core.rule.Pattern;
import org.drools.core.spi.Tuple;
import org.drools.core.time.Interval;
import org.drools.model.Index;
import org.drools.model.SingleConstraint;

public class ConstraintEvaluator {

    protected final SingleConstraint constraint;

    private final Declaration[] declarations;
    private final Declaration[] requiredDeclarations;
    private final Pattern pattern;
    private int[] argsPos;

    public ConstraintEvaluator(Pattern pattern, SingleConstraint constraint) {
        this.constraint = constraint;
        this.pattern = pattern;
        this.declarations = new Declaration[] { pattern.getDeclaration() };
        this.requiredDeclarations = new Declaration[0];
    }

    public ConstraintEvaluator(Declaration[] declarations, Pattern pattern, SingleConstraint constraint) {
        this.constraint = constraint;
        this.pattern = pattern;
        this.declarations = declarations;
        this.requiredDeclarations = Stream.of( declarations )
                                          .filter( d -> !d.getIdentifier().equals( pattern.getDeclaration().getIdentifier() ) )
                                          .toArray( Declaration[]::new );
    }

    public boolean evaluate( InternalFactHandle handle, InternalWorkingMemory workingMemory ) {
        return constraint.getPredicate().test( declarations.length == 1 ?
                                               new Object[] { handle.getObject() } :
                                               getAlphaInvocationArgs( handle, workingMemory ) );
    }

    public Object[] getAlphaInvocationArgs( InternalFactHandle handle, InternalWorkingMemory workingMemory ) {
        Object[] params = new Object[declarations.length];
        for (int i = 0; i < params.length; i++) {
            params[i] = pattern.getDeclaration().getIdentifier().equals( declarations[i].getIdentifier() ) ?
                        handle.getObject() :
                        declarations[1].getValue( workingMemory, null ) ;
        }
        return params;
    }

    public boolean evaluate(InternalFactHandle handle, Tuple tuple) {
        return constraint.getPredicate().test( getBetaInvocationArgs( handle, tuple ) );
    }

    private Object[] getBetaInvocationArgs( InternalFactHandle handle, Tuple tuple ) {
        initArgsPos();
        Object[] params = new Object[argsPos.length];
        for (int i = 0; i < params.length; i++) {
            params[i] = argsPos[i] >= 0 ? tuple.getObject(argsPos[i]) : handle.getObject();
        }
        return params;
    }

    protected InternalFactHandle[] getBetaInvocationFactHandles( InternalFactHandle handle, Tuple tuple ) {
        initArgsPos();
        InternalFactHandle[] fhs = new InternalFactHandle[argsPos.length];
        for (int i = 0; i < fhs.length; i++) {
            fhs[i] = argsPos[i] >= 0 ? tuple.get(argsPos[i]) : handle;
        }
        return fhs;
    }

    private void initArgsPos() {
        if (this.argsPos == null) {
            this.argsPos = Stream.of( declarations )
                                 .map( Declaration::getPattern )
                                 .mapToInt( p -> p.getDeclaration().getIdentifier().equals( pattern.getDeclaration().getIdentifier() ) ? -1 : p.getOffset() )
                                 .toArray();
        }
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
        return requiredDeclarations;
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

    public boolean isTemporal() {
        return false;
    }

    public Interval getInterval() {
        throw new UnsupportedOperationException();
    }
}
