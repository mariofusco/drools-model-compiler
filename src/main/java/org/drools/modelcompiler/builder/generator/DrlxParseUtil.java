/*
 * Copyright 2005 JBoss Inc
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.drools.modelcompiler.builder.generator;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.drlx.expr.InlineCastExpr;
import com.github.javaparser.ast.expr.BinaryExpr.Operator;
import com.github.javaparser.ast.expr.CastExpr;
import com.github.javaparser.ast.expr.EnclosedExpr;
import com.github.javaparser.ast.expr.Expression;
import com.github.javaparser.ast.expr.FieldAccessExpr;
import com.github.javaparser.ast.expr.InstanceOfExpr;
import com.github.javaparser.ast.expr.LiteralExpr;
import com.github.javaparser.ast.expr.MethodCallExpr;
import com.github.javaparser.ast.expr.NameExpr;
import com.github.javaparser.ast.expr.ThisExpr;
import com.github.javaparser.ast.type.ClassOrInterfaceType;
import com.github.javaparser.ast.type.ReferenceType;
import org.drools.core.util.ClassUtils;
import org.drools.core.util.index.IndexUtil;
import org.drools.core.util.index.IndexUtil.ConstraintType;
import org.drools.modelcompiler.builder.generator.ModelGenerator.RuleContext;

public class DrlxParseUtil {

    public static IndexUtil.ConstraintType toConstraintType( Operator operator ) {
        switch ( operator ) {
            case EQUALS:
                return ConstraintType.EQUAL;
            case NOT_EQUALS:
                return ConstraintType.NOT_EQUAL;
            case GREATER:
                return ConstraintType.GREATER_THAN;
            case GREATER_EQUALS:
                return ConstraintType.GREATER_OR_EQUAL;
            case LESS:
                return ConstraintType.LESS_THAN;
            case LESS_EQUALS:
                return ConstraintType.LESS_OR_EQUAL;
        }
        throw new UnsupportedOperationException( "Unknown operator " + operator );
    }

    public static TypedExpression toTypedExpression( RuleContext context, Class<?> patternType, Expression drlxExpr,
                                                     Set<String> usedDeclarations, Set<String> reactOnProperties ) {
        
        Class<?> typeCursor = patternType;
        
        if ( drlxExpr instanceof LiteralExpr ) {
            return new TypedExpression( drlxExpr , Optional.empty());
        } else if ( drlxExpr instanceof ThisExpr ) {
            return new TypedExpression( new NameExpr( "_this") , Optional.empty());
        } else if ( drlxExpr instanceof NameExpr ) {
            String name = drlxExpr.toString();
            reactOnProperties.add(name);
            Method accessor = ClassUtils.getAccessor( typeCursor, name );
            Class<?> accessorReturnType = accessor.getReturnType();

            NameExpr _this = new NameExpr("_this");
            MethodCallExpr body = new MethodCallExpr( _this, accessor.getName() );
            
            return new TypedExpression( body, Optional.of( accessorReturnType ));
        } else if ( drlxExpr instanceof FieldAccessExpr ) {
            List<Node> childNodes = drlxExpr.getChildNodes();
            Node firstNode = childNodes.get(0);

            boolean isInLineCast = firstNode instanceof InlineCastExpr;
            if (isInLineCast) {
                InlineCastExpr inlineCast = (InlineCastExpr) firstNode;
                try {
                    typeCursor = context.getPkg().getTypeResolver().resolveType( inlineCast.getType().toString() );
                } catch (ClassNotFoundException e) {
                    throw new RuntimeException( e );
                }
                firstNode = inlineCast.getExpression();

            }

            Expression previous;

            if (firstNode instanceof NameExpr) {
                String firstName = ( (NameExpr) firstNode ).getName().getIdentifier();
                if ( context.declarations.containsKey( firstName ) ) {
                    // do NOT append any reactOnProperties.
                    // because reactOnProperties is referring only to the properties of the type of the pattern, not other declarations properites.
                    usedDeclarations.add( firstName );
                    if (!isInLineCast) {
                        typeCursor = context.declarations.get( firstName );
                    }
                    previous = new NameExpr( firstName );
                } else {
                    Method firstAccessor = ClassUtils.getAccessor( typeCursor, firstName );
                    if (firstAccessor != null) {
                        reactOnProperties.add( firstName );
                        typeCursor = firstAccessor.getReturnType();
                        previous = new MethodCallExpr( new NameExpr( "_this" ), firstAccessor.getName() );
                    } else {
                        throw new UnsupportedOperationException("firstNode I don't know about");
                        // TODO would it be fine to assume is a global if it's not in the declarations and not the first accesssor in a chain?
                    }
                }

            } else if (firstNode instanceof ThisExpr) {
                previous = new NameExpr( "_this" );
                if ( childNodes.size() > 1 && childNodes.get(1) instanceof NameExpr ) {
                    NameExpr child0 = (NameExpr) childNodes.get(1);
                    reactOnProperties.add( child0.getName().getIdentifier() );
                }

            } else if (firstNode instanceof FieldAccessExpr && ( (FieldAccessExpr) firstNode ).getScope() instanceof ThisExpr) {
                String firstName = ( (FieldAccessExpr) firstNode ).getName().getIdentifier();
                Method firstAccessor = ClassUtils.getAccessor( typeCursor, firstName );
                if (firstAccessor != null) {
                    reactOnProperties.add( firstName );
                    typeCursor = firstAccessor.getReturnType();
                    previous = new MethodCallExpr( new NameExpr( "_this" ), firstAccessor.getName() );
                } else {
                    throw new UnsupportedOperationException("firstNode I don't know about");
                    // TODO would it be fine to assume is a global if it's not in the declarations and not the first accesssor in a chain?
                }
            } else {
                throw new UnsupportedOperationException( "Unknown node: " + firstNode );
            }

            childNodes = drlxExpr.getChildNodes().subList( 1, drlxExpr.getChildNodes().size() );

            TypedExpression typedExpression = new TypedExpression();
            if (isInLineCast) {
                ReferenceType castType = new ClassOrInterfaceType( typeCursor.getName() );
                typedExpression.setPrefixExpression( new InstanceOfExpr( previous, castType ) );
                previous = new EnclosedExpr( new CastExpr( castType, previous ) );
            }

            for ( Node part : childNodes ) {
                String field = part.toString();
                Method accessor = ClassUtils.getAccessor( typeCursor, field );
                if (accessor == null) {
                    throw new IllegalStateException( "Unknown field '" + field + "' on type " + typeCursor );
                }
                typeCursor = accessor.getReturnType();
                previous = new MethodCallExpr( previous, accessor.getName() );
            }

            return typedExpression.setExpression( previous ).setType( Optional.of( typeCursor ) );
        } else {
            // TODO the below should not be needed anymore...
            drlxExpr.getChildNodes();
            String expression = drlxExpr.toString();
            String[] parts = expression.split("\\.");
            StringBuilder telescoping = new StringBuilder();
            boolean implicitThis = true;
            
            for ( int idx = 0; idx < parts.length ; idx++ ) {
                String part = parts[idx];
                boolean isGlobal = false;
                if ( isGlobal ) {
                    implicitThis = false;
                    telescoping.append( part );
                } else if ( idx == 0 && context.declarations.containsKey(part) ) {
                    implicitThis = false;
                    usedDeclarations.add( part );
                    telescoping.append( part );
                } else {
                    if ( ( idx == 0 && implicitThis ) || ( idx == 1 && implicitThis == false ) ) {
                        reactOnProperties.add(part);
                    }
                    Method accessor = ClassUtils.getAccessor( typeCursor, part );
                    typeCursor = accessor.getReturnType();
                    telescoping.append( "." + accessor.getName() + "()" );
                }
            }
            return new TypedExpression( implicitThis ? "_this" + telescoping.toString() : telescoping.toString(), Optional.of( typeCursor ));
        }
    }
}
