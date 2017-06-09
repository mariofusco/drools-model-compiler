package org.drools.modelcompiler.constraints;

import java.util.List;

import org.drools.core.base.ValueType;
import org.drools.core.base.field.ObjectFieldImpl;
import org.drools.core.common.InternalFactHandle;
import org.drools.core.common.InternalWorkingMemory;
import org.drools.core.reteoo.PropertySpecificUtil;
import org.drools.core.rule.ContextEntry;
import org.drools.core.rule.Declaration;
import org.drools.core.rule.IndexableConstraint;
import org.drools.core.rule.MutableTypeConstraint;
import org.drools.core.rule.constraint.MvelConstraint;
import org.drools.core.spi.FieldValue;
import org.drools.core.spi.InternalReadAccessor;
import org.drools.core.spi.Tuple;
import org.drools.core.util.AbstractHashTable.FieldIndex;
import org.drools.core.util.bitmask.BitMask;
import org.drools.core.util.index.IndexUtil;
import org.drools.model.AlphaIndex;
import org.drools.model.Index;

import static org.drools.core.reteoo.PropertySpecificUtil.getEmptyPropertyReactiveMask;

public class LambdaConstraint extends MutableTypeConstraint implements IndexableConstraint {

    private final ConstraintEvaluator evaluator;

    private FieldValue field;
    private InternalReadAccessor readAccessor;

    public LambdaConstraint(ConstraintEvaluator evaluator) {
        this.evaluator = evaluator;
        initIndexes();
    }

    private void initIndexes() {
        Index index = evaluator.getIndex();
        if (index instanceof AlphaIndex) {
            Object value = ( (AlphaIndex) index ).getRightValue();
            field = new ObjectFieldImpl( value );
            ValueType vType = ValueType.determineValueType( value.getClass() );
            // TODO LambdaReadAccessor.index ???
            readAccessor = new LambdaReadAccessor( 0, value.getClass(), vType, ( (AlphaIndex) index ).getLeftOperandExtractor() );
        }
    }

    @Override
    public Declaration[] getRequiredDeclarations() {
        return evaluator.getRequiredDeclarations();
    }

    @Override
    public void replaceDeclaration(Declaration oldDecl, Declaration newDecl) {
        evaluator.replaceDeclaration( oldDecl, newDecl );
    }

    @Override
    public BitMask getListenedPropertyMask( List<String> settableProperties ) {
        if (evaluator.getReactiveProps() == null) {
            return super.getListenedPropertyMask( settableProperties );
        }
        BitMask mask = getEmptyPropertyReactiveMask(settableProperties.size());
        for (String prop : evaluator.getReactiveProps()) {
            int pos = settableProperties.indexOf(prop);
            if (pos >= 0) { // Ignore not settable properties
                mask = mask.set( pos + PropertySpecificUtil.CUSTOM_BITS_OFFSET );
            } else {
                throw new RuntimeException( "Unknown property: " + prop );
            }
        }
        return mask;
    }

    @Override
    public LambdaConstraint clone() {
        return this;
    }

    @Override
    public boolean isTemporal() {
        throw new UnsupportedOperationException();
    }

    @Override
    public boolean isAllowed(InternalFactHandle handle, InternalWorkingMemory workingMemory) {
        return evaluator.evaluate(handle);
    }

    @Override
    public boolean isAllowedCachedLeft(ContextEntry context, InternalFactHandle handle) {
        return evaluator.evaluate(handle, ((LambdaContextEntry) context).getTuple());
    }

    @Override
    public boolean isAllowedCachedRight(Tuple tuple, ContextEntry context) {
        return evaluator.evaluate(((LambdaContextEntry) context).getHandle(), tuple);
    }

    @Override
    public ContextEntry createContextEntry() {
        return new LambdaContextEntry();
    }

    @Override
    public boolean isUnification() {
        throw new UnsupportedOperationException();

    }

    @Override
    public boolean isIndexable( short nodeType ) {
        return getConstraintType().isIndexableForNode(nodeType);
    }

    @Override
    public IndexUtil.ConstraintType getConstraintType() {
        Index index = evaluator.getIndex();
        if (index != null) {
            switch (index.getConstraintType()) {
                case EQUAL:
                    return IndexUtil.ConstraintType.EQUAL;
                case NOT_EQUAL:
                    return IndexUtil.ConstraintType.NOT_EQUAL;
                case GREATER_THAN:
                    return IndexUtil.ConstraintType.GREATER_THAN;
                case GREATER_OR_EQUAL:
                    return IndexUtil.ConstraintType.GREATER_OR_EQUAL;
                case LESS_THAN:
                    return IndexUtil.ConstraintType.LESS_THAN;
                case LESS_OR_EQUAL:
                    return IndexUtil.ConstraintType.LESS_OR_EQUAL;
                case RANGE:
                    return IndexUtil.ConstraintType.RANGE;
            }
        }
        return IndexUtil.ConstraintType.UNKNOWN;
    }

    @Override
    public FieldValue getField() {
        return field;
    }

    @Override
    public FieldIndex getFieldIndex() {
        throw new UnsupportedOperationException();
    }

    @Override
    public InternalReadAccessor getFieldExtractor() {
        return readAccessor;
    }

    public static class LambdaContextEntry extends MvelConstraint.MvelContextEntry {
        Tuple getTuple() {
            return tuple;
        }
        InternalFactHandle getHandle() {
            return rightHandle;
        }
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) return true;
        if (other == null || getClass() != other.getClass()) return false;
        return evaluator.equals(((LambdaConstraint) other).evaluator);
    }

    @Override
    public int hashCode() {
        return evaluator.hashCode();
    }
}
